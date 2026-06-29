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
an FFM/Panama binding, which needs two native shared libraries reached at runtime: the **core**
`libtree-sitter` runtime and a per-language **grammar** (e.g. `tree-sitter-java`). Like sqlite-vec they
are `dlopen`'d (cannot be static-linked), so the build embeds them as classpath resources
(`native/<os>-<arch>/{libtree-sitter,tree-sitter-java}.<suffix>`). At startup `TreeSitterLibraryResolver`
extracts the host-platform ones to the runtime dir and `TreeSitterEngine` loads them by absolute path.

These binaries are **git-ignored** too. Keep the core and grammar on the **same tree-sitter minor line**
as the `jtreesitter` dependency in `modules/daemon/build.gradle.kts` (currently the **0.25** line).
Missing libraries degrade gracefully — that language (or all symbol parsing) is skipped with a warning.

## Build (macOS aarch64 example)

```bash
DEST=packaging/native/macos-aarch64
# Core runtime: from Homebrew (0.25.x), or build libtree-sitter from source.
cp "$(brew --prefix tree-sitter)/lib/libtree-sitter.dylib" "$DEST/libtree-sitter.dylib"

# Java grammar: compile the checked-in parser.c into a shared lib exporting tree_sitter_java().
git clone --depth 1 https://github.com/tree-sitter/tree-sitter-java /tmp/ts-java
clang -shared -fPIC -O2 -I /tmp/ts-java/src -o "$DEST/tree-sitter-java.dylib" /tmp/ts-java/src/parser.c

chmod 644 "$DEST"/{libtree-sitter,tree-sitter-java}.dylib   # ensure the build can re-stage them
```

Linux uses `.so` (build with `cc -shared -fPIC`), Windows `.dll`. A grammar with an external scanner
also needs `src/scanner.c` on the compile line; tree-sitter-java has none.

## Runtime resolution order (per library)

1. `pieria.treesitter.core-path` / `pieria.treesitter.java-grammar-path` (config) — explicit override
2. `PIERIA_TREESITTER_CORE` / `PIERIA_TREESITTER_JAVA_GRAMMAR` (environment variables)
3. the library beside the running binary/jar or a sibling `lib/`
4. the embedded `native/<os>-<arch>/<lib>.<suffix>` resource, extracted to the runtime dir
5. otherwise: that language is skipped (the code index degrades to file/module/dependency facts)

Set `pieria.treesitter.enabled=false` to disable Tree-sitter parsing entirely.
