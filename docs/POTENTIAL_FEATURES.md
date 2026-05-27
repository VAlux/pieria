# Tier 1 — Storage & retrieval quality (moves recall accuracy)

1. Graph / relationship memory layer
Phase: 8 | Status: pending
Who has it: Zep, Graphiti, Cognee, Supermemory, Hindsight — universally, the single biggest thing Pieria lacks.
What: Extract entities and relations during classification; store an entity-relation graph; add graph traversal as a retrieval signal. Answers "who/what is connected to X" and multi-hop questions that vector+FTS miss.
Fit: New GraphChannel alongside the existing five in RetrievalService's StructuredTaskScope fan-out; extend MemoryStore with edge tables. Largest effort here, largest payoff. SQLite can hold the adjacency tables; no new
infra needed.

2. Reranker stage between fusion and synthesis
Phase: 9 | Status: pending
Who has it: Mem0, LanceDB, Pinecone, Langbase.
What: RRF currently feeds top-K straight into synthesis. A cross-encoder (or small-model) rerank of the fused candidates before synthesis sharply improves precision of what the large model sees.
Fit: Drops in cleanly after ReciprocalRankFusion, before synthesis, using the existing two-tier model gateway (small model does the rerank). Low effort, measurable on your eval harness immediately.

3. Bi-temporal fact validity / temporal invalidation
Phase: 10 | Status: pending
Who has it: Zep, Graphiti.
What: Today supersession only fires on explicit topic_key collision. Competitors track valid_from/valid_to so facts can be invalidated by time, enabling "what was true last week" and conflict resolution without an exact
key match.
Fit: You already have TemporalExtractor and the supersession machinery — extend the memory row with validity columns and filter active queries on them. Medium effort.

4. Memory consolidation / reflection (background replay)
Phase: 11 | Status: pending
Who has it: Hindsight (observations from raw facts), Letta, Generative Agents research. Already in your SPEC §17 as "future."
What: A background worker merges/strengthens related memories and derives higher-level observations from clusters of raw facts.
Fit: The transactional-outbox + virtual-thread worker pattern (VectorizationWorker/VectorizationScheduler) is exactly the host for a ConsolidationWorker. Architecturally pre-paved.

5. Execution-trace / tool-output memory
Phase: 12 | Status: pending
Who has it: Memori (its core differentiator).
What: Ingest tool calls, outputs, and failures — not just chat messages. For coding agents this is often the most valuable signal (what commands worked, what errored).
Fit: New ingestion source feeding IngestionService; possibly a new memory type or payload shape. The content-addressed ID scheme already handles dedup. Medium effort, high relevance to your coding-agent target.

---
# Tier 2 — Capabilities (new things agents can do)

6. reflect()-style reasoning recall mode
Phase: unassigned | Status: pending
Who has it: Hindsight.
What: A recall variant that reasons over retrieved memories to answer harder questions, distinct from straight synthesis. Exposed as a second MCP tool.
Fit: New endpoint + MCP tool reusing the retrieval pipeline with a different (reasoning) synthesis prompt.

7. TTL / expiration for ephemeral memories
Phase: unassigned | Status: pending
Who has it: Mem0 (expiration policies), Redis (TTL primitive). This is literally your open question §18 on task retention.
What: Auto-expire task and other ephemeral memories.
Fit: An expires_at column + a sweep in an existing scheduler. Low effort.

8. Citations / provenance surfaced in the answer
Phase: unassigned | Status: pending
Who has it: Hindsight.
What: You already store session_id provenance and line indices; surface them as citations in the synthesized recall answer so the agent (and user) can trust/trace claims.
Fit: Synthesis prompt + RecallResponse change. Low effort, high trust payoff.

9. Rolling user/project profile compaction
Phase: unassigned | Status: pending
Who has it: Supermemory (user profiles), Zep (context templates).
What: Maintain a continuously-compacted "profile" memory per project — a synthesized standing summary that's cheap to inject at session start.
Fit: Pairs with consolidation (#4); feeds your SessionStart hook injection path.

10. Procedural / skill memory as versioned artifacts
Phase: unassigned | Status: pending
Who has it: Letta MemFS, Voyager research.
What: Your instruction type is declarative text; competitors store reusable procedures/code recipes as versioned artifacts. Relevant specifically because you target coding agents.
Fit: Larger conceptual addition; could layer on the existing version-chain (supersedes) idea.

---
# Tier 3 — Quality-of-life & ops

11. Local recall/usage analytics dashboard
Phase: unassigned | Status: pending
Who has it: Memori cloud. You already emit per-stage latency/token metrics to logs — a local read-only dashboard makes them usable. No telemetry leaves the machine, consistent with your design.

12. Webhooks / change events
Phase: unassigned | Status: pending
Who has it: Zep. Emit events on memory write/supersede so harnesses or tooling can react. More relevant once server mode exists.

13. Pluggable vector backend / quantization
Phase: unassigned | Status: pending
Who has it: the substrate vendors; already your SPEC §17 (Qdrant). The VectorStore seam is in place — only relevant at server scale.

14. Encryption at rest (SQLCipher)
Phase: unassigned | Status: pending
Who has it: Supermemory Enterprise; your SPEC §17. Opt-in for the embedded DB.

15. Auth / RBAC / audit logging
Phase: unassigned | Status: pending
Who has it: Zep (SOC2/HIPAA/RBAC). Only meaningful for Phase 6 server mode — don't pull this into local-mode code paths.
