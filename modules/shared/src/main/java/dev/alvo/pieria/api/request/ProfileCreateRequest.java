package dev.alvo.pieria.api.request;

/**
 * Optional body of PUT /v1/profiles/{name}.
 *
 * @param parent the profile to create the new one under, or {@code null} for top level
 */
public record ProfileCreateRequest(String parent) {
}
