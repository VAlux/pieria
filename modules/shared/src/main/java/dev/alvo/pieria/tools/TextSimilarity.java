package dev.alvo.pieria.tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic near-duplicate detection for short texts: word-trigram shingling plus Jaccard
 * overlap. No model call, no randomness — the same pair always scores the same.
 *
 * <p>Trigrams rather than bare tokens because word order carries meaning: two sentences built from
 * the same vocabulary in a different arrangement are not the same statement. Texts shorter than a
 * trigram degrade to their token set so they still compare sensibly.
 */
public final class TextSimilarity {

  private static final int SHINGLE = 3;

  private TextSimilarity() {
  }

  /**
   * Jaccard overlap of two texts' shingle sets, in {@code [0.0, 1.0]}. Two blank texts score
   * {@code 0.0} — nothing is not the same statement as nothing.
   */
  public static double similarity(String left, String right) {
    return jaccard(shingles(left), shingles(right));
  }

  /**
   * Jaccard overlap of two pre-computed shingle sets, for comparing one text against many.
   */
  public static double jaccard(Set<String> left, Set<String> right) {
    if (left.isEmpty() || right.isEmpty()) {
      return 0.0;
    }

    int intersection = 0;
    Set<String> smaller = left.size() <= right.size() ? left : right;
    Set<String> larger = smaller == left ? right : left;

    for (String shingle : smaller) {
      if (larger.contains(shingle)) {
        intersection++;
      }
    }

    int union = left.size() + right.size() - intersection;

    return (double) intersection / union;
  }

  /**
   * Word-trigram shingles of {@code text}, lower-cased and stripped of punctuation so that
   * formatting differences ("A; B" vs "A. B") do not register as content differences.
   */
  public static Set<String> shingles(String text) {
    List<String> tokens = tokenize(text);
    if (tokens.isEmpty()) {
      return Set.of();
    }
    if (tokens.size() < SHINGLE) {
      return new HashSet<>(tokens);
    }
    Set<String> shingles = new HashSet<>(tokens.size());
    for (int i = 0; i + SHINGLE <= tokens.size(); i++) {
      shingles.add(String.join(" ", tokens.subList(i, i + SHINGLE)));
    }
    return shingles;
  }

  private static List<String> tokenize(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    List<String> tokens = new ArrayList<>();
    for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
      if (!token.isBlank()) {
        tokens.add(token);
      }
    }
    return tokens;
  }
}
