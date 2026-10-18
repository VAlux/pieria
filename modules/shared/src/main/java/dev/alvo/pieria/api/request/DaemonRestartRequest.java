package dev.alvo.pieria.api.request;

/**
 * Body of POST /v1/daemon/restart. {@code force} restarts even while background tasks run (they
 * are interrupted). The body is required so browsers preflight the call.
 */
public record DaemonRestartRequest(Boolean force) {
}
