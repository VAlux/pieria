package dev.alvo.pieria.harvesters.confluence;

import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvester.protocol.RecordCodec;
import dev.alvo.pieria.harvester.protocol.RequestCodec;
import dev.alvo.pieria.harvesters.PieriaHarvest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
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

class ConfluenceHarvesterTests {

  private static final Instant T = Instant.parse("2026-10-01T10:00:00Z");

  private record Outcome(int exit, List<HarvestRecord> records, String stderr) {

    List<HarvestRecord.Document> documents() {
      return records.stream().filter(r -> r instanceof HarvestRecord.Document).map(r -> (HarvestRecord.Document) r).toList();
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

    JsonNode cursor() {
      JsonNode cursor = null;
      for (HarvestRecord record : records) {
        if (record instanceof HarvestRecord.Checkpoint checkpoint) {
          cursor = checkpoint.cursor();
        }
      }
      return cursor;
    }
  }

  private static Outcome run(String command, ObjectNode config, JsonNode cursor) {
    HarvestRequest request = new HarvestRequest(1, config, Map.of("token", StubConfluence.TOKEN), cursor, null);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exit = PieriaHarvest.run(new String[]{"confluence", command},
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
  void firstHarvestReportsDocumentsAndSweeps(boolean cloud) {
    try (StubConfluence confluence = new StubConfluence(cloud)) {
      confluence.add("101", "OPS", "Runbook", T, "<p>Restart the worker.</p>");
      confluence.add("102", "OPS", "Oncall", T.plusSeconds(1), "<p>Page the lead.</p>");
      confluence.add("201", "HR", "Holidays", T.plusSeconds(2), "<p>Not in scope.</p>");

      Outcome outcome = run("harvest", ConfluenceFeedTests.config(confluence), null);

      assertThat(outcome.exit()).as(outcome.stderr()).isZero();
      assertThat(outcome.documents()).extracting(HarvestRecord.Document::id).containsExactly("101", "102");
      assertThat(outcome.seenIds()).containsExactlyInAnyOrder("101", "102");
      assertThat(outcome.swept()).isTrue();
    }
  }

  @Test
  void editedPageIsReportedWithItsNewVersion() {
    try (StubConfluence confluence = new StubConfluence(false)) {
      StubConfluence.Content page = confluence.add("101", "OPS", "Runbook", Instant.now().minusSeconds(120), "<p>Restart once.</p>");
      confluence.add("102", "OPS", "Oncall", Instant.now().minusSeconds(110), "<p>Page the lead.</p>");
      Outcome first = run("harvest", ConfluenceFeedTests.config(confluence), null);
      page.edit(Instant.now(), "<p>Restart twice.</p>");

      Outcome second = run("harvest", ConfluenceFeedTests.config(confluence), first.cursor());

      assertThat(second.exit()).as(second.stderr()).isZero();
      assertThat(second.documents()).singleElement().satisfies(document -> {
        assertThat(document.id()).isEqualTo("101");
        assertThat(document.version()).isEqualTo("2");
        assertThat(document.text()).isEqualTo("Restart twice.");
      });
    }
  }

  @Test
  void checkRejectsAnInvalidSpaceKey() {
    try (StubConfluence confluence = new StubConfluence(false)) {
      ObjectNode config = ConfluenceFeedTests.config(confluence);
      config.putArray("spaces").add("A\" OR type=user");

      Outcome outcome = run("check", config, null);

      assertThat(outcome.exit()).isEqualTo(1);
      assertThat(outcome.failure().errorClass()).isEqualTo(ErrorClass.CONFIG);
      assertThat(outcome.failure().message()).contains("not a valid space key");
    }
  }

  @Test
  void checkNeedsSpacesOrCql() {
    try (StubConfluence confluence = new StubConfluence(false)) {
      ObjectNode config = ConfluenceFeedTests.config(confluence);
      config.remove("spaces");

      Outcome outcome = run("check", config, null);

      assertThat(outcome.failure().message()).contains("spaces or cql is required");
    }
  }

  @Test
  void checkPassesWithGoodCredentials() {
    try (StubConfluence confluence = new StubConfluence(true)) {
      Outcome outcome = run("check", ConfluenceFeedTests.config(confluence), null);

      assertThat(outcome.exit()).as(outcome.records().toString()).isZero();
    }
  }

  @Test
  void revokedTokenFailsAuthWithNoSweep() {
    try (StubConfluence confluence = new StubConfluence(true)) {
      confluence.add("101", "OPS", "Runbook", T, "<p>Restart the worker.</p>");
      confluence.revoke();

      Outcome outcome = run("harvest", ConfluenceFeedTests.config(confluence), null);

      assertThat(outcome.exit()).isEqualTo(1);
      assertThat(outcome.failure().errorClass()).isEqualTo(ErrorClass.AUTH);
      assertThat(outcome.swept()).isFalse();
      assertThat(outcome.documents()).isEmpty();
    }
  }
}
