package dev.alvo.pieria.mapping;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic profile-name resolver shared by the MCP gateway and harness hooks (SPEC §10.2,
 * phase-4 step 4). A given working directory always maps to the same profile name regardless
 * of which component calls it.
 *
 * <h2>Resolution precedence (highest first)</h2>
 * <ol>
 *   <li>Explicit override: {@code PIERIA_PROFILE} env var, or a non-blank string passed
 *       directly to the constructor's {@code explicitProfile} argument.</li>
 *   <li>Git remote-derived project name: last path segment of
 *       {@code git config --get remote.origin.url}, with a trailing {@code .git} stripped.</li>
 *   <li>Working-directory basename.</li>
 * </ol>
 *
 * <p>The raw name from whichever source wins is then passed through {@link #normalize(String)}
 * (lower-case, non-{@code [a-z0-9-]} runs → single hyphen, leading/trailing hyphens trimmed,
 * empty → {@code "default"}). Normalization is deterministic and source-agnostic so the gateway
 * and hooks always agree.
 *
 * <h2>Testability</h2>
 * Env lookup and git execution are injected seams ({@code Function<String,String>} and
 * {@link GitRemoteReader}), so no real system calls are needed in tests. Use
 * {@link #create(Path)} for production wiring.
 */
public final class ProfileResolver {

  /** Reads the git remote URL for a given working directory, or empty on any failure. */
  @FunctionalInterface
  public interface GitRemoteReader {
    Optional<String> remoteUrl(Path workingDir);
  }

  private static final String ENV_KEY = "PIERIA_PROFILE";

  private final String explicitProfile;
  private final Function<String, String> env;
  private final GitRemoteReader gitRemoteReader;
  private final Path workingDir;

  /**
   * Full constructor for production and test use.
   *
   * @param explicitProfile optional programmatic override (null or blank ⇒ ignored)
   * @param env             env-var lookup function (key → value or null)
   * @param gitRemoteReader returns the remote.origin.url for a directory, or empty
   * @param workingDir      the project directory whose profile name is being resolved
   */
  public ProfileResolver(String explicitProfile,
                         Function<String, String> env,
                         GitRemoteReader gitRemoteReader,
                         Path workingDir) {
    this.explicitProfile = explicitProfile;
    this.env = env;
    this.gitRemoteReader = gitRemoteReader;
    this.workingDir = workingDir;
  }

  /**
   * Production factory. Wires real {@link System#getenv} and a {@link ProcessBuilder}-based
   * git reader (fail-closed: any I/O error or non-zero exit ⇒ {@link Optional#empty()}).
   *
   * @param workingDir the project directory
   * @return a resolver ready for production use
   */
  public static ProfileResolver create(Path workingDir) {
    return new ProfileResolver(null, System::getenv, realGitReader(), workingDir);
  }

  /**
   * Resolves the profile name for {@link #workingDir} using the precedence rules above.
   * The returned value is always normalized (never null, never empty).
   */
  public String resolve() {
    // 1. Explicit programmatic override.
    if (explicitProfile != null && !explicitProfile.isBlank()) {
      return normalize(explicitProfile);
    }
    // 2. PIERIA_PROFILE env var.
    String fromEnv = env.apply(ENV_KEY);
    if (fromEnv != null && !fromEnv.isBlank()) {
      return normalize(fromEnv);
    }
    // 3. Git remote-derived name.
    Optional<String> remoteUrl = gitRemoteReader.remoteUrl(workingDir);
    if (remoteUrl.isPresent()) {
      String repoName = parseRepoName(remoteUrl.get());
      if (!repoName.isBlank()) {
        return normalize(repoName);
      }
    }
    // 4. Working-directory basename.
    Path fileName = workingDir.getFileName();
    String basename = (fileName != null) ? fileName.toString() : "";
    return normalize(basename);
  }

  // -------------------------------------------------------------------------
  // Pure static helpers — exposed for reuse by the gateway, hooks, and tests.
  // -------------------------------------------------------------------------

  /**
   * Extracts the repository name from a git remote URL. Handles:
   * <ul>
   *   <li>HTTPS: {@code https://github.com/org/repo.git}, {@code https://host/org/repo}</li>
   *   <li>SCP-style SSH: {@code git@github.com:org/repo.git}</li>
   *   <li>SSH URL: {@code ssh://git@host/org/repo}, {@code ssh://git@host/org/repo.git}</li>
   *   <li>Trailing slashes, no {@code .git} suffix.</li>
   * </ul>
   * Returns the last non-empty path segment with a trailing {@code .git} stripped.
   * Returns an empty string if the URL cannot be parsed.
   */
  public static String parseRepoName(String remoteUrl) {
    if (remoteUrl == null || remoteUrl.isBlank()) {
      return "";
    }
    String url = remoteUrl.strip();

    // SCP-style: git@github.com:org/repo.git — split on the colon, take the path part.
    // This must come before the generic path splitting since the colon is not a path separator.
    Matcher scpMatcher = Pattern.compile("^[^/@:]+@[^:]+:(.+)$").matcher(url);
    if (scpMatcher.matches()) {
      url = scpMatcher.group(1);
    } else {
      // Strip any scheme (https://, ssh://, git://) and authority (user@host).
      Matcher schemeMatcher = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+\\-.]*://[^/]*(/.*)?$").matcher(url);
      if (schemeMatcher.matches()) {
        String path = schemeMatcher.group(1);
        url = (path != null) ? path : "";
      }
    }

    // Strip trailing slashes, then grab the last path segment.
    url = url.replaceAll("/+$", "");
    int lastSlash = url.lastIndexOf('/');
    String segment = (lastSlash >= 0) ? url.substring(lastSlash + 1) : url;

    // Strip a trailing .git suffix.
    if (segment.endsWith(".git")) {
      segment = segment.substring(0, segment.length() - 4);
    }
    return segment;
  }

  /**
   * Normalizes a raw name into a valid profile slug:
   * <ol>
   *   <li>Lower-case.</li>
   *   <li>Replace any run of characters outside {@code [a-z0-9-]} with a single {@code -}.</li>
   *   <li>Trim leading and trailing hyphens.</li>
   *   <li>Collapse repeated hyphens to one.</li>
   *   <li>If the result is empty, return {@code "default"}.</li>
   * </ol>
   */
  public static String normalize(String raw) {
    if (raw == null || raw.isBlank()) {
      return "default";
    }
    String lower = raw.toLowerCase(Locale.ROOT);
    // Replace runs of characters outside [a-z0-9-] with a single hyphen.
    String hyphenated = lower.replaceAll("[^a-z0-9-]+", "-");
    // Collapse repeated hyphens.
    String collapsed = hyphenated.replaceAll("-{2,}", "-");
    // Trim leading/trailing hyphens.
    String trimmed = collapsed.replaceAll("^-+|-+$", "");
    return trimmed.isEmpty() ? "default" : trimmed;
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  /** Production git reader: runs {@code git config --get remote.origin.url} via ProcessBuilder. */
  private static GitRemoteReader realGitReader() {
    return workingDir -> {
      try {
        Process process = new ProcessBuilder("git", "config", "--get", "remote.origin.url")
          .directory(workingDir.toFile())
          .redirectErrorStream(true)
          .start();
        String output = new String(process.getInputStream().readAllBytes()).strip();
        int exitCode = process.waitFor();
        if (exitCode != 0 || output.isEmpty()) {
          return Optional.empty();
        }
        return Optional.of(output);
      } catch (IOException | InterruptedException e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        return Optional.empty();
      }
    };
  }
}
