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
		include("profile-name.sh", "ingest.sh", "claude-code/*.sh", "codex/*.sh")
	}
}

sourceSets["main"].resources.srcDir(stageHarnessAssets)

// --- Embed the build version as a classpath resource ---
// BuildInfo.current() (and `pieria --version`) read /version.txt; the dist tasks copy the same stamp
// next to the binaries so `pieria update` can report old -> new.
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

val runnableJar by tasks.registering(Jar::class) {
	group = "distribution"
	description = "Plain runnable jar fallback for the pieria CLI (native binary is the primary artifact)."
	archiveFileName.set("pieria-cli.jar")
	manifest { attributes["Main-Class"] = "dev.alvo.pieria.cli.PieriaCli" }
	from(sourceSets["main"].output)
	from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

graalvmNative {
	binaries {
		named("main") {
			imageName.set("pieria")
			mainClass.set("dev.alvo.pieria.cli.PieriaCli")
			buildArgs.addAll(
				"-H:+ReportExceptionStackTraces"
			)
		}
	}
}
