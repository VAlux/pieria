package dev.alvo.pieria.cli.command.task;

import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.cli.modules.daemon.DaemonUrls;
import dev.alvo.pieria.cli.modules.task.HttpTaskClient;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Shared plumbing for {@code pieria task} sub-commands: resolve the daemon URL, build an
 * {@link HttpTaskClient}, and translate its typed failures into the CLI's conventional exit codes
 * (3 = daemon down, 4 = not found, 1 = other API error).
 */
abstract class AbstractTaskCommand implements Callable<Integer> {

  protected final Logger log = new Logger();

  @Option(names = "--daemon-url", description = "Daemon base URL (default: $PIERIA_DAEMON_URL or http://127.0.0.1:8077).")
  String daemonUrl;

  protected abstract int run(HttpTaskClient client) throws Exception;

  @Override
  public final Integer call() {
    String url = DaemonUrls.resolve(daemonUrl);
    HttpTaskClient client = new HttpTaskClient(url);
    try {
      return run(client);
    } catch (HttpTaskClient.DaemonDownException e) {
      log.error("Pieria daemon is not reachable at {}.", url);
      log.error("Start it with 'pieria start'.");
      return 3;
    } catch (HttpTaskClient.NotFoundException e) {
      log.error("No such task. It may have finished and been evicted, or the daemon was restarted.");
      return 4;
    } catch (HttpTaskClient.ApiException e) {
      log.error(e.getMessage());
      return 1;
    } catch (Exception e) {
      log.error("Error: {}", e.getMessage());
      return 1;
    }
  }
}
