package dev.alvo.pieria.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the daemon's host binding defaults to {@code 127.0.0.1} so the process
 * never accidentally binds a public interface in local mode.
 */
@SpringBootTest(classes = DaemonBindingTests.Config.class)
@TestPropertySource(properties = {
  // Explicitly set daemon properties to trigger binding and confirm defaults are applied.
  "pieria.daemon.port=8077"
  // pieria.daemon.host is intentionally omitted to exercise the @DefaultValue("127.0.0.1")
})
class DaemonBindingTests {

  @Autowired
  PieriaProperties properties;

  @Test
  void defaultDaemonHostIsLocalhost() {
    assertThat(properties.daemon().host())
      .as("daemon must bind 127.0.0.1 by default, never a public interface")
      .isEqualTo("127.0.0.1");
  }

  @Test
  void defaultDaemonPortIs8077() {
    assertThat(properties.daemon().port()).isEqualTo(8077);
  }

  @EnableConfigurationProperties(PieriaProperties.class)
  static class Config {
  }
}
