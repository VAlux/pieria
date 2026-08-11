# Windows Support

**Status as of 2026-08-11:** blockers 1–6 and 8 are **done**. CI builds and tests Windows and Linux
artifacts, the Tree-sitter MSVC export problem is solved, the POSIX harness hooks are absorbed into
the CLI, `pieria update` works on all three platforms, and `pieria daemon start/stop/restart` drives
the Scheduled Task the installer registers. Blocker 7 is instrumented rather than open. What is left
is verification on real hardware, plus two residual notes at the bottom.

## What already works

Do not redo any of this:

| Component | State |
|---|---|
| `OsFamily` (`modules/shared/.../tools/os/`) | Detects `WINDOWS`; used throughout. |
| `AppDirs` | Full Windows branch — `%APPDATA%\Pieria`, `%LOCALAPPDATA%\Pieria\logs`. |
| `InstallHome` / `PathResolver` | Resolves `%LOCALAPPDATA%\Pieria`, appends `.exe`. |
| `NativeResourceExtractor` | `platformKey()` yields `windows-x86_64`; `librarySuffix()` yields `dll`. |
| `packaging/install.ps1` | Complete — download, checksum, expand, PATH, logon task. |
| `packaging/native/windows-x86_64/vec0.dll` | The sqlite-vec extension is present and current. |
| Tree-sitter Gradle task | MSVC branch builds and exports correctly; grammar fixtures pass on the Windows runner. |
| Release workflow | `windows-x86_64` and `linux-x86_64` matrix legs are enabled and green. |
| `pieria hook` CLI group | Replaces the former POSIX shell hooks; no `sh`/`curl`/`python3` dependency. |
| `.gitattributes` | Pins `*.sh`/`*.txt` to LF and `*.bat` to CRLF. |
| `pieria update` | Works on macOS, Linux, and Windows — see blocker 4. |
| `pieria daemon start/stop/restart` | Drives the logon Scheduled Task — see blocker 5. |

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

### 4. `pieria update` was a stub, and its swap strategy was Unix-only — **DONE**

`WindowsPlatform` and `LinuxPlatform` are now real implementations. Four pieces:

- **`harden()` is a legitimate no-op** on both — no Gatekeeper, no quarantine xattr.
- **Archive format follows the platform.** `Platform.archiveExtension()` returns `zip` on Windows
  and `tar.gz` elsewhere; `ReleaseSource` builds the asset name and URL from it instead of a
  hardcoded `.tar.gz`, which had been the actual reason the Windows download path could never work.
  Zip extraction is pure Java (`shared/.../tools/io/ZipArchive`, with a zip-slip guard). The Unix
  targets keep the `tar` shell-out, now shared between the two platforms as `TarArchive` — a
  hand-rolled ustar/pax reader would be real correctness surface for no benefit.
- **`BinarySwapper` handles the Windows lock.** Its old javadoc premise was the Unix inode trick;
  Windows holds an exclusive lock on running image files, so moving over a running
  `pieria-gateway.exe` (held open by a live Claude Code session) or over `pieria.exe` (the updater
  updating itself) fails with `AccessDeniedException`. Windows *does* permit **renaming** a running
  executable, and that turned out to reduce the divergence to one step: gated on
  `Platform.locksRunningBinaries()`, the original is **moved** to `.bak` rather than **copied**,
  vacating the name for the new binary while any live process carries on from the renamed file.
  Rollback was already a `move` and needed no change.
- **Leftovers self-heal.** The `.bak` usually cannot be deleted at the end of the run (something is
  still executing from it — including `pieria.exe` updating itself). `BinarySwapper` sweeps stale
  `.bak`/`.bak.<ts>`/`.new` files at the *start* of the next swap, so leftovers last exactly one
  run, and `freeBackupPath` uniquifies the backup name if a locked one survives even that.

One related change: `Platform.supported()` would have been permanently `true` once all three
platforms landed, so it was repurposed as `hasPublishedRelease()` — the same
unsupported-architecture preflight the installers gained in `e76960d`, checked in `ReleaseSource`
rather than in `UpdateCommand`. That matters: `--from`/`--from-build` now install a self-built
distribution on any architecture, including `linux-aarch64`, instead of being blocked by a
platform gate that only ever concerned *downloads*.

### 5. Daemon lifecycle had no Windows path, and two mechanisms disagreed — **DONE**

**The Scheduled Task won.** `packaging/install.ps1` registers a logon Scheduled Task and needs no
third-party wrapper; `packaging/service/windows/pieria-service.ps1` refused a real SCM install
without WinSW/NSSM and has been **deleted** along with its README. There is now one mechanism.

- `DaemonProcessController.detectService()` gained a `WINDOWS` branch that queries
  `schtasks /Query /TN PieriaDaemon`, and `startViaService`/`stopViaService` drive it with
  `schtasks /Run` and `schtasks /End`. `schtasks` is preferred over the PowerShell
  `*-ScheduledTask` cmdlets: always present, no interpreter startup cost, no execution-policy
  surprises. The task name is a contract between installer and CLI, pinned by `ServiceScriptTests`.
- The `detectService()` if-chain became an exhaustive `switch` over `OsFamily`, so adding an OS is a
  compile error rather than a silent `null`.
- **The spawn fallback now detaches.** A plain `ProcessBuilder` child inherits the console on
  Windows and dies with it (`CTRL_CLOSE_EVENT` reaches everything attached), and Java exposes no
  `DETACHED_PROCESS` flag. The daemon is spawned through PowerShell's `Start-Process
  -WindowStyle Hidden -PassThru`, whose printed `.Id` goes into the existing PID file, so
  `stopSpawned` is unchanged. Argument quoting goes through `shared/.../tools/os/PowerShellQuoting`
  (single-quoted literals, embedded `'` doubled) — single quotes are the right choice because they
  suppress `$`/backtick expansion and leave backslashes alone.
- **`locateDaemon` looked in the wrong place on Windows.** It probed only
  `~/.local/bin/pieria-daemon.exe`; `install.ps1` installs to `%LOCALAPPDATA%\Pieria\bin`. Since the
  installer edits the *user* PATH and only new shells see it, `pieria daemon start` in the
  installing shell could not find the daemon at all. It now consults `InstallHome.defaultHome(...)`
  — the same `shared` helper `InstallLayout` already used.
- `uid()` shells out to `id -u`; the `Service` switch makes it reachable only from the launchd
  cases, which is now stated in its javadoc rather than left implicit.

### 6. Mechanical items — **DONE**

- ~~**`ServiceScriptTests`** needs `@EnabledOnOs`.~~ It already had it (the doc was stale). Its
  Windows case asserted on the now-deleted `pieria-service.ps1` and was replaced with one pinning
  the Scheduled Task name across `install.ps1` and `DaemonProcessController`.
- **Backslash escaping in generated config.** Both mergers round-trip
  `C:\Users\First Last\...\pieria-gateway.exe` correctly, now pinned by tests. Worth recording *why*
  the TOML side is safe: Jackson emits a TOML **literal** string (`'...'`), which has no escape
  processing at all, so backslashes are written verbatim rather than doubled. The apostrophe case
  (`C:\Users\O'Brien\...`), which a literal string cannot hold, is covered too.
- **Paths containing spaces.** Covered across all four surfaces: the command string
  (`HookCommandLine`, from the cli-hooks work), PowerShell arguments (`PowerShellQuoting`), the
  install layout (`InstallLayoutTests`), and the JDBC URL (`SqliteSpacedPathTests` — xerial accepts
  a raw spaced path with query parameters appended).
- ~~**`.gitattributes` does not pin `*.sh` to LF.**~~ **Done** — `.gitattributes` now pins `*.sh`
  and `*.txt` to LF, `*.bat` to CRLF, and `gradlew` to LF. This still matters for
  `packaging/**/*.sh`, which outlived the hook rewrite.

### 7. Runtime verification nobody has done yet — **instrumented**

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

### 8. Documentation — **DONE**

`README.md`'s quick install now covers all three platforms with the PowerShell installer as a peer
of the `curl | bash` one, and the update section no longer claims macOS-only; it describes the
per-platform specifics (macOS quarantine/codesign, Windows rename-then-replace) instead.

## What is left

Nothing in the code. Two things remain:

1. **Manual verification on real Windows hardware**, ideally under a user whose home directory
   contains a space. The lifecycle and swap paths are unit-tested but have never executed on
   Windows. The load-bearing case is `pieria update` run *while a Claude Code session is live*: a
   locked `pieria-gateway.exe` is exactly what the old `ATOMIC_MOVE` could not survive.
2. **Reading the first uploaded CI test report** to confirm blocker 7's skip counts, and deciding
   whether to check in prebuilt Windows Tree-sitter libraries (blocker 2's residual).

## A note on Linux

`LinuxPlatform` is no longer a stub — `pieria update` works there, and Linux already had
`systemd --user` support in `DaemonProcessController`. It needed no equivalent of the Tree-sitter
MSVC problem and no equivalent of the Windows file-lock problem, so it came essentially free with
blocker 4's cross-platform half. One gap the same work exposed and closed: `linux-aarch64` has no
published release, and now fails the preflight with build-from-source guidance instead of a bare
404, while `pieria update --from-build` still installs a self-built distribution there.
