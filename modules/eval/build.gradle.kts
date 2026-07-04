plugins {
	id("io.spring.dependency-management")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.6")
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
	}
}

tasks.withType<Test> {
	testLogging {
		showStandardStreams = true
	}
	// Resolve relative paths (datasets/, pieria-eval-reports/) against the repo root so live
	// benchmark runs find their dataset and write their report where the docs say they will.
	workingDir = rootDir
	// The live benchmark test (DaemonBenchmarkLiveTests) self-disables via
	// @EnabledIfEnvironmentVariable(PIERIA_LIVE_EVAL): it boots a real daemon and needs Ollama, so it
	// never runs in CI. The remaining tests are pure adapter/fixture-loader unit tests.
}

dependencies {
	// The harness drives a REAL daemon over HTTP: it boots PieriaApplication as a web server on a
	// throwaway temp DB (LiveDaemon) and talks to it via the shared HTTP DTOs (DaemonEvalClient).
	// daemon publishes a plain jar (jar.enabled = true) alongside its bootJar for this dependency;
	// shared carries the request/response records the client (de)serializes.
	implementation(project(":daemon"))
	implementation(project(":shared"))
	implementation("com.fasterxml.jackson.core:jackson-databind")
	// Booting the in-process daemon (web) and the judge gateway (non-web) needs the Boot runtime.
	implementation("org.springframework.boot:spring-boot")
	// WebServerApplicationContext lives here (Boot 4 split); LiveDaemon reads the random port off it.
	implementation("org.springframework.boot:spring-boot-web-server")
	implementation("org.springframework.boot:spring-boot-autoconfigure")
	implementation("org.springframework:spring-context")
	implementation("org.slf4j:slf4j-api")

	testImplementation("org.junit.jupiter:junit-jupiter")
	testImplementation("org.assertj:assertj-core")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
