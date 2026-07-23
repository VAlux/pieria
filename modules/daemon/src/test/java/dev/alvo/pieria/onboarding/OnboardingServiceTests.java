package dev.alvo.pieria.onboarding;

import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link OnboardingService}: routing a spec to the source that claims its type, and
 * failing clearly on an unhandled type or a duplicate registration.
 */
class OnboardingServiceTests {

  /** A stub source for one spec subtype that records the ingest and returns a marker result. */
  private static final class StubSource<S extends SourceSpec> implements OnboardingSource<S> {
    private final Class<S> type;
    private final String marker;
    boolean ingested;

    StubSource(Class<S> type, String marker) {
      this.type = type;
      this.marker = marker;
    }

    @Override
    public Class<S> specType() {
      return type;
    }

    @Override
    public OnboardingWork begin(String profile, S spec, IngestProgressListener progress) {
      ingested = true;
      return OnboardingWork.completed(OnboardResult.content(marker, 1, 1, 0));
    }
  }

  @Test
  void routesSpecToTheSourceThatClaimsItsType() {
    StubSource<SourceSpec.Markdown> markdown = new StubSource<>(SourceSpec.Markdown.class, "md");
    StubSource<SourceSpec.Web> web = new StubSource<>(SourceSpec.Web.class, "web");
    OnboardingService service = new OnboardingService(List.of(markdown, web));

    OnboardResult result = service.begin("p",
      new SourceSpec.Web(List.of("https://x"), null, null), IngestProgressListener.noop())
      .finish(IngestProgressListener.noop());

    assertThat(result.sourceType()).isEqualTo("web");
    assertThat(web.ingested).isTrue();
    assertThat(markdown.ingested).isFalse();
  }

  @Test
  void routesPdfSpecToThePdfSource() {
    StubSource<SourceSpec.Markdown> markdown = new StubSource<>(SourceSpec.Markdown.class, "md");
    StubSource<SourceSpec.Pdf> pdf = new StubSource<>(SourceSpec.Pdf.class, "pdf");
    OnboardingService service = new OnboardingService(List.of(markdown, pdf));

    OnboardResult result = service.begin("p",
      new SourceSpec.Pdf("/abs/proj", null, null), IngestProgressListener.noop())
      .finish(IngestProgressListener.noop());

    assertThat(result.sourceType()).isEqualTo("pdf");
    assertThat(pdf.ingested).isTrue();
    assertThat(markdown.ingested).isFalse();
  }

  @Test
  void unhandledSpecTypeIsRejected() {
    OnboardingService service = new OnboardingService(
      List.of(new StubSource<>(SourceSpec.Markdown.class, "md")));

    assertThatThrownBy(() -> service.begin("p",
      new SourceSpec.Web(List.of("https://x"), null, null), IngestProgressListener.noop()))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("no onboarding source");
  }

  @Test
  void duplicateSpecTypeRegistrationFailsFast() {
    assertThatThrownBy(() -> new OnboardingService(List.of(
      new StubSource<>(SourceSpec.Markdown.class, "a"),
      new StubSource<>(SourceSpec.Markdown.class, "b"))))
      .isInstanceOf(IllegalStateException.class);
  }
}
