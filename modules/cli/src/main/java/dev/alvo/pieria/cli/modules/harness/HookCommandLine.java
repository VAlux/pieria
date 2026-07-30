package dev.alvo.pieria.cli.modules.harness;

import dev.alvo.pieria.tools.StringKit;

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
    StringBuilder command = new StringBuilder(StringKit.quoteIfSpaced(executable));
    for (String arg : args) {
      command.append(' ').append(arg);
    }
    return command.toString();
  }
}
