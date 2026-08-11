#!/usr/bin/env bash
set -euo pipefail

# Pieria installer for macOS (Apple Silicon) and Linux.
#
# Downloads the native daemon + gateway binaries for the host platform, installs
# them under PIERIA_HOME, links them onto PATH, and registers the daemon as a
# per-user OS service by delegating to the tested service scripts shipped in the
# release. Windows is served by a separate install.ps1.
#
# Usage (inspect before piping to a shell):
#   curl -fsSL https://raw.githubusercontent.com/VAlux/pieria/main/packaging/install.sh | bash
#   bash install.sh [options]
#
# Re-running is safe: download + link + service install are all idempotent.

REPO="${PIERIA_REPO:-VAlux/pieria}"
VERSION="${PIERIA_VERSION:-latest}"
PIERIA_HOME="${PIERIA_HOME:-$HOME/.local/share/pieria}"
BIN_DIR="${PIERIA_BIN_DIR:-$HOME/.local/bin}"
BASE_URL="${PIERIA_BASE_URL:-}"   # override download host (releases mirror / local testing)
INSTALL_SERVICE=1
DRY_RUN=0
# Normalized to 0/1 up front: the arithmetic test below re-evaluates its operand
# as a variable name, so a word like "true" would abort the script under `set -u`.
case "${PIERIA_ALLOW_UNSUPPORTED:-0}" in
	1|y|yes|true|TRUE|True) ALLOW_UNSUPPORTED=1 ;;
	*) ALLOW_UNSUPPORTED=0 ;;
esac

# Platforms the release workflow actually builds (.github/workflows/release.yml).
# Anything else has no asset to download, so the install is stopped up front
# rather than left to surface as a 404 that reads like a network problem.
SUPPORTED_PLATFORMS="macos-aarch64 linux-x86_64 windows-x86_64"

usage() {
	cat <<'USAGE'
Usage: install.sh [options]

Installs the Pieria native daemon and gateway for the host platform.

Options:
  --version TAG     Release tag to install (default: latest)
  --home PATH       Install root for binaries (default: ~/.local/share/pieria)
  --bin-dir PATH    Directory linked onto PATH (default: ~/.local/bin)
  --no-service      Install binaries only; skip OS service registration
  --allow-unsupported
                    Attempt the install on a platform with no published release
                    build (pair with PIERIA_BASE_URL to serve your own archive)
  --dry-run         Print the steps and resolved URLs without changing anything
  -h, --help        Show this help

Environment overrides: PIERIA_REPO, PIERIA_VERSION, PIERIA_HOME, PIERIA_BIN_DIR,
PIERIA_BASE_URL, PIERIA_ALLOW_UNSUPPORTED.
USAGE
}

log()  { printf '==> %s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }

while (($#)); do
	case "$1" in
		--version) VERSION="$2"; shift 2 ;;
		--home) PIERIA_HOME="$2"; shift 2 ;;
		--bin-dir) BIN_DIR="$2"; shift 2 ;;
		--no-service) INSTALL_SERVICE=0; shift ;;
		--allow-unsupported) ALLOW_UNSUPPORTED=1; shift ;;
		--dry-run) DRY_RUN=1; shift ;;
		-h|--help) usage; exit 0 ;;
		*) echo "Unknown option: $1" >&2; usage >&2; exit 64 ;;
	esac
done

# --- platform detection ------------------------------------------------------
# Maps uname to the os-arch slug used by release assets and packaging/native/.
detect_platform() {
	local os arch
	case "$(uname -s)" in
		Darwin) os="macos" ;;
		Linux)  os="linux" ;;
		*) die "unsupported OS '$(uname -s)'. Windows: use install.ps1." ;;
	esac
	case "$(uname -m)" in
		arm64|aarch64) arch="aarch64" ;;
		x86_64|amd64)  arch="x86_64" ;;
		*) die "unsupported architecture '$(uname -m)'." ;;
	esac
	printf '%s-%s' "$os" "$arch"
}

# Preflight: stop before any download when the detected platform has no release
# build. --allow-unsupported keeps the old best-effort behaviour for anyone
# serving a self-built archive through PIERIA_BASE_URL.
require_supported_platform() {
	local platform="$1"
	case " $SUPPORTED_PLATFORMS " in
		*" $platform "*) return 0 ;;
	esac
	if ((ALLOW_UNSUPPORTED)); then
		warn "no release build is published for '$platform'; continuing because --allow-unsupported was given."
		return 0
	fi
	die "no release build for '$platform'.
Published platforms: ${SUPPORTED_PLATFORMS// /, }.
Build from source with './gradlew :daemon:nativeDist', or re-run with
--allow-unsupported (set PIERIA_BASE_URL to serve your own archive)."
}

# --- download helpers --------------------------------------------------------
DOWNLOADER=""
if command -v curl >/dev/null 2>&1; then
	DOWNLOADER="curl"
elif command -v wget >/dev/null 2>&1; then
	DOWNLOADER="wget"
else
	die "need curl or wget to download release assets."
fi

fetch() {  # fetch <url> <dest>
	local url="$1" dest="$2"
	if ((DRY_RUN)); then
		echo "download $url -> $dest"
		return 0
	fi
	if [[ "$DOWNLOADER" == "curl" ]]; then
		# Pin to https for remote downloads; relax for http/file used in local testing.
		local proto_opt=()
		[[ "$url" == https://* ]] && proto_opt=(--proto '=https')
		log "download $url -> $dest"
		curl -fSL "${proto_opt[@]+"${proto_opt[@]}"}" --retry 3 -o "$dest" "$url"
	else
		wget -q -O "$dest" "$url"
	fi
}

# Resolve the release base URL. A pinned tag uses .../download/<tag>/; "latest"
# uses GitHub's .../latest/download/ redirect. PIERIA_BASE_URL overrides both.
release_base() {
	if [[ -n "$BASE_URL" ]]; then
		printf '%s' "${BASE_URL%/}"
	elif [[ "$VERSION" == "latest" ]]; then
		printf 'https://github.com/%s/releases/latest/download' "$REPO"
	else
		printf 'https://github.com/%s/releases/download/%s' "$REPO" "$VERSION"
	fi
}

# Raw repo URL for the (untagged-on-latest) service scripts. Pinned to the tag
# when a version is given so the service logic matches the installed binaries.
raw_base() {
	local ref="$VERSION"
	[[ "$ref" == "latest" ]] && ref="main"
	printf 'https://raw.githubusercontent.com/%s/%s' "$REPO" "$ref"
}

PLATFORM="$(detect_platform)"
require_supported_platform "$PLATFORM"
RELEASE_BASE="$(release_base)"
TARBALL="pieria-${PLATFORM}.tar.gz"
TARBALL_URL="${RELEASE_BASE}/${TARBALL}"
CHECKSUMS_URL="${RELEASE_BASE}/checksums.txt"

log "platform:   $PLATFORM"
log "version:    $VERSION"
log "install to: $PIERIA_HOME"
log "link into:  $BIN_DIR"

# --- download + verify + extract --------------------------------------------
WORK="$(mktemp -d "${TMPDIR:-/tmp}/pieria-install.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# A failure here is nearly always a missing asset rather than a broken network,
# so name the asset and the release instead of leaving curl's exit code to speak.
fetch "$TARBALL_URL" "$WORK/$TARBALL" || die "could not download $TARBALL from $TARBALL_URL.
Check that release '$VERSION' publishes that asset: https://github.com/$REPO/releases"

# Optional integrity check: verify only if a checksums file is published.
if fetch "$CHECKSUMS_URL" "$WORK/checksums.txt" 2>/dev/null && [[ -s "$WORK/checksums.txt" ]]; then
	if ((DRY_RUN)); then
		echo "verify $TARBALL against checksums.txt"
	else
		# Match "<hash>  name" and the BSD binary form "<hash> *name"; portable across awk/grep.
		expected="$(awk -v f="$TARBALL" '$2 == f || $2 == "*" f {print $1; exit}' "$WORK/checksums.txt")"
		if [[ -n "$expected" ]]; then
			if command -v shasum >/dev/null 2>&1; then
				actual="$(shasum -a 256 "$WORK/$TARBALL" | awk '{print $1}')"
			else
				actual="$(sha256sum "$WORK/$TARBALL" | awk '{print $1}')"
			fi
			[[ "$actual" == "$expected" ]] || die "checksum mismatch for $TARBALL (expected $expected, got $actual)."
			log "checksum verified"
		else
			warn "no checksum entry for $TARBALL; skipping verification."
		fi
	fi
else
	warn "no checksums.txt published; skipping integrity verification."
fi

if ((DRY_RUN)); then
	echo "extract $TARBALL -> $PIERIA_HOME/bin"
else
	mkdir -p "$PIERIA_HOME/bin" "$BIN_DIR"
	# Tarball lays out bin/{pieria,pieria-daemon,pieria-gateway}; strip the bin/.
	tar -xzf "$WORK/$TARBALL" -C "$PIERIA_HOME/bin" --strip-components=1
	chmod +x "$PIERIA_HOME/bin/pieria" "$PIERIA_HOME/bin/pieria-daemon" "$PIERIA_HOME/bin/pieria-gateway"
fi

# --- link onto PATH ----------------------------------------------------------
for tool in pieria pieria-daemon pieria-gateway; do
	if ((DRY_RUN)); then
		echo "ln -sf $PIERIA_HOME/bin/$tool $BIN_DIR/$tool"
	else
		ln -sf "$PIERIA_HOME/bin/$tool" "$BIN_DIR/$tool"
	fi
done

case ":$PATH:" in
	*":$BIN_DIR:"*) ;;
	*) warn "$BIN_DIR is not on your PATH. Add it: export PATH=\"$BIN_DIR:\$PATH\"" ;;
esac

# --- service registration ----------------------------------------------------
if ((INSTALL_SERVICE)); then
	case "$PLATFORM" in
		macos-*) svc_rel="packaging/service/macos/pieria-launchd.sh" ;;
		linux-*) svc_rel="packaging/service/linux/pieria-systemd-user.sh" ;;
	esac
	# Prefer the copy shipped beside this installer (running from a checkout);
	# otherwise download the version-matched script from the repo.
	script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
	repo_root="$(CDPATH= cd -- "$script_dir/.." && pwd)"
	if [[ -f "$repo_root/$svc_rel" ]]; then
		svc_script="$repo_root/$svc_rel"
	else
		svc_script="$WORK/$(basename "$svc_rel")"
		fetch "$(raw_base)/$svc_rel" "$svc_script"
	fi
	svc_args=(install
		--daemon "$PIERIA_HOME/bin/pieria-daemon"
		--gateway "$PIERIA_HOME/bin/pieria-gateway")
	if ((DRY_RUN)); then
		echo "bash $svc_script ${svc_args[*]} --dry-run"
		bash "$svc_script" "${svc_args[@]}" --dry-run || true
	else
		log "registering daemon as a per-user service"
		bash "$svc_script" "${svc_args[@]}"
		bash "$svc_script" start --daemon "$PIERIA_HOME/bin/pieria-daemon" || \
			warn "service installed but failed to start; start it manually with: bash $svc_rel start"
	fi
fi

# --- next steps --------------------------------------------------------------
DAEMON_URL="http://127.0.0.1:8077"
cat <<NEXT

=== Pieria installed ===
Binaries:   $PIERIA_HOME/bin/{pieria,pieria-daemon,pieria-gateway}
Daemon URL: $DAEMON_URL
$( ((INSTALL_SERVICE)) && echo "Service:    registered (first-run init runs on daemon start; check logs for model-pull guidance)." || echo "Service:    skipped (--no-service). Start the daemon yourself: pieria-daemon" )

Wire a harness (registers the MCP gateway + lifecycle hooks). From your project:

  pieria harness install claude-code      # or: codex
  pieria harness install claude-code --user   # wire ~/.claude instead of this repo

Inspect or undo with 'pieria harness list' and 'pieria harness uninstall <name>'.
Run 'pieria harness install <name> --dry-run' first to preview the changes.
========================
NEXT
