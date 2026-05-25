#!/usr/bin/env bash
set -euo pipefail

# End-to-end smoke test for packaging/install.sh without a real GitHub release.
#
# Builds a stand-in release (dummy daemon/gateway binaries + tarball + checksums),
# serves it over a local HTTP server, runs the installer into throwaway dirs with
# --no-service, and asserts the binaries and PATH symlinks landed. Also exercises
# the --dry-run path. Nothing touches your real ~/.local or any OS service.
#
# Usage: bash packaging/test-install.sh

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
INSTALLER="$SCRIPT_DIR/install.sh"

[[ -f "$INSTALLER" ]] || { echo "error: cannot find install.sh next to this script" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "error: python3 is required to serve the fake release" >&2; exit 1; }

# --- platform slug (same mapping as install.sh) ------------------------------
case "$(uname -s)" in
	Darwin) OS="macos" ;;
	Linux)  OS="linux" ;;
	*) echo "error: this tester covers macOS/Linux; use install.ps1 on Windows" >&2; exit 1 ;;
esac
case "$(uname -m)" in
	arm64|aarch64) ARCH="aarch64" ;;
	x86_64|amd64)  ARCH="x86_64" ;;
	*) echo "error: unsupported arch $(uname -m)" >&2; exit 1 ;;
esac
PLATFORM="$OS-$ARCH"
TARBALL="pieria-$PLATFORM.tar.gz"

# --- scratch workspace -------------------------------------------------------
WORK="$(mktemp -d "${TMPDIR:-/tmp}/pieria-test-install.XXXXXX")"
REL="$WORK/release"
HOME_DIR="$WORK/home"
BIN_DIR="$WORK/bin"
SERVER_PID=""

cleanup() {
	[[ -n "$SERVER_PID" ]] && kill "$SERVER_PID" 2>/dev/null || true
	rm -rf "$WORK"
}
trap cleanup EXIT

PASS=0
FAIL=0
check() {  # check <description> <test-expression...>
	local desc="$1"; shift
	if "$@"; then
		printf '  ok   %s\n' "$desc"; PASS=$((PASS + 1))
	else
		printf '  FAIL %s\n' "$desc"; FAIL=$((FAIL + 1))
	fi
}

# --- build the stand-in release ---------------------------------------------
echo "==> building stand-in release for $PLATFORM"
mkdir -p "$REL" "$WORK/stage/bin"
printf '#!/bin/sh\necho "pieria-daemon stub $*"\n'  > "$WORK/stage/bin/pieria-daemon"
printf '#!/bin/sh\necho "pieria-gateway stub $*"\n' > "$WORK/stage/bin/pieria-gateway"
chmod +x "$WORK/stage/bin/"*
# Entries are "bin/pieria-*"; the installer strips one leading component.
tar -czf "$REL/$TARBALL" -C "$WORK/stage" bin
if command -v shasum >/dev/null 2>&1; then
	( cd "$REL" && shasum -a 256 "$TARBALL" > checksums.txt )
else
	( cd "$REL" && sha256sum "$TARBALL" > checksums.txt )
fi

# --- serve it over localhost -------------------------------------------------
PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')"
python3 -m http.server "$PORT" --directory "$REL" --bind 127.0.0.1 >/dev/null 2>&1 &
SERVER_PID=$!
disown "$SERVER_PID" 2>/dev/null || true   # suppress job-control "Terminated" notice on cleanup
BASE="http://127.0.0.1:$PORT"

# wait for readiness
for _ in $(seq 1 50); do
	if curl -fsS "$BASE/$TARBALL" -o /dev/null 2>/dev/null; then break; fi
	sleep 0.1
done
echo "==> serving $REL at $BASE (pid $SERVER_PID)"

# --- dry-run pass (no writes) ------------------------------------------------
echo "==> dry-run"
DRY_OUT="$(PIERIA_BASE_URL="$BASE" bash "$INSTALLER" --home "$HOME_DIR" --bin-dir "$BIN_DIR" --no-service --dry-run)"
check "dry-run resolves the platform tarball" grep -q "$TARBALL" <<<"$DRY_OUT"
check "dry-run wrote nothing to home" bash -c "[[ ! -e '$HOME_DIR/bin/pieria-daemon' ]]"

# --- real install (no service) -----------------------------------------------
echo "==> install"
INSTALL_OUT="$(PIERIA_BASE_URL="$BASE" bash "$INSTALLER" --home "$HOME_DIR" --bin-dir "$BIN_DIR" --no-service)"
check "checksum verified line printed"   grep -q "checksum verified" <<<"$INSTALL_OUT"
check "daemon binary installed"          test -x "$HOME_DIR/bin/pieria-daemon"
check "gateway binary installed"         test -x "$HOME_DIR/bin/pieria-gateway"
check "daemon symlinked onto PATH dir"   test -L "$BIN_DIR/pieria-daemon"
check "gateway symlinked onto PATH dir"  test -L "$BIN_DIR/pieria-gateway"
check "gateway symlink resolves"         test -x "$BIN_DIR/pieria-gateway"
check "MCP snippet references gateway"   grep -q "$HOME_DIR/bin/pieria-gateway" <<<"$INSTALL_OUT"
check "installed binary runs"            bash -c "'$BIN_DIR/pieria-gateway' --version | grep -q 'pieria-gateway stub'"

# --- idempotency -------------------------------------------------------------
echo "==> re-install (idempotency)"
check "second install succeeds" bash -c "PIERIA_BASE_URL='$BASE' bash '$INSTALLER' --home '$HOME_DIR' --bin-dir '$BIN_DIR' --no-service >/dev/null"

# --- tamper detection --------------------------------------------------------
echo "==> checksum mismatch is rejected"
printf 'corrupt' >> "$REL/$TARBALL"
if PIERIA_BASE_URL="$BASE" bash "$INSTALLER" --home "$WORK/home2" --bin-dir "$WORK/bin2" --no-service >/dev/null 2>&1; then
	check "corrupted tarball rejected" false
else
	check "corrupted tarball rejected" true
fi

# --- result ------------------------------------------------------------------
echo
echo "==> $PASS passed, $FAIL failed"
[[ "$FAIL" -eq 0 ]]
