package dev.alvo.pieria.harvesters.jira;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvesters.ConfigValues;
import dev.alvo.pieria.harvesters.atlassian.AtlassianSite;

import java.time.Duration;
import java.util.regex.Pattern;

/** A Jira source's settings, validated. */
public record JiraConfig(AtlassianSite site, String jql, Duration sweepEvery) {

  static final Pattern ORDER_BY = Pattern.compile("(?i)\\border\\s+by\\b");

  public static JiraConfig parse(HarvestRequest request) throws HarvesterException {
    AtlassianSite site = AtlassianSite.parse(request.config(), request.secrets(), "");
    String jql = ConfigValues.requiredString(request.config(), "jql");
    if (ORDER_BY.matcher(jql).find()) {
      throw ConfigValues.invalid("jql must not contain ORDER BY; the harvester orders results itself");
    }
    int sweepHours = ConfigValues.integer(request.config(), "sweepHours", 24, 1, 720);
    return new JiraConfig(site, jql, Duration.ofHours(sweepHours));
  }
}
