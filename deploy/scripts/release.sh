#!/usr/bin/env bash
# 蓝绿发布：新 jar 起在空闲颜色，冒烟通过后切流，再停旧颜色。
#
# 用法：./release.sh /tmp/panel-backend.jar 20261113-a1b2c3d
#
# 前置：
#   - /etc/systemd/system/panel-backend@.service
#   - /etc/panel/panel.env（公共）、blue.env / green.env（各含 SERVER_PORT、PANEL_JAR）
#   - /etc/nginx/panel-upstream.conf 由 switch-upstream.sh 管理
set -euo pipefail

JAR="${1:-}"
VERSION="${2:-}"

if [[ -z "$JAR" || -z "$VERSION" ]]; then
  echo "usage: $0 <jar-path> <version>   e.g. $0 /tmp/panel-backend.jar 20261113-a1b2c3d" >&2
  exit 2
fi
[[ -f "$JAR" ]] || { echo "jar not found: $JAR" >&2; exit 2; }
[[ "$VERSION" =~ ^[0-9]{8}-[0-9a-f]{7,}$ ]] || {
  echo "version 格式应为 YYYYMMDD-<git sha7>，实际：$VERSION" >&2; exit 2;
}

PANEL_HOME=${PANEL_HOME:-/opt/panel}
CONF_DIR=${CONF_DIR:-/etc/panel}
UPSTREAM_FILE=${UPSTREAM_FILE:-/etc/nginx/panel-upstream.conf}
BLUE_PORT=${BLUE_PORT:-8080}
GREEN_PORT=${GREEN_PORT:-8081}
RELEASE_DIR="$PANEL_HOME/releases/$VERSION"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

current_port()   { grep -oE '127\.0\.0\.1:[0-9]+' "$UPSTREAM_FILE" | head -n1 | cut -d: -f2; }
port_of_color()  { [[ "$1" == "blue" ]] && echo "$BLUE_PORT" || echo "$GREEN_PORT"; }
color_of_port()  { [[ "$1" == "$BLUE_PORT" ]] && echo "blue" || echo "green"; }

wait_health() {
  local port="$1" timeout="${2:-180}" i=0
  while (( i < timeout )); do
    if curl -fsS --max-time 3 "http://127.0.0.1:${port}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep 2; i=$((i+2))
  done
  return 1
}

set_jar() {  # set_jar <env-name> <jar-path>
  local env_file="$CONF_DIR/$1.env"
  if sudo grep -q '^PANEL_JAR=' "$env_file"; then
    sudo sed -i "s|^PANEL_JAR=.*|PANEL_JAR=$2|" "$env_file"
  else
    echo "PANEL_JAR=$2" | sudo tee -a "$env_file" >/dev/null
  fi
}

echo "==> 1/7 落盘 $RELEASE_DIR"
sudo install -d -o panel -g panel -m 0755 "$RELEASE_DIR"
sudo install -o panel -g panel -m 0640 "$JAR" "$RELEASE_DIR/panel-backend.jar"

echo "==> 2/7 迁移前备份数据库"
"$SCRIPT_DIR/backup.sh" db-only

echo "==> 3/7 判定当前颜色"
ACTIVE_PORT="$(current_port)"
ACTIVE_COLOR="$(color_of_port "$ACTIVE_PORT")"
IDLE_COLOR=$([[ "$ACTIVE_COLOR" == "blue" ]] && echo green || echo blue)
IDLE_PORT="$(port_of_color "$IDLE_COLOR")"
echo "    active=${ACTIVE_COLOR}:${ACTIVE_PORT}  idle=${IDLE_COLOR}:${IDLE_PORT}"

echo "==> 4/7 在 $IDLE_COLOR 启动 $VERSION（Flyway 在此执行迁移）"
set_jar "$IDLE_COLOR" "$RELEASE_DIR/panel-backend.jar"
sudo systemctl stop "panel-backend@${IDLE_COLOR}" 2>/dev/null || true
sudo systemctl start "panel-backend@${IDLE_COLOR}"

if ! wait_health "$IDLE_PORT" 240; then
  echo "!! 240s 未就绪：journalctl -u panel-backend@${IDLE_COLOR} -n 200" >&2
  sudo systemctl stop "panel-backend@${IDLE_COLOR}" || true
  echo "   流量未切换，线上仍是 $ACTIVE_COLOR" >&2
  exit 1
fi

echo "==> 5/7 直连 $IDLE_PORT 冒烟"
if ! BASE="http://127.0.0.1:${IDLE_PORT}" "$SCRIPT_DIR/smoke.sh"; then
  echo "!! 冒烟失败，未切流；$IDLE_COLOR 保留在 $IDLE_PORT 供排查" >&2
  exit 1
fi

echo "==> 6/7 切流 $ACTIVE_COLOR -> $IDLE_COLOR"
"$SCRIPT_DIR/switch-upstream.sh" "$IDLE_COLOR"

echo "==> 7/7 观察 30s，然后停掉旧颜色"
sleep 30
sudo systemctl stop "panel-backend@${ACTIVE_COLOR}" || true
sudo ln -sfn "$RELEASE_DIR" "$PANEL_HOME/current"

echo "发布完成：$VERSION  active=${IDLE_COLOR}:${IDLE_PORT}"
echo "回滚：$SCRIPT_DIR/rollback.sh $ACTIVE_COLOR"
