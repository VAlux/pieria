package dev.alvo.pieria.cli.modules.hook;

import dev.alvo.pieria.api.request.RememberRequest;
import dev.alvo.pieria.api.response.MemoryResponse;

import java.util.List;
import java.util.Locale;

/**
 * Stores one memory explicitly, backing the {@code /pieria-remember} slash command.
 *
 * <p>Unlike the background hooks this is an explicit user action, so a failure is reported on
 * stdout — the user must know the memory did not persist. The command still exits 0.
 *
 * <p>The input carries an optional {@code key:<topic-key>} marker so this deterministic path can
 * write keyed memories. Without it every pin is unkeyed and accumulates instead of superseding,
 * which is exactly what {@code topicKey} exists to prevent for facts whose value changes.
 */
public final class MemoryPinner {

  private static final String USAGE =
    "usage: /pieria-remember [fact:|instruction:|event:|task:] [key:<topic-key>] <content>";
  private static final List<String> TYPES = List.of("fact", "instruction", "event", "task");
  private static final String KEY_PREFIX = "key:";

  /** A raw input split into its memory type, optional topic key, and content. */
  public record Parsed(String type, String topicKey, String content) {
  }

  private MemoryPinner() {
  }

  /**
   * Split the optional leading {@code <type>:} and {@code key:<topic-key>} markers off the content.
   * Both are optional and may appear in either order; parsing stops at the first token that is
   * neither, so a colon later in the sentence stays in the content and yields a {@code fact}.
   *
   * <p>{@code key:} only counts as a marker when a non-blank token follows it immediately, which
   * keeps prose like {@code "key: value pairs are cheap"} as plain content.
   */
  public static Parsed parse(String raw) {
    String rest = raw == null ? "" : raw.strip();
    String type = null;
    String topicKey = null;

    for (int marker = 0; marker < 2; marker++) {
      String candidateType = type == null ? matchedType(rest) : null;
      if (candidateType != null) {
        type = candidateType;
        rest = afterPrefix(rest, candidateType.length() + 1);
        continue;
      }
      String candidateKey = topicKey == null ? matchedKey(rest) : null;
      if (candidateKey != null) {
        topicKey = candidateKey;
        rest = afterPrefix(rest, KEY_PREFIX.length() + candidateKey.length());
        continue;
      }
      break;
    }

    return new Parsed(type == null ? "fact" : type, topicKey, rest);
  }

  /** The memory type named by a leading {@code <type>:} token, or null if there is none. */
  private static String matchedType(String value) {
    int colon = value.indexOf(':');
    if (colon <= 0) {
      return null;
    }
    String candidate = value.substring(0, colon).toLowerCase(Locale.ROOT);
    return TYPES.contains(candidate) ? candidate : null;
  }

  /** The topic key named by a leading {@code key:<token>}, or null if there is none. */
  private static String matchedKey(String value) {
    if (!value.regionMatches(true, 0, KEY_PREFIX, 0, KEY_PREFIX.length())) {
      return null;
    }
    String token = value.substring(KEY_PREFIX.length());
    for (int i = 0; i < token.length(); i++) {
      if (Character.isWhitespace(token.charAt(i))) {
        return i == 0 ? null : token.substring(0, i);
      }
    }
    return token.isEmpty() ? null : token;
  }

  /** Drop a consumed marker, plus the single space that separates it from what follows. */
  private static String afterPrefix(String value, int consumed) {
    String remainder = value.substring(consumed);
    return remainder.startsWith(" ") ? remainder.substring(1) : remainder;
  }

  public static HookOutcome pin(HookContext ctx, String raw) {
    Parsed parsed = parse(raw);
    if (parsed.content().isBlank()) {
      return new HookOutcome.Skipped(USAGE);
    }
    if (!ctx.health().reachable()) {
      return new HookOutcome.Failed(
        "daemon not reachable at " + ctx.daemonUrl() + " — memory NOT stored.");
    }
    try {
      MemoryResponse stored = ctx.profiles().remember(ctx.profile(),
        new RememberRequest(parsed.type(), parsed.content(), null, parsed.topicKey(), null));
      String keyed = parsed.topicKey() == null ? "" : ", key \"" + parsed.topicKey() + "\"";
      return new HookOutcome.Ok("Pieria remembered (%s%s) in profile \"%s\": %s"
        .formatted(stored.type(), keyed, ctx.profile(), parsed.content()));
    } catch (RuntimeException e) {
      return new HookOutcome.Failed("memory NOT stored: " + e.getMessage());
    }
  }
}
