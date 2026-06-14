plugins {
	`java-library`
	id("io.spring.dependency-management")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.6")
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
	}
}

dependencies {
	api("jakarta.validation:jakarta.validation-api")
	api("com.fasterxml.jackson.core:jackson-annotations")
	// Config loading/merging (dev.alvo.pieria.config.*) is shared by the CLI and the daemon,
	// so the tree-model + TOML parser are exported as api.
	api("tools.jackson.core:jackson-databind")
	api("tools.jackson.dataformat:jackson-dataformat-toml")

	// GraalVM native-image Feature/RuntimeReflection API for ApiContractFeature. Compile-only — the
	// classes are provided by the image builder at nativeCompile time and are never on the normal
	// runtime classpath. Keep the version aligned with the GraalVM toolchain (currently 25.0.3).
	compileOnly("org.graalvm.sdk:nativeimage:25.0.3")

	testImplementation("org.junit.jupiter:junit-jupiter")
	testImplementation("org.assertj:assertj-core")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
