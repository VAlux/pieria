# Embedded sqlite-vec extension

The Pieria daemon performs vector search through the [`sqlite-vec`](https://github.com/asg017/sqlite-vec)
loadable extension. Because the xerial `sqlite-jdbc` driver loads its **own** SQLite engine as a
runtime JNI library, sqlite-vec cannot be statically linked into the GraalVM native image — it must
be a loadable `vec0` extension that the daemon loads by absolute path at startup.

To keep the distribution a single self-contained artifact, the build **embeds** the platform
extensions as classpath resources (`native/<os>-<arch>/vec0.<suffix>`) into both the native image
and the boot jar. At startup `VecExtensionResolver` extracts the binary matching the host OS+arch to
the app-data runtime directory and `DataSourceConfig` loads it. No sidecar file ships next to the
binary.

## Layout

Drop the platform extensions into per-architecture directories before building:

```
packaging/native/macos-aarch64/vec0.dylib    # Apple Silicon
packaging/native/macos-x86_64/vec0.dylib     # Intel Mac
packaging/native/linux-aarch64/vec0.so
packaging/native/linux-x86_64/vec0.so
packaging/native/windows-x86_64/vec0.dll
```

Arch matters: Apple Silicon and Intel macs share the `.dylib` suffix but need different binaries, so
the directory — not the filename — carries the architecture. The directory name maps to the JVM's
`os.arch` (`aarch64`/`arm64` ⇒ `aarch64`; `amd64`/`x86_64` ⇒ `x86_64`).

The binaries are **git-ignored** — fetch them from the sqlite-vec releases (currently **v0.1.9**) at
build/release time. The release archives are named `sqlite-vec-<ver>-loadable-<os>-<arch>.tar.gz`
and each contains a single `vec0.{dylib,so,dll}`. Example for one platform:

```bash
mkdir -p packaging/native/macos-aarch64
curl -sL https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-vec-0.1.9-loadable-macos-aarch64.tar.gz \
  | tar -xz -C packaging/native/macos-aarch64 vec0.dylib
```

A native image only needs the host OS+arch; the boot jar can carry several so one jar runs anywhere.
If a build supplies no extension for the host platform, it still succeeds and the daemon degrades to
FTS + keyed lookup at runtime (with a warning).

## Runtime resolution order

At startup the daemon resolves the extension in this order (first match wins):

1. `pieria.vec.extension-path` (config property) — explicit override
2. `PIERIA_VEC_EXTENSION` (environment variable)
3. `vec0.<suffix>` beside the running binary/jar or a sibling `lib/` — ops patch without rebuild
4. the embedded `native/<os>-<arch>/vec0.<suffix>` resource, extracted to the runtime dir
5. otherwise: the OS extension search path (bare `vec0`), then vector search is disabled

# Embedded Tree-sitter libraries

The code-index parser (Phase 13) uses [`jtreesitter`](https://github.com/tree-sitter/java-tree-sitter),
an FFM/Panama binding, which needs a **core** `libtree-sitter` runtime and per-language **grammar**
libraries. Like sqlite-vec they are `dlopen`'d (cannot be static-linked), so the build embeds them as
classpath resources
(`native/<os>-<arch>/{libtree-sitter,tree-sitter-*}.<suffix>`). At startup `TreeSitterLibraryResolver`
extracts the host-platform ones to the runtime dir and `TreeSitterEngine` loads them by absolute path.

Native distribution builds use pinned reproducible sources:

- Tree-sitter core 0.25.10
- Java grammar 0.23.5
- JavaScript grammar 0.25.0
- TypeScript grammar 0.23.2 (both `tree_sitter_typescript` and `tree_sitter_tsx` in one library)
- SCSS grammar 1.0.0
- Kotlin grammar 1.1.0
- Scala grammar 0.26.0
- Python grammar 0.25.0
- Go grammar 0.25.0
- Rust grammar 0.24.2
- Ruby grammar 0.23.1
- PHP grammar 0.24.2
- C# grammar 0.23.5
- C grammar 0.24.2
- C++ grammar 0.23.4
- Swift grammar 0.7.3 (published crate sources, because the repository omits generated `parser.c`)

Missing libraries degrade gracefully per pack. In particular, a missing TypeScript library disables
both TypeScript and TSX without affecting the other packs.

## Build

The Gradle task builds every library for the host OS and architecture:

```bash
./gradlew :daemon:buildTreeSitterLibraries
```

Outputs are written to
`modules/daemon/build/generated/treesitter-native/<os>-<arch>/`. The task requires `git` plus `cc`
on macOS/Linux or `cl.exe` on Windows; set `CC` to select another compatible compiler. External
scanners are included where required, and TypeScript plus TSX are linked into one library.

`nativeCompile`, `nativeDist`, and `deployLocal` depend on this task and stage its outputs ahead of
any prebuilt fallback under `packaging/native/`. The release workflow invokes the same task, runs all
real grammar fixtures against its outputs, and then builds the distribution, ensuring local installs
and release artifacts carry the same pack set.

The language packs cover every extension in `DiscoveryConfig.DEFAULT_SOURCE_EXTENSIONS`: Java,
Kotlin, Scala, JavaScript/JSX, TypeScript/TSX, SCSS, Python, Go, Rust, Ruby, PHP, C#, C, C++, and
Swift. Plain CSS and indented Sass are not supported. After installing upgraded packs, run
`pieria onboard --source-code --reindex`.

## Runtime resolution

Language grammar locations are intentionally not configurable: the daemon always extracts them
from its embedded `native/<os>-<arch>/tree-sitter-*.<suffix>` resources. There are no pack-specific
configuration properties or environment variables. If an embedded grammar is absent, only that
language is skipped.

The core runtime retains its operational fallback order: `pieria.treesitter.core-path`,
`PIERIA_TREESITTER_CORE`, a library beside the binary/jar (or sibling `lib/`), then the embedded
resource. If the core is unavailable, all symbol parsing is disabled.

Set `pieria.treesitter.enabled=false` to disable Tree-sitter parsing entirely.
