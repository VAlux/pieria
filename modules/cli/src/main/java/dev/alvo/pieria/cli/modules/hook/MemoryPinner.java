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
 */
public final class MemoryPinner {

  private static final String USAGE = "usage: /pieria-remember [fact:|instruction:|event:|task:] <content>";
  private static final List<String> TYPES = List.of("fact", "instruction", "event", "task");

  /** A raw input split into its memory type and content. */
  public record Parsed(String type, String content) {
  }

  private MemoryPinner() {
  }

  /**
   * Split an optional leading {@code <type>:} token off the content, dropping a single space after
   * the colon. Anything else — including a colon later in the sentence — is a {@code fact}.
   */
  public static Parsed parse(String raw) {
    String trimmed = raw == null ? "" : raw.strip();
    int colon = trimmed.indexOf(':');
    if (colon > 0) {
      String candidate = trimmed.substring(0, colon).toLowerCase(Locale.ROOT);
      if (TYPES.contains(candidate)) {
        String content = trimmed.substring(colon + 1);
        return new Parsed(candidate, content.startsWith(" ") ? content.substring(1) : content);
      }
    }
    return new Parsed("fact", trimmed);
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
      MemoryResponse stored = ctx.profiles()
        .remember(ctx.profile(), new RememberRequest(parsed.type(), parsed.content(), null, null, null));
      return new HookOutcome.Ok("Pieria remembered (%s) in profile \"%s\": %s"
        .formatted(stored.type(), ctx.profile(), parsed.content()));
    } catch (RuntimeException e) {
      return new HookOutcome.Failed("memory NOT stored: " + e.getMessage());
    }
  }
}
