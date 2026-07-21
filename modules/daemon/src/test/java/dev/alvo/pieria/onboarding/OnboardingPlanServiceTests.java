package dev.alvo.pieria.onboarding;

import dev.alvo.pieria.api.request.OnboardPlanRequest;
import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.reminiscence.ReminiscenceService;
import dev.alvo.pieria.task.TaskCancelledException;
import dev.alvo.pieria.task.TaskRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnboardingPlanServiceTests {

  @Test
  void processesSourcesInOrderPrefixesProgressAndSubmitsOneGraphTask() {
    List<String> order = new ArrayList<>();
    OnboardingService routing = new OnboardingService(List.of(
      source(SourceSpec.Markdown.class, "markdown", order, 2),
      source(SourceSpec.Text.class, "text", order, 3)));
    ReminiscenceService reminiscence = mock(ReminiscenceService.class);
    when(reminiscence.countOnboardingOrphans("p")).thenReturn(5L);
    TaskRegistry tasks = mock(TaskRegistry.class);
    UUID graphId = UUID.randomUUID();
    when(tasks.submit(eq("onboard-graph"), eq("p"), any())).thenReturn(graphId);
    OnboardingPlanService service = new OnboardingPlanService(
      routing, reminiscence, tasks, new ObjectMapper());
    List<String> phases = new ArrayList<>();

    OnboardPlanResult result = service.ingest("p", new OnboardPlanRequest(List.of(
      new SourceSpec.Markdown("/p", false, null, null),
      new SourceSpec.Text("/p", null, null)), true),
      (phase, done, total) -> phases.add(phase));

    assertThat(order).containsExactly("markdown", "text");
    assertThat(phases).anyMatch(p -> p.startsWith("source 1/2 markdown:"))
      .anyMatch(p -> p.startsWith("source 2/2 text:"));
    assertThat(result.memoriesStored()).isEqualTo(5);
    assertThat(result.graphDeferred()).isEqualTo(5);
    assertThat(result.graphCandidates()).isEqualTo(5);
    assertThat(result.graphEnrichmentTaskId()).isEqualTo(graphId.toString());
    verify(tasks).submit(eq("onboard-graph"), eq("p"), any());
  }

  @Test
  void accumulatesSourceFailuresContinuesAndStartsGraphEnrichment() {
    List<String> order = new ArrayList<>();
    OnboardingSource<SourceSpec.Markdown> first = source(
      SourceSpec.Markdown.class, "markdown", order, 1);
    OnboardingSource<SourceSpec.Text> failingText = new OnboardingSource<>() {
      public Class<SourceSpec.Text> specType() { return SourceSpec.Text.class; }
      public OnboardResult ingest(String profile, SourceSpec.Text spec, IngestProgressListener progress) {
        order.add("text");
        throw new IllegalStateException("broken source");
      }
    };
    OnboardingSource<SourceSpec.Pdf> failingPdf = new OnboardingSource<>() {
      public Class<SourceSpec.Pdf> specType() { return SourceSpec.Pdf.class; }
      public OnboardResult ingest(String profile, SourceSpec.Pdf spec, IngestProgressListener progress) {
        order.add("pdf");
        throw new UnsatisfiedLinkError("Can't load library: awt");
      }
    };
    OnboardingSource<SourceSpec.SourceCode> last = new OnboardingSource<>() {
      public Class<SourceSpec.SourceCode> specType() { return SourceSpec.SourceCode.class; }
      public OnboardResult ingest(String profile, SourceSpec.SourceCode spec, IngestProgressListener progress) {
        order.add("source-code");
        return OnboardResult.code(2, 2, 8, 3, 0);
      }
    };
    ReminiscenceService reminiscence = mock(ReminiscenceService.class);
    when(reminiscence.countOnboardingOrphans("p")).thenReturn(2L);
    TaskRegistry tasks = mock(TaskRegistry.class);
    UUID graphId = UUID.randomUUID();
    when(tasks.submit(eq("onboard-graph"), eq("p"), any())).thenReturn(graphId);
    OnboardingPlanService service = new OnboardingPlanService(
      new OnboardingService(List.of(first, failingText, failingPdf, last)),
      reminiscence, tasks, new ObjectMapper());

    OnboardPlanResult result = service.ingest("p", new OnboardPlanRequest(List.of(
      new SourceSpec.Markdown("/p", false, null, null),
      new SourceSpec.Text("/p", null, null),
      new SourceSpec.Pdf("/p", null, null),
      new SourceSpec.SourceCode("/p", false, false, null)), true), IngestProgressListener.noop());

    assertThat(order).containsExactly("markdown", "text", "pdf", "source-code");
    assertThat(result.sources()).extracting(OnboardResult::sourceType)
      .containsExactly("markdown", "source-code");
    assertThat(result.memoriesStored()).isEqualTo(3);
    assertThat(result.symbols()).isEqualTo(8);
    assertThat(result.errors()).containsExactly(
      new OnboardError(2, "text", "IllegalStateException", "broken source"),
      new OnboardError(3, "pdf", "UnsatisfiedLinkError", "Can't load library: awt"));
    assertThat(result.graphEnrichmentTaskId()).isEqualTo(graphId.toString());
    verify(tasks).submit(eq("onboard-graph"), eq("p"), any());
  }

  @Test
  void cancellationStillStopsThePlanImmediately() {
    List<String> order = new ArrayList<>();
    OnboardingSource<SourceSpec.Markdown> cancelled = new OnboardingSource<>() {
      public Class<SourceSpec.Markdown> specType() { return SourceSpec.Markdown.class; }
      public OnboardResult ingest(String profile, SourceSpec.Markdown spec, IngestProgressListener progress) {
        order.add("markdown");
        throw new TaskCancelledException();
      }
    };
    OnboardingSource<SourceSpec.Text> later = source(SourceSpec.Text.class, "text", order, 1);
    ReminiscenceService reminiscence = mock(ReminiscenceService.class);
    TaskRegistry tasks = mock(TaskRegistry.class);
    OnboardingPlanService service = new OnboardingPlanService(
      new OnboardingService(List.of(cancelled, later)), reminiscence, tasks, new ObjectMapper());

    assertThatThrownBy(() -> service.ingest("p", new OnboardPlanRequest(List.of(
      new SourceSpec.Markdown("/p", false, null, null),
      new SourceSpec.Text("/p", null, null)), true), IngestProgressListener.noop()))
      .isInstanceOf(TaskCancelledException.class);

    assertThat(order).containsExactly("markdown");
    verify(reminiscence, never()).countOnboardingOrphans(any());
    verify(tasks, never()).submit(any(), any(), any());
  }

  private static <S extends SourceSpec> OnboardingSource<S> source(
    Class<S> type, String name, List<String> order, int memories) {
    return new OnboardingSource<>() {
      public Class<S> specType() { return type; }
      public OnboardResult ingest(String profile, S spec, IngestProgressListener progress) {
        order.add(name);
        progress.onPhase("extract", 1, 1);
        return OnboardResult.content(name, 1, memories, 0, memories);
      }
    };
  }
}
