plugins {
	java
	id("org.springframework.boot")
	id("io.spring.dependency-management")
	id("org.graalvm.buildtools.native")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
	}
}

dependencies {
	implementation(project(":shared"))
	implementation("org.springframework.boot:spring-boot-starter")
	implementation("org.springframework:spring-web")
	implementation("org.springframework.ai:spring-ai-starter-mcp-server")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.bootJar {
	archiveFileName.set("pieria-gateway.jar")
}

graalvmNative {
	binaries {
		named("main") {
			imageName.set("pieria-gateway")
			mainClass.set("dev.alvo.pieria.gateway.GatewayApplication")
			buildArgs.addAll(
				"--enable-url-protocols=http,https",
				"-H:+ReportExceptionStackTraces",
				"-H:+UnlockExperimentalVMOptions",
				"-H:Optimize=2"
			)
		}
	}
}
