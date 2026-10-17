package dev.alvo.pieria.cluster;

import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.storage.MemoryStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClusterDiscoveryServiceTests {
  private Memory memory(String id,String text) {
    return new Memory(id,"fixture",MemoryType.EVENT,text,null,null,false,"{}",null,Instant.parse("2026-01-01T00:00:00Z"));
  }

  private Memory sourced(String id,String text,String session,String payload) {
    return new Memory(id,session,MemoryType.EVENT,text,null,null,false,payload,null,
      Instant.parse("2026-01-01T00:00:00Z"));
  }

  @Test void vectorNeighborsCanConnectLexicallyDifferentOriginals() {
    MemoryStore store=mock(MemoryStore.class);
    Memory a=memory("a","The storage writer serializes commits.");
    Memory b=memory("b","Jobs resume after restart.");
    Memory c=memory("c","A durable queue tracks progress.");
    float[] vector={1,0,0};
    when(store.findMemoryById("a")).thenReturn(Optional.of(a));
    when(store.searchMemoriesFts(eq("profile"),anyString(),eq(15))).thenReturn(List.of(a));
    when(store.embeddingsFor(eq("profile"),anyCollection())).thenReturn(Map.of("a",vector,"b",vector,"c",vector));
    when(store.vectorSearch("profile",vector,15)).thenReturn(List.of(a,b,c));
    var found=new ClusterDiscoveryService(store).discover("profile","a");
    assertNotNull(found);
    assertEquals(List.of("a","b","c"),found.members().stream().map(ClusterRepository.Member::id).toList());
  }

  @Test void oversizedNeighborSetIsRejectedWithoutTruncatingMembership() {
    MemoryStore store=mock(MemoryStore.class);
    Memory seed=memory("seed","A distinctive system subject.");
    float[] vector={1,0,0};
    List<Memory> neighbors=new ArrayList<>();
    Map<String,float[]> embeddings=new java.util.HashMap<>();
    embeddings.put("seed",vector);
    neighbors.add(seed);
    for(int i=0;i<22;i++) {
      Memory neighbor=memory("n"+i,"Different detail "+i);
      neighbors.add(neighbor);
      embeddings.put(neighbor.id(),vector);
    }
    when(store.findMemoryById("seed")).thenReturn(Optional.of(seed));
    when(store.searchMemoriesFts(eq("profile"),anyString(),eq(15))).thenReturn(List.of(seed));
    when(store.embeddingsFor(eq("profile"),anyCollection())).thenReturn(embeddings);
    when(store.vectorSearch("profile",vector,15)).thenReturn(neighbors);
    assertNull(new ClusterDiscoveryService(store).discover("profile","seed"));
  }

  @Test void traceNeighborsNeedAConcreteSharedSubject() {
    MemoryStore store=mock(MemoryStore.class);
    Memory unrelated=sourced("unrelated","Command completed after retry.","trace-b",
      "{\"source\":\"trace\",\"command\":\"git fetch\"}");
    Memory linked=sourced("linked","Command completed after retry.","trace-c",
      "{\"source\":\"trace\",\"command\":\"./gradlew test\"}");
    Memory linkedBySymbol=sourced("symbol","A build command finished.","trace-d",
      "{\"source\":\"trace\",\"symbolIds\":[\"build-symbol\"]}");
    Memory seedWithSymbol=sourced("seed","Command completed after retry.","trace-a",
      "{\"source\":\"trace\",\"command\":\"./gradlew test\",\"symbolIds\":[\"build-symbol\"]}");
    float[] vector={1,0,0};
    when(store.findMemoryById("seed")).thenReturn(Optional.of(seedWithSymbol));
    when(store.searchMemoriesFts(eq("profile"),anyString(),eq(15)))
      .thenReturn(List.of(seedWithSymbol,unrelated,linked,linkedBySymbol));
    when(store.embeddingsFor(eq("profile"),anyCollection())).thenReturn(Map.of(
      "seed",vector,"unrelated",vector,"linked",vector,"symbol",vector));
    when(store.vectorSearch("profile",vector,15)).thenReturn(List.of());
    var found=new ClusterDiscoveryService(store).discover("profile","seed");
    assertNotNull(found);
    assertEquals(List.of("linked","seed","symbol"),
      found.members().stream().map(ClusterRepository.Member::id).toList());
  }

  @Test void codeSummariesCanGroupWithinNamedModule() {
    MemoryStore store=mock(MemoryStore.class);
    String codeSession=dev.alvo.pieria.code.CodeIndexingService.CODE_SESSION;
    Memory seed=sourced("seed","Storage state overview",codeSession,
      "{\"source\":\"code-summary\",\"path\":\"modules/daemon/storage/A.java\"}");
    Memory peer=sourced("peer","Storage state overview",codeSession,
      "{\"source\":\"code-summary\",\"path\":\"modules/daemon/storage/B.java\"}");
    Memory other=sourced("other","Storage state overview",codeSession,
      "{\"source\":\"code-summary\",\"path\":\"modules/cli/Command.java\"}");
    Memory third=sourced("third","Storage state overview",codeSession,
      "{\"source\":\"code-summary\",\"path\":\"modules/daemon/api/C.java\"}");
    when(store.findMemoryById("seed")).thenReturn(Optional.of(seed));
    when(store.searchMemoriesFts(eq("profile"),anyString(),eq(15))).thenReturn(List.of(seed,peer,other,third));
    when(store.embeddingsFor(eq("profile"),anyCollection())).thenReturn(Map.of());
    var found=new ClusterDiscoveryService(store).discover("profile","seed");
    assertNotNull(found);
    assertEquals(List.of("peer","seed","third"),
      found.members().stream().map(ClusterRepository.Member::id).toList());
  }
}
