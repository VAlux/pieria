package dev.alvo.pieria.packaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceScriptTests {

	// The script itself only targets macOS launchd; running it through Windows/Linux Git Bash
	// exercises nothing real and just adds a spurious cross-platform failure mode.
	@Test
	@EnabledOnOs(OS.MAC)
	void launchdDryRunGeneratesDaemonOnlyPlist() throws Exception {
		String output = runScript(
			"packaging/service/macos/pieria-launchd.sh",
			List.of(
				"install",
				"--dry-run",
				"--daemon", "/opt/pieria/pieria.jar",
				"--java", "/usr/bin/java",
				"--gateway", "/opt/pieria/pieria-gateway",
				"--data-dir", "/tmp/pieria-data",
				"--log-dir", "/tmp/pieria-logs"
			)
		);

		assertThat(output).contains("<key>Label</key>");
		assertThat(output).contains("<string>dev.alvo.pieria.daemon</string>");
		assertThat(output).contains("<string>/usr/bin/java</string>");
		assertThat(output).contains("<string>-jar</string>");
		assertThat(output).contains("<string>/opt/pieria/pieria.jar</string>");
		assertThat(output).contains("<string>--pieria.daemon.host=127.0.0.1</string>");
		assertThat(output).contains("<string>--pieria.daemon.port=8077</string>");
		assertThat(output).contains("<string>--pieria.db.path=/tmp/pieria-data/pieria.db</string>");
		assertThat(output).contains("Pieria gateway executable for harness MCP configs: /opt/pieria/pieria-gateway");

		String programArguments = substringBetween(output, "<key>ProgramArguments</key>", "</array>");
		assertThat(programArguments).doesNotContain("pieria-gateway");
	}

	// The script itself only targets Linux systemd --user; running it through Windows/macOS Git
	// Bash exercises nothing real and just adds a spurious cross-platform failure mode.
	@Test
	@EnabledOnOs(OS.LINUX)
	void systemdDryRunGeneratesDaemonOnlyUserUnit() throws Exception {
		String output = runScript(
			"packaging/service/linux/pieria-systemd-user.sh",
			List.of(
				"install",
				"--dry-run",
				"--daemon", "/opt/pieria/pieria.jar",
				"--java", "/usr/bin/java",
				"--gateway", "/opt/pieria/pieria-gateway",
				"--data-dir", "/tmp/pieria-data",
				"--log-dir", "/tmp/pieria-logs"
			)
		);

		assertThat(output).contains("[Unit]");
		assertThat(output).contains("[Service]");
		assertThat(output).contains("ExecStart=\"/usr/bin/java\" -jar \"/opt/pieria/pieria.jar\"");
		assertThat(output).contains("\"--pieria.daemon.host=127.0.0.1\"");
		assertThat(output).contains("\"--pieria.daemon.port=8077\"");
		assertThat(output).contains("\"--pieria.db.path=/tmp/pieria-data/pieria.db\"");
		assertThat(output).contains("Pieria gateway executable for harness MCP configs: /opt/pieria/pieria-gateway");

		String execStart = output.lines()
			.filter(line -> line.startsWith("ExecStart="))
			.findFirst()
			.orElseThrow();
		assertThat(execStart).doesNotContain("pieria-gateway");
	}

	@Test
	void windowsServiceScriptDocumentsDryRunAndDaemonOnlyInstall() throws IOException {
		Path script = repoRoot().resolve("packaging/service/windows/pieria-service.ps1");
		String content = Files.readString(script, StandardCharsets.UTF_8);

		assertThat(content).contains("[ValidateSet(\"Install\", \"Start\", \"Stop\", \"Status\", \"Uninstall\")]");
		assertThat(content).contains("New-Service -Name $ServiceName");
		assertThat(content).contains("gateway executable for harness MCP configs: $Gateway");
		assertThat(content).contains("if ($DryRun)");
		assertThat(content).doesNotContain("New-Service -Name $Gateway");
		// The $Gateway param must be declared so the dry-run guidance resolves it.
		assertThat(content).contains("[string]$Gateway = ");
		// Guard against a typed-but-unnamed parameter (e.g. "[string] = ...") that
		// parses as text but breaks the real param block on Windows.
		assertThat(content.lines())
			.as("every PowerShell parameter must have a name after its [type]")
			.noneMatch(line -> line.strip().matches("^\\[[A-Za-z]+\\]\\s*=.*"));
	}

	private static String runScript(String relativePath, List<String> arguments) throws IOException, InterruptedException {
		Path script = repoRoot().resolve(relativePath);
		ProcessBuilder processBuilder = new ProcessBuilder();
		processBuilder.command(command(script, arguments));
		processBuilder.directory(repoRoot().toFile());
		processBuilder.redirectErrorStream(true);
		Process process = processBuilder.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		int exitCode = process.waitFor();
		assertThat(exitCode).as(output).isEqualTo(0);
		return output;
	}

	private static List<String> command(Path script, List<String> arguments) {
		List<String> command = new java.util.ArrayList<>();
		command.add("bash");
		command.add(script.toString());
		command.addAll(arguments);
		return command;
	}

	private static Path repoRoot() {
		Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
		while (current != null) {
			if (Files.exists(current.resolve("settings.gradle.kts"))) {
				return current;
			}
			current = current.getParent();
		}
		throw new IllegalStateException("Could not locate repository root");
	}

	private static String substringBetween(String value, String startMarker, String endMarker) {
		int start = value.indexOf(startMarker);
		assertThat(start).isGreaterThanOrEqualTo(0);
		int end = value.indexOf(endMarker, start);
		assertThat(end).isGreaterThan(start);
		return value.substring(start, end);
	}
}
