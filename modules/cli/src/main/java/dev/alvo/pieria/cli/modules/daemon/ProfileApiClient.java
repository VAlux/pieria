package dev.alvo.pieria.cli.modules.daemon;

import dev.alvo.pieria.api.request.RecallRequest;
import dev.alvo.pieria.api.request.RememberRequest;
import dev.alvo.pieria.api.response.MemoryListResponse;
import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.api.response.ProfileListResponse;
import dev.alvo.pieria.api.response.ProfileStatsResponse;
import dev.alvo.pieria.api.response.ProfileSummary;
import dev.alvo.pieria.api.response.RecallResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Talks to the daemon's profile/memory REST surface ({@code /v1/profiles[...]}) for the
 * {@code pieria profile} sub-commands. Mirrors {@link DaemonClient}'s plain {@code HttpClient} +
 * Jackson approach, but uses longer timeouts since recall/ingest can be slow, and surfaces
 * failures as typed exceptions the commands map to exit codes.
 */
public final class ProfileApiClient {

  private final String baseUrl;
  private final HttpClient http;
  private final ObjectMapper mapper;

  public ProfileApiClient(String baseUrl) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();
    this.mapper = JsonMapper.builder().build();
  }

  private static String profilePath(String profile) {
    return "/v1/profiles/" + encodePath(profile);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /**
   * Path-segment encoding: keep it predictable and avoid '+' for spaces that query encoding uses.
   */
  private static String encodePath(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  public ProfileListResponse listProfiles() {
    return parse(get("/v1/profiles"), ProfileListResponse.class);
  }

  /**
   * Create an empty profile; throws {@link ConflictException} when one with that name already exists.
   */
  public ProfileSummary createProfile(String profile) {
    String body = send(HttpRequest.newBuilder(uri(profilePath(profile)))
      .timeout(Duration.ofSeconds(10))
      .PUT(HttpRequest.BodyPublishers.noBody())
      .build());
    return parse(body, ProfileSummary.class);
  }

  /**
   * Delete a profile and all its memories; throws {@link NotFoundException} when the daemon reports 404.
   */
  public void deleteProfile(String profile) {
    send(HttpRequest.newBuilder(uri(profilePath(profile)))
      .timeout(Duration.ofSeconds(30))
      .DELETE()
      .build());
  }

  public ProfileStatsResponse stats(String profile) {
    return parse(get(profilePath(profile) + "/stats"), ProfileStatsResponse.class);
  }

  public MemoryListResponse memories(String profile, String type, String session) {
    StringBuilder path = new StringBuilder(profilePath(profile)).append("/memories");
    String sep = "?";
    if (type != null && !type.isBlank()) {
      path.append(sep).append("type=").append(encode(type));
      sep = "&";
    }
    if (session != null && !session.isBlank()) {
      path.append(sep).append("session=").append(encode(session));
    }
    return parse(get(path.toString()), MemoryListResponse.class);
  }

  public RecallResponse recall(String profile, RecallRequest request) {
    return parse(post(profilePath(profile) + "/recall", request), RecallResponse.class);
  }

  public MemoryResponse remember(String profile, RememberRequest request) {
    return parse(post(profilePath(profile) + "/memories", request), MemoryResponse.class);
  }

  /**
   * Delete a memory; throws {@link NotFoundException} when the daemon reports 404.
   */
  public void forget(String profile, String id) {
    send(HttpRequest.newBuilder(uri(profilePath(profile) + "/memories/" + encodePath(id)))
      .timeout(Duration.ofSeconds(10))
      .DELETE()
      .build());
  }

  /**
   * Raw NDJSON export body.
   */
  public String export(String profile) {
    return send(HttpRequest.newBuilder(uri(profilePath(profile) + "/export"))
      .timeout(Duration.ofSeconds(30))
      .GET()
      .build());
  }

  private String get(String path) {
    return send(HttpRequest.newBuilder(uri(path))
      .timeout(Duration.ofSeconds(15))
      .GET()
      .build());
  }

  private String post(String path, Object body) {
    String json;
    try {
      json = mapper.writeValueAsString(body);
    } catch (RuntimeException e) {
      throw new ApiException(0, "failed to serialize request: " + e.getMessage());
    }
    return send(HttpRequest.newBuilder(uri(path))
      .timeout(Duration.ofSeconds(60))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
      .build());
  }

  /**
   * Sends a request, returning the body on 2xx and translating failures into typed exceptions.
   */
  private String send(HttpRequest request) {
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      throw new DaemonDownException("could not reach daemon at " + baseUrl, e);
    }
    int code = response.statusCode();
    if (code >= 200 && code < 300) {
      return response.body();
    }
    if (code == 404) {
      throw new NotFoundException(errorMessage(response.body(), "Not found."));
    }
    if (code == 409) {
      throw new ConflictException(errorMessage(response.body(), "Already exists."));
    }
    throw new ApiException(code, response.body());
  }

  /**
   * Best-effort extraction of the daemon's sanitized error {@code message}, falling back to the raw
   * body (or {@code fallback} when the body is blank) so the CLI never surfaces a raw JSON envelope.
   */
  private String errorMessage(String body, String fallback) {
    if (body == null || body.isBlank()) {
      return fallback;
    }
    try {
      ErrorBody parsed = mapper.readValue(body, ErrorBody.class);
      if (parsed != null && parsed.message() != null && !parsed.message().isBlank()) {
        return parsed.message();
      }
    } catch (RuntimeException ignored) {
      // Not a JSON error envelope; fall through to the raw body.
    }
    return body;
  }

  private record ErrorBody(String error, String message) {
  }

  private <T> T parse(String body, Class<T> type) {
    try {
      return mapper.readValue(body, type);
    } catch (RuntimeException e) {
      throw new ApiException(0, "failed to parse daemon response: " + e.getMessage());
    }
  }

  private URI uri(String path) {
    return URI.create(baseUrl + path);
  }

  /**
   * Daemon could not be reached at all (connection refused / timeout). Exit code 3.
   */
  public static final class DaemonDownException extends RuntimeException {
    public DaemonDownException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * The daemon returned 404 (unknown profile or memory). Exit code 4.
   */
  public static final class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
      super(message);
    }
  }

  /**
   * The daemon returned 409 (a create collided with an existing profile). Exit code 1.
   */
  public static final class ConflictException extends RuntimeException {
    public ConflictException(String message) {
      super(message);
    }
  }

  /**
   * Any other non-2xx response. Carries the HTTP status and body for a useful message. Exit code 1.
   */
  public static final class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String body) {
      super("daemon returned HTTP " + status + (body == null || body.isBlank() ? "" : ": " + body));
      this.status = status;
    }

    public int status() {
      return status;
    }
  }
}
