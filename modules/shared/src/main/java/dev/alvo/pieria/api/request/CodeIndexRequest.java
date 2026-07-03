package dev.alvo.pieria.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Body of POST /v1/profiles/{name}/code: a batch of source files to index, plus the repo's git
 * tree/HEAD hash for status/freshness. {@code language}/{@code contentHash} may be blank — the
 * daemon detects the language by extension and content-addresses by a hash of the content.
 *
 * <p>{@code reindex} forces every file to be re-parsed even when its content hash is unchanged
 * (bypassing the skip-if-unchanged optimization). Use it after a parser/language-pack upgrade, when
 * unchanged source would otherwise be skipped and never re-indexed by the new parser.
 *
 * <p>{@code summarize} controls the LLM code-narrative pass after indexing (async endpoint only):
 * {@code null} follows the daemon's {@code pieria.code.summarization.enabled} config;
 * {@code true}/{@code false} force it on/off for this run.
 */
public record CodeIndexRequest(
  String treeHash,
  boolean reindex,
  Boolean summarize,
  @NotEmpty @Valid List<FileDto> files) {

  public record FileDto(
    @NotBlank String repoRelPath,
    String language,
    String contentHash,
    @NotNull String content) {
  }
}
