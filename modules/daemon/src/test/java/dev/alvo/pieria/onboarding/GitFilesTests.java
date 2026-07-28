package dev.alvo.pieria.onboarding;

import dev.alvo.pieria.code.CodeIndexingService.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the production {@code git ls-files} readers against a real repository — the injected
 * seam used by the other discovery tests bypasses them entirely.
 *
 * <p>The case that matters: a freshly {@code git init}ed project with nothing committed yet. Git
 * exits 0 with no output there, so a {@code --cached}-only listing looks identical to "this project
 * has no files" and every onboarding lane silently discovers nothing.
 */
class GitFilesTests {

  private static void git(Path dir, String... args) throws IOException, InterruptedException {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    Process process = new ProcessBuilder(command).directory(dir.toFile())
      .redirectErrorStream(true).start();
    process.getInputStream().readAllBytes();
    assertThat(process.waitFor()).isZero();
  }

  /** Seed a git repo whose files are all untracked, with a .gitignore excluding build output. */
  private static void initRepoWithUncommittedFiles(Path proj) throws IOException, InterruptedException {
    try {
      git(proj, "init");
    } catch (IOException e) {
      assumeTrue(false, "git is not available on this machine");
    }
    Files.writeString(proj.resolve(".gitignore"), "/target/\n/runs/\n");
    Files.writeString(proj.resolve("README.md"), "# Readme");
    Files.writeString(proj.resolve("notes.txt"), "notes");
    Files.createDirectories(proj.resolve("src"));
    Files.writeString(proj.resolve("src/App.java"), "class App {}");
    Files.createDirectories(proj.resolve("target"));
    Files.writeString(proj.resolve("target/generated.md"), "# Build output");
    Files.writeString(proj.resolve("target/Generated.java"), "class Generated {}");
  }

  @Test
  void discoversMarkdownInARepoWithNothingCommittedYet(@TempDir Path proj) throws Exception {
    initRepoWithUncommittedFiles(proj);

    List<String> found = MarkdownDiscovery.create(proj).discover(false).stream()
      .map(d -> d.relative().toString()).toList();

    assertThat(found).containsExactly("README.md");
  }

  @Test
  void discoversTextInARepoWithNothingCommittedYet(@TempDir Path proj) throws Exception {
    initRepoWithUncommittedFiles(proj);

    List<String> found = TextDiscovery.create(proj).discover().stream()
      .map(d -> d.relative().toString()).toList();

    assertThat(found).containsExactly("notes.txt");
  }

  @Test
  void discoversSourceCodeInARepoWithNothingCommittedYet(@TempDir Path proj) throws Exception {
    initRepoWithUncommittedFiles(proj);

    List<String> found = CodeDiscovery.create(proj, dev.alvo.pieria.config.model.DiscoveryConfig.defaults())
      .discover().stream().map(SourceFile::repoRelPath).toList();

    assertThat(found).containsExactly("src/App.java");
  }

  @Test
  void stillHonoursGitignoreWhenNothingIsCommitted(@TempDir Path proj) throws Exception {
    initRepoWithUncommittedFiles(proj);

    List<String> markdown = MarkdownDiscovery.create(proj).discover(false).stream()
      .map(d -> d.relative().toString()).toList();
    List<String> code = CodeDiscovery.create(proj, dev.alvo.pieria.config.model.DiscoveryConfig.defaults())
      .discover().stream().map(SourceFile::repoRelPath).toList();

    assertThat(markdown).doesNotContain("target/generated.md");
    assertThat(code).doesNotContain("target/Generated.java");
  }

  @Test
  void discoversCommittedFilesToo(@TempDir Path proj) throws Exception {
    initRepoWithUncommittedFiles(proj);
    git(proj, "add", "README.md");
    git(proj, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "seed");
    Files.writeString(proj.resolve("CHANGELOG.md"), "# Changes");

    List<String> found = MarkdownDiscovery.create(proj).discover(false).stream()
      .map(d -> d.relative().toString()).toList();

    assertThat(found).containsExactly("CHANGELOG.md", "README.md");
  }
}
