package dev.alvo.pieria.wiki;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Wiki query/descriptor policy, not a general text utility. */
public final class WikiText {
  private WikiText() {}
  public static String match(String text) {
    return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
      .filter(t->t.length()>1).distinct().limit(20).map(t->"\""+t+"\"").collect(Collectors.joining(" OR "));
  }
  public static String title(String anchor) {
    String name=anchor.replaceFirst("^(topic|entity|code|group|split|directory):","").replace(':',' ').replace('_',' ').replace('-',' ').strip();
    return name.isEmpty()?"Evidence":Character.toUpperCase(name.charAt(0))+name.substring(1);
  }
}
