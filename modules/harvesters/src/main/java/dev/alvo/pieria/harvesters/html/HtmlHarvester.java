package dev.alvo.pieria.harvesters.html;

import dev.alvo.pieria.harvester.Harvester;
import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.RecordSink;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvester.protocol.HarvestRequest;

public final class HtmlHarvester implements Harvester {

  @Override
  public void check(HarvestRequest request, RecordSink sink) throws HarvesterException {
    throw new HarvesterException(ErrorClass.FATAL, "not implemented");
  }

  @Override
  public void harvest(HarvestRequest request, RecordSink sink) throws HarvesterException {
    throw new HarvesterException(ErrorClass.FATAL, "not implemented");
  }
}
