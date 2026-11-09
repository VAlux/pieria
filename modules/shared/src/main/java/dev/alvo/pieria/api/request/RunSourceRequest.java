package dev.alvo.pieria.api.request;

/** Body of POST /v1/profiles/{name}/sources/{id}/run. */
public record RunSourceRequest(Boolean dryRun) {
}
