package dev.alvo.pieria.ingestion;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic verification pre-filter: decides whether an extracted candidate is so plainly
 * grounded in its source transcript that the model verifier can be skipped. Candidates that fail
 * this check are "suspect" and go to the batched model verify; candidates that pass are stored
 * as-is. This is what keeps the verify stage from re-sending the full chunk transcript for the
 * (typical) majority of well-grounded candidates.
 *
 * <p>Two rules, both over lowercased text:
 * <ul>
 *   <li><b>Critical tokens</b> — tokens containing a digit or a {@code /} (versions, prices,
 *       dates, paths, URLs): every one must appear verbatim as a substring of the transcript.
 *       A single miss fails the check, because fabricated concrete values are exactly what
 *       verification exists to catch.</li>
 *   <li><b>Word overlap</b> — of the remaining word tokens of length ≥ {@value #MIN_WORD_LENGTH},
 *       at least {@value #MIN_WORD_OVERLAP} (as a fraction) must appear in the transcript's token
 *       set. Below that the candidate is too paraphrased/inventive to trust without the model.</li>
 * </ul>
 * Degenerate candidates (blank, or nothing but short filler words) are never grounded — they go
 * to the model verifier, which is the component with the judgment to drop or correct them.
 */
public final class GroundingFilter {

  static final int MIN_WORD_LENGTH = 4;
  static final double MIN_WORD_OVERLAP = 0.6;

  private static final Pattern TOKEN = Pattern.compile("[a-z0-9./_-]+");

  private GroundingFilter() {
  }

  /**
   * Whether {@code candidateContent} is plainly grounded in {@code transcript} (see class doc).
   */
  public static boolean grounded(String candidateContent, String transcript) {
    if (candidateContent == null || candidateContent.isBlank()
      || transcript == null || transcript.isBlank()) {
      return false;
    }

    String content = candidateContent.toLowerCase(Locale.ROOT);
    String source = transcript.toLowerCase(Locale.ROOT);

    Set<String> sourceTokens = tokenize(source);
    int words = 0;
    int wordsFound = 0;

    Matcher matcher = TOKEN.matcher(content);
    while (matcher.find()) {
      String token = matcher.group();
      if (isCritical(token)) {
        if (!source.contains(token)) {
          return false;
        }
      } else if (token.length() >= MIN_WORD_LENGTH) {
        words++;
        if (sourceTokens.contains(token)) {
          wordsFound++;
        }
      }
    }
    if (words == 0) {
      // Nothing but filler/short tokens: not enough signal to auto-pass.
      return false;
    }
    return (double) wordsFound / words >= MIN_WORD_OVERLAP;
  }

  /** Tokens carrying concrete values: anything with a digit or a path separator. */
  private static boolean isCritical(String token) {
    if (token.indexOf('/') >= 0) {
      return true;
    }
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      if (c >= '0' && c <= '9') {
        return true;
      }
    }
    return false;
  }

  private static Set<String> tokenize(String text) {
    Set<String> tokens = new HashSet<>();
    Matcher matcher = TOKEN.matcher(text);
    while (matcher.find()) {
      tokens.add(matcher.group());
    }
    return tokens;
  }
}
