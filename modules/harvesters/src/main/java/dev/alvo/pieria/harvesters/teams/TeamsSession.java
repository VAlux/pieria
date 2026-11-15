package dev.alvo.pieria.harvesters.teams;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.RecordSink;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRecord;
import dev.alvo.pieria.harvesters.http.JsonTransport;
import tools.jackson.databind.JsonNode;

/** A signed-in Graph connection and who it is signed in as. */
public record TeamsSession(GraphClient graph, String userId, String userName) {

  /**
   * Refreshes the sign-in, saving a rotated refresh token before anything else can fail, checks the
   * permissions the config needs, and reads the user.
   */
  public static TeamsSession open(TeamsConfig config, JsonTransport transport, RecordSink sink) throws HarvesterException {
    if (config.refreshToken() == null) {
      throw new HarvesterException(ErrorClass.AUTH, "not signed in yet; run 'pieria source authorize <source id>'");
    }
    Tokens tokens = new MicrosoftLogin(config, transport).refresh(config.refreshToken());
    if (!tokens.refreshToken().equals(config.refreshToken())) {
      sink.emit(new HarvestRecord.SecretUpdate(TeamsConfig.REFRESH_TOKEN, tokens.refreshToken()));
    }
    MicrosoftLogin.requireScopes(tokens, config);
    GraphClient graph = new GraphClient(config.graphUrl(), tokens.accessToken(), transport);
    JsonNode me = graph.get("/me?$select=id,displayName");
    String id = me.path("id").asString("");
    if (id.isBlank()) {
      throw new HarvesterException(ErrorClass.TRANSIENT, "Microsoft Graph answered /me without an id");
    }
    String name = me.path("displayName").asString("");
    return new TeamsSession(graph, id, name.isBlank() ? id : name);
  }
}
