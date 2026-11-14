package dev.alvo.pieria.harvesters.confluence;

import dev.alvo.pieria.harvester.Harvester;
import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.RecordSink;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;
import dev.alvo.pieria.harvesters.atlassian.AtlassianClient;
import dev.alvo.pieria.harvesters.incremental.IncrementalHarvest;

import java.time.Duration;

/** Harvests Confluence pages and blog posts, Cloud or Data Center, as Markdown documents. */
public final class ConfluenceHarvester implements Harvester {

  private static final Duration REQUEST_DEADLINE = Duration.ofSeconds(60);

  private static ConfluenceFeed feed(ConfluenceConfig config) {
    return new ConfluenceFeed(config, new AtlassianClient(config.site(), REQUEST_DEADLINE));
  }

  @Override
  public void check(HarvestRequest request, RecordSink sink) throws HarvesterException {
    ConfluenceFeed feed = feed(ConfluenceConfig.parse(request));
    feed.begin();
    feed.probe();
  }

  @Override
  public void harvest(HarvestRequest request, RecordSink sink) throws HarvesterException {
    ConfluenceConfig config = ConfluenceConfig.parse(request);
    IncrementalHarvest.run(feed(config), request, sink, config.sweepEvery(), "confluence");
  }
}
