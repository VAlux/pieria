package dev.alvo.pieria.config.model;

/**
 * Top-level shape of a Pieria config file ({@code config.toml} in the OS config dir, or a
 * project's {@code .pieria/config.toml}). Both layers share this schema; precedence
 * (project &gt; global &gt; code defaults) is resolved by deep-merging the raw trees with
 * {@code ConfigMerge} before binding.
 *
 * <p>{@code discovery} is consumed by the CLI; {@code pieria} is the daemon-overridable subset
 * that the CLI pushes to {@code PUT /v1/profiles/{name}/config}.
 */
public record PieriaConfigFile(DiscoveryConfig discovery, DaemonOverrides pieria) {

  public PieriaConfigFile {
    discovery = discovery == null ? DiscoveryConfig.defaults() : discovery;
    pieria = pieria == null ? new DaemonOverrides(null, null) : pieria;
  }

  /** Equal to an absent/empty config file: discovery defaults, no daemon overrides. */
  public static PieriaConfigFile empty() {
    return new PieriaConfigFile(null, null);
  }
}
