package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.PieriaApplication;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.model.OllamaModelGateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Boots a non-web Spring context from the daemon's {@link PieriaApplication} so the live
 * {@link OllamaModelGateway} (built from Spring AI's autoconfigured Ollama beans) and the bound
 * {@link PieriaProperties} can be handed to {@link BenchmarkRunner}.
 *
 * <p>This is the only place that depends on a live model provider; it is never touched by CI. The
 * context is started lazily by {@link #fromSpring()} and must be {@link #close() closed} by the
 * caller (the {@code BenchmarkRunner.main} does this in a {@code finally} block). Because the
 * benchmark harness supplies its own in-memory {@link dev.alvo.pieria.storage.MemoryStore}, the
 * daemon's SQLite writer is never engaged — only the model gateway is reused.
 *
 * <p>To point at a different provider (e.g. a hosted Anthropic/OpenAI baseline for comparison),
 * override the relevant {@code spring.ai.*} / {@code pieria.model.*} properties via standard Spring
 * configuration before calling {@link #fromSpring()}, or bypass this class entirely and pass any
 * {@link ModelGateway} supplier straight to {@link BenchmarkRunner}.
 */
public final class LiveModelGatewayFactory implements AutoCloseable {

  private final ConfigurableApplicationContext context;
  private final ModelGateway gateway;
  private final PieriaProperties properties;

  private LiveModelGatewayFactory(ConfigurableApplicationContext context,
                                  ModelGateway gateway,
                                  PieriaProperties properties) {
    this.context = Objects.requireNonNull(context, "context");
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  public static LiveModelGatewayFactory fromSpring(String... args) {
    ConfigurableApplicationContext context = nonWebApplication().run(args);
    try {
      ModelGateway gateway = context.getBean(OllamaModelGateway.class);
      PieriaProperties properties = context.getBean(PieriaProperties.class);
      return new LiveModelGatewayFactory(context, gateway, properties);
    } catch (RuntimeException e) {
      context.close();
      throw e;
    }
  }

  public Supplier<ModelGateway> gatewayFactory() {
    return () -> gateway;
  }

  public PieriaProperties properties() {
    return properties;
  }

  @Override
  public void close() {
    context.close();
  }

  private static SpringApplication nonWebApplication() {
    SpringApplication application = new SpringApplication(PieriaApplication.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    return application;
  }
}
