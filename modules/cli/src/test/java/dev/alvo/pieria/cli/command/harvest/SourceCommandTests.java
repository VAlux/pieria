package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.cli.PieriaCli;
import dev.alvo.pieria.cli.testsupport.StubDaemon;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceCommandTests {

  private static final String SOURCES = "/v1/profiles/p/sources";
  private static final String HARVESTERS = """
    {"harvesters":[{"id":"jira","name":"Jira","version":"1","capabilities":[],"network":[],"fields":[
      {"key":"url","kind":"string","label":"URL","hint":null,"required":true,"options":[],"defaultValue":null},
      {"key":"token","kind":"secret","label":"Token","hint":null,"required":true,"options":[],"defaultValue":null}
    ]}]}""";

  private static String source(String warnings, Object sweepBlocked) {
    return """
      {"id":"s","profile":"p","harvester":"jira","config":{"url":"https://x"},"secretNames":["token"],"mode":"full",
       "intervalSeconds":21600,"maxItemsPerRun":null,"maxCharsPerRun":null,"status":"paused","nextRunAt":"2026-03-01T00:00:00Z",
       "consecutiveFailures":0,"sweepBlockedCount":%s,"acceptNextSweep":false,"running":false,"warnings":%s,"lastRun":null}"""
      .formatted(sweepBlocked, warnings);
  }

  private static final String SOURCE = source("[]", null);
  private static final String CHECK_OK = "{\"ok\":true,\"errorClass\":null,\"message\":null}";

  private record Captured(int code, String stdout, String stderr) {
  }

  private Captured run(StubDaemon daemon, String... args) {
    List<String> all = new ArrayList<>(List.of(args));
    all.add("--daemon-url");
    all.add(daemon.baseUrl());
    all.add("--profile");
    all.add("p");
    return execute(all.toArray(String[]::new));
  }

  private Captured execute(String... args) {
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code;
    try {
      System.setOut(new PrintStream(out));
      System.setErr(new PrintStream(err));
      code = new CommandLine(new PieriaCli()).execute(args);
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
    return new Captured(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  private StubDaemon addable(String created, String check) {
    StubDaemon daemon = StubDaemon.start();
    daemon.stub("/v1/harvesters", 200, HARVESTERS);
    daemon.stub(SOURCES, 201, created);
    daemon.stub(SOURCES + "/s/check", 200, check);
    return daemon;
  }

  private Captured add(StubDaemon daemon, String... extra) {
    List<String> args = new ArrayList<>(List.of("source", "add", "jira", "--id", "s", "--set", "url=https://x", "--secret-env", "token=PATH"));
    args.addAll(List.of(extra));
    return run(daemon, args.toArray(String[]::new));
  }

  @Test
  void addSendsConfigAndSecretsFromTheEnvironment() {
    try (StubDaemon daemon = addable(SOURCE, CHECK_OK)) {
      Captured out = add(daemon);

      assertThat(out.code).as(out.stderr).isZero();
      String body = daemon.lastRequestTo(SOURCES).body();
      assertThat(body).contains("\"id\":\"s\"").contains("\"harvester\":\"jira\"").contains("https://x").contains(System.getenv("PATH"));
    }
  }

  @Test
  void addNeverPrintsTheSecret() {
    try (StubDaemon daemon = addable(SOURCE, CHECK_OK)) {
      Captured out = add(daemon);

      assertThat(out.stdout + out.stderr).doesNotContain(System.getenv("PATH"));
    }
  }

  @Test
  void addRefusesAMissingRequiredFieldWithoutAConsole() {
    try (StubDaemon daemon = addable(SOURCE, CHECK_OK)) {
      Captured out = run(daemon, "source", "add", "jira", "--id", "s", "--secret-env", "token=PATH");

      assertThat(out.code).isEqualTo(1);
      assertThat(out.stderr).contains("url");
      assertThat(daemon.requests()).extracting(StubDaemon.Recorded::method).doesNotContain("POST");
    }
  }

  @Test
  void addRefusesAMissingRequiredSecretWithoutAConsole() {
    try (StubDaemon daemon = addable(SOURCE, CHECK_OK)) {
      Captured out = run(daemon, "source", "add", "jira", "--id", "s", "--set", "url=https://x");

      assertThat(out.code).isEqualTo(1);
      assertThat(out.stderr).contains("token");
    }
  }

  @Test
  void addHasNoOptionThatTakesASecretValue() {
    CommandLine add = new CommandLine(new PieriaCli()).getSubcommands().get("source").getSubcommands().get("add");

    assertThat(add.getCommandSpec().options()).flatExtracting(option -> List.of(option.names())).doesNotContain("--secret", "--token", "--password");
  }

  @Test
  void addPrintsWarningsAndNextSteps() {
    try (StubDaemon daemon = addable(source("[\"Text will be sent elsewhere\"]", null), CHECK_OK)) {
      Captured out = add(daemon);

      assertThat(out.stdout).contains("Text will be sent elsewhere")
        .contains("pieria source run s --dry-run").contains("pieria source resume s");
    }
  }

  @Test
  void addRunsCheckAndReportsFailure() {
    try (StubDaemon daemon = addable(SOURCE, "{\"ok\":false,\"errorClass\":\"auth\",\"message\":\"bad token\"}")) {
      Captured out = add(daemon);

      assertThat(out.code).isEqualTo(1);
      assertThat(out.stderr).contains("bad token");
      assertThat(out.stdout + out.stderr).contains("pieria source secret s");
    }
  }

  @Test
  void addDefaultsToAPausedSource() {
    try (StubDaemon daemon = addable(SOURCE, CHECK_OK)) {
      add(daemon);

      assertThat(daemon.lastRequestTo(SOURCES).body()).doesNotContain("\"start\":true");
    }
  }

  @Test
  void addPassesStartAndSchedule() {
    try (StubDaemon daemon = addable(SOURCE, CHECK_OK)) {
      add(daemon, "--start", "--interval", "30m", "--mode", "raw", "--max-items", "5", "--max-chars", "9");

      assertThat(daemon.lastRequestTo(SOURCES).body()).contains("\"start\":true").contains("\"intervalSeconds\":1800")
        .contains("\"mode\":\"raw\"").contains("\"maxItemsPerRun\":5").contains("\"maxCharsPerRun\":9");
    }
  }

  @Test
  void addWithAnUnknownHarvesterExitsFour() {
    try (StubDaemon daemon = addable(SOURCE, CHECK_OK)) {
      assertThat(run(daemon, "source", "add", "nope", "--id", "s").code).isEqualTo(4);
    }
  }

  @Test
  void listPrintsSources() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub(SOURCES, 200, "{\"sources\":[" + SOURCE + "]}");

      assertThat(run(daemon, "source", "list").stdout).contains("s").contains("jira").contains("paused");
    }
  }

  @Test
  void showNamesSecretsWithoutValues() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub(SOURCES + "/s", 200, SOURCE);

      assertThat(run(daemon, "source", "show", "s").stdout).contains("token").contains("https://x");
    }
  }

  @Test
  void showFlagsABlockedSweep() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub(SOURCES + "/s", 200, source("[]", 3));

      assertThat(run(daemon, "source", "show", "s").stdout).contains("3").contains("pieria source accept-sweep s");
    }
  }

  @Test
  void runSubmitsATask() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub(SOURCES + "/s/run", 202, "{\"taskId\":\"T1\"}");

      Captured out = run(daemon, "source", "run", "s");

      assertThat(out.code).isZero();
      assertThat(out.stdout).contains("T1");
      assertThat(daemon.lastRequestTo(SOURCES + "/s/run").body()).contains("\"dryRun\":false");
    }
  }

  private static String task(String status, String result) {
    return """
      {"status":"%s","kind":"harvest","profile":"p","lanes":[],"startedAtEpochMs":1,"errorKind":null,"errorMessage":null,
       "result":%s,"finishedAtEpochMs":2,"durationMs":1,"resultSummary":null,"retryable":false}""".formatted(status, result);
  }

  @Test
  void dryRunWaitsAndPrintsCounts() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub(SOURCES + "/s/run", 202, "{\"taskId\":\"T1\"}");
      daemon.stubSequence("/v1/tasks/T1", task("RUNNING", "null"),
        task("SUCCEEDED", "{\"status\":\"succeeded\",\"itemsSeen\":42,\"itemsChanged\":7,\"chars\":1234,\"estimatedChunks\":9}"));

      Captured out = run(daemon, "source", "run", "s", "--dry-run");

      assertThat(out.code).as(out.stderr).isZero();
      assertThat(daemon.lastRequestTo(SOURCES + "/s/run").body()).contains("\"dryRun\":true");
      assertThat(out.stdout).contains("42").contains("7").contains("1234").contains("9");
    }
  }

  @Test
  void aFailedRunExitsOneWhenWaiting() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub(SOURCES + "/s/run", 202, "{\"taskId\":\"T1\"}");
      daemon.stubSequence("/v1/tasks/T1", task("SUCCEEDED", "{\"status\":\"failed\",\"itemsSeen\":0,\"itemsChanged\":0,\"chars\":0,\"estimatedChunks\":0}"));
      daemon.stub(SOURCES + "/s", 200, SOURCE);

      assertThat(run(daemon, "source", "run", "s", "--wait").code).isEqualTo(1);
    }
  }

  @Test
  void updateSendsOnlyWhatChanged() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub(SOURCES + "/s", 200, SOURCE);

      Captured out = run(daemon, "source", "update", "s", "--interval", "1h");

      assertThat(out.code).as(out.stderr).isZero();
      StubDaemon.Recorded request = daemon.lastRequestTo(SOURCES + "/s");
      assertThat(request.method()).isEqualTo("PATCH");
      assertThat(request.body()).contains("\"intervalSeconds\":3600").doesNotContain("config").doesNotContain("mode");
    }
  }

  @Test
  void updateConvertsSettingsThroughTheManifest() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub(SOURCES + "/s", 200, SOURCE);
      daemon.stub("/v1/harvesters", 200, HARVESTERS);

      run(daemon, "source", "update", "s", "--set", "url=https://y", "--reset-cursor");

      assertThat(daemon.lastRequestTo(SOURCES + "/s").body()).contains("https://y").contains("\"resetCursor\":true");
    }
  }

  @Test
  void pauseResumeAndAcceptSweepCallTheDaemon() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub(SOURCES + "/s/pause", 200, SOURCE);
      daemon.stub(SOURCES + "/s/resume", 200, SOURCE);
      daemon.stub(SOURCES + "/s/accept-sweep", 200, SOURCE);

      assertThat(run(daemon, "source", "pause", "s").code).isZero();
      assertThat(run(daemon, "source", "resume", "s").code).isZero();
      assertThat(run(daemon, "source", "accept-sweep", "s").code).isZero();

      assertThat(daemon.lastRequestTo(SOURCES + "/s/accept-sweep").method()).isEqualTo("POST");
    }
  }

  @Test
  void runsListsRecentRuns() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub(SOURCES + "/s/runs", 200, """
        {"runs":[{"id":"r","dryRun":false,"status":"failed","startedAt":"2026-03-01T00:00:00Z","finishedAt":"2026-03-01T00:01:00Z",
         "itemsSeen":3,"itemsChanged":1,"itemsDeleted":0,"memoriesStored":2,"chars":10,"errorClass":"auth","errorMessage":"denied","stderrTail":""}]}""");

      assertThat(run(daemon, "source", "runs", "s").stdout).contains("failed").contains("denied");
    }
  }

  @Test
  void checkExitsOneOnFailure() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub(SOURCES + "/s/check", 200, "{\"ok\":false,\"errorClass\":\"auth\",\"message\":\"denied\"}");

      Captured out = run(daemon, "source", "check", "s");

      assertThat(out.code).isEqualTo(1);
      assertThat(out.stderr).contains("denied");
    }
  }

  @Test
  void removePassesForgetMemories() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub(SOURCES + "/s", 204, "");

      Captured out = run(daemon, "source", "remove", "s", "--forget-memories", "--yes");

      assertThat(out.code).as(out.stderr).isZero();
      StubDaemon.Recorded request = daemon.lastRequestTo(SOURCES + "/s");
      assertThat(request.method()).isEqualTo("DELETE");
      assertThat(request.rawQuery()).contains("forgetMemories=true");
    }
  }

  @Test
  void removeRefusesWithoutConfirmation() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub(SOURCES + "/s", 204, "");

      assertThat(run(daemon, "source", "remove", "s").code).isEqualTo(1);
      assertThat(daemon.requests()).extracting(StubDaemon.Recorded::method).doesNotContain("DELETE");
    }
  }

  @Test
  void secretReadsTheEnvironment() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub(SOURCES + "/s/secrets", 204, "");

      Captured out = run(daemon, "source", "secret", "s", "--secret-env", "token=PATH");

      assertThat(out.code).as(out.stderr).isZero();
      assertThat(daemon.lastRequestTo(SOURCES + "/s/secrets").body()).contains(System.getenv("PATH"));
    }
  }

  @Test
  void missingEnvironmentVariableIsAnError() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub(SOURCES + "/s/secrets", 204, "");

      Captured out = run(daemon, "source", "secret", "s", "--secret-env", "token=NO_SUCH_VARIABLE_FOR_TESTS");

      assertThat(out.code).isEqualTo(1);
      assertThat(daemon.requests()).extracting(StubDaemon.Recorded::method).doesNotContain("PUT");
    }
  }

  @Test
  void unknownSourceExitsFour() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub(SOURCES + "/absent", 404, "{\"error\":\"not_found\",\"message\":\"source not found\"}");

      assertThat(run(daemon, "source", "show", "absent").code).isEqualTo(4);
    }
  }

  private static final String SIGN_IN_HARVESTERS = """
    {"harvesters":[{"id":"teams","name":"Teams","version":"1","capabilities":["incremental","oauth-device-code"],"network":[],"fields":[
      {"key":"clientId","kind":"string","label":"Client id","hint":null,"required":true,"options":[],"defaultValue":null},
      {"key":"refreshToken","kind":"secret","label":"Refresh token","hint":null,"required":false,"options":[],"defaultValue":null}
    ]}]}""";

  private static String authorization(String status, String errorClass, String error) {
    return """
      {"status":"%s","verificationUri":"https://microsoft.com/devicelogin","userCode":"ABCD",
       "message":"To sign in, open https://microsoft.com/devicelogin and enter ABCD","expiresAt":"%s",
       "errorClass":%s,"error":%s}""".formatted(status, java.time.Instant.now().plusSeconds(900),
      errorClass == null ? "null" : "\"" + errorClass + "\"", error == null ? "null" : "\"" + error + "\"");
  }

  @Test
  void authorizePrintsTheMessageAndWaits() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stubSequence(SOURCES + "/s/authorize", authorization("pending", null, null), authorization("authorized", null, null));

      Captured out = run(daemon, "source", "authorize", "s");

      assertThat(out.code).as(out.stderr).isZero();
      assertThat(out.stdout + out.stderr).contains("To sign in, open https://microsoft.com/devicelogin and enter ABCD")
        .contains("Signed in.");
      assertThat(daemon.requests()).extracting(StubDaemon.Recorded::method).containsSubsequence("POST", "GET");
    }
  }

  @Test
  void authorizeFailureExitsOne() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stubSequence(SOURCES + "/s/authorize", authorization("pending", null, null), authorization("failed", "auth", "declined"));

      Captured out = run(daemon, "source", "authorize", "s");

      assertThat(out.code).isEqualTo(1);
      assertThat(out.stdout + out.stderr).contains("Sign-in failed (auth): declined");
    }
  }

  @Test
  void addSkipsTheCheckForASignInHarvester() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/harvesters", 200, SIGN_IN_HARVESTERS);
      daemon.stub(SOURCES, 201, SOURCE.replace("\"jira\"", "\"teams\""));
      daemon.stub(SOURCES + "/s/check", 200, CHECK_OK);

      Captured out = run(daemon, "source", "add", "teams", "--id", "s", "--set", "clientId=abc");

      assertThat(out.code).as(out.stderr).isZero();
      assertThat(daemon.requests()).extracting(StubDaemon.Recorded::path).noneMatch(path -> path.endsWith("/check"));
      assertThat(out.stdout + out.stderr).contains("pieria source authorize s");
    }
  }

  private static final String CUSTOM_SIGN_IN = """
    {"harvesters":[{"id":"crm","name":"CRM","version":"1","capabilities":["oauth-device-code"],"network":[],"fields":[
      {"key":"clientSecret","kind":"secret","label":"Client secret","hint":null,"required":true,"options":[],"defaultValue":null},
      {"key":"session","kind":"secret","label":"Session","hint":null,"required":false,"options":[],"defaultValue":null}
    ]}]}""";

  @Test
  void signInHarvesterWithItsOwnSecretNamesStillSkipsTheCheck() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/harvesters", 200, CUSTOM_SIGN_IN);
      daemon.stub(SOURCES, 201, SOURCE.replace("\"jira\"", "\"crm\""));
      daemon.stub(SOURCES + "/s/check", 200, CHECK_OK);

      Captured out = run(daemon, "source", "add", "crm", "--id", "s", "--secret-env", "clientSecret=PATH");

      assertThat(out.code).as(out.stderr).isZero();
      assertThat(daemon.requests()).extracting(StubDaemon.Recorded::path).noneMatch(path -> path.endsWith("/check"));
    }
  }

  @Test
  void signInHarvesterWithEverySecretGivenIsChecked() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/harvesters", 200, CUSTOM_SIGN_IN);
      daemon.stub(SOURCES, 201, SOURCE.replace("\"jira\"", "\"crm\""));
      daemon.stub(SOURCES + "/s/check", 200, CHECK_OK);

      Captured out = run(daemon, "source", "add", "crm", "--id", "s", "--secret-env", "clientSecret=PATH", "--secret-env", "session=HOME");

      assertThat(out.code).as(out.stderr).isZero();
      assertThat(daemon.requests()).extracting(StubDaemon.Recorded::path).anyMatch(path -> path.endsWith("/check"));
    }
  }
}
