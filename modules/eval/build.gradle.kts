plugins {
	id("io.spring.dependency-management")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.6")
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
	}
}

dependencies {
	// Evaluation code instantiates the real IngestionService and RetrievalService directly.
	// daemon publishes a plain jar (jar.enabled = true) alongside its bootJar for this dependency.
	implementation(project(":daemon"))
	// Jackson is used by EvaluationFixtureLoader and EvaluationReportWriter; version from BOM.
	implementation("com.fasterxml.jackson.core:jackson-databind")

	testImplementation("org.junit.jupiter:junit-jupiter")
	testImplementation("org.assertj:assertj-core")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
