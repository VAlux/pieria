package dev.alvo.pieria.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * First-run and local model-check behavior. Pull policy is configuration only for now; daemon
 * startup reports what it would do without invoking model downloads implicitly.
 */
@ConfigurationProperties(prefix = "pieria.first-run")
public record FirstRunProperties(@DefaultValue("true") boolean enabled,
                                 @DefaultValue("true") boolean checkModels,
                                 @DefaultValue("never") ModelPullPolicy modelPullPolicy) {

  public enum ModelPullPolicy {
    NEVER,
    IF_MISSING,
    ALWAYS
  }
}
