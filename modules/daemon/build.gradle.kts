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
	implementation("org.springframework.ai:spring-ai-starter-model-ollama")
	runtimeOnly("org.xerial:sqlite-jdbc")
	// sqlite-vec (SPEC 4/5.2): there is no reliable, cross-platform Maven coordinate that ships the
	// `vec0` loadable extension for the xerial driver. The extension is therefore loaded at runtime
	// via `SELECT load_extension('vec0')` (DataSourceConfig), best-effort: the daemon must locate
	// the native sqlite-vec library on the OS extension search path (or have it installed alongside
	// the binary in Phase 5 packaging). When absent, vector search is disabled and FTS + keyed
	// lookup still work. See SqliteVectorIndex for the capability-gated table creation.
	testImplementation(project(":shim"))
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
	archiveFileName.set("pieria.jar")
}

// Publish a plain jar alongside bootJar so :eval can depend on daemon's classes directly.
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

val jvmDist by tasks.registering(Sync::class) {
	group = "distribution"
	description = "Assemble a local JVM distribution with daemon, shim, command wrappers, and harness assets."
	dependsOn(tasks.named("bootJar"), project(":shim").tasks.named("bootJar"))

	into(layout.buildDirectory.dir("distributions/pieria-jvm"))
	from(tasks.named("bootJar")) {
		into("lib")
	}
	from(project(":shim").tasks.named("bootJar")) {
		into("lib")
	}
	from(rootProject.layout.projectDirectory.dir("packaging/bin")) {
		into("bin")
		filePermissions {
			unix("rwxr-xr-x")
		}
	}
	from(rootProject.layout.projectDirectory.dir("packaging/harness")) {
		into("harness")
	}
	from(rootProject.layout.projectDirectory.dir("harness")) {
		into("harness/examples")
	}
}

val nativeDist by tasks.registering(Sync::class) {
	group = "distribution"
	description = "Assemble a native-image distribution: self-contained daemon + shim binaries and harness assets."
	dependsOn(tasks.named("nativeCompile"), project(":shim").tasks.named("nativeCompile"))

	into(layout.buildDirectory.dir("distributions/pieria-native"))
	// The sqlite-vec extension is embedded in the binary; nothing extra rides alongside it.
	from(tasks.named("nativeCompile")) {
		into("bin")
		include("pieria-daemon", "pieria-daemon.exe")
	}
	from(project(":shim").tasks.named("nativeCompile")) {
		into("bin")
		include("pieria-shim", "pieria-shim.exe")
	}
	from(rootProject.layout.projectDirectory.dir("packaging/harness")) {
		into("harness")
	}
	from(rootProject.layout.projectDirectory.dir("harness")) {
		into("harness/examples")
	}
}
