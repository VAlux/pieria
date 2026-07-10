package dev.alvo.pieria.model;

import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transient/permanent classification that drives {@link ModelCallRetry}: rate limits, 5xx, and
 * brief connectivity failures are worth retrying; deterministic HTTP rejections and misconfiguration
 * are not.
 */
class ModelFailuresTests {

  private static final Headers NO_HEADERS = Headers.builder().build();

  @Test
  void rateLimitIsTransient() {
    assertThat(ModelFailures.isTransient(RateLimitException.builder().headers(NO_HEADERS).build())).isTrue();
  }

  @Test
  void serverErrorIsTransient() {
    var e = InternalServerException.builder().statusCode(503).headers(NO_HEADERS).build();
    assertThat(ModelFailures.isTransient(e)).isTrue();
  }

  @Test
  void badRequestIsNotTransient() {
    assertThat(ModelFailures.isTransient(BadRequestException.builder().headers(NO_HEADERS).build())).isFalse();
  }

  @Test
  void authFailuresAreNotTransient() {
    assertThat(ModelFailures.isTransient(UnauthorizedException.builder().headers(NO_HEADERS).build())).isFalse();
    assertThat(ModelFailures.isTransient(PermissionDeniedException.builder().headers(NO_HEADERS).build())).isFalse();
  }

  @Test
  void connectivityBlipsAreTransientEvenWhenWrapped() {
    assertThat(ModelFailures.isTransient(new ConnectException("connection refused"))).isTrue();
    assertThat(ModelFailures.isTransient(new SocketTimeoutException("read timed out"))).isTrue();
    assertThat(ModelFailures.isTransient(new RuntimeException("io", new TimeoutException("slow")))).isTrue();
    assertThat(ModelFailures.isTransient(new RuntimeException("wrapper", new ConnectException("refused")))).isTrue();
  }

  @Test
  void unknownHostIsTreatedAsPermanentMisconfiguration() {
    assertThat(ModelFailures.isTransient(new UnknownHostException("no-such-host"))).isFalse();
  }

  @Test
  void genericFailuresAreNotTransient() {
    assertThat(ModelFailures.isTransient(new IllegalArgumentException("bad content"))).isFalse();
    assertThat(ModelFailures.isTransient(null)).isFalse();
  }
}
