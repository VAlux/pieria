# Phase 10 — Full bi-temporal fact validity

## Model and scope

SQLite stores content-addressed memories separately from source assertions, assertion revisions,
and resolved validity segments. World time answers “what was true at T?”; recorded time answers
“what did Pieria believe at K about T?”. PostgreSQL implementation remains deferred to Phase 6.

`memory_assertions` identifies a stable source occurrence and fact group. `assertion_revisions`
preserves asserted bounds, speaking time, source payload, correction evidence and transaction
intervals. `validity_segments` stores the resolved intervals referencing those revisions. Both
axes use half-open intervals: start inclusive, end exclusive. A monotonic transaction sequence
breaks clock ties and supplies a committed watermark for parallel recall channels.

Facts, instructions and keyed `trace:outcome:` events resolve within fact groups. Ordinary events
and tasks are append-only; knowledge cutoffs still apply. Tasks never receive embeddings.
`superseded` and `supersedes` remain compatibility/lineage fields, not visibility predicates.

## Deterministic resolution

Partition a group's asserted intervals at every boundary. The latest effective start wins an
overlap, then speaking time and stable source order. When a start-time bucket contains missing
speaking times, transaction order resolves that bucket; using one mode per bucket avoids a
non-transitive mixed comparator. Adjacent segments with the same winner coalesce.

An assertion that applies from January onward plus a March-only override resolves to:

| World interval | Selected assertion |
| --- | --- |
| January 1–March 1 | Alice |
| March 1–April 1 | Bob |
| April 1 onward | Alice |

Late arrivals fill their historical periods. Future replacements leave predecessors visible until
their effective start. Corrections replace a targeted assertion revision before partitioning;
old segments remain queryable under earlier recorded cutoffs. Invalidating at an instant truncates
an assertion, allowing an underlying valid assertion to resume.

Store entry points and revisions run inside one transaction: persist content and assertion changes,
close previous recorded intervals, resolve replacement segments, persist graph evidence, and enqueue
embeddings. Direct-store overloads carry transaction annotations too. Revision writes take the SQLite
writer lock before checking the expected revision, so concurrent corrections yield one winner and a
409 conflict. Model calls run before storage; semantic targets are revalidated at commit.

## Public contract

- Recall accepts optional `asOf` and `knownAsOf`, independently defaulting to request time. Values
  must be ISO timestamps with offsets and normalize to UTC. Future world time is allowed; future
  knowledge cutoffs and malformed timestamps are rejected. Natural-language dates do not select a
  database snapshot.
- Remember accepts `validFrom`, `validTo` and `checkConflicts=false`. A missing start uses source
  speaking time when available, otherwise store time. Empty/reversed intervals are rejected.
- `POST /memories/{id}/revisions` requires `assertionId` and `expectedRevisionId`. Optional
  `content`, `validFrom` and `validTo` correct an assertion. Omitted fields retain their values;
  `openEnded=true` explicitly removes an end bound. `invalidateAt` truncates validity and cannot
  be combined with correction fields. Stale expected revisions return 409.
- `GET /memories/{id}/revisions` exposes revision history. NDJSON exports include revision and resolved segment history.
- Memory responses carry selected `validity` segments; write/list responses also expose assertion
  history. Segment provenance includes assertion/revision IDs, speaking time, source occurrence,
  fact group, source payload and approximation status. Recall reports both resolved query times,
  the transaction watermark, and coverage limitations. Historical recall does not expose revisions
  learned after its cutoff.
- MCP adds temporal parameters plus `revise`/`revisions`; CLI adds `--as-of`, `--known-as-of`,
  `--valid-from`, `--valid-to`, `--check-conflicts`, `profile revise` and `profile revisions`.

## Extraction and semantic conflicts

Extraction supplies a source-message index and verbatim effective-date phrase in its payload.
Java verifies the phrase against that turn and resolves supported ISO dates, day references, and
calendar periods using shared `RelativeDates`. An explicit whole-month applicability can produce
month bounds; “sometime in May” cannot become May 1. Missing source references use a marked chunk
fallback. Unresolved timing and inferred defaults remain in provenance.

Assertion identity uses content identity, stable source occurrence and explicit temporal inputs.
Fallback wall-clock times do not change occurrence identity. Distinct source turns can assert the
same content independently; identical replays do not add assertion or transaction history.

Conversational ingestion checks semantic conflicts by default; direct remember opts in. Candidate
collection is bounded and uses exact keys, FTS, available graph entities and already stored
embeddings. The small model labels replacement, correction, equivalent, compatible or uncertain.
Automatic changes require one unambiguous target, the same literal subject/property, and grounded
quotes on both sides. Ambiguous, multivalued, malformed, timed-out or failed classifications retain
both assertions. A three-second deadline bounds model waiting. Code-derived memories stay outside
semantic matching. Semantic no-change decisions cannot fall through into near-duplicate invalidation.
Exact keys remain deterministic; direct writes without semantic checking retain the established
near-duplicate fallback, except for explicitly bounded temporal occurrences.

## Retrieval, forgetting and migration

Every memory channel and graph hop uses the shared temporal predicate before limits. Raw messages
and graph attachments have recorded timestamps and sequence watermarks separate from source speaking
time. Explicit temporal recalls omit unversioned symbol/code-graph channels and disclose that limit;
code-derived memory records remain searchable through memory channels. Synthesis receives resolved
query times and selected assertion provenance; EVIDENCE makes no chat-model calls.

Historical and scheduled assertions retain embeddings. SQLite vector recall filters eligible raw
embedding blobs before exact L2 ranking with `vec_distance_L2`. Its correlated eligibility lookup
starts at the memory's indexed revisions, then indexed segments; the opposite join order caused an
expensive profile-wide scan per candidate in scale testing.

Forgetting sets an independent marker, removes stored/indexed embeddings and queued work, and hides
the content from every cutoff, graph query, history listing and export. Forgotten assertions still
participate in resolution, so forgetting cannot resurrect competitors. Vectorization rechecks the
marker while holding the writer lock.

`V2__temporal_assertions.sql` follows the consolidated V1 baseline without modifying it. Migration
reconstructs approximate assertions from `created_at`, parseable `stated_at` and surviving successor
links. The actual live set anchors current visibility. Unlinked superseded rows are excluded because
legacy forgetting cannot be distinguished safely from supersession; unsupported ancestors cannot
become current during a later group resolution. Reconstructed assertions are marked approximate,
coverage gaps are recorded, and recoverable missing embeddings are queued without model calls.
Legacy reconstruction cannot recover corrections or provenance that the old schema never retained.

## Verification

Tests cover both interval axes and independent query defaults; future world times and invalid
requests; bounded/nested overrides and gaps; retroactive corrections; repeated content and replay;
optimistic concurrent corrections; graph attachment cutoffs; source-message cutoffs; semantic
compatibility/failure guards; forgetting and vector completion; fresh schema and legacy reconstruction;
profile isolation; export behavior; and vector eligibility under heavily ineligible nearest neighbors.

Local scalar-vector fixture measurements (September 12, 2026; four-dimensional vectors, five queries,
median; excludes model/network time):

| Memories | Current query | Historical query |
| --- | ---: | ---: |
| 5,000 | 5.9 ms | 6.3 ms |
| 50,000 | 63.6 ms | 71.4 ms |

The current fixtures have only 50 eligible rows and many closer ineligible vectors. Every query
returns ten eligible hits. These measurements establish filtering behavior and SQL scaling; they
are not end-to-end recall benchmarks at production embedding dimensions.

Run `./gradlew test`. Native compilation and deployment require explicit user authorization.
