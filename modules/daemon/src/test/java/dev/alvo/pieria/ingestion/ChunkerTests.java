package dev.alvo.pieria.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.Chunk;
import dev.alvo.pieria.domain.Message;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkerTests {

  private final TranscriptNormalizer normalizer = new TranscriptNormalizer();

  private Chunker chunker(int chunkSizeChars, int overlapMessages) {
    PieriaProperties props = new PieriaProperties(
      new PieriaProperties.Daemon("127.0.0.1", 8077),
      new PieriaProperties.Db(":memory:"),
      new PieriaProperties.Provider("http://localhost:11434", "test-key", "test-provider", "openai", "2024-10-21"),
      new PieriaProperties.Model("small", "large", "embed", 1024),
      new PieriaProperties.Ingestion(chunkSizeChars, overlapMessages, 4, 9, 32, 5, false, 5000),
      null);
    return new Chunker(normalizer, props);
  }

  private Message msg(String role, String content) {
    return Message.of("s1", role, content);
  }

  @Test
  void smallConversationYieldsSingleChunk() {
    Chunker chunker = chunker(10_000, 2);
    List<Message> messages = List.of(
      msg("user", "hello"),
      msg("assistant", "hi"),
      msg("user", "bye"));

    List<Chunk> chunks = chunker.chunk(messages);

    assertThat(chunks).hasSize(1);
    Chunk c = chunks.get(0);
    assertThat(c.index()).isZero();
    assertThat(c.firstMessageIndex()).isZero();
    assertThat(c.lastMessageIndex()).isEqualTo(2);
    assertThat(c.messages()).hasSize(3);
    assertThat(c.transcript()).isEqualTo(normalizer.render(messages, 0));
  }

  @Test
  void emptyInputYieldsNoChunks() {
    assertThat(chunker(10_000, 2).chunk(List.of())).isEmpty();
  }

  @Test
  void splitsAtSizeTargetAlignedToMessageBoundaries() {
    // Each message ~100 chars; small target forces multiple chunks but never splits a message.
    Chunker chunker = chunker(150, 0);
    String body = "x".repeat(100);
    List<Message> messages = List.of(
      msg("user", body),
      msg("assistant", body),
      msg("user", body));

    List<Chunk> chunks = chunker.chunk(messages);

    // 150-char target only fits one ~100-char message at a time => one message per chunk.
    assertThat(chunks).hasSize(3);
    for (Chunk c : chunks) {
      assertThat(c.messages()).hasSize(1);
      assertThat(c.firstMessageIndex()).isEqualTo(c.lastMessageIndex());
    }
    assertThat(chunks).extracting(Chunk::index).containsExactly(0, 1, 2);
    assertThat(chunks).extracting(Chunk::firstMessageIndex).containsExactly(0, 1, 2);
  }

  @Test
  void twoMessageOverlapBetweenAdjacentChunks() {
    // ~50-char messages with a 200-char target => 3 messages per chunk, leaving room for a genuine
    // two-message overlap (overlap only manifests when a chunk holds more than `overlap` messages).
    Chunker chunker = chunker(200, 2);
    String body = "x".repeat(50);
    List<Message> messages = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      messages.add(msg("user", body + i));
    }

    List<Chunk> chunks = chunker.chunk(messages);

    // Adjacent chunks must share exactly two messages: the next chunk starts two before the
    // previous chunk's last (inclusive) index.
    assertThat(chunks.size()).isGreaterThan(1);
    for (int i = 1; i < chunks.size(); i++) {
      int prevLast = chunks.get(i - 1).lastMessageIndex();
      int curFirst = chunks.get(i).firstMessageIndex();
      assertThat(curFirst).isEqualTo(prevLast - 1); // 2-message overlap with inclusive bounds
    }
  }

  @Test
  void oversizedSingleMessageStillFormsOwnChunkNeverSplit() {
    Chunker chunker = chunker(50, 2);
    String huge = "y".repeat(500);
    List<Message> messages = List.of(msg("user", huge), msg("assistant", "ok"));

    List<Chunk> chunks = chunker.chunk(messages);

    // First chunk contains the whole oversized message intact (not split).
    assertThat(chunks.get(0).messages()).hasSize(1);
    assertThat(chunks.get(0).messages().get(0).content()).isEqualTo(huge);
  }

  @Test
  void transcriptUsesAbsoluteIndicesForProvenance() {
    Chunker chunker = chunker(150, 2);
    String body = "x".repeat(100);
    List<Message> messages = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      messages.add(msg("user", body + i));
    }

    List<Chunk> chunks = chunker.chunk(messages);

    for (Chunk c : chunks) {
      // The transcript must begin with the chunk's absolute first-message index.
      assertThat(c.transcript()).startsWith("[" + c.firstMessageIndex() + "] ");
    }
  }
}
