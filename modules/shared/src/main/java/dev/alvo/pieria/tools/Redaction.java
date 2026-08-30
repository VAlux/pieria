package dev.alvo.pieria.tools;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounds and scrubs untrusted tool output before it is stored, embedded, or sent to a model.
 *
 * <p>Lives in {@code shared} because two modules apply it: the CLI hook scrubs before anything
 * reaches the spool file, and the daemon scrubs again on receipt so a direct API caller gets the
 * same treatment. Running it twice is safe — {@link #scrub} is idempotent, and the second pass
 * reports zero hits.
 *
 * <p>Redaction is best-effort pattern matching, not a guarantee. The patterns below cover the
 * shapes that actually show up in build logs and shell history; they must be maintained.
 */
public final class Redaction {

  /** Fraction of the truncation budget given to the head; the rest goes to the tail. */
  private static final double HEAD_SHARE = 0.4;

  private static final String MASK = "[redacted]";

  /**
   * Secret shapes, each with exactly one capturing group holding the value to mask. Ordered
   * most-specific first so a PEM block is not partially eaten by a looser rule.
   */
  private static final List<Pattern> SECRETS = List.of(
    // PEM private key blocks, including the body across lines.
    Pattern.compile("(-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----)"),
    // Provider-issued tokens with recognizable prefixes.
    Pattern.compile("\\b(gh[pousr]_[A-Za-z0-9]{16,})"),
    Pattern.compile("\\b(sk-[A-Za-z0-9_-]{16,})"),
    Pattern.compile("\\b(xox[abprs]-[A-Za-z0-9-]{10,})"),
    Pattern.compile("\\b(AKIA[0-9A-Z]{16})\\b"),
    // Authorization headers.
    Pattern.compile("(?i)\\bBearer\\s+([A-Za-z0-9._~+/=-]{12,})"),
    // key=value / key: value assignments whose key names a credential.
    // I1: Allow snake_case/kebab-case (SECRET_KEY, my_password) and JSON quoted keys ("password").
    // I3: Prevent Bearer pattern from being matched as a credential value.
    // C1: Possessive quantifier *+ prevents catastrophic backtracking on long non-terminating suffixes.
    // Negative lookahead prevents re-matching [redacted] to ensure idempotence.
    Pattern.compile("(?i)(?<![a-zA-Z0-9])(?:api[_-]?key|secret|token|password|passwd|pwd|auth|credential)(?:[_-][a-zA-Z0-9]+)*+[\"']?"
      + "\\s*[:=]\\s*[\"']?(?!\\[redacted\\]|Bearer )([^\\s\"']{6,})[\"']?"));

  private Redaction() {
  }

  /**
   * Scrubbed text and how many secret matches were masked. The count exists so redaction activity
   * can be logged without logging any of the redacted content.
   */
  public record Redacted(String text, int hits) {
  }

  /**
   * Cap {@code text} at roughly {@code budget} characters, keeping the head and — with the larger
   * share — the tail, joined by an elision marker. The tail wins because a build log's last lines
   * are the failure; truncating head-only would discard exactly what makes the trace worth storing.
   */
  public static String truncate(String text, int budget) {
    if (text == null || budget <= 0 || text.length() <= budget) {
      return text;
    }
    int head = (int) (budget * HEAD_SHARE);
    int tail = budget - head;
    int elided = text.length() - head - tail;
    return text.substring(0, head)
      + "\n…[" + elided + " chars elided]…\n"
      + text.substring(text.length() - tail);
  }

  /** Mask every recognized secret in {@code text}, reporting how many were masked. */
  public static Redacted redactSecrets(String text) {
    if (text == null || text.isEmpty()) {
      return new Redacted(text, 0);
    }
    String current = text;
    int hits = 0;
    for (Pattern pattern : SECRETS) {
      Matcher matcher = pattern.matcher(current);
      StringBuilder out = new StringBuilder(current.length());
      while (matcher.find()) {
        hits++;
        // Replace only the captured value, preserving the surrounding key/prefix so the line still
        // reads as "API_KEY=[redacted]" rather than vanishing entirely.
        String whole = matcher.group();
        String value = matcher.group(1);
        int valueStart = whole.lastIndexOf(value);
        // I4: Preserve any trailing quotes or characters matched after the value.
        String beforeValue = whole.substring(0, valueStart);
        String afterValue = whole.substring(valueStart + value.length());
        String masked = beforeValue + MASK + afterValue;
        matcher.appendReplacement(out, Matcher.quoteReplacement(masked));
      }
      matcher.appendTail(out);
      current = out.toString();
    }
    return new Redacted(current, hits);
  }

  /**
   * Rewrite machine-specific absolute paths: {@code repoRoot} becomes {@code ./}, and the user's
   * home becomes {@code ~}. Longest prefix first, so a repo inside the home directory does not get
   * the weaker rewrite. Null roots are skipped.
   */
  public static String normalizePaths(String text, Path repoRoot, Path userHome) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String current = text;
    if (repoRoot != null) {
      current = current.replace(repoRoot.toAbsolutePath() + "/", "./");
      current = current.replace(repoRoot.toAbsolutePath().toString(), ".");
    }
    if (userHome != null) {
      current = current.replace(userHome.toAbsolutePath() + "/", "~/");
      current = current.replace(userHome.toAbsolutePath().toString(), "~");
    }
    return current;
  }

  /**
   * The full pipeline in the order that matters: truncate, then redact, then normalize paths.
   *
   * <p>Truncating first is deliberate. It bounds the regex work by the budget rather than by the
   * raw output size, which matters because this runs inside a {@code PostToolUse} hook on the
   * agent's critical path. A secret beyond the budget is discarded rather than scanned — it never
   * reaches disk either way.
   */
  public static Redacted scrub(String text, int budget, Path repoRoot, Path userHome) {
    Redacted redacted = redactSecrets(truncate(text, budget));
    return new Redacted(normalizePaths(redacted.text(), repoRoot, userHome), redacted.hits());
  }
}
