plugins {
	java
	id("io.spring.dependency-management")
	id("org.graalvm.buildtools.native")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.6")
	}
}

dependencies {
	implementation(project(":harvester-sdk"))
	implementation(project(":shared"))
	implementation("org.jsoup:jsoup:1.16.1")

	testImplementation("org.junit.jupiter:junit-jupiter")
	testImplementation("org.assertj:assertj-core")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

graalvmNative {
	binaries {
		named("main") {
			imageName.set("pieria-harvest")
			mainClass.set("dev.alvo.pieria.harvesters.PieriaHarvest")
			buildArgs.addAll(
				"--enable-url-protocols=http,https",
				"-H:+ReportExceptionStackTraces",
				// Real sites use more than the charsets a native image includes by default.
				"-H:+AddAllCharsets"
			)
		}
	}
}
