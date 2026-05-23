package dev.alvo.pieria.mapping;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link ProfileResolver}. All env lookups and git reads use fakes — no real
 * git, no real env, no network.
 */
class ProfileResolverTests {

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Resolver with every seam fully under test control. */
  private static ProfileResolver resolver(String explicitProfile,
                                          String envValue,
                                          Optional<String> remoteUrl,
                                          String dirName) {
    Path dir = dirName != null ? Path.of("/projects", dirName) : Path.of("/projects/unnamed");
    return new ProfileResolver(
      explicitProfile,
      key -> "PIERIA_PROFILE".equals(key) ? envValue : null,
      wd -> remoteUrl,
      dir
    );
  }

  /** Resolver with no explicit arg, no env var, and a supplied remote URL. */
  private static ProfileResolver resolverWithRemote(String remoteUrl, String dirName) {
    return resolver(null, null, Optional.of(remoteUrl), dirName);
  }

  /** Resolver with no explicit arg, no env var, and no remote (directory fallback). */
  private static ProfileResolver resolverNoRemote(String dirName) {
    return resolver(null, null, Optional.empty(), dirName);
  }

  // ---------------------------------------------------------------------------
  // 1. Explicit override wins over everything else
  // ---------------------------------------------------------------------------

  @Test
  void explicitProfileOverridesEnvAndGit() {
    ProfileResolver r = resolver("My Project", "env-profile",
      Optional.of("https://github.com/org/other-repo.git"), "local-dir");
    assertEquals("my-project", r.resolve());
  }

  @Test
  void explicitProfileIsNormalized() {
    ProfileResolver r = resolver("My_Fancy.Project!", null, Optional.empty(), "dir");
    assertEquals("my-fancy-project", r.resolve());
  }

  @Test
  void blankExplicitProfileFallsThrough() {
    // blank explicit → falls through to env
    ProfileResolver r = resolver("   ", "env-name", Optional.empty(), "dir");
    assertEquals("env-name", r.resolve());
  }

  // ---------------------------------------------------------------------------
  // 2. PIERIA_PROFILE env var (when no explicit override)
  // ---------------------------------------------------------------------------

  @Test
  void envVarOverridesGitAndDirectory() {
    ProfileResolver r = resolver(null, "forced-profile",
      Optional.of("https://github.com/org/repo.git"), "local-dir");
    assertEquals("forced-profile", r.resolve());
  }

  @Test
  void envVarIsNormalized() {
    ProfileResolver r = resolver(null, "My Env Profile", Optional.empty(), "dir");
    assertEquals("my-env-profile", r.resolve());
  }

  @Test
  void blankEnvVarFallsThrough() {
    ProfileResolver r = resolver(null, "  ",
      Optional.of("https://github.com/org/repo.git"), "dir");
    // blank env → falls through to git remote
    assertEquals("repo", r.resolve());
  }

  // ---------------------------------------------------------------------------
  // 3. Git remote URL variants
  // ---------------------------------------------------------------------------

  @Test
  void httpsWithDotGit() {
    assertEquals("my-repo", resolverWithRemote("https://github.com/org/my-repo.git", "dir").resolve());
  }

  @Test
  void httpsWithoutDotGit() {
    assertEquals("my-repo", resolverWithRemote("https://github.com/org/my-repo", "dir").resolve());
  }

  @Test
  void httpsWithTrailingSlash() {
    assertEquals("my-repo", resolverWithRemote("https://github.com/org/my-repo/", "dir").resolve());
  }

  @Test
  void scpStyleSshWithDotGit() {
    assertEquals("my-repo", resolverWithRemote("git@github.com:org/my-repo.git", "dir").resolve());
  }

  @Test
  void scpStyleSshWithoutDotGit() {
    assertEquals("my-repo", resolverWithRemote("git@github.com:org/my-repo", "dir").resolve());
  }

  @Test
  void sshUrlSchemeWithDotGit() {
    assertEquals("my-repo", resolverWithRemote("ssh://git@github.com/org/my-repo.git", "dir").resolve());
  }

  @Test
  void sshUrlSchemeWithoutDotGit() {
    assertEquals("my-repo", resolverWithRemote("ssh://git@github.com/org/my-repo", "dir").resolve());
  }

  @Test
  void httpsCustomHostNoOrg() {
    assertEquals("repo", resolverWithRemote("https://internal.host/repo.git", "dir").resolve());
  }

  @Test
  void scpStyleWithCustomHost() {
    assertEquals("pieria", resolverWithRemote("git@bitbucket.org:acme/pieria.git", "dir").resolve());
  }

  // ---------------------------------------------------------------------------
  // 4. Directory basename fallback (no git remote)
  // ---------------------------------------------------------------------------

  @Test
  void noRemoteFallsBackToDirectoryBasename() {
    assertEquals("my-project", resolverNoRemote("my-project").resolve());
  }

  @Test
  void noRemoteDirectoryNameNormalized() {
    assertEquals("my-project", resolverNoRemote("My_Project").resolve());
  }

  // ---------------------------------------------------------------------------
  // 5. parseRepoName — URL shape coverage (static, pure)
  // ---------------------------------------------------------------------------

  @ParameterizedTest(name = "parseRepoName({0}) == {1}")
  @CsvSource({
    "https://github.com/org/repo.git,          repo",
    "https://github.com/org/repo,              repo",
    "https://github.com/org/repo/,             repo",
    "git@github.com:org/repo.git,              repo",
    "git@github.com:org/repo,                  repo",
    "ssh://git@github.com/org/repo.git,        repo",
    "ssh://git@github.com/org/repo,            repo",
    "https://host/just-repo,                   just-repo",
    "https://host/just-repo.git,               just-repo",
    "git@host:just-repo.git,                   just-repo",
  })
  void parseRepoNameVariants(String url, String expected) {
    assertEquals(expected.strip(), ProfileResolver.parseRepoName(url.strip()));
  }

  @Test
  void parseRepoNameNullReturnsEmpty() {
    assertEquals("", ProfileResolver.parseRepoName(null));
  }

  @Test
  void parseRepoNameBlankReturnsEmpty() {
    assertEquals("", ProfileResolver.parseRepoName("   "));
  }

  // ---------------------------------------------------------------------------
  // 6. normalize — all transformation rules
  // ---------------------------------------------------------------------------

  @Test
  void normalizeUpperCase() {
    assertEquals("my-project", ProfileResolver.normalize("My-Project"));
  }

  @Test
  void normalizeUnderscores() {
    assertEquals("my-project", ProfileResolver.normalize("my_project"));
  }

  @Test
  void normalizeDots() {
    assertEquals("my-project", ProfileResolver.normalize("my.project"));
  }

  @Test
  void normalizeSpaces() {
    assertEquals("my-project", ProfileResolver.normalize("my project"));
  }

  @Test
  void normalizeMixedSeparators() {
    assertEquals("my-fancy-project", ProfileResolver.normalize("My_Fancy.Project"));
  }

  @Test
  void normalizeLeadingHyphens() {
    assertEquals("project", ProfileResolver.normalize("--project"));
  }

  @Test
  void normalizeTrailingHyphens() {
    assertEquals("project", ProfileResolver.normalize("project--"));
  }

  @Test
  void normalizeLeadingAndTrailingHyphens() {
    assertEquals("project", ProfileResolver.normalize("-project-"));
  }

  @Test
  void normalizeRepeatedHyphens() {
    assertEquals("a-b", ProfileResolver.normalize("a---b"));
  }

  @Test
  void normalizeMixedRunProducesOneHyphen() {
    assertEquals("a-b", ProfileResolver.normalize("a _.-b"));
  }

  @Test
  void normalizeNullReturnsDefault() {
    assertEquals("default", ProfileResolver.normalize(null));
  }

  @Test
  void normalizeEmptyStringReturnsDefault() {
    assertEquals("default", ProfileResolver.normalize(""));
  }

  @Test
  void normalizeBlankStringReturnsDefault() {
    assertEquals("default", ProfileResolver.normalize("   "));
  }

  @Test
  void normalizeAllSpecialCharsReturnsDefault() {
    assertEquals("default", ProfileResolver.normalize("___...---"));
  }

  // ---------------------------------------------------------------------------
  // 7. Same logical name normalizes identically regardless of source
  // ---------------------------------------------------------------------------

  @Test
  void sameLogicalNameIdenticalAcrossSources() {
    String rawName = "My_Project";
    String expected = ProfileResolver.normalize(rawName);

    // Via explicit arg
    assertEquals(expected, resolver(rawName, null, Optional.empty(), "other-dir").resolve());

    // Via env var
    assertEquals(expected, resolver(null, rawName, Optional.empty(), "other-dir").resolve());

    // Via git remote where repo name is rawName
    assertEquals(expected,
      resolverWithRemote("https://github.com/org/" + rawName + ".git", "other-dir").resolve());

    // Via directory basename
    assertEquals(expected, resolverNoRemote(rawName).resolve());
  }
}
