package dev.alvo.pieria.harvesters.html;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class CrawlScopeTests {

  private static CrawlScope scope(Set<String> hosts, List<Pattern> include, List<Pattern> exclude) {
    return new CrawlScope(new CrawlConfig(List.of("https://x.test/"), List.of(), hosts, include, exclude, 3, 500, 0, false));
  }

  @ParameterizedTest
  @CsvSource({
    "HTTP://Example.COM:80/a#f, http://example.com/a",
    "https://example.com:443/, https://example.com/",
    "https://example.com, https://example.com/",
    "https://example.com:8443/a?b=1, https://example.com:8443/a?b=1",
    "https://example.com/a b, https://example.com/a%20b"
  })
  void normalizes(String in, String out) {
    assertThat(CrawlScope.normalize(in)).isEqualTo(out);
  }

  @Test
  void nonAsciiPathsBecomeAscii() {
    assertThat(CrawlScope.normalize("https://x.test/café")).isEqualTo("https://x.test/caf%C3%A9");
  }

  @ParameterizedTest
  @ValueSource(strings = {"ftp://x.test/", "http://u:p@x.test/", "not a url", "/relative", "javascript:alert(1)", "mailto:a@b.test", ""})
  void refuses(String in) {
    assertThat(CrawlScope.normalize(in)).isNull();
  }

  @Test
  void refusesAnOverlongUrl() {
    String prefix = "https://x.test/";
    assertThat(CrawlScope.normalize(prefix + "a".repeat(400 - prefix.length()))).isNotNull();
    assertThat(CrawlScope.normalize(prefix + "a".repeat(401 - prefix.length()))).isNull();
  }

  @Test
  void anExactHostMatchesOnlyItself() {
    CrawlScope scope = scope(Set.of("x.test"), List.of(), List.of());

    assertThat(scope.allows("https://x.test/a")).isTrue();
    assertThat(scope.allows("https://y.x.test/a")).isFalse();
  }

  @Test
  void wildcardHostMatchesSubdomainsOnly() {
    CrawlScope scope = scope(Set.of("*.example.com"), List.of(), List.of());

    assertThat(scope.allows("https://docs.example.com/")).isTrue();
    assertThat(scope.allows("https://example.com/")).isFalse();
    assertThat(scope.allows("https://notexample.com/")).isFalse();
  }

  @Test
  void includeAndExcludeApply() {
    CrawlScope scope = scope(Set.of("x.test"), List.of(Pattern.compile("/docs/")), List.of(Pattern.compile("\\.zip$")));

    assertThat(scope.allows("https://x.test/docs/a")).isTrue();
    assertThat(scope.allows("https://x.test/blog/a")).isFalse();
    assertThat(scope.allows("https://x.test/docs/a.zip")).isFalse();
  }
}
