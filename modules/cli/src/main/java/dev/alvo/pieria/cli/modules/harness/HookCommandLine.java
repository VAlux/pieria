package dev.alvo.pieria.cli.modules.harness;

/**
 * Builds the single command string a harness config stores for a hook.
 *
 * <p>Quoting is not cosmetic: a Windows install path is routinely
 * {@code C:\Users\First Last\AppData\...}, and an unquoted executable would be split at the space.
 * Arguments are literal subcommand names with no spaces, so only the executable needs quoting.
 */
public final class HookCommandLine {

  private HookCommandLine() {
  }

  public static String of(String executable, String... args) {
    StringBuilder command = new StringBuilder(quote(executable));
    for (String arg : args) {
      command.append(' ').append(arg);
    }
    return command.toString();
  }

  private static String quote(String executable) {
    boolean quoted = executable.startsWith("\"") && executable.endsWith("\"");
    return quoted || !executable.contains(" ") ? executable : "\"" + executable + "\"";
  }
}
