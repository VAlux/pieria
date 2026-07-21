package dev.alvo.pieria.cli.command;

import dev.alvo.pieria.cli.PieriaCli;
import dev.alvo.pieria.cli.testsupport.StubDaemon;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileAuditCommandTests {
  @Test
  void rendersFilteredPageAndNextCursor() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/audit", 200, """
        {"events":[{"id":"e1","eventType":"http","operation":"recall","requestId":"r1",
        "client":"gateway","harness":"codex","channel":"mcp","startedAt":"2026-01-01T00:00:00Z",
        "completedAt":"2026-01-01T00:00:01Z","durationMs":12,"httpStatus":200,"outcome":"success",
        "requestBytes":10,"requestTruncated":false,"responseBytes":20,"responseTruncated":false,
        "responsePreview":"remembered answer"}],"nextCursor":"next-token"}
        """);

      Captured result = execute("profile", "audit", "alpha", "--search", "tea time",
        "--operation", "recall", "--harness", "codex", "--limit", "25",
        "--daemon-url", daemon.baseUrl());

      assertThat(result.code()).isZero();
      assertThat(result.out()).contains("recall", "codex/mcp", "remembered answer", "--cursor next-token");
      StubDaemon.Recorded request = daemon.lastRequestTo("/audit");
      assertThat(request.rawQuery()).contains("q=tea+time", "operation=recall", "harness=codex", "limit=25");
    }
  }

  @Test
  void jsonDetailReturnsCompleteDaemonContract() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/audit/e1", 200, """
        {"id":"e1","eventType":"http","operation":"recall","requestId":"r1","client":"cli",
        "channel":"cli","startedAt":"2026-01-01T00:00:00Z","completedAt":"2026-01-01T00:00:01Z",
        "durationMs":12,"httpStatus":200,"outcome":"success","metadata":"{}",
        "requestBody":"{\\"query\\":\\"tea\\"}","requestBytes":15,"requestSha256":"abc",
        "requestTruncated":false,"responseBody":"{\\"answer\\":\\"yes\\"}","responseBytes":16,
        "responseSha256":"def","responseTruncated":false}
        """);

      Captured result = execute("profile", "audit", "alpha", "--id", "e1", "--json",
        "--daemon-url", daemon.baseUrl());
      assertThat(result.code()).isZero();
      assertThat(result.out()).contains("\"operation\":\"recall\"", "\"responseBody\":\"{\\\"answer\\\":\\\"yes\\\"}\"");
    }
  }

  private static Captured execute(String... args) {
    PrintStream oldOut = System.out, oldErr = System.err;
    ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(out));
      System.setErr(new PrintStream(err));
      int code = new CommandLine(new PieriaCli()).execute(args);
      return new Captured(code, out.toString(), err.toString());
    } finally {
      System.setOut(oldOut);
      System.setErr(oldErr);
    }
  }

  private record Captured(int code, String out, String err) {}
}
