package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.PieriaApplication;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.model.OpenAiModelGateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Objects;

/**
 * Boots a non-web Spring context from the daemon's {@link PieriaApplication} to obtain the live
 * {@link OpenAiModelGateway}, used purely as the <em>judge</em> for answer faithfulness — the
 * evaluator, deliberately kept separate from the system under test (the {@link LiveDaemon} the
 * benchmark drives over HTTP).
 *
 * <p>This is one of two places that depend on a live model provider (the other being the daemon the
 * benchmark drives); neither is touched by CI. The context is started lazily by {@link #fromSpring()}
 * and must be {@link #close() closed} by the caller. No storage or pipeline is engaged here — only
 * the model gateway is reused, to judge answers already recorded by the daemon run.
 *
 * <p>To judge against a different provider (e.g. a hosted OpenAI baseline), override the relevant
 * {@code spring.ai.*} / {@code pieria.provider.*} properties before calling {@link #fromSpring()}, or
 * bypass this class and hand any {@link ModelGateway} to {@link FaithfulnessJudgeRunner}.
 */
public final class LiveModelGatewayFactory implements AutoCloseable {

  private final ConfigurableApplicationContext context;
  private final ModelGateway gateway;

  private LiveModelGatewayFactory(ConfigurableApplicationContext context, ModelGateway gateway) {
    this.context = Objects.requireNonNull(context, "context");
    this.gateway = Objects.requireNonNull(gateway, "gateway");
  }

  public static LiveModelGatewayFactory fromSpring(String... args) {
    ConfigurableApplicationContext context = nonWebApplication().run(args);
    try {
      ModelGateway gateway = context.getBean(OpenAiModelGateway.class);
      return new LiveModelGatewayFactory(context, gateway);
    } catch (RuntimeException e) {
      context.close();
      throw e;
    }
  }

  /** The live model gateway to judge answer faithfulness. */
  public ModelGateway gateway() {
    return gateway;
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
