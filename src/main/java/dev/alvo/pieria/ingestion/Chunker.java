package dev.alvo.pieria.ingestion;

import org.springframework.context.annotation.Profile;

import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.Chunk;
import dev.alvo.pieria.domain.Message;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Splits a normalized message list into message-boundary-aligned {@link Chunk}s around a
 * configurable character target (SPEC 6.2, phase-2 step 3).
 *
 * <p>Rules:
 * <ul>
 *   <li>Never splits a message: a message is the atomic unit, so a single oversized message still
 *       forms its own chunk.</li>
 *   <li>Targets {@code pieria.ingestion.chunk-size-chars} characters per chunk (measured over the
 *       rendered transcript). A chunk is closed once adding the next message would exceed the
 *       target, unless the chunk is still empty.</li>
 *   <li>Adjacent chunks overlap by {@code pieria.ingestion.chunk-overlap-messages} messages so
 *       context spanning a boundary is not lost.</li>
 *   <li>A short conversation that fits the target yields exactly one chunk.</li>
 * </ul>
 *
 * <p>Each chunk's {@code transcript} is rendered via {@link TranscriptNormalizer#render} using the
 * absolute message index, keeping provenance line indices consistent across chunks.
 */
@Component
@Profile("!shim")
public class Chunker {

  private final TranscriptNormalizer normalizer;
  private final int targetChars;
  private final int overlapMessages;

  public Chunker(TranscriptNormalizer normalizer, PieriaProperties properties) {
    this.normalizer = normalizer;
    PieriaProperties.Ingestion ingestion = properties.ingestion();
    this.targetChars = Math.max(1, ingestion.chunkSizeChars());
    this.overlapMessages = Math.max(0, ingestion.chunkOverlapMessages());
  }

  /**
   * Chunk the given (already normalized) messages. Returns an empty list for empty input.
   */
  public List<Chunk> chunk(List<Message> messages) {
    List<Chunk> chunks = new ArrayList<>();
    if (messages == null || messages.isEmpty()) {
      return chunks;
    }

    int n = messages.size();
    int start = 0;
    int chunkIndex = 0;

    while (start < n) {
      int end = start; // inclusive end index, advances as messages fit
      int size = 0;
      // Always include at least one message (never split a message, even if oversized).
      while (end < n) {
        int cost = messageCost(messages.get(end), end == start);
        if (end > start && size + cost > targetChars) {
          break;
        }
        size += cost;
        end++;
      }
      int lastInclusive = end - 1;

      List<Message> span = List.copyOf(messages.subList(start, end));
      String transcript = normalizer.render(span, start);
      chunks.add(new Chunk(chunkIndex++, start, lastInclusive, span, transcript));

      if (end >= n) {
        break;
      }
      // Next chunk starts `overlapMessages` before the next unprocessed message, but must make
      // forward progress (never re-emit an identical span).
      int next = end - overlapMessages;
      if (next <= start) {
        next = start + 1;
      }
      start = next;
    }

    return chunks;
  }

  /**
   * Approximate rendered cost of a message: the content plus the role label and line-index
   * decoration {@link TranscriptNormalizer#render} adds.
   */
  private int messageCost(Message m, boolean first) {
    int role = m.role() == null ? 0 : m.role().length();
    int content = m.content() == null ? 0 : m.content().length();
    // "[i] role: content" plus the joining newline for non-first lines.
    return role + content + 8 + (first ? 0 : 1);
  }
}
