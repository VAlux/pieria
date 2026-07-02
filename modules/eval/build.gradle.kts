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
	// The live benchmark tests (BenchmarkRunnerLiveTests) self-disable via
	// @EnabledIfEnvironmentVariable(PIERIA_LIVE_EVAL), so no Gradle-level exclude is needed — and a
	// broad "*Benchmark*" exclude would (a) also drop the offline *BenchmarkAdapterTests and (b) block
	// `--tests "*BenchmarkRunner*"`, since Gradle excludes win over an explicit --tests filter.
}

dependencies {
	// Evaluation code instantiates the real IngestionService and RetrievalService directly.
	// daemon publishes a plain jar (jar.enabled = true) alongside its bootJar for this dependency.
	implementation(project(":daemon"))
	implementation("com.fasterxml.jackson.core:jackson-databind")
	// The live benchmark entry point boots a Spring context to obtain the daemon's OpenAiModelGateway.
	implementation("org.springframework.boot:spring-boot")
	implementation("org.springframework.boot:spring-boot-autoconfigure")
	implementation("org.springframework:spring-context")
	implementation("org.slf4j:slf4j-api")

	testImplementation("org.junit.jupiter:junit-jupiter")
	testImplementation("org.assertj:assertj-core")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
