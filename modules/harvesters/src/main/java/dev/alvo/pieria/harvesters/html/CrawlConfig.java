package dev.alvo.pieria.harvesters.html;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvesters.ConfigValues;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** A crawl source's settings, validated. The defaults live here, because the daemon doesn't apply them. */
public record CrawlConfig(List<String> seeds, List<String> sitemaps, Set<String> allowedHosts, List<Pattern> include,
                          List<Pattern> exclude, int maxDepth, int maxPages, long delayMillis, boolean allowPrivate) {

  public static CrawlConfig parse(ObjectNode config) throws HarvesterException {
    List<String> seeds = urls(config, "seeds");
    List<String> sitemaps = urls(config, "sitemaps");
    if (seeds.isEmpty() && sitemaps.isEmpty()) {
      throw ConfigValues.invalid("seeds or sitemaps is required");
    }
    CrawlConfig parsed = new CrawlConfig(seeds, sitemaps, allowedHosts(config, seeds, sitemaps), patterns(config, "include"),
      patterns(config, "exclude"), ConfigValues.integer(config, "maxDepth", 3, 0, 50), ConfigValues.integer(config, "maxPages", 500, 1, 2000),
      ConfigValues.integer(config, "delayMillis", 1000, 0, 60_000), ConfigValues.flag(config, "allowPrivate"));
    // Otherwise the crawl silently drops the seed and fails as if the site were down, retrying forever.
    CrawlScope scope = new CrawlScope(parsed);
    for (String seed : seeds) {
      if (!scope.allows(seed)) {
        throw ConfigValues.invalid("the seed " + seed + " is outside the allowed hosts or patterns");
      }
    }
    return parsed;
  }

  private static List<String> urls(ObjectNode config, String key) throws HarvesterException {
    List<String> urls = new ArrayList<>();
    for (String value : ConfigValues.strings(config, key)) {
      String normalized = CrawlScope.normalize(value);
      if (normalized == null) {
        throw ConfigValues.invalid(key + " has an invalid URL: " + value);
      }
      urls.add(normalized);
    }
    return List.copyOf(urls);
  }

  private static Set<String> allowedHosts(ObjectNode config, List<String> seeds, List<String> sitemaps) throws HarvesterException {
    Set<String> hosts = new LinkedHashSet<>();
    for (String host : ConfigValues.strings(config, "allowedHosts")) {
      if (host.contains("/") || host.contains(":")) {
        throw ConfigValues.invalid("allowedHosts takes host names, not URLs: " + host);
      }
      hosts.add(host.strip().toLowerCase(Locale.ROOT));
    }
    if (hosts.isEmpty()) {
      seeds.forEach(url -> hosts.add(URI.create(url).getHost()));
      sitemaps.forEach(url -> hosts.add(URI.create(url).getHost()));
    }
    return Set.copyOf(hosts);
  }

  private static List<Pattern> patterns(ObjectNode config, String key) throws HarvesterException {
    List<Pattern> patterns = new ArrayList<>();
    for (String value : ConfigValues.strings(config, key)) {
      try {
        patterns.add(Pattern.compile(value));
      } catch (PatternSyntaxException e) {
        throw ConfigValues.invalid(key + " has an invalid pattern: " + value);
      }
    }
    return List.copyOf(patterns);
  }

}
