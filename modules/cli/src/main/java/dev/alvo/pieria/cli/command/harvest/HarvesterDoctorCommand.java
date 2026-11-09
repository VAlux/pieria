package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.api.response.HarvesterDoctorResponse;
import dev.alvo.pieria.client.HarvestClient;
import picocli.CommandLine.Command;

import java.util.List;

@Command(name = "doctor", description = "Check every installed harvester and report problems.", mixinStandardHelpOptions = true)
public final class HarvesterDoctorCommand extends AbstractHarvestCommand {

  @Override
  protected int run(HarvestClient client) {
    List<HarvesterDoctorResponse.Entry> entries = client.doctor().entries();
    if (entries.isEmpty()) {
      log.info("No harvesters installed.");
      return 0;
    }
    boolean healthy = true;
    for (HarvesterDoctorResponse.Entry entry : entries) {
      healthy &= entry.ok();
      log.info("{}  {}  {}", entry.ok() ? "ok     " : "problem", entry.directory(), entry.detail());
    }
    return healthy ? 0 : 1;
  }
}
