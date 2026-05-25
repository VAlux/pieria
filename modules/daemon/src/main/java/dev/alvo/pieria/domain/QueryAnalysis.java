package dev.alvo.pieria.domain;

import java.util.List;

/**
 * Output of recall query analysis. The analyzer turns a raw recall
 * query into the inputs the retrieval channels need:
 * <ul>
 *   <li>{@code topicKeys} — ranked normalized keys for the exact fact-key channel.</li>
 *   <li>{@code ftsTerms} — keyword/synonym terms for the FTS channels (already expanded).</li>
 *   <li>{@code hydeStatement} — a hypothetical declarative answer for the HyDE vector channel.</li>
 * </ul>
 * Produced either by the model ({@code ModelGateway.analyzeQuery}) or, when the model is
 * unavailable, by a deterministic fallback so exact/FTS lookup can still run.
 *
 * @param topicKeys     ranked candidate topic keys (may be empty)
 * @param ftsTerms      FTS keyword terms including synonyms (may be empty)
 * @param hydeStatement hypothetical declarative answer, or {@code null} when not generated
 */
public record QueryAnalysis(
  List<String> topicKeys,
  List<String> ftsTerms,
  String hydeStatement) {

  public QueryAnalysis {
    topicKeys = topicKeys == null ? List.of() : List.copyOf(topicKeys);
    ftsTerms = ftsTerms == null ? List.of() : List.copyOf(ftsTerms);
  }
}
