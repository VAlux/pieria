package dev.alvo.pieria;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Arrays;

/**
 * Single jar, two launch modes:
 * <ul>
 *   <li><b>daemon</b> (default) — the pure-REST background service holding all state.</li>
 *   <li><b>shim</b> — the MCP stdio server that forwards model tool calls to the daemon over
 *       localhost HTTP. Activated by the {@code --mcp-shim} arg (or {@code spring.profiles.active=shim}).</li>
 * </ul>
 *
 * <p>The {@code shim} profile selects {@code application-shim.properties} (web disabled, DB/Flyway/
 * model autoconfig excluded). Daemon component-scanned beans carry {@code @Profile("!shim")} so the
 * shim process never instantiates the store, datasource, ingestion, retrieval, or model gateway.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PieriaApplication {

  /** Arg that flips this jar into the MCP stdio shim launch mode. */
  static final String SHIM_ARG = "--mcp-shim";

  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(PieriaApplication.class);
    if (isShim(args)) {
      // Activate the shim profile before refresh so application-shim.properties and the
      // @Profile guards take effect.
      app.setAdditionalProfiles("shim");
    }
    app.run(args);
  }

  static boolean isShim(String[] args) {
    return args != null && Arrays.asList(args).contains(SHIM_ARG);
  }
}
