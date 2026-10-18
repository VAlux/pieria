package dev.alvo.pieria.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds SQLite FTS5 {@code MATCH} expressions from free text a person typed into a search box.
 * Every token is double-quoted, so punctuation and FTS operators in the input can never raise a
 * syntax error.
 */
public final class FtsQuery {

  private FtsQuery() {
  }

  /**
   * Every word must appear, each as a prefix ({@code "web"* AND "cons"*}), which is what a
   * search-as-you-type box expects. Returns {@code null} when the text has no usable token.
   */
  public static String allPrefixTerms(String text) {
    if (text == null) {
      return null;
    }
    List<String> terms = new ArrayList<>();
    for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_-]+")) {
      if (!token.isBlank()) {
        terms.add("\"" + token.replace("\"", "\"\"") + "\"*");
      }
    }
    return terms.isEmpty() ? null : String.join(" AND ", terms);
  }
}
