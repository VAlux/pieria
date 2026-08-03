plugins {
	java
	id("org.springframework.boot") version "4.0.6" apply false
	id("io.spring.dependency-management") version "1.1.7" apply false
	id("org.graalvm.buildtools.native") version "0.11.5" apply false
}

allprojects {
	group = "dev.alvo"
	version = "0.2.0.2"

	repositories {
		mavenCentral()
	}
}

subprojects {
	apply(plugin = "java")

	extra["springAiVersion"] = "2.0.0-M6"

	configure<JavaPluginExtension> {
		toolchain {
			languageVersion = JavaLanguageVersion.of(25)
		}
	}

	tasks.withType<Test> {
		useJUnitPlatform()
	}
}
