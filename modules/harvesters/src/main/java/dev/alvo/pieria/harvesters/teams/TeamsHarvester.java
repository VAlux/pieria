package dev.alvo.pieria.harvesters.teams;

import dev.alvo.pieria.harvester.Harvester;
import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.RecordSink;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvester.protocol.ProtocolLimits;
import dev.alvo.pieria.harvesters.http.JsonTransport;
import dev.alvo.pieria.harvesters.incremental.IncrementalHarvest;

import java.time.Duration;

/** Harvests the signed-in user's Teams chats and named channels. Signs in with Microsoft's device code. */
public final class TeamsHarvester implements Harvester {

  private static final Duration REQUEST_DEADLINE = Duration.ofSeconds(60);
  /** Never reached: Teams is not swept. */
  private static final Duration SWEEP_EVERY = Duration.ofHours(24);

  private static JsonTransport transport() {
    return new JsonTransport("Microsoft", REQUEST_DEADLINE);
  }

  @Override
  public void check(HarvestRequest request, RecordSink sink) throws HarvesterException {
    new TeamsFeed(TeamsConfig.parse(request), transport(), sink).begin();
  }

  @Override
  public void harvest(HarvestRequest request, RecordSink sink) throws HarvesterException {
    IncrementalHarvest.run(new TeamsFeed(TeamsConfig.parse(request), transport(), sink), request, sink, SWEEP_EVERY, "teams");
  }

  /** Shows the code, waits for the user, and saves the refresh token only once every needed permission is granted. */
  @Override
  public void authorize(HarvestRequest request, RecordSink sink) throws HarvesterException {
    TeamsConfig config = TeamsConfig.parse(request);
    MicrosoftLogin login = new MicrosoftLogin(config, transport());
    DeviceCodeGrant grant = login.start();
    String message = grant.message();
    if (message != null && message.length() > ProtocolLimits.MAX_ERROR_CHARS) {
      message = message.substring(0, ProtocolLimits.MAX_ERROR_CHARS);
    }
    sink.emit(new HarvestRecord.DeviceCode(grant.verificationUri(), grant.userCode(), message,
      Math.min(grant.expiresIn(), ProtocolLimits.MAX_SIGN_IN_SECONDS)));
    Tokens tokens = login.await(grant);
    MicrosoftLogin.requireScopes(tokens, config);
    sink.emit(new HarvestRecord.SecretUpdate(TeamsConfig.REFRESH_TOKEN, tokens.refreshToken()));
  }
}
