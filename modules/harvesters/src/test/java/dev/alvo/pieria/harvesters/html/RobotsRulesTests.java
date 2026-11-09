package dev.alvo.pieria.harvesters.html;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

class RobotsRulesTests {

  private static RobotsRules rules(String text) {
    return RobotsRules.parse(text, "PieriaHarvester");
  }

  @Test
  void disallowsAPrefix() {
    RobotsRules rules = rules("User-agent: *\nDisallow: /private\n");

    assertThat(rules.allows("/private/a")).isFalse();
    assertThat(rules.allows("/public")).isTrue();
  }

  @Test
  void anEmptyDisallowAllowsEverything() {
    assertThat(rules("User-agent: *\nDisallow:\n").allows("/a")).isTrue();
  }

  @Test
  void longestMatchWins() {
    RobotsRules rules = rules("User-agent: *\nDisallow: /a\nAllow: /a/b\n");

    assertThat(rules.allows("/a/b/c")).isTrue();
    assertThat(rules.allows("/a/c")).isFalse();
  }

  @Test
  void allowWinsATie() {
    assertThat(rules("User-agent: *\nDisallow: /a\nAllow: /a\n").allows("/a")).isTrue();
  }

  @Test
  void wildcardAndAnchor() {
    RobotsRules rules = rules("User-agent: *\nDisallow: /*.pdf$\n");

    assertThat(rules.allows("/x/y.pdf")).isFalse();
    assertThat(rules.allows("/x/y.pdf?z=1")).isTrue();
  }

  @Test
  void prefersItsOwnGroupOverStar() {
    RobotsRules rules = rules("User-agent: *\nDisallow: /\n\nUser-agent: pieriaharvester\nDisallow: /only\n");

    assertThat(rules.allows("/a")).isTrue();
    assertThat(rules.allows("/only")).isFalse();
  }

  @Test
  void sharedUserAgentLines() {
    assertThat(rules("User-agent: other\nUser-agent: PieriaHarvester\nDisallow: /a\n").allows("/a")).isFalse();
  }

  @Test
  void ignoresOtherAgentsAndComments() {
    assertThat(rules("# note\nUser-agent: other\nDisallow: /\n").allows("/a")).isTrue();
  }

  @Test
  void crawlDelayIsReadAndCapped() {
    assertThat(rules("User-agent: *\nCrawl-delay: 2\n").crawlDelayMillis()).isEqualTo(2000);
    assertThat(rules("User-agent: *\nCrawl-delay: 999\n").crawlDelayMillis()).isEqualTo(60_000);
    assertThat(rules("User-agent: *\nDisallow: /x\n").crawlDelayMillis()).isZero();
  }

  @Test
  void garbageAllowsEverything() {
    assertThat(rules("<html>not robots</html>").allows("/a")).isTrue();
  }

  @Test
  void constantsBehaveAsNamed() {
    assertThat(RobotsRules.ALLOW_ALL.allows("/a")).isTrue();
    assertThat(RobotsRules.DENY_ALL.allows("/a")).isFalse();
  }

  @Test
  @Timeout(5)
  void aPathologicalPatternCannotStallTheCrawl() {
    RobotsRules rules = rules("User-agent: *\nDisallow: /" + "*a".repeat(200) + "*b\n");

    assertThat(rules.allows("/" + "a".repeat(380))).isTrue();
  }
}
