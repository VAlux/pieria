package dev.alvo.pieria.ingestion.model;

import dev.alvo.pieria.domain.memory.Message;

import java.util.List;

/**
 * A contiguous slice of a normalized conversation produced by the chunker. Each chunk
 * carries the source messages it spans (for provenance) plus a pre-rendered, role-labeled
 * {@code transcript} with line indices and absolute dates, ready to hand to the extraction model.
 *
 * @param index             zero-based position of this chunk in the chunk sequence
 * @param firstMessageIndex index (into the normalized message list) of the first message in this chunk
 * @param lastMessageIndex  index of the last message in this chunk (inclusive)
 * @param messages          the source messages this chunk spans, in order
 * @param transcript        role-labeled transcript with line indices, ready for the model
 */
public record Chunk(
  int index,
  int firstMessageIndex,
  int lastMessageIndex,
  List<Message> messages,
  String transcript) {
}
