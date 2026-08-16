package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.PieriaApplication;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.model.OpenAiModelGateway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Boots a non-web Spring context from the daemon's {@link PieriaApplication} to obtain the live
 * {@link OpenAiModelGateway}, used purely as the <em>judge</em> for answer faithfulness — the
 * evaluator, deliberately kept separate from the system under test (the {@link LiveDaemon} the
 * benchmark drives over HTTP).
 *
 * <p>Like the daemon under test it runs against a throwaway {@link EvalHome}, so judging never opens
 * the operator's real store. No storage or pipeline is engaged here — only the model gateway is
 * reused, to judge answers already recorded by the daemon run. The context is started lazily by
 * {@link #fromSpring()} and must be {@link #close() closed} by the caller.
 *
 * <p>To judge against a different provider (e.g. a hosted OpenAI baseline), point
 * {@link #fromSpring(Path)} at a config file naming it, or bypass this class and hand any
 * {@link ModelGateway} to {@link JudgeRunner}.
 */
public final class LiveModelGatewayFactory implements AutoCloseable {

  private final ConfigurableApplicationContext context;
  private final EvalHome home;
  private final ModelGateway gateway;

  private LiveModelGatewayFactory(ConfigurableApplicationContext context, EvalHome home,
                                  ModelGateway gateway) {
    this.context = Objects.requireNonNull(context, "context");
    this.home = Objects.requireNonNull(home, "home");
    this.gateway = Objects.requireNonNull(gateway, "gateway");
  }

  /**
   * Boots the judge against the same {@code configFile} (nullable) as the daemon under test, so the
   * judge model is the one that config names rather than the bundled default.
   */
  public static LiveModelGatewayFactory fromSpring(Path configFile) {
    EvalHome home = EvalHome.create();
    SpringApplication application = new SpringApplication(PieriaApplication.class);
    application.setWebApplicationType(WebApplicationType.NONE);

    ConfigurableApplicationContext context;
    try {
      context = application.run(home.springArgs(configFile).toArray(String[]::new));
    } catch (RuntimeException e) {
      home.close();
      throw e;
    }

    try {
      return new LiveModelGatewayFactory(context, home, context.getBean(OpenAiModelGateway.class));
    } catch (RuntimeException e) {
      context.close();
      home.close();
      throw e;
    }
  }

  /** The live model gateway to judge answer faithfulness. */
  public ModelGateway gateway() {
    return gateway;
  }

  /**
   * The per-tier token prices this context was configured with
   * ({@code pieria.stats.spend.<tier>.input-price} / {@code .output-price}), keyed by lower-case tier
   * name.
   *
   * <p>The judge runs outside the daemon, so its calls never reach a profile's spend counters and the
   * daemon cannot cost them. Exposing the price table lets the harness cost the judge's own token
   * usage exactly as the daemon costs the pipeline's, rather than reporting one in dollars and the
   * other in bare tokens.
   */
  public Map<String, PieriaProperties.Stats.TierPrice> tierPrices() {
    PieriaProperties.Stats stats = context.getBean(PieriaProperties.class).stats();
    return stats == null || stats.spend() == null ? Map.of() : stats.spend();
  }

  @Override
  public void close() {
    try {
      context.close();
    } finally {
      home.close();
    }
  }
}
