package dev.alvo.pieria.api.response;

/**
 * Response to an async submit (e.g. POST /v1/profiles/{name}/ingest/async): the id the client polls
 * at {@code GET /v1/tasks/{taskId}} for progress.
 */
public record TaskSubmitResponse(String taskId) {
}
