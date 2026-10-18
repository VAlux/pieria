package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.cluster.ClusterRepository;
import dev.alvo.pieria.storage.MemoryStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/profiles/{name}/clustering/schedule")
public class ClusteringScheduleController {
  public record Update(boolean enabled,String localTime,String timezone) {}
  private final MemoryStore store;
  public ClusteringScheduleController(MemoryStore store) {this.store=store;}
  private String profile(String name) {
    return store.findProfile(name).orElseThrow(() -> new IllegalArgumentException("profile not found")).id();
  }
  @GetMapping public ClusterRepository.Schedule get(@PathVariable String name) {
    String profileId=profile(name);return store.readClusters(repo->repo.schedule(profileId));
  }
  @PutMapping public ClusterRepository.Schedule set(@PathVariable String name,@RequestBody Update update) {
    String profileId=profile(name);
    return store.writeClusters(repo->repo.setSchedule(profileId,update.enabled(),update.localTime(),update.timezone()));
  }
}
