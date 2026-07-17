package dev.alvo.pieria.api.request;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.alvo.pieria.config.model.DiscoveryConfig;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * One entry in {@link OnboardPlanRequest#sources()}: an <em>onboarding source</em> the daemon should
 * ingest into the profile. This is the extension point for "where memories come from"
 * — markdown docs, source code, a web page — behind one polymorphic contract. Adding a new kind of
 * source is a new subtype here plus a matching {@code OnboardingSource} on the daemon; nothing else
 * on the wire changes.
 *
 * <p>The daemon does the discovery and reading itself (it runs {@code git ls-files} under
 * {@code root} / fetches {@code urls}), so a client only names the source — it needs no filesystem
 * or network access of its own. The {@code type} discriminator selects the subtype.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = SourceSpec.Markdown.class, name = "markdown"),
  @JsonSubTypes.Type(value = SourceSpec.SourceCode.class, name = "source-code"),
  @JsonSubTypes.Type(value = SourceSpec.Web.class, name = "web"),
  @JsonSubTypes.Type(value = SourceSpec.Pdf.class, name = "pdf"),
  @JsonSubTypes.Type(value = SourceSpec.Text.class, name = "text"),
})
public sealed interface SourceSpec {

  /**
   * Seed a profile from a project's markdown documentation. The daemon enumerates {@code *.md} under
   * {@code root} (via {@code git ls-files}, filesystem-walk fallback) and feeds each doc through the
   * memory-extraction pipeline.
   *
   * @param root              absolute path to a directory to scan, or to a single {@code *.md} file
   *                          to ingest
   * @param includeAgentDocs  when true, also seed {@code CLAUDE.md}/{@code AGENTS.md} (excluded by
   *                          default as already-in-context for harnesses)
   * @param extractionSamples independent extract passes per chunk (null ⇒ profile default)
   * @param refresh           re-ingest every document even when unchanged since the last onboard
   *                          (null ⇒ false)
   */
  record Markdown(
    @NotBlank String root,
    boolean includeAgentDocs,
    @Positive Integer extractionSamples,
    Boolean refresh) implements SourceSpec {
  }

  /**
   * Build the source-code intelligence index from a project's tracked source files. Targets the
   * code-index pipeline (symbols, edges, optional narrative summaries), not memory extraction.
   *
   * @param root      absolute path to the project directory to scan
   * @param reindex   re-parse every file even when unchanged (use after a parser upgrade)
   * @param summarize run the LLM narrative summarization pass (null ⇒ daemon config)
   * @param discovery which files count as source ({@code [discovery]} resolved client-side; null ⇒
   *                  the daemon's code-baked defaults)
   */
  record SourceCode(
    @NotBlank String root,
    boolean reindex,
    Boolean summarize,
    DiscoveryConfig discovery) implements SourceSpec {
  }

  /**
   * Seed a profile from one or more web pages. The daemon fetches each URL, extracts the main text,
   * and feeds it through the memory-extraction pipeline.
   *
   * @param urls              the pages to fetch (each an absolute http(s) URL)
   * @param extractionSamples independent extract passes per chunk (null ⇒ profile default)
   * @param refresh           re-ingest every page even when its content is unchanged since the
   *                          last onboard (null ⇒ false)
   */
  record Web(
    @NotEmpty List<@NotBlank String> urls,
    @Positive Integer extractionSamples,
    Boolean refresh) implements SourceSpec {
  }

  /**
   * Seed a profile from a project's PDF documents. The daemon enumerates {@code *.pdf} under
   * {@code root} (via {@code git ls-files}, filesystem-walk fallback), extracts each document's text,
   * and feeds it through the memory-extraction pipeline.
   *
   * @param root              absolute path to a directory to scan, or to a single {@code *.pdf} file
   *                          to ingest
   * @param extractionSamples independent extract passes per chunk (null ⇒ profile default)
   * @param refresh           re-ingest every document even when unchanged since the last onboard
   *                          (null ⇒ false)
   */
  record Pdf(
    @NotBlank String root,
    @Positive Integer extractionSamples,
    Boolean refresh) implements SourceSpec {
  }

  /**
   * Seed a profile from a project's plain-text documents. The daemon enumerates {@code *.txt} under
   * {@code root} (via {@code git ls-files}, filesystem-walk fallback), reads each document's text,
   * and feeds it through the memory-extraction pipeline.
   *
   * @param root              absolute path to a directory to scan, or to a single {@code *.txt} file
   *                          to ingest
   * @param extractionSamples independent extract passes per chunk (null ⇒ profile default)
   * @param refresh           re-ingest every document even when unchanged since the last onboard
   *                          (null ⇒ false)
   */
  record Text(
    @NotBlank String root,
    @Positive Integer extractionSamples,
    Boolean refresh) implements SourceSpec {
  }
}
