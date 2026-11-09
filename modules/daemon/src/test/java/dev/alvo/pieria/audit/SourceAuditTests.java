package dev.alvo.pieria.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SourceAuditTests {

  @ParameterizedTest
  @CsvSource({
    "POST, sources, source.create",
    "GET, sources, source.list",
    "GET, sources/s, source.read",
    "PATCH, sources/s, source.update",
    "DELETE, sources/s, source.delete",
    "POST, sources/s/check, source.check",
    "POST, sources/s/run, source.run",
    "GET, sources/s/runs, source.runs",
    "POST, sources/s/pause, source.pause",
    "POST, sources/s/resume, source.resume",
    "POST, sources/s/accept-sweep, source.accept-sweep",
    "PUT, sources/s/secrets, source.secrets"
  })
  void sourceOperationsAreNamed(String method, String rest, String expected) {
    assertThat(ProfileAuditFilter.operation(method, rest)).isEqualTo(expected);
  }

  @Test
  void sourceBodiesAreNeverCaptured() throws Exception {
    AuditRecorder recorder = mock(AuditRecorder.class);
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("recorder", recorder);
    ProfileAuditFilter filter = new ProfileAuditFilter(beans.getBeanProvider(AuditRecorder.class),
      beans.getBeanProvider(dev.alvo.pieria.config.AuditProperties.class));
    MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/v1/profiles/p/sources/s/secrets");
    request.setContentType("application/json");
    request.setContent("{\"secrets\":{\"token\":\"hunter2\"}}".getBytes(StandardCharsets.UTF_8));

    filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> req.getInputStream().readAllBytes());

    ArgumentCaptor<CapturedPayload> payload = ArgumentCaptor.forClass(CapturedPayload.class);
    verify(recorder).recordHttp(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
      org.mockito.ArgumentMatchers.anyInt(), payload.capture(), any(), any(), any());
    assertThat(payload.getValue().body()).doesNotContain("hunter2");
  }
}
