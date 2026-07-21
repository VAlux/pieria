package dev.alvo.pieria.audit;

import dev.alvo.pieria.task.TaskRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class TaskAuditTests {
  @Test
  void terminalTaskInheritsSubmissionCorrelation() throws Exception {
    AuditRecorder recorder = mock(AuditRecorder.class);
    TaskRegistry tasks = new TaskRegistry(recorder);
    AuditRequestContext context = new AuditRequestContext("alpha", "ingest.async", "request-1",
      new AuditCaller("gateway", "codex", "mcp", "1", "127.0.0.1"));
    AuditRequestContext.set(context);
    String taskId;
    try {
      taskId = tasks.submit("ingest", "alpha", _ -> JsonNodeFactory.instance.objectNode().put("count", 2)).toString();
    } finally {
      AuditRequestContext.clear();
    }

    verify(recorder, timeout(Duration.ofSeconds(2).toMillis())).recordTaskTerminal(
      eq(context), eq(taskId), eq("ingest"), org.mockito.ArgumentMatchers.any(),
      org.mockito.ArgumentMatchers.any(), eq("SUCCEEDED"), org.mockito.ArgumentMatchers.any(),
      isNull(), isNull());
    tasks.shutdown();
  }
}
