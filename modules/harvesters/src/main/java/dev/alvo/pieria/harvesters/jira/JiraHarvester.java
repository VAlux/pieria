package dev.alvo.pieria.harvesters.jira;

import dev.alvo.pieria.harvester.Harvester;
import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.RecordSink;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvesters.atlassian.AtlassianClient;
import dev.alvo.pieria.harvesters.incremental.IncrementalHarvest;

import java.time.Duration;

/** Harvests Jira issues, Cloud or Data Center, as threads of the description and its comments. */
public final class JiraHarvester implements Harvester {

  private static final Duration REQUEST_DEADLINE = Duration.ofSeconds(60);

  private static JiraFeed feed(JiraConfig config) {
    return new JiraFeed(config, new AtlassianClient(config.site(), REQUEST_DEADLINE));
  }

  @Override
  public void check(HarvestRequest request, RecordSink sink) throws HarvesterException {
    JiraFeed feed = feed(JiraConfig.parse(request));
    feed.begin();
    feed.probe();
  }

  @Override
  public void harvest(HarvestRequest request, RecordSink sink) throws HarvesterException {
    JiraConfig config = JiraConfig.parse(request);
    IncrementalHarvest.run(feed(config), request, sink, config.sweepEvery(), "jira");
  }
}
