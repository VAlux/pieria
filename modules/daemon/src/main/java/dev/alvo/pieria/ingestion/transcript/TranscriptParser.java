package dev.alvo.pieria.ingestion.transcript;

import dev.alvo.pieria.domain.memory.Message;
import java.util.List;

/**
 * Parses a harness-specific session transcript into the ordered conversation {@link Message}s the
 * ingestion pipeline consumes.
 *
 * <p>Pieria is a cross-agentic memory layer: Claude Code, Codex, OpenCode and other harnesses each
 * persist their transcripts in a different shape. This interface is the seam that keeps that
 * harness-specific knowledge out of the ingestion pipeline — each harness provides one concrete
 * implementation, and {@link TranscriptParserRegistry} dispatches to the right one by
 * {@link #harness()}. Adding a new harness is a new implementation, not a change to the pipeline or
 * the REST surface.
 *
 * <p>Implementations must be lenient: skip non-conversation events and lines they cannot parse
 * rather than throwing, so a single malformed record can never fail an entire ingest.
 */
public interface TranscriptParser {

  /** Stable harness identifier this parser handles, e.g. {@code "claude-code"} or {@code "codex"}. */
  String harness();

  /**
   * Parse the raw transcript into conversation messages tagged with the given session id.
   *
   * @param transcript raw transcript body as emitted by the harness (may be null/blank)
   * @param sessionId  session id to stamp on every extracted message
   * @return messages in source order; empty if nothing usable was found
   */
  List<Message> parse(String transcript, String sessionId);
}
