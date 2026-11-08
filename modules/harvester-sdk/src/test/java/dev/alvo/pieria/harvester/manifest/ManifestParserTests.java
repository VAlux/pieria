package dev.alvo.pieria.harvester.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestParserTests {

  private static final String VALID = """
    id = "jira"
    name = "Jira"
    version = "1.0.0"
    protocol = 1
    command = ["./bin/jira", "--quiet"]
    capabilities = ["incremental", "deletes"]
    network = ["*.atlassian.net"]

    [[config]]
    key = "baseUrl"
    kind = "string"
    label = "Base URL"
    hint = "Your site"
    required = true

    [[config]]
    key = "token"
    kind = "secret"
    label = "API token"

    [[config]]
    key = "mode"
    kind = "enum"
    label = "Mode"
    options = ["full", "raw"]
    default = "full"
    """;

  private static final String MINIMAL = """
    id = "x"
    name = "X"
    version = "1"
    protocol = 1
    command = ["python3", "h.py"]
    """;

  @Test
  void parsesACompleteManifest() {
    HarvesterManifest manifest = ManifestParser.parse(VALID);

    assertThat(manifest.id()).isEqualTo("jira");
    assertThat(manifest.name()).isEqualTo("Jira");
    assertThat(manifest.version()).isEqualTo("1.0.0");
    assertThat(manifest.protocol()).isEqualTo(1);
    assertThat(manifest.command()).containsExactly("./bin/jira", "--quiet");
    assertThat(manifest.has(Capability.INCREMENTAL)).isTrue();
    assertThat(manifest.has(Capability.DELETES)).isTrue();
    assertThat(manifest.has(Capability.OAUTH_DEVICE_CODE)).isFalse();
    assertThat(manifest.network()).containsExactly("*.atlassian.net");
    assertThat(manifest.config()).extracting(ConfigFieldSpec::key).containsExactly("baseUrl", "token", "mode");

    ConfigFieldSpec baseUrl = manifest.config().get(0);
    assertThat(baseUrl.kind()).isEqualTo(FieldKind.STRING);
    assertThat(baseUrl.label()).isEqualTo("Base URL");
    assertThat(baseUrl.hint()).isEqualTo("Your site");
    assertThat(baseUrl.required()).isTrue();

    assertThat(manifest.config().get(1).kind()).isEqualTo(FieldKind.SECRET);
    assertThat(manifest.config().get(1).required()).isFalse();

    ConfigFieldSpec mode = manifest.config().get(2);
    assertThat(mode.options()).containsExactly("full", "raw");
    assertThat(mode.defaultValue().asString()).isEqualTo("full");
  }

  @Test
  void parsesTheOauthCapability() {
    assertThat(ManifestParser.parse(MINIMAL + "capabilities = [\"oauth-device-code\"]\n").has(Capability.OAUTH_DEVICE_CODE)).isTrue();
  }

  @Test
  void optionalSectionsDefaultToEmpty() {
    HarvesterManifest manifest = ManifestParser.parse(MINIMAL);

    assertThat(manifest.capabilities()).isEmpty();
    assertThat(manifest.network()).isEmpty();
    assertThat(manifest.config()).isEmpty();
  }

  @Test
  void resolvesARelativeProgramAgainstTheManifestDir() {
    assertThat(ManifestParser.parse(VALID).resolvedCommand(Path.of("/opt/h")))
      .containsExactly(Path.of("/opt/h/bin/jira").toString(), "--quiet");
  }

  @Test
  void leavesABareProgramForPathLookup() {
    assertThat(ManifestParser.parse(MINIMAL).resolvedCommand(Path.of("/opt/h"))).containsExactly("python3", "h.py");
  }

  @Test
  void unknownKeysAreIgnored() {
    assertThat(ManifestParser.parse(MINIMAL + "future = 1\n").id()).isEqualTo("x");
  }

  @Test
  void loadsFromAFile(@TempDir Path dir) throws Exception {
    Path file = Files.writeString(dir.resolve("harvester.toml"), MINIMAL);

    assertThat(ManifestParser.load(file).id()).isEqualTo("x");
  }

  @Test
  void missingFileIsAManifestError(@TempDir Path dir) {
    assertThatThrownBy(() -> ManifestParser.load(dir.resolve("nope.toml"))).isInstanceOf(ManifestException.class);
  }

  private static String without(String key) {
    return MINIMAL.lines().filter(line -> !line.startsWith(key + " ")).reduce("", (a, b) -> a + b + "\n");
  }

  private static String replacing(String key, String value) {
    return without(key) + key + " = " + value + "\n";
  }

  private static String withField(String body) {
    return MINIMAL + "[[config]]\n" + body + "\n";
  }

  static Stream<String> invalidManifests() {
    return Stream.of(
      "this is = = not toml",
      without("id"),
      without("name"),
      without("version"),
      without("protocol"),
      without("command"),
      replacing("id", "\"Bad Id\""),
      replacing("id", "7"),
      replacing("protocol", "2"),
      replacing("command", "\"./run --all\""),
      replacing("command", "[]"),
      replacing("command", "[\"\"]"),
      replacing("command", "[\"/usr/bin/x\"]"),
      replacing("command", "[\"../x\"]"),
      replacing("command", "[\"./a/../../x\"]"),
      replacing("command", "[\"sub/x\"]"),
      replacing("command", "[\".\\\\x\"]"),
      MINIMAL + "capabilities = [\"teleport\"]\n",
      withField("key = \"a\"\nkind = \"blob\"\nlabel = \"A\""),
      withField("key = \"a\"\nkind = \"string\"\nlabel = \"A\"\nrequired = \"yes\""),
      withField("kind = \"string\"\nlabel = \"A\""),
      withField("key = \"a\"\nkind = \"string\""),
      withField("key = \"a\"\nkind = \"enum\"\nlabel = \"A\""),
      withField("key = \"a\"\nkind = \"secret\"\nlabel = \"A\"\ndefault = \"x\""),
      withField("key = \"a\"\nkind = \"string\"\nlabel = \"A\"") + "[[config]]\nkey = \"a\"\nkind = \"int\"\nlabel = \"B\"\n");
  }

  @ParameterizedTest
  @MethodSource("invalidManifests")
  void rejectsInvalidManifests(String toml) {
    assertThatThrownBy(() -> ManifestParser.parse(toml)).isInstanceOf(ManifestException.class);
  }
}
