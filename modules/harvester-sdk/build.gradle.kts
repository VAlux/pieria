plugins {
	`java-library`
	id("io.spring.dependency-management")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.6")
	}
}

// Consumed by outside plugin authors, so this module deliberately depends on no other Pieria module.
dependencies {
	api("tools.jackson.core:jackson-databind")
	implementation("tools.jackson.dataformat:jackson-dataformat-toml")

	testImplementation("org.junit.jupiter:junit-jupiter")
	testImplementation("org.assertj:assertj-core")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
