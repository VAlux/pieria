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

// A single runnable jar, so authors of harvesters in any language can run the conformance kit.
tasks.register<Jar>("conformanceJar") {
	group = "build"
	description = "Assemble the runnable harvester conformance kit."
	archiveFileName = "harvester-conformance.jar"
	manifest {
		attributes["Main-Class"] = "dev.alvo.pieria.harvester.conformance.ConformanceMain"
	}
	from(sourceSets.main.get().output)
	dependsOn(configurations.runtimeClasspath)
	from({ configurations.runtimeClasspath.get().map { zipTree(it) } })
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

// PythonSampleTests runs the sample harvester in harvest-examples/, so a change there must rerun the tests.
tasks.test {
	inputs.files(fileTree(rootProject.layout.projectDirectory.dir("harvest-examples")) { exclude("**/__pycache__/**") })
		.withPropertyName("harvestExamples")
}
