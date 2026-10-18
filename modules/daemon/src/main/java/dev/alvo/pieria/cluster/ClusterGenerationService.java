package dev.alvo.pieria.cluster;

import dev.alvo.pieria.cluster.ClusterModelInput.Child;
import dev.alvo.pieria.cluster.ClusterModelInput.Draft;
import dev.alvo.pieria.cluster.ClusterRepository.Claim;
import dev.alvo.pieria.cluster.ClusterRepository.Evidence;
import dev.alvo.pieria.cluster.ClusterRepository.Source;
import dev.alvo.pieria.cluster.ClusterRepository.Topic;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.model.usage.InferenceUsageAccumulator;
import dev.alvo.pieria.model.usage.InferenceUsageSink;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.tools.Tokens;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded, fail-closed generation. No model call runs inside a SQLite transaction. */
@Service
public class ClusterGenerationService {
  private static final int MAX_INPUT_TOKENS=8000;
  private static final int MAX_OUTPUT_TOKENS=1200;
  private final MemoryStore store;
  private final ModelGateway model;

  public ClusterGenerationService(MemoryStore store,ModelGateway model) {
    this.store=store;this.model=model;
  }

  boolean coherent(String profileId,Topic topic) {
    List<String> contents=new ArrayList<>();
    for(var member:topic.members()) {
      if(member.kind().equals("memory")) {
        var memory=store.findMemoryById(member.id()).orElse(null);
        if(memory==null) return false;
        contents.add(memory.content());
      } else {
        var child=store.readClusters(repo->repo.synthesis(profileId,member.id(),null)).orElse(null);
        if(child==null) return false;
        contents.add(child.claims().stream().map(Claim::text).reduce((a,b)->a+"\n"+b).orElse(""));
      }
    }
    if(contents.stream().mapToLong(Tokens::estimate).sum()>MAX_INPUT_TOKENS) return false;
    var usage=new InferenceUsageAccumulator();
    try(var ignored=InferenceUsageSink.bind(usage)) {
      return model.validateClusterGroup(topic.title(),topic.scope(),contents);
    } finally {recordUsage(profileId,usage);}
  }

  private void recordUsage(String profileId,InferenceUsageAccumulator usage) {
    try {store.recordInferenceUsage(profileId,usage.snapshot());}
    catch(RuntimeException ignored) { /* Accounting cannot fail a generation. */ }
  }

  private record Prepared(String profileId,String clusterId,int revision,String fingerprint,
                          ClusterModelInput input) {}

  public ClusterRepository.Synthesis generate(String profileName,String clusterId,int expectedRevision) {
    String profileId=store.findProfile(profileName)
      .orElseThrow(() -> new IllegalArgumentException("profile not found")).id();
    return generateForProfileId(profileId,clusterId,expectedRevision,null,null);
  }

  ClusterRepository.Synthesis generateForProfileId(String profileId,String clusterId,int expectedRevision,
                                                   String runId,String workerId) {
    Prepared prepared=store.readClusters(repo->{
      var detail=repo.detail(profileId,clusterId,null)
        .orElseThrow(() -> new IllegalArgumentException("cluster not found"));
      if(detail.cluster().revision()!=expectedRevision) throw new ClusterConflictException("structure revision changed");
      if(!detail.cluster().state().equals("active")) throw new ClusterConflictException("cluster dissolved");
      List<Evidence> evidence=repo.evidence(profileId,clusterId,null);
      if(evidence.size()<3) throw new IllegalArgumentException("at least three distinct originals required");
      if(runId!=null) {
        var run=repo.run(profileId,runId)
          .orElseThrow(() -> new ClusterConflictException("generation run unavailable"));
        for(Evidence source:evidence) {
          boolean selected=store.selectedValidity(source.memoryId(),run.snapshot()).stream()
            .anyMatch(validity->source.assertionRevisionId().equals(validity.revisionId()));
          if(!selected) throw new ClusterConflictException("source changed after run snapshot");
        }
      }
      List<Child> children=new ArrayList<>();
      for(var member:detail.members()) if(member.kind().equals("cluster")) {
        var child=repo.synthesis(profileId,member.id(),null)
          .orElseThrow(() -> new ClusterConflictException("child synthesis unavailable"));
        children.add(new Child(member.id(),child.id(),child.claims()));
      }
      if(!children.isEmpty()) {
        List<Set<String>> branches=new ArrayList<>();
        for(var member:detail.members()) {
          if(member.kind().equals("memory")) branches.add(Set.of(member.id()));
          else branches.add(repo.evidence(profileId,member.id(),null).stream()
            .map(Evidence::memoryId).collect(java.util.stream.Collectors.toSet()));
        }
        boolean distinct=false;
        for(int i=0;i<branches.size() && !distinct;i++) for(int j=i+1;j<branches.size();j++)
          if(!branches.get(i).containsAll(branches.get(j)) &&
              !branches.get(j).containsAll(branches.get(i))) distinct=true;
        if(!distinct) throw new IllegalArgumentException("higher cluster needs two distinct contributions");
      }
      var input=new ClusterModelInput(detail.cluster().title(),detail.cluster().scope(),evidence,children,MAX_OUTPUT_TOKENS);
      long tokens=Tokens.estimate(input.title()+input.scope());
      for(var source:evidence) tokens+=Tokens.estimate(source.content());
      for(var child:children) for(var claim:child.claims()) tokens+=Tokens.estimate(claim.text());
      if(tokens>MAX_INPUT_TOKENS) throw new IllegalArgumentException("synthesis input exceeds 8000 tokens");
      return new Prepared(profileId,clusterId,expectedRevision,repo.fingerprint(profileId,clusterId),input);
    });
    var usage=new InferenceUsageAccumulator();
    try(var ignored=InferenceUsageSink.bind(usage)) {
    List<String> originals=prepared.input.evidence().stream().map(Evidence::content).toList();
    if(!model.validateClusterGroup(prepared.input.title(),prepared.input.scope(),originals))
      throw new IllegalArgumentException("cluster coherence rejected");
    Draft draft=model.synthesizeCluster(prepared.input);
    if(draft==null || draft.claims()==null || draft.claims().isEmpty())
      throw new IllegalArgumentException("cluster synthesis had no claims");
    Map<String,Evidence> byId=new HashMap<>();
    for(Evidence evidence:prepared.input.evidence()) byId.put(evidence.memoryId(),evidence);
    List<Claim> claims=new ArrayList<>();
    Set<String> supportedOriginals=new HashSet<>();
    for(var draftClaim:draft.claims()) {
      if(draftClaim.text()==null || draftClaim.text().isBlank() || draftClaim.supports()==null || draftClaim.supports().isEmpty())
        throw new IllegalArgumentException("claim text and support required");
      List<Source> supports=new ArrayList<>();
      List<String> texts=new ArrayList<>();
      for(String id:new LinkedHashSet<>(draftClaim.supports())) {
        Evidence evidence=byId.get(id);
        if(evidence==null) throw new IllegalArgumentException("claim cites an unknown original");
        supports.add(new Source(id,evidence.assertionRevisionId()));
        texts.add(evidence.content());
        supportedOriginals.add(id);
      }
      if(!model.validateClusterClaim(draftClaim.text(),texts))
        throw new IllegalArgumentException("unsupported claim rejected");
      List<Source> covers=new ArrayList<>();
      if(draftClaim.covers()!=null) for(String id:new LinkedHashSet<>(draftClaim.covers())) {
        Evidence evidence=byId.get(id);
        if(evidence==null || !draftClaim.supports().contains(id))
          throw new IllegalArgumentException("coverage lacks claim support");
        if(model.validateClusterCoverage(evidence.content(),draftClaim.text()))
          covers.add(new Source(id,evidence.assertionRevisionId()));
      }
      claims.add(new Claim(draftClaim.text().strip(),supports,covers));
    }
    if(supportedOriginals.size()<3) throw new IllegalArgumentException("synthesis covers fewer than three originals");
    String body=claims.stream().map(Claim::text).reduce((a,b)->a+"\n"+b).orElseThrow();
    if(Tokens.estimate(body)>MAX_OUTPUT_TOKENS) throw new IllegalArgumentException("synthesis exceeds 1200 tokens");
    List<float[]> claimEmbeddings=new ArrayList<>(claims.size());
    for(Claim claim:claims) {
      float[] embedding=model.embed(claim.text());
      if(embedding==null || embedding.length==0)
        throw new IllegalArgumentException("claim embedding unavailable");
      claimEmbeddings.add(embedding);
    }
    return store.writeClusters(repo->{
      if(runId!=null && !repo.canPublishRun(profileId,runId,workerId))
        throw new ClusterConflictException("run paused, cancelled, or lease expired");
      String validation=new tools.jackson.databind.ObjectMapper().writeValueAsString(
        Map.of("grounded",true,"usage",usage.snapshot(),"supportedOriginals",supportedOriginals.size(),
          "claimCount",claims.size()));
      return repo.publish(prepared.profileId,prepared.clusterId,prepared.revision,
        prepared.fingerprint,body,claims,"large","cluster-v1",validation,"automatic",claimEmbeddings);
    });
    } finally {recordUsage(profileId,usage);}
  }
}
