package dev.alvo.pieria.cli.modules.hook;

import dev.alvo.pieria.cli.modules.daemon.DaemonUrls;
import dev.alvo.pieria.cli.modules.update.BuildInfo;
import dev.alvo.pieria.client.ClientIdentity;
import dev.alvo.pieria.client.HealthClient;
import dev.alvo.pieria.client.ProfileClient;
import dev.alvo.pieria.mapping.ProfileResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * Everything a hook command needs to reach the daemon: the resolved profile, the daemon URL, and
 * clients tagged with a hook {@link ClientIdentity} for audit attribution.
 *
 * <p>The environment is injected rather than read statically so the resolution rules are directly
 * testable, matching {@link ProfileResolver} and
 * {@link dev.alvo.pieria.cli.modules.harness.PathResolver}. Reading the harness's own variables
 * here — rather than having the installer interpolate them into a command string — is the whole
 * point of the design: {@code $VAR} expansion would require a shell, which Windows lacks.
 */
public final class HookContext {

  private final Function<String, String> env;
  private final Path workingDir;
  private final String harnessId;

  public HookContext(Function<String, String> env, Path workingDir, String harnessId) {
    this.env = env;
    this.workingDir = workingDir;
    this.harnessId = harnessId;
  }

  /** Production factory: real environment, current working directory. */
  public static HookContext create(String harnessId) {
    return new HookContext(System::getenv, Path.of("").toAbsolutePath(), harnessId);
  }

  public String harnessId() {
    return harnessId;
  }

  /**
   * The profile slug. An injected {@code PIERIA_PROFILE} wins outright; otherwise defer to the
   * production resolver, which keeps git-remote derivation. {@code ProfileResolver}'s git reader is
   * private to its own factory, so this cannot be expressed as one constructor call.
   */
  public String profile() {
    String override = env.apply("PIERIA_PROFILE");
    return override != null && !override.isBlank()
      ? ProfileResolver.normalize(override)
      : ProfileResolver.create(workingDir).resolve();
  }

  public String daemonUrl() {
    return DaemonUrls.resolve(null, env);
  }

  public ProfileClient profiles() {
    return new ProfileClient(daemonUrl(), identity());
  }

  public HealthClient health() {
    return new HealthClient(daemonUrl(), identity());
  }

  /** A non-blank environment value, or empty. */
  public Optional<String> env(String key) {
    String value = env.apply(key);
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
  }

  /** The first of the harness's transcript variables that points at an existing file. */
  public Optional<Path> firstExistingTranscript(HarnessHookSpec spec) {
    for (String key : spec.transcriptEnvKeys()) {
      Optional<Path> candidate = env(key).map(Path::of).filter(Files::isRegularFile);
      if (candidate.isPresent()) {
        return candidate;
      }
    }
    return Optional.empty();
  }

  /** The harness session id, or null so the daemon generates one. */
  public String sessionId(HarnessHookSpec spec) {
    return spec.sessionIdEnvKey() == null ? null : env(spec.sessionIdEnvKey()).orElse(null);
  }

  private ClientIdentity identity() {
    return new ClientIdentity("hook", harnessId, "hook", BuildInfo.current());
  }
}
