package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.cluster.ClusterConflictException;
import dev.alvo.pieria.cluster.ClusterRepository;
import dev.alvo.pieria.storage.MemoryStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.alvo.pieria.cluster.ClusterRepository.Member;

/** Bounded graph inspection and optimistic manual editing. */
@RestController
@RequestMapping("/v1/profiles/{name}/clusters")
public class ClusterController {
  public record Create(String title,String scope,List<Member> members) {}
  public record Edit(int expectedRevision,String title,String scope) {}
  public record ChangeMember(int expectedRevision,Member member) {}
  public record Restore(int expectedRevision,int sourceRevision) {}
  public record Restored(ClusterRepository.Cluster cluster,ClusterRepository.Run operation) {}

  private final MemoryStore store;
  public ClusterController(MemoryStore store) { this.store=store; }

  private String profile(String name) {
    return store.findProfile(name).orElseThrow(() -> new IllegalArgumentException("profile not found")).id();
  }
  private ClusterRepository.Detail detail(String profileId,String id) {
    return store.readClusters(c->c.detail(profileId,id,null).orElseThrow(() -> new IllegalArgumentException("cluster not found")));
  }
  private URI uri(String name,String id) {
    return URI.create("/v1/profiles/"+URLEncoder.encode(name,StandardCharsets.UTF_8).replace("+","%20")+"/clusters/"+id);
  }

  @GetMapping public List<ClusterRepository.Cluster> list(@PathVariable String name,
      @RequestParam(required=false) String q,@RequestParam(defaultValue="30") int limit,
      @RequestParam(required=false) String asOf,@RequestParam(required=false) String knownAsOf) {
    String profileId=profile(name);var snapshot=store.temporalSnapshot(asOf,knownAsOf);
    return store.readClusters(c->c.list(profileId,q,limit,snapshot));
  }
  @GetMapping("/export") public Map<String,Object> export(@PathVariable String name) {
    String profileId=profile(name);
    return store.readClusters(c->c.exportProfile(profileId));
  }
  @PostMapping public ResponseEntity<ClusterRepository.Cluster> create(@PathVariable String name,@RequestBody Create request) {
    String profileId=profile(name);
    var cluster=store.writeClusters(c->c.create(profileId,request.title(),request.scope(),request.members(),"manual"));
    return ResponseEntity.created(uri(name,cluster.id())).body(cluster);
  }
  @GetMapping("/{id}") public ClusterRepository.Detail get(@PathVariable String name,@PathVariable String id,
      @RequestParam(required=false) String asOf,@RequestParam(required=false) String knownAsOf) {
    String profileId=profile(name);var snapshot=store.temporalSnapshot(asOf,knownAsOf);
    return store.readClusters(c->c.detail(profileId,id,snapshot).orElseThrow(() -> new IllegalArgumentException("cluster not found")));
  }
  @GetMapping("/{id}/parents") public List<String> parents(@PathVariable String name,@PathVariable String id) {
    return detail(profile(name),id).parents();
  }
  @GetMapping("/{id}/members") public List<Member> members(@PathVariable String name,@PathVariable String id) {
    return detail(profile(name),id).members();
  }
  @GetMapping("/{id}/descendants") public List<Member> descendants(@PathVariable String name,@PathVariable String id,
      @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit,
      @RequestParam(required=false) String asOf,@RequestParam(required=false) String knownAsOf) {
    String profileId=profile(name);var snapshot=store.temporalSnapshot(asOf,knownAsOf);
    return store.readClusters(c->c.descendants(profileId,id,offset,limit,snapshot));
  }
  @GetMapping("/{id}/originals") public List<ClusterRepository.Evidence> originals(@PathVariable String name,@PathVariable String id,
      @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit,
      @RequestParam(required=false) String asOf,@RequestParam(required=false) String knownAsOf) {
    if(offset<0 || limit<1 || limit>100) throw new IllegalArgumentException("invalid page");
    String profileId=profile(name);var snapshot=store.temporalSnapshot(asOf,knownAsOf);
    return store.readClusters(c->c.evidence(profileId,id,snapshot).stream().skip(offset).limit(limit).toList());
  }
  @GetMapping("/{id}/revisions") public List<ClusterRepository.Structure> revisions(@PathVariable String name,@PathVariable String id) {
    return detail(profile(name),id).history();
  }
  @GetMapping("/{id}/synthesis") public ClusterRepository.Synthesis synthesis(@PathVariable String name,@PathVariable String id,
      @RequestParam(required=false) String asOf,@RequestParam(required=false) String knownAsOf) {
    String profileId=profile(name);var snapshot=store.temporalSnapshot(asOf,knownAsOf);
    return store.readClusters(c->c.synthesis(profileId,id,snapshot).orElseThrow(() -> new IllegalArgumentException("eligible synthesis not found")));
  }
  @PostMapping("/{id}/rename") public ClusterRepository.Cluster rename(@PathVariable String name,@PathVariable String id,@RequestBody Edit request) {
    String profileId=profile(name);
    return store.writeClusters(c->{
      var prior=c.detail(profileId,id,null).orElseThrow(() -> new IllegalArgumentException("cluster not found"));
      return c.edit(profileId,id,request.expectedRevision(),request.title(),request.scope(),prior.members(),"manual","rename");
    });
  }
  @PostMapping("/{id}/members") public ClusterRepository.Cluster attach(@PathVariable String name,@PathVariable String id,@RequestBody ChangeMember request) {
    String profileId=profile(name);
    return store.writeClusters(c->{
      var prior=c.detail(profileId,id,null).orElseThrow(() -> new IllegalArgumentException("cluster not found"));
      List<Member> members=new ArrayList<>(prior.members());
      if(members.contains(request.member())) throw new ClusterConflictException("member already attached");
      members.add(request.member());
      return c.edit(profileId,id,request.expectedRevision(),prior.cluster().title(),prior.cluster().scope(),members,"manual","attach");
    });
  }
  @DeleteMapping("/{id}/members") public ClusterRepository.Cluster detach(@PathVariable String name,@PathVariable String id,@RequestBody ChangeMember request) {
    String profileId=profile(name);
    return store.writeClusters(c->{
      var prior=c.detail(profileId,id,null).orElseThrow(() -> new IllegalArgumentException("cluster not found"));
      List<Member> members=new ArrayList<>(prior.members());
      if(!members.remove(request.member())) throw new IllegalArgumentException("member not attached");
      return c.edit(profileId,id,request.expectedRevision(),prior.cluster().title(),prior.cluster().scope(),members,"manual","detach");
    });
  }
  @PostMapping("/{id}/members/detach") public ClusterRepository.Cluster detachPost(
      @PathVariable String name,@PathVariable String id,@RequestBody ChangeMember request) {
    return detach(name,id,request);
  }
  @PostMapping("/{id}/dissolve") public ClusterRepository.Cluster dissolve(@PathVariable String name,@PathVariable String id,@RequestBody Edit request) {
    String profileId=profile(name);
    return store.writeClusters(c->c.dissolve(profileId,id,request.expectedRevision(),"manual"));
  }
  @PostMapping("/{id}/restore") public ResponseEntity<Restored> restore(@PathVariable String name,@PathVariable String id,@RequestBody Restore request) {
    String profileId=profile(name);
    Restored restored=store.writeClusters(c->{
      var cluster=c.restore(profileId,id,request.expectedRevision(),request.sourceRevision(),"manual");
      var run=c.startRun(profileId,"regenerate");
      c.enqueueRegeneration(run.id(),id,cluster.revision());
      return new Restored(cluster,run);
    });
    return ResponseEntity.accepted().location(URI.create("/v1/profiles/"+
      URLEncoder.encode(name,StandardCharsets.UTF_8).replace("+","%20")+
      "/clustering/runs/"+restored.operation().id())).body(restored);
  }
  @PostMapping("/{id}/regenerate") public ResponseEntity<ClusterRepository.Run> regenerate(
      @PathVariable String name,@PathVariable String id,@RequestBody Edit request) {
    String profileId=profile(name);
    ClusterRepository.Run run=store.writeClusters(repo->{
      var detail=repo.detail(profileId,id,null).orElseThrow(() -> new IllegalArgumentException("cluster not found"));
      if(detail.cluster().revision()!=request.expectedRevision()) throw new ClusterConflictException("structure revision changed");
      var created=repo.startRun(profileId,"regenerate");
      repo.enqueueRegeneration(created.id(),id,request.expectedRevision());
      return created;
    });
    return ResponseEntity.accepted().location(URI.create("/v1/profiles/"+
      URLEncoder.encode(name,StandardCharsets.UTF_8).replace("+","%20")+"/clustering/runs/"+run.id())).body(run);
  }
  @ExceptionHandler(ClusterConflictException.class) public ResponseEntity<?> conflict(ClusterConflictException e) {
    return ResponseEntity.status(409).body(Map.of("code","cluster_conflict","message",e.getMessage()));
  }
  @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e) {
    int status=e.getMessage()!=null && e.getMessage().contains("not found")?404:400;
    return ResponseEntity.status(status).body(Map.of("code",status==404?"not_found":"invalid_cluster","message",e.getMessage()));
  }
}
