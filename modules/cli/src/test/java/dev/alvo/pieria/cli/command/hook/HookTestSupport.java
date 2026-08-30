package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.PieriaCli;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Shared scaffolding for hook command tests: run the CLI with a given stdin payload and capture
 * what lands on stderr. Extracted out of {@code HookCommandTests} so
 * {@code CcPostToolUseCommandTests} can reuse it rather than duplicating it.
 */
final class HookTestSupport {

  private HookTestSupport() {
  }

  record Run(int exitCode, String stderr) {
  }

  static Run runWithStdin(String stdin, String... args) {
    InputStream originalIn = System.in;
    PrintStream originalErr = System.err;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try {
      System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
      System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
      int exitCode = new CommandLine(new PieriaCli()).execute(args);
      return new Run(exitCode, captured.toString(StandardCharsets.UTF_8));
    } finally {
      System.setIn(originalIn);
      System.setErr(originalErr);
    }
  }
}
