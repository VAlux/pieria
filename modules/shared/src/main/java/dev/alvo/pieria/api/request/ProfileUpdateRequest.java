package dev.alvo.pieria.api.request;

/**
 * Body of PATCH /v1/profiles/{name}.
 *
 * @param parent the profile to move this one under, or {@code null} to move it to top level
 */
public record ProfileUpdateRequest(String parent) {
}
