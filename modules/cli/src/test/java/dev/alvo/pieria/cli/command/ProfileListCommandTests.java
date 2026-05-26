package dev.alvo.pieria.cli.command;

import com.sun.net.httpserver.HttpServer;
import dev.alvo.pieria.cli.PieriaCli;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@code pieria profile list} against a tiny in-process HTTP server standing in for the
 * daemon, plus the daemon-down exit-code path. Network-free (loopback only).
 */
class ProfileListCommandTests {

  private HttpServer server;
  private String baseUrl;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void listPrintsProfilesAndCounts() {
    server.createContext("/v1/profiles", exchange -> {
      byte[] body = ("""
        {"profiles":[\
        {"name":"alpha","createdAt":"2026-01-01T00:00:00Z","memoryCount":3},\
        {"name":"beta","createdAt":"2026-02-02T00:00:00Z","memoryCount":0}]}""")
        .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();

    Captured out = run("profile", "list", "--daemon-url", baseUrl);

    assertThat(out.code).isEqualTo(0);
    assertThat(out.stdout).contains("alpha").contains("beta").contains("3");
  }

  @Test
  void exitsWithCode3WhenDaemonUnreachable() {
    // Server created but never started: connection is refused on the bound port.
    Captured out = run("profile", "list", "--daemon-url", baseUrl);

    assertThat(out.code).isEqualTo(3);
    assertThat(out.stderr).contains("not reachable");
  }

  private Captured run(String... args) {
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

  private record Captured(int code, String stdout, String stderr) {
  }
}
