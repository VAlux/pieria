# Phase 9 - Retrieval Reranking Stage

## Objective

Insert a reranking stage between weighted Reciprocal Rank Fusion and synthesis in the read pipeline. RRF produces a
fused candidate list optimized for recall; the reranker re-scores the top-N fused candidates against the original query
and hands a tighter, higher-precision top-K to the large synthesis model. The default reranker is a structured relevance
scorer driven by the small/fast model tier (never the large synthesis model), with a stable seam for a dedicated
reranker model. The whole stage is feature-flagged so it can be A/B compared on the Phase 5 evaluation harness, and it
degrades to the existing RRF ordering on any failure or timeout.

## Scope

- Re-scoring and precision filtering of already-fused candidates.
- A stable `Reranker` interface with a model-backed default and a no-op pass-through.
- Configuration: feature flag, candidate window N, output K, score threshold, and score-combination mode.
- Hard dependency on Phase 3 (full retrieval pipeline + RRF) only; the reranker consumes the `List<RecallCandidate>`
  that `ReciprocalRankFusion.fuse(...)` emits and produces a re-ordered `List<RecallCandidate>` for synthesis.
- Independent of Phases 8, 10, 11, and 12 and may be implemented in parallel with them; it touches only the slice of
  `RetrievalService` between fusion and synthesis.
- No changes to the ingestion path, the retrieval channels, RRF itself, or the synthesis prompt contract.
- No dedicated reranker-model packaging work; the dedicated-model path is a config-selectable seam, deferred for
  activation until eval data justifies it.

## Implementation Sequence

1. Define the `Reranker` interface.

- `List<RecallCandidate> rerank(String query, List<RecallCandidate> fused, RerankContext ctx)` returning a re-ordered,
  possibly shortened list.
- Preserve each candidate's existing `Memory`, RRF `score`, and `source` provenance; the reranker may overwrite the
  surfaced score per the configured combination mode but must keep the channel provenance string intact for
  diagnostics.
- Contract: never throw for empty/null input (return the input unchanged); never widen the list beyond what it
  received.

2. Implement the no-op pass-through reranker.

- Returns the input list unchanged (identity).
- Selected when the stage is disabled by config and used as the fallback target on reranker failure.
- Wired as the default bean so existing Phase 3 behavior is byte-for-byte unchanged when the flag is off.

3. Implement the model-backed default reranker.

- Take the top-N fused candidates (N = candidate window) and ask the small/fast model tier via `ModelGateway` for a
  structured relevance score per candidate against the original query.
- Add a `ModelGateway.rerank(query, candidates)` (or equivalent structured scoring) method as a `default` so existing
  stubs (`FakeModelGateway`, `StubModelGateway`) compile unchanged.
- Score in a single batched call where the provider allows it; bound the candidate text length fed to the model.
- Map model output back to candidates by stable id; candidates the model omits or fails to score retain their RRF
  score (combination mode decides how this is treated).

4. Apply combination, threshold, and truncation.

- Combination mode (config): `replace` uses the rerank score as the surfaced score; `blend` combines normalized rerank
  and RRF scores with a configurable mix weight.
- Drop candidates whose final score is below the configured threshold.
- Re-sort by final score descending, reusing Phase 3 deterministic tie-breaking (recency, then memory id).
- Truncate to output K and hand that list to synthesis.

5. Insert the stage in `RetrievalService`.

- Call the reranker after `fusion.fuse(...)` and before `synthesizeRecall(...)`, replacing the current `limit`
  truncation so the reranker owns the candidate window and final K.
- Keep the recall `limit` as the ceiling on what synthesis and the API response see.
- Time the stage and add a `rerankMs` field to the existing recall latency log line.

6. Make the stage degrade gracefully.

- Wrap the rerank call in the same bounded-timeout / best-effort posture used for channels in Phase 3.
- On reranker exception or timeout, log a warning and fall back to the RRF-ordered list truncated to K; recall must
  not fail.
- Treat reranker failure as non-critical, exactly like a best-effort vector channel.

7. Preserve diagnostics through the stage.

- Extend `RetrievalDiagnostics` with a rerank section: input candidate count, output count, dropped count, whether a
  fallback occurred, and stage latency.
- Keep the per-candidate `source` provenance from fusion; optionally annotate the surfaced score origin (`rrf` vs
  `rerank` vs `blend`) for debug-only output.
- Default API responses stay concise; rerank diagnostics appear only under the existing debug request flag.

8. Wire configuration.

- Add a `rerank` block under retrieval properties: `enabled` (flag), `candidateWindow` (N), `outputK` (K),
  `scoreThreshold`, `combinationMode` (`replace`/`blend`), `blendWeight`, `timeoutMs`, and `model` selector (
  small-tier structured scorer by default; dedicated reranker model seam reserved).
- Defaults: stage enabled with conservative N/K (N larger than K), zero or near-zero threshold, and `replace` mode,
  all tunable for Phase 5 evaluation.

## Tests

- Unit tests for the no-op reranker (identity on populated, empty, and null input).
- Unit tests for combination modes (`replace`, `blend` with mix weight), threshold dropping, truncation to K, and
  deterministic tie-breaking.
- Reranker test using a stub `ModelGateway` that returns fixed relevance scores, proving the fused order is re-arranged
  and provenance is preserved.
- Degradation tests: model exception and timeout both fall back to RRF order without failing recall.
- `RetrievalService` test proving the stage sits between fusion and synthesis, that synthesis receives the reranked
  top-K, and that the flag-off path is identical to Phase 3.
- Diagnostics test asserting rerank fields and preserved channel provenance under the debug flag.
- Run `./gradlew test`.

## Acceptance Criteria

- With the flag off, recall output is identical to the Phase 3 pipeline.
- With the flag on, synthesis receives a reranked, threshold-filtered top-K drawn from the top-N fused candidates.
- The default reranker uses the small/fast model tier, never the large synthesis model.
- Reranker failure or timeout falls back to RRF ordering and recall still returns an answer.
- Channel provenance survives the rerank stage and surfaces in debug diagnostics.
- Candidate window N, output K, threshold, and combination mode are configurable without code changes.

## Risks And Follow-Ups

- Rerank weights, threshold, candidate window, and output K are defaults until Phase 5 evaluation data exists; the eval
  harness must measure precision/recall and latency with the stage on vs off, mirroring how RRF weights are treated as
  provisional.
- Adding a small-model call on the read path raises recall latency; measure the latency budget and consider batching
  limits and caching, and keep the no-op path as the comparison baseline.
- A dedicated local reranker model (e.g. a bge-reranker via Ollama or a Spring AI reranking seam) is left as a
  config-selectable follow-up; activate it only if eval shows the small-tier structured scorer underperforms.
- `blend` mode requires score normalization across two different scales (RRF vs relevance); validate the normalization
  on eval fixtures before defaulting to it.
