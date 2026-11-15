package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.api.response.SourceAuthorizationResponse;
import dev.alvo.pieria.client.HarvestClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.time.Duration;
import java.time.Instant;

/** Signs a source in through its harvester, showing the code the user enters at the provider's page. */
@Command(name = "authorize", description = "Sign a source in, for harvesters that use a sign-in code.", mixinStandardHelpOptions = true)
public final class SourceAuthorizeCommand extends AbstractSourceCommand {

  static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
  /** How long past the code's expiry to keep waiting for the daemon to report how it ended. */
  private static final Duration EXPIRY_GRACE = Duration.ofSeconds(60);

  @Parameters(index = "0", paramLabel = "<id>", description = "Source id.")
  String id;

  @Override
  protected int run(HarvestClient client) throws InterruptedException {
    String profile = profile();
    SourceAuthorizationResponse state = client.authorize(profile, id);
    if (state.status().equals("pending")) {
      if (state.message() != null && !state.message().isBlank()) {
        log.info("{}", state.message());
      } else {
        log.info("Open {} and enter the code {}.", state.verificationUri(), state.userCode());
      }
      log.info("Waiting for you to sign in…");
      Instant giveUp = (state.expiresAt() == null ? Instant.now() : state.expiresAt()).plus(EXPIRY_GRACE);
      while (state.status().equals("pending")) {
        if (Instant.now().isAfter(giveUp)) {
          log.error("Sign-in timed out.");
          return 1;
        }
        Thread.sleep(POLL_INTERVAL.toMillis());
        state = client.authorization(profile, id);
      }
    }
    if (state.status().equals("authorized")) {
      log.info("Signed in.");
      return 0;
    }
    log.error("Sign-in failed ({}): {}", state.errorClass(), state.error());
    return 1;
  }
}
