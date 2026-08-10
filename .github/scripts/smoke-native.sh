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
cleanup() {
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    # Git Bash's kill cannot always signal a native Win32 process; taskkill always can.
    if [ "${RUNNER_OS:-}" = "Windows" ]; then
      taskkill //PID "$pid" //T //F >/dev/null 2>&1 || true
    else
      kill "$pid" 2>/dev/null || true
    fi
    wait "$pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT

echo "smoke: starting $exe on 127.0.0.1:$port ($platform)"
# check-models=false keeps startup off the network: no runner has Ollama, and the model probe is
# irrelevant to what this step verifies.
"$exe" \
  --pieria.daemon.port="$port" \
  --pieria.app-data.root="$appdata" \
  --pieria.first-run.check-models=false \
  >"$log" 2>&1 &
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
