package dev.alvo.pieria.api.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of POST /v1/harvesters.
 *
 * @param entrypointSha256 the value from inspect, so what is installed is what was shown; may be null
 * @param manifestSha256   the value from inspect; may be null
 * @param replace          whether to replace an installed harvester with the same id
 */
public record InstallHarvesterRequest(@NotBlank String path, String entrypointSha256, String manifestSha256, Boolean replace) {
}
