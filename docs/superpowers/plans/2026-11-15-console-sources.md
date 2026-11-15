# Harvesters Phase 8: Console Sources Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the web console a per-profile Sources page. From it you can add, edit, run, dry-run, pause, resume, sign in, check and remove harvest sources, read their run history, and see which harvesters are installed.

**Architecture:**
- **Console-only.** Every endpoint already exists from phases 4 and 7, so the daemon is not touched.
- **API module.** `console/src/api/sources.ts` wraps `/v1/harvesters` and `/v1/profiles/{name}/sources/...` in the console's typed client.
- **Pure form model.** `features/sources/form.ts` turns a harvester's declared fields into form state, and form state into the request body. It holds every parsing and diffing rule, so the components stay thin and the rules are unit-tested.
- **Three routes:**
  - `sources`: the list of sources and installed harvesters;
  - `sources/new`: the add form;
  - `sources/:sourceId`: detail, with settings, actions, run history and sign-in.

**Tech Stack:** React 19, react-router 8, TanStack Query 5, TypeScript 5.9, Vite 8, Vitest 5 with happy-dom and Testing Library, CSS modules.

**Spec:** `docs/phases/phase-16-knowledge-harvesters.md`. The relevant parts are:
- "CLI and console": a console `features/sources` page with schema-driven forms, run history and `needs_auth` state;
- "Delivery phases", item 8;
- "Amendments since approval", including phase 7's `authorize` flow.

**Settled with the user for this phase:**
- **Sources only.** Installed harvesters are listed read-only. Installing and removing harvesters stays in the CLI (`pieria harvester install|remove`), because it needs a filesystem path and an explicit trust decision.
- **Secrets are write-only.** Secret fields are password inputs on the add form, and each set secret has a Replace action on the detail page. A value is sent to the daemon once and never shown back. The page lists secret names only.

**REST surface used (all existing):**

| Call | Purpose |
|---|---|
| `GET /v1/harvesters` → `{harvesters: Harvester[]}` | installed harvesters and their fields |
| `GET /v1/profiles/{p}/sources` → `{sources: Source[]}` | list |
| `POST /v1/profiles/{p}/sources` (201) → `Source` | create |
| `GET`, `PATCH` → `Source`; `DELETE ?forgetMemories=` (204) on `…/sources/{id}` | read, update, remove |
| `POST …/{id}/check` → `{ok, errorClass, message}` | check |
| `POST …/{id}/run` `{dryRun}` (202) → `{taskId}` | run |
| `GET …/{id}/runs?limit=` → `{runs: SourceRun[]}` | history |
| `POST …/{id}/pause`, `…/resume`, `…/accept-sweep` → `Source` | status changes |
| `PUT …/{id}/secrets` `{secrets}` (204) | replace secrets |
| `POST` and `GET …/{id}/authorize` → `SourceAuthorization` | sign-in |
| `GET /v1/tasks/{taskId}` → `{status, result, errorMessage, …}` | dry-run outcome |

The wire shapes are the records in `modules/shared/src/main/java/dev/alvo/pieria/api/response/`:
- `SourceResponse`, `SourceRunResponse`;
- `HarvesterResponse`, with its nested `Field`;
- `SourceCheckResponse`, `SourceAuthorizationResponse`, `TaskStatusResponse`.

The request shapes are `CreateSourceRequest` and `UpdateSourceRequest`, in `…/api/request/`.

## Global Constraints

- **Process:**
  - Work on branch `harvesters-phase-8`.
  - Before every commit, run `cd console && npm test && npm run typecheck`, then `./gradlew test`, as AGENTS.md requires.
  - Never run `nativeCompile`, `nativeDist` or `deployLocal`.
- **No daemon or Java changes.** If a task finds it needs one, that is a ruling to record, not a silent addition.
- **Requests go through `api/client.ts`** (`request`, `send`, `query`, `profilePath`), which keeps the `X-Pieria-Client: console` and `X-Pieria-Channel: console` headers. No other `fetch` calls.
- **Secrets:**
  - Never placed in a query key, a URL, `localStorage`, a toast or an error message.
  - Never read back from the daemon, which can't return them anyway.
  - Inputs are `type="password"` with `autoComplete="new-password"`.
  - After a submit, the field is cleared.
- **Untrusted text stays literal.** Source text, harvester error messages, `stderrTail`, config values and Microsoft's sign-in message are rendered as React text (`<pre>` for stderr). Never Markdown, never `dangerouslySetInnerHTML`.
- **Sign-in links.** The verification link opens only when it starts with `https://`, with `target="_blank" rel="noopener noreferrer"`.
- **Conventions:**
  - Query keys are profile-scoped, starting `['profile', name, 'sources', …]`.
  - The installed-harvester list is `['harvesters']`.
  - Server errors are shown with `ErrorBanner` or the `errorMessage(e)` text.
  - Pages use `Page`, `PageHeader`, `Card`, `Badge`, `Pill`, `EmptyState`, `SkeletonRows`, `Dialog` and `useToast` from `console/src/ui`.
  - Styling goes in one `sources.module.css` that uses `styles/tokens.css` variables.
  - Tests live beside the code as `*.test.ts(x)`, use `mockApi` and `renderAt` from `src/test/render.tsx`, and keep the existing `vitest` setup.

## Review Focus

1. **A secret value leaks back into the page.**
   - After a Replace, or after adding a source, the typed value must not remain in an input, the query cache, the URL or a toast.
   - The PATCH body for a settings edit never carries a secret key.
   - Pinned in Tasks 2 and 5.
2. **The daemon answers 409 because the source is running,** on an update, a remove or a second run. The message is shown, the form keeps its edits, and nothing else changes. Pinned in Task 5.
3. **A source whose harvester is no longer installed.** The list and detail pages still render from the stored config, with a warning, and offer only Remove and Pause or Resume. They don't crash on the missing field list. Pinned in Tasks 3 and 5.
4. **A source whose harvester can't sign in**, or a `needs_auth` source whose harvester uses a token.
   - Sign in is offered only when the harvester declares `oauth-device-code`.
   - A `needs_auth` source otherwise points to Replace on its secret.
   - Pinned in Tasks 3 and 5.
5. **The user closes the sign-in dialog while it is pending, or the code expires.** Polling stops when the dialog unmounts. The page doesn't keep calling `GET …/authorize` in the background. Signing in again starts a new session, which the daemon makes replace the old one. Pinned in Task 6.

---

### Task 1: The sources API module

**Files:**
- Create:
  - `console/src/api/sources.ts`
  - `console/src/api/sources.test.ts`
- Modify:
  - `console/src/api/tasks.ts`, which gains `getTask`
  - `console/src/api/queries.ts`, which gains query keys

**Interfaces:**
- **Produces, in `api/sources.ts`, the wire types:**
  - `HarvesterField { key; kind: 'string'|'secret'|'int'|'bool'|'enum'|'list'; label; hint: string|null; required: boolean; options: string[]; defaultValue: unknown }`
  - `Harvester { id; name; version; capabilities: string[]; network: string[]; fields: HarvesterField[]; bundled: boolean|null }`
  - `SourceRun { id; dryRun; status: 'running'|'succeeded'|'failed'|'cancelled'|'interrupted'; startedAt: string; finishedAt: string|null; itemsSeen; itemsChanged; itemsDeleted; memoriesStored; chars; errorClass: string|null; errorMessage: string|null; stderrTail: string|null }`
  - `Source { id; profile; harvester; config: Record<string, unknown>; secretNames: string[]; mode: 'full'|'raw'; intervalSeconds: number; maxItemsPerRun: number|null; maxCharsPerRun: number|null; status: 'active'|'paused'|'needs_auth'; nextRunAt: string|null; consecutiveFailures: number; sweepBlockedCount: number|null; acceptNextSweep: boolean; running: boolean; warnings: string[]; lastRun: SourceRun|null }`
  - `SourceCheck { ok: boolean; errorClass: string|null; message: string|null }`
  - `SourceAuthorization { status: 'pending'|'authorized'|'failed'; verificationUri: string|null; userCode: string|null; message: string|null; expiresAt: string|null; errorClass: string|null; error: string|null }`
  - `CreateSource { id; harvester; config: Record<string, unknown>; secrets: Record<string, string>; mode: 'full'|'raw'; intervalSeconds: number|null; maxItemsPerRun: number|null; maxCharsPerRun: number|null; start: boolean }`
  - `UpdateSource { config?: Record<string, unknown>; mode?: 'full'|'raw'; intervalSeconds?: number; maxItemsPerRun?: number; maxCharsPerRun?: number; resetCursor?: boolean }`
- **Produces, in `api/sources.ts`, these functions.** Each takes an optional trailing `signal?: AbortSignal` where it reads.
  - `listHarvesters(): Promise<Harvester[]>`
  - `listSources(profile): Promise<Source[]>`
  - `getSource(profile, id): Promise<Source>`
  - `createSource(profile, body: CreateSource): Promise<Source>`
  - `updateSource(profile, id, body: UpdateSource): Promise<Source>`
  - `removeSource(profile, id, forgetMemories: boolean): Promise<void>`
  - `checkSource(profile, id): Promise<SourceCheck>`
  - `runSource(profile, id, dryRun: boolean): Promise<string>`, which returns the task id
  - `listRuns(profile, id, limit = 20): Promise<SourceRun[]>`
  - `pauseSource`, `resumeSource`, `acceptSweep (profile, id): Promise<Source>`
  - `setSecrets(profile, id, secrets: Record<string, string>): Promise<void>`
  - `startAuthorization(profile, id): Promise<SourceAuthorization>`
  - `authorizationStatus(profile, id): Promise<SourceAuthorization>`
  - `canSignIn(h: Harvester | undefined): boolean`, which is `h?.capabilities.includes('oauth-device-code') ?? false`
- **Produces, in `api/tasks.ts`:**
  - `TaskStatus { status: TaskStatus; result: Record<string, unknown> | null; errorKind: string|null; errorMessage: string|null }`
  - `getTask(id, signal?): Promise<TaskStatus>`, which calls `GET /v1/tasks/{id}`
- **Produces, in `qk`:**
  - `harvesters: ['harvesters']`
  - `sources: (p) => ['profile', p, 'sources']`
  - `source: (p, id) => ['profile', p, 'sources', id]`
  - `sourceRuns: (p, id) => ['profile', p, 'sources', id, 'runs']`

- [ ] **Step 1: Write the failing tests in `api/sources.test.ts`**

Use `mockApi` from `src/test/render.tsx`, and `vi.restoreAllMocks()` in `afterEach`.
- `createPostsTheDraftToTheProfile`: `createSource('my p', {…})` POSTs to `/v1/profiles/my%20p/sources`, with a JSON body that equals the draft, `Content-Type: application/json` and `X-Pieria-Client: console`.
- `removeSendsForgetMemories`: `removeSource('p', 's', true)` sends `DELETE /v1/profiles/p/sources/s?forgetMemories=true`.
- `runSendsDryRunAndReturnsTheTask`: the stub answers `{taskId: 't1'}`. The body is `{"dryRun":true}`, and the result is `'t1'`.
- `secretsArePut`: `setSecrets('p', 's', {token: 'x'})` is a `PUT` to `…/s/secrets` with body `{"secrets":{"token":"x"}}`.
- `idsAreEncoded`: `getSource('p', 'a/b')` requests `/v1/profiles/p/sources/a%2Fb`.
- `canSignInReadsTheCapability`.

- [ ] **Step 2: Run and see them fail**

Run: `cd console && npx vitest run src/api/sources.test.ts`
Expected: FAIL, because `./sources` cannot be resolved.

- [ ] **Step 3: Implement `api/sources.ts`, `getTask` and the query keys**

The functions are thin `request` calls. Lists unwrap their `{sources}`, `{harvesters}` or `{runs}` envelope, and an empty or absent envelope gives `[]`.

- [ ] **Step 4: Run the console suite and typecheck**

Run: `cd console && npm test && npm run typecheck`
Expected: PASS.

- [ ] **Step 5: Commit**, with the plan file included

```bash
git add docs/superpowers/plans/2026-11-15-console-sources.md console/src/api
git commit -m "Add a console API module for harvest sources"
```

---

### Task 2: The source form model

**Files:**
- Create:
  - `console/src/features/sources/form.ts`
  - `console/src/features/sources/form.test.ts`

**Interfaces:**
- Consumes: Task 1's `HarvesterField`, `Harvester`, `Source`, `CreateSource` and `UpdateSource`.
- **Produces:**
  - `type FieldValues = Record<string, string | boolean>`. Every non-bool field holds the text the user typed. A `list` is one item per line.
  - `initialValues(fields: HarvesterField[], config?: Record<string, unknown>): FieldValues`:
    - A value from `config` wins over `defaultValue`. Arrays join with `\n`, numbers become strings, bools stay bools.
    - A bool with neither is `false`. Any other field with neither is `''`.
    - Secret fields always start `''`.
  - `parseValues(fields, values): { config: Record<string, unknown>; secrets: Record<string, string>; errors: Record<string, string> }`. Per kind:
    - **`string` and `enum`:** stripped text, omitted when blank. An enum value outside `options` gives the error ``${label} must be one of ${options.join(', ')}``.
    - **`int`:** must match `/^-?\d+$/`, else ``${label} must be a whole number``. It becomes a number, and blank is omitted.
    - **`bool`:** always included.
    - **`list`:** split on newlines and commas, strip each, drop blanks; omitted when empty. Commas are accepted as well, so pasted CLI values work.
    - **`secret`:** goes to `secrets`, never to `config`, and is omitted when blank.
    - **Required fields:** a required field omitted after parsing gives ``${label} is required``. A required secret is exempt when `secretOptional` is set.
    - Signature: `parseValues(fields, values, { secretOptional?: boolean })`.
  - `configChanges(fields, before: Record<string, unknown>, after: Record<string, unknown>): Record<string, unknown>`:
    - It returns the keys whose value differs, compared as JSON.
    - A key in `before` that is absent from `after` becomes `null`, because PATCH merges and null clears.
    - Secret keys are never included.
  - `parseInterval(text: string): { seconds?: number; error?: string }` accepts `90`, `30m`, `6h` or `1d`, like the CLI.
    - Under 60 seconds gives `The interval must be at least 60 seconds`.
    - Anything else gives `Use a form like 30m, 6h or 1d`.
  - `formatInterval(seconds: number): string` gives the largest whole unit: `1d`, `6h`, `30m` or `90s`.
  - `SOURCE_ID_PATTERN = /^[a-z0-9][a-z0-9_-]{0,63}$/`, the same as `MemoryOrigin.SOURCE_ID_REGEX`. A bad id gives `Use lowercase letters, digits, - and _ (start with a letter or digit)`.

- [ ] **Step 1: Write the failing tests in `form.test.ts`**

Use Teams-like fields: `clientId` (string, required), `includeChats` (bool, default `true`), `channels` (list), `sinceDays` (int, default `90`), `refreshToken` (secret), `mode` (enum `["a","b"]`), and Jira's `token` (secret, required).
- `initialValuesPreferConfigOverDefaults`: config `{sinceDays: 30, channels: ['Eng/Ops','Sales']}` gives `sinceDays: '30'`, `channels: 'Eng/Ops\nSales'` and `includeChats: true` (the default). A secret is `''`, even when the config somehow holds that key.
- `parseSplitsListsOnLinesAndCommas`: `'Eng/Ops\n Sales , \n'` gives `['Eng/Ops', 'Sales']`.
- `parseKeepsSecretsOutOfConfig`: the secrets hold `{token: 'x'}`, and the config has no `token`.
- `parseReportsEveryError`: a bad int, an unknown enum and a missing required field all appear in `errors`, with the exact messages above.
- `secretOptionalSkipsTheRequiredSecret`.
- `configChangesClearsRemovedKeysAndSkipsSecrets`: before `{a:'1', b:'2', token:'x'}`, after `{a:'1', c:'3'}` gives `{b: null, c: '3'}`.
- `intervalRoundTrips`:
  - `parseInterval('6h')` gives `21600`, and `formatInterval(21600)` gives `'6h'`.
  - `parseInterval('30')` gives the 60-second error.
  - `parseInterval('soon')` gives the format error.
- `sourceIdPattern`: `teams-chats` is accepted; `Teams` and `-x` are refused.

- [ ] **Step 2: Run and see them fail**

Run: `cd console && npx vitest run src/features/sources/form.test.ts`
Expected: FAIL, because `./form` cannot be resolved.

- [ ] **Step 3: Implement `form.ts`**

- [ ] **Step 4: Run the console suite and typecheck**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add console/src/features/sources/form.ts console/src/features/sources/form.test.ts
git commit -m "Turn harvester fields into source forms and back"
```

---

### Task 3: The Sources page and its navigation

**Files:**
- Create:
  - `console/src/features/sources/SourcesPage.tsx`
  - `console/src/features/sources/SourcesPage.test.tsx`
  - `console/src/features/sources/status.ts`, with its test `status.test.ts`
  - `console/src/features/sources/sources.module.css`
- Modify:
  - `console/src/routes.tsx`, which gains `sources`, `sources/new` and `sources/:sourceId`, lazily loaded the way `tasks` is
  - `console/src/lib/routes.ts`, whose `switchedPath` keep-list gains `'sources'`; and `lib/routes.test.ts`
  - `console/src/features/shell/Sidebar.tsx`: under Operations, `Sources`, with icon `activity` unless a better existing one fits, at `url('sources')`
  - `console/src/features/shell/useNavBadges.tsx`: a `sources` badge counting sources that need attention
  - `console/src/features/shell/CommandPalette.tsx`: a `['Sources', 'sources', …]` navigation entry

**Interfaces:**
- Consumes: Task 1's `listSources`, `listHarvesters`, `qk.sources`, `qk.harvesters` and `canSignIn`.
- **Produces, in `status.ts`:**
  - `statusLabel(source, signIn: boolean): { label: string; tone: Tone }`, where `signIn` is `canSignIn(harvester)`:
    - `running` gives `Running` (warn);
    - `needs_auth` gives `Needs sign-in` (fail) when `signIn`, else `Credentials refused` (fail);
    - `paused` gives `Paused` (neutral);
    - active with `lastRun?.status === 'failed'` gives `Failing` (fail);
    - otherwise `Active` (ok).
  - `needsAttention(source): boolean`: true for `needs_auth`, a failed last run, or a non-null `sweepBlockedCount`.
  - `runSummary(run: SourceRun | null): string`:
    - `null` gives `Never run`.
    - Otherwise: ``${dryRun ? 'Dry run' : 'Run'} ${status} · ${itemsChanged} changed of ${itemsSeen} · ${relativeTime(finishedAt ?? startedAt)}``.
    - The `itemsDeleted` part, ` · ${itemsDeleted} deleted`, is added only when it is over 0.
- **`SourcesPage` (`/p/:profile/sources`):**
  - **Header:** crumb `Operations`, title `Sources`, action `Add source`, a `LinkButton` to `sources/new`.
  - **Sources card:** one row per source, sorted by id. Each row shows:
    - the id, as a link to `sources/{id}`;
    - the harvester's `name`, or its id with the hint `not installed` when absent from `listHarvesters`;
    - the `statusLabel` badge, and `raw` when the mode is raw;
    - `runSummary(lastRun)` and the next run time.
    - A `needs_auth` row adds `Sign in` when `canSignIn(harvester)`, else `Replace credentials`. Both link to the detail page with `?signin=1` or `?secrets=1`.
    - A row with `sweepBlockedCount` adds `Deletions held back: N`.
  - **Empty state:** with no sources, `EmptyState` titled `No sources yet`, with copy naming the installed harvesters and the same Add button.
  - **Installed harvesters card**, read-only: name, id, version, a `bundled` badge, capabilities and network hosts. Its footnote says `Install or remove harvesters with pieria harvester install and pieria harvester remove.`
  - **Refresh:** every 2 s while any source is `running`, else every 15 s, like `TasksPage`.

- [ ] **Step 1: Write the failing tests**

`status.test.ts`:
- `labelsEachState`, with one assertion per branch above, including both `needs_auth` labels.
- `attentionCoversAuthFailuresAndHeldSweeps`.
- `runSummaryMentionsDeletionsOnlyWhenThereAreSome`.

`SourcesPage.test.tsx`, with `mockApi` routes for `/v1/harvesters` and `/v1/profiles/p/sources`, rendered with `renderAt('/p/:profile/sources', '/p/p/sources', <SourcesPage />)`:
- `listsSourcesWithStatusAndLastRun`: two sources, `docs` (active, a succeeded last run) and `teams-chats` (`needs_auth`, harvester `teams` with `oauth-device-code`). The page shows both ids, `Needs sign-in`, and a `Sign in` link to `/p/p/sources/teams-chats?signin=1`.
- `sourceOnAMissingHarvesterStillRenders`: harvester id `gone` is not in the list. The row shows `not installed`, and nothing throws.
- `emptyProfileOffersAdd`: no sources gives `No sources yet` and an `Add source` link to `/p/p/sources/new`.
- `harvestersAreListedReadOnly`: the harvester card lists `Microsoft Teams` with `oauth-device-code`, and there is no install button (`queryByRole('button', {name: /install/i})` is null).

`lib/routes.test.ts`: `switchedPath('/p/a/sources/s1', 'b')` gives `/p/b/sources`.

- [ ] **Step 2: Run and see them fail**

Run: `cd console && npx vitest run src/features/sources src/lib/routes.test.ts`
Expected: FAIL; the modules don't exist, and `switchedPath` drops `sources`.

- [ ] **Step 3: Implement `status.ts`, `SourcesPage`, the routes, the sidebar item, the badge and the palette entry**

- **Routes.** Point `sources/new` and `sources/:sourceId` at temporary placeholders that render `SourcesPage`. Tasks 4 and 5 replace them.
- **Sidebar badge.** It uses the existing `qk.sources(profile)` query and shows the `needsAttention` count, in the same style as the tasks badge, only when it is over 0.

- [ ] **Step 4: Run the console suite and typecheck**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add console/src
git commit -m "Add the console Sources page"
```

---

### Task 4: Add a source

**Files:**
- Create:
  - `console/src/features/sources/SourceFields.tsx`
  - `console/src/features/sources/AddSourcePage.tsx`
  - `console/src/features/sources/AddSourcePage.test.tsx`
- Modify: `console/src/routes.tsx`, so `sources/new` renders `AddSourcePage`

**Interfaces:**
- Consumes:
  - Task 1's `listHarvesters`, `createSource`, `checkSource`, `canSignIn` and `qk`;
  - Task 2's `initialValues`, `parseValues`, `parseInterval`, `formatInterval` and `SOURCE_ID_PATTERN`.
- **Produces `SourceFields({ fields, values, errors, onChange, secretPlaceholder? })`.** It renders one labelled control per field, with its `hint` and its `required` mark. Each control is reachable by its label.
  - **string:** `TextInput`;
  - **int:** `TextInput`, `inputMode="numeric"`;
  - **bool:** `Switch`;
  - **enum:** `Select`, or `Segmented` for at most 3 options;
  - **list:** `TextArea`, with the hint suffix `One per line.`;
  - **secret:** `TextInput type="password" autoComplete="new-password"`, with the placeholder `secretPlaceholder ?? ''`.
  - An error in `errors[key]` shows as `role="alert"` under its control.
- **`AddSourcePage` (`/p/:profile/sources/new`):**
  1. **Harvester.** A `Select` of installed harvesters; `?harvester=<id>` preselects one. Changing it resets the field values.
  2. **Source id**, checked against `SOURCE_ID_PATTERN`.
  3. **The harvester's fields**, through `SourceFields`. When the harvester `canSignIn`, the `refreshToken` field is hidden, and a note says `You'll sign in after the source is added.`
  4. **Run settings:**
     - Mode, a `Segmented` of `Full`/`Raw`, with a hint for raw: `Only makes the text searchable; no model calls.`
     - Interval, default `6h`.
     - Max items and max characters, both optional and positive whole numbers.
     - A `Start on schedule now` checkbox, default off.
  5. **Submit** (`Add source`):
     - On a client-side error, nothing is sent and every error shows.
     - Otherwise it runs `createSource` with `secretOptional: canSignIn(h)`.
     - On success, secret inputs are cleared and `qk.sources` is invalidated.
     - Each of `created.warnings` is shown as a toast.
     - **When `canSignIn(h)` and no secret was given,** it navigates to `sources/{id}?signin=1`.
     - **Otherwise** it runs `checkSource`. When the check passes, it navigates to `sources/{id}`. When it fails, it navigates to `sources/{id}?check=failed` and shows a toast, `The check failed: ${message}`.
     - **An `ApiError`** from create, such as 409 `source already exists`, shows an `ErrorBanner` and keeps every value apart from the secrets.

- [ ] **Step 1: Write the failing tests in `AddSourcePage.test.tsx`**

Use a `jira` fixture harvester with fields `baseUrl` (label `Base URL`, required string), `token` (label `API token`, required secret), `jql` (label `Which issues`, required string) and `sweepHours` (int, default 24), and the `teams` fields with `oauth-device-code`.
- `requiredFieldsBlockTheSubmit`: submitting empty shows `Base URL is required` (from the field label) and the id error, and no `POST` is made.
- `addsAJiraSourceAndRunsTheCheck`:
  - Fill in id `acme`, `baseUrl`, `jql` and `token` `s3cret`, then submit.
  - The `POST` body has `config` `{baseUrl, jql, sweepHours: 24}`, `secrets` `{token: 's3cret'}`, `mode` `full`, `intervalSeconds` `21600` and `start` `false`.
  - Then `POST …/acme/check` is called, and the location becomes `/p/p/sources/acme`.
  - The text `s3cret` is nowhere in `document.body.textContent`.
- `signInHarvesterSkipsTheCheck`: with `teams`, there is no refreshToken input, the submit makes no `/check` call, and the location becomes `/p/p/sources/t?signin=1`.
- `failedCheckLandsOnTheSource`: the check returns `{ok: false, errorClass: 'auth', message: 'denied'}`. The location ends with `?check=failed`, and the toast text contains `denied`.
- `conflictKeepsTheForm`: create answers 409 `{error:'conflict', message:'source already exists: acme'}`. The banner shows that message, the `baseUrl` input still holds its value, and the `token` input is empty.

- [ ] **Step 2: Run and see them fail**

Run: `cd console && npx vitest run src/features/sources/AddSourcePage.test.tsx`
Expected: FAIL, because `./AddSourcePage` cannot be resolved.

- [ ] **Step 3: Implement `SourceFields` and `AddSourcePage`, and wire the route**

`mockApi` answers 200 for every route. For the 409 case, add `export function reply(status: number, body: unknown)` to `src/test/render.tsx`. It returns a marked object, which `mockApi` sends with that status. Route values that aren't marked behave as before, so existing tests don't change.

- [ ] **Step 4: Run the console suite and typecheck**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add console/src
git commit -m "Add sources from the console with schema-driven forms"
```

---

### Task 5: Source detail: settings, actions and run history

**Files:**
- Create:
  - `console/src/features/sources/SourceDetailPage.tsx`
  - `console/src/features/sources/RunHistory.tsx`
  - `console/src/features/sources/SourceDetailPage.test.tsx`
- Modify: `console/src/routes.tsx`, so `sources/:sourceId` renders `SourceDetailPage`

**Interfaces:**
- Consumes:
  - Task 1's `getSource`, `updateSource`, `removeSource`, `runSource`, `getTask`, `listRuns`, `pauseSource`, `resumeSource`, `acceptSweep`, `setSecrets`, `checkSource`, `canSignIn` and `qk`;
  - Task 2's `initialValues`, `parseValues`, `configChanges`, `parseInterval` and `formatInterval`;
  - Task 4's `SourceFields`;
  - Task 6's `SignInDialog`, which is wired at the end of Task 6.
- **`SourceDetailPage` (`/p/:profile/sources/:sourceId`):**
  - **Header:** crumb `Sources`, as a link back; the title is the id; the meta holds the status badge, the harvester's name and the mode. The actions are:
    - `Run now`;
    - `Dry run`;
    - `Pause` or `Resume`, by status;
    - `Check`;
    - `Sign in`, only when `canSignIn`;
    - `Remove`.
  - **When `running`,** `Run now`, `Dry run` and `Remove` are disabled, through `DisabledHint` with the reason `A run is in progress.` The source refetches every 2 s while it runs, else every 15 s.
  - **Banners**, each with its own action:
    - **`needs_auth`:**
      - with sign-in: `This source needs you to sign in again.` and `Sign in`;
      - otherwise: `The source's credentials were refused. Replace them below, then resume it.`
    - **`sweepBlockedCount`:** `The last full run would delete N items, more than the safety limit allows. Nothing was deleted.`, with `Allow these deletions` (`acceptSweep`). When `acceptNextSweep` is already true, it reads `Allowed; the next full run will delete them.` with no button.
    - **`?check=failed`, or a failed `Check`:** the check's message.
  - **Settings card:**
    - It holds `SourceFields` from `initialValues(fields, source.config)`, without secret fields, plus mode, interval and limits.
    - `Save` stays disabled until something changes.
    - Saving sends `updateSource` with `config: configChanges(...)`, plus only the changed run settings, and a toast `Saved.`.
    - A `Reset cursor` checkbox maps to `resetCursor: true`, with the hint `The next run starts over from the beginning.`
    - **When the harvester isn't installed,** the card shows the stored config as read-only key/value text, with `The harvester isn't installed, so these settings can't be edited.`
  - **Credentials card:**
    - One row per declared secret field. A row shows `Set` when its key is in `secretNames`, else `Not set`, plus a `Replace` button.
    - Replace opens a `Dialog` with one password input. Its `Save` calls `setSecrets({[key]: value})`, clears the input, closes, and toasts `Credential replaced.`
    - `?secrets=1` opens the dialog of the first secret field.
    - `?signin=1` opens `SignInDialog` when `canSignIn`, and the Credentials card hides `refreshToken` for such harvesters.
  - **Actions:**
    - **Run now** calls `runSource(false)`, toasts `Run started.` and invalidates the source.
    - **Dry run** calls `runSource(true)`, then polls `getTask` every second while `RUNNING`. It then shows a panel: ``${itemsSeen} items, ${itemsChanged} changed, ${chars} characters · about ${estimatedChunks} chunks to extract``, read from `result`. A failed task shows its `errorMessage`.
    - **Pause and Resume** call their endpoints.
    - **Check** shows `The check passed.` or the failure.
  - **Remove** opens a `Dialog`. It has a checkbox, `Also forget the memories this source added`, off by default, and a `Remove source` danger button. On success it invalidates `qk.sources` and navigates to `sources`.
  - **Every mutation's `ApiError`,** including 409 `source s is running; try again when it finishes`, shows its message in an inline `ErrorBanner` above the card it came from. Settings edits are kept.
- **`RunHistory({ runs })`:** a table of `listRuns(…, 20)` with:
  - started time, kind (`Run` or `Dry run`) and a status badge;
  - seen, changed, deleted and memories;
  - duration, which is `finishedAt − startedAt`;
  - an expandable row showing `errorClass: errorMessage`, and `stderrTail` in a `<pre>` as literal text.
  - Empty, it shows `No runs yet.`

- [ ] **Step 1: Write the failing tests in `SourceDetailPage.test.tsx`**

Mount with `renderAt('/p/:profile/sources/:sourceId', '/p/p/sources/acme', …)`, using `mockApi` with method-aware route functions.
- `editingSendsOnlyTheChanges`: change `jql`, then Save. The `PATCH` body is exactly `{config: {jql: 'project = B'}}`, with no `token` key anywhere.
- `replacingASecretSendsItOnceAndForgetsIt`:
  - Replace, type `n3w`, then Save.
  - The `PUT …/secrets` body is `{secrets: {token: 'n3w'}}`.
  - Afterwards `n3w` is in no input value and not in `document.body.textContent`.
- `runningDisablesRunAndRemove`: with `running: true`, the `Run now` and `Remove` buttons are disabled.
- `conflictKeepsTheEdit`: the `PATCH` answers 409 with `source acme is running; try again when it finishes`. The message is shown, and the edited `jql` input still holds the edit.
- `dryRunShowsTheEstimate`:
  - `POST …/run` gives `{taskId: 't1'}`.
  - `GET /v1/tasks/t1` answers `RUNNING`, then `SUCCEEDED` with `result: {itemsSeen: 12, itemsChanged: 3, chars: 4000, estimatedChunks: 2}`.
  - The text `12 items, 3 changed, 4,000 characters · about 2 chunks to extract` appears.
  - Use `integer()` for the numbers; use fake timers or `findByText` with a timeout of up to 3 s.
- `heldBackSweepCanBeAllowed`: `sweepBlockedCount: 40` shows the banner, and the button POSTs `…/accept-sweep`.
- `removeCanForgetMemories`: tick the checkbox, then confirm. This sends `DELETE …?forgetMemories=true`, and the location becomes `/p/p/sources`.
- `missingHarvesterShowsReadOnlySettings`.
- `needsAuthWithoutSignInPointsToCredentials`: a Jira source in `needs_auth` shows the credentials banner and no `Sign in` button.
- `runHistoryShowsStderrLiterally`: a failed run with `stderrTail` `<b>boom</b>` shows that exact text, and `container.querySelector('b')` is null.

- [ ] **Step 2: Run and see them fail**

Run: `cd console && npx vitest run src/features/sources/SourceDetailPage.test.tsx`
Expected: FAIL, because `./SourceDetailPage` cannot be resolved.

- [ ] **Step 3: Implement `SourceDetailPage` and `RunHistory`, and wire the route**

Until Task 6 lands, render `Sign in` as disabled, with the hint `Coming in the next step`. Task 6 replaces it. If you do Task 6 in the same sitting, wire it directly and record the ruling.

- [ ] **Step 4: Run the console suite and typecheck**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add console/src
git commit -m "Show a source's settings, actions and run history in the console"
```

---

### Task 6: Sign in from the console

**Files:**
- Create:
  - `console/src/features/sources/SignInDialog.tsx`
  - `console/src/features/sources/SignInDialog.test.tsx`
- Modify: `SourceDetailPage.tsx`, which opens it from `Sign in`, `?signin=1` and the `needs_auth` banner

**Interfaces:**
- Consumes: Task 1's `startAuthorization`, `authorizationStatus` and `qk.source`.
- **Produces `SignInDialog({ profile, sourceId, onClose })`:**
  - **On mount** it calls `startAuthorization` once. While it waits, it shows `Asking for a sign-in code…`.
  - **While pending,** it shows:
    - `message` as plain text, when non-blank;
    - the `userCode`, large and monospace, with a `Copy code` button using `navigator.clipboard.writeText`;
    - an `Open the sign-in page` link to `verificationUri`, only when that starts with `https://`;
    - ``The code expires ${relativeTime(expiresAt)}.``
  - **Polling:** `authorizationStatus` every `POLL_MS = 1000`, through `useQuery` `refetchInterval`, while pending. It stops when the dialog unmounts.
  - **`authorized`** shows `Signed in.` and a `Done` button, and invalidates `qk.source` and `qk.sources`.
  - **`failed`** shows ``Sign-in failed (${errorClass}): ${error}`` and a `Try again` button, which calls `startAuthorization` again.
  - **When `expiresAt` + 60 s passes while still pending,** it shows `The code expired.` and `Try again`.
  - **An `ApiError` from start,** such as 400 `has no sign-in`, shows its message.

- [ ] **Step 1: Write the failing tests in `SignInDialog.test.tsx`**

- `showsTheCodeThenSignsIn`:
  - `POST …/authorize` gives pending, with `userCode` `ABCD-EFGH`, `verificationUri` `https://microsoft.com/devicelogin`, a message, and `expiresAt` 15 minutes ahead. `GET …/authorize` then gives `authorized`.
  - `ABCD-EFGH` is shown, the link's `href` is the URI with `rel="noopener noreferrer"`, and `Signed in.` appears.
- `failureOffersRetry`: `GET` gives failed `auth`/`declined`. The dialog shows `Sign-in failed (auth): declined`, and `Try again` POSTs again.
- `nonHttpsLinkIsNotRendered`: with `verificationUri` `javascript:alert(1)`, there is no link, though the code is still shown.
- `closingStopsPolling`: unmount after the first `GET`, then advance 3 s with fake timers. No further `GET …/authorize` calls follow.
- `detailOpensTheDialogFromTheQuery`, in `SourceDetailPage.test.tsx`: `/p/p/sources/t?signin=1` with a `teams` harvester POSTs `…/t/authorize`.

- [ ] **Step 2: Run and see them fail**

Run: `cd console && npx vitest run src/features/sources/SignInDialog.test.tsx`
Expected: FAIL, because `./SignInDialog` cannot be resolved.

- [ ] **Step 3: Implement `SignInDialog` and wire it into the detail page**

- [ ] **Step 4: Run the console suite, typecheck and production build**

Run: `cd console && npm test && npm run typecheck && npm run build`
Expected: PASS. The build writes `console/dist`, which git ignores.

- [ ] **Step 5: Commit**

```bash
git add console/src
git commit -m "Sign harvest sources in from the console"
```

---

### Task 7: Docs

**Files:**
- Modify:
  - `docs/phases/phase-16-knowledge-harvesters.md`: the status line becomes "1–8 complete", plus an amendment bullet, "Console Sources page, settled in phase 8"
  - `README.md`, its "Bring in outside knowledge" section

- [ ] **Step 1: Write the phase amendment**

One bullet each for:
- the three routes;
- that harvesters are listed read-only, with install and remove in the CLI, and why;
- that secrets are write-only (password inputs, Replace, names only);
- sign-in from the console;
- dry runs reading the task result;
- that the console verifies with `npm test` and `npm run build`, outside `./gradlew test`.

- [ ] **Step 2: Add one README paragraph**

The Sources page at `/console/p/<profile>/sources` adds, runs and signs sources in. Installing harvesters stays `pieria harvester install`.

- [ ] **Step 3: Run the suites and commit**

Run: `cd console && npm test && npm run typecheck`, then `./gradlew test`
Expected: PASS.

```bash
git add docs README.md
git commit -m "Document the console Sources page"
```

---

## Manual verification (yours, after `./gradlew :daemon:deployLocal`)

1. **The page.** Open `http://127.0.0.1:8077/console/`, pick a profile, then Sources.
   - The installed harvesters are listed, with `teams` bundled.
   - No install button appears.
2. **An HTML source:**
   1. Add `html`, with id `docs` and seeds set to a small site. It lands on the detail page, and the check has passed.
   2. Use Dry run: the estimate appears.
   3. Use Run now: the history gains a run.
   4. Use Remove, ticking forget: the source is gone.
3. **A Teams source:**
   1. Add `teams` with your client id. It opens the sign-in dialog, with the code and the link.
   2. Sign in in the browser. The dialog says `Signed in.`, and the status is `Active`.
4. **Credentials.** For a Jira source with a wrong token:
   - The run fails, and the row shows `Credentials refused` with `Replace credentials`.
   - Replace the token, then Resume.
   - The value never shows on screen again.
