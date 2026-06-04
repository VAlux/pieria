# Phase 13 - Source-Code Ingestion / Persistent Code Intelligence Index

## Objective

Build a persistent, polyglot source-code intelligence index from the actual repository. Parse the repo
with Tree-sitter, store an exhaustive symbol-and-edge substrate behind a new `CodeIndexStore` seam,
reuse the existing Phase 8 `Entity`/`Edge` graph for a curated subset of code *relationships* (so code
co-retrieves with conversation/trace memories), derive compact durable code memories that ride the
existing ingestion/retrieval pipeline, and add two retrieval channels — a `SymbolFtsChannel` and a
`CodeGraphChannel` — into the existing weighted Reciprocal Rank Fusion. Expose it through a new
`pieria onboard --source-code` path and a `POST /v1/profiles/{name}/code` daemon endpoint.

A language is a *data pack* (a Tree-sitter grammar plus `.scm` tag/import queries), so adding a
language never touches the pipeline; the initial set ships Java, Kotlin, Scala, TypeScript/JavaScript,
Python, Go, and Rust, and unknown extensions degrade to path/module/dependency-only facts.

Hard dependency on Phase 2 (content-addressed IDs, store transaction, vectorization outbox),
Phase 3 (retrieval channels + weighted RRF), and Phase 8 (graph `entities`/`edges` + `GraphChannel`).
Soft-benefits Phase 9 (reranker) and Phase 11 (consolidation). SQLite remains the active backend;
nothing leaves the machine.

## Scope

- File discovery in the CLI (`.gitignore`-aware, binary/size skip); daemon-side Tree-sitter parsing;
  the `CodeIndexStore` substrate (`code_files`, `code_symbols`, `code_symbols_fts`, `code_edges`,
  `code_modules`); language packs (grammar + tag/import `.scm`); deterministic derived code memories
  routed through the existing pipeline; projection of curated code `Entity`/`Edge`s into the Phase 8
  graph; two new channels (`SymbolFtsChannel`, `CodeGraphChannel`); the new REST endpoint and
  `--source-code` onboard flag; content-addressing by file/tree hash for idempotent re-index;
  observability.
- Two relation homes, by purpose:
  - **Precise code graph → dedicated `code_edges` substrate.** A confidence-scored
    (`resolved`|`heuristic`), file-provenanced edge set carrying the exhaustive call/import/reference/
    inheritance graph. `CodeGraphChannel` traverses this. It is *not* the graph `Edge` model:
    `Edge` has no `confidence`, binds lifetime to a memory rather than a file, and would be flooded by
    code volume.
  - **Curated cross-domain links → reuse the Phase 8 `Entity`/`Edge` graph.** A bounded, high-value
    subset (module/file/class/endpoint/test/command relations) projected so the existing
    `GraphChannel` co-retrieves code with conversation/trace memories.
- The dedicated *symbol* substrate is needed because `Entity` cannot carry signatures/line ranges (no
  indexed structural columns), has no FTS index, and collapses overloaded/same-named symbols.
- The retrieval/synthesis unit stays `Memory`: code channels resolve symbol/edge hits back to their
  derived code memories and return `Memory` candidates, so fusion and synthesis are unchanged.
- SQLite remains the active backend; the Postgres logical model is noted only and deferred to Phase 6.

Out of scope (explicit follow-ups):

- Promoting `heuristic` edges to `resolved` via a precise-resolution pass (a later enhancement; the
  `confidence` column is the seam for it).
- Watch mode / incremental freshness and `pieria code index --changed` (feature #15); this phase only
  lays the tree-hash/`contentHash` seam and a minimal status read.
- Endpoint/config-key extraction beyond a best-effort, per-pack deterministic pass.
- Changing `RetrievalCandidate`/synthesis to carry raw symbol/edge rows as first-class evidence (tie
  to the reranker #3 / citations #11 work).
- Refactoring recipes (#8) and Postgres parity (Phase 6).

## Implementation Sequence

1. Define the code-index substrate model (records in the `Entity`/`Edge` style).
   - `CodeFile`: content-addressed `id` over `(profileId, repoRelPath, contentHash)`; `language`,
     `repoRelPath`, `contentHash`, `loc`, `moduleId`, `indexedAt`.
   - `CodeSymbol`: `id` over `(profileId, fileId, kind, qualifiedName, signatureHash)`; `kind`
     (`module`/`package`/`class`/`interface`/`method`/`function`/`field`/`endpoint`/`config-key`/
     `test`), `name`, `qualifiedName` (best-effort FQN), `signature`, `visibility`, `startLine`/
     `endLine`, `language`, `parentSymbolId`.
   - `CodeEdge`: `id` over `(profileId, srcSymbolId, relation, dstRef, confidence)`; `relation`
     (`calls`/`references`/`imports`/`extends`/`implements`/`depends-on`/`tests`/`handles-route`),
     `confidence` (`resolved`|`heuristic`), `dstSymbolId` (nullable when unresolved), `dstRef` (the
     target name when unresolved), and `fileId` provenance (an edge is active while its file is in the
     index — lifetime bound to the file, not a memory).
   - `CodeModule`: deterministic build-unit/dir (e.g. a Gradle module or package root).
   - Reuse the existing `ContentId` helper; add fixed-vector ID unit tests, as for `Entity`/`Edge`.

2. Add storage schema and migration `V5__code_index.sql`.
   - Tables `code_files`, `code_symbols`, `code_edges`, `code_modules`; profile-scoped;
     insert-or-ignore on content-addressed IDs.
   - Indexes on `code_symbols(profile_id, qualified_name)`, `(profile_id, name)`, `(profile_id,
     file_id)`, `(profile_id, module_id)`; on `code_edges(profile_id, src_symbol_id)`,
     `(profile_id, dst_symbol_id)`, `(profile_id, relation, confidence)`, and `(profile_id, file_id)`.
   - FTS5 table `code_symbols_fts` over `(name, qualified_name, signature, path)` with sync triggers.
   - Reuse the existing `entities`/`edges` tables for the curated cross-domain projection — no change
     to them.
   - Note the mirrored Postgres logical model in the migration; defer the Postgres migration to
     Phase 6.

3. Add a `CodeIndexStore` seam (a sibling interface to `MemoryStore`, implemented by the same SQLite
   backend class so it can share a transaction).
   - `upsertCodeFile/Symbol/Edge/Module`.
   - `replaceFileIndex(profileId, fileId, symbols, edges)` — atomically re-index one changed file:
     delete that file's prior symbols and edges, insert the new sets, in one transaction.
   - `searchSymbolsFts(profileId, match, limit)`; `findSymbolsByName/Qualified(...)`.
   - `symbolNeighborhood(profileId, seedSymbolIds, depth, fanout, minConfidence)` — BFS over
     `code_edges` (seeds first, deduped), bounded by `fanout` per hop, traversing only edges whose
     `confidence >= minConfidence` and whose file is present; returns reached symbol ids.
   - `isCodeIndexPresent(profileId)` and code-index counts for status.
   - The curated cross-domain relations are written through the existing
     `MemoryStore.upsertEntity/upsertEdge`, tagged with the derived memory's id, in the same store
     transaction as the derived memory.

4. Build the language-pack abstraction.
   - `LanguagePack`: language id, file extensions, Tree-sitter grammar handle, tag query (`.scm`),
     import query, optional route/config rules. A registry keyed by extension.
   - Adding a language = adding a pack (grammar lib + `.scm` resources); no pipeline code changes.
   - Ship the initial set (Java, Kotlin, Scala, TypeScript/JavaScript, Python, Go, Rust). Unknown
     extensions produce a `CodeFile` + module/dependency facts only, no symbols.

5. Integrate Tree-sitter in the daemon (single-writer keeps parsing daemon-side).
   - Use `jtreesitter` (the official FFM/Panama binding — no JNI), in-process. The tree-sitter runtime
     plus per-language grammar libs are provisioned by **reusing the existing sqlite-vec mechanism**:
     drop grammar `.dylib`/`.so`/`.dll` into `packaging/native/<os>-<arch>/`, stage them as classpath
     resources via a `Sync` task sibling to `embedVecExtensions`, and add a `TreeSitterLibraryResolver`
     mirroring `VecExtensionResolver` (config → env → sidecar dir → embedded resource extracted to the
     app-data runtime dir, loaded by absolute path). Missing grammars degrade gracefully — that
     language is skipped; no grammars at all means the code index is simply absent.
   - A `TreeSitterEngine` bean owns a long-lived `Arena`, the loaded `Language` handles, and the `.scm`
     tag/import `Query` objects **compiled once at load** (queries are reusable; a `QueryCursor` is
     created per parse).
   - Threading/native memory: a `Parser` is **not thread-safe** — pool one per worker. Parsing is
     CPU-bound native work, so run it on a **bounded platform-thread executor sized to cores** (not
     virtual threads), never on the request thread. Each parse runs in a **scoped `Arena`
     (try-with-resources)** so the tree's off-heap memory is freed and large repos do not leak.
   - Native-image: the `org.graalvm.buildtools.native` plugin is already wired; add the FFM build args
     (`--enable-native-access=...`) and downcall/reachability registration, and verify
     `:daemon:nativeCompile` **early** (grammars are `dlopen`'d at runtime, as sqlite-vec already is,
     so they cannot be static-linked). Keep an out-of-process parser-worker fallback (plain-JVM
     `jtreesitter` or a prebuilt binary over stdio) ready if native-image FFM proves impractical.

6. Implement deterministic extraction (no model I/O in this stage).
   - Per file: parse → tag query → `CodeSymbol`s (outline, signatures, line ranges, visibility).
   - Build `code_edges` with confidence:
    - **`resolved`**: within-file binding (calls/references whose target resolves in the same file's
      scope) and import-target/inheritance targets resolvable from the file's imports.
    - **`heuristic`**: cross-file calls/references resolved by imported-name + declaring-file
      matching, plus module-level `depends-on`, `tests`, `handles-route`, and `extends`/`implements`
      recorded by target name when the declaring file is not in the batch (`dstSymbolId` null,
      `dstRef` set).
   - Skip files unchanged by `contentHash` (idempotent). A parse failure on one file logs and
     continues; it never fails the batch.

7. Derive compact durable memories from the substrate (the memory product).
   - Deterministically synthesize compact facts: module responsibilities (package/dir + public
     symbols), public entry points (exported/public top-level symbols, `main`, endpoints), module
     dependency facts (`depends-on`), test mapping (`tests`), and a config-key inventory.
   - Content-address derived memories by the code hashes they summarize (tree/file `contentHash`) so
     unchanged code regenerates no duplicates.
   - Route them through the existing ingestion classify/store path as `fact` (and `instruction` for
     discoverable build/test commands), tagging `payload.source = "code"` plus structured provenance
     (`fileId`, `symbolId`/FQN, line range, `contentHash`). Keyed facts use a `topic_key` (e.g.
     `code:module:<id>:responsibility`) so a changed module supersedes the prior fact in one
     transaction. Vector-eligible derived memories ride the existing outbox; raw symbol/edge rows are
     not embedded (keep the index lean, mirroring the `task` exclusion).
   - Optional small-model summarization for module-level prose only, reusing the small tier and
     degradable — the structural facts themselves stay deterministic.

8. Project curated relations into the Phase 8 graph (reusing `Entity`/`Edge`).
   - For the high-value subset, upsert the two endpoints as `Entity` rows (types `module`/`file`/
     `class`/`method`/`endpoint`/`config-key`/`test`/`command`) and the relation as an `Edge`, tagged
     with the derived memory's id as provenance, so the existing `GraphChannel` co-retrieves code with
     conversation/trace memories. Do not project locals, private members, or the exhaustive call graph
     (that lives in `code_edges`).

9. Add `SymbolFtsChannel` (new, non-critical, wave 1).
   - FTS over `code_symbols_fts`; resolve symbol hits to their derived code memories (and nearby
     module/file facts) and return `Memory` `RetrievalCandidate`s so fusion/synthesis are unchanged.
   - Add `SYMBOL_FTS` to `RetrievalChannelType`; run it in the wave-1 fan-out; return empty cleanly
     when no code index is present.

10. Add `CodeGraphChannel` (new, non-critical, wave 2 — mirrors `GraphChannel`'s seeding).
    - Seed symbols from (a) query terms/entities via `findSymbolsByName/Qualified` and (b) symbols
      named in the provenance of wave-1 candidate memories (`payload.symbolId`/FQN).
    - Expand via `symbolNeighborhood(depth, fanout, minConfidence)`, preferring `resolved` edges;
      resolve reached symbols to their derived memories and return ranked `Memory` candidates
      (resolved-edge paths ranked ahead of heuristic).
    - Add `CODE_GRAPH` to `RetrievalChannelType`; run it in the second wave seeded from wave-1 hits.
      Failure/timeout contributes nothing and never fails recall.

11. Wire weights and configuration.
    - `PieriaProperties.Retrieval`: `weightSymbolFts` and `weightCodeGraph` (both registered in the
      channel-weights map; weight 0 disables a channel entirely, mirroring `graphEnabled`).
      `weightCodeGraph` defaults to a conservative primary-tier value comparable to `weightGraph`,
      tunable on the Phase 5 eval harness. `codeGraphDepth`, `codeGraphFanout`, and
      `codeGraphMinConfidence` (default: include `heuristic` but prefer `resolved` in ranking).
    - New code section: parser concurrency, file size/byte caps, included/excluded globs,
      language-pack toggles.

12. Add the intake endpoint and CLI path.
    - `POST /v1/profiles/{name}/code`: body = repo metadata (git HEAD/tree hash) + a list of
      `{repoRelPath, language?, contentHash, content}`. The daemon skips unchanged files by hash,
      parses changed ones, replaces their symbols and edges atomically, derives memories, projects
      graph nodes, and enqueues vectors. Idempotent.
    - `GET /v1/profiles/{name}/code/status`: file/symbol/edge counts and last-indexed tree hash
      (minimal; full freshness is feature #15).
    - CLI: extend `OnboardCommand` with `--source-code` (composable with markdown seeding),
      `.gitignore`-aware discovery (extend the `MarkdownDiscovery` pattern), binary/size skip, file
      batching, tree-hash transmission, and `--dry-run` listing files + detected languages. Add a
      `CodeIndexClient` mirroring `IngestClient` (test seam via `clientOverride`).

13. Expose observability.
    - Per-run counts: files discovered/sent/skipped-unchanged/parsed/failed, symbols, edges (split
      `resolved` vs `heuristic`), derived memories stored/superseded, graph entities/edges projected,
      vectors enqueued; per-language parse latency. No off-machine telemetry.

## Tests

- Fixed-vector ID tests for `CodeFile`/`CodeSymbol`/`CodeEdge`/`CodeModule`.
- Migration test: `V5` tables/indexes/FTS triggers exist; re-indexing identical content adds no rows;
  a changed file atomically replaces only that file's symbols and edges.
- Per-language pack tests: a small fixture per shipped language → expected symbols (kind/name/
  signature/lines), at least one `resolved` within-file edge, and at least one `heuristic` cross-file
  edge carrying the confidence flag and `dstRef`. Grammars are bundled so CI needs no network.
- Derived-memory tests: deterministic module/entry-point/dependency facts; content-addressing by code
  hash means unchanged code regenerates nothing; a changed module supersedes via `topic_key`;
  `payload.source = "code"` and provenance present; raw symbol/edge rows are not embedded.
- Graph-reuse tests: the curated subset upserts `Entity`/`Edge` rows, locals/private and the exhaustive
  call graph are excluded, and the existing `GraphChannel` co-retrieves a code fact linked to a
  conversation memory.
- Channel tests: `SymbolFtsChannel` resolves symbol hits to derived memories and skips cleanly with no
  index; `CodeGraphChannel` seeds from query + wave-1 provenance, honors `depth`/`fanout`/
  `minConfidence`, ranks `resolved` paths ahead of `heuristic`, and resolves reached symbols to
  derived memories; both participate in weighted RRF deterministically and are disabled by weight 0;
  `CodeGraphChannel` failure yields partial results without failing recall.
- API tests: `POST /code` is idempotent, unchanged-by-hash files are skipped, a changed file
  re-indexes; markdown-only onboard still works; mixed markdown + source-code onboard works.
- Degradation tests: a parse failure on one file logs and the batch continues; a model-summary failure
  leaves the deterministic facts stored; a missing code index never fails recall.
- Run `./gradlew test`.

## Acceptance Criteria

- `pieria onboard --source-code` builds a persistent, polyglot code index from the repo through a
  single Tree-sitter mechanism; adding a language is a data pack, not a code change.
- An exhaustive symbol-and-edge substrate is stored separately; full source is never stored as ordinary
  memories; derived memories are compact and content-addressed by code hash.
- `code_edges` carry a `confidence` flag; `CodeGraphChannel` traverses them bounded by depth/fanout/
  `minConfidence`, ranks `resolved` ahead of `heuristic`, feeds the existing weighted RRF, and returns
  `Memory` candidates (synthesis unchanged).
- A curated subset of code relations reuses the existing `Entity`/`Edge` graph and co-retrieves with
  conversation/trace memories via the existing `GraphChannel`.
- Re-indexing unchanged code is idempotent (no duplicate rows or memories); changed files re-index
  atomically (symbols and edges) and supersede stale derived facts.
- A parse failure never fails the batch; either code channel failing/timing out never fails recall;
  nothing leaves the machine.

## Risks And Follow-Ups

- **Heuristic edges over-match** same-named symbols across files; the `confidence` flag,
  `codeGraphMinConfidence`, resolved-first ranking, and a tunable `weightCodeGraph` contain it. A
  later precise-resolution pass can promote `heuristic` edges to `resolved` — the `confidence` column
  is the seam; keep it stable.
- **Tree-sitter on the JVM / native-image** is the primary delivery risk: FFM bindings plus bundling
  per-language grammar native libs across OS/arch. Mitigate with a bundled-grammar loader and an
  out-of-process parser-worker fallback; verify `:daemon:nativeCompile` early.
- **Language coverage is finite** though the architecture is polyglot; maintain a pack backlog;
  unknown languages degrade to path/dependency facts.
- **Scale on large monorepos**: `code_edges` is the highest-volume table — enforce size/byte caps,
  excluded globs, bounded parser concurrency, and bounded neighborhood fanout; keep symbol/edge rows
  out of the vector index.
- **Reranker (#3 / Phase 9)** becomes more important once code, memory, and trace candidates compete
  for the context budget; surfacing raw symbol/edge rows as first-class recall evidence (a
  `RetrievalCandidate` change) is deferred to that work.
- **Consolidation (#5 / Phase 11)** should later derive higher-level project observations from the
  code index; keep derived-fact `topic_key`s and provenance stable for that consumer.
- **Freshness / watch mode (#15)** builds on the tree-hash/`contentHash` seam here; keep
  `GET /code/status` and the hash fields stable.
- **`CodeIndexStore` vs `MemoryStore` boundary**: a sibling interface must share the backend
  transaction so the atomic file replace, derived-memory write, and `Entity`/`Edge` projection commit
  together.
