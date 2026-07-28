package dev.alvo.pieria.onboarding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The production {@code git ls-files} reader shared by every onboarding discovery lane.
 *
 * <p>Enumeration is {@code --cached --others --exclude-standard}: everything git considers part of
 * the working tree — committed files <em>and</em> files that are merely present — minus anything
 * {@code .gitignore} excludes. Listing only {@code --cached} would make a freshly {@code git init}ed
 * project (nothing committed or staged yet) indistinguishable from an empty one: git exits 0 with no
 * output, the caller reads that as "this project has no files", and the whole onboarding run
 * silently discovers nothing. Including untracked-but-not-ignored files also means a README written
 * today is onboardable before it is committed.
 *
 * <p>Fails closed to {@link Optional#empty()} when the directory is not a git repository or git is
 * unavailable, which is the callers' signal to fall back to a plain filesystem walk.
 */
final class GitFiles {

  private GitFiles() {
  }

  /**
   * List repo-relative paths of working-tree files that git does not ignore, optionally narrowed by
   * a pathspec (e.g. {@code "*.md"}); empty when this is not a git repo or git failed.
   */
  static Optional<List<String>> list(Path projectDir, String... pathspec) {
    List<String> command = new java.util.ArrayList<>(
      List.of("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"));
    command.addAll(List.of(pathspec));
    try {
      Process process = new ProcessBuilder(command)
        .directory(projectDir.toFile())
        .redirectErrorStream(true)
        .start();
      byte[] out = process.getInputStream().readAllBytes();
      if (process.waitFor() != 0) {
        return Optional.empty();
      }
      // -z gives NUL-separated paths (safe for spaces/newlines); drop the trailing empty token.
      // A path can repeat when the index holds unmerged stages, so de-duplicate while keeping order.
      Set<String> paths = new LinkedHashSet<>();
      for (String token : new String(out, StandardCharsets.UTF_8).split("\0")) {
        if (!token.isBlank()) {
          paths.add(token);
        }
      }
      return Optional.of(List.copyOf(paths));
    } catch (IOException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }
}
