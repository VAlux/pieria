package dev.alvo.pieria.domain;

import java.util.List;

/**
 * Classification + enrichment output for one verified candidate (SPEC 6.4): the assigned
 * {@link MemoryType}, a normalized {@code topicKey} for {@code fact}/{@code instruction} (else
 * {@code null}), 3-5 {@code interrogativeQueries} used to build {@code embed_text} (SPEC 8.1),
 * and the per-type {@code payload} JSON.
 *
 * @param type                 the assigned memory type
 * @param topicKey             normalized key for keyed types, or {@code null}
 * @param interrogativeQueries interrogative search queries prepended to content for embedding
 * @param payload              heterogeneous per-type fields as a JSON string (defaults to {@code "{}"})
 */
public record Classification(
  MemoryType type,
  String topicKey,
  List<String> interrogativeQueries,
  String payload) {
}
