package dev.alvo.pieria.tools;

/**
 * Vector arithmetic over the raw {@code float[]} embeddings the store persists.
 *
 * <p>Exists because lexical overlap and semantic similarity answer different questions.
 * {@link TextSimilarity} compares word trigrams, so it recognises a <em>restatement</em> — the same
 * sentence rewritten — and is deliberately blind to word order changes. Two sentences that carry
 * one fact in wholly different words are <em>paraphrases</em>, score near zero there, and are only
 * detectable in embedding space.
 */
public final class Vectors {

  private Vectors() {
  }

  /**
   * Cosine similarity of two equal-length vectors, in {@code [-1.0, 1.0]}. Returns {@code 0.0} when
   * either side is null, empty, of a different length, or has zero magnitude — all cases where "how
   * similar" has no answer, and none of which should be reported as similar.
   */
  public static double cosine(float[] left, float[] right) {
    if (left == null || right == null || left.length == 0 || left.length != right.length) {
      return 0.0;
    }
    double dot = 0.0;
    double leftSquared = 0.0;
    double rightSquared = 0.0;
    for (int i = 0; i < left.length; i++) {
      dot += (double) left[i] * right[i];
      leftSquared += (double) left[i] * left[i];
      rightSquared += (double) right[i] * right[i];
    }
    if (leftSquared == 0.0 || rightSquared == 0.0) {
      return 0.0;
    }
    return dot / (Math.sqrt(leftSquared) * Math.sqrt(rightSquared));
  }
}
