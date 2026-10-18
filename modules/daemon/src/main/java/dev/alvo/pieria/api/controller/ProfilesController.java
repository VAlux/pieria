package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.response.ProfileListResponse;
import dev.alvo.pieria.api.response.ProfileSummary;
import dev.alvo.pieria.profile.ProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Collection-level profile surface ({@code /v1/profiles}). Kept separate from
 * {@link ProfileController} (which is scoped to a single {@code /v1/profiles/{name}}) so the
 * unparameterized listing path does not collide with the {@code {name}} path variable.
 */
@RestController
public class ProfilesController {

  private final ProfileService profiles;

  public ProfilesController(ProfileService profiles) {
    this.profiles = profiles;
  }

  @GetMapping("/v1/profiles")
  public ProfileListResponse list() {
    return new ProfileListResponse(profiles.listSummaries().stream()
      .map(p -> new ProfileSummary(p.profile().name(), p.profile().createdAt(), p.activeCount(),
        p.sessionCount(), p.lastMemoryAt()))
      .toList());
  }
}
