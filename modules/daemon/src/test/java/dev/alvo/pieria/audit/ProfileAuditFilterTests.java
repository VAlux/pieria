package dev.alvo.pieria.audit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileAuditFilterTests {

  @ParameterizedTest
  @CsvSource({
    "GET, memories, memory.list",
    "GET, memories/search, memory.search",
    "GET, memories/abc, memory.get",
    "DELETE, memories/abc, memory.forget",
    "GET, memories/abc/history, memory.history",
    "POST, memories/abc/revisions, memory.revise",
    "GET, sessions, session.list",
    "GET, topic-keys, topic-key.search",
    "GET, graph/neighborhood, graph.neighborhood",
    "GET, graph/entities/e1, graph.entities",
    "GET, clusters, cluster.list",
    "GET, clusters/links, cluster.links",
    "POST, clusters/c1/rename, cluster.update",
    "POST, clustering/runs, clustering.update",
    "GET, wiki/status, wiki.status",
    "POST, wiki/build, wiki.build"
  })
  void namesConsoleOperations(String method, String rest, String expected) {
    assertThat(ProfileAuditFilter.operation(method, rest)).isEqualTo(expected);
  }
}
