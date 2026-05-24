#!/usr/bin/env bash
set -euo pipefail

usage() {
	cat <<'USAGE'
Usage: pieria-systemd-user.sh <install|start|stop|status|uninstall> [options]

Installs and controls the Pieria daemon as a per-user systemd service.
Only the daemon is registered as a service; the shim path is emitted as harness
configuration guidance.

Options:
  --daemon PATH     Daemon executable, wrapper, or pieria.jar path (default: ~/.local/bin/pieria-daemon)
  --java PATH       Java executable used when --daemon points at a jar (default: java)
  --shim PATH       Shim executable path for harness MCP configs (default: ~/.local/bin/pieria-shim)
  --name NAME       systemd unit name without .service (default: pieria-daemon)
  --host HOST       Daemon bind host (default: 127.0.0.1)
  --port PORT       Daemon bind port (default: 8077)
  --data-dir PATH   Data directory (default: ~/.local/share/pieria)
  --config-dir PATH Config directory (default: ~/.config/pieria)
  --log-dir PATH    Log directory (default: ~/.local/state/pieria/logs)
  --runtime-dir PATH Runtime directory (default: DATA_DIR/run)
  --dry-run         Print generated service content or commands without installing
  -h, --help        Show this help
USAGE
}

systemd_escape() {
	local value="$1"
	value=${value//\\/\\\\}
	value=${value//\"/\\\"}
	value=${value//%/%%}
	printf '%s' "$value"
}

quote_arg() {
	printf '"%s"' "$(systemd_escape "$1")"
}

exec_start() {
	if [[ "$DAEMON" == *.jar ]]; then
		quote_arg "$JAVA"
		printf ' -jar '
		quote_arg "$DAEMON"
	else
		quote_arg "$DAEMON"
	fi
	printf ' '
	quote_arg "--pieria.daemon.host=$HOST"
	printf ' '
	quote_arg "--pieria.daemon.port=$PORT"
	printf ' '
	quote_arg "--pieria.db.path=$DATA_DIR/pieria.db"
	printf ' '
	quote_arg "--pieria.app-data.root=$DATA_DIR"
	printf ' '
	quote_arg "--pieria.app-data.config-dir=$CONFIG_DIR"
	printf ' '
	quote_arg "--pieria.app-data.logs-dir=$LOG_DIR"
	printf ' '
	quote_arg "--pieria.app-data.runtime-dir=$RUNTIME_DIR"
	printf ' '
	quote_arg "--logging.file.name=$LOG_DIR/pieria-daemon.log"
}

generate_unit() {
	cat <<UNIT
# Pieria shim executable for harness MCP configs: $SHIM
[Unit]
Description=Pieria local memory daemon
Documentation=https://github.com/dev-alvo/pieria
After=network.target

[Service]
Type=simple
ExecStart=$(exec_start)
WorkingDirectory=$(quote_arg "$DATA_DIR")
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=default.target
UNIT
}

ACTION="${1:-}"
if [[ -z "$ACTION" || "$ACTION" == "-h" || "$ACTION" == "--help" ]]; then
	usage
	exit 0
fi
shift

NAME="pieria-daemon"
DAEMON="$HOME/.local/bin/pieria-daemon"
JAVA="java"
SHIM="$HOME/.local/bin/pieria-shim"
HOST="127.0.0.1"
PORT="8077"
DATA_DIR="$HOME/.local/share/pieria"
CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/pieria"
LOG_DIR="$HOME/.local/state/pieria/logs"
RUNTIME_DIR=""
DRY_RUN=0

while (($#)); do
	case "$1" in
		--daemon) DAEMON="$2"; shift 2 ;;
		--java) JAVA="$2"; shift 2 ;;
		--shim) SHIM="$2"; shift 2 ;;
		--name) NAME="${2%.service}"; shift 2 ;;
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

RUNTIME_DIR="${RUNTIME_DIR:-$DATA_DIR/run}"

UNIT_DIR="$HOME/.config/systemd/user"
UNIT_NAME="$NAME.service"
UNIT_PATH="$UNIT_DIR/$UNIT_NAME"

case "$ACTION" in
	install)
		if ((DRY_RUN)); then
			echo "# Would write $UNIT_PATH"
			generate_unit
			exit 0
		fi
		mkdir -p "$UNIT_DIR" "$DATA_DIR" "$CONFIG_DIR" "$LOG_DIR" "$RUNTIME_DIR"
		generate_unit > "$UNIT_PATH"
		systemctl --user daemon-reload
		systemctl --user enable "$UNIT_NAME"
		echo "Installed $UNIT_NAME. Start it with: $0 start"
		;;
	start)
		if ((DRY_RUN)); then
			echo "systemctl --user start $UNIT_NAME"
			exit 0
		fi
		systemctl --user start "$UNIT_NAME"
		;;
	stop)
		if ((DRY_RUN)); then
			echo "systemctl --user stop $UNIT_NAME"
			exit 0
		fi
		systemctl --user stop "$UNIT_NAME"
		;;
	status)
		if ((DRY_RUN)); then
			echo "systemctl --user status $UNIT_NAME"
			exit 0
		fi
		systemctl --user status "$UNIT_NAME"
		;;
	uninstall)
		if ((DRY_RUN)); then
			echo "systemctl --user stop $UNIT_NAME"
			echo "systemctl --user disable $UNIT_NAME"
			echo "rm -f $UNIT_PATH"
			echo "systemctl --user daemon-reload"
			exit 0
		fi
		systemctl --user stop "$UNIT_NAME" || true
		systemctl --user disable "$UNIT_NAME" || true
		rm -f "$UNIT_PATH"
		systemctl --user daemon-reload
		;;
	*)
		echo "Unknown action: $ACTION" >&2
		usage >&2
		exit 64
		;;
esac
