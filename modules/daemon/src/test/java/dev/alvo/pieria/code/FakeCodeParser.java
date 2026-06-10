package dev.alvo.pieria.code;

import dev.alvo.pieria.code.CodeParser.ParseResult;

import java.util.HashMap;
import java.util.Map;

/**
 * Deterministic {@link CodeParser} test double: returns a pre-registered {@link ParseResult} per
 * repo-relative path (empty when none registered). Keeps the whole Phase 13 pipeline testable
 * without Tree-sitter or native libraries.
 */
public final class FakeCodeParser implements CodeParser {

  private final String language;
  private final Map<String, ParseResult> byPath = new HashMap<>();

  public FakeCodeParser(String language) {
    this.language = language;
  }

  public FakeCodeParser register(String repoRelPath, ParseResult result) {
    byPath.put(repoRelPath, result);
    return this;
  }

  @Override
  public boolean supports(String language) {
    return this.language.equals(language);
  }

  @Override
  public ParseResult parse(ParseInput input) {
    return byPath.getOrDefault(input.repoRelPath(), ParseResult.empty());
  }
}
