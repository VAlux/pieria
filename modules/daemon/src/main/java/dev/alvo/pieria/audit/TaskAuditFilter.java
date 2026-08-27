package dev.alvo.pieria.audit;

import dev.alvo.pieria.api.AuditHeaders;
import dev.alvo.pieria.config.AuditProperties;
import dev.alvo.pieria.task.TaskRegistry;
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
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Audits task cancellation under the task's owning profile; task polling remains intentionally quiet.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class TaskAuditFilter extends OncePerRequestFilter {
  private final AuditRecorder recorder;
  private final TaskRegistry tasks;
  private final int maxBodyBytes;

  public TaskAuditFilter(ObjectProvider<AuditRecorder> recorder, ObjectProvider<TaskRegistry> tasks,
                         ObjectProvider<AuditProperties> properties) {
    this.recorder = recorder.getIfAvailable();
    this.tasks = tasks.getIfAvailable();
    AuditProperties configured = properties.getIfAvailable();
    this.maxBodyBytes = configured == null ? 1_048_576 : configured.maxBodyBytes();
  }

  private static String header(HttpServletRequest request, String name, String fallback) {
    String value = nullable(request, name);
    return value == null ? fallback : value;
  }

  private static String nullable(HttpServletRequest request, String name) {
    String value = request.getHeader(name);
    if (value == null || value.isBlank()) return null;
    String normalized = value.strip().toLowerCase(Locale.ROOT);
    return normalized.substring(0, Math.min(128, normalized.length()));
  }

  private static String limited(HttpServletRequest request, String name) {
    String value = request.getHeader(name);
    if (value == null || value.isBlank()) return null;
    String stripped = value.strip();
    return stripped.substring(0, Math.min(128, stripped.length()));
  }

  private static String requestId(String supplied) {
    return supplied != null && supplied.length() <= 128 && supplied.matches("[A-Za-z0-9._:-]+")
      ? supplied : UUID.randomUUID().toString();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return recorder == null || tasks == null || !request.getMethod().equals("DELETE")
      || !request.getRequestURI().matches("/v1/tasks/[^/]+");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
    String taskId = request.getRequestURI().substring("/v1/tasks/".length());
    TaskRegistry.TaskInfo info = taskInfo(taskId);
    if (info == null) {
      chain.doFilter(request, response);
      return;
    }
    String requestId = requestId(request.getHeader(AuditHeaders.REQUEST_ID));
    response.setHeader(AuditHeaders.REQUEST_ID, requestId);
    AuditResponseWrapper wrapped = new AuditResponseWrapper(response, maxBodyBytes);
    Instant started = Instant.now();
    Throwable failure = null;
    try {
      chain.doFilter(request, wrapped);
    } catch (Throwable t) {
      failure = t;
      if (t instanceof ServletException servlet) throw servlet;
      if (t instanceof IOException io) throw io;
      if (t instanceof RuntimeException runtime) throw runtime;
      throw new ServletException(t);
    } finally {
      AuditCaller caller = new AuditCaller(header(request, AuditHeaders.CLIENT, "api"),
        nullable(request, AuditHeaders.HARNESS), header(request, AuditHeaders.CHANNEL, "http"),
        limited(request, AuditHeaders.CLIENT_VERSION), request.getRemoteAddr());
      recorder.recordHttp(info.profile(), "task.cancel", requestId, caller, request.getMethod(),
        request.getRequestURI(), request.getQueryString(), request.getContentType(), wrapped.getContentType(),
        started, Instant.now(), failure == null ? wrapped.getStatus() : 500,
        new BoundedCapture(0).snapshot(), wrapped.captured(), taskId, failure);
    }
  }

  private TaskRegistry.TaskInfo taskInfo(String taskId) {
    try {
      return tasks.findInfo(UUID.fromString(taskId)).orElse(null);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }
}
