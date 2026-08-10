# Windows Support

**Status as of 2026-08-10:** blockers 1–3 are **done** — CI builds and tests Windows and Linux
artifacts, the Tree-sitter MSVC export problem is solved, and the POSIX harness hooks have been
absorbed into the CLI. Blocker 7 is now instrumented: the sqlite-vec extension is mandatory in tests,
the native binary is smoke-tested, and test reports are published. Blockers 4–6 and 8 remain. Nothing
left here is an architectural rewrite; the OS abstractions are already in place and already have real
Windows branches.

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
| Tree-sitter Gradle task | MSVC branch builds and exports correctly; grammar fixtures pass on the Windows runner. |
| Release workflow | `windows-x86_64` and `linux-x86_64` matrix legs are enabled and green. |
| `pieria hook` CLI group | Replaces the former POSIX shell hooks; no `sh`/`curl`/`python3` dependency. |
| `.gitattributes` | Pins `*.sh`/`*.txt` to LF and `*.bat` to CRLF. |

## Blockers, in dependency order

### 1. CI never builds Windows artifacts — **DONE**

`.github/workflows/release.yml` now enables all three matrix legs: `macos-14`, `ubuntu-latest`
(`linux-x86_64`, tar), and `windows-latest` (`windows-x86_64`, zip). The `ilammy/msvc-dev-cmd@v1`
MSVC setup step, the `Compress-Archive` zip step, and the checksum flow are all wired.

Verified green: run `30824530630` (2026-08-03) shows `build windows-x86_64` **success** and
`build linux-x86_64` **success**. The macOS leg was the failure there — a memory-starved
3-core runner, addressed separately by `PIERIA_CONSTRAINED_NATIVE_BUILD` and the larger-runner work.

### 2. Tree-sitter native libraries have never been built on Windows — **DONE**

Solved via option 2 (`.def` module-definition file), implemented as
`compileWindowsWithAutoExports` in `modules/daemon/build.gradle.kts`. The diagnosis held: the
pinned Tree-sitter core guards its exports for GCC/Clang only (`#pragma GCC visibility push` in
`api.h`; `alloc.h` blanks `TS_PUBLIC` on `_WIN32`), so a plain `cl.exe /LD` produced a DLL that
loaded but exported nothing.

The build now compiles the core to `.obj` files, runs `dumpbin /symbols` over them, filters
`ts_*`/`tree_sitter_*` externals, and generates a `.def` — replicating CMake's
`WINDOWS_EXPORT_ALL_SYMBOLS` — rather than patching freshly cloned upstream headers. It fails loudly
if the symbol scrape comes back empty. Grammar DLLs need none of this, as predicted: generated
`parser.c` carries its own `__declspec(dllexport)`, so they take the plain `/LD` path.

Two incidental Windows-only snags were fixed along the way: the link is routed through `cl.exe`
with `/link /DEF:` because Git Bash's coreutils `link` shadows MSVC's `link.exe` under the
workflow's `shell: bash`, and `LC_ALL=C` pins `dumpbin`'s output format for the symbol regex.

CI proves it end to end — the workflow runs `:daemon:buildTreeSitterLibraries` and then the full
test suite with `PIERIA_TEST_NATIVE_DIR` pointed at the freshly built libraries, no packs skipped.

Residual (minor): `packaging/native/windows-x86_64/` still contains only `vec0.dll`, no checked-in
prebuilt Tree-sitter libraries — unlike `macos-aarch64`. CI builds them, so release artifacts are
fine, but a local Windows JVM test run that skips `buildTreeSitterLibraries` still degrades code
parsing to disabled with only a warning from `embedTreeSitterLibraries`.

### 3. Harness hooks were POSIX shell — **DONE**

The eleven `harness/**/*.sh` scripts depended on `sh`, `curl`, and `python3`, and the installers
wired them in as the literal string `sh <path>/script.sh`. Stock Windows has none of that.

Resolved: no `.sh` files remain under `harness/`. The logic lives in the CLI as a hidden
`pieria hook` command group (`modules/cli/.../command/hook/` — per-harness commands for Claude Code,
Codex, and OpenCode). See `docs/superpowers/specs/2026-07-30-cli-hooks-design.md`.

**The constraint that work established, which any future hook wiring must preserve:** the command
string stored in harness config may contain **only literals** — an absolute binary path plus fixed
subcommand names. A parameterised form like `pieria hook ingest --transcript "$TRANSCRIPT_PATH"`
cannot work, because `$VAR` expansion requires a shell and `cmd.exe` uses `%VAR%`. The CLI therefore
reads the harness's own hook input itself: the JSON payload on stdin (`transcript_path`,
`session_id`), falling back to the harness's environment variables where it exposes them.

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
`pieria start` / `pieria stop` / `pieria restart` always fall through to PID-file spawn.

- `packaging/install.ps1` registers a **logon Scheduled Task**, while
  `packaging/service/windows/pieria-service.ps1` refuses a real SCM install without a WinSW/NSSM
  wrapper. Two divergent mechanisms, neither auto-detected by the CLI, so `pieria restart`
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
  `cli-hooks` work added `HookCommandLine` for the command-string half of this.
- ~~**`.gitattributes` does not pin `*.sh` to LF.**~~ **Done** — `.gitattributes` now pins `*.sh`
  and `*.txt` to LF, `*.bat` to CRLF, and `gradlew` to LF. This still matters for
  `packaging/**/*.sh`, which outlived the hook rewrite.

### 7. Runtime verification nobody has done yet — **now instrumented**

A green CI leg used to prove far less than it looked. Every native dependency soft-fails by design:
`DataSourceConfig` logs a warning and disables vector search when `load_extension` fails, and the
vector tests were guarded by `assumeTrue(store.isVectorSearchAvailable())`. Gradle prints no skip
counts, and no test report was published — so "passed" and "never ran" were indistinguishable. That
was not hypothetical: the suite only ever tried to load the extension by *bare name*, which never
resolves from Gradle's working directory, so the vector assertions had been silently skipping on
**every** platform, macOS included.

Three changes close the gap:

- **The extension is mandatory.** `VecExtension.requireLoaded` (daemon test-support) fails instead of
  skipping, and resolves the extension the way the daemon does — from the embedded
  `native/<os>-<arch>/vec0.*` classpath resource. `PIERIA_ALLOW_MISSING_VEC_EXTENSION=1` downgrades
  it to a skip for offline work on a fresh clone, where `packaging/native/**` is git-ignored and
  therefore empty. CI must never set it.
- **The native binary is smoke-tested.** `.github/scripts/smoke-native.sh` runs the built
  `pieria-daemon`, waits for `/pieria-status`, and asserts `vectorSearch: true` — a new field on the
  status endpoint, since nothing previously exposed whether vector search had actually come up.
- **Test reports are uploaded** per matrix leg, so skips are visible after the fact.

Status of the original list: the first three are covered by the smoke step the next time the workflow
runs — GraalVM `nativeCompile` with the FFM flags is already proven by `nativeDist` producing the
green leg's zip, and the smoke step adds xerial's native extraction plus `load_extension` against an
extracted `vec0.dll` path. SQLite file-locking under the single-writer daemon model remains
unverified; the smoke test starts one daemon and does not exercise contention.

Note the same soft-fail shape still applies to Tree-sitter: `TreeSitterLanguagePackTests` guards on
`engine.supports(<language>)` and `embedTreeSitterLibraries` only warns. Locally 15 of its 16 tests
skip. CI builds the grammars first, so its numbers should be better — but until the uploaded reports
are read, that is an assumption, not a fact.

### 8. Documentation

`README.md` scopes quick install to "macOS / Linux" and tells non-macOS users to re-run the installer
instead of using `pieria update`. Both need updating once the above lands.

## Suggested order

~~1. **Blockers 1 → 2.**~~ Done — CI produces working Windows and Linux binaries, and the
Tree-sitter DLL question is settled.

1. **Blocker 5.** Now the top item: it determines whether an installed Pieria is *usable* rather
   than merely present. (Its sibling, blocker 3, is done.)
2. **Blocker 4.** `pieria update` can land last — re-running `install.ps1` is a working fallback in
   the meantime.
3. **Blockers 6 and 8** alongside whichever of the above they touch. Blocker 7 is now instrumented
   rather than open — read the first uploaded test report and smoke-step output to confirm.

## A note on Linux

The `ubuntu-latest` matrix entry is enabled and green, but `LinuxPlatform` is still the same stub as
`WindowsPlatform` (`supported()` returns `false`, both swap methods throw). The remaining work above
— `pieria update` and the `Platform` implementations — covers Linux at the same time. Linux needed no
equivalent of the Tree-sitter MSVC problem and already has `systemd --user` support in
`DaemonProcessController`, so it remains the cheaper of the two targets: with CI already publishing
`pieria-linux-x86_64.tar.gz`, only blocker 4's `Platform` fill-in stands between it and a working
`pieria update`.
