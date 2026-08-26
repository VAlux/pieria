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
