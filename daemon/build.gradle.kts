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
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.ai:spring-ai-starter-model-ollama")
	runtimeOnly("org.xerial:sqlite-jdbc")
	// sqlite-vec (SPEC 4/5.2): there is no reliable, cross-platform Maven coordinate that ships the
	// `vec0` loadable extension for the xerial driver. The extension is therefore loaded at runtime
	// via `SELECT load_extension('vec0')` (DataSourceConfig), best-effort: the daemon must locate
	// the native sqlite-vec library on the OS extension search path (or have it installed alongside
	// the binary in Phase 5 packaging). When absent, vector search is disabled and FTS + keyed
	// lookup still work. See SqliteVectorIndex for the capability-gated table creation.
	testImplementation(project(":shim"))
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.springframework.ai:spring-ai-spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.bootJar {
	archiveFileName.set("pieria.jar")
}
