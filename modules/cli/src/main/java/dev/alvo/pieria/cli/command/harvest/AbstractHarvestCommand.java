package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.cli.modules.daemon.DaemonUrls;
import dev.alvo.pieria.cli.modules.update.BuildInfo;
import dev.alvo.pieria.client.HarvestClient;
import dev.alvo.pieria.client.exception.DaemonClientException;
import dev.alvo.pieria.client.exception.DaemonConflictException;
import dev.alvo.pieria.client.exception.DaemonNotFoundException;
import dev.alvo.pieria.client.exception.DaemonUnavailableException;
import picocli.CommandLine.Option;

import java.io.Console;
import java.util.concurrent.Callable;

/**
 * Shared plumbing for the harvester and source commands, with the same exit codes as the profile
 * commands: {@code 0} ok, {@code 1} error, {@code 3} daemon unreachable, {@code 4} not found.
 */
abstract class AbstractHarvestCommand implements Callable<Integer> {

  protected final Logger log = new Logger();

  @Option(names = "--daemon-url", description = "Daemon base URL (default: $PIERIA_DAEMON_URL or http://127.0.0.1:8077).")
  String daemonUrl;

  protected abstract int run(HarvestClient client) throws Exception;

  protected final String resolvedDaemonUrl() {
    return DaemonUrls.resolve(daemonUrl);
  }

  /** The console, or null when there is no one at a terminal to ask. */
  protected static Console interactiveConsole() {
    Console console = System.console();
    return console != null && console.isTerminal() ? console : null;
  }

  protected final boolean confirm(String question) {
    Console console = interactiveConsole();
    if (console == null) {
      return false;
    }
    String answer = console.readLine("%s [y/N] ", question);
    return answer != null && answer.strip().equalsIgnoreCase("y");
  }

  @Override
  public final Integer call() {
    String url = resolvedDaemonUrl();
    try {
      return run(new HarvestClient(url, BuildInfo.clientIdentity()));
    } catch (DaemonUnavailableException e) {
      log.error("Pieria daemon is not reachable at {}.", url);
      log.error("Start it with 'pieria start'.");
      return 3;
    } catch (DaemonNotFoundException e) {
      String detail = e.daemonMessage();
      log.error(detail == null || detail.isBlank() ? "Not found." : detail);
      return 4;
    } catch (DaemonConflictException e) {
      String detail = e.daemonMessage();
      log.error(detail == null || detail.isBlank() ? "Conflict." : detail);
      return 1;
    } catch (DaemonClientException e) {
      log.error(e.getMessage());
      return 1;
    } catch (Exception e) {
      log.error("Error: {}", e.getMessage());
      return 1;
    }
  }
}
