#!/usr/bin/env bash
#
# Smoke-test the freshly built native daemon.
#
# The JVM suite only ever exercises the classpath resource path, and until this step nothing ran the
# native image at all. That left GraalVM's FFM support, xerial sqlite-jdbc's own native-library
# extraction, and `load_extension` against an extracted vec0 path unverified on every platform.
#
# The daemon degrades to FTS-only rather than failing when sqlite-vec cannot load, so "it started"
# is not a sufficient assertion — this checks `vectorSearch` on /pieria-status and fails when the
# binary came up in the reduced mode.
set -euo pipefail

platform="${1:?usage: smoke-native.sh <platform>}"
port="${PIERIA_SMOKE_PORT:-8078}"

bin_dir="modules/daemon/build/distributions/pieria-native/bin"
exe="$bin_dir/pieria-daemon"
if [ "${RUNNER_OS:-}" = "Windows" ]; then
  exe="${exe}.exe"
fi

if [ ! -x "$exe" ] && [ ! -f "$exe" ]; then
  echo "smoke: native daemon not found at $exe" >&2
  ls -l "$bin_dir" >&2 || true
  exit 1
fi

work="$(mktemp -d)"
log="$work/daemon.log"
appdata="$work/appdata"
mkdir -p "$appdata"

pid=""

# Git Bash's `$!` is an MSYS pid, which is NOT the Windows pid taskkill addresses; /proc/<pid>/winpid
# maps between them. Passing the unmapped id to taskkill silently killed nothing, so the daemon
# survived cleanup and the reap below never returned — that hung the windows package job for hours
# *after* its own output had already said "smoke: OK".
win_pid() {
  if [ -r "/proc/$1/winpid" ]; then
    cat "/proc/$1/winpid"
  else
    echo "$1"
  fi
}

cleanup() {
  [ -n "$pid" ] || return 0
  if [ "${RUNNER_OS:-}" = "Windows" ]; then
    # Git Bash's kill cannot always signal a native Win32 process; taskkill always can.
    taskkill //PID "$(win_pid "$pid")" //T //F >/dev/null 2>&1 || true
  else
    kill "$pid" 2>/dev/null || true
  fi
  # Bounded reap instead of `wait`: teardown must never outlive the check it is tearing down. A
  # daemon that refuses to die is the runner's orphan-cleanup problem, not a reason to hold the job
  # open to its 6-hour limit.
  for _ in $(seq 1 10); do
    kill -0 "$pid" 2>/dev/null || return 0
    sleep 1
  done
  echo "smoke: warning — daemon (pid $pid) still running after cleanup; leaving it to the runner" >&2
  return 0
}
trap cleanup EXIT

echo "smoke: starting $exe on 127.0.0.1:$port ($platform)"
# check-models=false keeps startup off the network: no runner has Ollama, and the model probe is
# irrelevant to what this step verifies.
# stdin from /dev/null and both output streams to a file: a background process holding the step's
# own pipes open is the other classic way an Actions step outlives its script.
"$exe" \
  --pieria.daemon.port="$port" \
  --pieria.app-data.root="$appdata" \
  --pieria.first-run.check-models=false \
  </dev/null >"$log" 2>&1 &
pid=$!

status_url="http://127.0.0.1:${port}/pieria-status"
ready=""
for _ in $(seq 1 90); do
  if curl -fsS "$status_url" -o "$work/status.json" 2>/dev/null; then
    ready=1
    break
  fi
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "smoke: daemon exited during startup" >&2
    break
  fi
  sleep 1
done

if [ -z "$ready" ]; then
  echo "smoke: daemon never served $status_url" >&2
  echo "----- daemon log -----" >&2
  cat "$log" >&2 || true
  exit 1
fi

echo "smoke: /pieria-status ->"
cat "$work/status.json"
echo

if ! grep -Eq '"vectorSearch"[[:space:]]*:[[:space:]]*true' "$work/status.json"; then
  echo "smoke: FAILED — the native daemon started but vector search is disabled." >&2
  echo "smoke: sqlite-vec did not load in the native image on $platform." >&2
  echo "----- daemon log -----" >&2
  grep -iE "vec|extension" "$log" >&2 || cat "$log" >&2
  exit 1
fi

echo "smoke: OK — native daemon serves status with vector search enabled on $platform"
