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
				"-H:+ReportExceptionStackTraces"
			)
		}
	}
}

// --- Embedded sqlite-vec extension (SPEC 14) ---
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
}
