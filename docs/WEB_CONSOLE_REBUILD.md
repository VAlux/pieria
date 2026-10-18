# Web console rebuild plan

## Decision and release shape

Build the new console as a **React + TypeScript client-side app** in a top-level
`console/` project, with Vite for development and production builds. Keep the
daemon as the sole API and data owner. The console build produces static files;
there is no Node.js process on an end user's machine.

Treat "separate" as a source, build, and deployable artifact boundary. For the
default local install, place the built artifact under `<PIERIA_HOME>/console/`
in the **same release archive** as the daemon, gateway, and CLI. The daemon
serves those files at `/console/` on its existing loopback listener; browser
requests to `/v1/`, `/pieria-health`, and `/pieria-status` stay on the same
origin. Keep `/` (and existing `/index.html` links) working through a redirect
or compatibility page. Users still run one installer, one daemon, and open one
local URL. Do not add a second service, browser runtime, CDN, or CORS dependency.

The framework does not determine installation complexity: Angular can also
produce a static browser build. React is the better fit here because the
current console already uses small ES modules, so screens and graph code can
move incrementally while the API and current console keep working. Angular's
built-in routing, forms, and dependency injection would help a larger team
that wants stronger prescribed structure, but a clean rewrite into Angular
would increase the migration surface without simplifying distribution. Avoid
SSR and full-stack React features; Pieria already owns the backend.

## Current constraints to preserve

- The console lives in `modules/daemon/src/main/resources/static`; it currently
  uses vanilla ES modules and same-origin `/v1` requests. The daemon is bound
  to `127.0.0.1` by default. Its UI currently includes memories, add, recall,
  statistics, audit, graph, wiki, task tray, and global/profile configuration.
- The configuration forms derive editable fields from `GET /v1/config/schema`.
  Preserve the daemon as the authority for field scope, validation, effective
  values, and restart requirements.
- The graph requests bounded slices; keep that behavior. Recall results,
  memory prose, and wiki pages need safe Markdown rendering. Audit logs,
  errors, commands, and raw payloads must stay literal.
- The console adds `X-Pieria-Client: console` and
  `X-Pieria-Channel: console` on requests. Preserve those audit headers.
- Release archives currently contain only `bin/` even though `nativeDist`
  stages other material. The Unix installer strips `bin/` while extracting;
  the Windows installer expands the archive root. `pieria update` validates
  and swaps only the three native binaries. All of these paths must change
  together before the external console can be released.
- The old Java console asset tests assert source strings and classpath
  resources. Replace them with tests of the new built artifact and user flows
  when retiring the old UI.

## Architecture and compatibility contract

1. Create `console/` with a lockfile, TypeScript, React, Vite, an accessible
   component style layer, and a small typed HTTP client. The HTTP client owns
   `/v1` URL building, profile encoding, headers, errors, cancellation, and
   NDJSON downloads. It does not hold business rules already in the daemon.
2. Define routes for each current view, with the selected profile in the URL.
   Provide deep-link fallback **only under `/console/`** so missing API URLs
   still return API errors. Keep the existing query links working during the
   transition, including wiki page links.
3. Build with a `/console/` asset base. Fingerprint JS/CSS assets and cache
   them aggressively; serve `index.html` without a long cache lifetime. Ship
   every asset locally, including graph and Markdown dependencies.
4. Resolve the console directory from the installation root, distinct from
   Pieria's data directory. For development, use Vite's local proxy to the
   daemon. A source checkout can use the dev server; release startup must not
   require Node.js. If the console files are absent, show a clear console 404
   or installation message while keeping the API available.
5. Publish a small daemon/console compatibility value (API major or minimum
   console version) and show a clear mismatch message. A separately replaced
   console must not silently call incompatible API endpoints.

## Delivery sequence

1. **Contract and skeleton.** Inventory the current network calls and record
   response shapes and error cases. Add the typed HTTP client, router, shell,
   profile selection, status, and task tray. Prove the Vite dev proxy and a
   built app served at `/console/` against a running daemon.
2. **Read flows.** Move memories, filtering, history, statistics, audit, graph,
   and wiki. Preserve bounded graph fetching, wiki job polling, pagination,
   cancellation on profile/route changes, and deep links.
3. **Write flows.** Move add, recall, forget, profile deletion, export, and
   schema-driven configuration. Verify confirmations, dirty-form guards,
   restart banners, and accessible loading/error states.
4. **Packaging.** Stage `console/dist` as `console/` in `nativeDist` and in
   release archives. Update both installers to extract the full archive layout.
   Extend `pieria update` and `--from-build` to stage, validate, and replace
   the console directory with rollback alongside binaries. Keep one pinned
   release version for the daemon and console in the default install; a
   separate console artifact can be published from the same build output.
5. **Cutover.** Run both UIs during migration under distinct paths. Make
   `/` lead to the new console after parity is demonstrated; retain a tested
   rollback path to the old UI for one release. Then remove old static assets,
   old string-based asset tests, and the compatibility path when safe.

Status: every view has been rebuilt in `console/` to the redesign canvas — the
sidebar with the ⌘P profile switcher and ⌘K command palette, Overview, Memories
(server-paged list, detail with bi-temporal validity history, correct / end
validity / forget), Add memory, Recall, Activity, Background tasks, Profile and
Global config, the Entity graph and the Wiki. Routes are profile-scoped
(`/console/p/{profile}/{view}`); the daemon falls back to `index.html` for
extensionless `/console/**` paths, and legacy `?view=&profile=` links redirect to
the matching route. The root URL remains on the existing UI until cutover.

Endpoints added for the console (all under `/v1`):

- `profiles/{name}/memories/search` (search, type counts, cursor paging, grouped
  tool calls), `memories/{id}`, `sessions`, `topic-keys`
- `audit` and `audit/{id}` across profiles, `audit/facets`
- `tasks?profile=`, `tasks/{id}/retry`; task summaries carry finish time,
  duration, a result summary and retryability
- `config/provider/test`, `daemon/restart` (launchd/systemd only); secret config
  values are no longer returned, entries carry the shipped default
- `profiles/{name}/wiki/status`, `wiki/build` (a `wiki-build` task),
  `clusters/links`; entity responses carry relation totals; recall responses
  always carry timings and per-memory channels
- `/pieria-status` reports host, bound port, `apiVersion`, daemon version and
  whether a restart is possible

Console checks: `npm ci && npm run typecheck && npm test && npm run build` in
`console/`. `PIERIA_DAEMON_URL=http://127.0.0.1:8078 npm run dev` points the dev
server at a daemon other than the default.

## Release gates

- `npm ci`, typecheck, unit tests for URL/HTTP behavior, production build, and
  browser tests for the main read/write flows. Use fixture API responses for
  focused UI tests and a real local daemon for one integration smoke test.
- `./gradlew test` and `./gradlew :daemon:compileJava`; do not run native compilation or local
  deployment as part of routine development. The release pipeline should
  perform platform-specific archive/install/update smoke tests, including
  fresh install, upgrade from a bin-only release, interrupted update rollback,
  offline opening of `/console/`, and a deep-link refresh.
- Check release archives contain `bin/` and `console/index.html`; check that
  no page requires remote fonts, scripts, or APIs. Verify browser requests
  remain same-origin and reach only the daemon API.

## Sources for framework and build behavior

- [React: creating an app](https://react.dev/learn/creating-a-react-app) and
  [adding React to an existing project](https://react.dev/learn/add-react-to-an-existing-project)
- [Angular deployment](https://angular.dev/tools/cli/deployment)
- [Vite production build](https://vite.dev/guide/build.html)
