#!/usr/bin/env bash
#
# daemon_rebuild_local_macos.sh — rebuild the native daemon, replace the installed binary,
# and restart the launchd-managed daemon. Local dev convenience; gitignored.
#
# Steps:
#   1. GraalVM native-compile the daemon (modules/daemon).
#   2. Stop the launchd agent so the binary is free to overwrite.
#   3. Copy the fresh executable over the installed binary.
#   4. Kickstart-restart the launchd agent so the new binary is running.
#   5. Poll /pieria-health until the daemon reports healthy.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILT_BINARY="$REPO_ROOT/modules/daemon/build/native/nativeCompile/pieria-daemon"
INSTALLED_BINARY="$HOME/.local/share/pieria/bin/pieria-daemon"
LAUNCHD_LABEL="dev.alvo.pieria.daemon"
LAUNCHD_TARGET="gui/$(id -u)/$LAUNCHD_LABEL"
DAEMON_URL="${PIERIA_DAEMON_URL:-http://127.0.0.1:8077}"

# native-image must match the Java 25 bytecode. A pre-set GRAALVM_HOME is honoured ONLY if it is a
# Java 25 JDK; otherwise (or if unset) auto-detect a GraalVM 25 via java_home. This avoids the
# class-version mismatch when the environment points GRAALVM_HOME at an older GraalVM.
is_graalvm25() { [[ -n "$1" && -x "$1/bin/native-image" ]] && "$1/bin/java" -version 2>&1 | grep -q '"25'; }
if ! is_graalvm25 "${GRAALVM_HOME:-}"; then
  GRAALVM_HOME="$(/usr/libexec/java_home -V 2>&1 | awk '/[Gg]raal[Vv][Mm]/ && /25\./ {print $NF; exit}')"
fi
if ! is_graalvm25 "${GRAALVM_HOME:-}"; then
  echo "ERROR: no GraalVM 25 found. Set GRAALVM_HOME to a GraalVM 25 JDK." >&2
  exit 1
fi
export GRAALVM_HOME
export JAVA_HOME="$GRAALVM_HOME"
echo "==> Using GraalVM at $GRAALVM_HOME"

echo "==> 1/3 Native-compiling the daemon (this takes a few minutes)…"
# --no-daemon forks a fresh JVM that inherits GRAALVM_HOME above; a long-lived Gradle daemon
# would otherwise reuse the environment it was first started with and may pick the wrong GraalVM.
"$REPO_ROOT/gradlew" --no-daemon :daemon:nativeCompile

if [[ ! -x "$BUILT_BINARY" ]]; then
  echo "ERROR: expected native binary not found at $BUILT_BINARY" >&2
  exit 1
fi

echo "==> 2/5 Stopping launchd agent $LAUNCHD_LABEL"
# bootout removes the job from the gui domain so KeepAlive cannot respawn the old binary while we
# overwrite it. Tolerate "not loaded" (exit 3 / "No such process") on a first run or after a stop.
launchctl bootout "$LAUNCHD_TARGET" 2>/dev/null || true

echo "==> 3/5 Replacing installed binary at $INSTALLED_BINARY"
mkdir -p "$(dirname "$INSTALLED_BINARY")"
cp -f "$BUILT_BINARY" "$INSTALLED_BINARY"
chmod +x "$INSTALLED_BINARY"
# A plain copy invalidates the Mach-O signature; macOS kills unsigned binaries
# (launchd: OS_REASON_CODESIGNING). Ad-hoc re-sign so the daemon can launch.
codesign --force -s - "$INSTALLED_BINARY"

echo "==> 4/5 Starting launchd agent $LAUNCHD_LABEL"
# bootout fully removed the job, so re-bootstrap it; kickstart alone would fail on an unloaded job.
launchctl bootstrap "gui/$(id -u)" "$HOME/Library/LaunchAgents/$LAUNCHD_LABEL.plist"
launchctl kickstart -k "$LAUNCHD_TARGET"

echo "==> 5/5 Waiting for the daemon to report healthy at $DAEMON_URL/pieria-health"
healthy=0
for attempt in $(seq 1 30); do
  if curl -fsS -o /dev/null "$DAEMON_URL/pieria-health" 2>/dev/null; then
    healthy=1
    echo "    healthy after ${attempt}s"
    break
  fi
  sleep 1
done
if [[ "$healthy" -ne 1 ]]; then
  echo "ERROR: daemon did not become healthy within 30s." >&2
  echo "       Inspect logs: tail -f \"$HOME/Library/Logs/Pieria/pieria-daemon.log\"" >&2
  exit 1
fi

echo "==> Done. New daemon is live. Tail logs with:"
echo "    tail -f \"$HOME/Library/Logs/Pieria/pieria-daemon.log\""
