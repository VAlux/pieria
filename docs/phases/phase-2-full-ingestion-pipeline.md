# Phase 2 - Full Ingestion Pipeline

## Objective

Replace the Phase 1 naive ingestion call with the full write pipeline: idempotent message storage, chunked extraction,
optional detail extraction, verification, classification, topic key generation, interrogative query generation,
supersession, and async vectorization.

## Scope

- Ingestion quality and durability only.
- SQLite remains the active backend.
- Retrieval can continue using the Phase 1 implementation until Phase 3.
- Vectorization outbox is implemented even if final vector search is enabled in Phase 3.

## Implementation Sequence

1. Introduce deterministic content IDs.
  - Message ID: SHA-256 over `sessionId`, role, and content, truncated to the chosen stable width.
  - Memory ID: SHA-256 over profile, type, canonical content, topic key, payload, and provenance fields that should
    define identity.
  - Add unit tests with fixed vectors so future changes cannot accidentally alter ID semantics.
  - Use insert-or-ignore behavior so re-ingesting the same transcript is safe.

2. Normalize transcript input.
  - Validate roles, content, timestamps, and session ID.
  - Preserve source order and line/message indexes for provenance.
  - Resolve obvious relative dates against the request timestamp where deterministic rules are enough.
  - Keep raw messages stored even when extraction returns no memories.

3. Add chunking.
  - Chunk around the planned 10K character target.
  - Preserve a two-message overlap between adjacent chunks.
  - Keep chunks aligned to message boundaries where possible.
  - Record chunk metadata so extraction outputs can be traced back to source messages.

4. Implement extraction passes.
  - Full pass: process chunks in parallel with a configurable maximum concurrency.
  - Detail pass: enable for conversations of 9 or more messages and focus on concrete values such as names, versions,
    prices, paths, entity attributes, and dates.
  - Merge extracted candidates by normalized content and source provenance.
  - Use Spring AI structured output parsing through `ModelGateway`.

5. Add verification.
  - Check extracted candidates against the source transcript.
  - Require verification output to be one of: pass, correct, or drop.
  - Capture verification reasons for logs and tests.
  - Drop unsupported or ambiguous memories rather than storing guesses.

6. Add classification and enrichment.
  - Classify each verified candidate as `fact`, `event`, `instruction`, or `task`.
  - Generate normalized `topic_key` for `fact` and `instruction`.
  - Generate 3-5 interrogative search queries for vector embedding.
  - Build `embed_text` from interrogative queries plus canonical declarative content.
  - Store heterogeneous fields in `payload` JSON.

7. Implement supersession for keyed memories.
  - In one transaction, find active memories with the same `(profile_id, topic_key)` for `fact` and `instruction`.
  - Mark prior memories superseded.
  - Insert the new memory with `supersedes` pointing to the previous active row when present.
  - Remove superseded vector rows inside the same transaction once vector storage is available.
  - Leave `event` and `task` memories append-only unless a later policy says otherwise.

8. Enqueue vector work.
  - Enqueue every active vector-eligible memory except `task`.
  - Store outbox rows transactionally with memory inserts.
  - Make retries explicit with `attempts`, last error logging, and configurable retry limits.
  - Ensure duplicate memory inserts do not create duplicate outbox work.

9. Add the vectorization worker.
  - Use virtual threads for blocking embedding calls.
  - Drain the transactional outbox in batches.
  - Generate embeddings with Spring AI embedding clients.
  - Persist embeddings through `MemoryStore` methods, even if the Phase 1 SQLite implementation stores them as
    pending/no-op until vector search is added.
  - Delete the outbox row only after the embedding write commits.

10. Expose ingestion observability.
- Log per-stage counts: messages, chunks, extracted candidates, verified candidates, stored memories, superseded
  memories, and vector jobs enqueued.
- Log per-stage latency and token usage where Spring AI exposes it.
- Do not send telemetry off-machine.

## Tests

- Unit tests for content-addressed IDs, chunk boundaries, overlap behavior, transcript normalization, and topic key
  normalization.
- Unit tests for classification mapping and interrogative query serialization into `embed_text`.
- Service tests using fake model responses for extraction, detail extraction, verification, and classification.
- Storage integration tests for idempotent ingest, supersession chains, transaction rollback, and outbox enqueue
  behavior.
- Worker tests for batch draining, retry increments, and successful outbox deletion.
- API tests proving repeated `POST /ingest` calls do not duplicate messages or memories.
- Run `./gradlew test`.

## Acceptance Criteria

- Re-ingesting the same transcript is idempotent.
- Long transcripts are chunked and processed without losing source provenance.
- Unsupported extractions are dropped during verification.
- Facts and instructions supersede previous active memories with the same topic key in one transaction.
- Vector-eligible memories are enqueued for embedding; tasks are not.
- The ingest endpoint returns without waiting for vectorization to finish.

## Risks And Follow-Ups

- Structured output prompts must be versioned or kept stable enough for deterministic tests with fake model responses.
- Embedding dimensions become a storage compatibility concern once vector rows are persisted.
- Phase 3 must consume `embed_text`, outbox embeddings, and supersession state rather than inventing a parallel
  retrieval model.
