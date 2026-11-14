package dev.alvo.pieria.harvesters.jira;

import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvester.protocol.RecordCodec;
import dev.alvo.pieria.harvester.protocol.RequestCodec;
import dev.alvo.pieria.harvesters.PieriaHarvest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JiraHarvesterTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Instant T = Instant.parse("2026-10-01T10:00:00Z");

  private record Outcome(int exit, List<HarvestRecord> records, String stderr) {

    List<String> itemIds() {
      return records.stream().filter(r -> r instanceof HarvestRecord.Item).map(r -> ((HarvestRecord.Item) r).id()).toList();
    }

    List<String> seenIds() {
      return records.stream().filter(r -> r instanceof HarvestRecord.Seen).flatMap(r -> ((HarvestRecord.Seen) r).ids().stream()).toList();
    }

    boolean swept() {
      return records.stream().anyMatch(r -> r instanceof HarvestRecord.Sweep);
    }

    HarvestRecord.Failure failure() {
      return records.stream().filter(r -> r instanceof HarvestRecord.Failure).map(r -> (HarvestRecord.Failure) r).findFirst().orElse(null);
    }
  }

  private static ObjectNode config(StubJira jira, String jql) {
    ObjectNode config = MAPPER.createObjectNode().put("baseUrl", jira.baseUrl()).put("jql", jql)
      .put("deployment", jira.cloud ? "cloud" : "datacenter");
    if (jira.cloud) {
      config.put("email", StubJira.EMAIL);
    }
    return config;
  }

  private static Outcome run(String command, ObjectNode config) {
    HarvestRequest request = new HarvestRequest(1, config, Map.of("token", StubJira.TOKEN), null, null);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exit = PieriaHarvest.run(new String[]{"jira", command},
      new ByteArrayInputStream((RequestCodec.encode(request) + "\n").getBytes(StandardCharsets.UTF_8)), out,
      new PrintStream(err, true, StandardCharsets.UTF_8));
    List<HarvestRecord> records = new ArrayList<>();
    for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
      if (!line.isBlank()) {
        records.add(RecordCodec.decode(line));
      }
    }
    return new Outcome(exit, records, err.toString(StandardCharsets.UTF_8));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void firstHarvestReportsThreadsAndSweeps(boolean cloud) {
    try (StubJira jira = new StubJira(cloud)) {
      jira.add("10001", "T-1", "One", T, T.plusSeconds(60), "<p>first</p>").comment("1", "Bo", T.plusSeconds(70), "<p>hi</p>");
      jira.add("10002", "T-2", "Two", T.plusSeconds(1), T.plusSeconds(61), "<p>second</p>");

      Outcome outcome = run("harvest", config(jira, "project = T"));

      assertThat(outcome.exit()).as(outcome.stderr()).isZero();
      assertThat(outcome.itemIds()).containsExactly("10001", "10002");
      assertThat(outcome.seenIds()).containsExactlyInAnyOrder("10001", "10002");
      assertThat(outcome.swept()).isTrue();
      HarvestRecord.ConversationThread first = (HarvestRecord.ConversationThread) outcome.records().stream()
        .filter(r -> r instanceof HarvestRecord.ConversationThread).findFirst().orElseThrow();
      assertThat(first.turns()).extracting(HarvestRecord.Turn::text).containsExactly("Type: Task · Status: Open\n\nfirst", "hi");
    }
  }

  @Test
  void editDuringDataCenterPagingSkipsNothing() {
    try (StubJira jira = new StubJira(false)) {
      for (int i = 1; i <= 1_050; i++) {
        jira.add(String.valueOf(10_000 + i), "T-" + i, "Issue " + i, T.plusSeconds(i), T.plusSeconds(1000 + i), "<p>b" + i + "</p>");
      }
      // Issue 2 is edited after the first page: it moves to the end, and the rest shift left by one.
      jira.afterFirstSearchPage(() -> jira.add("10002", "T-2", "Issue 2", T.plusSeconds(2), Instant.now(), "<p>edited</p>"));

      Outcome outcome = run("harvest", config(jira, "project = T"));

      assertThat(outcome.exit()).as(outcome.stderr()).isZero();
      List<String> expected = new ArrayList<>();
      for (int i = 1; i <= 1_050; i++) {
        expected.add(String.valueOf(10_000 + i));
      }
      assertThat(outcome.itemIds()).containsAll(expected);
      assertThat(outcome.seenIds()).containsExactlyInAnyOrderElementsOf(expected);
    }
  }

  @Test
  void revokedTokenFailsAuthWithNoSweep() {
    try (StubJira jira = new StubJira(true)) {
      jira.add("10001", "T-1", "One", T, T.plusSeconds(60), "<p>first</p>");
      jira.revoke();

      Outcome outcome = run("harvest", config(jira, "project = T"));

      assertThat(outcome.exit()).isEqualTo(1);
      assertThat(outcome.failure().errorClass()).isEqualTo(ErrorClass.AUTH);
      assertThat(outcome.swept()).isFalse();
      assertThat(outcome.itemIds()).isEmpty();
    }
  }

  @Test
  void checkPassesWithGoodCredentials() {
    try (StubJira jira = new StubJira(false)) {
      assertThat(run("check", config(jira, "project = T")).exit()).isZero();
    }
  }

  @Test
  void checkRejectsBadJql() {
    try (StubJira jira = new StubJira(true)) {
      Outcome outcome = run("check", config(jira, "nope = 1"));

      assertThat(outcome.exit()).isEqualTo(1);
      assertThat(outcome.failure().errorClass()).isEqualTo(ErrorClass.CONFIG);
      assertThat(outcome.failure().message()).contains("nope");
    }
  }

  @Test
  void checkRejectsOrderBy() {
    try (StubJira jira = new StubJira(true)) {
      Outcome outcome = run("check", config(jira, "project = T order by created"));

      assertThat(outcome.exit()).isEqualTo(1);
      assertThat(outcome.failure().errorClass()).isEqualTo(ErrorClass.CONFIG);
      assertThat(outcome.failure().message()).contains("jql must not contain ORDER BY");
    }
  }

  @Test
  void secretNeverReachesOutput() {
    try (StubJira jira = new StubJira(true)) {
      jira.revoke();

      Outcome outcome = run("harvest", config(jira, "project = T"));

      assertThat(outcome.stderr()).doesNotContain(StubJira.TOKEN);
      assertThat(outcome.records().toString()).doesNotContain(StubJira.TOKEN);
    }
  }
}
