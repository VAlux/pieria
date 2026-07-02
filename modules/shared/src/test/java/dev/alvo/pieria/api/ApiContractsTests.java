package dev.alvo.pieria.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.alvo.pieria.api.request.CodeIndexRequest;
import dev.alvo.pieria.api.request.IngestRequest;
import dev.alvo.pieria.api.request.RecallRequest;
import dev.alvo.pieria.api.request.RememberRequest;
import dev.alvo.pieria.api.response.CodeIndexResponse;
import dev.alvo.pieria.api.response.ErrorResponse;
import dev.alvo.pieria.api.response.RecallResponse;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies on the JVM that {@link ApiContracts} discovers every request/response DTO — including
 * nested records — that {@link ApiContractFeature} would register for native reflection. This is the
 * safety net for the Feature: the {@code RuntimeReflection} calls only fire during nativeCompile, but
 * the discovery they depend on is exercised here.
 */
class ApiContractsTests {

  @Test
  void discoversEveryRequestAndResponseDtoIncludingNestedRecords() {
    Set<Class<?>> types = ApiContracts.all(getClass().getClassLoader());

    assertThat(types).contains(
      // top-level requests
      CodeIndexRequest.class,
      IngestRequest.class,
      RecallRequest.class,
      RememberRequest.class,
      // nested request records (the case that triggered the original native-image failure)
      CodeIndexRequest.FileDto.class,
      IngestRequest.MessageDto.class,
      // responses, incl. deeply nested records
      CodeIndexResponse.class,
      ErrorResponse.class,
      RecallResponse.class,
      RecallResponse.CodeEvidence.class,
      RecallResponse.RecallDebug.class,
      RecallResponse.RecallDebug.Provenance.class,
      RecallResponse.RecallDebug.ChannelDiagnostic.class);
  }

  @Test
  void discoversOnlyTypesInTheTwoContractPackages() {
    Set<Class<?>> types = ApiContracts.all(getClass().getClassLoader());

    assertThat(types).isNotEmpty();
    assertThat(types).allSatisfy(type -> assertThat(type.getName())
      .matches("dev\\.alvo\\.pieria\\.api\\.(request|response)\\..+"));
  }
}
