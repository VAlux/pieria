package dev.alvo.pieria.harvesters.confluence;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvesters.ConfigValues;
import dev.alvo.pieria.harvesters.atlassian.AtlassianSite;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** A Confluence source's settings, validated. */
public record ConfluenceConfig(AtlassianSite site, List<String> spaces, String cql, Duration sweepEvery) {

  private static final Pattern SPACE_KEY = Pattern.compile("[A-Za-z0-9_~-]+");
  private static final Pattern ORDER_BY = Pattern.compile("(?i)\\border\\s+by\\b");

  public static ConfluenceConfig parse(HarvestRequest request) throws HarvesterException {
    AtlassianSite site = AtlassianSite.parse(request.config(), request.secrets(), "/wiki");
    List<String> spaces = ConfigValues.strings(request.config(), "spaces").stream().map(String::strip).toList();
    for (String space : spaces) {
      // Keys are quoted into CQL, so they must not be able to close the quote.
      if (!SPACE_KEY.matcher(space).matches()) {
        throw ConfigValues.invalid(space + " is not a valid space key");
      }
    }
    String cql = ConfigValues.string(request.config(), "cql");
    if (spaces.isEmpty() && cql == null) {
      throw ConfigValues.invalid("spaces or cql is required");
    }
    if (cql != null && ORDER_BY.matcher(cql).find()) {
      throw ConfigValues.invalid("cql must not contain ORDER BY; the harvester orders results itself");
    }
    int sweepHours = ConfigValues.integer(request.config(), "sweepHours", 24, 1, 720);
    return new ConfluenceConfig(site, spaces, cql, Duration.ofHours(sweepHours));
  }

  /** The CQL every query starts from: pages and blog posts in the chosen spaces. */
  public String scope() {
    StringBuilder scope = new StringBuilder("type in (page, blogpost)");
    if (!spaces.isEmpty()) {
      scope.append(" AND space in (").append(spaces.stream().map(key -> "\"" + key + "\"").collect(Collectors.joining(","))).append(")");
    }
    if (cql != null) {
      scope.append(" AND (").append(cql).append(")");
    }
    return scope.toString();
  }
}
