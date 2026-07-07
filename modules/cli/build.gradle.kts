plugins {
	java
	id("io.spring.dependency-management")
	id("org.graalvm.buildtools.native")
}

dependencyManagement {
	imports {
		// Pin Jackson (databind + toml dataformat) via the same BOM the other modules use.
		mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.6")
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
	}
}

val picocliVersion = "4.7.6"

dependencies {
	implementation(project(":shared"))
	implementation("info.picocli:picocli:$picocliVersion")
	// Generates META-INF/native-image reflect-config for the @Command tree at compile time.
	annotationProcessor("info.picocli:picocli-codegen:$picocliVersion")
	// Jackson databind + TOML come transitively from :shared (api).

	testImplementation("org.junit.jupiter:junit-jupiter")
	testImplementation("org.assertj:assertj-core")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Tell picocli-codegen which command the project roots at (used for the generated native config).
tasks.compileJava {
	options.compilerArgs.add("-Aproject=dev.alvo.pieria.cli")
}

// --- Embed harness shell assets as classpath resources ---
// The native release ships only bin/, so the hook scripts are not on disk afterwards. We embed
// the canonical scripts from the repo's harness/ tree into the CLI binary; `pieria harness install`
// extracts them to PIERIA_HOME/harness/. harness/ stays the single source of truth (no duplication
// in Java). Mirrors the daemon's embedVecExtensions pattern.
val stageHarnessAssets by tasks.registering(Sync::class) {
	description = "Stage harness shell scripts as embeddable classpath resources (harness/...)."
	into(layout.buildDirectory.dir("generated/harness-resources"))
	// Nest under harness/ so the classpath resource paths are harness/profile-name.sh, etc.
	from(rootProject.layout.projectDirectory.dir("harness")) {
		into("harness")
		include(
			"profile-name.sh", "ingest.sh", "recall.sh", "remember.sh",
			"claude-code/*.sh", "codex/*.sh", "opencode/*.sh",
			"claude-code/commands/*.md", "codex/commands/*.md", "opencode/commands/*.md"
		)
	}
}

sourceSets["main"].resources.srcDir(stageHarnessAssets)

// --- Embed the build version as a classpath resource ---
// BuildInfo.current() (and `pieria --version`) read /version.txt; the dist tasks copy the same stamp
// next to the binaries for version reporting.
val stageVersion by tasks.registering {
	description = "Write the project version to a classpath resource (version.txt)."
	val outDir = layout.buildDirectory.dir("generated/version")
	val projectVersion = project.version.toString()
	inputs.property("version", projectVersion)
	outputs.dir(outDir)
	doLast {
		outDir.get().file("version.txt").asFile.apply {
			parentFile.mkdirs()
			writeText(projectVersion)
		}
	}
}

sourceSets["main"].resources.srcDir(stageVersion)

graalvmNative {
	binaries {
		named("main") {
			imageName.set("pieria")
			mainClass.set("dev.alvo.pieria.cli.PieriaCli")
			buildArgs.addAll(
				"-H:+ReportExceptionStackTraces",
				// Auto-register api.request/response DTOs for reflection (see :shared ApiContractFeature).
				// Replaces the hand-maintained api.* entries that used to live in reflect-config.json.
				"--features=dev.alvo.pieria.api.ApiContractFeature"
			)
		}
	}
}

// Fast CLI-only redeploy: native-compile just the `pieria` binary and drop it into the installed
// bin/ without rebuilding the daemon and gateway. Uses Copy (not Sync) so it never deletes the
// sibling daemon/gateway binaries, version stamp, or harness assets already in PIERIA_HOME. For a
// full, consistent distribution (all three binaries + harness) use :daemon:deployLocal instead.
val deployLocal by tasks.registering(Copy::class) {
	group = "distribution"
	description = "Native-compile only the CLI and copy the pieria binary into ~/.local/share/pieria/bin."
	dependsOn(tasks.named("nativeCompile"))
	from(tasks.named("nativeCompile")) {
		include("pieria", "pieria.exe")
	}
	val home = providers.environmentVariable("PIERIA_HOME").orElse(
		providers.systemProperty("user.home").map { "$it/.local/share/pieria" }
	)
	into(home.map { "$it/bin" })
	// Replacing the installed binary invalidates AMFI's signature cache for that path, so macOS SIGKILLs
	// the next exec (`Killed: 9`). Re-sign the fresh `pieria` ad-hoc so it launches without a manual
	// codesign step. Mirrors :daemon reSignAdhocMacOs; ProcessBuilder keeps it configuration-cache safe.
	doLast {
		if (System.getProperty("os.name").lowercase().contains("mac")) {
			val bin = destinationDir.resolve("pieria")
			if (bin.isFile) {
				val proc = ProcessBuilder("codesign", "--force", "--sign", "-", bin.absolutePath)
					.redirectErrorStream(true)
					.start()
				val output = proc.inputStream.bufferedReader().readText()
				if (proc.waitFor() != 0) {
					throw GradleException("codesign failed for ${bin.name}: $output")
				}
				logger.lifecycle("pieria: re-signed ${bin.name} ad-hoc (macOS AMFI)")
			}
		}
	}
}
