package dev.alvo.pieria.harvesters.html;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** What a site's robots.txt lets this crawler fetch, following RFC 9309. */
public final class RobotsRules {

  public static final RobotsRules ALLOW_ALL = new RobotsRules(List.of(), 0);
  public static final RobotsRules DENY_ALL = new RobotsRules(List.of(new Rule(false, "/")), 0);

  private static final int MAX_CHARS = 500 * 1024;
  private static final long MAX_DELAY_MILLIS = 60_000;

  private final List<Rule> rules;
  private final long crawlDelayMillis;

  private RobotsRules(List<Rule> rules, long crawlDelayMillis) {
    this.rules = rules;
    this.crawlDelayMillis = crawlDelayMillis;
  }

  private record Rule(boolean allow, String pattern) {
  }

  private static final class Group {
    final List<String> agents = new ArrayList<>();
    final List<Rule> rules = new ArrayList<>();
    long delayMillis;
    boolean closed;
  }

  public static RobotsRules parse(String text, String agentToken) {
    List<Group> groups = groups(text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text);
    String token = agentToken.toLowerCase(Locale.ROOT);
    List<Group> selected = groups.stream().filter(group -> group.agents.contains(token)).toList();
    if (selected.isEmpty()) {
      selected = groups.stream().filter(group -> group.agents.contains("*")).toList();
    }
    return new RobotsRules(selected.stream().flatMap(group -> group.rules.stream()).toList(),
      selected.stream().mapToLong(group -> group.delayMillis).max().orElse(0));
  }

  private static List<Group> groups(String text) {
    List<Group> groups = new ArrayList<>();
    Group current = null;
    for (String raw : text.split("\\R")) {
      String line = raw.split("#", 2)[0];
      int colon = line.indexOf(':');
      if (colon < 0) {
        continue;
      }
      String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
      String value = line.substring(colon + 1).strip();
      if (key.equals("user-agent")) {
        if (current == null || current.closed) {
          current = new Group();
          groups.add(current);
        }
        current.agents.add(value.toLowerCase(Locale.ROOT));
      } else if (current != null) {
        current.closed = true;
        apply(current, key, value);
      }
    }
    return groups;
  }

  private static void apply(Group group, String key, String value) {
    switch (key) {
      case "allow", "disallow" -> {
        if (!value.isEmpty()) {
          group.rules.add(new Rule(key.equals("allow"), value));
        }
      }
      case "crawl-delay" -> {
        try {
          group.delayMillis = Math.clamp((long) (Double.parseDouble(value) * 1000), 0, MAX_DELAY_MILLIS);
        } catch (NumberFormatException e) {
          // An unreadable delay is treated as none.
        }
      }
      default -> {
      }
    }
  }

  /** @param pathAndQuery the URL from its path on, such as {@code /a/b?c=1} */
  public boolean allows(String pathAndQuery) {
    Rule best = null;
    for (Rule rule : rules) {
      if (matches(rule.pattern(), pathAndQuery) && (best == null || rule.pattern().length() > best.pattern().length()
        || (rule.pattern().length() == best.pattern().length() && rule.allow()))) {
        best = rule;
      }
    }
    return best == null || best.allow();
  }

  public long crawlDelayMillis() {
    return crawlDelayMillis;
  }

  /**
   * Matches without a regular expression. The pattern comes from the site, and backtracking over
   * many wildcards could otherwise stall the crawl.
   */
  private static boolean matches(String rawPattern, String path) {
    boolean anchored = rawPattern.endsWith("$");
    String pattern = anchored ? rawPattern.substring(0, rawPattern.length() - 1) : rawPattern;
    int p = 0;
    int s = 0;
    int star = -1;
    int mark = 0;
    while (s < path.length()) {
      if (p < pattern.length() && pattern.charAt(p) == '*') {
        star = p++;
        mark = s;
      } else if (p < pattern.length() && pattern.charAt(p) == path.charAt(s)) {
        p++;
        s++;
      } else if (p == pattern.length() && !anchored) {
        return true;
      } else if (star >= 0) {
        p = star + 1;
        s = ++mark;
      } else {
        return false;
      }
    }
    while (p < pattern.length() && pattern.charAt(p) == '*') {
      p++;
    }
    return p == pattern.length();
  }
}
