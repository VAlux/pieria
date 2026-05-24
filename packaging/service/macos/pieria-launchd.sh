#!/usr/bin/env bash
set -euo pipefail

usage() {
	cat <<'USAGE'
Usage: pieria-launchd.sh <install|start|stop|status|uninstall> [options]

Installs and controls the Pieria daemon as a per-user launchd agent on macOS.
Only the daemon is registered as a service; the shim path is emitted as harness
configuration guidance.

Options:
  --daemon PATH     Daemon executable, wrapper, or pieria.jar path (default: ~/.local/bin/pieria-daemon)
  --java PATH       Java executable used when --daemon points at a jar (default: java)
  --shim PATH       Shim executable path for harness MCP configs (default: ~/.local/bin/pieria-shim)
  --label LABEL     launchd label (default: dev.alvo.pieria.daemon)
  --host HOST       Daemon bind host (default: 127.0.0.1)
  --port PORT       Daemon bind port (default: 8077)
  --data-dir PATH   Data directory (default: ~/Library/Application Support/Pieria)
  --config-dir PATH Config directory (default: DATA_DIR/config)
  --log-dir PATH    Log directory (default: ~/Library/Logs/Pieria)
  --runtime-dir PATH Runtime directory (default: DATA_DIR/run)
  --dry-run         Print generated service content or commands without installing
  -h, --help        Show this help
USAGE
}

xml_escape() {
	local value="$1"
	value=${value//&/&amp;}
	value=${value//</&lt;}
	value=${value//>/&gt;}
	value=${value//\"/&quot;}
	value=${value//\'/&apos;}
	printf '%s' "$value"
}

program_arguments() {
	if [[ "$DAEMON" == *.jar ]]; then
		printf '        <string>%s</string>\n' "$(xml_escape "$JAVA")"
		printf '        <string>-jar</string>\n'
		printf '        <string>%s</string>\n' "$(xml_escape "$DAEMON")"
	else
		printf '        <string>%s</string>\n' "$(xml_escape "$DAEMON")"
	fi
	printf '        <string>--pieria.daemon.host=%s</string>\n' "$(xml_escape "$HOST")"
	printf '        <string>--pieria.daemon.port=%s</string>\n' "$(xml_escape "$PORT")"
	printf '        <string>--pieria.db.path=%s</string>\n' "$(xml_escape "$DATA_DIR/pieria.db")"
	printf '        <string>--pieria.app-data.root=%s</string>\n' "$(xml_escape "$DATA_DIR")"
	printf '        <string>--pieria.app-data.config-dir=%s</string>\n' "$(xml_escape "$CONFIG_DIR")"
	printf '        <string>--pieria.app-data.logs-dir=%s</string>\n' "$(xml_escape "$LOG_DIR")"
	printf '        <string>--pieria.app-data.runtime-dir=%s</string>\n' "$(xml_escape "$RUNTIME_DIR")"
	printf '        <string>--logging.file.name=%s</string>\n' "$(xml_escape "$LOG_DIR/pieria-daemon.log")"
}

generate_plist() {
	cat <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<!-- Pieria shim executable for harness MCP configs: $(xml_escape "$SHIM") -->
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$(xml_escape "$LABEL")</string>
    <key>ProgramArguments</key>
    <array>
$(program_arguments)
    </array>
    <key>RunAtLoad</key>
    <false/>
    <key>KeepAlive</key>
    <dict>
        <key>SuccessfulExit</key>
        <false/>
    </dict>
    <key>StandardOutPath</key>
    <string>$(xml_escape "$LOG_DIR/pieria-daemon.out.log")</string>
    <key>StandardErrorPath</key>
    <string>$(xml_escape "$LOG_DIR/pieria-daemon.err.log")</string>
    <key>WorkingDirectory</key>
    <string>$(xml_escape "$DATA_DIR")</string>
</dict>
</plist>
PLIST
}

ACTION="${1:-}"
if [[ -z "$ACTION" || "$ACTION" == "-h" || "$ACTION" == "--help" ]]; then
	usage
	exit 0
fi
shift

LABEL="dev.alvo.pieria.daemon"
DAEMON="$HOME/.local/bin/pieria-daemon"
JAVA="java"
SHIM="$HOME/.local/bin/pieria-shim"
HOST="127.0.0.1"
PORT="8077"
DATA_DIR="$HOME/Library/Application Support/Pieria"
CONFIG_DIR=""
LOG_DIR="$HOME/Library/Logs/Pieria"
RUNTIME_DIR=""
DRY_RUN=0

while (($#)); do
	case "$1" in
		--daemon) DAEMON="$2"; shift 2 ;;
		--java) JAVA="$2"; shift 2 ;;
		--shim) SHIM="$2"; shift 2 ;;
		--label) LABEL="$2"; shift 2 ;;
		--host) HOST="$2"; shift 2 ;;
		--port) PORT="$2"; shift 2 ;;
		--data-dir) DATA_DIR="$2"; shift 2 ;;
		--config-dir) CONFIG_DIR="$2"; shift 2 ;;
		--log-dir) LOG_DIR="$2"; shift 2 ;;
		--runtime-dir) RUNTIME_DIR="$2"; shift 2 ;;
		--dry-run) DRY_RUN=1; shift ;;
		-h|--help) usage; exit 0 ;;
		*) echo "Unknown option: $1" >&2; usage >&2; exit 64 ;;
	esac
done

CONFIG_DIR="${CONFIG_DIR:-$DATA_DIR/config}"
RUNTIME_DIR="${RUNTIME_DIR:-$DATA_DIR/run}"

PLIST_DIR="$HOME/Library/LaunchAgents"
PLIST_PATH="$PLIST_DIR/$LABEL.plist"
SERVICE_TARGET="gui/$UID/$LABEL"

case "$ACTION" in
	install)
		if ((DRY_RUN)); then
			echo "# Would write $PLIST_PATH"
			generate_plist
			exit 0
		fi
		mkdir -p "$PLIST_DIR" "$DATA_DIR" "$CONFIG_DIR" "$LOG_DIR" "$RUNTIME_DIR"
		generate_plist > "$PLIST_PATH"
		if ! launchctl print "$SERVICE_TARGET" >/dev/null 2>&1; then
			launchctl bootstrap "gui/$UID" "$PLIST_PATH"
		fi
		echo "Installed $LABEL. Start it with: $0 start"
		;;
	start)
		if ((DRY_RUN)); then
			echo "launchctl bootstrap gui/$UID $PLIST_PATH"
			echo "launchctl enable $SERVICE_TARGET"
			echo "launchctl kickstart -k $SERVICE_TARGET"
			exit 0
		fi
		if ! launchctl print "$SERVICE_TARGET" >/dev/null 2>&1; then
			launchctl bootstrap "gui/$UID" "$PLIST_PATH"
		fi
		launchctl enable "$SERVICE_TARGET"
		launchctl kickstart -k "$SERVICE_TARGET"
		;;
	stop)
		if ((DRY_RUN)); then
			echo "launchctl kill TERM $SERVICE_TARGET"
			exit 0
		fi
		launchctl kill TERM "$SERVICE_TARGET" || true
		;;
	status)
		if ((DRY_RUN)); then
			echo "launchctl print $SERVICE_TARGET"
			exit 0
		fi
		launchctl print "$SERVICE_TARGET"
		;;
	uninstall)
		if ((DRY_RUN)); then
			echo "launchctl kill TERM $SERVICE_TARGET"
			echo "launchctl bootout gui/$UID $PLIST_PATH"
			echo "rm -f $PLIST_PATH"
			exit 0
		fi
		launchctl kill TERM "$SERVICE_TARGET" || true
		launchctl bootout "gui/$UID" "$PLIST_PATH" || true
		rm -f "$PLIST_PATH"
		;;
	*)
		echo "Unknown action: $ACTION" >&2
		usage >&2
		exit 64
		;;
esac
