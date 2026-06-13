package dev.alvo.pieria.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Fixed-vector tests for the Phase 13 code-index ids on {@link ContentId}.
 *
 * <p>As with {@link ContentIdTests}, the expected hex is HARD-CODED on purpose: these ids are
 * persisted and drive idempotent insert-or-ignore re-index. A change to the hashing scheme would
 * silently duplicate code-index rows; pinning the output makes any such change fail loudly.
 */
class ContentIdCodeTests {

  @Test
  void codeModuleIdMatchesFixedVector() {
    assertThat(ContentId.forCodeModule("prof-1", "modules/daemon"))
      .isEqualTo("109798ccb42198367a6b1a706c7b5c56");
  }

  @Test
  void codeFileIdIsPathStableAndMatchesFixedVector() {
    assertThat(ContentId.forCodeFile("prof-1", "modules/daemon/src/Main.java"))
      .isEqualTo("0bf6de90833aa9301f2347d43a206995");
  }

  @Test
  void codeSymbolIdMatchesFixedVector() {
    assertThat(ContentId.forCodeSymbol("prof-1", "file-1", "method", "com.x.Bar#create", "create(Req):Resp"))
      .isEqualTo("89b46d313d6b00526600ea6419ecfbd0");
  }

  @Test
  void codeEdgeIdMatchesFixedVector() {
    assertThat(ContentId.forCodeEdge("prof-1", "sym-1", "calls", "create", "resolved"))
      .isEqualTo("9aea709206206501a7a8332c0d603713");
  }

  @Test
  void codeSymbolIdVariesWithSignatureSoOverloadsStayDistinct() {
    String a = ContentId.forCodeSymbol("prof-1", "file-1", "method", "com.x.Bar#create", "create(Req):Resp");
    String b = ContentId.forCodeSymbol("prof-1", "file-1", "method", "com.x.Bar#create", "create(Req,Opts):Resp");
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void codeEdgeIdVariesWithConfidence() {
    String resolved = ContentId.forCodeEdge("prof-1", "sym-1", "calls", "create", "resolved");
    String heuristic = ContentId.forCodeEdge("prof-1", "sym-1", "calls", "create", "heuristic");
    assertThat(resolved).isNotEqualTo(heuristic);
    assertThat(heuristic).isEqualTo("a5b704a6740eb1a23e2f52cdc8ec1c1f");
  }
}
