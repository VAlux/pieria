package dev.alvo.pieria.client;

import dev.alvo.pieria.api.response.ErrorResponse;
import dev.alvo.pieria.client.exception.DaemonConflictException;
import dev.alvo.pieria.client.exception.DaemonHttpException;
import dev.alvo.pieria.client.exception.DaemonInterruptedException;
import dev.alvo.pieria.client.exception.DaemonNotFoundException;
import dev.alvo.pieria.client.exception.DaemonProtocolException;
import dev.alvo.pieria.client.exception.DaemonUnavailableException;
import dev.alvo.pieria.config.toml.ConfigCodec;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class DaemonTransport {
  private final String baseUrl;
  private final HttpClient http;
  private final ObjectMapper json = JsonMapper.builder()
    .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)
    .build();

  DaemonTransport(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("base URL must not be blank");
    }
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    URI uri = URI.create(this.baseUrl);
    if (uri.getScheme() == null || uri.getHost() == null) {
      throw new IllegalArgumentException("invalid daemon base URL: " + baseUrl);
    }
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  static String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  static String query(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  static String withQuery(String path, String... pairs) {
    List<String> values = new ArrayList<>();
    for (int i = 0; i < pairs.length; i += 2) {
      if (pairs[i + 1] != null && !pairs[i + 1].isBlank()) {
        values.add(pairs[i] + "=" + query(pairs[i + 1]));
      }
    }
    return values.isEmpty() ? path : path + "?" + String.join("&", values);
  }

  String baseUrl() {
    return baseUrl;
  }

  URI uri(String path) {
    return URI.create(baseUrl + path);
  }

  String get(String path, Duration timeout) {
    return send(builder(path, timeout).GET().build());
  }

  String delete(String path, Duration timeout) {
    return send(builder(path, timeout).DELETE().build());
  }

  String putEmpty(String path, Duration timeout) {
    return send(builder(path, timeout).PUT(HttpRequest.BodyPublishers.noBody()).build());
  }

  String put(String path, Object body, Duration timeout, boolean kebab) {
    return sendJson(builder(path, timeout), "PUT", body, kebab);
  }

  String post(String path, Object body, Duration timeout) {
    return sendJson(builder(path, timeout), "POST", body, false);
  }

  int probe(String path, Duration timeout) {
    HttpRequest request = builder(path, timeout).GET().build();
    try {
      return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DaemonInterruptedException(e);
    } catch (Exception e) {
      throw new DaemonUnavailableException(baseUrl, e);
    }
  }

  <T> T parse(String body, Class<T> type) {
    try {
      return json.readValue(body, type);
    } catch (RuntimeException e) {
      throw new DaemonProtocolException("failed to parse daemon response", e);
    }
  }

  <T> T parseConfig(String body, Class<T> type) {
    try {
      return ConfigCodec.bind(ConfigCodec.parseJson(body), type);
    } catch (RuntimeException e) {
      throw new DaemonProtocolException("failed to parse daemon response", e);
    }
  }

  String toJson(Object value) {
    try {
      return json.writeValueAsString(withoutNulls(json.valueToTree(value)));
    } catch (RuntimeException e) {
      throw new DaemonProtocolException("failed to serialize daemon request", e);
    }
  }

  private HttpRequest.Builder builder(String path, Duration timeout) {
    return HttpRequest.newBuilder(uri(path)).timeout(timeout);
  }

  private String sendJson(HttpRequest.Builder builder, String method, Object body, boolean kebab) {
    String serialized;
    try {
      serialized = kebab ? ConfigCodec.toJson(body) : json.writeValueAsString(withoutNulls(json.valueToTree(body)));
    } catch (RuntimeException e) {
      throw new DaemonProtocolException("failed to serialize daemon request", e);
    }
    return send(builder.header("Content-Type", "application/json")
      .method(method, HttpRequest.BodyPublishers.ofString(serialized, StandardCharsets.UTF_8)).build());
  }

  private String send(HttpRequest request) {
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DaemonInterruptedException(e);
    } catch (Exception e) {
      throw new DaemonUnavailableException(baseUrl, e);
    }
    int status = response.statusCode();
    if (status >= 200 && status < 300) {
      return response.body() == null ? "" : response.body();
    }
    String body = response.body() == null ? "" : response.body();
    String message = errorMessage(body);
    if (status == 404) {
      throw new DaemonNotFoundException(body, message);
    }
    if (status == 409) {
      throw new DaemonConflictException(body, message);
    }
    throw new DaemonHttpException(status, body, message);
  }

  private String errorMessage(String body) {
    if (body.isBlank()) {
      return "";
    }
    try {
      ErrorResponse error = json.readValue(body, ErrorResponse.class);
      return error == null || error.message() == null ? "" : error.message();
    } catch (RuntimeException ignored) {
      return body;
    }
  }

  private JsonNode withoutNulls(JsonNode node) {
    if (node instanceof ObjectNode object) {
      List<String> remove = new ArrayList<>();
      for (var property : object.properties()) {
        if (property.getValue() == null || property.getValue().isNull()) {
          remove.add(property.getKey());
        } else {
          withoutNulls(property.getValue());
        }
      }
      remove.forEach(object::remove);
    } else if (node instanceof ArrayNode array) {
      for (JsonNode value : array) {
        withoutNulls(value);
      }
    }
    return node;
  }
}
