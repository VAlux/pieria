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
 */
public record CodeIndexRequest(
  String treeHash,
  @NotEmpty @Valid List<FileDto> files) {

  public record FileDto(
    @NotBlank String repoRelPath,
    String language,
    String contentHash,
    @NotNull String content) {
  }
}
