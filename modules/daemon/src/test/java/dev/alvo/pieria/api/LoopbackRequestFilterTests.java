package dev.alvo.pieria.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class LoopbackRequestFilterTests {

  private final LoopbackRequestFilter filter = new LoopbackRequestFilter("127.0.0.1");

  private record Outcome(int status, boolean passedOn, String body) {
  }

  private static Outcome send(LoopbackRequestFilter filter, String host, String origin) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/harvesters");
    if (host != null) {
      request.addHeader("Host", host);
    }
    if (origin != null) {
      request.addHeader("Origin", origin);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    return new Outcome(response.getStatus(), chain.getRequest() != null, response.getContentAsString());
  }

  private Outcome send(String host, String origin) throws Exception {
    return send(filter, host, origin);
  }

  @ParameterizedTest
  @ValueSource(strings = {"localhost", "localhost:8077", "LOCALHOST:5173", "127.0.0.1", "127.0.0.1:8077", "127.1.2.3:80", "[::1]", "[::1]:8077"})
  void loopbackHostsPass(String host) throws Exception {
    assertThat(send(host, null).passedOn()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"evil.example", "evil.example:8077", "127.0.0.1.evil.example", "localhost.evil.example:8077", "[2001:db8::1]:8077", "192.168.1.5"})
  void otherHostsAreRefused(String host) throws Exception {
    Outcome outcome = send(host, null);

    assertThat(outcome.status()).isEqualTo(403);
    assertThat(outcome.passedOn()).isFalse();
    assertThat(outcome.body()).contains("\"error\":\"forbidden\"");
  }

  @ParameterizedTest
  @ValueSource(strings = {"http://localhost:5173", "http://127.0.0.1:8077", "http://[::1]:5173", "https://localhost"})
  void loopbackOriginsPass(String origin) throws Exception {
    assertThat(send("localhost:8077", origin).passedOn()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"http://evil.example", "http://evil.example:8077", "null", "not a url", "http://localhost.evil.example"})
  void otherOriginsAreRefused(String origin) throws Exception {
    Outcome outcome = send("localhost:8077", origin);

    assertThat(outcome.status()).isEqualTo(403);
    assertThat(outcome.passedOn()).isFalse();
  }

  @Test
  void aRequestWithoutHostOrOriginPasses() throws Exception {
    assertThat(send(null, null).passedOn()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"localhost", "127.0.0.1", "::1", "[::1]", "127.0.0.2"})
  void everyLoopbackBindIsEnforced(String bound) throws Exception {
    assertThat(send(new LoopbackRequestFilter(bound), "evil.example", null).status()).isEqualTo(403);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0.0.0.0", "10.0.0.5", "memory.internal"})
  void aDaemonBoundElsewhereIsNotRestricted(String bound) throws Exception {
    assertThat(send(new LoopbackRequestFilter(bound), "memory.internal:8077", "https://console.internal").passedOn()).isTrue();
  }
}
