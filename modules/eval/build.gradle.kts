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
	// Benchmark tests require a live model and dataset; run them explicitly via
	// PIERIA_LIVE_EVAL=1 ./gradlew :eval:test
	filter.excludeTestsMatching("*Benchmark*")
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
