package dev.alvo.pieria.client;

import dev.alvo.pieria.api.request.CreateSourceRequest;
import dev.alvo.pieria.api.request.HarvesterPathRequest;
import dev.alvo.pieria.api.request.InstallHarvesterRequest;
import dev.alvo.pieria.api.request.RunSourceRequest;
import dev.alvo.pieria.api.request.SourceSecretsRequest;
import dev.alvo.pieria.api.request.UpdateSourceRequest;
import dev.alvo.pieria.api.response.HarvesterDoctorResponse;
import dev.alvo.pieria.api.response.HarvesterListResponse;
import dev.alvo.pieria.api.response.HarvesterPackageResponse;
import dev.alvo.pieria.api.response.HarvesterResponse;
import dev.alvo.pieria.api.response.SourceAuthorizationResponse;
import dev.alvo.pieria.api.response.SourceCheckResponse;
import dev.alvo.pieria.api.response.SourceListResponse;
import dev.alvo.pieria.api.response.SourceResponse;
import dev.alvo.pieria.api.response.SourceRunListResponse;
import dev.alvo.pieria.api.response.TaskSubmitResponse;

import java.time.Duration;
import java.util.Map;

/** Talks to the daemon's harvester and source endpoints. */
public final class HarvestClient {

  private static final Duration SHORT = Duration.ofSeconds(15);
  private static final Duration CHECK = Duration.ofSeconds(90);
  private static final Duration LONG = Duration.ofSeconds(120);

  private final DaemonTransport transport;

  HarvestClient(DaemonTransport transport) {
    this.transport = transport;
  }

  public HarvestClient(String baseUrl, ClientIdentity identity) {
    this(new DaemonTransport(baseUrl, identity));
  }

  private static String sources(String profile) {
    return "/v1/profiles/" + DaemonTransport.segment(profile) + "/sources";
  }

  private static String source(String profile, String id) {
    return sources(profile) + "/" + DaemonTransport.segment(id);
  }

  public HarvesterListResponse harvesters() {
    return transport.parse(transport.get("/v1/harvesters", SHORT), HarvesterListResponse.class);
  }

  public HarvesterDoctorResponse doctor() {
    return transport.parse(transport.get("/v1/harvesters/doctor", SHORT), HarvesterDoctorResponse.class);
  }

  public HarvesterPackageResponse inspect(String path) {
    return transport.parse(transport.post("/v1/harvesters/inspect", new HarvesterPathRequest(path), LONG), HarvesterPackageResponse.class);
  }

  public HarvesterResponse install(InstallHarvesterRequest request) {
    return transport.parse(transport.post("/v1/harvesters", request, LONG), HarvesterResponse.class);
  }

  public void removeHarvester(String id) {
    transport.delete("/v1/harvesters/" + DaemonTransport.segment(id), SHORT);
  }

  public SourceResponse create(String profile, CreateSourceRequest request) {
    return transport.parse(transport.post(sources(profile), request, SHORT), SourceResponse.class);
  }

  public SourceListResponse list(String profile) {
    return transport.parse(transport.get(sources(profile), SHORT), SourceListResponse.class);
  }

  public SourceResponse get(String profile, String id) {
    return transport.parse(transport.get(source(profile, id), SHORT), SourceResponse.class);
  }

  public SourceResponse update(String profile, String id, UpdateSourceRequest request) {
    return transport.parse(transport.patch(source(profile, id), request, SHORT), SourceResponse.class);
  }

  public void remove(String profile, String id, boolean forgetMemories) {
    transport.delete(DaemonTransport.withQuery(source(profile, id), "forgetMemories", String.valueOf(forgetMemories)), LONG);
  }

  public SourceCheckResponse check(String profile, String id) {
    return transport.parse(transport.post(source(profile, id) + "/check", Map.of(), CHECK), SourceCheckResponse.class);
  }

  /** Starts a sign-in and returns once the harvester has a code for the user, or has failed. */
  public SourceAuthorizationResponse authorize(String profile, String id) {
    return transport.parse(transport.post(source(profile, id) + "/authorize", Map.of(), CHECK), SourceAuthorizationResponse.class);
  }

  public SourceAuthorizationResponse authorization(String profile, String id) {
    return transport.parse(transport.get(source(profile, id) + "/authorize", SHORT), SourceAuthorizationResponse.class);
  }

  public TaskSubmitResponse run(String profile, String id, boolean dryRun) {
    return transport.parse(transport.post(source(profile, id) + "/run", new RunSourceRequest(dryRun), SHORT), TaskSubmitResponse.class);
  }

  public SourceRunListResponse runs(String profile, String id, int limit) {
    return transport.parse(transport.get(DaemonTransport.withQuery(source(profile, id) + "/runs", "limit", String.valueOf(limit)), SHORT),
      SourceRunListResponse.class);
  }

  public SourceResponse pause(String profile, String id) {
    return transport.parse(transport.post(source(profile, id) + "/pause", Map.of(), SHORT), SourceResponse.class);
  }

  public SourceResponse resume(String profile, String id) {
    return transport.parse(transport.post(source(profile, id) + "/resume", Map.of(), SHORT), SourceResponse.class);
  }

  public SourceResponse acceptSweep(String profile, String id) {
    return transport.parse(transport.post(source(profile, id) + "/accept-sweep", Map.of(), SHORT), SourceResponse.class);
  }

  public void setSecrets(String profile, String id, Map<String, String> secrets) {
    transport.put(source(profile, id) + "/secrets", new SourceSecretsRequest(secrets), SHORT, false);
  }
}
