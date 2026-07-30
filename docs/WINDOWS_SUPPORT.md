# Windows Support

**Status as of 2026-07-30:** Pieria ships for macOS only. Nothing here is an architectural
rewrite — the OS abstractions are already in place and already have real Windows branches. What is
missing is a set of unfinished ends, listed below in the order they should be tackled.

## What already works

Do not redo any of this:

| Component | State |
|---|---|
| `OsFamily` (`modules/shared/.../tools/os/`) | Detects `WINDOWS`; used throughout. |
| `AppDirs` | Full Windows branch — `%APPDATA%\Pieria`, `%LOCALAPPDATA%\Pieria\logs`. |
| `InstallHome` / `PathResolver` | Resolves `%LOCALAPPDATA%\Pieria`, appends `.exe`. |
| `NativeResourceExtractor` | `platformKey()` yields `windows-x86_64`; `librarySuffix()` yields `dll`. |
| `packaging/install.ps1` | Essentially complete — download, checksum, expand, PATH, logon task. |
| `packaging/native/windows-x86_64/vec0.dll` | The sqlite-vec extension is present and current. |
| Tree-sitter Gradle task | Has an `cl.exe /LD` branch (untested — see blocker 2). |
| Release workflow | Has the MSVC setup step and zip packaging step (matrix entry disabled — see blocker 1). |

## Blockers, in dependency order

### 1. CI never builds Windows artifacts

`.github/workflows/release.yml` has the `windows-latest` **and** `ubuntu-latest` matrix entries
commented out. Only `macos-14` builds. Consequently `pieria-windows-x86_64.zip` is never published,
and `packaging/install.ps1` fails at its very first step trying to download it.

The zip packaging step, the `ilammy/msvc-dev-cmd@v1` MSVC setup step, and the checksum flow are all
already written and waiting. This is uncomment-and-make-green — but "make green" is where blocker 2
lives, so budget for that.

### 2. Tree-sitter native libraries have never been built on Windows

`modules/daemon/build.gradle.kts` has an MSVC branch (`cl.exe /nologo /LD /O2 ... /Fe:`) that has
never been executed.

**The likely failure: MSVC does not export symbols from a DLL by default.** Tree-sitter's core
`lib/src/lib.c` carries no `__declspec(dllexport)` annotations, so the resulting
`libtree-sitter.dll` will load but expose no symbols, and the FFM `SymbolLookup` in
`TreeSitterEngine` will find nothing. Fixes, in order of preference:

1. Compile with `-DTREE_SITTER_API=__declspec(dllexport)` if the pinned version honours such a macro.
2. Generate a `.def` module-definition file and pass `/DEF:`.
3. Build the core as a static library and link it into each grammar DLL.

Language grammars are lower risk — recent grammar sources annotate `TS_PUBLIC` — but verify rather
than assume.

Secondary: `packaging/native/windows-x86_64/` contains only `vec0.dll`, no prebuilt Tree-sitter
libraries. Any Windows JVM test run that skips `buildTreeSitterLibraries` silently degrades code
parsing to disabled — `embedTreeSitterLibraries` only logs a warning.

### 3. Harness hooks were POSIX shell — **being fixed now**

The eleven `harness/**/*.sh` scripts depended on `sh`, `curl`, and `python3`, and the installers
wired them in as the literal string `sh <path>/script.sh`. Stock Windows has none of that.

This is being resolved on the `cli-hooks` branch by absorbing the logic into the CLI as a hidden
`pieria hook` command group. See `docs/superpowers/specs/2026-07-30-cli-hooks-design.md`.

**The constraint that work established, which any future hook wiring must preserve:** the command
string stored in harness config may contain **only literals** — an absolute binary path plus fixed
subcommand names. A parameterised form like `pieria hook ingest --transcript "$CLAUDE_TRANSCRIPT_PATH"`
cannot work, because `$VAR` expansion requires a shell and `cmd.exe` uses `%VAR%`. The CLI therefore
reads the harness's environment variables itself.

### 4. `pieria update` is a stub, and its swap strategy is Unix-only

`WindowsPlatform.harden()` and `WindowsPlatform.extractDistributionArchive()` both throw;
`supported()` returns `false`, so `pieria update` refuses to run and points at `install.ps1`.

Filling those in is the easy part:

- `harden()` is a legitimate no-op — no Gatekeeper, no quarantine xattr.
- Extraction needs **zip**, not `tar.gz`. The `Platform` interface is currently specified for
  `.tar.gz`; widen the contract and implement with `java.util.zip`. A pure-Java reader would also let
  `MacOsPlatform` drop its shell-out to `tar`, which its own javadoc already flags as temporary.

**The hard part is `BinarySwapper`.** Its javadoc premise is explicitly the Unix inode trick:
`ATOMIC_MOVE` over a running binary leaves live processes on the old inode. Windows holds an
exclusive lock on running image files, so moving over a running `pieria-gateway.exe` (held open by a
live Claude Code session) or over `pieria.exe` (the updater updating itself) fails with
`AccessDeniedException`.

The Windows pattern is rename-then-replace: Windows *does* permit renaming a running executable, so
move the target to `<name>.old`, move the new binary in, and sweep `.old` on next launch. This is a
genuine design addition, not a fill-in-the-blank.

### 5. Daemon lifecycle has no Windows path, and two mechanisms disagree

`DaemonProcessController.detectService()` returns `null` on Windows by design, so
`pieria daemon start/stop/restart` always falls through to PID-file spawn.

- `packaging/install.ps1` registers a **logon Scheduled Task**, while
  `packaging/service/windows/pieria-service.ps1` refuses a real SCM install without a WinSW/NSSM
  wrapper. Two divergent mechanisms, neither auto-detected by the CLI, so `pieria daemon restart`
  will not drive whatever the installer actually set up. **Pick one** — the Scheduled Task is the
  pragmatic choice since it needs no third-party wrapper — and teach `detectService()`,
  `startViaService()`, and `stopViaService()` to use `Start-ScheduledTask` / `Stop-ScheduledTask`.
- The spawn fallback uses a plain `ProcessBuilder`. On Windows the child is tied to the console and
  dies when the shell closes; there is no direct Java equivalent of the detach this relies on.
  Likely needs a `cmd /c start /b` wrapper. Verify before assuming.
- `uid()` shells out to `id -u`. Only reached from the launchd path today, so harmless, but it should
  be guarded rather than left as a latent trap.

### 6. Mechanical items

- **`ServiceScriptTests`** executes `packaging/service/{macos,linux}/*.sh` through `sh` and will fail
  on a Windows runner. Needs `@EnabledOnOs`. `pieria-service.ps1` is currently untested altogether
  and deserves an equivalent dry-run assertion.
- **Backslash escaping in generated config.** `JsonConfigMerger` and `TomlConfigMerger` will write
  paths like `C:\Users\...\bin\pieria-gateway.exe` into `.mcp.json` and `.codex/config.toml`. Jackson
  escapes JSON correctly; TOML basic strings also require `\\` and deserve a pinning test.
- **Paths containing spaces.** `C:\Users\First Last\...` is the common case, not the exception. It
  must survive the PowerShell argument quoting, the JDBC URL, and any generated command string. The
  `cli-hooks` work adds `HookCommandLine` for the command-string half of this.
- **`.gitattributes` does not pin `*.sh` to LF.** A Windows checkout with `autocrlf=true` gives every
  shell script CRLF endings, breaking them even under Git Bash or WSL. Being fixed as part of the
  `cli-hooks` branch; `packaging/**/*.sh` remains after that work, so the pin still matters.

### 7. Runtime verification nobody has done yet

All of these *should* work. None is confirmed:

- GraalVM `nativeCompile` on Windows with the FFM flags (`--enable-native-access=ALL-UNNAMED`, shared
  `Arena` support).
- xerial sqlite-jdbc's own native-library extraction on Windows.
- `load_extension` against a Windows path for `vec0.dll`.
- SQLite file-locking semantics under the single-writer daemon model.

### 8. Documentation

`README.md` scopes quick install to "macOS / Linux" and tells non-macOS users to re-run the installer
instead of using `pieria update`. Both need updating once the above lands.

## Suggested order

1. **Blockers 1 → 2.** Getting a Windows CI job to produce working binaries is the gate everything
   else depends on, and blocker 2 is where the genuine unknown lives. Do not estimate the rest until
   the Tree-sitter DLL question is settled.
2. **Blockers 5 and 3.** These determine whether an installed Pieria is *usable* rather than merely
   present. Blocker 3 is already in flight.
3. **Blocker 4.** `pieria update` can land last — re-running `install.ps1` is a working fallback in
   the meantime.
4. **Blockers 6–8** alongside whichever of the above they touch.

## A note on Linux

The `ubuntu-latest` matrix entry is commented out too, and `LinuxPlatform` is the same stub as
`WindowsPlatform` (`supported()` returns `false`, both swap methods throw). Most of the work above —
CI, `pieria update`, the `Platform` implementations — covers Linux at the same time. Linux needs no
equivalent of the Tree-sitter MSVC problem and already has `systemd --user` support in
`DaemonProcessController`, so it is strictly the cheaper of the two targets and worth doing first if
the goal is simply "more than one platform."
