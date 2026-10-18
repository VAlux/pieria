package dev.alvo.pieria.cluster;

import dev.alvo.pieria.code.CodeIndexingService;
import dev.alvo.pieria.code.CodePayload;
import dev.alvo.pieria.cluster.ClusterRepository.Member;
import dev.alvo.pieria.cluster.ClusterRepository.Topic;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.tools.Hash;
import dev.alvo.pieria.tools.TextSimilarity;
import dev.alvo.pieria.tools.Vectors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Bounded lexical/vector neighbor discovery; the model makes the coherence decision later. */
final class ClusterDiscoveryService {
  private static final ObjectMapper JSON=new ObjectMapper();
  private final MemoryStore store;
  ClusterDiscoveryService(MemoryStore store) {this.store=store;}

  Topic discover(String profileId,String seedId) {
    Memory seed=store.findMemoryById(seedId).orElse(null);
    if(seed==null || seed.type()==MemoryType.CLUSTER || seed.type()==MemoryType.TASK) return null;
    Map<String,Memory> neighbors=new LinkedHashMap<>();
    neighbors.put(seed.id(),seed);
    for(Memory candidate:store.searchMemoriesFts(profileId,
        seed.content().substring(0,Math.min(500,seed.content().length())),15))
      if(candidate.type()!=MemoryType.CLUSTER && candidate.type()!=MemoryType.TASK)
        neighbors.putIfAbsent(candidate.id(),candidate);
    float[] seedVector=store.embeddingsFor(profileId,List.of(seed.id())).get(seed.id());
    if(seedVector!=null) for(Memory candidate:store.vectorSearch(profileId,seedVector,15))
      if(candidate.type()!=MemoryType.CLUSTER && candidate.type()!=MemoryType.TASK)
        neighbors.putIfAbsent(candidate.id(),candidate);
    Map<String,float[]> vectors=seedVector==null?Map.of():store.embeddingsFor(profileId,neighbors.keySet());
    // The seed plus at most 30 neighbors enters the candidate gate.
    List<Member> admitted=new ArrayList<>();
    for(Memory candidate:neighbors.values()) {
      if(candidate.id().equals(seed.id())) {admitted.add(new Member("memory",seed.id()));continue;}
      boolean keyed=seed.topicKey()!=null && seed.topicKey().equals(candidate.topicKey());
      double lexical=TextSimilarity.similarity(seed.content(),candidate.content());
      boolean semantic=false;
      if(seedVector!=null) {
        float[] vector=vectors.get(candidate.id());
        semantic=vector!=null && vector.length==seedVector.length && Vectors.cosine(seedVector,vector)>=.82;
      }
      if((keyed || lexical>=.35 || semantic) && sourceCompatible(seed,candidate,keyed))
        admitted.add(new Member("memory",candidate.id()));
    }
    if(admitted.size()<3 || admitted.size()>20) return null;
    admitted.sort(Comparator.comparing(Member::id));
    String normalized=seed.content().replaceAll("\\s+"," ").strip();
    String title=seed.topicKey()!=null?seed.topicKey():normalized.substring(0,Math.min(70,normalized.length()));
    String fingerprint=Hash.hash128(String.join("\n",admitted.stream().map(Member::id).toList()));
    return new Topic("discovery:"+seedId,title,"discovery:"+fingerprint,List.copyOf(admitted));
  }

  /** Similar wording in tool outcomes and code summaries is weak evidence without a shared subject. */
  private static boolean sourceCompatible(Memory seed,Memory candidate,boolean keyed) {
    if(keyed) return true;
    boolean trace=source(seed).equals("trace") || source(candidate).equals("trace");
    boolean code=CodeIndexingService.CODE_SESSION.equals(seed.sessionId()) ||
      CodeIndexingService.CODE_SESSION.equals(candidate.sessionId());
    if(!trace && !code) return true;
    Set<String> symbols=Set.copyOf(CodePayload.symbolIds(seed.payload()));
    if(CodePayload.symbolIds(candidate.payload()).stream().anyMatch(symbols::contains)) return true;
    String command=field(seed,"command");
    if(!command.isBlank() && command.equals(field(candidate,"command"))) return true;
    String firstPath=field(seed,"path"), secondPath=field(candidate,"path");
    if(!firstPath.isBlank() && !secondPath.isBlank()) {
      if(firstPath.equals(secondPath)) return true;
      // Files in one named module can share a subject; a repository root cannot establish one.
      String firstModule=module(firstPath), secondModule=module(secondPath);
      if(!firstModule.isBlank() && firstModule.equals(secondModule)) return true;
    }
    return false;
  }

  private static String source(Memory memory) {return field(memory,"source");}

  private static String module(String path) {
    String[] segments=path.split("/");
    if(segments.length>=3 && segments[0].equals("modules"))
      return segments[0]+"/"+segments[1];
    int separator=path.lastIndexOf('/');
    return separator>0?path.substring(0,separator):"";
  }

  private static String field(Memory memory,String name) {
    try {
      JsonNode value=JSON.readTree(memory.payload()).path(name);
      return value.isTextual()?value.asText():"";
    } catch(RuntimeException ignored) {return "";}
  }
}
