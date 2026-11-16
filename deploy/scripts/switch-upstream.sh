#!/usr/bin/env bash
# 蓝绿切流：把 Nginx 的 upstream 指向 blue(8080) 或 green(8081)
# 用法：./switch-upstream.sh blue|green|8080|8081|health
set -euo pipefail

BLUE_PORT=${BLUE_PORT:-8080}
GREEN_PORT=${GREEN_PORT:-8081}
UPSTREAM_FILE=${UPSTREAM_FILE:-/etc/nginx/panel-upstream.conf}

target="${1:-}"

if [[ "$target" == "health" ]]; then
  curl -fsS "http://127.0.0.1:${BLUE_PORT}/actuator/health"  && echo " <- blue"
  curl -fsS "http://127.0.0.1:${GREEN_PORT}/actuator/health" && echo " <- green"
  exit 0
fi

case "$target" in
  blue|8080)  port="$BLUE_PORT"  ;;
  green|8081) port="$GREEN_PORT" ;;
  *)
    echo "usage: $0 blue|green|8080|8081|health" >&2
    exit 2
    ;;
esac


# 切之前必须确认目标实例真的活着，否则等于把全站切到 502
if ! curl -fsS --max-time 3 "http://127.0.0.1:${port}/actuator/health" >/dev/null; then
  echo "refuse to switch: 127.0.0.1:${port} health is not UP" >&2
  exit 1
fi

tmp="$(mktemp)"
printf 'server 127.0.0.1:%s max_fails=2 fail_timeout=10s;\n' "$port" > "$tmp"
sudo install -m 0644 -o root -g root "$tmp" "$UPSTREAM_FILE"
rm -f "$tmp"

sudo nginx -t
sudo systemctl reload nginx

echo "upstream -> 127.0.0.1:${port} ($target) OK"
