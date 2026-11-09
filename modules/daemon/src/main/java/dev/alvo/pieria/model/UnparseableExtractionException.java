package dev.alvo.pieria.model;

/**
 * The extraction model's output for a chunk could not be read. Distinct from a chunk that genuinely
 * has nothing worth remembering, which matters when an empty result would retire existing memories.
 */
public final class UnparseableExtractionException extends RuntimeException {

  public UnparseableExtractionException(int chunkIndex) {
    super("the model returned unreadable output for chunk " + chunkIndex);
  }
}
