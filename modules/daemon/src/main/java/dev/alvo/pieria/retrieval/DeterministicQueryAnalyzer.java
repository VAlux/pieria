package dev.alvo.pieria.retrieval;


import dev.alvo.pieria.domain.QueryAnalysis;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure, network-free fallback query analyzer. Used when the model is
 * unavailable so the exact-key and FTS retrieval channels can still run ("degrades gracefully").
 * Has no {@code ModelGateway} dependency and never produces a HyDE statement (which needs a model).
 *
 * <h2>Deterministic behavior of {@link #analyze(String)}</h2>
 * <ol>
 *   <li>A null or blank query yields an empty analysis (empty lists, {@code null} HyDE).</li>
 *   <li>The query is lowercased and split on runs of non-alphanumeric characters.</li>
 *   <li>Tokens that are English stopwords (see {@link #STOPWORDS}) or shorter than
 *       {@link #MIN_TOKEN_LENGTH} characters are dropped.</li>
 *   <li>{@code ftsTerms} = the surviving tokens, de-duplicated, original order preserved.</li>
 *   <li>{@code topicKeys} are derived from the surviving tokens, de-duplicated, in this order:
 *       the full key (all tokens joined by '.'), then the first-token-only key, then each adjacent
 *       pair joined by '.'. With one surviving token the full key and first-token key coincide
 *       (de-duplicated to a single entry); with none, an empty list.</li>
 *   <li>{@code hydeStatement} is always {@code null} (no model).</li>
 * </ol>
 * Output depends only on the input string, so the same query always yields the same analysis.
 */
@Component
public class DeterministicQueryAnalyzer {

  /** Tokens shorter than this many characters are discarded. */
  static final int MIN_TOKEN_LENGTH = 2;

  /** Small English stopword set removed during tokenization. */
  static final Set<String> STOPWORDS = Set.of(
    "a", "an", "and", "are", "as", "at", "be", "but", "by", "do", "does", "for", "from",
    "how", "i", "in", "is", "it", "me", "my", "of", "on", "or", "tell", "that", "the",
    "to", "was", "were", "what", "when", "where", "which", "who", "why", "with", "you",
    "your", "about", "did", "had", "has", "have", "this", "these", "those");

  /**
   * Analyze a recall query deterministically. See the class javadoc for the exact contract.
   *
   * @param query the raw recall query (may be {@code null} or blank)
   * @return a {@link QueryAnalysis} with derived topic keys + FTS terms and a {@code null} HyDE
   */
  public QueryAnalysis analyze(String query) {
    if (query == null || query.isBlank()) {
      return new QueryAnalysis(List.of(), List.of(), null);
    }

    String[] rawTokens = query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
    List<String> tokens = new ArrayList<>();
    LinkedHashSet<String> seenTokens = new LinkedHashSet<>();
    for (String token : rawTokens) {
      if (token.length() < MIN_TOKEN_LENGTH || STOPWORDS.contains(token)) {
        continue;
      }
      if (seenTokens.add(token)) {
        tokens.add(token);
      }
    }

    if (tokens.isEmpty()) {
      return new QueryAnalysis(List.of(), List.of(), null);
    }

    List<String> ftsTerms = List.copyOf(tokens);

    List<String> topicKeys = new ArrayList<>();
    LinkedHashSet<String> seenKeys = new LinkedHashSet<>();
    addKey(topicKeys, seenKeys, String.join(".", tokens));
    addKey(topicKeys, seenKeys, tokens.getFirst());
    for (int i = 0; i + 1 < tokens.size(); i++) {
      addKey(topicKeys, seenKeys, tokens.get(i) + "." + tokens.get(i + 1));
    }

    return new QueryAnalysis(topicKeys, ftsTerms, null);
  }

  private static void addKey(List<String> keys, Set<String> seen, String key) {
    if (seen.add(key)) {
      keys.add(key);
    }
  }
}
