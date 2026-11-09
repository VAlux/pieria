package dev.alvo.pieria.api.request;

import jakarta.validation.constraints.NotEmpty;

import java.util.Map;

/** Body of PUT /v1/profiles/{name}/sources/{id}/secrets. */
public record SourceSecretsRequest(@NotEmpty Map<String, String> secrets) {

  @Override
  public String toString() {
    return "SourceSecretsRequest[names=" + (secrets == null ? "[]" : secrets.keySet()) + "]";
  }
}
