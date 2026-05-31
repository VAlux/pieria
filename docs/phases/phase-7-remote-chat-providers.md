# Phase 7 - Remote Chat Providers

## Objective

Lower the onboarding cost of Pieria by letting users point the chat tier at a hosted
provider (Anthropic or OpenAI) instead of requiring a local Ollama chat model, while keeping
embeddings local by default. A `pieria login <provider>` command captures and stores an API key so
the daemon can use a hosted chat model in the same way it currently uses Ollama.

## Design Decisions

These choices are intentional and constrain the scope below.

- **Hybrid provider split is a first-class mode.** Chat (extract / verify / classify /
  analyzeQuery / synthesize / judge) and embedding are configured independently. The default and
  recommended remote setup is **remote chat + local Ollama embeddings**, so the vector store and its
  fixed embedding dimension are unaffected by switching chat providers.
- **`pieria login` stores an API key only.** It does **not** authenticate against, impersonate, or
  reuse a consumer subscription (Claude Pro/Max, ChatGPT Plus). Subscription OAuth credentials are
  scoped to first-party clients and reusing them from a third-party app violates provider terms of
  service. Pieria uses metered API keys exclusively.
- **Anthropic covers chat only.** Anthropic exposes no embedding API, so an Anthropic login can
  never drive Pieria's vector path; embeddings must come from Ollama (or, optionally, OpenAI).
- **Ollama remains the zero-config default.** No remote provider is contacted unless explicitly
  configured. Local-only operation continues to work with no API key.

## Scope

- Provider-agnostic chat tier selectable between `ollama`, `anthropic`, and `openai`.
- Independent embedding provider selection, defaulting to Ollama.
- `pieria login <provider>` CLI command to capture, validate, and store an API key.
- Secure credential storage in the existing app-data config location, preferring the OS keychain.
- No change to the `MemoryStore`, ingestion, or retrieval contracts.
- Server mode (Phase 6) provider configuration is out of scope here; this phase targets local mode.

## Implementation Sequence

1. Generalize the model gateway seam for multiple chat providers.

- Keep `ModelGateway` as the single contract; ingestion and retrieval must not change.
- Make `ModelGatewayConfig` provider-conditional so the small/large `ChatClient` beans and the
  `EmbeddingModel` bean are built from the selected provider's Spring AI model.
- Confirm each provider implementation still wraps provider failures in
  `ModelUnavailableException` with a generic message so hosts/secrets never leak.
- Implement provider-appropriate `isModelProviderReachable()` and `availableModels()`; for hosted
  providers these may be a cheap auth/probe call or a static capability list, never a token-
  generating request.

2. Add provider configuration to `PieriaProperties`.

- Add `pieria.model.chat-provider` (`ollama` | `anthropic` | `openai`, default `ollama`).
- Add `pieria.model.embedding-provider` (`ollama` | `openai`, default `ollama`).
- Allow chat and embedding providers to differ (the hybrid case).
- Keep existing `pieria.model.chat-small`, `chat-large`, `embedding`, and `embedding-dimension`
  properties, interpreted per the selected provider.
- Validate at startup that the selected providers and model names are coherent (e.g. reject
  `embedding-provider=anthropic`).

3. Add Spring AI provider starters and wiring.

- Add the `spring-ai-anthropic` and `spring-ai-openai` starters to the daemon module.
- Wire base URLs, model names, and API-key sourcing from Pieria properties / stored credentials
  rather than relying solely on Spring AI's default environment-variable resolution.
- Ensure remote starters do not change behavior when the provider is `ollama` (no key required,
  no network calls at startup).

4. Implement credential storage.

- Define a credential store abstraction that reads/writes a per-provider API key.
- Prefer the OS keychain (macOS Keychain, libsecret, Windows Credential Manager); fall back to a
  restricted-permission file under the resolved app-data config dir.
- Never log keys; never echo them in health, status, or error output.
- The daemon resolves the active provider's key from this store at startup and on demand.

5. Implement the `pieria login <provider>` CLI command.

- Add the command alongside the existing daemon CLI commands.
- Capture the API key via a non-echoing prompt (or `--key`/stdin for scripted use).
- Validate the key with a single cheap authenticated call before storing.
- Store via the credential store; print masked confirmation and the resulting provider config
  hint, never the key itself.
- Add `pieria logout <provider>` to remove a stored key.
- Reflect provider/login state in first-run guidance and status output.

6. Update first-run and observability.

- First-run model checks branch on the configured chat/embedding providers: check Ollama
  reachability/models only for Ollama tiers; for hosted tiers, check that a key is present and
  valid without generating tokens.
- Status/health output reports the active chat provider, embedding provider, and model names
  (no secrets), consistent with Phase 5 observability rules.

7. Documentation.

- Document the three setups: local-only (default), remote chat + local embeddings (recommended
  remote), and full OpenAI (chat + embeddings).
- Document `pieria login` / `pieria logout`, where credentials are stored, and how to rotate a
  key.
- State explicitly that subscriptions are not supported and only metered API keys are used.
- Warn that changing the embedding provider/model changes the embedding dimension and requires a
  full re-embed of the store.

## Tests

- Provider-conditional configuration tests: each provider selection builds the expected chat and
  embedding beans; defaults resolve to Ollama with no key required.
- Hybrid configuration test: remote chat provider with Ollama embeddings.
- Startup validation tests for incoherent provider/model combinations (e.g. Anthropic embeddings).
- Credential store tests for write/read/rotate/remove, masking, and file permissions; keychain paths
  stubbed where the OS keychain is unavailable in CI.
- `pieria login`/`logout` command tests using a fake credential store and a stubbed validation call;
  no live network access.
- Gateway/daemon tests confirm hosted-provider failures surface as `ModelUnavailableException`
  without leaking hosts or keys.
- Run `./gradlew test`; live hosted-provider calls remain opt-in and excluded from CI.

## Acceptance Criteria

- Local-only Ollama mode remains the default and passes all existing tests with no API key.
- The chat tier can be switched to Anthropic or OpenAI via configuration.
- Embeddings can remain on local Ollama while chat runs remotely (hybrid mode works).
- `pieria login <provider>` stores a validated API key securely and `pieria logout` removes it.
- No subscription credentials are used; only metered API keys.
- First-run and status output reflect the active providers and login state without exposing secrets.
- Ingestion, retrieval, and `MemoryStore` contracts are unchanged.

## Risks And Follow-Ups

- Embedding dimension is pinned once (`FLOAT[n]`); allowing an OpenAI embedding provider introduces
  a dimension mismatch path that must be guarded at startup and clearly documented as a re-embed
  event. Keeping embeddings local sidesteps this for the recommended setup.
- OS keychain integration varies per platform; the file fallback must have strict permissions and be
  covered by tests where keychain access is unavailable.
- Hosted providers add cost and rate-limit failure modes; provider errors must degrade the same way
  Ollama unavailability does today (503 via `ModelUnavailableException`), without leaking provider
  details.
- Spring AI native-image (Phase 5) reachability metadata may need updates for the added provider
  starters.
