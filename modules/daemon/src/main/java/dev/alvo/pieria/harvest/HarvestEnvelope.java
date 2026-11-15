package dev.alvo.pieria.harvest;

import dev.alvo.pieria.harvester.manifest.ConfigFieldSpec;
import dev.alvo.pieria.harvester.manifest.FieldKind;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** What every harvester command is given on stdin, and how the secrets it touches are kept and hidden. */
final class HarvestEnvelope {

  private static final Logger log = LoggerFactory.getLogger(HarvestEnvelope.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HarvestEnvelope() {
  }

  /** The source's stored config or cursor can't be sent. */
  static final class InvalidSource extends Exception {
    InvalidSource(String message) {
      super(message);
    }
  }

  static Set<String> declaredSecrets(RegisteredHarvester harvester) {
    return harvester.manifest().config().stream()
      .filter(field -> field.kind() == FieldKind.SECRET)
      .map(ConfigFieldSpec::key)
      .collect(Collectors.toSet());
  }

  /**
   * Builds the stdin envelope with only the declared secrets, noting every stored value for scrubbing,
   * and what was sent in {@code sent}, so a later update can tell whether it is still the newest.
   */
  static HarvestRequest build(HarvestSecretStore secretStore, HarvestSource source, Set<String> declared, Set<String> secretValues,
                              Map<String, String> sent) throws InvalidSource {
    Map<String, String> secrets = new LinkedHashMap<>(secretStore.secrets(source.id()));
    secretValues.addAll(secrets.values());
    secrets.keySet().retainAll(declared);
    sent.putAll(secrets);
    try {
      if (!(MAPPER.readTree(source.configJson()) instanceof ObjectNode config)) {
        throw new InvalidSource("the source config is not a JSON object");
      }
      JsonNode cursor = source.cursorJson() == null ? null : MAPPER.readTree(source.cursorJson());
      return new HarvestRequest(HarvestRequest.PROTOCOL_VERSION, config, secrets, cursor,
        new HarvestRequest.Limits(source.maxItemsPerRun(), source.maxCharsPerRun()));
    } catch (JacksonException e) {
      throw new InvalidSource("the source config or cursor is not valid JSON");
    }
  }

  /**
   * Saves a {@code secret_update}. With {@code sent}, it replaces the stored value only if it is still
   * the one this process was given (or last saved), so an older process can't undo a newer sign-in.
   * Without it, as for a sign-in, the update always wins. Returns whether it was saved.
   */
  static boolean saveSecret(HarvestSecretStore secretStore, HarvestSource source, Set<String> declared, Set<String> secretValues,
                            Map<String, String> sent, HarvestRecord.SecretUpdate update) {
    secretValues.add(update.value());
    if (!declared.contains(update.name())) {
      log.warn("harvest source={} sent an update for undeclared secret {}; ignoring", source.id(), update.name());
      return false;
    }
    if (sent == null) {
      secretStore.put(source.id(), update.name(), update.value());
      return true;
    }
    if (secretStore.replaceIf(source.id(), update.name(), sent.get(update.name()), update.value())) {
      sent.put(update.name(), update.value());
      return true;
    }
    log.warn("harvest source={} rotated secret {}, but a newer value was saved meanwhile; keeping that", source.id(), update.name());
    return false;
  }

  static String scrub(String text, Set<String> secretValues) {
    if (text == null) {
      return null;
    }
    String scrubbed = text;
    for (String secret : secretValues) {
      if (!secret.isBlank()) {
        scrubbed = scrubbed.replace(secret, "***");
      }
    }
    return scrubbed;
  }
}
