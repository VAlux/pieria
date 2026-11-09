package dev.alvo.pieria.api.request;

import jakarta.validation.constraints.NotBlank;

/** Body of POST /v1/harvesters/inspect: an absolute path to a directory or .zip file. */
public record HarvesterPathRequest(@NotBlank String path) {
}
