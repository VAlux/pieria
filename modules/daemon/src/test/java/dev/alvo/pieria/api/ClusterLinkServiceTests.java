package dev.alvo.pieria.api;

import dev.alvo.pieria.cluster.ClusterLinkService;
import dev.alvo.pieria.domain.error.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClusterLinkServiceTests {

  /** Three clusters: web and agent share two entities; "pieria" appears in all three. */
  private final StubMemoryStore store = new StubMemoryStore() {
    @Override
    public Map<String, Map<String, String>> clusterEntities(String profileId, Collection<String> clusterIds) {
      Map<String, Map<String, String>> out = new LinkedHashMap<>();
      out.put("web", Map.of("e-pieria", "pieria", "e-vite", "vite", "e-console", "console"));
      out.put("agent", Map.of("e-pieria", "pieria", "e-vite", "vite", "e-console", "console", "e-mcp", "mcp"));
      out.put("storage", Map.of("e-pieria", "pieria", "e-sqlite", "sqlite"));
      return out;
    }
  };

  @Test
  void linksPairsByDistinctiveSharedEntities() {
    store.getOrCreateProfile("p");
    var links = new ClusterLinkService(store).links("p", List.of("web", "agent", "storage"), 1, 10);

    assertThat(links).singleElement().satisfies(link -> {
      assertThat(List.of(link.a(), link.b())).containsExactlyInAnyOrder("web", "agent");
      assertThat(link.shared()).isEqualTo(2);
      assertThat(link.examples()).containsExactly("console", "vite");
    });
  }

  @Test
  void validatesInput() {
    ClusterLinkService service = new ClusterLinkService(store);
    assertThatThrownBy(() -> service.links("ghost", List.of("a"), 1, 10)).isInstanceOf(NotFoundException.class);
    store.getOrCreateProfile("p");
    assertThatThrownBy(() -> service.links("p", List.of(), 1, 10)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.links("p", List.of("a"), 0, 10)).isInstanceOf(IllegalArgumentException.class);
  }
}
