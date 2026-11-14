package dev.alvo.pieria.harvesters;

import dev.alvo.pieria.harvester.Harvester;
import dev.alvo.pieria.harvester.HarvesterRunner;
import dev.alvo.pieria.harvesters.html.HtmlHarvester;
import dev.alvo.pieria.harvesters.jira.JiraHarvester;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Supplier;

/** The first-party harvesters in one binary: {@code pieria-harvest <id> check|harvest}. */
public final class PieriaHarvest {

  private static final Map<String, Supplier<Harvester>> HARVESTERS = Map.of(
    "html", HtmlHarvester::new,
    "jira", JiraHarvester::new);

  private PieriaHarvest() {
  }

  public static void main(String[] args) {
    if (!validUsage(args)) {
      System.err.println(usage());
      System.exit(HarvesterRunner.EXIT_USAGE);
    }
    HarvesterRunner.main(HARVESTERS.get(args[0]).get(), new String[]{args[1]});
  }

  /** Runs one command against the given streams and returns the process exit code. */
  public static int run(String[] args, InputStream in, OutputStream out, PrintStream err) {
    if (!validUsage(args)) {
      err.println(usage());
      return HarvesterRunner.EXIT_USAGE;
    }
    return HarvesterRunner.run(HARVESTERS.get(args[0]).get(), new String[]{args[1]}, in, out, err);
  }

  private static boolean validUsage(String[] args) {
    return args.length == 2 && HARVESTERS.containsKey(args[0]);
  }

  private static String usage() {
    return "usage: pieria-harvest <" + String.join("|", new TreeSet<>(HARVESTERS.keySet())) + "> check|harvest";
  }
}
