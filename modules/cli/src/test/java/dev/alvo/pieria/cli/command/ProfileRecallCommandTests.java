package dev.alvo.pieria.cli.command;

import dev.alvo.pieria.cli.PieriaCli;
import dev.alvo.pieria.cli.testsupport.StubDaemon;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileRecallCommandTests {

  private static final String HARVESTED = """
    {"id":"m1","type":"fact","content":"the db is mysql","topicKey":null,"sessionId":"s","superseded":false,"payload":"{}",
     "createdAt":"2026-03-01T00:00:00Z","validity":[],"assertions":[],
     "origin":{"sourceId":"jira-acme","harvester":"jira","externalId":"ACME-12","url":"https://a/12","trust":"untrusted"}}""";
  private static final String OWN = """
    {"id":"m2","type":"fact","content":"the db is postgres","topicKey":null,"sessionId":"s","superseded":false,"payload":"{}",
     "createdAt":"2026-03-01T00:00:00Z","validity":[],"assertions":[]}""";

  private static String stdout(String... args) {
    PrintStream original = System.out;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(out));
      new CommandLine(new PieriaCli()).execute(args);
    } finally {
      System.setOut(original);
    }
    return out.toString(StandardCharsets.UTF_8);
  }

  private static void stubRecall(StubDaemon daemon, String memories) {
    daemon.stub("/v1/profiles/p/recall", 200, "{\"answer\":\"a\",\"memories\":[" + memories + "]}");
  }

  @Test
  void passesSourceFilters() {
    try (StubDaemon daemon = StubDaemon.start()) {
      stubRecall(daemon, OWN);

      stdout("profile", "recall", "p", "q", "--source", "jira", "--source", "wiki", "--exclude-source", "web", "--daemon-url", daemon.baseUrl());

      assertThat(daemon.lastRequestTo("/v1/profiles/p/recall").body())
        .contains("\"includeSources\":[\"jira\",\"wiki\"]").contains("\"excludeSources\":[\"web\"]");
    }
  }

  @Test
  void labelsHarvestedEvidence() {
    try (StubDaemon daemon = StubDaemon.start()) {
      stubRecall(daemon, HARVESTED);

      assertThat(stdout("profile", "recall", "p", "q", "--daemon-url", daemon.baseUrl()))
        .contains("[fact] [untrusted · jira · ACME-12 · https://a/12] the db is mysql");
    }
  }

  @Test
  void leavesOwnMemoriesUnlabelled() {
    try (StubDaemon daemon = StubDaemon.start()) {
      stubRecall(daemon, OWN);

      assertThat(stdout("profile", "recall", "p", "q", "--daemon-url", daemon.baseUrl()))
        .contains("[fact] the db is postgres").doesNotContain("untrusted");
    }
  }

  @Test
  void labelsHarvestedMemoriesInTheList() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/profiles/p/memories", 200, "{\"memories\":[" + HARVESTED + "]}");

      assertThat(stdout("profile", "memories", "p", "--daemon-url", daemon.baseUrl()))
        .contains("[untrusted · jira · ACME-12 · https://a/12]");
    }
  }
}
