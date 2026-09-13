package dev.alvo.pieria.api.request;

import jakarta.validation.constraints.NotBlank;

/** Null correction fields retain their previous values; invalidateAt truncates asserted validity. */
public record RevisionRequest(@NotBlank String assertionId, @NotBlank String expectedRevisionId,
                              String content, String validFrom, String validTo, String invalidateAt, Boolean openEnded) {
  public RevisionRequest(String assertionId, String expectedRevisionId, String content, String validFrom, String validTo, String invalidateAt) {
    this(assertionId, expectedRevisionId, content, validFrom, validTo, invalidateAt, false);
  }
}
