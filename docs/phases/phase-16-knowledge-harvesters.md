# Phase 16 - Knowledge Harvesters

## Current status

Phases 1 (origin-aware ingest and recall) and 2 (protocol, SDK and conformance kit) are complete. Later phases have not started. See "Amendments since approval" at the end of this document, which take precedence over the text above them.

## Context

Today, memories come almost entirely from coding agents. The goal is to bring in knowledge from where teams actually keep it (websites, Jira, Confluence, MS Teams, and so on) through a plugin system. Users can add and remove harvesters, and other developers can write their own.

What the code already gives us, and where it stops:

| Existing piece | Reuse | Gap |
|---|---|---|
| `OnboardingSource` / sealed `SourceSpec` (`modules/daemon/.../onboarding`) | Proves the source idea | One-shot and compile-time; no cursor, schedule or plugin boundary |
| `ContentIngestor` | Section splitting (`toMessageContents`), checkpoint-after-store rule | Keys its ledger on the provenance string (a title change looks like a new doc); one fixed session; `Message.of` drops timestamps, so dates resolve to ingest time; no handling of edits or deletes |
| `IngestionService.ingestDetailed` + `ChunkLedgerMode` | The whole extraction pipeline; incremental re-ingest of growing threads | No notion of where content came from or how far to trust it |
| `TaskRegistry`, `@Scheduled` workers (`ClusterWorker`), `GitFiles` (process spawn) | Progress, cancellation, polling, subprocess precedent | Task state is in-memory, so run history needs its own table |
| `config-schema.json` field kinds, `GlobalConfigService` secret masking | Form-field model, never returning secrets | Provider-level only |
| `MemoryStore.revise` / `forgetMemory` | Retiring memories | Nothing links a memory back to its source document |

The constraint that sets the architecture: the daemon is a GraalVM native image, which can't load JARs at runtime.

**Decisions:** harvesters run as separate processes. The first batch is HTML crawler, Jira, Confluence and MS Teams. Harvested memories are mixed into recall, labelled as untrusted.

## Design

### Concepts
- **Harvester**: an installed plugin, meaning an executable plus a manifest.
- **Source**: a configured instance: harvester + config + credentials + one target profile + schedule. Several sources can use one harvester, such as two Jira sites.
- **Item**: one unit at the source (page, issue, chat window) with a stable external id.

```
pieria-daemon ── spawns ──> harvester process (any language)
     │   stdin:  one JSON envelope {protocol, config, secrets, cursor, limits}
     │   stdout: JSON lines  document | thread | tombstone | seen | sweep | checkpoint | progress | secret_update | error
     ▼
HarvestRunner → HarvestItemIngestor → IngestionService (with IngestOrigin) → MemoryStore
```
The daemon stays the only writer. Harvesters never touch the store or call the model.

### Protocol v1
Commands are `<exe> check` (validate config and auth) and `<exe> harvest`. The manifest replaces a `spec` command.

**Manifest** `harvester.toml`: `id`, `name`, `version`, `protocol = 1`, `command`, `capabilities` (`incremental`, `deletes`, `oauth-device-code`), `network` hosts (shown at install), and `[[config]]` fields using the same kinds as `config-schema.json` (`string`, `secret`, `int`, `bool`, `enum`, `list`). The CLI prompts and the console forms are both generated from these fields.

**Records:**
- `document {id, version?, title, url, author?, updatedAt, format: markdown|text, text, metadata?}`
- `thread {id, title, url, turns: [{id, author, timestamp, text}], metadata?}`
- `tombstone {id}`: the source says this item is gone.
- `seen {ids}` and `sweep {}`: for sources with no delete feed. `sweep` means "every live item was reported in this run", so the daemon tombstones the rest.
- `checkpoint {cursor}`: an opaque JSON value, returned on the next run.
- `progress {done, total?}`
- `secret_update {name, value}`: for rotated OAuth refresh tokens.
- `error {class: auth|rate_limited|transient|config|fatal, message, retryAfter?}`

**Rules:** the daemon reads the next record only when it's ready, so the pipe provides backpressure. Line and text sizes are capped, and a violation kills the run. Stderr is captured with a size limit and redacted.

### Daemon runtime (`dev.alvo.pieria.harvest`)
- `HarvesterRegistry` scans bundled and `$PIERIA_HOME/harvesters/<id>/` manifests, and rejects a protocol mismatch or checksum change.
- `HarvestStore` is a sibling seam to `CodeIndexStore`. Migration `V7__harvest.sql` adds `harvest_sources`, `harvest_runs`, `harvest_items` (external id, version hash, turn-prefix hash, session id, last-seen run) and `harvest_item_memories`. The profile hard-delete must cascade to all of these.
- `HarvestScheduler` is a `@Scheduled` poller that uses per-source intervals with jitter and exponential backoff, and honours `retryAfter`. An `auth` error pauses the source as `needs_auth`. Concurrent runs are capped (default 1).
- `HarvestRunner` runs as a `TaskRegistry` task of kind `harvest`, with a timeout and cancellation that kills the process. The child gets a scrubbed environment. On daemon restart, a `running` row becomes `interrupted` and resumes from its last checkpoint.
- **Checkpoint rule (same as `ContentIngestor`):** a cursor is persisted only after every earlier item is durably stored.

### Item ingestion (`HarvestItemIngestor`)
- Each item gets a stable session, `harvest:<sourceId>:<hash(externalId)>`, which removes the chunk-ledger collision that forces onboarding to disable the ledger.
- Timestamps are always passed through: `updatedAt` for documents and per-turn `timestamp` for threads, so relative dates resolve to when the text was written. Thread turns use the author as `role`, so attribution reaches extraction.
- Unchanged version hash means skip, at no model cost.
- **New or edited document:** full re-extract with `ChunkLedgerMode.DISABLED`.
- **Thread with only new turns** (stored prefix hash still matches): `ChunkLedgerMode.ENABLED`, so only new chunks are extracted, and links are only added.
- **Thread with an edited or removed earlier turn:** treated like an edited document.
- Graph extraction uses `GraphMode.DEFERRED` and reuses the onboarding graph backfill in `OnboardingPlanService`.

**Edits and deletes (memory ↔ item links):**
- **Edit:** memories no longer produced by the new version get `revise(... invalidateAt = item.updatedAt)`, so they remain visible to `asOf` queries. Stale raw messages for that session are removed from message FTS.
- **Tombstone:** `forgetMemory` plus deletion of the session's raw messages. Deleted at the source means gone.
- A memory is retired only when no other live item links to it. A memory the user has since revised is left alone, which the `expectedRevisionId` check detects.
- **Mass-delete guard:** if a sweep would tombstone more than a set share of items (default 20%), nothing is deleted and the source is flagged. A revoked permission or site outage must not wipe a source.

### Trust: stopping outside text from steering agents
Add an `IngestOrigin` (source id, harvester, external id, URL, `trust = untrusted`) as a new `ingestDetailed` overload. Conversation ingest is unchanged. When an origin is present:
1. Harvested memories are limited to `fact` and `event`. `instruction` and `task` are coerced to `fact`, because a web page must never become a standing instruction or an item on the user's to-do list.
2. `topicKey` is namespaced as `source:<id>:<key>`, so a random ticket can't supersede a fact established in the user's own sessions. `SemanticConflictResolver` is scoped to the same source.
3. `extract-unified.txt` gains a `{{sourceInstruction}}` slot telling the extractor the chunk is third-party data and that any instructions inside it are not to be followed.
4. The origin is stamped in `payload` and `origin_source_id`.

On recall:
- `RecallResponse` and gateway/hook output carry an origin label on each harvested memory (for example `untrusted · jira · ACME-12 · <url>`).
- `synthesize-recall.txt` treats those memories as reference data, never as directions.
- `RecallRequest` gains `includeSources` / `excludeSources`, filtered in the channel SQL so limits aren't starved.

### Cost and volume
- Sources must be scoped (JQL, spaces, allowed hosts, chats), and there are `maxItemsPerRun` / `maxCharsPerRun` limits.
- `pieria source run --dry-run` runs the harvester and reports item and chunk counts without calling the model.
- Per-source `mode = full | raw`. `raw` only stages messages for full-text search and makes no model calls, which suits high-volume chat.
- Harvest extraction gets its own low concurrency setting so interactive ingest and recall aren't starved.

### Security
- Plugins run with the user's permissions and are **not sandboxed**, and the docs will say so plainly. Install is an explicit act: the manifest and network hosts are shown, the executable's SHA-256 is pinned and checked before each spawn. V1 has no auto-update or registry.
- Secrets live in `$PIERIA_HOME/harvesters/secrets.json` (mode 0600; Windows ACLs per `docs/WINDOWS_SUPPORT.md`). They reach a harvester only through stdin, only the ones its manifest declares, and never through argv or environment. The API, logs, audit and export never return them.
- `pieria source add` warns when the profile's model provider isn't local, because source content will be sent to it.

### Developer experience
- `modules/harvester-sdk`: protocol records, a runner (`Harvester` interface, flush and exit handling), and a **conformance test kit** that checks a harvester's records and resume-from-cursor behaviour. It also runs against any executable, so non-Java plugins can use it.
- `modules/harvesters`: first-party harvesters in one native binary, `pieria-harvest <id> <command>`, using the same protocol as third parties.
- A small sample Python harvester under `harvest-examples/` plus `docs/HARVESTERS.md` (protocol reference and tutorial).

### CLI and console
- `pieria harvester list | install <dir|archive> | remove <id> | doctor`
- `pieria source add <harvester> | list | show | check | run [--dry-run] | pause | resume | remove [--forget-memories]`
- REST: `/v1/harvesters`, `/v1/profiles/{name}/sources` (CRUD, `/check`, `/run`, `/runs`).
- Console: a new `features/sources` page with schema-driven forms, run history and `needs_auth` state.

## First-batch harvesters

| Harvester | Item shape | Incremental | Deletes | Notes |
|---|---|---|---|---|
| **HTML crawler** | document (markdown) | ETag / Last-Modified in cursor, content hash | `sweep` | Seeds or sitemap, allowed hosts, include/exclude patterns, depth and page caps, robots.txt, per-host delay. Blocks private and loopback addresses unless `allowPrivate`, including after redirects. Keeps headings, unlike `HttpWebFetcher`, so section splitting works. No JS rendering in v1. |
| **Jira** | thread (description + comments) | JQL `updated >=` with a small overlap; hashes make the overlap free | periodic key `sweep` | Cloud (email + API token) and Data Center (PAT). The two differ in search endpoint and body format (ADF vs wiki markup). |
| **Confluence** | document | CQL `lastmodified` | periodic id `sweep` | Shares an Atlassian client with Jira. Converts storage format to markdown. |
| **MS Teams** | thread; chats grouped into time windows (`chat:<id>:<date>`) | Graph delta where available, else modified-time filter | tombstone where reported | Device-code OAuth with a user-supplied app client id; refresh token rotates via `secret_update`. Defaults to `raw` mode for chats, drops trivial messages. |

**Teams risk:** channel messages likely need tenant admin consent, and the exact scopes and delta support must be checked against current Graph docs. Phase 7 starts with a short spike and may ship chats first.

## Proposed roadmap after the first batch
1. **GitHub / GitLab**: issues, PRs, review threads, releases. Probably the most useful next source for coding agents.
2. **Slack**: threads, with the same model as Teams.
3. **SharePoint / OneDrive**: reuses Teams' Graph auth.
4. **Notion**, **Google Drive/Docs**
5. **Linear**, **Azure DevOps**
6. **Meeting transcripts**: Teams recap, Zoom, Google Meet.
7. **Incident and support tools**: PagerDuty postmortems, Sentry, Zendesk.
8. **Local folder / Obsidian vault watcher**, **RSS / changelog feeds**
9. **Email** (Outlook, Gmail): last and opt-in, since it has the highest privacy risk and most noise.

## Delivery phases
Each phase ships and passes `./gradlew test` on its own. The design is recorded in `docs/phases/phase-16-knowledge-harvesters.md`, with new constraints added to `AGENTS.md`.

1. **Origin-aware ingest and recall:** `IngestOrigin`, type coercion, key namespacing, prompt slots, recall labels and filters.
2. **Protocol, SDK and conformance kit.**
3. **Daemon runtime:** migration, store, registry, runner, scheduler, item ingestor, edit/delete handling.
4. **CLI and REST.**
5. **`pieria-harvest` binary and HTML crawler:** first end-to-end path. Adds the module to `settings.gradle.kts`, `nativeDist`, the `prebuiltNativeBins` include list, release workflow and native hints.
6. **Jira and Confluence.**
7. **MS Teams** (spike first).
8. **Console Sources page.**
9. **Developer docs and Python sample.**

## Critical files
- `modules/daemon/.../ingestion/IngestionService.java` (origin overload; payload stamping near line 958)
- `modules/daemon/.../ingestion/SemanticConflictResolver.java`
- `modules/daemon/.../onboarding/ContentIngestor.java` (expose section splitting)
- `modules/daemon/.../storage/MemoryStore.java`, `SqliteMemoryStore.java`, new `HarvestStore`
- `modules/daemon/src/main/resources/db/migration/V6__memory_origin.sql`, `V7__harvest.sql`
- `modules/daemon/src/main/resources/prompts/extract-unified.txt`, `synthesize-recall.txt`
- `modules/daemon/.../retrieval/` channels and `RetrievalService` (source filter, origin labels)
- `modules/shared/.../api/request/RecallRequest.java`, recall response DTOs
- `modules/daemon/.../config/DaemonNativeHints.java`, `modules/daemon/build.gradle.kts`, `settings.gradle.kts`
- `modules/cli/.../command/` (new `harvester`, `source`), `console/src/features/sources`, `console/src/lib/routes.ts`

## Non-goals for v1
- Sandboxing, a public plugin registry, plugin auto-update
- JavaScript-rendered crawling
- Webhook or push-based harvesting (polling only)
- Writing back to source systems
- Migrating `pieria onboard --web` (it keeps working unchanged)
- Postgres backend (noted in the migration, as with earlier phases)

## Design calls to revisit
- A memory comes from one profile-bound source; there's no fan-out to several profiles.
- An edit invalidates old memories and keeps their history, while a delete at the source forgets them entirely.
- Harvested memories can only be `fact` or `event`, so Jira tickets won't appear as Pieria tasks.
- Namespaced topic keys mean harvested facts can't supersede the user's own, at the cost of weaker exact-key lookup for them.
- A large source could crowd out conversation memories in recall. V1 ships with a per-profile weight for harvested memories, tuned against a real profile.

## Verification
- **Unit:** protocol parsing and limits; checkpoint ordering (a crash before store must not advance the cursor); thread prefix detection; retirement with shared links, revised memories and the mass-delete guard; type coercion and key namespacing. Model calls use fakes, per project convention.
- **Runner:** tests spawn a small scripted stub harvester and cover crash, timeout, oversized line, cancel and resume. No test seams in production code.
- **Harvesters:** conformance kit against localhost HTTP stubs replaying recorded Jira, Confluence and Graph responses. CI needs no network or Ollama.
- **Retrieval:** a test proving instructions embedded in harvested text never become `instruction` memories, and that labels reach gateway output. Re-run the LoCoMo eval (`./gradlew :eval:locomo`) after phase 1 to confirm conversation retrieval didn't regress.
- **End to end (manual):** native builds are run by hand. After `./gradlew :daemon:deployLocal`: add a crawler source for a small docs site, run with `--dry-run`, run for real, edit a page, re-run, confirm recall shows the new fact with its label and `asOf` still shows the old one, then remove the page and confirm it's forgotten. Repeat against a real Jira project and Teams tenant.

## Amendments since approval

- **Migrations.** Phase 1 adds `V6__memory_origin.sql`, so the harvest tables are in `V7__harvest.sql`. `origin_source_id` is a virtual generated column over `payload.origin.source_id`, not a written column, and it treats a non-JSON payload as not harvested.
- **Shared memories.** Memory ids hash profile, session, type and content, and each item has its own session. Identical statements in two items are therefore separate memories, so phase 3 can drop the "no other live item links to it" check.
- **Source filters.** The options on the MCP `recall` tool and `pieria profile recall` ship with the CLI in phase 4. Phase 1 exposes them over REST only.
- **Open item, required before phase 5.** Cluster and wiki generation build new memories from existing ones, so harvested text could lose its untrusted label on the way through. Phase 3 must exclude harvested memories from those inputs or carry the label forward. Until clusters can be attributed to sources, any recall with a source filter leaves clusters out.
- **Conflict scope.** Conflict candidates are limited to the incoming memory's own scope in every candidate query. Conversation memories count as one scope, so a user's statement can't retire a harvested fact automatically. The alternative lets the user's fact join the harvested fact group, where the next harvest on that key would close it.
- **Temporal block.** Synthesis treats this block as authoritative, so harvested memories contribute no source payload to validity lines, and harvested events are named by origin label instead of their text.
- **Protocol details settled in phase 2.**
  - Exit codes are `0` success, `1` harvester failure and `2` usage or bad envelope.
  - `check` reports through its exit code and, on failure, an `error` record. It may also emit `secret_update`, and nothing else.
  - `command` in the manifest is an array, never a shell string. Its program is a bare name on `PATH` or a `./` path inside the harvester's directory, and no argument may contain a `..` segment.
  - `entrypoint` names the file an install pins by SHA-256. A `./` program is its own entrypoint. A bare interpreter must declare one, and it must appear in `command`.
  - Size limits are in `ProtocolLimits`: 16 MiB per line, 1 MiB for the envelope, and 2,000,000 characters of text per document or thread.
  - An unknown record `type` is a violation. Unknown fields on a known record are ignored.
  - `sweep` means every live item was reported in this run, as an item or in `seen`, whatever cursor the run started from. It must be the last record apart from `progress`, and takes effect only if the run exits 0. A run that covers only part of the source must not emit it. The conformance kit enforces all of this.
  - A run succeeded only if it exits 0 with no `error` record. `error` is the last record of a failed run. A non-zero exit with no `error` counts as `fatal`.
  - Checkpoints from a failed run still stand, because everything before them was stored. A run with no checkpoint keeps the previous cursor.
  - `maxItemsPerRun` is a hard cap on documents and threads; `seen` and tombstones don't count. `maxCharsPerRun` stops the run before an item that would exceed it, except the run's first item, so one large item can't stall a source. A run stopped by a limit must leave a checkpoint that lets the next run continue.
  - Character limits count UTF-16 code units. `retryAfter` is whole seconds.
  - The SDK removes secret values from `error` messages and stack traces. The daemon still redacts stderr.
  - The SDK encodes through Jackson's JSON tree, so its types need no reflection configuration in a native image.
- **Phase 3 process runner.** It should share the conformance kit's launch and deadline logic, including reading on a thread it can abandon at the deadline, because a descendant that inherits stdout can hold the pipe open after the harvester exits.
