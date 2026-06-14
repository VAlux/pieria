package dev.alvo.pieria.api;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/**
 * GraalVM native-image {@link Feature} that registers every HTTP contract DTO in
 * {@code dev.alvo.pieria.api.request} / {@code .response} for reflective JSON (de)serialization,
 * so new DTOs need no hand-maintained native reflection config in any module.
 *
 * <p>Wired into each native binary (daemon, gateway, cli) via
 * {@code --features=dev.alvo.pieria.api.ApiContractFeature}; {@link ApiContracts} does the discovery.
 * This single mechanism replaces the per-module Spring {@code RuntimeHintsRegistrar} entries (which
 * never ran for the non-Spring CLI) and the CLI's hand-written {@code reflect-config.json} entries.
 *
 * <p>The GraalVM SDK is a compile-only dependency provided by the image builder, so this class is
 * never loaded by the normal (non-native) JVM runtime. Spring component scanning reads it via ASM
 * metadata only — it is not a bean — so its missing supertype never triggers a load at runtime.
 */
public final class ApiContractFeature implements Feature {

  @Override
  public void beforeAnalysis(BeforeAnalysisAccess access) {
    for (Class<?> type : ApiContracts.all(access.getApplicationClassLoader())) {
      RuntimeReflection.register(type);
      // register(Executable...) / register(Field...) make the elements reflectively *invokable*,
      // which Jackson needs to call record accessors and constructors. The registerAllDeclared*
      // bulk variants only register them as *queried* (visible to getDeclaredMethods() but not
      // invokable), which is what caused MissingReflectionRegistrationError at serialization time.
      RuntimeReflection.register(type.getDeclaredConstructors());
      RuntimeReflection.register(type.getDeclaredMethods());
      RuntimeReflection.register(type.getDeclaredFields());
    }
  }

  @Override
  public String getDescription() {
    return "Registers dev.alvo.pieria.api request/response DTOs for reflective JSON serialization.";
  }
}
