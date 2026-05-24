# JVM Packaging Smoke

Build the packaged JVM artifacts and assemble the local distribution:

```sh
./gradlew :daemon:jvmDist
```

Start the daemon against a temporary database:

```sh
TMPDIR=$(mktemp -d)
PIERIA_JAVA_OPTS="-Dpieria.db.path=$TMPDIR/pieria.db -Dpieria.app-data.root=$TMPDIR/data -Dpieria.app-data.config-dir=$TMPDIR/config -Dpieria.app-data.logs-dir=$TMPDIR/logs -Dpieria.app-data.runtime-dir=$TMPDIR/run -Dpieria.first-run.check-models=false" \
  daemon/build/distributions/pieria-jvm/bin/pieria-daemon
```

In another shell, check health and run the shim command shape:

```sh
curl -fsS http://127.0.0.1:8077/healthz
PIERIA_DAEMON_URL=http://127.0.0.1:8077 daemon/build/distributions/pieria-jvm/bin/pieria-shim
```
