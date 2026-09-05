package dev.alvo.pieria.retrieval.model;

import java.util.Locale;

/**
 * How relevant one retrieval candidate is to the query, as judged by the small/fast model tier.
 *
 * <p>Deliberately a three-way label rather than a numeric score: the small tier is a local ~4-8B
 * model, which clusters numeric relevance judgements so tightly (nearly everything lands on 7 or 8)
 * that the resulting order is noise. A coarse label is a judgement that model class makes reliably,
 * and it modulates the channel evidence RRF earned rather than replacing it.
 */
public enum RerankLabel {

  /** Directly answers the query; ranked above everything merely related. */
  ESSENTIAL,

  /** Plausibly useful context. The neutral verdict, and the one every parse failure degrades to. */
  RELATED,

  /** Does not bear on the query; dropped, unless every candidate said the same (see ModelReranker). */
  IRRELEVANT;

  /**
   * Parse one model-emitted token. Forgiving in exactly one direction: anything unrecognised — a
   * typo, a number, a null, an empty line — becomes {@link #RELATED}, so a garbled response
   * reorders nothing and drops nothing rather than dropping the wrong thing.
   */
  public static RerankLabel fromWire(String token) {
    if (token == null) {
      return RELATED;
    }
    return switch (token.strip().toLowerCase(Locale.ROOT)) {
      case "essential" -> ESSENTIAL;
      case "irrelevant" -> IRRELEVANT;
      default -> RELATED;
    };
  }
}
