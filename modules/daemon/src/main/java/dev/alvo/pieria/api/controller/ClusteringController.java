package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.cluster.ClusterConflictException;
import dev.alvo.pieria.cluster.ClusterRepository;
import dev.alvo.pieria.storage.MemoryStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/profiles/{name}/clustering/runs")
public class ClusteringController {
  private final MemoryStore store;
  public ClusteringController(MemoryStore store) {this.store=store;}
  private String profile(String name) {
    return store.findProfile(name).orElseThrow(() -> new IllegalArgumentException("profile not found")).id();
  }
  @PostMapping public ResponseEntity<ClusterRepository.Run> start(@PathVariable String name) {
    String profileId=profile(name);
    var run=store.writeClusters(repo->repo.startRun(profileId,"manual"));
    URI uri=URI.create("/v1/profiles/"+URLEncoder.encode(name,StandardCharsets.UTF_8).replace("+","%20")+
      "/clustering/runs/"+run.id());
    return ResponseEntity.accepted().location(uri).body(run);
  }
  @GetMapping public List<ClusterRepository.Run> list(@PathVariable String name,@RequestParam(defaultValue="30") int limit) {
    String profileId=profile(name);
    return store.readClusters(repo->repo.runs(profileId,limit));
  }
  @GetMapping("/{id}") public ClusterRepository.Run detail(@PathVariable String name,@PathVariable String id) {
    String profileId=profile(name);
    return store.readClusters(repo->repo.run(profileId,id).orElseThrow(() -> new IllegalArgumentException("run not found")));
  }
  @GetMapping("/{id}/events") public List<ClusterRepository.Work> events(@PathVariable String name,@PathVariable String id,
      @RequestParam(defaultValue="100") int limit) {
    String profileId=profile(name);
    return store.readClusters(repo->repo.work(profileId,id,limit));
  }
  @GetMapping("/{id}/audit") public List<Map<String,Object>> audit(@PathVariable String name,@PathVariable String id,
      @RequestParam(defaultValue="100") int limit) {
    String profileId=profile(name);
    return store.readClusters(repo->repo.auditEvents(profileId,id,limit));
  }
  @PostMapping("/{id}/pause") public ClusterRepository.Run pause(@PathVariable String name,@PathVariable String id) {
    String profileId=profile(name);return store.writeClusters(repo->repo.transitionRun(profileId,id,"pause"));
  }
  @PostMapping("/{id}/resume") public ClusterRepository.Run resume(@PathVariable String name,@PathVariable String id) {
    String profileId=profile(name);return store.writeClusters(repo->repo.transitionRun(profileId,id,"resume"));
  }
  @PostMapping("/{id}/cancel") public ClusterRepository.Run cancel(@PathVariable String name,@PathVariable String id) {
    String profileId=profile(name);return store.writeClusters(repo->repo.transitionRun(profileId,id,"cancel"));
  }
  @ExceptionHandler(ClusterConflictException.class) public ResponseEntity<?> conflict(ClusterConflictException e) {
    return ResponseEntity.status(409).body(Map.of("code","cluster_conflict","message",e.getMessage()));
  }
  @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e) {
    int status=e.getMessage()!=null && e.getMessage().contains("not found")?404:400;
    return ResponseEntity.status(status).body(Map.of("code",status==404?"not_found":"invalid_run","message",e.getMessage()));
  }
}
