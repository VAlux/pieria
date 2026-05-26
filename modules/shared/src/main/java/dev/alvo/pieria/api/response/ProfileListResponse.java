package dev.alvo.pieria.api.response;

import java.util.List;

/**
 * Result of GET /v1/profiles.
 */
public record ProfileListResponse(List<ProfileSummary> profiles) {
}
