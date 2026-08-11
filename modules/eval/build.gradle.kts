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
	// The tests here are pure adapter/config/renderer unit tests — no daemon, no model provider.
	// Resolve relative paths against the repo root anyway, to match how the benchmark tasks run.
	workingDir = rootDir
}

/**
 * Runs the LoCoMo benchmark against a real daemon booted in-process on a throwaway DB. Needs a local
 * dataset (datasets/locomo/locomo10.json) and a reachable model provider — never run by CI.
 *
 *   ./gradlew :eval:locomo --args="--conversations=1 --sessions=3 --questions=10 --no-judge"
 */
tasks.register<JavaExec>("locomo") {
	group = "verification"
	description = "Run the LoCoMo benchmark against a real daemon (see --args=\"--help\")"
	mainClass = "dev.alvo.pieria.evaluation.BenchmarkRunner"
	classpath = sourceSets["main"].runtimeClasspath
	// datasets/ and pieria-eval-reports/ are repo-root relative.
	workingDir = rootDir
}

/**
 * Re-renders a previously written report JSON as HTML, without re-running the benchmark.
 *
 *   ./gradlew :eval:locomoReport --args="pieria-eval-reports/evaluation-....json"
 */
tasks.register<JavaExec>("locomoReport") {
	group = "verification"
	description = "Render an existing benchmark report JSON as HTML"
	mainClass = "dev.alvo.pieria.evaluation.HtmlReportWriter"
	classpath = sourceSets["main"].runtimeClasspath
	workingDir = rootDir
}

dependencies {
	// The harness drives a REAL daemon over HTTP: it boots PieriaApplication as a web server on a
	// throwaway temp DB (LiveDaemon) and talks to it via the shared HTTP DTOs (DaemonEvalClient).
	// daemon publishes a plain jar (jar.enabled = true) alongside its bootJar for this dependency;
	// shared carries the request/response records the client (de)serializes.
	implementation(project(":daemon"))
	implementation(project(":shared"))
	implementation("com.fasterxml.jackson.core:jackson-databind")
	// EvaluationReport.generatedAt is an Instant; the report must round-trip through JSON.
	implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
	// Standalone Thymeleaf (no Spring MVC) renders the JSON report into a self-contained HTML page.
	implementation("org.thymeleaf:thymeleaf")
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
