package dev.alvo.pieria.cli.harness;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Known harness installers, keyed by their command-line id. */
public final class HarnessRegistry {

  private final Map<String, HarnessInstaller> installers = new LinkedHashMap<>();

  public HarnessRegistry() {
    register(new ClaudeCodeInstaller());
    register(new CodexInstaller());
  }

  private void register(HarnessInstaller installer) {
    installers.put(installer.id(), installer);
  }

  public Optional<HarnessInstaller> find(String id) {
    return Optional.ofNullable(installers.get(id));
  }

  public java.util.Collection<HarnessInstaller> all() {
    return installers.values();
  }

  public java.util.Set<String> ids() {
    return installers.keySet();
  }
}
