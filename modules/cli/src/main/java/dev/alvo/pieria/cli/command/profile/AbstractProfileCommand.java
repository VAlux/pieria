package dev.alvo.pieria.cli.command.profile;

import dev.alvo.pieria.cli.modules.daemon.DaemonUrls;
import dev.alvo.pieria.cli.modules.daemon.ProfileApiClient;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Shared plumbing for the {@code pieria profile} sub-commands that talk to the daemon: resolves the
 * daemon URL, builds a {@link ProfileApiClient}, and maps the client's typed failures to consistent
 * exit codes ({@code 0} ok, {@code 1} error, {@code 3} daemon unreachable, {@code 4} not found).
 */
abstract class AbstractProfileCommand implements Callable<Integer> {

  @Option(names = "--daemon-url", description = "Daemon base URL (default: $PIERIA_DAEMON_URL or http://127.0.0.1:8077).")
  String daemonUrl;

  /**
   * Do the command's work against the daemon; may throw the client's typed exceptions.
   */
  protected abstract int run(ProfileApiClient client) throws Exception;

  @Override
  public final Integer call() {
    String url = DaemonUrls.resolve(daemonUrl);
    ProfileApiClient client = new ProfileApiClient(url);
    try {
      return run(client);
    } catch (ProfileApiClient.DaemonDownException e) {
      System.err.printf("Pieria daemon is not reachable at %s.%n", url);
      System.err.println("Start it with 'pieria daemon start'.");
      return 3;
    } catch (ProfileApiClient.NotFoundException e) {
      String detail = e.getMessage();
      System.err.println(detail == null || detail.isBlank() ? "Not found." : detail);
      return 4;
    } catch (ProfileApiClient.ApiException e) {
      System.err.println(e.getMessage());
      return 1;
    } catch (Exception e) {
      System.err.println("Error: " + e.getMessage());
      return 1;
    }
  }
}
