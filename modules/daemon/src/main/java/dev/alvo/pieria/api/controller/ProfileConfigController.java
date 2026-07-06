package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.config.EffectiveConfigResolver;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.ResolvedConfig;
import dev.alvo.pieria.config.model.DaemonOverrides;
import dev.alvo.pieria.config.model.DaemonOverrides.Ingestion;
import dev.alvo.pieria.config.model.DaemonOverrides.Retrieval;
import dev.alvo.pieria.config.toml.ConfigCodec;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.storage.MemoryStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per-profile config overrides, pushed by the CLI from a project's merged
 * {@code .pieria/config.toml}. The body is the whitelisted {@link DaemonOverrides} subset in
 * kebab-case JSON; anything else (process-global properties like {@code model.embedding-dimension}
 * or {@code db.path}, or unknown keys) is rejected with 400 so a stray project file can never
 * reach process-global state. GET returns the <em>effective</em> config — the global properties
 * overlaid with the stored overrides.
 */
@RestController
@RequestMapping("/v1/profiles/{name}/config")
public class ProfileConfigController {

  /**
   * Allowed keys per section, derived from the DaemonOverrides records (kebab-case).
   */
  private static final Map<String, Set<String>> WHITELIST = Map.of(
    "ingestion", kebabComponentNames(Ingestion.class),
    "retrieval", kebabComponentNames(Retrieval.class));

  private final MemoryStore store;
  private final EffectiveConfigResolver configResolver;

  public ProfileConfigController(MemoryStore store, EffectiveConfigResolver configResolver) {
    this.store = store;
    this.configResolver = configResolver;
  }

  /**
   * Replace the profile's overrides wholesale (creating the profile if needed) and return the
   * resulting effective config. An empty body clears the overrides.
   */
  @PutMapping
  public JsonNode put(@PathVariable String name, @RequestBody JsonNode body) {
    validateWhitelist(body);
    DaemonOverrides overrides = ConfigCodec.bind(body, DaemonOverrides.class);

    Profile profile = store.getOrCreateProfile(name);
    if (overrides.isEmpty()) {
      store.clearProfileConfig(profile.id());
    } else {
      store.putProfileConfig(profile.id(), ConfigCodec.toJson(overrides));
    }
    configResolver.invalidate(profile.id());

    return effective(profile.id());
  }

  /**
   * The effective config for the profile. An unknown profile resolves to the global config (no
   * profile row is created by reading).
   */
  @GetMapping
  public JsonNode get(@PathVariable String name) {
    return store.findProfile(name)
      .map(profile -> effective(profile.id()))
      .orElseGet(() -> ConfigCodec.toNode(toFullOverrides(configResolver.global())));
  }

  /**
   * Remove the profile's overrides; reading falls back to the global config. Idempotent.
   */
  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String name) {
    store.findProfile(name).ifPresent(profile -> {
      store.clearProfileConfig(profile.id());
      configResolver.invalidate(profile.id());
    });
  }

  /**
   * Effective config for the profile as a kebab-case node.
   */
  private JsonNode effective(String profileId) {
    return ConfigCodec.toNode(toFullOverrides(configResolver.resolve(profileId)));
  }

  /**
   * Render a ResolvedConfig as a fully-populated DaemonOverrides view (every field set).
   */
  private static DaemonOverrides toFullOverrides(ResolvedConfig resolved) {
    PieriaProperties.Ingestion ingestion = resolved.ingestion();
    PieriaProperties.Retrieval retrieval = resolved.retrieval();

    return new DaemonOverrides(
      new Ingestion(
        ingestion.chunkSizeChars(),
        ingestion.chunkOverlapMessages(),
        ingestion.maxExtractionConcurrency(),
        ingestion.detailPassMinMessages()),

      new Retrieval(
        retrieval.vectorEnabled(),
        retrieval.rrfK(),
        retrieval.weightExactKey(),
        retrieval.weightFtsMemory(),
        retrieval.weightHydeVector(),
        retrieval.weightDirectVector(),
        retrieval.weightFtsMessage(),
        retrieval.weightGraph(),
        retrieval.graphDepth(),
        retrieval.graphFanout(),
        retrieval.graphSeedLimit(),
        retrieval.channelLimit(),
        retrieval.channelTimeoutMs(),
        retrieval.weightSymbolFts(),
        retrieval.weightCodeGraph(),
        retrieval.codeGraphDepth(),
        retrieval.codeGraphFanout(),
        retrieval.codeGraphSeedLimit(),
        retrieval.codeGraphMinConfidence(),
        retrieval.recallMode()));
  }

  /**
   * Reject any key outside the {@link DaemonOverrides} shape. Mapped to 400 by the global handler.
   */
  private static void validateWhitelist(JsonNode body) {
    if (body == null || body.isNull()) {
      return;
    }

    if (!(body instanceof ObjectNode root)) {
      throw new IllegalArgumentException("config overrides must be a JSON object");
    }

    for (var section : root.properties()) {
      Set<String> allowed = WHITELIST.get(section.getKey());
      if (allowed == null) {
        throw new IllegalArgumentException(
          "unknown or non-overridable config section: '" + section.getKey() + "'");
      }

      if (!(section.getValue() instanceof ObjectNode sectionNode)) {
        throw new IllegalArgumentException("config section '" + section.getKey() + "' must be an object");
      }

      for (var entry : sectionNode.properties()) {
        if (!allowed.contains(entry.getKey())) {
          throw new IllegalArgumentException("unknown or non-overridable config key: '"
            + section.getKey() + "." + entry.getKey() + "'");
        }
      }
    }
  }

  /**
   * Record component names converted to kebab-case (matches the ConfigCodec naming strategy).
   */
  private static Set<String> kebabComponentNames(Class<? extends Record> type) {
    Set<String> names = new LinkedHashSet<>();
    for (RecordComponent component : type.getRecordComponents()) {
      names.add(toKebab(component.getName()));
    }
    return Set.copyOf(names);
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
