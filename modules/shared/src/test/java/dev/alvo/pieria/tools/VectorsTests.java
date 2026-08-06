package dev.alvo.pieria.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class VectorsTests {

  @Test
  void identicalDirectionScoresOneRegardlessOfMagnitude() {
    assertThat(Vectors.cosine(new float[] {1, 2, 3}, new float[] {1, 2, 3})).isCloseTo(1.0, within(1e-9));
    assertThat(Vectors.cosine(new float[] {1, 2, 3}, new float[] {10, 20, 30})).isCloseTo(1.0, within(1e-9));
  }

  @Test
  void orthogonalVectorsScoreZeroAndOppositeOnesScoreMinusOne() {
    assertThat(Vectors.cosine(new float[] {1, 0}, new float[] {0, 1})).isCloseTo(0.0, within(1e-9));
    assertThat(Vectors.cosine(new float[] {1, 0}, new float[] {-1, 0})).isCloseTo(-1.0, within(1e-9));
  }

  @Test
  void incomparableInputsScoreZeroRatherThanThrowing() {
    assertThat(Vectors.cosine(null, new float[] {1, 0})).isZero();
    assertThat(Vectors.cosine(new float[] {1, 0}, null)).isZero();
    assertThat(Vectors.cosine(new float[] {}, new float[] {})).isZero();
    assertThat(Vectors.cosine(new float[] {1, 0}, new float[] {1, 0, 0})).isZero();
    assertThat(Vectors.cosine(new float[] {0, 0}, new float[] {1, 0})).isZero();
  }
}
