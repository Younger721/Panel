#!/usr/bin/env bash
# 回滚：把流量切回指定颜色，并确认它活着。
# 用法：./rollback.sh blue|green
# 注意：只回滚代码。如果新版本已经跑了不兼容的迁移，见 docs/部署与升级-运行手册.md §5。
set -euo pipefail

COLOR="${1:-}"
[[ "$COLOR" == "blue" || "$COLOR" == "green" ]] || {
  echo "usage: $0 blue|green" >&2; exit 2;
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT=$([[ "$COLOR" == "blue" ]] && echo "${BLUE_PORT:-8080}" || echo "${GREEN_PORT:-8081}")

if ! systemctl is-active --quiet "panel-backend@${COLOR}"; then
  echo "==> $COLOR 没在跑，先启动"
  sudo systemctl start "panel-backend@${COLOR}"
  for _ in $(seq 1 60); do
    if curl -fsS --max-time 2 "http://127.0.0.1:${PORT}/actuator/health" >/dev/null 2>&1; then break; fi
    sleep 2
  done
fi

"$SCRIPT_DIR/switch-upstream.sh" "$COLOR"
echo "已回滚到 $COLOR (127.0.0.1:${PORT})"
sudo journalctl -u "panel-backend@${COLOR}" -n 20 --no-pager || true
