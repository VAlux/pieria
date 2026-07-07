package dev.alvo.pieria.client;

import dev.alvo.pieria.config.model.DaemonOverrides;
import dev.alvo.pieria.config.toml.ConfigCodec;

import java.time.Duration;

public final class ConfigClient {
  private final DaemonTransport transport;

  ConfigClient(DaemonTransport transport) {
    this.transport = transport;
  }

  public ConfigClient(String baseUrl) {
    this(new DaemonTransport(baseUrl));
  }

  private String path(String profile) {
    return "/v1/profiles/" + DaemonTransport.segment(profile) + "/config";
  }

  public DaemonOverrides get(String profile) {
    return transport.parseConfig(transport.get(path(profile), Duration.ofSeconds(10)), DaemonOverrides.class);
  }

  public DaemonOverrides put(String profile, DaemonOverrides overrides) {
    return transport.parseConfig(
      transport.put(path(profile), overrides, Duration.ofSeconds(10), true),
      DaemonOverrides.class);
  }

  public String toJson(DaemonOverrides overrides) {
    return ConfigCodec.toJson(overrides);
  }
}
