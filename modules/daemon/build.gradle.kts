plugins {
	java
	id("org.springframework.boot")
	id("io.spring.dependency-management")
	id("org.graalvm.buildtools.native")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
	}
}

dependencies {
	implementation(project(":shared"))
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.ai:spring-ai-starter-model-openai")
	// Tree-sitter (FFM/Panama binding). Version tracks the tree-sitter 0.25 core bundled in
	// packaging/native/<os>-<arch>/libtree-sitter.*; keep them on the same minor line.
	implementation("io.github.tree-sitter:jtreesitter:0.25.6")
	implementation("org.jsoup:jsoup:1.16.1")
	// Apache Tika for PDF text extraction (pulls PDFBox transitively). We depend on the scoped
	// tika-parser-pdf-module rather than tika-parsers-standard-package: the standard package drags in
	// the mail module, whose transitive org.eclipse.angus:angus-activation ships a native-image Feature
	// (AngusActivationFeature) that aborts the GraalVM build with NoClassDefFoundError:
	// jakarta/mail/MessagingException (no jakarta.mail on the classpath). The pdf module is all the PDF
	// path needs; AutoDetectParser still discovers PDFParser via ServiceLoader and tika-core supplies
	// PDF magic-byte detection.
	implementation("org.apache.tika:tika-core:3.3.1")
	implementation("org.apache.tika:tika-parser-pdf-module:3.3.1") {
		// jaxb-runtime (used for PDF XMP metadata) drags in org.eclipse.angus:angus-activation, whose
		// native-image Feature (AngusActivationFeature) aborts the GraalVM build:
		// NoClassDefFoundError: jakarta/mail/MessagingException. Its default mailcap registers mail
		// DataContentHandlers reflectively, which need jakarta.mail — absent here, and not needed:
		// XMP is unmarshalled straight to beans and never touches the activation DataHandler path.
		// Drop the activation impl; jakarta.activation-api (below) stays for JAXB's link-time refs.
		exclude(group = "org.eclipse.angus", module = "angus-activation")
	}
	// JAXB links against the activation API; keep it even though the angus impl is excluded above.
	implementation("jakarta.activation:jakarta.activation-api:2.1.4")
	runtimeOnly("org.xerial:sqlite-jdbc")
	testImplementation(project(":gateway"))
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.springframework.ai:spring-ai-spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.bootJar {
	enabled = false
}

// jtreesitter makes FFM/Panama downcalls into the bundled Tree-sitter libraries; grant native access
// so the parser tests (and a JVM-mode daemon) run without the restricted-method warning/denial.
tasks.test {
	jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Publish a plain jar so :eval can depend on daemon's classes directly.
tasks.jar {
	enabled = true
}

graalvmNative {
	binaries {
		named("main") {
			imageName.set("pieria-daemon")
			mainClass.set("dev.alvo.pieria.PieriaApplication")
			buildArgs.addAll(
				"--enable-url-protocols=http,https",
				"-H:+ReportExceptionStackTraces",
				// Auto-register api.request/response DTOs for reflection (see :shared ApiContractFeature).
				"--features=dev.alvo.pieria.api.ApiContractFeature",
				// jtreesitter (Tree-sitter code parser) makes FFM downcalls into the bundled native libs.
				// FFM is on by default in GraalVM 25; this grants the unnamed module native access. The
				// downcall descriptors are registered via META-INF/native-image/.../reachability-metadata.json
				// (generated with the native-image tracing agent).
				"--enable-native-access=ALL-UNNAMED",
				// jtreesitter's Parser and the parse path use Arena.ofShared (so a parse tree is usable
				// across threads); native-image gates shared arenas behind this flag.
				"-H:+UnlockExperimentalVMOptions",
				"-H:+SharedArenaSupport"
			)
		}
	}
}

// --- Embedded sqlite-vec extension ---
// The xerial driver loads its own SQLite engine at runtime, so sqlite-vec cannot be statically
// linked into the GraalVM image. Instead we embed the platform `vec0` loadable extension(s) as
// classpath resources under native/; at startup VecExtensionResolver extracts the host-platform one
// to the runtime dir and DataSourceConfig loads it by absolute path. This yields a single-file
// native binary / self-contained jar with no sidecar. Binaries come from packaging/native/<platform>
// (git-ignored, fetched per platform — see packaging/native/README.md). When none are present the
// build still succeeds and the daemon degrades to FTS + keyed lookup; a release build must supply them.
val embedVecExtensions by tasks.registering(Sync::class) {
	description = "Stage per-arch sqlite-vec extensions as embeddable classpath resources (native/<os>-<arch>/vec0.*)."
	from(rootProject.layout.projectDirectory.dir("packaging/native")) {
		// Preserve the <os>-<arch>/ directory so arch-distinct binaries (e.g. macos-aarch64 vs
		// macos-x86_64) stay separate; VecExtensionResolver looks them up by the same key.
		include("*-*/vec0.dylib", "*-*/vec0.so", "*-*/vec0.dll")
		includeEmptyDirs = false
		eachFile { relativePath = RelativePath(true, "native", *relativePath.segments) }
	}
	into(layout.buildDirectory.dir("generated/vec-resources"))
	doLast {
		val nativeDir = layout.buildDirectory.dir("generated/vec-resources/native").get().asFile
		val staged = nativeDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
		if (staged.isEmpty()) {
			logger.warn("pieria: no sqlite-vec extensions found under packaging/native/<os>-<arch>/; "
				+ "vector search will be disabled at runtime. See packaging/native/README.md.")
		} else {
			logger.lifecycle("pieria: embedding sqlite-vec extensions for $staged")
		}
	}
}

// Adding the task as a resource srcDir wires processResources (and nativeCompile) to depend on it.
sourceSets["main"].resources.srcDir(embedVecExtensions)

// --- Embedded Tree-sitter native libraries (Phase 13) ---
// jtreesitter is an FFM binding; the core libtree-sitter + per-language grammar shared libs are
// dlopen'd at runtime (cannot be static-linked, like sqlite-vec). Embed them as classpath resources
// under native/<os>-<arch>/; at startup TreeSitterLibraryResolver extracts the host-platform ones to
// the runtime dir and TreeSitterEngine loads them by absolute path. Missing libs degrade gracefully
// (that language — or all symbol parsing — is skipped). Binaries come from packaging/native/<platform>.
val embedTreeSitterLibraries by tasks.registering(Sync::class) {
	description = "Stage per-arch Tree-sitter libraries as embeddable classpath resources (native/<os>-<arch>/{libtree-sitter,tree-sitter-*}.*)."
	from(rootProject.layout.projectDirectory.dir("packaging/native")) {
		include(
			"*-*/libtree-sitter.dylib", "*-*/libtree-sitter.so", "*-*/libtree-sitter.dll",
			"*-*/tree-sitter-*.dylib", "*-*/tree-sitter-*.so", "*-*/tree-sitter-*.dll"
		)
		includeEmptyDirs = false
		eachFile { relativePath = RelativePath(true, "native", *relativePath.segments) }
	}
	into(layout.buildDirectory.dir("generated/treesitter-resources"))
	doLast {
		val nativeDir = layout.buildDirectory.dir("generated/treesitter-resources/native").get().asFile
		val staged = nativeDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
		if (staged.isEmpty()) {
			logger.warn("pieria: no Tree-sitter libraries found under packaging/native/<os>-<arch>/; "
				+ "code symbol parsing will be disabled at runtime. See packaging/native/README.md.")
		} else {
			logger.lifecycle("pieria: embedding Tree-sitter libraries for $staged")
		}
	}
}

sourceSets["main"].resources.srcDir(embedTreeSitterLibraries)

// Version stamp shipped next to the native binaries (bin/version.txt) for version reporting.
val generateVersionStamp by tasks.registering {
	description = "Write the project version to bin/version.txt for the distributions."
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

// GraalVM emits "linker-signed" ad-hoc Mach-O binaries (flags=0x20002); macOS AMFI SIGKILLs such a
// binary on its first exec after the file is replaced (the kernel signature cache is stale), which is
// the `Killed: 9` seen right after a deploy. Re-signing with a plain ad-hoc signature (flags=0x2)
// refreshes it so the binary launches. No-op off macOS (no codesign / not affected). ProcessBuilder
// keeps this off Gradle's exec API, so it is configuration-cache safe.
fun reSignAdhocMacOs(binDir: java.io.File, log: org.gradle.api.logging.Logger) {
	if (!System.getProperty("os.name").lowercase().contains("mac")) {
		return
	}
	listOf("pieria", "pieria-daemon", "pieria-gateway")
		.map { binDir.resolve(it) }
		.filter { it.isFile }
		.forEach { bin ->
			val proc = ProcessBuilder("codesign", "--force", "--sign", "-", bin.absolutePath)
				.redirectErrorStream(true)
				.start()
			val output = proc.inputStream.bufferedReader().readText()
			if (proc.waitFor() != 0) {
				throw GradleException("codesign failed for ${bin.name}: $output")
			}
			log.lifecycle("pieria: re-signed ${bin.name} ad-hoc (macOS AMFI)")
		}
}

val nativeDist by tasks.registering(Sync::class) {
	group = "distribution"
	description = "Assemble a native-image distribution: self-contained daemon + gateway + cli binaries and harness assets."
	dependsOn(
		tasks.named("nativeCompile"),
		project(":gateway").tasks.named("nativeCompile"),
		project(":cli").tasks.named("nativeCompile")
	)

	into(layout.buildDirectory.dir("distributions/pieria-native"))
	// The sqlite-vec extension is embedded in the binary; nothing extra rides alongside it.
	from(tasks.named("nativeCompile")) {
		into("bin")
		include("pieria-daemon", "pieria-daemon.exe")
	}
	from(project(":gateway").tasks.named("nativeCompile")) {
		into("bin")
		include("pieria-gateway", "pieria-gateway.exe")
	}
	from(project(":cli").tasks.named("nativeCompile")) {
		into("bin")
		include("pieria", "pieria.exe")
	}
	from(rootProject.layout.projectDirectory.dir("packaging/harness")) {
		into("harness")
	}
	from(rootProject.layout.projectDirectory.dir("harness")) {
		into("harness/examples")
	}
	from(generateVersionStamp) {
		into("bin")
	}
	// Re-sign the freshly assembled binaries so the dist (and release archives) ship plain ad-hoc.
	doLast {
		reSignAdhocMacOs(layout.buildDirectory.dir("distributions/pieria-native/bin").get().asFile, logger)
	}
}

// Build the native dist and copy it into the local install directory in one step.
val deployLocal by tasks.registering(Sync::class) {
	group = "distribution"
	description = "Build the native distribution and sync it into ~/.local/share/pieria."
	dependsOn(nativeDist)
	from(layout.buildDirectory.dir("distributions/pieria-native"))
	into(providers.environmentVariable("PIERIA_HOME").orElse(
		providers.systemProperty("user.home").map { "$it/.local/share/pieria" }
	))
	// `pieria harness install` extracts hook scripts into harness/<id>/ at runtime; the dist only
	// ships them under harness/examples/. Without this, Sync would delete the wired scripts on every
	// redeploy, leaving settings.json hooks pointing at missing files.
	preserve {
		include("harness/claude-code/**")
		include("harness/codex/**")
		include("harness/opencode/**")
	}
	// Re-sign in place after install: replacing the installed binary invalidates AMFI's cache for that
	// path, so sign the final files so `pieria daemon restart` works immediately without a manual step.
	doLast {
		reSignAdhocMacOs(destinationDir.resolve("bin"), logger)
	}
}
