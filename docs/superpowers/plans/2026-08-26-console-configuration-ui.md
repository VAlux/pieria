# Console Configuration UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an operator read and edit both layers of Pieria's configuration — per-profile overrides and process-global daemon settings — from the web console.

**Architecture:** The daemon serves a *schema* (`GET /v1/config/schema`) describing every editable key: its scope, grouping, control kind, and cost-to-apply tier. The console renders both configuration pages generically from that schema, so adding a property never means touching JavaScript. Per-profile editing rides the existing `PUT /v1/profiles/{name}/config` whitelist; global editing is new, and writes line-preserving edits into `$configDir/pieria.properties`, reporting which keys the running daemon will not pick up until a restart.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Jackson 3 (`tools.jackson`), JUnit 5 + AssertJ, jsoup (asset contract tests), vanilla ES modules for the console (no build step, no JS test runner).

**Spec:** `docs/design/pieria/` — the clickable design canvas (`Main.dc.html` profile page, `Global.dc.html` global page, `States.dc.html` state sheet, `SidePanel.dc.html` information architecture). Published at https://claude.ai/code/artifact/f455c9b8-b643-49de-8e7a-e3f8a69b71e5. There is no prose spec; the artboards and their canvas annotations are the spec.

## Global Constraints

- All code lives under `dev.alvo.pieria`; module boundaries are enforced by Gradle. Daemon-only code goes in `modules/daemon`.
- Test classes use the `*Tests` suffix. JUnit 5 via `useJUnitPlatform()`. AssertJ for assertions.
- Controllers are tested by **direct instantiation with real collaborators** (see `modules/daemon/src/test/java/dev/alvo/pieria/api/ProfileConfigApiTests.java`). No Mockito, no MockMvc — those dependencies are not on the daemon's test classpath.
- Model gateway dependencies must use fakes/stubs in tests. CI has no Ollama and no network.
- Do not add test seams to production code. Test through the real public surface.
- `./gradlew test` must pass before every commit. Verify with `./gradlew test` and `./gradlew :daemon:compileJava`.
- **Never run `nativeCompile`, `nativeDist`, or `deployLocal`.** They are slow and `deployLocal` overwrites the user's installed binaries.
- The console is vanilla ES modules served from `modules/daemon/src/main/resources/static`. There is **no JS test runner in this repo**; console behaviour is pinned by `ConsoleAssetsTests`-style contract assertions (jsoup + string matching) run from Java. Follow that pattern — it is the established one.
- Config wire format is kebab-case throughout, via `ConfigCodec` (`PropertyNamingStrategies.KEBAB_CASE`).
- **Jackson 3 gotcha:** it serializes `isX()`-style record methods as properties. Any boolean-returning helper on a record that is not meant to be on the wire needs `@JsonIgnore` (this has bitten the codebase before — see `DaemonOverrides.isEmpty()`).
- The daemon binds to `127.0.0.1` and must never bind a public interface in local mode.
- `pieria.model.embedding-dimension` fixes the `FLOAT[n]` width of the `memories_vec` table. Changing it invalidates every stored vector. It is never per-profile, and on the global page it is behind a server-enforced acknowledgement — not a UI-only guard.

---

## File Structure

**Backend — new files**

| File | Responsibility |
|---|---|
| `modules/daemon/src/main/java/dev/alvo/pieria/config/schema/ConfigField.java` | One editable key's descriptor: key, scope, section, tier, kind, options, label, hint. |
| `modules/daemon/src/main/java/dev/alvo/pieria/config/schema/ConfigSchemaService.java` | Loads and caches `config-schema.json`; lookup by key and scope. |
| `modules/daemon/src/main/resources/config/config-schema.json` | The schema data. Copy lives here, keys are checked against code by a drift test. |
| `modules/daemon/src/main/java/dev/alvo/pieria/config/PropertiesFileEditor.java` | Line-preserving read/set/remove/write for a `.properties` file. |
| `modules/daemon/src/main/java/dev/alvo/pieria/config/GlobalConfigService.java` | Effective global values + provenance; validated writes to `pieria.properties`. |
| `modules/daemon/src/main/java/dev/alvo/pieria/api/controller/GlobalConfigController.java` | `GET /v1/config`, `PUT /v1/config`, `GET /v1/config/schema`. |

**Backend — modified**

| File | Change |
|---|---|
| `modules/daemon/src/main/java/dev/alvo/pieria/api/controller/ProfileConfigController.java` | Add `GET /v1/profiles/{name}/config/detail` returning the three layers. Existing GET/PUT/DELETE untouched. |
| `modules/daemon/src/main/java/dev/alvo/pieria/config/ProfileConfigService.java` | Add `detail(String profileName)`. |

**Console — new files**

| File | Responsibility |
|---|---|
| `static/css/config.css` | Config-page styling only. Reuses `base.css` tokens; adds no new tokens. |
| `static/js/console/config/schema.js` | Fetch + cache the schema; group fields by section. |
| `static/js/console/config/field.js` | Render one field row for any control kind. The whole visual vocabulary. |
| `static/js/console/config/form.js` | Dirty tracking, client validation, the save bar. |
| `static/js/console/config/channel-mix.js` | The stacked retrieval-channel bar. |
| `static/js/console/config/profile.js` | Per-profile view: load, render, save, reset. |
| `static/js/console/config/global.js` | Global view: tiers, restart banner, lock gate. |

**Console — modified**

| File | Change |
|---|---|
| `static/index.html` | Two new `.view` sections; side-panel Configuration entries. |
| `static/js/console/router.js` | Route the two config views. |
| `static/js/console/main.js` | Register views, wire the new side-panel entries. |
| `static/js/console/profiles.js` | Render the per-profile Configuration child entry. |
| `modules/daemon/src/test/java/dev/alvo/pieria/console/ConfigConsoleAssetsTests.java` | New contract test for the config assets. |

---

### Task 1: Config schema and its endpoint

**Files:**
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/config/schema/ConfigField.java`
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/config/schema/ConfigSchemaService.java`
- Create: `modules/daemon/src/main/resources/config/config-schema.json`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/config/schema/ConfigSchemaTests.java`

**Interfaces:**
- Consumes: `ConfigCodec` (kebab-case JSON), `DaemonOverrides.Ingestion`, `DaemonOverrides.Retrieval`.
- Produces: `ConfigField(String key, String scope, String section, String tier, String kind, List<String> options, String label, String hint)`; `ConfigSchemaService.all()`, `.forScope(String scope)`, `.find(String key)` returning `Optional<ConfigField>`. `scope` is `"profile"` or `"global"`; `tier` is `"live"`, `"restart"` or `"locked"`; `kind` is one of `"weight" "int" "double" "bool" "enum" "string" "secret"`.

- [ ] **Step 1: Write the failing test**

Create `modules/daemon/src/test/java/dev/alvo/pieria/config/schema/ConfigSchemaTests.java`:

```java
package dev.alvo.pieria.config.schema;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import dev.alvo.pieria.config.model.DaemonOverrides;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The schema carries human copy; the KEYS must stay in lockstep with DaemonOverrides, or the
 * console would offer a field the daemon rejects (or silently hide one it accepts).
 */
class ConfigSchemaTests {

  private final ConfigSchemaService schema = new ConfigSchemaService();

  @Test
  void profileScopedKeysMatchDaemonOverridesExactly() {
    Set<String> fromCode = new LinkedHashSet<>();
    kebabComponentNames(DaemonOverrides.Ingestion.class)
      .forEach(name -> fromCode.add("ingestion." + name));
    kebabComponentNames(DaemonOverrides.Retrieval.class)
      .forEach(name -> fromCode.add("retrieval." + name));

    Set<String> fromSchema = schema.forScope("profile").stream()
      .map(ConfigField::key)
      .collect(Collectors.toCollection(LinkedHashSet::new));

    assertThat(fromSchema).containsExactlyInAnyOrderElementsOf(fromCode);
  }

  @Test
  void everyFieldDeclaresAKnownScopeTierAndKind() {
    assertThat(schema.all()).isNotEmpty();
    assertThat(schema.all()).allSatisfy(field -> {
      assertThat(field.scope()).isIn("profile", "global");
      assertThat(field.tier()).isIn("live", "restart", "locked");
      assertThat(field.kind()).isIn("weight", "int", "double", "bool", "enum", "string", "secret");
      assertThat(field.label()).isNotBlank();
      if ("enum".equals(field.kind())) {
        assertThat(field.options()).isNotEmpty();
      }
    });
  }

  @Test
  void profileFieldsAreAlwaysLiveBecauseTheResolverInvalidatesOnWrite() {
    assertThat(schema.forScope("profile")).allSatisfy(
      field -> assertThat(field.tier()).isEqualTo("live"));
  }

  @Test
  void embeddingDimensionAndDatabasePathAreLocked() {
    assertThat(schema.find("pieria.model.embedding-dimension"))
      .get().extracting(ConfigField::tier).isEqualTo("locked");
    assertThat(schema.find("pieria.db.path"))
      .get().extracting(ConfigField::tier).isEqualTo("locked");
  }

  private static Set<String> kebabComponentNames(Class<? extends Record> type) {
    Set<String> names = new LinkedHashSet<>();
    for (RecordComponent component : type.getRecordComponents()) {
      names.add(toKebab(component.getName()));
    }
    return names;
  }

  private static String toKebab(String camel) {
    StringBuilder sb = new StringBuilder(camel.length() + 4);
    for (char character : camel.toCharArray()) {
      if (Character.isUpperCase(character)) {
        sb.append('-').append(Character.toLowerCase(character));
      } else {
        sb.append(character);
      }
    }
    return sb.toString().toLowerCase(Locale.ROOT);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.config.schema.ConfigSchemaTests'`
Expected: FAIL — compilation error, `ConfigSchemaService` and `ConfigField` do not exist.

- [ ] **Step 3: Create the descriptor record**

Create `modules/daemon/src/main/java/dev/alvo/pieria/config/schema/ConfigField.java`:

```java
package dev.alvo.pieria.config.schema;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One editable configuration key as the console needs to render it.
 *
 * <p>The daemon is the single source of truth for what is editable: the console builds both
 * configuration pages from this schema, so adding a property is a resource edit rather than a
 * JavaScript change. {@code key} is the wire key — a dotted path inside {@code DaemonOverrides}
 * for {@code profile} scope ({@code retrieval.weight-graph}), a full Spring property name for
 * {@code global} scope ({@code pieria.daemon.port}).
 *
 * @param scope   {@code profile} (per-profile override) or {@code global} (process-wide)
 * @param section UI grouping within a page
 * @param tier    what applying it costs: {@code live}, {@code restart}, or {@code locked}
 * @param kind    control kind: weight, int, double, bool, enum, string, secret
 * @param options permitted values, required when {@code kind} is {@code enum}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConfigField(
  String key,
  String scope,
  String section,
  String tier,
  String kind,
  List<String> options,
  String label,
  String hint) {

  public ConfigField {
    options = options == null ? List.of() : List.copyOf(options);
  }
}
```

- [ ] **Step 4: Create the schema resource**

Create `modules/daemon/src/main/resources/config/config-schema.json`. Keys are kebab-case to match `ConfigCodec`:

```json
[
  {"key":"retrieval.weight-exact-key","scope":"profile","section":"channels","tier":"live","kind":"weight","label":"Exact topic-key match"},
  {"key":"retrieval.weight-fts-memory","scope":"profile","section":"channels","tier":"live","kind":"weight","label":"Full-text over memories"},
  {"key":"retrieval.weight-hyde-vector","scope":"profile","section":"channels","tier":"live","kind":"weight","label":"HyDE vector"},
  {"key":"retrieval.weight-direct-vector","scope":"profile","section":"channels","tier":"live","kind":"weight","label":"Direct vector"},
  {"key":"retrieval.weight-fts-message","scope":"profile","section":"channels","tier":"live","kind":"weight","label":"Full-text over raw messages"},
  {"key":"retrieval.weight-graph","scope":"profile","section":"channels","tier":"live","kind":"weight","label":"Entity-relation graph","hint":"0 disables the channel entirely."},
  {"key":"retrieval.vector-enabled","scope":"profile","section":"channels","tier":"live","kind":"bool","label":"Vector search enabled"},

  {"key":"retrieval.graph-depth","scope":"profile","section":"graph","tier":"live","kind":"int","label":"Depth","hint":"Neighbourhood expansion in hops."},
  {"key":"retrieval.graph-fanout","scope":"profile","section":"graph","tier":"live","kind":"int","label":"Fanout"},
  {"key":"retrieval.graph-seed-limit","scope":"profile","section":"graph","tier":"live","kind":"int","label":"Seed limit"},

  {"key":"retrieval.weight-symbol-fts","scope":"profile","section":"code-graph","tier":"live","kind":"double","label":"Symbol full-text weight"},
  {"key":"retrieval.weight-code-graph","scope":"profile","section":"code-graph","tier":"live","kind":"double","label":"Code-graph weight"},
  {"key":"retrieval.code-graph-depth","scope":"profile","section":"code-graph","tier":"live","kind":"int","label":"Depth"},
  {"key":"retrieval.code-graph-fanout","scope":"profile","section":"code-graph","tier":"live","kind":"int","label":"Fanout"},
  {"key":"retrieval.code-graph-seed-limit","scope":"profile","section":"code-graph","tier":"live","kind":"int","label":"Seed limit"},
  {"key":"retrieval.code-graph-min-confidence","scope":"profile","section":"code-graph","tier":"live","kind":"enum","options":["resolved","heuristic"],"label":"Minimum edge confidence"},

  {"key":"retrieval.rrf-k","scope":"profile","section":"fusion","tier":"live","kind":"int","label":"RRF k"},
  {"key":"retrieval.channel-limit","scope":"profile","section":"fusion","tier":"live","kind":"int","label":"Results per channel"},
  {"key":"retrieval.channel-timeout-ms","scope":"profile","section":"fusion","tier":"live","kind":"int","label":"Channel timeout (ms)"},
  {"key":"retrieval.recall-mode","scope":"profile","section":"fusion","tier":"live","kind":"enum","options":["EVIDENCE","ANALYZED","SYNTHESIZED"],"label":"Default recall tier"},
  {"key":"retrieval.near-duplicate-threshold","scope":"profile","section":"fusion","tier":"live","kind":"double","label":"Lexical duplicate threshold","hint":"0 disables the collapse."},
  {"key":"retrieval.semantic-duplicate-threshold","scope":"profile","section":"fusion","tier":"live","kind":"double","label":"Semantic duplicate threshold","hint":"Cosine over stored embeddings. 0 disables."},

  {"key":"ingestion.chunk-size-chars","scope":"profile","section":"ingestion","tier":"live","kind":"int","label":"Chunk size (chars)"},
  {"key":"ingestion.chunk-overlap-messages","scope":"profile","section":"ingestion","tier":"live","kind":"int","label":"Chunk overlap (messages)"},
  {"key":"ingestion.max-extraction-concurrency","scope":"profile","section":"ingestion","tier":"live","kind":"int","label":"Extraction concurrency"},
  {"key":"ingestion.interrogative-queries-per-memory","scope":"profile","section":"ingestion","tier":"live","kind":"int","label":"Interrogative queries per memory"},
  {"key":"ingestion.max-extracted-candidates-per-chunk","scope":"profile","section":"ingestion","tier":"live","kind":"int","label":"Max candidates per chunk","hint":"0 means uncapped."},
  {"key":"ingestion.graph-from-extraction","scope":"profile","section":"ingestion","tier":"live","kind":"bool","label":"Extract graph during ingest"},

  {"key":"logging.level.dev.alvo.pieria.model","scope":"global","section":"observability","tier":"live","kind":"enum","options":["INFO","DEBUG","TRACE"],"label":"Model-call log level","hint":"DEBUG logs each model call with stage, model, prompt size and latency."},
  {"key":"pieria.model.max-concurrent-structured-calls","scope":"global","section":"throughput","tier":"live","kind":"int","label":"Max concurrent structured calls","hint":"Hosted providers take 16-32; a single-GPU local Ollama gains nothing past 4."},
  {"key":"pieria.reminiscence.parallelism","scope":"global","section":"throughput","tier":"live","kind":"int","label":"Graph enrichment parallelism"},
  {"key":"pieria.ingestion.vectorization-interval-ms","scope":"global","section":"throughput","tier":"live","kind":"int","label":"Vectorization interval (ms)"},
  {"key":"pieria.audit.max-body-bytes","scope":"global","section":"observability","tier":"live","kind":"int","label":"Audit body retention (bytes)"},

  {"key":"pieria.provider.type","scope":"global","section":"provider","tier":"restart","kind":"enum","options":["openai","azure"],"label":"Provider dialect","hint":"openai covers Ollama, LM Studio, llama.cpp, vLLM, OpenRouter and OpenAI."},
  {"key":"pieria.provider.base-url","scope":"global","section":"provider","tier":"restart","kind":"string","label":"Provider base URL","hint":"API root WITHOUT /v1 — the client appends it. In azure mode, the resource endpoint."},
  {"key":"pieria.provider.api-key","scope":"global","section":"provider","tier":"restart","kind":"secret","label":"API key","hint":"Forwarded as the bearer token. Local providers ignore it."},
  {"key":"pieria.provider.name","scope":"global","section":"provider","tier":"restart","kind":"string","label":"Provider label"},
  {"key":"pieria.provider.api-version","scope":"global","section":"provider","tier":"restart","kind":"string","label":"Azure API version","hint":"Used only when the dialect is azure."},
  {"key":"pieria.model.extraction-model","scope":"global","section":"models","tier":"restart","kind":"string","label":"Extraction model","hint":"In azure mode this is the deployment name."},
  {"key":"pieria.model.synthesis-model","scope":"global","section":"models","tier":"restart","kind":"string","label":"Synthesis model"},
  {"key":"pieria.model.embedding","scope":"global","section":"models","tier":"restart","kind":"string","label":"Embedding model"},
  {"key":"pieria.daemon.host","scope":"global","section":"daemon","tier":"restart","kind":"string","label":"Bind address","hint":"Local mode must stay on a loopback address."},
  {"key":"pieria.daemon.port","scope":"global","section":"daemon","tier":"restart","kind":"int","label":"Port"},

  {"key":"pieria.model.embedding-dimension","scope":"global","section":"storage","tier":"locked","kind":"int","label":"Embedding dimension","hint":"Fixes the FLOAT[n] width of memories_vec. Changing it invalidates every stored vector."},
  {"key":"pieria.db.path","scope":"global","section":"storage","tier":"locked","kind":"string","label":"Database path","hint":"Blank means the OS-appropriate app-data path."},
  {"key":"pieria.storage.backend","scope":"global","section":"storage","tier":"locked","kind":"enum","options":["sqlite","postgres"],"label":"Storage backend"}
]
```

- [ ] **Step 5: Create the schema service**

Create `modules/daemon/src/main/java/dev/alvo/pieria/config/schema/ConfigSchemaService.java`:

```java
package dev.alvo.pieria.config.schema;

import dev.alvo.pieria.config.toml.ConfigCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the editable-configuration schema once at construction. The schema carries the human copy
 * (labels, hints, grouping); the KEYS are checked against {@code DaemonOverrides} by
 * {@code ConfigSchemaTests}, so the console can never offer a field the daemon would reject.
 */
@Component
public class ConfigSchemaService {

  static final String SCHEMA_RESOURCE = "config/config-schema.json";

  private final List<ConfigField> fields;
  private final Map<String, ConfigField> byKey;

  public ConfigSchemaService() {
    this.fields = List.copyOf(load());
    Map<String, ConfigField> index = new LinkedHashMap<>();
    for (ConfigField field : fields) {
      index.put(field.key(), field);
    }
    this.byKey = Map.copyOf(index);
  }

  /** Every editable field, in declaration order. */
  public List<ConfigField> all() {
    return fields;
  }

  /** Fields for one scope: {@code profile} or {@code global}. */
  public List<ConfigField> forScope(String scope) {
    return fields.stream().filter(field -> field.scope().equals(scope)).toList();
  }

  public Optional<ConfigField> find(String key) {
    return Optional.ofNullable(byKey.get(key));
  }

  private static List<ConfigField> load() {
    try (InputStream in = new ClassPathResource(SCHEMA_RESOURCE).getInputStream()) {
      String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      JsonNode root = ConfigCodec.parseJson(json);
      List<ConfigField> parsed = new ArrayList<>();
      for (JsonNode node : root) {
        parsed.add(ConfigCodec.bind(node, ConfigField.class));
      }
      return parsed;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + SCHEMA_RESOURCE, e);
    }
  }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.config.schema.ConfigSchemaTests'`
Expected: PASS — all four tests green. If `profileScopedKeysMatchDaemonOverridesExactly` fails, the JSON and the records disagree; fix the JSON, never the test.

- [ ] **Step 7: Commit**

```bash
git add modules/daemon/src/main/java/dev/alvo/pieria/config/schema \
        modules/daemon/src/main/resources/config/config-schema.json \
        modules/daemon/src/test/java/dev/alvo/pieria/config/schema
git commit -m "feat(config): serve an editable-configuration schema with a drift guard"
```

---

### Task 2: Three-layer view for a profile's config

**Files:**
- Modify: `modules/daemon/src/main/java/dev/alvo/pieria/config/ProfileConfigService.java`
- Modify: `modules/daemon/src/main/java/dev/alvo/pieria/api/controller/ProfileConfigController.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/api/ProfileConfigDetailTests.java`

**Interfaces:**
- Consumes: `ProfileConfigService`, `EffectiveConfigResolver`, `MemoryStore`, `ConfigCodec`, `DaemonOverrides`.
- Produces: `ProfileConfigService.detail(String profileName)` returning `ProfileConfigDetail(DaemonOverrides global, DaemonOverrides overrides, DaemonOverrides effective)`; `GET /v1/profiles/{name}/config/detail`.

**Why this exists:** the current `GET /v1/profiles/{name}/config` returns only the *effective* config. The console cannot tell an overridden field from an inherited one that happens to hold the same value, so it cannot render the provenance state or reset a single field correctly. Deriving it by diffing against global is wrong when a profile deliberately overrides a key to the global value.

- [ ] **Step 1: Write the failing test**

Create `modules/daemon/src/test/java/dev/alvo/pieria/api/ProfileConfigDetailTests.java`:

```java
package dev.alvo.pieria.api;

import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.api.controller.ProfileConfigController;
import dev.alvo.pieria.api.request.RecallMode;
import dev.alvo.pieria.config.EffectiveConfigResolver;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.ProfileConfigService;
import dev.alvo.pieria.config.VerifyMode;
import dev.alvo.pieria.config.toml.ConfigCodec;
import dev.alvo.pieria.storage.SqliteMemoryStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The detail view is what lets the console distinguish "overridden to 1.0" from "inherits 1.0".
 * Diffing effective against global cannot: both look identical.
 */
class ProfileConfigDetailTests {

  private Path dbFile;
  private HikariDataSource dataSource;
  private ProfileConfigController controller;

  private static PieriaProperties globalProps() {
    return new PieriaProperties(null, null, null, null,
      new PieriaProperties.Ingestion(10000, 2, 4, VerifyMode.ALWAYS, 1, 0, 0, false, 3, 3, 32, 5, false, 5000, true, 0.70),
      new PieriaProperties.Retrieval(true, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2, 20, 8, 10, 3000,
        1.0, 1.0, 2, 20, 8, "heuristic", RecallMode.SYNTHESIZED, 0.60, 0.78),
      null);
  }

  @BeforeEach
  void setUp() throws Exception {
    dbFile = Files.createTempFile("pieria-detail", ".db");
    Files.deleteIfExists(dbFile);
    String url = "jdbc:sqlite:" + dbFile;
    dataSource = DataSourceBuilder.create().type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC").url(url).build();
    Flyway.configure().dataSource(dataSource).load().migrate();

    SqliteMemoryStore store = new SqliteMemoryStore(dataSource);
    PieriaProperties props = globalProps();
    EffectiveConfigResolver resolver = new EffectiveConfigResolver(props, store);
    controller = new ProfileConfigController(new ProfileConfigService(store, resolver));
  }

  @AfterEach
  void tearDown() throws Exception {
    if (dataSource != null) dataSource.close();
    Files.deleteIfExists(dbFile);
  }

  @Test
  void detailSeparatesOverriddenFromInheritedEvenWhenValuesAgree() {
    // weight-graph is overridden to exactly the global value; rrf-k is left alone.
    controller.put("alice", ConfigCodec.parseJson(
      "{\"retrieval\":{\"weight-graph\":1.0}}"));

    JsonNode detail = controller.detail("alice");

    assertThat(detail.get("overrides").get("retrieval").has("weight-graph")).isTrue();
    assertThat(detail.get("overrides").get("retrieval").has("rrf-k")).isFalse();
    assertThat(detail.get("effective").get("retrieval").get("weight-graph").asDouble()).isEqualTo(1.0);
    assertThat(detail.get("global").get("retrieval").get("weight-graph").asDouble()).isEqualTo(1.0);
    assertThat(detail.get("global").get("retrieval").get("rrf-k").asInt()).isEqualTo(60);
  }

  @Test
  void detailOfAnUnknownProfileIsAllGlobalAndCreatesNoProfileRow() {
    JsonNode detail = controller.detail("nobody");

    assertThat(detail.get("overrides").isEmpty()).isTrue();
    assertThat(detail.get("effective").get("retrieval").get("rrf-k").asInt()).isEqualTo(60);
    assertThat(detail.get("global").get("retrieval").get("rrf-k").asInt()).isEqualTo(60);
  }

  @Test
  void overriddenValuesShowThroughToEffectiveButNotToGlobal() {
    controller.put("alice", ConfigCodec.parseJson(
      "{\"retrieval\":{\"rrf-k\":12},\"ingestion\":{\"chunk-size-chars\":14000}}"));

    JsonNode detail = controller.detail("alice");

    assertThat(detail.get("effective").get("retrieval").get("rrf-k").asInt()).isEqualTo(12);
    assertThat(detail.get("global").get("retrieval").get("rrf-k").asInt()).isEqualTo(60);
    assertThat(detail.get("overrides").get("ingestion").get("chunk-size-chars").asInt()).isEqualTo(14000);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.api.ProfileConfigDetailTests'`
Expected: FAIL — compilation error, `controller.detail(...)` does not exist.

- [ ] **Step 3: Add the detail record and service method**

Create `modules/daemon/src/main/java/dev/alvo/pieria/config/ProfileConfigDetail.java`:

```java
package dev.alvo.pieria.config;

import dev.alvo.pieria.config.model.DaemonOverrides;

/**
 * The three configuration layers for one profile, as the console needs them in a single read.
 *
 * @param global    the global baseline, fully populated
 * @param overrides only what this profile actually sets — sparse, nulls omitted on the wire
 * @param effective global overlaid with overrides, fully populated
 */
public record ProfileConfigDetail(
  DaemonOverrides global,
  DaemonOverrides overrides,
  DaemonOverrides effective) {
}
```

In `modules/daemon/src/main/java/dev/alvo/pieria/config/ProfileConfigService.java`, add these imports and method. Note `toFullOverrides` and `effectiveFor` already exist as private static/instance methods:

```java
  /**
   * All three layers for one profile in a single read. The console needs the sparse override map
   * to tell "overridden to the global value" apart from "inherited" — diffing effective against
   * global cannot distinguish them.
   */
  public ProfileConfigDetail detail(String profileName) {
    DaemonOverrides global = toFullOverrides(configResolver.global());

    return store.findProfile(profileName)
      .map(profile -> new ProfileConfigDetail(
        global,
        storedOverrides(profile.id()),
        effectiveFor(profile.id())))
      .orElseGet(() -> new ProfileConfigDetail(
        global,
        new DaemonOverrides(null, null),
        global));
  }

  /**
   * The profile's raw stored overrides, or an empty set. Fail-open like the resolver: a corrupt
   * row must not break the config page.
   */
  private DaemonOverrides storedOverrides(String profileId) {
    try {
      return store.getProfileConfig(profileId)
        .map(json -> ConfigCodec.bind(ConfigCodec.parseJson(json), DaemonOverrides.class))
        .orElseGet(() -> new DaemonOverrides(null, null));
    } catch (RuntimeException e) {
      return new DaemonOverrides(null, null);
    }
  }
```

- [ ] **Step 4: Add the controller endpoint**

In `modules/daemon/src/main/java/dev/alvo/pieria/api/controller/ProfileConfigController.java`, add:

```java
  /**
   * The three configuration layers for this profile: the global baseline, the profile's own
   * (sparse) overrides, and the effective result. Additive — the plain GET keeps its shape, which
   * the CLI depends on.
   */
  @GetMapping("/detail")
  public JsonNode detail(@PathVariable String name) {
    return ConfigCodec.toNode(configService.detail(name));
  }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.api.ProfileConfigDetailTests'`
Expected: PASS — all three tests green.

- [ ] **Step 6: Run the existing config suite to prove nothing broke**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.api.ProfileConfigApiTests'`
Expected: PASS — the plain GET/PUT/DELETE contract the CLI uses is unchanged.

- [ ] **Step 7: Commit**

```bash
git add modules/daemon/src/main/java/dev/alvo/pieria/config/ProfileConfigDetail.java \
        modules/daemon/src/main/java/dev/alvo/pieria/config/ProfileConfigService.java \
        modules/daemon/src/main/java/dev/alvo/pieria/api/controller/ProfileConfigController.java \
        modules/daemon/src/test/java/dev/alvo/pieria/api/ProfileConfigDetailTests.java
git commit -m "feat(config): expose global/overrides/effective layers for a profile"
```

---

### Task 3: Line-preserving properties file editor

**Files:**
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/config/PropertiesFileEditor.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/config/PropertiesFileEditorTests.java`

**Interfaces:**
- Consumes: nothing but `java.nio`.
- Produces: `PropertiesFileEditor.read(Path)` → `PropertiesFileEditor`; instance methods `Optional<String> get(String key)`, `void set(String key, String value)`, `void remove(String key)`, `void write(Path)`.

**Why not `java.util.Properties`:** `pieria.properties` is materialized from a heavily commented template that users are expected to edit by hand. `Properties.store()` discards every comment and reorders the file. This editor rewrites only the lines it owns.

- [ ] **Step 1: Write the failing test**

Create `modules/daemon/src/test/java/dev/alvo/pieria/config/PropertiesFileEditorTests.java`:

```java
package dev.alvo.pieria.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PropertiesFileEditorTests {

  @TempDir
  Path dir;

  private Path write(String... lines) throws IOException {
    Path file = dir.resolve("pieria.properties");
    Files.write(file, List.of(lines));
    return file;
  }

  @Test
  void readsAnExistingValue() throws IOException {
    Path file = write("# a comment", "pieria.daemon.port=8077");

    assertThat(PropertiesFileEditor.read(file).get("pieria.daemon.port")).contains("8077");
  }

  @Test
  void missingFileReadsAsEmpty() {
    PropertiesFileEditor editor = PropertiesFileEditor.read(dir.resolve("absent.properties"));

    assertThat(editor.get("pieria.daemon.port")).isEmpty();
  }

  @Test
  void commentedOutKeysAreNotValues() throws IOException {
    Path file = write("# pieria.daemon.port=9999", "!pieria.db.path=/nope");
    PropertiesFileEditor editor = PropertiesFileEditor.read(file);

    assertThat(editor.get("pieria.daemon.port")).isEmpty();
    assertThat(editor.get("pieria.db.path")).isEmpty();
  }

  @Test
  void setReplacesInPlaceAndKeepsEveryOtherLine() throws IOException {
    Path file = write(
      "# Pieria configuration",
      "# Edit and restart.",
      "",
      "pieria.daemon.port=8077",
      "pieria.provider.name=ollama");

    PropertiesFileEditor editor = PropertiesFileEditor.read(file);
    editor.set("pieria.daemon.port", "9090");
    editor.write(file);

    assertThat(Files.readAllLines(file)).containsExactly(
      "# Pieria configuration",
      "# Edit and restart.",
      "",
      "pieria.daemon.port=9090",
      "pieria.provider.name=ollama");
  }

  @Test
  void setAppendsUnderAManagedHeaderWhenTheKeyIsAbsent() throws IOException {
    Path file = write("# Pieria configuration", "pieria.daemon.port=8077");

    PropertiesFileEditor editor = PropertiesFileEditor.read(file);
    editor.set("pieria.provider.name", "lmstudio");
    editor.set("pieria.reminiscence.parallelism", "16");
    editor.write(file);

    List<String> lines = Files.readAllLines(file);
    assertThat(lines).containsSubsequence(
      "# Pieria configuration",
      "pieria.daemon.port=8077",
      PropertiesFileEditor.MANAGED_HEADER,
      "pieria.provider.name=lmstudio",
      "pieria.reminiscence.parallelism=16");
    assertThat(lines.stream().filter(PropertiesFileEditor.MANAGED_HEADER::equals)).hasSize(1);
  }

  @Test
  void removeDropsTheLineSoTheShippedDefaultTakesOverAgain() throws IOException {
    Path file = write("pieria.daemon.port=9090", "pieria.provider.name=ollama");

    PropertiesFileEditor editor = PropertiesFileEditor.read(file);
    editor.remove("pieria.daemon.port");
    editor.write(file);

    assertThat(Files.readAllLines(file)).containsExactly("pieria.provider.name=ollama");
  }

  @Test
  void handlesColonSeparatorsAndSurroundingWhitespace() throws IOException {
    Path file = write("  pieria.daemon.port : 8077  ");

    PropertiesFileEditor editor = PropertiesFileEditor.read(file);
    assertThat(editor.get("pieria.daemon.port")).contains("8077");

    editor.set("pieria.daemon.port", "9090");
    editor.write(file);
    assertThat(Files.readAllLines(file)).containsExactly("pieria.daemon.port=9090");
  }

  @Test
  void writeCreatesTheFileAndItsParentWhenAbsent() throws IOException {
    Path file = dir.resolve("nested").resolve("pieria.properties");

    PropertiesFileEditor editor = PropertiesFileEditor.read(file);
    editor.set("pieria.daemon.port", "8077");
    editor.write(file);

    assertThat(Files.readAllLines(file)).contains("pieria.daemon.port=8077");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.config.PropertiesFileEditorTests'`
Expected: FAIL — compilation error, `PropertiesFileEditor` does not exist.

- [ ] **Step 3: Write the implementation**

Create `modules/daemon/src/main/java/dev/alvo/pieria/config/PropertiesFileEditor.java`:

```java
package dev.alvo.pieria.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads, edits and rewrites a {@code .properties} file one line at a time.
 *
 * <p>{@code pieria.properties} is materialized from a heavily commented template that users are
 * expected to edit by hand, so {@link java.util.Properties#store} is not usable here: it discards
 * every comment and reorders the file. This editor rewrites only the lines it owns — an existing
 * key is replaced where it sits, a new one is appended under {@link #MANAGED_HEADER}, and
 * everything else survives byte for byte.
 *
 * <p>Not thread-safe. The daemon is a single writer; callers read, edit and write in one go.
 */
public final class PropertiesFileEditor {

  /** Section the editor appends newly-introduced keys under, so hand edits stay separable. */
  public static final String MANAGED_HEADER = "# --- Written by the Pieria console ---";

  private final List<String> lines;

  private PropertiesFileEditor(List<String> lines) {
    this.lines = lines;
  }

  /** Read a properties file. A missing file reads as empty rather than failing. */
  public static PropertiesFileEditor read(Path file) {
    if (file == null || !Files.isRegularFile(file)) {
      return new PropertiesFileEditor(new ArrayList<>());
    }
    try {
      return new PropertiesFileEditor(new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + file, e);
    }
  }

  /** The value assigned to {@code key}, ignoring commented-out lines. */
  public Optional<String> get(String key) {
    int at = indexOf(key);
    if (at < 0) {
      return Optional.empty();
    }
    String line = lines.get(at);
    int separator = separatorIndex(line, key);
    return Optional.of(line.substring(separator + 1).trim());
  }

  /** Assign {@code key}, replacing an existing assignment in place or appending a new one. */
  public void set(String key, String value) {
    int at = indexOf(key);
    String assignment = key + "=" + (value == null ? "" : value);
    if (at >= 0) {
      lines.set(at, assignment);
      return;
    }
    if (!lines.contains(MANAGED_HEADER)) {
      if (!lines.isEmpty()) {
        lines.add("");
      }
      lines.add(MANAGED_HEADER);
    }
    lines.add(assignment);
  }

  /** Drop {@code key} entirely, so the daemon's shipped default applies again. Idempotent. */
  public void remove(String key) {
    int at = indexOf(key);
    if (at >= 0) {
      lines.remove(at);
    }
  }

  /**
   * Write the file atomically: a config the daemon imports at startup must never be observed
   * half-written, so the content lands in a sibling temp file and is moved into place.
   */
  public void write(Path file) {
    try {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Path temp = Files.createTempFile(parent, "pieria-properties", ".tmp");
      Files.write(temp, lines, StandardCharsets.UTF_8);
      try {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException atomicUnsupported) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write " + file, e);
    }
  }

  private int indexOf(String key) {
    Pattern pattern = Pattern.compile("^\\s*" + Pattern.quote(key) + "\\s*[=:]");
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      String trimmed = line.stripLeading();
      if (trimmed.startsWith("#") || trimmed.startsWith("!")) {
        continue;
      }
      Matcher matcher = pattern.matcher(line);
      if (matcher.find()) {
        return i;
      }
    }
    return -1;
  }

  private static int separatorIndex(String line, String key) {
    int from = line.indexOf(key) + key.length();
    for (int i = from; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '=' || c == ':') {
        return i;
      }
    }
    return line.length() - 1;
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.config.PropertiesFileEditorTests'`
Expected: PASS — all eight tests green.

- [ ] **Step 5: Commit**

```bash
git add modules/daemon/src/main/java/dev/alvo/pieria/config/PropertiesFileEditor.java \
        modules/daemon/src/test/java/dev/alvo/pieria/config/PropertiesFileEditorTests.java
git commit -m "feat(config): add a comment-preserving properties file editor"
```

---

### Task 4: Global configuration service

**Files:**
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/config/GlobalConfigService.java`
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/config/GlobalConfigEntry.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/config/GlobalConfigServiceTests.java`

**Interfaces:**
- Consumes: `ConfigSchemaService` (Task 1), `PropertiesFileEditor` (Task 3), `AppDataPathResolver`, Spring `Environment`.
- Produces:
  - `GlobalConfigEntry(String key, String section, String tier, String kind, List<String> options, String label, String hint, String value, String fileValue, String provenance, boolean restartPending)` — `provenance` is `"set"` or `"default"`.
  - `GlobalConfigService.effective()` → `List<GlobalConfigEntry>`
  - `GlobalConfigService.apply(Map<String, String> updates, boolean acknowledgeDestructive)` → `ApplyResult(List<String> written, List<String> cleared, List<String> restartRequired)`
  - A `null` value in `updates` clears the key back to the shipped default.

**Two behaviours that matter:**
1. `value` is what the *running* daemon is using (from `Environment`); `fileValue` is what is on disk. After a restart-tier write they differ, and `restartPending` says so — the console's banner stays truthful across a page reload, not just in the session that made the edit.
2. `locked`-tier keys are refused unless the caller passes `acknowledgeDestructive`. This is a **server-side** guard. The UI's unlock gate is a second layer, not the only one.

- [ ] **Step 1: Write the failing test**

Create `modules/daemon/src/test/java/dev/alvo/pieria/config/GlobalConfigServiceTests.java`:

```java
package dev.alvo.pieria.config;

import dev.alvo.pieria.config.schema.ConfigSchemaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlobalConfigServiceTests {

  @TempDir
  Path configDir;

  private MockEnvironment environment;
  private GlobalConfigService service;
  private Path propertiesFile;

  @BeforeEach
  void setUp() {
    propertiesFile = configDir.resolve("pieria.properties");
    environment = new MockEnvironment();
    environment.setProperty("pieria.daemon.port", "8077");
    environment.setProperty("pieria.provider.name", "ollama");
    environment.setProperty("pieria.model.embedding-dimension", "1024");
    environment.setProperty("pieria.reminiscence.parallelism", "8");
    service = new GlobalConfigService(new ConfigSchemaService(), environment, pathResolver(configDir));
  }

  /**
   * A real resolver pointed at the temp directory — the same pattern BootstrapServiceTests uses.
   * AppDataPathResolver null-guards pieria.db(), so an all-null PieriaProperties is safe here.
   */
  static AppDataPathResolver pathResolver(Path dir) {
    return new AppDataPathResolver(
      new AppDataProperties(dir.toString(), dir.toString(), dir.toString(),
        dir.toString(), dir.toString()),
      new PieriaProperties(null, null, null, null, null, null, null));
  }

  @Test
  void reportsTheRunningValueAndMarksUntouchedKeysAsDefault() {
    GlobalConfigEntry port = entry("pieria.daemon.port");

    assertThat(port.value()).isEqualTo("8077");
    assertThat(port.fileValue()).isNull();
    assertThat(port.provenance()).isEqualTo("default");
    assertThat(port.restartPending()).isFalse();
    assertThat(port.tier()).isEqualTo("restart");
  }

  @Test
  void writingALiveKeyMarksItSetAndNeedsNoRestart() {
    GlobalConfigService.ApplyResult result =
      service.apply(Map.of("pieria.reminiscence.parallelism", "16"), false);

    assertThat(result.written()).containsExactly("pieria.reminiscence.parallelism");
    assertThat(result.restartRequired()).isEmpty();
    assertThat(entry("pieria.reminiscence.parallelism").provenance()).isEqualTo("set");
    assertThat(entry("pieria.reminiscence.parallelism").fileValue()).isEqualTo("16");
  }

  @Test
  void writingARestartKeyReportsItAndStaysPendingUntilTheDaemonRestarts() {
    service.apply(Map.of("pieria.daemon.port", "9090"), false);

    GlobalConfigEntry port = entry("pieria.daemon.port");
    assertThat(port.value()).isEqualTo("8077");        // the running daemon, unchanged
    assertThat(port.fileValue()).isEqualTo("9090");    // what a restart would pick up
    assertThat(port.restartPending()).isTrue();
    assertThat(port.provenance()).isEqualTo("set");
  }

  @Test
  void restartRequiredListsOnlyTheKeysThatNeedOne() {
    Map<String, String> updates = new HashMap<>();
    updates.put("pieria.reminiscence.parallelism", "16");
    updates.put("pieria.daemon.port", "9090");

    GlobalConfigService.ApplyResult result = service.apply(updates, false);

    assertThat(result.restartRequired()).containsExactly("pieria.daemon.port");
  }

  @Test
  void lockedKeysAreRefusedWithoutAnExplicitAcknowledgement() {
    assertThatThrownBy(() -> service.apply(Map.of("pieria.model.embedding-dimension", "768"), false))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("pieria.model.embedding-dimension")
      .hasMessageContaining("acknowledge");

    assertThat(Files.exists(propertiesFile)).isFalse();
  }

  @Test
  void lockedKeysAreWrittenWhenAcknowledged() {
    GlobalConfigService.ApplyResult result =
      service.apply(Map.of("pieria.model.embedding-dimension", "768"), true);

    assertThat(result.written()).containsExactly("pieria.model.embedding-dimension");
    assertThat(result.restartRequired()).containsExactly("pieria.model.embedding-dimension");
  }

  @Test
  void unknownKeysAreRejectedSoAStrayPayloadCannotReachProcessState() {
    assertThatThrownBy(() -> service.apply(Map.of("pieria.secret.backdoor", "1"), true))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("pieria.secret.backdoor");
  }

  @Test
  void profileScopedKeysAreRejectedOnTheGlobalSurface() {
    assertThatThrownBy(() -> service.apply(Map.of("retrieval.rrf-k", "12"), true))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("retrieval.rrf-k");
  }

  @Test
  void valuesAreValidatedAgainstTheDeclaredKind() {
    assertThatThrownBy(() -> service.apply(Map.of("pieria.daemon.port", "eight-thousand"), false))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("pieria.daemon.port");

    assertThatThrownBy(() -> service.apply(Map.of("pieria.provider.type", "bedrock"), false))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("bedrock");
  }

  @Test
  void aNullValueClearsTheKeyBackToTheShippedDefault() throws IOException {
    service.apply(Map.of("pieria.reminiscence.parallelism", "16"), false);
    assertThat(entry("pieria.reminiscence.parallelism").provenance()).isEqualTo("set");

    Map<String, String> clearing = new HashMap<>();
    clearing.put("pieria.reminiscence.parallelism", null);
    GlobalConfigService.ApplyResult result = service.apply(clearing, false);

    assertThat(result.cleared()).containsExactly("pieria.reminiscence.parallelism");
    assertThat(entry("pieria.reminiscence.parallelism").provenance()).isEqualTo("default");
    assertThat(Files.readAllLines(propertiesFile))
      .noneMatch(line -> line.startsWith("pieria.reminiscence.parallelism="));
  }

  @Test
  void nothingIsWrittenWhenAnyKeyInTheBatchIsInvalid() {
    Map<String, String> updates = new HashMap<>();
    updates.put("pieria.reminiscence.parallelism", "16");
    updates.put("pieria.daemon.port", "not-a-port");

    assertThatThrownBy(() -> service.apply(updates, false))
      .isInstanceOf(IllegalArgumentException.class);

    assertThat(Files.exists(propertiesFile)).isFalse();
  }

  @Test
  void everyGlobalSchemaFieldIsReported() {
    List<GlobalConfigEntry> entries = service.effective();

    assertThat(entries).hasSize(new ConfigSchemaService().forScope("global").size());
    assertThat(entries).extracting(GlobalConfigEntry::label).doesNotContainNull();
  }

  private GlobalConfigEntry entry(String key) {
    return service.effective().stream()
      .filter(candidate -> candidate.key().equals(key))
      .findFirst()
      .orElseThrow(() -> new AssertionError("no entry for " + key));
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.config.GlobalConfigServiceTests'`
Expected: FAIL — compilation error, `GlobalConfigService` and `GlobalConfigEntry` do not exist.

If `org.springframework.mock.env.MockEnvironment` cannot be resolved, add `testImplementation("org.springframework:spring-test")` to `modules/daemon/build.gradle.kts` and re-run. Do not substitute a hand-rolled fake `Environment` — that would be a test seam in production shape.

- [ ] **Step 3: Create the entry record**

Create `modules/daemon/src/main/java/dev/alvo/pieria/config/GlobalConfigEntry.java`:

```java
package dev.alvo.pieria.config;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One process-global configuration key as the console renders it.
 *
 * <p>{@code value} is what the RUNNING daemon is using; {@code fileValue} is what
 * {@code pieria.properties} holds. For a restart-tier key these differ between a save and the next
 * restart, and {@code restartPending} carries that fact — so the console's banner survives a page
 * reload instead of living only in the session that made the edit.
 *
 * @param provenance     {@code set} when the key is assigned in the config-dir properties file,
 *                       {@code default} when the shipped value applies
 * @param restartPending the file and the running process disagree
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GlobalConfigEntry(
  String key,
  String section,
  String tier,
  String kind,
  List<String> options,
  String label,
  String hint,
  String value,
  String fileValue,
  String provenance,
  boolean restartPending) {

  public GlobalConfigEntry {
    options = options == null ? List.of() : List.copyOf(options);
  }
}
```

- [ ] **Step 4: Write the service**

Create `modules/daemon/src/main/java/dev/alvo/pieria/config/GlobalConfigService.java`:

```java
package dev.alvo.pieria.config;

import dev.alvo.pieria.config.schema.ConfigField;
import dev.alvo.pieria.config.schema.ConfigSchemaService;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Reads and writes the process-global configuration: the effective values the daemon is running
 * with, and validated edits to {@code pieria.properties} in the config directory.
 *
 * <p>Writes are all-or-nothing. Every key in a batch is validated against the schema before
 * anything reaches disk, so a single bad value cannot leave the file half-updated — the daemon
 * imports this file at startup and a partial write is a broken boot.
 *
 * <p>Locked-tier keys ({@code embedding-dimension}, {@code db.path}, {@code storage.backend})
 * require an explicit acknowledgement. That check lives here, not only in the console: changing
 * the embedding dimension invalidates every vector in the fixed-width {@code memories_vec} table,
 * and nothing else in the daemon would stop it.
 */
@Component
public class GlobalConfigService {

  private static final String PROPERTIES_FILE = "pieria.properties";

  private final ConfigSchemaService schema;
  private final Environment environment;
  private final AppDataPathResolver pathResolver;

  public GlobalConfigService(ConfigSchemaService schema,
                             Environment environment,
                             AppDataPathResolver pathResolver) {
    this.schema = schema;
    this.environment = environment;
    this.pathResolver = pathResolver;
  }

  /** What was written, what was cleared, and which of those the running daemon will not pick up. */
  public record ApplyResult(List<String> written, List<String> cleared, List<String> restartRequired) {
  }

  /** Every global-scoped key with its running value, file value and provenance. */
  public List<GlobalConfigEntry> effective() {
    PropertiesFileEditor file = PropertiesFileEditor.read(propertiesPath());
    List<GlobalConfigEntry> entries = new ArrayList<>();

    for (ConfigField field : schema.forScope("global")) {
      String running = environment.getProperty(field.key());
      String onDisk = file.get(field.key()).orElse(null);
      entries.add(new GlobalConfigEntry(
        field.key(),
        field.section(),
        field.tier(),
        field.kind(),
        field.options(),
        field.label(),
        field.hint(),
        running,
        onDisk,
        onDisk == null ? "default" : "set",
        onDisk != null && !Objects.equals(onDisk, running)));
    }
    return List.copyOf(entries);
  }

  /**
   * Apply a batch of updates. A {@code null} value clears the key so the shipped default applies
   * again. Throws {@link IllegalArgumentException} — mapped to 400 by the global handler — before
   * touching disk if any key is unknown, out of scope, locked without acknowledgement, or fails to
   * parse for its declared kind.
   */
  public ApplyResult apply(Map<String, String> updates, boolean acknowledgeDestructive) {
    if (updates == null || updates.isEmpty()) {
      return new ApplyResult(List.of(), List.of(), List.of());
    }

    for (Map.Entry<String, String> update : updates.entrySet()) {
      ConfigField field = requireGlobalField(update.getKey());
      if ("locked".equals(field.tier()) && !acknowledgeDestructive) {
        throw new IllegalArgumentException("'" + field.key()
          + "' cannot be changed in place; the request must acknowledge the consequences");
      }
      if (update.getValue() != null) {
        validate(field, update.getValue());
      }
    }

    Path path = propertiesPath();
    PropertiesFileEditor file = PropertiesFileEditor.read(path);
    List<String> written = new ArrayList<>();
    List<String> cleared = new ArrayList<>();
    List<String> restart = new ArrayList<>();

    for (Map.Entry<String, String> update : updates.entrySet()) {
      ConfigField field = requireGlobalField(update.getKey());
      if (update.getValue() == null) {
        file.remove(field.key());
        cleared.add(field.key());
      } else {
        file.set(field.key(), update.getValue());
        written.add(field.key());
      }
      if (!"live".equals(field.tier())) {
        restart.add(field.key());
      }
    }

    file.write(path);
    return new ApplyResult(List.copyOf(written), List.copyOf(cleared), List.copyOf(restart));
  }

  private ConfigField requireGlobalField(String key) {
    Optional<ConfigField> found = schema.find(key);
    if (found.isEmpty() || !"global".equals(found.get().scope())) {
      throw new IllegalArgumentException("unknown or non-overridable global config key: '" + key + "'");
    }
    return found.get();
  }

  private static void validate(ConfigField field, String value) {
    switch (field.kind()) {
      case "int" -> parseOrThrow(field, value, () -> Long.parseLong(value.trim()));
      case "double", "weight" -> parseOrThrow(field, value, () -> Double.parseDouble(value.trim()));
      case "bool" -> {
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("true") && !normalized.equals("false")) {
          throw new IllegalArgumentException("'" + field.key() + "' must be true or false, got '" + value + "'");
        }
      }
      case "enum" -> {
        if (!field.options().contains(value.trim())) {
          throw new IllegalArgumentException("'" + field.key() + "' must be one of "
            + field.options() + ", got '" + value + "'");
        }
      }
      default -> {
        // string and secret accept any value, including the empty string.
      }
    }
  }

  private static void parseOrThrow(ConfigField field, String value, Runnable parse) {
    try {
      parse.run();
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("'" + field.key() + "' must be a number, got '" + value + "'");
    }
  }

  private Path propertiesPath() {
    return pathResolver.resolve().configDir().resolve(PROPERTIES_FILE);
  }
}
```

Imports: `dev.alvo.pieria.config.schema.ConfigField`, `dev.alvo.pieria.config.schema.ConfigSchemaService`, `org.springframework.core.env.Environment`, `org.springframework.stereotype.Component`, `java.nio.file.Path`, `java.util.*`. There is no `java.util.function.Supplier` — the seam is gone.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.config.GlobalConfigServiceTests'`
Expected: PASS — all twelve tests green.

- [ ] **Step 6: Commit**

```bash
git add modules/daemon/src/main/java/dev/alvo/pieria/config/GlobalConfigService.java \
        modules/daemon/src/main/java/dev/alvo/pieria/config/GlobalConfigEntry.java \
        modules/daemon/src/test/java/dev/alvo/pieria/config/GlobalConfigServiceTests.java \
        modules/daemon/build.gradle.kts
git commit -m "feat(config): read and write process-global configuration with a locked tier"
```

---

### Task 5: Global configuration endpoints

**Files:**
- Create: `modules/daemon/src/main/java/dev/alvo/pieria/api/controller/GlobalConfigController.java`
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/api/GlobalConfigApiTests.java`

**Interfaces:**
- Consumes: `GlobalConfigService` (Task 4), `ConfigSchemaService` (Task 1).
- Produces: `GET /v1/config` → `{entries: [GlobalConfigEntry], configFile: "<absolute path>", restartCommand: "pieria daemon restart"}`; `PUT /v1/config` with body `{values: {key: value|null}, acknowledgeDestructive: bool}` → `ApplyResult`; `GET /v1/config/schema` → `List<ConfigField>`.

`restartCommand` is served rather than hardcoded in the console: the browser cannot restart the daemon, so the page hands the operator the exact command instead of a button that would lie about what it does.

- [ ] **Step 1: Write the failing test**

Create `modules/daemon/src/test/java/dev/alvo/pieria/api/GlobalConfigApiTests.java`:

```java
package dev.alvo.pieria.api;

import dev.alvo.pieria.api.controller.GlobalConfigController;
import dev.alvo.pieria.config.AppDataPathResolver;
import dev.alvo.pieria.config.AppDataProperties;
import dev.alvo.pieria.config.GlobalConfigService;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.schema.ConfigSchemaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlobalConfigApiTests {

  @TempDir
  Path configDir;

  private GlobalConfigController controller;

  @BeforeEach
  void setUp() {
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("pieria.daemon.port", "8077");
    environment.setProperty("pieria.reminiscence.parallelism", "8");
    environment.setProperty("pieria.model.embedding-dimension", "1024");

    ConfigSchemaService schema = new ConfigSchemaService();
    AppDataPathResolver paths = new AppDataPathResolver(
      new AppDataProperties(configDir.toString(), configDir.toString(), configDir.toString(),
        configDir.toString(), configDir.toString()),
      new PieriaProperties(null, null, null, null, null, null, null));
    GlobalConfigService service = new GlobalConfigService(schema, environment, paths);
    controller = new GlobalConfigController(service, schema, paths);
  }

  @Test
  void schemaCoversBothScopesSoOneFetchDrivesBothPages() {
    JsonNode schema = controller.schema();

    assertThat(schema.isArray()).isTrue();
    boolean hasProfile = false;
    boolean hasGlobal = false;
    for (JsonNode field : schema) {
      if ("profile".equals(field.get("scope").asString())) hasProfile = true;
      if ("global".equals(field.get("scope").asString())) hasGlobal = true;
    }
    assertThat(hasProfile).isTrue();
    assertThat(hasGlobal).isTrue();
  }

  @Test
  void getReportsEntriesTheConfigFileAndTheRestartCommand() {
    JsonNode body = controller.get();

    assertThat(body.get("entries").isArray()).isTrue();
    assertThat(body.get("entries").size()).isGreaterThan(0);
    assertThat(body.get("configFile").asString()).endsWith("pieria.properties");
    assertThat(body.get("restartCommand").asString()).isEqualTo("pieria daemon restart");
  }

  @Test
  void putWritesAndReportsWhatNeedsARestart() {
    Map<String, String> values = new HashMap<>();
    values.put("pieria.reminiscence.parallelism", "16");
    values.put("pieria.daemon.port", "9090");

    JsonNode result = controller.put(new GlobalConfigController.GlobalConfigUpdate(values, false));

    assertThat(result.get("written").size()).isEqualTo(2);
    assertThat(result.get("restart-required").size()).isEqualTo(1);
    assertThat(result.get("restart-required").get(0).asString()).isEqualTo("pieria.daemon.port");
  }

  @Test
  void putRefusesALockedKeyUntilItIsAcknowledged() {
    Map<String, String> values = new HashMap<>();
    values.put("pieria.model.embedding-dimension", "768");

    assertThatThrownBy(() ->
      controller.put(new GlobalConfigController.GlobalConfigUpdate(values, false)))
      .isInstanceOf(IllegalArgumentException.class);

    JsonNode ok = controller.put(new GlobalConfigController.GlobalConfigUpdate(values, true));
    assertThat(ok.get("written").get(0).asString()).isEqualTo("pieria.model.embedding-dimension");
  }

  @Test
  void putRefusesAProfileScopedKey() {
    Map<String, String> values = new HashMap<>();
    values.put("retrieval.rrf-k", "12");

    assertThatThrownBy(() ->
      controller.put(new GlobalConfigController.GlobalConfigUpdate(values, true)))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("retrieval.rrf-k");
  }

  @Test
  void aRestartTierWriteShowsAsPendingOnTheNextRead() {
    Map<String, String> values = new HashMap<>();
    values.put("pieria.daemon.port", "9090");
    controller.put(new GlobalConfigController.GlobalConfigUpdate(values, false));

    JsonNode body = controller.get();
    JsonNode port = null;
    for (JsonNode entry : body.get("entries")) {
      if ("pieria.daemon.port".equals(entry.get("key").asString())) port = entry;
    }

    assertThat(port).isNotNull();
    assertThat(port.get("value").asString()).isEqualTo("8077");
    assertThat(port.get("file-value").asString()).isEqualTo("9090");
    assertThat(port.get("restart-pending").asBoolean()).isTrue();
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.api.GlobalConfigApiTests'`
Expected: FAIL — compilation error, `GlobalConfigController` does not exist.

- [ ] **Step 3: Write the controller**

Create `modules/daemon/src/main/java/dev/alvo/pieria/api/controller/GlobalConfigController.java`:

```java
package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.config.AppDataPathResolver;
import dev.alvo.pieria.config.GlobalConfigService;
import dev.alvo.pieria.config.schema.ConfigSchemaService;
import dev.alvo.pieria.config.toml.ConfigCodec;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Process-global configuration: the settings every profile inherits and no profile can override.
 *
 * <p>Unlike {@code ProfileConfigController} these do not live in the store — they are Spring
 * properties, bound once at startup and persisted in {@code pieria.properties} in the config
 * directory. A write therefore does not change the running daemon for anything outside the
 * {@code live} tier, and the response says exactly which keys that applies to.
 *
 * <p>{@code restartCommand} is served rather than hardcoded in the console: the browser cannot
 * restart the daemon, so the page hands the operator the command instead of offering a button
 * that would not do what it says.
 */
@RestController
@RequestMapping("/v1/config")
public class GlobalConfigController {

  private static final String RESTART_COMMAND = "pieria daemon restart";
  private static final String PROPERTIES_FILE = "pieria.properties";

  private final GlobalConfigService configService;
  private final ConfigSchemaService schemaService;
  private final AppDataPathResolver pathResolver;

  public GlobalConfigController(GlobalConfigService configService,
                                ConfigSchemaService schemaService,
                                AppDataPathResolver pathResolver) {
    this.configService = configService;
    this.schemaService = schemaService;
    this.pathResolver = pathResolver;
  }

  /**
   * Request body for a global write. A {@code null} value clears the key back to the shipped
   * default. {@code acknowledgeDestructive} is required for locked-tier keys.
   */
  public record GlobalConfigUpdate(Map<String, String> values, boolean acknowledgeDestructive) {
  }

  /** Every editable key across both scopes, so one fetch drives both console pages. */
  @GetMapping("/schema")
  public JsonNode schema() {
    return ConfigCodec.toNode(schemaService.all());
  }

  /** The effective global configuration, with provenance and pending-restart state per key. */
  @GetMapping
  public JsonNode get() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("entries", configService.effective());
    body.put("configFile", pathResolver.resolve().configDir().resolve(PROPERTIES_FILE).toString());
    body.put("restartCommand", RESTART_COMMAND);
    return ConfigCodec.toNode(body);
  }

  /**
   * Apply a batch of global updates. All-or-nothing: an unknown key, a value that does not parse
   * for its kind, or an unacknowledged locked key rejects the whole request with 400 and leaves
   * the file untouched.
   */
  @PutMapping
  public JsonNode put(@RequestBody GlobalConfigUpdate body) {
    GlobalConfigService.ApplyResult result = configService.apply(
      body == null ? Map.of() : body.values(),
      body != null && body.acknowledgeDestructive());
    return ConfigCodec.toNode(result);
  }
}
```

Note: `ConfigCodec` is kebab-case, so `restartRequired` serializes as `restart-required`, `fileValue` as `file-value`, and `restartPending` as `restart-pending`. The tests above already assert those names; the console in Tasks 7–8 must read the same.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.api.GlobalConfigApiTests'`
Expected: PASS — all six tests green.

- [ ] **Step 5: Run the whole daemon suite**

Run: `./gradlew :daemon:test`
Expected: PASS — no regressions. The new `/v1/config` mapping does not collide with `/v1/profiles/{name}/config`.

- [ ] **Step 6: Commit**

```bash
git add modules/daemon/src/main/java/dev/alvo/pieria/api/controller/GlobalConfigController.java \
        modules/daemon/src/test/java/dev/alvo/pieria/api/GlobalConfigApiTests.java
git commit -m "feat(api): add global configuration read/write endpoints"
```

---

### Task 6: Console foundation — schema fetch, field renderer, stylesheet

**Files:**
- Create: `modules/daemon/src/main/resources/static/js/console/config/schema.js`
- Create: `modules/daemon/src/main/resources/static/js/console/config/field.js`
- Create: `modules/daemon/src/main/resources/static/css/config.css`
- Modify: `modules/daemon/src/main/resources/static/index.html` (add the stylesheet link)
- Test: `modules/daemon/src/test/java/dev/alvo/pieria/console/ConfigConsoleAssetsTests.java`

**Interfaces:**
- Consumes: `apiFetch`, `el` from `../../util/dom.js`.
- Produces:
  - `schema.js`: `loadSchema()` → `Promise<Array<field>>` (cached); `bySection(fields, scope)` → `Array<{section, fields}>` in declaration order.
  - `field.js`: `renderFieldRow(field, view)` → `HTMLElement`, where `view` is
    `{value, source, sourceLabel, error, disabled, onChange(nextValue), onReset()}`.
    `source` is `"set"` or `"inherited"`. `onReset` absent means no reset button.

The row is the entire visual vocabulary of both pages: a provenance dot, label over the literal key, one control, a provenance chip, a reset button. Only the exception is marked — an inherited row carries no dot and a muted label, so 28 fields read as "these six are mine" at a glance.

- [ ] **Step 1: Write the failing test**

Create `modules/daemon/src/test/java/dev/alvo/pieria/console/ConfigConsoleAssetsTests.java`:

```java
package dev.alvo.pieria.console;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the configuration console assets. There is no JS runner in this repo, so the
 * console's behavioural contracts are pinned here the same way the rest of the console is.
 */
class ConfigConsoleAssetsTests {

  @Test
  void configStylesheetIsLinkedAndReusesTheExistingTokens() throws IOException {
    Document html = Jsoup.parse(resource("static/index.html"));
    String css = resource("static/css/config.css");

    assertThat(html.select("link[href=css/config.css]")).hasSize(1);
    // No new colour tokens: the config pages ride base.css.
    assertThat(css).doesNotContain(":root {");
    assertThat(css).contains("var(--accent)", "var(--panel)", "var(--border)", "var(--dim)");
    assertThat(css).contains(".cfg-row", ".cfg-chip", ".cfg-savebar");
  }

  @Test
  void schemaModuleCachesOneFetchAndGroupsBySection() throws IOException {
    String schema = resource("static/js/console/config/schema.js");

    assertThat(schema)
      .contains("/v1/config/schema", "export function loadSchema", "export function bySection")
      .contains("cached");
    // One schema fetch serves both pages; re-fetching per view would be a needless round trip.
    assertThat(schema).doesNotContain("localStorage", "sessionStorage");
  }

  @Test
  void fieldRendererCoversEveryControlKindTheSchemaCanDeclare() throws IOException {
    String field = resource("static/js/console/config/field.js");

    assertThat(field).contains("export function renderFieldRow");
    // Every kind in config-schema.json must have a branch, or a field renders as nothing.
    assertThat(field).contains("\"weight\"", "\"int\"", "\"double\"", "\"bool\"", "\"enum\"",
      "\"string\"", "\"secret\"");
    assertThat(field).contains("cfg-row", "cfg-dot", "cfg-key", "cfg-chip", "cfg-reset");
  }

  @Test
  void everySchemaKindHasARendererBranch() throws IOException {
    String schemaJson = resource("config/config-schema.json");
    String field = resource("static/js/console/config/field.js");

    for (String kind : new String[] {"weight", "int", "double", "bool", "enum", "string", "secret"}) {
      if (schemaJson.contains("\"kind\":\"" + kind + "\"")) {
        assertThat(field)
          .as("field.js must handle kind '%s' declared in config-schema.json", kind)
          .contains("\"" + kind + "\"");
      }
    }
  }

  static String resource(String path) throws IOException {
    try (InputStream in = ConfigConsoleAssetsTests.class.getClassLoader().getResourceAsStream(path)) {
      assertThat(in).as("resource %s", path).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.console.ConfigConsoleAssetsTests'`
Expected: FAIL — `resource static/css/config.css` is null.

- [ ] **Step 3: Write the stylesheet**

Create `modules/daemon/src/main/resources/static/css/config.css`:

```css
/* Configuration pages. Rides the tokens in base.css — this file introduces no new colours. */

.cfg-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 5px; }
.cfg-head h2 { margin: 0; font-size: 19px; font-weight: 600; letter-spacing: -.2px; }
.cfg-scope { font-family: var(--mono); font-size: 12.5px; color: var(--accent); }
.cfg-scope.global { color: var(--muted); }
.cfg-lede { margin: 0 0 16px; color: var(--dim); font-size: 12.5px; max-width: 78ch; }

.cfg-summary {
  display: flex; align-items: center; gap: 14px; margin-bottom: 14px; padding: 13px 16px;
  background: var(--panel); border: 1px solid var(--border); border-radius: 10px;
}

.cfg-section {
  background: var(--panel); border: 1px solid var(--border); border-radius: 10px;
  margin-bottom: 12px; overflow: hidden;
}
.cfg-section.restart { border-color: rgba(210, 153, 34, .32); }
.cfg-section.locked { border-color: rgba(248, 81, 73, .3); }
.cfg-section-head {
  width: 100%; display: flex; align-items: center; gap: 10px; padding: 13px 16px;
  background: transparent; border: 0; border-radius: 0; text-align: left;
}
.cfg-section-head:hover { background: var(--panel-2); }
.cfg-section-title { font-size: 13px; font-weight: 600; }
.cfg-section-note { font-size: 12px; color: var(--dim); }
.cfg-section-head .ico { color: var(--dim); transition: transform .15s ease; }
.cfg-section-head[aria-expanded="true"] .ico { transform: rotate(90deg); }
.cfg-section-body { padding: 0 16px 6px; }
.cfg-section-body[hidden] { display: none; }

/* ---- one field row: the whole vocabulary of both pages ---- */
.cfg-row { padding: 11px 0; border-bottom: 1px solid var(--panel-2); }
.cfg-row:last-child { border-bottom: 0; }
.cfg-row.inactive { opacity: .45; }
.cfg-row-main { display: flex; align-items: center; gap: 12px; }
/* Only the exception is marked. An inherited row is quiet, so a page of 28 fields reads as
   "these six are mine" without counting. */
.cfg-dot { flex: none; width: 6px; height: 6px; border-radius: 999px; background: transparent; }
.cfg-row.is-set .cfg-dot { background: var(--accent); }
.cfg-label { flex: 1 1 auto; min-width: 0; }
.cfg-label-text { font-size: 13px; color: var(--muted); }
.cfg-row.is-set .cfg-label-text { color: var(--text); font-weight: 600; }
.cfg-key { font-family: var(--mono); font-size: 11px; color: var(--dim); margin-top: 1px; }
.cfg-control { flex: none; width: 286px; display: flex; align-items: center; justify-content: flex-end; gap: 10px; }
.cfg-control input[type="text"] { width: 96px; text-align: right; font-size: 12.5px; padding: 6px 9px; }
.cfg-control input.wide { width: 236px; text-align: left; }
.cfg-control input[type="range"] { flex: 1 1 auto; max-width: 154px; padding: 0; border: 0; background: transparent; accent-color: var(--accent); }
.cfg-control select { width: 168px; font-size: 12.5px; padding: 6px 28px 6px 9px; }
.cfg-control input.err { border-color: var(--danger); }
.cfg-provenance { flex: none; width: 130px; text-align: right; }
.cfg-chip {
  font-size: 11px; font-weight: 600; letter-spacing: .3px; padding: 2px 8px; border-radius: 999px;
  color: var(--accent); background: rgba(88, 166, 255, .13);
}
.cfg-inherited { font-family: var(--mono); font-size: 11px; color: var(--dim); }
.cfg-reset {
  flex: none; width: 28px; height: 28px; padding: 0;
  display: inline-flex; align-items: center; justify-content: center;
  background: transparent; border: 1px solid transparent; border-radius: 8px; color: var(--dim);
}
.cfg-reset:hover { background: var(--panel-2); color: var(--text); }
.cfg-row-error {
  margin: 6px 0 2px 18px; padding: 7px 10px; font-size: 11.5px; color: var(--danger);
  background: rgba(248, 81, 73, .08); border: 1px solid rgba(248, 81, 73, .3); border-radius: 7px;
}
.cfg-hint { margin: 5px 0 2px 18px; font-size: 11.5px; color: var(--dim); }

/* ---- channel mix ---- */
.cfg-mix { padding: 2px 0 14px; border-bottom: 1px solid var(--panel-2); }
.cfg-mix-bar { display: flex; height: 10px; border-radius: 999px; overflow: hidden; background: var(--bg); }
.cfg-mix-bar > span { transition: width .18s ease; }
.cfg-mix-legend { display: flex; flex-wrap: wrap; gap: 6px 16px; margin-top: 11px; }
.cfg-mix-legend button {
  display: inline-flex; align-items: center; gap: 7px; padding: 3px 8px;
  background: transparent; border: 1px solid transparent; border-radius: 7px; font-size: 11.5px;
}
.cfg-mix-legend button.active { border-color: var(--border); background: var(--panel-2); }
.cfg-mix-legend .off { opacity: .5; }
.cfg-mix-swatch { width: 8px; height: 8px; border-radius: 2px; flex: none; }

/* ---- banners ---- */
.cfg-banner {
  margin: 0 16px 12px; padding: 12px 14px; border-radius: 8px; font-size: 12.5px;
}
.cfg-banner.warn { color: var(--warn, #d29922); background: rgba(210, 153, 34, .08); border: 1px solid rgba(210, 153, 34, .32); }
.cfg-banner.danger { background: rgba(248, 81, 73, .07); border: 1px solid rgba(248, 81, 73, .3); }
.cfg-banner code {
  display: block; margin-top: 9px; padding: 8px 11px; font: 12px/1.5 var(--mono);
  background: var(--bg); border: 1px solid var(--border); border-radius: 7px; color: var(--text);
}

/* ---- save bar ---- */
.cfg-savebar {
  position: sticky; bottom: 0; z-index: 20;
  display: flex; align-items: center; gap: 14px; margin: 16px -20px -80px; padding: 13px 24px;
  background: var(--panel-2); border-top: 1px solid var(--border);
  box-shadow: 0 -10px 30px rgba(0, 0, 0, .45);
}
.cfg-savebar[hidden] { display: none; }
.cfg-savebar .endpoint { font-family: var(--mono); font-size: 11.5px; color: var(--dim); }
.cfg-savebar .blocked { font-size: 12px; color: var(--danger); }
```

- [ ] **Step 4: Link the stylesheet**

In `modules/daemon/src/main/resources/static/index.html`, add after the `graph.css` link:

```html
  <link rel="stylesheet" href="css/config.css">
```

- [ ] **Step 5: Write the schema module**

Create `modules/daemon/src/main/resources/static/js/console/config/schema.js`:

```js
// The editable-configuration schema, fetched once and shared by both config pages.
//
// The daemon owns what is editable: keys, control kinds and tiers all come from
// /v1/config/schema, so adding a property is a resource edit on the daemon rather than a change
// here. Cached in memory for the page's lifetime — the schema only changes when the daemon does.
import { apiFetch } from "../../util/dom.js";

let cached = null;

export function loadSchema() {
  if (cached) return Promise.resolve(cached);
  return apiFetch("/v1/config/schema", { headers: { Accept: "application/json" } })
    .then(function (r) {
      if (!r.ok) throw new Error("Could not load the configuration schema (" + r.status + ").");
      return r.json();
    })
    .then(function (fields) {
      cached = fields;
      return cached;
    });
}

// Group one scope's fields into sections, preserving the schema's declaration order.
export function bySection(fields, scope) {
  const order = [];
  const groups = {};
  fields.forEach(function (field) {
    if (field.scope !== scope) return;
    if (!groups[field.section]) {
      groups[field.section] = [];
      order.push(field.section);
    }
    groups[field.section].push(field);
  });
  return order.map(function (section) {
    return { section: section, fields: groups[section] };
  });
}
```

- [ ] **Step 6: Write the field renderer**

Create `modules/daemon/src/main/resources/static/js/console/config/field.js`:

```js
// One configuration field, rendered as a row. This is the entire visual vocabulary of both
// config pages: provenance dot, label over the literal key, one control, a provenance chip and a
// reset. Only a value set at THIS layer is marked — inherited rows stay quiet, so a page of 28
// fields reads as "these six are mine" without counting.
import { el, icon } from "../../util/dom.js";

const WIDE_KINDS = { string: true, secret: true };

function control(field, view) {
  const wrap = el("div", "cfg-control");

  if (field.kind === "bool") {
    const box = el("input");
    box.type = "checkbox";
    box.checked = view.value === true || view.value === "true";
    box.disabled = !!view.disabled;
    box.addEventListener("change", function () { view.onChange(box.checked); });
    wrap.appendChild(box);
    return wrap;
  }

  if (field.kind === "enum") {
    const select = el("select", "mono");
    (field.options || []).forEach(function (option) {
      const item = el("option", null, option);
      item.value = option;
      if (String(view.value) === option) item.selected = true;
      select.appendChild(item);
    });
    select.disabled = !!view.disabled;
    select.addEventListener("change", function () { view.onChange(select.value); });
    wrap.appendChild(select);
    return wrap;
  }

  // A weight is a number you compare to its siblings, so it gets a slider as well as the number.
  if (field.kind === "weight") {
    const slider = el("input");
    slider.type = "range";
    slider.min = "0";
    slider.max = "5";
    slider.step = "0.1";
    slider.value = String(view.value);
    slider.disabled = !!view.disabled;
    slider.addEventListener("input", function () {
      view.onChange(parseFloat(slider.value).toFixed(1));
    });
    wrap.appendChild(slider);
  }

  const input = el("input", "mono num" + (WIDE_KINDS[field.kind] ? " wide" : "")
    + (view.error ? " err" : ""));
  input.type = "text";
  input.value = view.value == null ? "" : String(view.value);
  input.disabled = !!view.disabled;
  if (field.kind === "secret" && view.source !== "set") input.placeholder = "••••••••";
  input.addEventListener("change", function () { view.onChange(input.value); });
  wrap.appendChild(input);
  return wrap;
}

export function renderFieldRow(field, view) {
  const row = el("div", "cfg-row" + (view.source === "set" ? " is-set" : "")
    + (view.disabled ? " inactive" : ""));
  const main = el("div", "cfg-row-main");

  main.appendChild(el("span", "cfg-dot"));

  const label = el("div", "cfg-label");
  label.appendChild(el("div", "cfg-label-text", field.label));
  label.appendChild(el("div", "cfg-key", field.key));
  main.appendChild(label);

  main.appendChild(control(field, view));

  const provenance = el("div", "cfg-provenance");
  if (view.source === "set") {
    provenance.appendChild(el("span", "cfg-chip", view.sourceLabel || "overridden"));
  } else if (view.sourceLabel) {
    provenance.appendChild(el("span", "cfg-inherited", view.sourceLabel));
  }
  main.appendChild(provenance);

  const resetCell = el("div");
  resetCell.style.flex = "none";
  resetCell.style.width = "28px";
  if (view.onReset && view.source === "set" && !view.disabled) {
    const reset = el("button", "cfg-reset");
    reset.type = "button";
    reset.title = "Reset to the inherited value";
    reset.appendChild(icon("chevronRight", 15));
    reset.querySelector("svg").innerHTML = '<path d="M4 12a8 8 0 1 0 2.6-5.9M4 4v4h4"/>';
    reset.addEventListener("click", view.onReset);
    resetCell.appendChild(reset);
  }
  main.appendChild(resetCell);

  row.appendChild(main);

  if (view.error) row.appendChild(el("div", "cfg-row-error", view.error));
  else if (field.hint) row.appendChild(el("div", "cfg-hint", field.hint));

  return row;
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.console.ConfigConsoleAssetsTests'`
Expected: PASS — all four tests green.

- [ ] **Step 8: Commit**

```bash
git add modules/daemon/src/main/resources/static/css/config.css \
        modules/daemon/src/main/resources/static/js/console/config \
        modules/daemon/src/main/resources/static/index.html \
        modules/daemon/src/test/java/dev/alvo/pieria/console/ConfigConsoleAssetsTests.java
git commit -m "feat(console): add the configuration field renderer and stylesheet"
```

---

### Task 7: Profile configuration view

**Files:**
- Create: `modules/daemon/src/main/resources/static/js/console/config/form.js`
- Create: `modules/daemon/src/main/resources/static/js/console/config/channel-mix.js`
- Create: `modules/daemon/src/main/resources/static/js/console/config/profile.js`
- Modify: `modules/daemon/src/main/resources/static/index.html` (add `#view-profile-config`)
- Modify: `modules/daemon/src/test/java/dev/alvo/pieria/console/ConfigConsoleAssetsTests.java`

**Interfaces:**
- Consumes: `renderFieldRow` (Task 6), `loadSchema`/`bySection` (Task 6), `GET /v1/profiles/{name}/config/detail` (Task 2), `PUT`/`DELETE /v1/profiles/{name}/config`.
- Produces:
  - `form.js`: `createForm({endpoint})` → `{values, baseline, isDirty(), changedKeys(), set(key, value), clear(key), reset(), commit(), renderSaveBar(container, opts)}`.
  - `channel-mix.js`: `renderChannelMix(container, weights, opts)` where `weights` is `[{key, label, value, color}]`.
  - `profile.js`: `loadProfileConfig(profile)`, `unloadProfileConfig()`.

**Nested keys:** the detail endpoint returns `{global, overrides, effective}` each shaped like `DaemonOverrides` — two objects, `ingestion` and `retrieval`. Schema keys are dotted (`retrieval.rrf-k`), so the view reads and writes through a small path helper rather than flattening.

- [ ] **Step 1: Write the failing test**

Append these tests to `modules/daemon/src/test/java/dev/alvo/pieria/console/ConfigConsoleAssetsTests.java`:

```java
  @Test
  void profileViewReadsAllThreeLayersAndWritesTheWhitelistedPayload() throws IOException {
    String profile = resource("static/js/console/config/profile.js");

    assertThat(profile)
      .contains("/config/detail", "\"PUT\"", "\"DELETE\"")
      .contains("export function loadProfileConfig", "export function unloadProfileConfig");
    // Provenance comes from the stored override map, never from diffing effective against global:
    // a profile may deliberately override a key to the global value.
    assertThat(profile).contains("overrides").doesNotContain("=== globalValue");
  }

  @Test
  void channelMixTreatsZeroAsADisableNotASmallNumber() throws IOException {
    String mix = resource("static/js/console/config/channel-mix.js");

    assertThat(mix).contains("export function renderChannelMix", "cfg-mix-bar", "cfg-mix-legend");
    assertThat(mix).contains("disabled");
  }

  @Test
  void saveBarBlocksWhenAFieldFailsClientValidation() throws IOException {
    String form = resource("static/js/console/config/form.js");

    assertThat(form)
      .contains("export function createForm", "changedKeys", "renderSaveBar")
      .contains("cfg-savebar", "Discard");
    // The daemon rejects the whole payload if one value fails to bind, so the client must not send
    // a batch it already knows is bad.
    assertThat(form).contains("blocked");
  }

  @Test
  void profileConfigViewSectionExistsInTheShell() throws IOException {
    Document html = Jsoup.parse(resource("static/index.html"));

    assertThat(html.select("main > section.view#view-profile-config")).hasSize(1);
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.console.ConfigConsoleAssetsTests'`
Expected: FAIL — `resource static/js/console/config/form.js` is null.

- [ ] **Step 3: Write the form controller**

Create `modules/daemon/src/main/resources/static/js/console/config/form.js`:

```js
// Dirty tracking, client-side validation and the save bar, shared by both config pages.
//
// The daemon rejects a config payload wholesale if any single value fails to bind, so the client
// refuses to send a batch it already knows is bad rather than letting the operator discover it as
// a 400 with the other edits lost.
import { el } from "../../util/dom.js";

function sameValue(a, b) {
  if (a === undefined && b === undefined) return true;
  if (a === undefined || b === undefined) return false;
  return String(a) === String(b);
}

export function validateValue(field, value) {
  if (value === undefined || value === null) return null;
  if (field.kind === "bool" || field.kind === "string" || field.kind === "secret") return null;
  if (field.kind === "enum") {
    return (field.options || []).indexOf(String(value)) >= 0
      ? null : "Must be one of " + (field.options || []).join(", ") + ".";
  }
  const text = String(value).trim();
  if (text === "") return "Must be a number.";
  const parsed = Number(text);
  if (!isFinite(parsed)) {
    return "Must be a number. The daemon rejects the whole payload if any value fails to bind.";
  }
  if (parsed < 0) return "Must be zero or greater.";
  if (field.kind === "int" && text.indexOf(".") >= 0) return "Must be a whole number.";
  return null;
}

export function createForm(options) {
  const state = { baseline: {}, values: {}, fields: options.fields || {} };

  return {
    get values() { return state.values; },

    load: function (setValues, fields) {
      state.baseline = Object.assign({}, setValues);
      state.values = Object.assign({}, setValues);
      if (fields) state.fields = fields;
    },

    set: function (key, value) { state.values[key] = value; },

    clear: function (key) { delete state.values[key]; },

    clearAll: function () { state.values = {}; },

    discard: function () { state.values = Object.assign({}, state.baseline); },

    commit: function () { state.baseline = Object.assign({}, state.values); },

    isSet: function (key) {
      return Object.prototype.hasOwnProperty.call(state.values, key);
    },

    changedKeys: function () {
      const seen = {};
      Object.keys(state.values).forEach(function (k) { seen[k] = 1; });
      Object.keys(state.baseline).forEach(function (k) { seen[k] = 1; });
      return Object.keys(seen).filter(function (k) {
        const inNew = Object.prototype.hasOwnProperty.call(state.values, k);
        const inOld = Object.prototype.hasOwnProperty.call(state.baseline, k);
        if (inNew !== inOld) return true;
        return !sameValue(state.values[k], state.baseline[k]);
      });
    },

    errors: function () {
      const found = {};
      const self = this;
      Object.keys(state.values).forEach(function (key) {
        const field = state.fields[key];
        if (!field) return;
        const message = validateValue(field, state.values[key]);
        if (message) found[key] = message;
      });
      return found;
    },

    renderSaveBar: function (container, opts) {
      const changed = this.changedKeys();
      const errors = this.errors();
      const blocked = Object.keys(errors).length > 0;
      container.innerHTML = "";
      container.hidden = changed.length === 0;
      if (!changed.length) return;

      container.appendChild(el("span", "cfg-dot", ""));
      container.appendChild(el("span", null,
        changed.length === 1 ? "1 change" : changed.length + " changes"));
      container.appendChild(el("span", "endpoint", opts.endpoint));
      const spacer = el("span", "spacer");
      container.appendChild(spacer);

      if (blocked) {
        container.appendChild(el("span", "blocked", "Fix the highlighted field first"));
      }

      const discard = el("button", null, "Discard");
      discard.type = "button";
      discard.addEventListener("click", opts.onDiscard);
      container.appendChild(discard);

      const save = el("button", "primary", opts.saveLabel || "Save");
      save.type = "button";
      save.disabled = blocked;
      save.addEventListener("click", opts.onSave);
      container.appendChild(save);
    }
  };
}
```

- [ ] **Step 4: Write the channel mix control**

Create `modules/daemon/src/main/resources/static/js/console/config/channel-mix.js`:

```js
// The six retrieval channel weights, drawn as one bar.
//
// A weight means nothing on its own — only its share of the total decides what the channel
// contributes, so the bar is the value and the numbers are the detail. A weight of 0 is a
// documented disable, not a small number: the segment disappears and the legend greys out.
import { el } from "../../util/dom.js";

export const CHANNEL_COLORS = {
  "retrieval.weight-exact-key": "#58a6ff",
  "retrieval.weight-fts-memory": "#3fb950",
  "retrieval.weight-hyde-vector": "#bc8cff",
  "retrieval.weight-direct-vector": "#56d4dd",
  "retrieval.weight-fts-message": "#d29922",
  "retrieval.weight-graph": "#ff7b72"
};

export function renderChannelMix(container, weights, opts) {
  const options = opts || {};
  container.innerHTML = "";
  const wrap = el("div", "cfg-mix");

  const head = el("div");
  head.style.display = "flex";
  head.style.alignItems = "baseline";
  head.style.marginBottom = "9px";
  head.appendChild(el("span", "section", "Channel mix"));
  head.appendChild(el("span", "spacer"));

  let total = 0;
  weights.forEach(function (w) { total += Number(w.value) || 0; });
  head.appendChild(el("span", "mono num", "total " + total.toFixed(1)));
  wrap.appendChild(head);

  const bar = el("div", "cfg-mix-bar");
  const divisor = total > 0 ? total : 1;
  weights.forEach(function (w) {
    const value = Number(w.value) || 0;
    if (value <= 0) return;
    const segment = el("span");
    segment.style.width = ((value / divisor) * 100).toFixed(2) + "%";
    segment.style.background = CHANNEL_COLORS[w.key] || "var(--accent)";
    segment.title = w.key + " = " + value;
    bar.appendChild(segment);
  });
  wrap.appendChild(bar);

  const legend = el("div", "cfg-mix-legend");
  let anyDisabled = false;
  weights.forEach(function (w) {
    const value = Number(w.value) || 0;
    const disabled = value <= 0;
    if (disabled) anyDisabled = true;
    const button = el("button", disabled ? "off" : null);
    button.type = "button";
    if (options.focused === w.key) button.classList.add("active");
    const swatch = el("span", "cfg-mix-swatch");
    swatch.style.background = disabled ? "var(--border)" : (CHANNEL_COLORS[w.key] || "var(--accent)");
    button.appendChild(swatch);
    button.appendChild(el("span", "mono", w.label));
    button.appendChild(el("span", "mono num", value.toFixed(1)));
    button.appendChild(el("span", "num",
      total > 0 ? Math.round((value / divisor) * 100) + "%" : "0%"));
    if (options.onFocus) {
      button.addEventListener("click", function () { options.onFocus(w.key); });
    }
    legend.appendChild(button);
  });
  wrap.appendChild(legend);

  if (anyDisabled && options.disabledNote) {
    wrap.appendChild(el("div", "cfg-hint", options.disabledNote));
  }

  container.appendChild(wrap);
  return { total: total, disabled: anyDisabled };
}
```

- [ ] **Step 5: Write the profile view**

Create `modules/daemon/src/main/resources/static/js/console/config/profile.js`:

```js
// Per-profile configuration: the whitelisted retrieval and ingestion overrides.
//
// Provenance comes from the stored override map returned by /config/detail, never from diffing
// effective against global — a profile may deliberately override a key to the global value, and
// diffing would render that as inherited and then silently drop it on the next save.
import { $, el, api, apiFetch } from "../../util/dom.js";
import { loadSchema, bySection } from "./schema.js";
import { renderFieldRow } from "./field.js";
import { createForm } from "./form.js";
import { renderChannelMix } from "./channel-mix.js";
import { toast } from "../toast.js";

const SECTION_TITLES = {
  channels: "Retrieval channels",
  graph: "Graph traversal",
  "code-graph": "Code graph",
  fusion: "Fusion and limits",
  ingestion: "Ingestion"
};

const OPEN_BY_DEFAULT = { channels: true, ingestion: true };

let form = null;
let schemaFields = null;
let layers = null;
let openSections = Object.assign({}, OPEN_BY_DEFAULT);
let focusedChannel = null;
let currentProfile = "";

function at(tree, key) {
  const parts = key.split(".");
  let node = tree;
  for (let i = 0; i < parts.length; i++) {
    if (node == null) return undefined;
    node = node[parts[i]];
  }
  return node;
}

// Rebuild the sparse {ingestion:{}, retrieval:{}} payload the daemon whitelist accepts.
function toPayload(values) {
  const body = {};
  Object.keys(values).forEach(function (key) {
    const parts = key.split(".");
    if (!body[parts[0]]) body[parts[0]] = {};
    body[parts[0]][parts[1]] = values[key];
  });
  return body;
}

function flattenOverrides(overrides) {
  const flat = {};
  ["ingestion", "retrieval"].forEach(function (group) {
    const section = overrides ? overrides[group] : null;
    if (!section) return;
    Object.keys(section).forEach(function (key) {
      if (section[key] !== null && section[key] !== undefined) {
        flat[group + "." + key] = section[key];
      }
    });
  });
  return flat;
}

export function loadProfileConfig(profile) {
  currentProfile = profile;
  const root = $("view-profile-config");
  root.innerHTML = "";
  root.appendChild(el("div", "banner", "Loading configuration…"));

  Promise.all([loadSchema(), fetchDetail(profile)])
    .then(function (results) {
      const fields = {};
      results[0].forEach(function (field) {
        if (field.scope === "profile") fields[field.key] = field;
      });
      schemaFields = fields;
      layers = results[1];
      form = createForm({ fields: fields });
      form.load(flattenOverrides(layers.overrides), fields);
      render();
    })
    .catch(function (e) {
      root.innerHTML = "";
      root.appendChild(el("div", "banner err", e.message));
    });
}

export function unloadProfileConfig() {
  form = null;
  layers = null;
  focusedChannel = null;
  openSections = Object.assign({}, OPEN_BY_DEFAULT);
}

function fetchDetail(profile) {
  return apiFetch(api(profile, "/config/detail"), { headers: { Accept: "application/json" } })
    .then(function (r) {
      if (!r.ok) throw new Error("Could not load configuration (" + r.status + ").");
      return r.json();
    });
}

function valueOf(key) {
  return form.isSet(key) ? form.values[key] : at(layers.global, key);
}

function render() {
  const root = $("view-profile-config");
  const errors = form.errors();
  root.innerHTML = "";

  const head = el("div", "cfg-head");
  head.appendChild(el("h2", null, "Configuration"));
  head.appendChild(el("span", "cfg-scope mono", currentProfile));
  root.appendChild(head);
  root.appendChild(el("p", "cfg-lede",
    "Overrides for this profile only. Anything left alone inherits the global configuration. "
    + "The daemon accepts the retrieval and ingestion keys below and rejects everything else."));

  const total = Object.keys(schemaFields).length;
  const setCount = Object.keys(form.values).length;
  const summary = el("div", "cfg-summary");
  summary.appendChild(el("span", "cfg-dot"));
  summary.appendChild(el("span", null, setCount + " of " + total + " fields overridden"));
  summary.appendChild(el("span", "muted small", (total - setCount) + " inherited from global"));
  summary.appendChild(el("span", "spacer"));
  if (setCount > 0) {
    const resetAll = el("button", "danger", "Reset all overrides");
    resetAll.type = "button";
    resetAll.addEventListener("click", confirmResetAll);
    summary.appendChild(resetAll);
  }
  root.appendChild(summary);

  const graphOff = Number(valueOf("retrieval.weight-graph")) === 0;

  bySection(Object.values(schemaFields), "profile").forEach(function (group) {
    root.appendChild(renderSection(group, errors, graphOff));
  });

  const bar = el("div", "cfg-savebar");
  root.appendChild(bar);
  form.renderSaveBar(bar, {
    endpoint: "PUT /v1/profiles/" + currentProfile + "/config",
    saveLabel: "Save overrides",
    onDiscard: function () { form.discard(); render(); },
    onSave: save
  });
}

function renderSection(group, errors, graphOff) {
  const section = el("section", "cfg-section");
  const inactive = group.section === "graph" && graphOff;
  const open = !!openSections[group.section];

  const head = el("button", "cfg-section-head");
  head.type = "button";
  head.setAttribute("aria-expanded", String(open));
  const chevron = el("span", "ico");
  chevron.innerHTML = '<svg width="15" height="15" viewBox="0 0 24 24"><path d="m10 6 6 6-6 6"/></svg>';
  head.appendChild(chevron);
  head.appendChild(el("span", "cfg-section-title", SECTION_TITLES[group.section] || group.section));

  const setHere = group.fields.filter(function (f) { return form.isSet(f.key); }).length;
  head.appendChild(el("span", "spacer"));
  if (setHere > 0) head.appendChild(el("span", "cfg-chip", setHere + " overridden"));
  head.addEventListener("click", function () {
    openSections[group.section] = !open;
    render();
  });
  section.appendChild(head);

  const body = el("div", "cfg-section-body");
  body.hidden = !open;

  if (group.section === "channels") {
    const mixHost = el("div");
    body.appendChild(mixHost);
    const weights = group.fields
      .filter(function (f) { return f.kind === "weight"; })
      .map(function (f) {
        return {
          key: f.key,
          label: f.key.replace("retrieval.weight-", ""),
          value: valueOf(f.key)
        };
      });
    renderChannelMix(mixHost, weights, {
      focused: focusedChannel,
      disabledNote: "A weight of 0 disables the channel; its traversal settings below are inactive.",
      onFocus: function (key) {
        focusedChannel = focusedChannel === key ? null : key;
        render();
      }
    });
  }

  group.fields.forEach(function (field) {
    body.appendChild(renderFieldRow(field, {
      value: valueOf(field.key),
      source: form.isSet(field.key) ? "set" : "inherited",
      sourceLabel: form.isSet(field.key)
        ? "overridden"
        : "global " + formatValue(at(layers.global, field.key)),
      error: errors[field.key],
      disabled: inactive,
      onChange: function (next) { form.set(field.key, next); render(); },
      onReset: function () { form.clear(field.key); render(); }
    }));
  });

  section.appendChild(body);
  return section;
}

function formatValue(value) {
  if (value === true) return "on";
  if (value === false) return "off";
  return String(value);
}

function confirmResetAll() {
  if (!window.confirm("Reset every override for " + currentProfile
    + "? All " + Object.keys(form.values).length
    + " fields fall back to the global configuration.")) return;
  form.clearAll();
  render();
}

function save() {
  const values = form.values;
  const empty = Object.keys(values).length === 0;
  const request = empty
    ? apiFetch(api(currentProfile, "/config"), { method: "DELETE" })
    : apiFetch(api(currentProfile, "/config"), {
      method: "PUT",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify(toPayload(values))
    });

  request
    .then(function (r) {
      if (!r.ok) return r.text().then(function (text) { throw new Error(text || ("Save failed (" + r.status + ").")); });
      form.commit();
      toast("Overrides saved for " + currentProfile, "ok");
      return fetchDetail(currentProfile);
    })
    .then(function (fresh) {
      if (fresh) layers = fresh;
      render();
    })
    .catch(function (e) { toast(e.message, "err"); });
}
```

- [ ] **Step 6: Add the view section to the shell**

In `modules/daemon/src/main/resources/static/index.html`, add inside `<main>` alongside the other `.view` sections:

```html
    <!-- ===== Per-profile configuration ===== -->
    <section class="view" id="view-profile-config"></section>
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.console.ConfigConsoleAssetsTests'`
Expected: PASS — all eight tests green.

`toast.js` exports `toast(msg, kind)` with `kind` being `"ok"` or `"err"` — verified, the import in `profile.js` is correct as written. Do not add a new export to `toast.js`.

- [ ] **Step 8: Commit**

```bash
git add modules/daemon/src/main/resources/static/js/console/config \
        modules/daemon/src/main/resources/static/index.html \
        modules/daemon/src/test/java/dev/alvo/pieria/console/ConfigConsoleAssetsTests.java
git commit -m "feat(console): add the per-profile configuration view"
```

---

### Task 8: Global configuration view

**Files:**
- Create: `modules/daemon/src/main/resources/static/js/console/config/global.js`
- Modify: `modules/daemon/src/main/resources/static/index.html` (add `#view-global-config`)
- Modify: `modules/daemon/src/test/java/dev/alvo/pieria/console/ConfigConsoleAssetsTests.java`

**Interfaces:**
- Consumes: `renderFieldRow`, `loadSchema`/`bySection`, `createForm`, `GET`/`PUT /v1/config` (Task 5).
- Produces: `loadGlobalConfig()`, `unloadGlobalConfig()`.

**Three behaviours the daemon dictates:**
1. Sections are grouped by **tier**, not by topic: `live`, `restart`, `locked`. The entry's `tier` decides which panel it lands in; `section` orders fields within it.
2. `restart-pending` on a GET means the file and the running process already disagree — the banner shows on load, not only after an edit in this session.
3. The locked panel stays read-only until the operator unlocks it, and the PUT then carries `acknowledgeDestructive: true`. The daemon enforces this independently; the UI gate is the second layer.

- [ ] **Step 1: Write the failing test**

Append to `modules/daemon/src/test/java/dev/alvo/pieria/console/ConfigConsoleAssetsTests.java`:

```java
  @Test
  void globalViewGroupsByTierAndHandsOverTheRestartCommand() throws IOException {
    String global = resource("static/js/console/config/global.js");

    assertThat(global)
      .contains("export function loadGlobalConfig", "export function unloadGlobalConfig")
      .contains("\"/v1/config\"", "\"live\"", "\"restart\"", "\"locked\"");
    // The browser cannot restart the daemon; the page hands over the command the daemon serves
    // rather than offering a button that would not do what it says.
    assertThat(global).contains("restartCommand").doesNotContain("/v1/daemon/restart");
  }

  @Test
  void lockedTierRequiresAnExplicitAcknowledgementOnTheWire() throws IOException {
    String global = resource("static/js/console/config/global.js");

    assertThat(global).contains("acknowledgeDestructive");
    assertThat(global).contains("memories_vec");
  }

  @Test
  void pendingRestartIsReadFromTheServerNotInferredFromLocalEdits() throws IOException {
    String global = resource("static/js/console/config/global.js");

    // The daemon reports file-vs-running divergence, so the banner survives a page reload.
    assertThat(global).contains("restart-pending");
  }

  @Test
  void globalConfigViewSectionExistsInTheShell() throws IOException {
    Document html = Jsoup.parse(resource("static/index.html"));

    assertThat(html.select("main > section.view#view-global-config")).hasSize(1);
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.console.ConfigConsoleAssetsTests'`
Expected: FAIL — `resource static/js/console/config/global.js` is null.

- [ ] **Step 3: Write the global view**

Create `modules/daemon/src/main/resources/static/js/console/config/global.js`:

```js
// Process-global configuration: what every profile inherits and no profile can override.
//
// Grouped by what applying a change costs, not by topic. The daemon binds most of these once at
// startup, so a save does not change the running process outside the `live` tier — the page says
// which keys that applies to, and hands over `pieria daemon restart` rather than offering a button
// the browser cannot honour.
import { $, el, apiFetch } from "../../util/dom.js";
import { renderFieldRow } from "./field.js";
import { createForm } from "./form.js";
import { toast } from "../toast.js";

const TIERS = [
  { id: "live", title: "Applies immediately", note: "the daemon picks these up on save" },
  { id: "restart", title: "Takes effect after a restart", note: "bound once at startup" },
  { id: "locked", title: "Locked", note: "changing these invalidates stored data" }
];

const LOCKED_WARNING = "The embedding dimension fixes the width of the memories_vec column, so "
  + "changing it invalidates every stored vector — it needs a re-embed, not a save. Moving the "
  + "database path points the daemon at a different store; the existing one is left behind.";

let form = null;
let snapshot = null;
let fieldsByKey = null;
let unlocked = false;

export function loadGlobalConfig() {
  const root = $("view-global-config");
  root.innerHTML = "";
  root.appendChild(el("div", "banner", "Loading configuration…"));

  apiFetch("/v1/config", { headers: { Accept: "application/json" } })
    .then(function (r) {
      if (!r.ok) throw new Error("Could not load configuration (" + r.status + ").");
      return r.json();
    })
    .then(function (body) {
      snapshot = body;
      fieldsByKey = {};
      const setValues = {};
      body.entries.forEach(function (entry) {
        fieldsByKey[entry.key] = entry;
        if (entry.provenance === "set") setValues[entry.key] = entry["file-value"];
      });
      form = createForm({ fields: fieldsByKey });
      form.load(setValues, fieldsByKey);
      render();
    })
    .catch(function (e) {
      root.innerHTML = "";
      root.appendChild(el("div", "banner err", e.message));
    });
}

export function unloadGlobalConfig() {
  form = null;
  snapshot = null;
  unlocked = false;
}

function valueOf(entry) {
  return form.isSet(entry.key) ? form.values[entry.key] : entry.value;
}

function render() {
  const root = $("view-global-config");
  const errors = form.errors();
  root.innerHTML = "";

  const head = el("div", "cfg-head");
  head.appendChild(el("h2", null, "Configuration"));
  head.appendChild(el("span", "cfg-scope global mono", "daemon"));
  root.appendChild(head);
  root.appendChild(el("p", "cfg-lede",
    "Process-global settings. Every profile inherits these, and a profile can only override the "
    + "retrieval and ingestion subset. Grouped by what applying a change costs you."));

  const file = el("div", "cfg-summary");
  file.appendChild(el("span", "muted small", "Written to"));
  file.appendChild(el("span", "mono small", snapshot.configFile));
  root.appendChild(file);

  const changed = form.changedKeys();
  const pendingKeys = snapshot.entries
    .filter(function (entry) { return entry["restart-pending"]; })
    .map(function (entry) { return entry.key; });

  TIERS.forEach(function (tier) {
    const entries = snapshot.entries.filter(function (entry) { return entry.tier === tier.id; });
    if (!entries.length) return;
    root.appendChild(renderTier(tier, entries, errors, changed, pendingKeys));
  });

  const bar = el("div", "cfg-savebar");
  root.appendChild(bar);
  form.renderSaveBar(bar, {
    endpoint: "PUT /v1/config",
    saveLabel: "Save configuration",
    onDiscard: function () { form.discard(); render(); },
    onSave: save
  });
}

function renderTier(tier, entries, errors, changed, pendingKeys) {
  const section = el("section", "cfg-section " + tier.id);

  const head = el("div", "cfg-section-head");
  head.appendChild(el("span", "cfg-section-title", tier.title));
  head.appendChild(el("span", "cfg-section-note", tier.note));
  head.appendChild(el("span", "spacer"));
  const setHere = entries.filter(function (e) { return form.isSet(e.key); }).length;
  if (setHere > 0) head.appendChild(el("span", "cfg-chip", setHere + " set"));
  section.appendChild(head);

  if (tier.id === "restart") {
    const dirtyHere = entries.filter(function (e) { return changed.indexOf(e.key) >= 0; });
    const pendingHere = entries.filter(function (e) { return pendingKeys.indexOf(e.key) >= 0; });
    const count = dirtyHere.length + pendingHere.length;
    if (count > 0) {
      const banner = el("div", "cfg-banner warn");
      banner.appendChild(el("div", null,
        count === 1 ? "1 change needs a restart" : count + " changes need a restart"));
      banner.appendChild(el("div", "muted small",
        "Saving writes the value now; the running daemon keeps the old one until it restarts. "
        + "The console cannot restart it — run this yourself:"));
      banner.appendChild(el("code", null, snapshot.restartCommand));
      section.appendChild(banner);
    }
  }

  if (tier.id === "locked") {
    const gate = el("div", "cfg-banner danger");
    gate.appendChild(el("div", null, LOCKED_WARNING));
    const button = el("button", null, unlocked ? "Lock again" : "Unlock to edit");
    button.type = "button";
    button.addEventListener("click", function () {
      if (unlocked) { unlocked = false; render(); return; }
      if (window.confirm("Unlock these settings? Changing them still requires a restart and a "
        + "full re-embed of every memory in the store.")) {
        unlocked = true;
        render();
      }
    });
    gate.appendChild(button);
    section.appendChild(gate);
  }

  const body = el("div", "cfg-section-body");
  entries.forEach(function (entry) {
    const disabled = tier.id === "locked" && !unlocked;
    const pending = pendingKeys.indexOf(entry.key) >= 0;
    body.appendChild(renderFieldRow(entry, {
      value: valueOf(entry),
      source: form.isSet(entry.key) ? "set" : "default",
      sourceLabel: form.isSet(entry.key)
        ? (pending ? "restart pending" : "set")
        : "default",
      error: errors[entry.key],
      disabled: disabled,
      onChange: function (next) { form.set(entry.key, next); render(); },
      onReset: function () { form.clear(entry.key); render(); }
    }));
  });
  section.appendChild(body);
  return section;
}

function save() {
  const values = {};
  // A key that was set and has been reset is sent as null, which clears it on the daemon.
  form.changedKeys().forEach(function (key) {
    values[key] = form.isSet(key) ? String(form.values[key]) : null;
  });

  apiFetch("/v1/config", {
    method: "PUT",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({ values: values, acknowledgeDestructive: unlocked })
  })
    .then(function (r) {
      if (!r.ok) return r.text().then(function (text) { throw new Error(text || ("Save failed (" + r.status + ").")); });
      return r.json();
    })
    .then(function (result) {
      const needsRestart = (result["restart-required"] || []).length;
      toast(needsRestart
        ? "Saved. " + needsRestart + " setting(s) apply after a restart."
        : "Configuration saved.", "ok");
      loadGlobalConfig();
    })
    .catch(function (e) { toast(e.message, "err"); });
}
```

- [ ] **Step 4: Add the view section to the shell**

In `modules/daemon/src/main/resources/static/index.html`, next to the profile config section:

```html
    <!-- ===== Global daemon configuration ===== -->
    <section class="view" id="view-global-config"></section>
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.console.ConfigConsoleAssetsTests'`
Expected: PASS — all twelve tests green.

- [ ] **Step 6: Commit**

```bash
git add modules/daemon/src/main/resources/static/js/console/config/global.js \
        modules/daemon/src/main/resources/static/index.html \
        modules/daemon/src/test/java/dev/alvo/pieria/console/ConfigConsoleAssetsTests.java
git commit -m "feat(console): add the global daemon configuration view"
```

---

### Task 9: Side-panel entries and routing

**Files:**
- Modify: `modules/daemon/src/main/resources/static/index.html`
- Modify: `modules/daemon/src/main/resources/static/js/console/router.js`
- Modify: `modules/daemon/src/main/resources/static/js/console/main.js`
- Modify: `modules/daemon/src/main/resources/static/js/console/profiles.js`
- Modify: `modules/daemon/src/main/resources/static/css/console.css`
- Modify: `modules/daemon/src/test/java/dev/alvo/pieria/console/ConfigConsoleAssetsTests.java`

**Interfaces:**
- Consumes: `loadProfileConfig`/`unloadProfileConfig` (Task 7), `loadGlobalConfig`/`unloadGlobalConfig` (Task 8), `setView` (existing).
- Produces: two new view names in the router — `profile-config` and `global-config` — reachable from the side panel and from `?view=`.

**Placement rationale (from the spec's `SidePanel.dc.html`):** the panel already has exactly the two categories the two config layers need. The active profile reveals a Configuration child; the daemon block gains one global Configuration entry. No seventh nav tab — a tab would read as global and undercut the per-profile scoping.

- [ ] **Step 1: Write the failing test**

Append to `modules/daemon/src/test/java/dev/alvo/pieria/console/ConfigConsoleAssetsTests.java`:

```java
  @Test
  void bothConfigEntriesLiveInTheSidePanelNotTheNavBar() throws IOException {
    Document html = Jsoup.parse(resource("static/index.html"));

    // Global config hangs off the daemon block, because that is its scope.
    assertThat(html.select("#sidePanel #daemonConfigLink[data-view=global-config]")).hasSize(1);
    // A seventh nav tab would read as global and undercut the per-profile scoping.
    assertThat(html.select(".nav button[data-view=profile-config]")).isEmpty();
    assertThat(html.select(".nav button[data-view=global-config]")).isEmpty();
  }

  @Test
  void selectingAProfileRevealsItsConfigurationEntry() throws IOException {
    String profiles = resource("static/js/console/profiles.js");
    String css = resource("static/css/console.css");

    assertThat(profiles).contains("side-panel-subitem", "profile-config");
    assertThat(css).contains(".side-panel-subitem");
  }

  @Test
  void routerLoadsAndTearsDownBothConfigViews() throws IOException {
    String router = resource("static/js/console/router.js");
    String main = resource("static/js/console/main.js");

    assertThat(router)
      .contains("profile-config", "global-config")
      .contains("loadProfileConfig", "loadGlobalConfig")
      // Both views hold fetched state; leaving must drop it so a profile switch cannot show
      // the previous profile's overrides.
      .contains("unloadProfileConfig", "unloadGlobalConfig");
    assertThat(main).contains("\"profile-config\"", "\"global-config\"");
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.console.ConfigConsoleAssetsTests'`
Expected: FAIL — `#daemonConfigLink` is not in `index.html`.

- [ ] **Step 3: Add the daemon Configuration entry to the shell**

In `modules/daemon/src/main/resources/static/index.html`, inside the `<section class="daemon" id="daemonStatus">` block, after the `</div>` closing `.daemon-body`:

```html
        <button class="side-panel-item" id="daemonConfigLink" type="button" data-view="global-config">
          <span class="side-panel-rail"></span>
          <span class="side-panel-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" focusable="false"><circle cx="12" cy="12" r="3.2"/><path d="M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-1.8-.3 1.6 1.6 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.6 1.6 0 0 0-1-1.5 1.6 1.6 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0 .3-1.8 1.6 1.6 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.6 1.6 0 0 0 1.5-1 1.6 1.6 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 1.8.3H9a1.6 1.6 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 1 1.5 1.6 1.6 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0-.3 1.8V9a1.6 1.6 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1Z"/></svg>
          </span>
          <span class="side-panel-name">Configuration</span>
        </button>
```

- [ ] **Step 4: Add the sub-item style**

In `modules/daemon/src/main/resources/static/css/console.css`, after the `.side-panel-item` rules:

```css
/* A profile's own sections, revealed when it is selected. Indented and a step quieter than the
   profile row, so the hierarchy is legible without a second icon column. */
.side-panel-sublist { list-style: none; margin: 2px 0 0; padding: 0 0 0 11px; }
.side-panel-sublist li + li { margin-top: 2px; }
.side-panel-subitem { padding: 6px 9px 6px 0; font-size: 12.5px; }
.side-panel-subitem .side-panel-rail { height: 14px; }
.side-panel-subitem .side-panel-icon { flex: 0 0 18px; width: 18px; height: 18px; }
.side-panel-subitem .side-panel-icon svg { width: 15px; height: 15px; }
.side-panel.is-collapsed .side-panel-sublist { display: none; }
```

- [ ] **Step 5: Render the profile sub-entry**

In `modules/daemon/src/main/resources/static/js/console/profiles.js`, replace the body of `renderProfiles` with:

```js
function renderProfiles(profiles) {
  const list = profileList();
  list.innerHTML = "";
  profiles.forEach(function (profile) {
    const row = el("li");
    const button = el("button", "side-panel-item");
    button.type = "button";
    button.dataset.profile = profile.name;
    button.title = profile.name + " (" + profile.memoryCount + ")";
    // Rail marks the active profile; the count is a tabular column rather than part of the label,
    // so a scan down the list reads as names on the left and magnitudes on the right.
    button.appendChild(el("span", "side-panel-rail"));
    button.appendChild(el("span", "side-panel-name", profile.name));
    button.appendChild(el("span", "side-panel-count mono num", String(profile.memoryCount)));
    if (profile.name === state.profile) {
      button.classList.add("active");
      button.setAttribute("aria-current", "page");
    }
    row.appendChild(button);

    // The selected profile reveals its own sections. Configuration is per-profile, so its entry
    // belongs under the profile rather than in the global nav.
    if (profile.name === state.profile) {
      const sub = el("ul", "side-panel-sublist");
      const item = el("li");
      const link = el("button", "side-panel-item side-panel-subitem");
      link.type = "button";
      link.dataset.view = "profile-config";
      link.appendChild(el("span", "side-panel-rail"));
      const gear = el("span", "side-panel-icon");
      gear.setAttribute("aria-hidden", "true");
      gear.innerHTML = '<svg viewBox="0 0 24 24" focusable="false"><circle cx="12" cy="12" r="3.2"/>'
        + '<path d="M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-1.8-.3'
        + ' 1.6 1.6 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.6 1.6 0 0 0-1-1.5 1.6 1.6 0 0 0-1.8.3l-.1.1a2 2'
        + ' 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0 .3-1.8 1.6 1.6 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.6 1.6 0'
        + ' 0 0 1.5-1 1.6 1.6 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 1.8.3H9a1.6'
        + ' 1.6 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 1 1.5 1.6 1.6 0 0 0 1.8-.3l.1-.1a2 2 0 1'
        + ' 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0-.3 1.8V9a1.6 1.6 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.6 1.6 0'
        + ' 0 0-1.5 1Z"/></svg>';
      link.appendChild(gear);
      link.appendChild(el("span", "side-panel-name", "Configuration"));
      if (state.view === "profile-config") {
        link.classList.add("active");
        link.setAttribute("aria-current", "page");
      }
      item.appendChild(link);
      sub.appendChild(item);
      row.appendChild(sub);
    }

    list.appendChild(row);
  });
}
```

- [ ] **Step 6: Route the two views**

In `modules/daemon/src/main/resources/static/js/console/router.js`, add the imports and extend `setView` / `loadActiveView`:

```js
import { loadProfileConfig, unloadProfileConfig } from "./config/profile.js";
import { loadGlobalConfig, unloadGlobalConfig } from "./config/global.js";
```

Replace `setView` and `loadActiveView` with:

```js
// Reflect the active tab in the nav, the visible section, and the URL, then (re)load its data.
export function setView(view) {
  const leavingGraph = state.view === "graph" && view !== "graph";
  // Both config views hold fetched state; leaving must drop it so a profile switch cannot render
  // the previous profile's overrides against the new profile's name.
  if (state.view === "profile-config" && view !== "profile-config") unloadProfileConfig();
  if (state.view === "global-config" && view !== "global-config") unloadGlobalConfig();

  state.view = view;
  document.querySelectorAll(".nav button").forEach(function (b) {
    b.classList.toggle("active", b.dataset.view === view);
  });
  document.querySelectorAll(".view").forEach(function (s) {
    s.classList.toggle("active", s.id === "view-" + view);
  });
  document.body.classList.toggle("view-graph", view === "graph");
  document.querySelectorAll("#sidePanel button[data-view]").forEach(function (b) {
    b.classList.toggle("active", b.dataset.view === view);
  });
  // Hand the graph its own teardown rather than relying on CSS alone: it owns a running
  // simulation and pointer state that must not keep working behind another tab.
  if (leavingGraph) hideGraph();
  syncUrl();
  loadActiveView(false);
}

export function loadActiveView(profileChanged) {
  if (!state.profile) return;
  if (state.view === "memories") loadMemories(profileChanged);
  else if (state.view === "stats") loadStats();
  else if (state.view === "audit") loadAudit(profileChanged);
  else if (state.view === "graph") showGraph(state.profile, profileChanged);
  else if (state.view === "profile-config") loadProfileConfig(state.profile);
  else if (state.view === "global-config") loadGlobalConfig();
  // add + recall views are lazy (populated on submit).
}
```

- [ ] **Step 7: Wire the side-panel clicks**

In `modules/daemon/src/main/resources/static/js/console/main.js`, extend `VIEWS` and the `profileList` handler:

```js
const VIEWS = ["memories", "add", "recall", "stats", "audit", "graph",
  "profile-config", "global-config"];
```

Replace the `profileList` click handler and add the daemon link handler inside `wireUp()`:

```js
  $("profileList").addEventListener("click", function (e) {
    const link = e.target.closest("button[data-view]");
    if (link) { setView(link.dataset.view); return; }
    const button = e.target.closest("button[data-profile]");
    if (button) selectProfile(button.dataset.profile);
  });
  $("daemonConfigLink").addEventListener("click", function () { setView("global-config"); });
```

Finally, in `selectProfile` (`profiles.js`), re-render the list so the sub-entry follows the selection. Replace the function with:

```js
export function selectProfile(profile) {
  if (!profile) return;
  state.profile = profile;
  markSelected(profile);
  $("profileLabel").textContent = "· " + profile;
  syncUrl();
  loadActiveView(true);   // reload whatever view is active
}
```

and extend `markSelected` so the sub-list is rebuilt for the newly selected profile:

```js
function markSelected(profile) {
  profileList().querySelectorAll("button[data-profile]").forEach(function (button) {
    const selected = button.dataset.profile === profile;
    button.classList.toggle("active", selected);
    if (selected) button.setAttribute("aria-current", "page");
    else button.removeAttribute("aria-current");
    // The Configuration sub-entry belongs to the selected profile only.
    const row = button.closest("li");
    const sub = row ? row.querySelector(".side-panel-sublist") : null;
    if (sub && !selected) sub.remove();
    if (selected && !sub) renderSubList(row);
  });
}
```

Extract the sub-entry construction from Step 5 into a `renderSubList(row)` helper in `profiles.js` and call it from both `renderProfiles` and `markSelected`, so there is one definition of the entry.

- [ ] **Step 8: Run the test to verify it passes**

Run: `./gradlew :daemon:test --tests 'dev.alvo.pieria.console.ConfigConsoleAssetsTests'`
Expected: PASS — all fifteen tests green.

- [ ] **Step 9: Run the full suite**

Run: `./gradlew test`
Expected: PASS — including the pre-existing `ConsoleAssetsTests`, which asserts on `index.html` structure and must not have been disturbed.

- [ ] **Step 10: Ask the user to verify in a running daemon**

The console cannot be exercised by the test suite. Ask the user to run `./gradlew :daemon:deployLocal` (or their usual restart) themselves and confirm in the browser: select a profile → Configuration, change a channel weight, watch the mix bar reflow and the save bar appear, save, reload the page and confirm the override persists with an "overridden" chip. Then Daemon → Configuration, change a restart-tier field, save, and confirm the banner still shows the pending restart after a page reload. **Do not run `deployLocal` yourself.**

- [ ] **Step 11: Commit**

```bash
git add modules/daemon/src/main/resources/static/index.html \
        modules/daemon/src/main/resources/static/css/console.css \
        modules/daemon/src/main/resources/static/js/console/router.js \
        modules/daemon/src/main/resources/static/js/console/main.js \
        modules/daemon/src/main/resources/static/js/console/profiles.js \
        modules/daemon/src/test/java/dev/alvo/pieria/console/ConfigConsoleAssetsTests.java
git commit -m "feat(console): route both configuration views from the side panel"
```

---

## Self-Review

**Spec coverage.** Every artboard maps to tasks:

| Spec element | Task |
|---|---|
| Field row anatomy, provenance dot, quiet inherited rows (`States` §1–2) | 6 |
| Six control kinds incl. secret and locked (`States` §3) | 6 |
| Channel mix bar, 0 as a disable (`Main`, `States` §4) | 7 |
| Save bar: dirty, restart-required, blocked (`States` §5) | 6 (styles), 7, 8 |
| Client validation + daemon whitelist rejection (`States` §6) | 4 (server), 7 (client) |
| Locked unlock flow (`States` §7, `Global`) | 4 (server guard), 8 (UI gate) |
| Empty / unreachable / CLI-pushed edge states (`States` §8) | 7, 8 (banner paths); the CLI-pushed reload prompt is **not** implemented — see below |
| Side-panel IA, both entries, routes (`SidePanel`) | 9 |
| `PUT /v1/config` as a new endpoint (`SidePanel` routes card) | 5 |
| Per-profile page against the existing whitelist | 2, 7 |

**Known gap, deliberately deferred.** The spec's third edge state — "a `pieria config sync` replaced the overrides while this page was open, offer to reload" — has no task. Detecting it needs either a version/etag on the profile config row or polling, and neither exists today. The current behaviour is last-write-wins, matching every other console view. Worth a follow-up; not worth blocking this on.

**Placeholder scan.** No `TBD`/`TODO`. Every code step carries complete source. One step carries a conditional rather than fixed code — Task 4 Step 2, add `spring-test` to the daemon's test dependencies if `MockEnvironment` does not resolve. It names the exact file and the exact decision.

**Verified against the current tree while writing.** `toast.js` exports `toast(msg, kind)`. `MemoryStore.getProfileConfig(String)` returns `Optional<String>` and *throws `UnsupportedOperationException` by default* — Task 2's `storedOverrides` catches `RuntimeException` and returns empty overrides, matching `EffectiveConfigResolver`'s fail-open stance, so a backend without profile-config support degrades to "everything inherited" rather than breaking the page. `/v1/config` does not collide with any existing mapping (`/v1/profiles/{name}` and its sub-paths, plus `/v1/tasks`, are the whole surface). Controllers in this repo are tested by direct instantiation, which is why no task reaches for MockMvc.

**Type consistency.** Checked across tasks: `ConfigField` accessors (`key/scope/section/tier/kind/options/label/hint`) are used identically in Tasks 1, 4, 5. `GlobalConfigService.ApplyResult(written, cleared, restartRequired)` is constructed in Task 4 and read in Task 5 as `written` / `restart-required` (kebab on the wire via `ConfigCodec`) and in Task 8's `global.js` as `result["restart-required"]`. `GlobalConfigEntry.fileValue`/`restartPending` serialize as `file-value`/`restart-pending`, asserted in Task 5 and read in Task 8. `createForm` exposes `load/set/clear/clearAll/discard/commit/isSet/changedKeys/errors/renderSaveBar` — Task 7 and Task 8 both use exactly that set. `renderFieldRow(field, view)` takes `{value, source, sourceLabel, error, disabled, onChange, onReset}` in Tasks 6, 7 and 8.

One naming note carried deliberately: `renderFieldRow` is given a `ConfigField` on the profile page and a `GlobalConfigEntry` on the global page. Both carry `key/label/kind/options/hint`, which is all the renderer touches — the entry is a superset of the field. Do not narrow the renderer to one type.
