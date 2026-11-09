package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.api.request.InstallHarvesterRequest;
import dev.alvo.pieria.api.response.HarvesterPackageResponse;
import dev.alvo.pieria.client.HarvestClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

@Command(name = "install", description = "Install a harvester from a directory or .zip file.", mixinStandardHelpOptions = true)
public final class HarvesterInstallCommand extends AbstractHarvestCommand {

  @Parameters(index = "0", paramLabel = "<dir|zip>", description = "Directory or .zip file holding the harvester.")
  String path;

  @Option(names = "--replace", description = "Replace an installed harvester with the same id.")
  boolean replace;

  @Option(names = "--yes", description = "Install without asking for confirmation.")
  boolean yes;

  @Override
  protected int run(HarvestClient client) {
    String absolute = Path.of(path).toAbsolutePath().normalize().toString();
    HarvesterPackage shown = show(client.inspect(absolute));
    if (!yes && !confirm("Install this harvester?")) {
      log.error("Not installed. Run again at a terminal to confirm, or pass --yes.");
      return 1;
    }
    client.install(new InstallHarvesterRequest(absolute, shown.entrypointSha256(), shown.manifestSha256(), replace));
    log.info("Installed harvester {}.", shown.id());
    return 0;
  }

  private record HarvesterPackage(String id, String entrypointSha256, String manifestSha256) {
  }

  private HarvesterPackage show(HarvesterPackageResponse inspected) {
    log.info("{} {} ({})", inspected.harvester().name(), inspected.harvester().version(), inspected.harvester().id());
    log.info("  command:  {}", String.join(" ", inspected.command()));
    log.info("  network:  {}", inspected.harvester().network().isEmpty() ? "(none declared)" : String.join(", ", inspected.harvester().network()));
    log.info("  entrypoint {}: {}", inspected.entrypoint(), inspected.entrypointSha256());
    log.info("  manifest: {}", inspected.manifestSha256());
    log.info("Harvesters run with your user's permissions and are not sandboxed. Install only ones you trust.");
    return new HarvesterPackage(inspected.harvester().id(), inspected.entrypointSha256(), inspected.manifestSha256());
  }
}
