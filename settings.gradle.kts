rootProject.name = "pieria"

include("shared", "daemon", "gateway", "eval", "cli", "harvester-sdk", "harvesters")

// Physical layout: all modules live under modules/ but keep their short logical names so all
// task paths (:daemon:test, :gateway:bootJar, etc.) and project() references stay unchanged.
rootDir.resolve("modules").let { base ->
	listOf("shared", "daemon", "gateway", "eval", "cli", "harvester-sdk", "harvesters").forEach { name ->
		project(":$name").projectDir = base.resolve(name)
	}
}
