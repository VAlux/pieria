rootProject.name = "pieria"

include("shared", "daemon", "shim", "eval")

// Physical layout: all modules live under modules/ but keep their short logical names so all
// task paths (:daemon:test, :shim:bootJar, etc.) and project() references stay unchanged.
rootDir.resolve("modules").let { base ->
	listOf("shared", "daemon", "shim", "eval").forEach { name ->
		project(":$name").projectDir = base.resolve(name)
	}
}
