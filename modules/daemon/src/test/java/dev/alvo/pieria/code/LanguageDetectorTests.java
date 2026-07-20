package dev.alvo.pieria.code;

import dev.alvo.pieria.config.model.DiscoveryConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageDetectorTests {

  @ParameterizedTest
  @CsvSource({
    "src/Main.JAVA,java",
    "src/Main.KT,kotlin",
    "build.gradle.KTS,kotlin",
    "src/Main.SCALA,scala",
    "script.SC,scala",
    "web/app.JS,javascript",
    "web/app.JSX,javascript",
    "web/app.MJS,javascript",
    "web/app.CJS,javascript",
    "web/app.TS,typescript",
    "web/app.MTS,typescript",
    "web/app.CTS,typescript",
    "web/app.TSX,tsx",
    "styles/app.SCSS,scss",
    "src/app.PY,python",
    "src/main.GO,go",
    "src/lib.RS,rust",
    "lib/app.RB,ruby",
    "public/index.PHP,php",
    "src/App.CS,csharp",
    "src/main.C,c",
    "include/main.H,c",
    "src/main.CPP,cpp",
    "src/main.CC,cpp",
    "include/main.HPP,cpp",
    "src/App.SWIFT,swift"
  })
  void detectsEveryPackCaseInsensitively(String path, String language) {
    assertThat(LanguageDetector.detect(path)).isEqualTo(language);
  }

  @Test
  void everyDefaultSourceExtensionHasALanguagePack() {
    assertThat(LanguagePackRegistry.byExtension().keySet())
      .containsExactlyInAnyOrderElementsOf(DiscoveryConfig.DEFAULT_SOURCE_EXTENSIONS);
  }
}
