package dev.alvo.pieria.cli.command;

import dev.alvo.pieria.cli.PieriaCli;
import dev.alvo.pieria.cli.testsupport.StubDaemon;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The profile tree from the CLI: {@code profile create --parent}, {@code profile parent} and the tree
 * that {@code profile list} prints, against the {@link StubDaemon}.
 */
class ProfileTreeCommandTests {

  private static final String SUMMARY = "{\"name\":\"%s\",\"createdAt\":\"2026-01-01T00:00:00Z\",\"memoryCount\":0%s}";

  private static String summary(String name, String parent) {
    return SUMMARY.formatted(name, parent == null ? "" : ",\"parent\":\"" + parent + "\"");
  }

  @Test
  void createWithParentSendsTheParentInTheBody() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/profiles/leaf", 201, summary("leaf", "root"));

      Captured out = run("profile", "create", "leaf", "--parent", "root", "--daemon-url", daemon.baseUrl());

      assertThat(out.code).isZero();
      assertThat(out.stdout).contains("Created profile leaf under root");
      StubDaemon.Recorded request = daemon.lastRequestTo("/v1/profiles/leaf");
      assertThat(request.method()).isEqualTo("PUT");
      assertThat(request.body()).contains("\"parent\":\"root\"");
    }
  }

  @Test
  void createUnderAMissingParentExitsWithCode4() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/profiles/leaf", 404, "{\"error\":\"not_found\",\"message\":\"No profile named 'root'\"}");

      Captured out = run("profile", "create", "leaf", "--parent", "root", "--daemon-url", daemon.baseUrl());

      assertThat(out.code).isEqualTo(4);
      assertThat(out.stderr).contains("No profile named 'root'");
    }
  }

  @Test
  void parentMovesAProfileUnderAnother() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/profiles/leaf", 200, summary("leaf", "root"));

      Captured out = run("profile", "parent", "leaf", "root", "--daemon-url", daemon.baseUrl());

      assertThat(out.code).isZero();
      assertThat(out.stdout).contains("Profile leaf is now under root");
      StubDaemon.Recorded request = daemon.lastRequestTo("/v1/profiles/leaf");
      assertThat(request.method()).isEqualTo("PATCH");
      assertThat(request.body()).contains("\"parent\":\"root\"");
    }
  }

  @Test
  void parentNoneMovesAProfileToTopLevel() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/profiles/leaf", 200, summary("leaf", null));

      Captured out = run("profile", "parent", "leaf", "--none", "--daemon-url", daemon.baseUrl());

      assertThat(out.code).isZero();
      assertThat(out.stdout).contains("Profile leaf is now at top level");
      assertThat(daemon.lastRequestTo("/v1/profiles/leaf").body()).doesNotContain("\"root\"");
    }
  }

  @Test
  void parentRequiresExactlyOneOfATargetOrNone() {
    assertThat(run("profile", "parent", "leaf").code).isEqualTo(2);
    assertThat(run("profile", "parent", "leaf", "root", "--none").code).isEqualTo(2);
  }

  @Test
  void aCycleIsRefusedWithTheDaemonsMessage() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/profiles/root", 409,
        "{\"error\":\"conflict\",\"message\":\"Profile 'leaf' is inside 'root', so it cannot become its parent\"}");

      Captured out = run("profile", "parent", "root", "leaf", "--daemon-url", daemon.baseUrl());

      assertThat(out.code).isEqualTo(1);
      assertThat(out.stderr).contains("cannot become its parent");
    }
  }

  @Test
  void listPrintsChildrenUnderTheirParent() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/v1/profiles", 200, "{\"profiles\":["
        + summary("alpha", null) + "," + summary("beta", "alpha") + "," + summary("gamma", "alpha") + ","
        + summary("delta", "gamma") + "," + summary("omega", null) + "]}");

      Captured out = run("profile", "list", "--daemon-url", daemon.baseUrl());

      assertThat(out.code).isZero();
      assertThat(out.stdout.lines().map(line -> line.substring(0, 32).stripTrailing()).toList()).containsExactly(
        "PROFILE",
        "alpha",
        "├─ beta",
        "└─ gamma",
        "   └─ delta",
        "omega");
    }
  }

  private Captured run(String... args) {
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code;
    try {
      System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
      System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
      code = new CommandLine(new PieriaCli()).execute(args);
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
    return new Captured(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  private record Captured(int code, String stdout, String stderr) {
  }
}
