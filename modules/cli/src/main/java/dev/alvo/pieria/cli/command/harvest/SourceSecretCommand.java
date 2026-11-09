package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.client.HarvestClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.Console;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Command(name = "secret", description = "Replace a source's credentials.", mixinStandardHelpOptions = true)
public final class SourceSecretCommand extends AbstractSourceCommand {

  @Parameters(index = "0", paramLabel = "<id>", description = "Source id.")
  String id;

  @Option(names = "--secret-env", paramLabel = "name=ENV_VAR", description = "Read a secret from an environment variable. Repeatable.")
  List<String> secretEnv = new ArrayList<>();

  @Option(names = "--name", description = "Prompt for this secret at the terminal. Repeatable.")
  List<String> names = new ArrayList<>();

  @Override
  protected int run(HarvestClient client) {
    Map<String, String> secrets = new LinkedHashMap<>(SourceOptions.secretsFromEnvironment(secretEnv));
    Console console = interactiveConsole();
    for (String name : names) {
      if (console == null) {
        throw new IllegalArgumentException("no terminal to prompt on: use --secret-env " + name + "=ENV_VAR");
      }
      secrets.put(name, new String(console.readPassword("%s: ", name)));
    }
    if (secrets.isEmpty()) {
      throw new IllegalArgumentException("nothing to set: use --secret-env name=ENV_VAR or --name name");
    }
    client.setSecrets(profile(), id, secrets);
    log.info("Updated credentials for source {}. If it was waiting on them, run 'pieria source resume {}'.", id, id);
    return 0;
  }
}
