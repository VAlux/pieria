# Phase 17 - Hierarchical Profiles

## Current status

Implemented on the `feature/hierarchical-profiles` branch: schema, daemon recall, REST, CLI and the
console Profiles page.

## Context

Profiles were a flat list: each agent wrote to the profile derived from its repository, and a recall
read exactly one profile. Users want to group profiles like folders. For example, a `work` profile
holds `billing-api` and `billing-web`, and a recall on `work` answers from all three.

**Decisions** (settled with the user):

- **Only recall aggregates.** REST `/recall` (JSON and `text/plain`), the MCP `recall` tool and the
  console Recall page read the requested profile and everything below it. Browsing, search, export,
  sessions, topic keys, forget and revise stay per profile. Stats and the SessionStart line report
  the subtree total as well.
- **Ingestion is unchanged.** An agent keeps writing to the profile it always wrote to. Implicit
  creation on first write still makes a top-level profile.
- **Deleting a parent moves its children up**, to the grandparent or to top level. Only the
  deleted profile's own memories go.
- **Competing keyed facts are both kept.** When two children hold the same topic key, a parent
  recall returns both, each labelled with its owner, and synthesis is told they may legitimately
  disagree. Supersession stays per profile.

Other rules:

- The hierarchy is a tree: one parent at most, and no cycles. A cycle is refused with a 409.
- Names stay flat and unique. `ProfileResolver.normalize` forbids `/`, so the tree lives in a
  column rather than in the name.
- The tree is current state, not bi-temporal. A recall with `asOf` uses today's tree.
- A profile with no children runs exactly the SQL and the prompts it ran before.

## Design

### Schema

`V9__profile_hierarchy.sql` adds `profiles.parent_id` (nullable, references `profiles.id`) and an
index on it. `Profile` gains `parentId`. `MemoryStore` gains:

- `createProfile(name, parentId)`;
- `setProfileParent(id, parentId)`;
- `profileSubtree(id)`: a recursive CTE that returns the requested profile first, then its
  descendants ordered by depth and then name;
- `memoryOwnerNames(ids)`.

`ProfileService` resolves names and refuses a parent that lies inside the moved profile's own
subtree. `deleteProfile` re-parents the children before it deletes the profile row.

### Recall over a subtree

`RetrievalService` resolves `ProfileScope(primaryId, subtreeIds)` once, right after the transaction
watermark, and carries it on `RetrievalContext`. Channel threads bind it with
`MemoryStore.atProfileScope`, next to the snapshot and source-filter bindings. The fusion and usage
steps on the request thread bind it the same way.

The SQLite stores render each recall-path predicate through `ProfileScopeSql.matches(column, id)`.
That returns `column = ?` when no scope is bound or the scope holds one profile. Otherwise it
returns `(column = ? OR column IN ('<descendants>'))`, and the `?` still binds the requested id, so
no parameter lists change. Only the scope's primary id is widened: a query for any other profile
inside the scope keeps its plain predicate.

The widened reads are:

- the memory FTS, exact-key, vector and message-FTS channels;
- the graph seed, hop and memory lookups;
- the symbol and code-graph lookups, plus code-index presence;
- `embeddingsFor`, the cluster-claim search and `sumActiveSourceTokens`.

Message FTS now groups and joins on `(profile_id, session_id)`, so a session id used in two
profiles can't cross-match. Graph hops never cross profiles, because entity ids hash the profile.

Clusters belong to the profile their memory lives in:

- eligibility and covered-original checks use the memory's owning profile when it is inside the
  scope;
- relevant parents are walked per profile in the scope.

The parent's config drives the whole recall, and usage is recorded against the parent.

### Owner labels

When the scope holds several profiles, the fused candidates get `ownerProfile` from one
`memoryOwnerNames` query. The owner then appears in three places:

- **Synthesis:** each memory line is prefixed `[profile: name]`, and
  `profile-synthesis-guidance.txt` is added through the existing guidance slot.
- **Responses:** `MemoryResponse.profile` and `RecallResponse.MemorySource.profile` carry the owner;
  both are omitted when null.
- **Text recall:** the `text/plain` block labels each line the same way.

### API (version 3)

| Method | Path | Change |
|---|---|---|
| GET | `/v1/profiles` | Each row carries `parent`. |
| PUT | `/v1/profiles/{name}` | Optional body `{"parent": "…"}`. An unknown parent is a 404 and creates nothing. |
| PATCH | `/v1/profiles/{name}` | New. `{"parent": "…" \| null}` returns the summary. 404 for an unknown profile or parent, 409 for a cycle. Audited as `profile.update`. |
| GET | `/v1/profiles/{name}/stats` | Adds `parent`, `subProfiles` and `subtreeActive`. |

### CLI

- `profile create <name> --parent <p>` creates a profile under another one.
- `profile parent <name> <parent>` and `profile parent <name> --none` move a profile. Giving both or
  neither is a usage error (exit 2).
- `profile list` prints the tree with `├─`/`└─` connectors.
- `profile stats` shows the parent and a sub-profile total.
- The SessionStart pointer adds `(+M in N sub-profiles)`.

### Console

The Profiles page, `/p/{name}/profiles`, is reachable from the sidebar and the command palette.

- **Moving:** profiles move by native HTML5 drag and drop. Dropping on a row moves the profile
  under it, and a top-level drop zone appears while dragging. A per-row "Move to…" menu does the
  same from the keyboard and offers only valid parents. A drop that would make a cycle is ignored
  in the UI, and the daemon's 409 is shown as a toast.
- **Creating:** "New profile" checks the name against the resolver's rule (mirrored in
  `lib/profileName.ts`) and takes an optional parent. The same dialog is offered from the
  no-profiles screen, from the unknown-profile banner (prefilled), from the switcher, and from
  `?new=1`.
- **Deleting:** the delete dialog says which sub-profiles will move up, and where to.
- **Recall:** evidence from a sub-profile shows an owner badge and links to the memory in its own
  profile.

## Not in scope

- Aggregated browsing, search, export, wiki, clusters and graph explorer.
- Config inheritance from a parent.
- Bi-temporal history of the tree.
- Daemon-side validation of profile names (only the console checks them).
