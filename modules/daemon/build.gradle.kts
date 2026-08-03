import java.net.URI

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
	constraints {
		// Tika 3.3.1 pins PDFBox 3.0.7, whose PDDocument.<clinit> eagerly reaches AWT. PDFBox
		// 3.0.8 removes that warm-up (PDFBOX-6214), allowing PDDocument and the logging backend to
		// retain their normal runtime initialization in a macOS native image. Keep all PDFBox 3.x
		// modules aligned, and remove these constraints once a Tika upgrade supplies 3.0.8 or later.
		implementation("org.apache.pdfbox:fontbox:3.0.8")
		implementation("org.apache.pdfbox:pdfbox:3.0.8")
		implementation("org.apache.pdfbox:pdfbox-io:3.0.8")
		implementation("org.apache.pdfbox:pdfbox-tools:3.0.8")
		implementation("org.apache.pdfbox:xmpbox:3.0.8")
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

// --- Embedded Tree-sitter native libraries ---
// jtreesitter is an FFM binding; the core libtree-sitter + per-language grammar shared libs are
// dlopen'd at runtime (cannot be static-linked, like sqlite-vec). Embed them as classpath resources
// under native/<os>-<arch>/; at startup TreeSitterLibraryResolver extracts the host-platform ones to
// the runtime dir and TreeSitterEngine loads them by absolute path. Native distributions compile the
// pinned core and every grammar through buildTreeSitterLibraries before resources are staged. Plain
// JVM/test builds stay offline and may reuse prebuilt binaries from packaging/native/<platform>.
val treeSitterCoreVersion = "0.25.10"
val treeSitterJavaVersion = "0.23.5"
val treeSitterJavaScriptVersion = "0.25.0"
val treeSitterTypeScriptVersion = "0.23.2"
val treeSitterScssVersion = "1.0.0"
val treeSitterKotlinVersion = "1.1.0"
val treeSitterScalaVersion = "0.26.0"
val treeSitterPythonVersion = "0.25.0"
val treeSitterGoVersion = "0.25.0"
val treeSitterRustVersion = "0.24.2"
val treeSitterRubyVersion = "0.23.1"
val treeSitterPhpVersion = "0.24.2"
val treeSitterCSharpVersion = "0.23.5"
val treeSitterCVersion = "0.24.2"
val treeSitterCppVersion = "0.23.4"
val treeSitterSwiftVersion = "0.7.3"

val treeSitterNativeRoot = layout.buildDirectory.dir("generated/treesitter-native")

val buildTreeSitterLibraries by tasks.registering {
	group = "build"
	description = "Build the pinned Tree-sitter core and all bundled language grammars for the host platform."
	inputs.property("coreVersion", treeSitterCoreVersion)
	inputs.property("javaVersion", treeSitterJavaVersion)
	inputs.property("javascriptVersion", treeSitterJavaScriptVersion)
	inputs.property("typescriptVersion", treeSitterTypeScriptVersion)
	inputs.property("scssVersion", treeSitterScssVersion)
	inputs.property("kotlinVersion", treeSitterKotlinVersion)
	inputs.property("scalaVersion", treeSitterScalaVersion)
	inputs.property("pythonVersion", treeSitterPythonVersion)
	inputs.property("goVersion", treeSitterGoVersion)
	inputs.property("rustVersion", treeSitterRustVersion)
	inputs.property("rubyVersion", treeSitterRubyVersion)
	inputs.property("phpVersion", treeSitterPhpVersion)
	inputs.property("csharpVersion", treeSitterCSharpVersion)
	inputs.property("cVersion", treeSitterCVersion)
	inputs.property("cppVersion", treeSitterCppVersion)
	inputs.property("swiftVersion", treeSitterSwiftVersion)
	inputs.property("hostOs", providers.systemProperty("os.name"))
	inputs.property("hostArch", providers.systemProperty("os.arch"))
	inputs.property("compiler", providers.environmentVariable("CC").orElse("<platform-default>"))
	outputs.dir(treeSitterNativeRoot)

	doLast {
		val osName = providers.systemProperty("os.name").orElse("").get().lowercase()
		val osToken = when {
			osName.contains("mac") || osName.contains("darwin") -> "macos"
			osName.contains("linux") -> "linux"
			osName.contains("win") -> "windows"
			else -> throw GradleException("Unsupported OS for Tree-sitter native build: $osName")
		}
		val archName = providers.systemProperty("os.arch").orElse("").get().lowercase()
		val archToken = when {
			archName.contains("aarch64") || archName.contains("arm64") -> "aarch64"
			archName.contains("amd64") || archName.contains("x86_64") -> "x86_64"
			else -> throw GradleException("Unsupported architecture for Tree-sitter native build: $archName")
		}
		val suffix = when (osToken) {
			"macos" -> "dylib"
			"windows" -> "dll"
			else -> "so"
		}
		val platformDir = treeSitterNativeRoot.get().dir("$osToken-$archToken").asFile
		val sourceRoot = layout.buildDirectory.dir("tmp/treesitter-sources").get().asFile
		delete(sourceRoot)
		sourceRoot.mkdirs()
		platformDir.mkdirs()

		fun runCommand(command: List<String>, workingDir: File = projectDir) {
			val process = ProcessBuilder(command)
				.directory(workingDir)
				.redirectErrorStream(true)
				.start()
			val output = process.inputStream.bufferedReader().readText()
			if (process.waitFor() != 0) {
				throw GradleException("Command failed: ${command.joinToString(" ")}\n$output")
			}
			if (output.isNotBlank()) logger.info(output.trim())
		}

		fun clone(tag: String, repository: String, directory: File) {
			runCommand(listOf("git", "clone", "--quiet", "--depth", "1", "--branch", tag,
				repository, directory.absolutePath))
		}

		// MSVC-compiled grammar DLLs already export their `tree_sitter_<lang>` entry point via
		// `__declspec(dllexport)` baked into the generated parser.c, so the plain /LD build works for
		// them. The pinned tree-sitter core, however, only guards its exports for GCC/Clang (a bare
		// #pragma GCC visibility push in api.h, and alloc.h explicitly blanks TS_PUBLIC on _WIN32) —
		// cl.exe /LD alone would produce a libtree-sitter.dll that loads but exports nothing. For that
		// one library, compile to object files and auto-generate a .def (replicating what CMake's
		// WINDOWS_EXPORT_ALL_SYMBOLS does) instead of trying to patch the freshly re-cloned upstream
		// headers on every build.
		fun compileWindowsWithAutoExports(output: File, includes: List<File>, sources: List<File>) {
			val compiler = providers.environmentVariable("CC").orElse("cl.exe").get()
			val objDir = File(output.parentFile, "${output.nameWithoutExtension}-obj").apply { mkdirs() }
			val objFiles = sources.map { source ->
				val obj = File(objDir, "${source.nameWithoutExtension}.obj")
				runCommand(listOf(compiler, "/nologo", "/c", "/O2") +
					includes.map { "/I${it.absolutePath}" } +
					listOf(source.absolutePath, "/Fo:${obj.absolutePath}"), platformDir)
				obj
			}

			val dumpbin = providers.environmentVariable("DUMPBIN").orElse("dumpbin.exe").get()
			val symbolLine = Regex("""^\S+\s+\S+\s+\S+\s+notype\s+\(\).*External\s*\|\s*(\S+)""")
			val symbols = objFiles.flatMap { obj ->
				val process = ProcessBuilder(dumpbin, "/nologo", "/symbols", obj.absolutePath)
					.directory(platformDir)
					.redirectErrorStream(true)
					.also { it.environment()["LC_ALL"] = "C" }
					.start()
				val output = process.inputStream.bufferedReader().readText()
				if (process.waitFor() != 0) {
					throw GradleException("dumpbin failed for ${obj.name}:\n$output")
				}
				output.lineSequence().mapNotNull { line -> symbolLine.find(line)?.groupValues?.get(1) }.toList()
			}.filter { it.startsWith("ts_") || it.startsWith("tree_sitter_") }.distinct().sorted()

			if (symbols.isEmpty()) {
				throw GradleException("dumpbin found no exportable ts_*/tree_sitter_* symbols in " +
					"${output.name}'s object files; the symbol-table parsing likely needs adjusting " +
					"for this MSVC toolset version.")
			}

			val defFile = File(objDir, "${output.nameWithoutExtension}.def")
			defFile.writeText(buildString {
				appendLine("LIBRARY ${output.nameWithoutExtension}")
				appendLine("EXPORTS")
				symbols.forEach { appendLine("    $it") }
			})

			// Route the link through cl.exe (already resolving correctly above) rather than invoking
			// link.exe directly: Git Bash's own coreutils `link` (hard-link creation, unrelated to
			// linking) shadows MSVC's linker on PATH ahead of the MSVC toolchain directory under the
			// runner's shell: bash steps. cl.exe finds its own co-located linker regardless of PATH
			// order, so forwarding /DEF: via /link sidesteps the collision entirely.
			runCommand(listOf(compiler, "/nologo", "/LD") + objFiles.map { it.absolutePath } +
				listOf("/Fe:${output.absolutePath}", "/link", "/DEF:${defFile.absolutePath}"), platformDir)
		}

		fun compile(output: File, includes: List<File>, sources: List<File>,
			autoExportSymbolsOnWindows: Boolean = false) {
			val missingSources = sources.filterNot { it.isFile }
			if (missingSources.isNotEmpty()) {
				throw GradleException("Tree-sitter grammar sources not found: ${missingSources.joinToString()}")
			}
			if (osToken == "windows" && autoExportSymbolsOnWindows) {
				compileWindowsWithAutoExports(output, includes, sources)
				return
			}
			val compiler = providers.environmentVariable("CC").orElse(
				if (osToken == "windows") "cl.exe" else "cc").get()
			val command = if (osToken == "windows") {
				listOf(compiler, "/nologo", "/LD", "/O2") +
					includes.map { "/I${it.absolutePath}" } +
					sources.map { it.absolutePath } + listOf("/Fe:${output.absolutePath}")
			} else {
				listOf(compiler, "-shared", "-fPIC", "-O2") +
					includes.flatMap { listOf("-I", it.absolutePath) } +
					listOf("-o", output.absolutePath) + sources.map { it.absolutePath }
			}
			runCommand(command, platformDir)
		}

		val core = sourceRoot.resolve("core")
		val javaGrammar = sourceRoot.resolve("java")
		val javascript = sourceRoot.resolve("javascript")
		val typescript = sourceRoot.resolve("typescript")
		val scss = sourceRoot.resolve("scss")
		val kotlin = sourceRoot.resolve("kotlin")
		val scala = sourceRoot.resolve("scala")
		val python = sourceRoot.resolve("python")
		val go = sourceRoot.resolve("go")
		val rust = sourceRoot.resolve("rust")
		val ruby = sourceRoot.resolve("ruby")
		val php = sourceRoot.resolve("php")
		val csharp = sourceRoot.resolve("csharp")
		val c = sourceRoot.resolve("c")
		val cpp = sourceRoot.resolve("cpp")
		val swift = sourceRoot.resolve("swift")
		clone("v$treeSitterCoreVersion", "https://github.com/tree-sitter/tree-sitter", core)
		clone("v$treeSitterJavaVersion", "https://github.com/tree-sitter/tree-sitter-java", javaGrammar)
		clone("v$treeSitterJavaScriptVersion", "https://github.com/tree-sitter/tree-sitter-javascript", javascript)
		clone("v$treeSitterTypeScriptVersion", "https://github.com/tree-sitter/tree-sitter-typescript", typescript)
		clone("v$treeSitterScssVersion", "https://github.com/tree-sitter-grammars/tree-sitter-scss", scss)
		clone("v$treeSitterKotlinVersion", "https://github.com/tree-sitter-grammars/tree-sitter-kotlin", kotlin)
		clone("v$treeSitterScalaVersion", "https://github.com/tree-sitter/tree-sitter-scala", scala)
		clone("v$treeSitterPythonVersion", "https://github.com/tree-sitter/tree-sitter-python", python)
		clone("v$treeSitterGoVersion", "https://github.com/tree-sitter/tree-sitter-go", go)
		clone("v$treeSitterRustVersion", "https://github.com/tree-sitter/tree-sitter-rust", rust)
		clone("v$treeSitterRubyVersion", "https://github.com/tree-sitter/tree-sitter-ruby", ruby)
		clone("v$treeSitterPhpVersion", "https://github.com/tree-sitter/tree-sitter-php", php)
		clone("v$treeSitterCSharpVersion", "https://github.com/tree-sitter/tree-sitter-c-sharp", csharp)
		clone("v$treeSitterCVersion", "https://github.com/tree-sitter/tree-sitter-c", c)
		clone("v$treeSitterCppVersion", "https://github.com/tree-sitter/tree-sitter-cpp", cpp)

		// The Swift repository intentionally omits generated parser.c. Its pinned crates.io source
		// archive contains the generated C sources published for consumers, so build from that.
		val swiftArchive = sourceRoot.resolve("tree-sitter-swift-$treeSitterSwiftVersion.crate")
		URI("https://static.crates.io/crates/tree-sitter-swift/"
			+ "tree-sitter-swift-$treeSitterSwiftVersion.crate").toURL().openStream().use { input ->
			swiftArchive.outputStream().use(input::copyTo)
		}
		copy {
			from(tarTree(resources.gzip(swiftArchive)))
			into(swift)
			eachFile {
				relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
			}
			includeEmptyDirs = false
		}

		compile(platformDir.resolve("libtree-sitter.$suffix"),
			listOf(core.resolve("lib/include"), core.resolve("lib/src")), listOf(core.resolve("lib/src/lib.c")),
			autoExportSymbolsOnWindows = true)
		compile(platformDir.resolve("tree-sitter-java.$suffix"), listOf(javaGrammar.resolve("src")),
			listOf(javaGrammar.resolve("src/parser.c")))
		compile(platformDir.resolve("tree-sitter-javascript.$suffix"), listOf(javascript.resolve("src")),
			listOf(javascript.resolve("src/parser.c"), javascript.resolve("src/scanner.c")))
		compile(platformDir.resolve("tree-sitter-typescript.$suffix"),
			listOf(typescript.resolve("typescript/src"), typescript.resolve("tsx/src")),
			listOf(
				typescript.resolve("typescript/src/parser.c"), typescript.resolve("typescript/src/scanner.c"),
				typescript.resolve("tsx/src/parser.c"), typescript.resolve("tsx/src/scanner.c")))
		compile(platformDir.resolve("tree-sitter-scss.$suffix"), listOf(scss.resolve("src")),
			listOf(scss.resolve("src/parser.c"), scss.resolve("src/scanner.c")))
		compile(platformDir.resolve("tree-sitter-kotlin.$suffix"), listOf(kotlin.resolve("src")),
			listOf(kotlin.resolve("src/parser.c"), kotlin.resolve("src/scanner.c")))
		compile(platformDir.resolve("tree-sitter-scala.$suffix"), listOf(scala.resolve("src")),
			listOf(scala.resolve("src/parser.c"), scala.resolve("src/scanner.c")))
		compile(platformDir.resolve("tree-sitter-python.$suffix"), listOf(python.resolve("src")),
			listOf(python.resolve("src/parser.c"), python.resolve("src/scanner.c")))
		compile(platformDir.resolve("tree-sitter-go.$suffix"), listOf(go.resolve("src")),
			listOf(go.resolve("src/parser.c")))
		compile(platformDir.resolve("tree-sitter-rust.$suffix"), listOf(rust.resolve("src")),
			listOf(rust.resolve("src/parser.c"), rust.resolve("src/scanner.c")))
		compile(platformDir.resolve("tree-sitter-ruby.$suffix"), listOf(ruby.resolve("src")),
			listOf(ruby.resolve("src/parser.c"), ruby.resolve("src/scanner.c")))
		compile(platformDir.resolve("tree-sitter-php.$suffix"), listOf(php.resolve("php/src")),
			listOf(php.resolve("php/src/parser.c"), php.resolve("php/src/scanner.c")))
		compile(platformDir.resolve("tree-sitter-c-sharp.$suffix"), listOf(csharp.resolve("src")),
			listOf(csharp.resolve("src/parser.c"), csharp.resolve("src/scanner.c")))
		compile(platformDir.resolve("tree-sitter-c.$suffix"), listOf(c.resolve("src")),
			listOf(c.resolve("src/parser.c")))
		compile(platformDir.resolve("tree-sitter-cpp.$suffix"), listOf(cpp.resolve("src")),
			listOf(cpp.resolve("src/parser.c"), cpp.resolve("src/scanner.c")))
		compile(platformDir.resolve("tree-sitter-swift.$suffix"), listOf(swift.resolve("src")),
			listOf(swift.resolve("src/parser.c"), swift.resolve("src/scanner.c")))

		val expected = listOf("libtree-sitter", "tree-sitter-java", "tree-sitter-javascript",
			"tree-sitter-typescript", "tree-sitter-scss", "tree-sitter-kotlin", "tree-sitter-scala",
			"tree-sitter-python", "tree-sitter-go", "tree-sitter-rust", "tree-sitter-ruby",
			"tree-sitter-php", "tree-sitter-c-sharp", "tree-sitter-c", "tree-sitter-cpp",
			"tree-sitter-swift").map { platformDir.resolve("$it.$suffix") }
		val missing = expected.filterNot { it.isFile }
		if (missing.isNotEmpty()) {
			throw GradleException("Tree-sitter build did not produce: ${missing.joinToString()}")
		}
		logger.lifecycle("pieria: built Tree-sitter core + all default source-language packs for $osToken-$archToken")
	}
}

val embedTreeSitterLibraries by tasks.registering(Sync::class) {
	description = "Stage per-arch Tree-sitter libraries as embeddable classpath resources (native/<os>-<arch>/{libtree-sitter,tree-sitter-*}.*)."
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	from(treeSitterNativeRoot) {
		include("*-*/libtree-sitter.*", "*-*/tree-sitter-*.*")
		includeEmptyDirs = false
		eachFile { relativePath = RelativePath(true, "native", *relativePath.segments) }
	}
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
			logger.warn("pieria: no Tree-sitter libraries found in generated or prebuilt native inputs; "
				+ "code symbol parsing will be disabled at runtime. See packaging/native/README.md.")
		} else {
			logger.lifecycle("pieria: embedding Tree-sitter libraries for $staged")
		}
	}
}

sourceSets["main"].resources.srcDir(embedTreeSitterLibraries)

// Only native distribution/image builds fetch and compile grammar sources. Because processResources
// also serves ordinary offline JVM tests, ordering is conditional: mustRunAfter applies only when the
// native task has brought buildTreeSitterLibraries into the same task graph.
embedTreeSitterLibraries.configure { mustRunAfter(buildTreeSitterLibraries) }
tasks.processResources { mustRunAfter(buildTreeSitterLibraries) }
tasks.named("nativeCompile") { dependsOn(buildTreeSitterLibraries) }

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
fun reSignAdhocMacOs(binDir: File, log: org.gradle.api.logging.Logger) {
	if (!providers.systemProperty("os.name").orElse("").get().lowercase().contains("mac")) {
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
		buildTreeSitterLibraries,
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
	// Re-sign in place after install: replacing the installed binary invalidates AMFI's cache for that
	// path, so sign the final files so `pieria restart` works immediately without a manual step.
	doLast {
		reSignAdhocMacOs(destinationDir.resolve("bin"), logger)
	}
}
