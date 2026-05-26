package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.api.response.ProfileListResponse;
import dev.alvo.pieria.api.response.ProfileSummary;
import dev.alvo.pieria.storage.MemoryStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Collection-level profile surface ({@code /v1/profiles}). Kept separate from
 * {@link ProfileController} (which is scoped to a single {@code /v1/profiles/{name}}) so the
 * unparameterized listing path does not collide with the {@code {name}} path variable.
 */
@RestController
public class ProfilesController {

  private final MemoryStore store;

  public ProfilesController(MemoryStore store) {
    this.store = store;
  }

  @GetMapping("/v1/profiles")
  public ProfileListResponse list() {
    return new ProfileListResponse(store.listProfiles().stream()
      .map(p -> new ProfileSummary(p.profile().name(), p.profile().createdAt(), p.activeCount()))
      .toList());
  }
}
