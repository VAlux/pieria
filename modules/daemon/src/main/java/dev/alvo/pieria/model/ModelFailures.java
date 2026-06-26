package dev.alvo.pieria.model;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;

import java.net.ConnectException;
import java.net.UnknownHostException;

/**
 * Turns a model-call failure into a short, human-readable, <em>sanitized</em> reason suitable for
 * logs, API error bodies, and the CLI. The point is diagnosability: a bare "model provider
 * unavailable" hides whether the provider was unreachable, the deployment name was wrong, or the
 * API key lacked access — three very different fixes.
 *
 * <p>Sanitization: HTTP failures echo the status code and the provider's own message (which carries
 * the offending model/deployment name — the user's own config, not a secret). Connection failures
 * are described generically without the host, since {@code ConnectException} messages embed it. The
 * API key lives only in request headers and never appears in these messages.
 */
public final class ModelFailures {

  /** Max length of the provider's echoed message, collapsed to a single line. */
  private static final int MAX_DETAIL = 200;

  private ModelFailures() {
  }

  /** A short sanitized reason for {@code t} (or its cause chain). */
  public static String describe(Throwable t) {
    OpenAIServiceException http = find(t, OpenAIServiceException.class);
    if (http != null) {
      int code = http.statusCode();
      String hint = switch (code) {
        case 401, 403 -> "authentication or access denied — check the API key and that it can access this deployment";
        case 404 -> "model or deployment not found — check the model/deployment name";
        case 429 -> "rate limited by the model provider";
        case 400 -> "the model provider rejected the request";
        default -> code >= 500 ? "the model provider returned a server error" : "the model provider returned an error";
      };
      String detail = trim(http.getMessage());
      return "HTTP " + code + ": " + hint + (detail.isBlank() ? "" : " (" + detail + ")");
    }
    if (find(t, OpenAIIoException.class) != null
      || find(t, ConnectException.class) != null
      || find(t, UnknownHostException.class) != null) {
      return "cannot reach the model provider (connection failed or timed out) — is it running and is the base URL correct?";
    }
    String message = t == null ? null : t.getMessage();
    return (message == null || message.isBlank()) ? "model call failed" : "model call failed: " + trim(message);
  }

  /** First throwable of {@code type} in the cause chain, or {@code null}. */
  private static <T extends Throwable> T find(Throwable t, Class<T> type) {
    for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
      if (type.isInstance(c)) {
        return type.cast(c);
      }
    }
    return null;
  }

  private static String trim(String message) {
    if (message == null) {
      return "";
    }
    String oneLine = message.strip().replaceAll("\\s+", " ");
    return oneLine.length() <= MAX_DETAIL ? oneLine : oneLine.substring(0, MAX_DETAIL - 1) + "…";
  }
}
