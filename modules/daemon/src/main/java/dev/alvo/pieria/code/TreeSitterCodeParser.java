package dev.alvo.pieria.code;

import org.springframework.stereotype.Component;

/**
 * Tree-sitter-backed parser that delegates capture interpretation to the selected language pack.
 * Native/runtime failures degrade to an empty result for that file and never fail onboarding.
 */
@Component
public class TreeSitterCodeParser implements CodeParser {

  private final TreeSitterEngine engine;

  public TreeSitterCodeParser(TreeSitterEngine engine) {
    this.engine = engine;
  }

  @Override
  public boolean supports(String language) {
    return engine.supports(language);
  }

  @Override
  public ParseResult parse(ParseInput input) {
    if (input == null || input.content() == null || !engine.supports(input.language())) {
      return ParseResult.empty();
    }
    LanguagePack pack = LanguagePackRegistry.find(input.language()).orElse(null);
    if (pack == null) {
      return ParseResult.empty();
    }
    return engine.parse(pack.id(), input.content(),
        (root, query, _) -> pack.extractor().extract(input, root, query))
      .orElse(ParseResult.empty());
  }
}
