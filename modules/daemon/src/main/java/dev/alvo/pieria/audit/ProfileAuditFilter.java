package dev.alvo.pieria.audit;

import dev.alvo.pieria.api.AuditHeaders;
import dev.alvo.pieria.config.AuditProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Captures every profile-scoped HTTP exchange except audit browsing itself.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ProfileAuditFilter extends OncePerRequestFilter {
  private static final String PREFIX = "/v1/profiles/";

  private final AuditRecorder recorder;
  private final int maxBodyBytes;

  public ProfileAuditFilter(ObjectProvider<AuditRecorder> recorder,
                            ObjectProvider<AuditProperties> properties) {
    this.recorder = recorder.getIfAvailable();
    AuditProperties configured = properties.getIfAvailable();
    this.maxBodyBytes = configured == null ? 1_048_576 : configured.maxBodyBytes();
  }

  private static AuditCaller caller(HttpServletRequest request) {
    return new AuditCaller(header(request, AuditHeaders.CLIENT, "api"),
      nullableHeader(request, AuditHeaders.HARNESS), header(request, AuditHeaders.CHANNEL, "http"),
      limitedHeader(request, AuditHeaders.CLIENT_VERSION), request.getRemoteAddr());
  }

  private static String header(HttpServletRequest request, String name, String fallback) {
    String value = nullableHeader(request, name);
    return value == null ? fallback : value;
  }

  private static String nullableHeader(HttpServletRequest request, String name) {
    String value = request.getHeader(name);
    if (value == null || value.isBlank()) return null;
    String normalized = value.strip().toLowerCase(Locale.ROOT);
    return normalized.substring(0, Math.min(128, normalized.length()));
  }

  private static String limitedHeader(HttpServletRequest request, String name) {
    String value = request.getHeader(name);
    if (value == null || value.isBlank()) return null;
    String stripped = value.strip();
    return stripped.substring(0, Math.min(128, stripped.length()));
  }

  private static String requestId(String supplied) {
    if (supplied != null && supplied.length() <= 128 && supplied.matches("[A-Za-z0-9._:-]+")) {
      return supplied;
    }
    return UUID.randomUUID().toString();
  }

  private static String profileName(String path) {
    String remainder = path.substring(PREFIX.length());
    int slash = remainder.indexOf('/');
    String segment = slash < 0 ? remainder : remainder.substring(0, slash);
    return URLDecoder.decode(segment, StandardCharsets.UTF_8);
  }

  private static String suffix(String path) {
    if (!path.startsWith(PREFIX)) return "";
    String remainder = path.substring(PREFIX.length());
    int slash = remainder.indexOf('/');
    return slash < 0 ? "" : remainder.substring(slash + 1);
  }

  static String operation(String method, String rest) {
    if (rest.equals("wiki")) return "wiki.directory";
    if (rest.equals("wiki/search")) return "wiki.search";
    if (rest.equals("wiki/status")) return "wiki.status";
    if (rest.equals("wiki/build")) return "wiki.build";
    if (rest.startsWith("graph/") && !rest.equals("graph/view")) return "graph." + rest.split("/")[1];
    if (rest.equals("clusters")) return method.equals("POST") ? "cluster.create" : "cluster.list";
    if (rest.equals("clusters/links")) return "cluster.links";
    if (rest.startsWith("clusters/")) return method.equals("GET") ? "cluster.read" : "cluster.update";
    if (rest.startsWith("clustering/")) return method.equals("GET") ? "clustering.read" : "clustering.update";
    if (rest.startsWith("wiki/jobs/")) return "wiki.job";
    if (rest.startsWith("wiki/pages/")) return rest.endsWith("/evaluate") ? "wiki.evaluate"
      : rest.endsWith("/evidence") ? "wiki.evidence" : "wiki.read";
    return switch (rest) {
      case "" -> method.equals("PUT") ? "profile.create" : method.equals("DELETE") ? "profile.delete" : "profile.read";
      case "ingest" -> "ingest";
      case "ingest/transcript" -> "ingest.transcript";
      case "ingest/async" -> "ingest.async";
      case "memories" -> method.equals("POST") ? "memory.remember" : "memory.list";
      case "memories/search" -> "memory.search";
      case "sessions" -> "session.list";
      case "topic-keys" -> "topic-key.search";
      case "recall" -> "recall";
      case "stats" -> "stats";
      case "graph" -> "graph.read";
      case "graph/view" -> "graph.view";
      case "export" -> "export";
      case "config/detail" -> "config.detail";
      case "config" -> switch (method) {
        case "PUT" -> "config.update";
        case "DELETE" -> "config.clear";
        default -> "config.get";
      };
      case "onboard/async" -> "onboard.async";
      case "code" -> "code.index";
      case "code/async" -> "code.index.async";
      case "code/status" -> "code.status";
      case "reminisce/async" -> "reminisce.async";
      case "reminisce/orphans" -> "reminisce.orphans";
      default -> rest.startsWith("memories/") ? memoryOperation(method, rest) : "profile." + method.toLowerCase(Locale.ROOT);
    };
  }

  private static String memoryOperation(String method, String rest) {
    if (rest.endsWith("/history")) return "memory.history";
    if (rest.endsWith("/revisions")) return method.equals("POST") ? "memory.revise" : "memory.revisions";
    return switch (method) {
      case "DELETE" -> "memory.forget";
      case "GET" -> "memory.get";
      default -> "profile." + method.toLowerCase(Locale.ROOT);
    };
  }

  private static String resourceId(String rest) {
    if (rest.startsWith("wiki/pages/") || rest.startsWith("wiki/jobs/")) {
      String id = rest.split("/", 4)[2];
      return id.isBlank() ? null : id;
    }
    if (rest.startsWith("memories/") && rest.length() > "memories/".length() && !rest.equals("memories/search")) {
      String id = rest.substring("memories/".length()).split("/", 2)[0];
      return URLDecoder.decode(id, StandardCharsets.UTF_8);
    }
    return null;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    String rest = suffix(path);
    return recorder == null || !path.startsWith(PREFIX)
      || rest.equals("audit") || rest.startsWith("audit/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
    String profile = profileName(request.getRequestURI());
    String rest = suffix(request.getRequestURI());
    String operation = operation(request.getMethod(), rest);
    String requestId = requestId(request.getHeader(AuditHeaders.REQUEST_ID));
    AuditCaller caller = caller(request);
    Instant startedAt = Instant.now();
    boolean wiki = rest.equals("wiki") || rest.startsWith("wiki/");
    AuditRequestWrapper wrappedRequest = new AuditRequestWrapper(request, wiki ? 0 : maxBodyBytes);
    AuditResponseWrapper wrappedResponse = new AuditResponseWrapper(response, wiki ? 0 : maxBodyBytes);
    wrappedResponse.setHeader(AuditHeaders.REQUEST_ID, requestId);
    Throwable failure = null;
    AuditRequestContext.set(new AuditRequestContext(profile, operation, requestId, caller));
    try {
      chain.doFilter(wrappedRequest, wrappedResponse);
    } catch (Throwable t) {
      failure = t;
      if (t instanceof ServletException servlet) throw servlet;
      if (t instanceof IOException io) throw io;
      if (t instanceof RuntimeException runtime) throw runtime;
      throw new ServletException(t);
    } finally {
      AuditRequestContext.clear();
      Instant completedAt = Instant.now();
      int status = failure == null ? wrappedResponse.getStatus() : 500;
      // Successful hard deletion owns the privacy contract: its prior audit rows are deleted by
      // SqliteMemoryStore, and the filter must not recreate one after the profile is gone.
      if (!(operation.equals("profile.delete") && status >= 200 && status < 300)) {
        recorder.recordHttp(profile, operation, requestId, caller, request.getMethod(),
          request.getRequestURI(), wiki ? null : request.getQueryString(), request.getContentType(),
          wrappedResponse.getContentType(), startedAt, completedAt, status,
          wiki ? new CapturedPayload("",0,"",false) : wrappedRequest.captured(),
          wiki ? new CapturedPayload("",0,"",false) : wrappedResponse.captured(), resourceId(rest), wiki ? null : failure);
      }
    }
  }
}
