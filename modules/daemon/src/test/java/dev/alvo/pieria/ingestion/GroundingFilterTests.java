package dev.alvo.pieria.ingestion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the deterministic verification pre-filter: candidates whose words (and, strictly,
 * whose concrete values) appear in the transcript are grounded; paraphrased or fabricated ones are
 * suspect and must go to the model verifier.
 */
class GroundingFilterTests {

  private static final String TRANSCRIPT =
    "[0] user: The service uses Postgres 16.2 as its database, configured in config/db.yaml. "
      + "Deployments happen every Friday.";

  @Test
  void candidateRestatingTranscriptWordsIsGrounded() {
    assertThat(GroundingFilter.grounded(
      "The service uses Postgres as its database", TRANSCRIPT)).isTrue();
  }

  @Test
  void exactValuesFromTranscriptAreGrounded() {
    assertThat(GroundingFilter.grounded(
      "Postgres 16.2 is configured in config/db.yaml", TRANSCRIPT)).isTrue();
  }

  @Test
  void fabricatedVersionNumberFailsGrounding() {
    // Every digit-bearing token must appear verbatim; 17.0 does not.
    assertThat(GroundingFilter.grounded(
      "The service uses Postgres 17.0 as its database", TRANSCRIPT)).isFalse();
  }

  @Test
  void fabricatedPathFailsGrounding() {
    assertThat(GroundingFilter.grounded(
      "The database is configured in config/database.yml", TRANSCRIPT)).isFalse();
  }

  @Test
  void heavilyParaphrasedCandidateFailsWordOverlap() {
    assertThat(GroundingFilter.grounded(
      "Releases ship weekly according to schedule", TRANSCRIPT)).isFalse();
  }

  @Test
  void degenerateCandidatesAreNeverGrounded() {
    assertThat(GroundingFilter.grounded(null, TRANSCRIPT)).isFalse();
    assertThat(GroundingFilter.grounded("   ", TRANSCRIPT)).isFalse();
    assertThat(GroundingFilter.grounded("a an it", TRANSCRIPT)).isFalse(); // only short filler words
    assertThat(GroundingFilter.grounded("anything", null)).isFalse();
    assertThat(GroundingFilter.grounded("anything", " ")).isFalse();
  }

  @Test
  void groundingIsCaseInsensitive() {
    assertThat(GroundingFilter.grounded(
      "the SERVICE uses POSTGRES as its DATABASE", TRANSCRIPT)).isTrue();
  }
}
