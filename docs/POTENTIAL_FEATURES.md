# Tier 1 — Storage & retrieval quality (moves recall accuracy)

1. Graph / relationship memory layer
Phase: 8 | Status: done
Who has it: Zep, Graphiti, Cognee, Supermemory, Hindsight — universally, the single biggest thing Pieria lacks.
What: Extract entities and relations during classification; store an entity-relation graph; add graph traversal as a retrieval signal. Answers "who/what is connected to X" and multi-hop questions that vector+FTS miss. For coding-agent use, make code entities first-class too: repository, module, package, file, class, method, endpoint, config key, command, test, tool, and memory.
Fit: New GraphChannel alongside the existing five in RetrievalService's StructuredTaskScope fan-out; extend MemoryStore with edge tables. Largest effort here, largest payoff. SQLite can hold the adjacency tables; no new
infra needed. Later server/large-local modes can evaluate Neo4j (graph + vector indexes), Kuzu (embedded graph + Cypher/vector/FTS), or TinkerPop/Gremlin as optional graph backends, but the first implementation should stay SQLite-native.
Shipped: `domain.graph` (`Entity`/`Edge`/`GraphFragment`/`EntityNormalizer`), entity/relation extraction wired into `IngestionService`'s classification stage, and `retrieval.channel.GraphChannel` running as a wave-2 channel in `RetrievalService`, weighted into the existing RRF fusion.

2. Source-code ingestion / persistent code intelligence index
Phase: 13 | Status: done
Who has it: Sourcegraph SCIP/LSIF, CodeGraphContext/codebase-memory-style MCP servers, Serena memories partially.
What: `pieria onboard` only seeds markdown today. Add source-code ingestion that builds a persistent semantic index from the actual repo: symbols, definitions, references, implementations, imports, ownership boundaries, API endpoints, config keys, tests, and module dependencies. Do not store full source as ordinary memories; store structured code-index rows and derive compact durable memories from them.
Fit: New `pieria onboard --source-code` path. For Java/Kotlin/Scala, start with `scip-java` because it supports Gradle/Maven/sbt and emits precise symbol occurrences. Use Tree-sitter as a lower-confidence fallback when the build cannot resolve or for polyglot files. Add `CodeIndexStore` methods under/alongside MemoryStore, then add SymbolFts/CodeGraph retrieval channels that feed the existing RRF pipeline.
Shipped: real Tree-sitter-based indexer (`CodeIndexingService`, `CodeIndexStore`/`SqliteCodeIndexStore`) behind `pieria onboard --source-code` and `POST /v1/profiles/{name}/code(/async)` + `GET /code/status`; content-hash based skip-unchanged and `--reindex`; `SymbolFtsChannel` and `CodeGraphChannel` feed the same RRF pipeline as the memory channels.

3. Reranker stage between fusion and synthesis
Phase: 9 | Status: pending
Who has it: Mem0, LanceDB, Pinecone, Langbase.
What: RRF currently feeds top-K straight into synthesis. A cross-encoder (or small-model) rerank of the fused candidates before synthesis sharply improves precision of what the large model sees. Once code-index channels exist, reranking becomes more important because memory, code symbol, trace, and graph candidates will be competing for the same context budget.
Fit: Drops in cleanly after ReciprocalRankFusion, before synthesis, using the existing two-tier model gateway (small model does the rerank). Low effort, measurable on your eval harness immediately. Add feature flags to compare memory-only rerank vs mixed memory+code rerank.

4. Bi-temporal fact validity / temporal invalidation
Phase: 10 | Status: pending
Who has it: Zep, Graphiti.
What: Today supersession only fires on explicit topic_key collision. Competitors track valid_from/valid_to so facts can be invalidated by time, enabling "what was true last week" and conflict resolution without an exact
key match.
Fit: You already have TemporalExtractor and the supersession machinery — extend the memory row with validity columns and filter active queries on them. Medium effort.

5. Memory consolidation / reflection (background replay)
Phase: 11 | Status: pending
Who has it: Hindsight (observations from raw facts), Letta, Generative Agents research. Already in your SPEC §17 as "future."
What: A background worker merges/strengthens related memories and derives higher-level observations from clusters of raw facts.
Fit: The transactional-outbox + virtual-thread worker pattern (VectorizationWorker/VectorizationScheduler) is exactly the host for a ConsolidationWorker. Architecturally pre-paved. Once source-code indexing exists, consolidation should also derive/update high-level project observations from code facts (module responsibilities, entry points, service boundaries, test strategy) rather than relying only on docs and conversations. The code-facing half landed as Phase 14 (code narrative summaries: per-file/module/architecture memories, hash-keyed); conversational consolidation remains.

6. Execution-trace / tool-output memory
Phase: 12 | Status: pending
Who has it: Memori (its core differentiator).
What: Ingest tool calls, outputs, and failures — not just chat messages. For coding agents this is often the most valuable signal (what commands worked, what errored).
Fit: New ingestion source feeding IngestionService; possibly a new memory type or payload shape. The content-addressed ID scheme already handles dedup. Medium effort, high relevance to your coding-agent target. Link traces to code graph entities (`test`, `command`, `file`, `class`, `method`, `build tool`) so "why did this test fail" or "what command validates this module" can retrieve both the trace and the affected code.

---
# Tier 2 — Capabilities (new things agents can do)

7. Live code MCP / LSP tools
Phase: unassigned | Status: pending
Who has it: Serena, JetBrains MCP/Serena JetBrains plugin, raw LSP bridges, Eclipse JDT LS for Java.
What: Give agents IDE-like code navigation and basic semantic editing through Pieria's gateway: find symbol, file outline, go to definition, find references, find implementations, hover, diagnostics, call hierarchy, type hierarchy, rename preview/apply, and organize imports. This is live project intelligence, complementary to the persisted code index.
Fit: Shortest path is to integrate or co-install Serena as a companion MCP server and optionally proxy a narrow stable subset through Pieria. Native path is an LSP client inside the daemon/gateway, with Eclipse JDT LS as the Java backend and other language servers configured per project. Keep destructive edit/refactor tools gated behind preview/diff-first calls.

8. Safe semantic refactoring recipes
Phase: unassigned | Status: pending
Who has it: OpenRewrite/Moderne for Java ecosystem migrations, IDE refactoring engines, Serena/JetBrains for symbol refactors.
What: Agents need more than text edits for reliable repo-wide changes. Add recipe-backed transformations: change type/package, migrate APIs, update dependencies, organize imports, remove unused code, and apply framework-specific migrations with a preview diff.
Fit: Use LSP/JDT for small symbol refactors (`rename`, `organizeImports`) and OpenRewrite for repeatable Java/Spring/Gradle migrations. Expose as `refactor.preview`, `refactor.apply`, and `refactor.listRecipes`; never apply without a diff and test recommendation.

9. reflect()-style reasoning recall mode
Phase: unassigned | Status: pending
Who has it: Hindsight.
What: A recall variant that reasons over retrieved memories to answer harder questions, distinct from straight synthesis. Exposed as a second MCP tool.
Fit: New endpoint + MCP tool reusing the retrieval pipeline with a different (reasoning) synthesis prompt.

10. TTL / expiration for ephemeral memories
Phase: unassigned | Status: pending
Who has it: Mem0 (expiration policies), Redis (TTL primitive). This is literally your open question §18 on task retention.
What: Auto-expire task and other ephemeral memories.
Fit: An expires_at column + a sweep in an existing scheduler. Low effort.

11. Citations / provenance surfaced in the answer
Phase: unassigned | Status: pending
Who has it: Hindsight.
What: You already store session_id provenance and line indices; surface them as citations in the synthesized recall answer so the agent (and user) can trust/trace claims. With code indexing, citations should include file path, symbol id/FQN, line range, memory id, trace id, and source freshness.
Fit: Synthesis prompt + RecallResponse change. Low effort, high trust payoff. Code-index candidates should carry structured provenance so citations do not depend on the model inventing file references. Partially landed: code-graph edge evidence (src/dst FQN + path + relation + confidence) already flows structurally through synthesis and `RecallResponse.codeEvidence`; extending the same carrier to memory/session citations is the remaining work.

12. Rolling user/project profile compaction
Phase: 15 follow-up (Standing-Summary Session Primer) | Status: **superseded (2026-08-05)**
Who has it: Supermemory (user profiles), Zep (context templates).
What: Maintain a continuously-compacted "profile" memory per project — a synthesized standing summary that's cheap to inject at session start. Extend it with code-derived standing context: architecture map, module responsibilities, public entry points, test/build commands, generated-code boundaries, and known risky files.
Superseded: the session-start injection path this fed no longer injects content — it emits a pointer at the store (`MemoryPointer`) and content moved to pull-based `recall`. The standing context enumerated above is precisely what `AGENTS.md`/`CLAUDE.md` already carry into every session, so a synthesized version would duplicate them more accurately rather than replace them; and any session-start push must guess the subject before the user has spoken, where `recall` knows the task. See OPTIMIZATIONS #15 for the measurements that led here. The Phase 14 half stands: hash-keyed `code:summary:*` architecture/module/file memories still exist and are reachable through `recall` — only the rolling *injectable* profile is dropped.

13. Procedural / skill memory as versioned artifacts
Phase: unassigned | Status: pending
Who has it: Letta MemFS, Voyager research, OpenRewrite recipes for code transformations.
What: Your instruction type is declarative text; competitors store reusable procedures/code recipes as versioned artifacts. Relevant specifically because you target coding agents. Examples: "release checklist", "debug native-image failure", "add a new Spring endpoint", "apply migration recipe X".
Fit: Larger conceptual addition; could layer on the existing version-chain (supersedes) idea. For code-specific procedures, store both a human instruction and an executable artifact reference (script path, OpenRewrite recipe, Gradle task, command template) with provenance and versioning.

20. Knowledge-graph wiki synthesis (human-readable project wiki)
Phase: 15 | Status: pending
Who has it: DeepWiki (Cognition) generates browsable wikis from a repo; Cognee exposes graph-derived documents; GitBook/Mintlify AI docs; Sourcegraph docs. None of them combine a *conversation-derived* memory graph with a code graph the way Pieria could.
What: Turn the onboarded entity-relation graph (Phase 8) plus the code index and narrative summaries (Phases 13-14) into a synthesized, cross-linked wiki a human can read to learn the project. Entities become pages, edges become inter-page links, and the provenance memory behind every edge becomes the sourced prose — so each claim is traceable back to a `memory_id` rather than model-invented. A living document: because `graphSnapshot` only includes active (non-superseded) memories, regeneration reflects the current understanding.
Fit: Read side already exists — `MemoryStore.graphSnapshot(profileId)` returns the connected entities + active edges + per-edge provenance snippet, exposed at `GET /v1/profiles/{name}/graph`. Missing piece is a page-composition + synthesis layer on top of it: rank entities by degree to pick pages, gather each page entity's neighborhood + provenance memories, and run the large (synthesis) tier to write a section per page. Architecturally this is Phase 14's `CodeSummarizationService` pattern at graph altitude — batch synthesis inside the async task, content-addressed skip keyed on the graph-snapshot hash, supersession on regeneration, best-effort per-page failure. Human-facing artifact (cached, regenerated on ingest), not an on-demand recall response.

---
# Tier 3 — Quality-of-life & ops

14. Local recall/usage analytics dashboard
Phase: unassigned | Status: done (CLI, not a GUI dashboard)
Who has it: Memori cloud. You already emit per-stage latency/token metrics to logs — a local read-only dashboard makes them usable. No telemetry leaves the machine, consistent with your design.
Shipped: `model.usage` (`InferenceTier`/`TierUsage`/`InferenceUsageAccumulator`/`InferenceUsageSink`) plus `pieria profile stats` (`ProfileStatsCommand`), which renders per-profile memory counts, sessions, vectorization backlog, an "impact" panel (estimated tokens saved vs. re-reading the source each answer was distilled from), and per-tier spend/cost estimation. Still CLI-only — no GUI/read-only web dashboard.

15. Code-index freshness and watch mode
Phase: unassigned | Status: in progress
Who has it: IDEs/LSP workspaces, Sourcegraph auto-indexing, file-watcher based code graph tools.
What: Keep source-code indexes fresh without making every recall rebuild the project. Track git HEAD/tree hash, indexed file hashes, language-server readiness, and stale symbol counts. Optionally watch files and queue incremental re-indexing.
Fit: Start simple: `pieria code status`, `pieria code index --changed`, and status fields surfaced through `/v1/status`. Later add a filesystem watcher and background index outbox. Do not block recall when the code index is stale; mark code candidates with freshness metadata.
Shipped so far: `GET /v1/profiles/{name}/code/status` (file/symbol/resolved-edge/heuristic-edge counts, `isCodeIndexPresent`) and per-file content-hash skip-unchanged with an explicit `--reindex` override. Still missing: a standalone `pieria code status` CLI command, git tree-hash-level staleness surfaced to the caller, and any filesystem watcher / incremental-queue for continuous re-indexing.

16. Webhooks / change events
Phase: unassigned | Status: pending
Who has it: Zep. Emit events on memory write/supersede so harnesses or tooling can react. More relevant once server mode exists.

17. Pluggable vector / graph backend / quantization
Phase: unassigned | Status: pending
Who has it: the substrate vendors; already your SPEC §17 (Qdrant). The VectorStore seam is in place — only relevant at server scale.
What: Keep the embedded default simple, but define backend seams for vector and graph storage once code graph + memory graph are real. Candidates: SQLite/sqlite-vec for local default, Postgres/pgvector for server mode, Neo4j for graph+vector, Qdrant for vector scale, Kuzu for embedded graph experiments.
Fit: Do not introduce new infrastructure for local mode until evaluation shows SQLite adjacency + sqlite-vec are insufficient. The main design work is keeping retrieval channels backend-neutral.

18. Encryption at rest (SQLCipher)
Phase: unassigned | Status: pending
Who has it: Supermemory Enterprise; your SPEC §17. Opt-in for the embedded DB.

19. Auth / RBAC / audit logging
Phase: unassigned | Status: pending
Who has it: Zep (SOC2/HIPAA/RBAC). Only meaningful for Phase 6 server mode — don't pull this into local-mode code paths.
