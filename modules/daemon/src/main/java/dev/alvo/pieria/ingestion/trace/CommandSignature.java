package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.tools.Hash;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The normalized grouping key behind both trace topic keys: {@code trace:outcome:<signature>} and
 * {@code trace:recipe:<signature>}.
 *
 * <p>Its job is <em>grouping</em>, so that a second run of the same command supersedes the first.
 * That is the opposite of an identifier's job, which is why {@code ContentId.forTrace} hashes the
 * full redacted args instead: collapsing {@code ./gradlew test} and
 * {@code ./gradlew test --rerun-tasks} into one signature is correct, and collapsing them into one
 * id would silently drop the second trace.
 */
public final class CommandSignature {

  /** How many meaningful tokens survive into the key. */
  private static final int MAX_TOKENS = 4;

  /** How much of the error tail feeds the digest. */
  private static final int DIGEST_TAIL_CHARS = 512;

  private static final Pattern NUMERIC = Pattern.compile("\\d+");
  private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9_]+");
  private static final Pattern LINE_COLUMN = Pattern.compile(":\\d+(?::\\d+)?\\b");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private CommandSignature() {
  }

  /**
   * Normalized key for a tool invocation. {@code Bash} args already name the program, so they stand
   * alone; every other tool is prefixed with its own name, or an {@code Edit} and a {@code Write}
   * of the same file would share a key.
   */
  public static String of(String tool, String args) {
    String toolSlug = slug(tool == null ? "" : tool);
    List<String> tokens = meaningfulTokens(args);
    if (tokens.isEmpty()) {
      return toolSlug.isEmpty() ? "unknown" : toolSlug;
    }
    String body = String.join("-", tokens);
    return "bash".equals(toolSlug) ? body : (toolSlug.isEmpty() ? body : toolSlug + "-" + body);
  }

  /**
   * A stable fingerprint of how a command failed, used to tell "the same failure again" from "a
   * different failure". Only the tail is digested (that is where the error lands), line and column
   * numbers are masked (a recompile shifts them without changing what broke), and an empty error
   * yields a fixed sentinel so success-to-success comparison rests on status alone.
   */
  public static String errorDigest(String errorOrOutput) {
    if (errorOrOutput == null || errorOrOutput.isBlank()) {
      return "none";
    }
    String text = errorOrOutput.strip();
    if (text.length() > DIGEST_TAIL_CHARS) {
      text = text.substring(text.length() - DIGEST_TAIL_CHARS);
    }
    String masked = LINE_COLUMN.matcher(text).replaceAll(":#");
    String collapsed = WHITESPACE.matcher(masked).replaceAll(" ").strip();
    return Hash.hash128(collapsed.toLowerCase(Locale.ROOT));
  }

  /**
   * Lowercase args, drop the leading {@code ./}, discard flag tokens and bare numbers,
   * then keep the first {@value #MAX_TOKENS} tokens.
   */
  private static List<String> meaningfulTokens(String args) {
    if (args == null || args.isBlank()) {
      return List.of();
    }
    String[] raw = WHITESPACE.split(args.strip().toLowerCase(Locale.ROOT));
    List<String> tokens = new ArrayList<>();
    for (String token : raw) {
      if (token.isEmpty()) {
        continue;
      }
      if (token.startsWith("-")) {
        // Skip the flag token itself, but keep its value.
        continue;
      }
      if (NUMERIC.matcher(token).matches()) {
        continue;
      }
      String cleaned = slug(stripLeadingDotSlash(token));
      if (cleaned.isEmpty()) {
        continue;
      }
      tokens.add(cleaned);
      if (tokens.size() == MAX_TOKENS) {
        break;
      }
    }
    return tokens;
  }

  private static String stripLeadingDotSlash(String token) {
    return token.startsWith("./") ? token.substring(2) : token;
  }

  /** Lowercase, replace every run of non-alphanumerics with a single dash, trim dashes. */
  private static String slug(String raw) {
    String lower = raw.toLowerCase(Locale.ROOT);
    String dashed = NON_SLUG.matcher(lower).replaceAll("-");
    int start = 0;
    int end = dashed.length();
    while (start < end && dashed.charAt(start) == '-') {
      start++;
    }
    while (end > start && dashed.charAt(end - 1) == '-') {
      end--;
    }
    return dashed.substring(start, end);
  }
}
