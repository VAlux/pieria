package dev.alvo.pieria.api.conversion;

import dev.alvo.pieria.api.response.ExportLineResponse;
import dev.alvo.pieria.api.response.ExportLineResponse.ExportMemory;
import dev.alvo.pieria.domain.ExportRow;
import dev.alvo.pieria.domain.memory.Memory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Maps a daemon {@link ExportRow} into the wire {@link ExportLineResponse}, one line of an NDJSON
 * profile export.
 */
@Component
public final class ExportLineResponseConverter implements Converter<ExportRow, ExportLineResponse> {

  @Override
  public ExportLineResponse convert(ExportRow row) {
    Memory memory = row.memory();

    ExportMemory exportMemory = new ExportMemory(
      memory.id(),
      memory.type() == null ? null : memory.type().wire(),
      memory.content(),
      memory.topicKey(),
      memory.sessionId(),
      memory.superseded(),
      memory.payload(),
      memory.createdAt() == null ? null : memory.createdAt().toString());

    return new ExportLineResponse(row.profileName(), exportMemory);
  }
}
