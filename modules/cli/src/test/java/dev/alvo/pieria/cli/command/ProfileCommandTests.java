package dev.alvo.pieria.cli.command;

import dev.alvo.pieria.cli.PieriaCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileCommandTests {

  @Test
  void printsNormalizedBasenameWhenNoGitRemote(@TempDir Path tmp) throws IOException {
    // A non-git dir under the system temp resolves to its normalized basename.
    Path dir = Files.createDirectory(tmp.resolve("My_Cool_Repo"));

    PrintStream original = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    int code;
    try {
      System.setOut(new PrintStream(captured));
      code = new CommandLine(new PieriaCli()).execute("profile", "--project-dir", dir.toString());
    } finally {
      System.setOut(original);
    }

    assertThat(code).isEqualTo(0);
    assertThat(captured.toString().strip()).isEqualTo("my-cool-repo");
  }
}
