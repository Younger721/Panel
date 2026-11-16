#!/usr/bin/env bash
# 备份：库 + 对象存储。默认全量；传 db-only 只备库（发布前调用）。
# 用法：./backup.sh [db-only]
# cron：10 3 * * * /opt/panel/deploy/scripts/backup.sh >> /opt/panel/logs/backup.log 2>&1
set -euo pipefail

MODE="${1:-all}"
BACKUP_DIR="${BACKUP_DIR:-/opt/panel/backup}"
DATA_DIR="${PANEL_OBJECT_STORE_LOCAL_DIR:-/opt/panel/data/knowledge}"
CONF="${CONF:-/etc/panel/panel.env}"

# panel.env 必须是 shell 可解析的 KEY=VALUE（含空格的值要加引号）
set -a
# shellcheck disable=SC1090
source "$CONF"
set +a

: "${MYSQL_HOST:?}" "${MYSQL_PORT:=3306}" "${MYSQL_DATABASE:?}" "${MYSQL_USER:?}" "${MYSQL_PASSWORD:?}"
mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
DAY="$(date +%F)"

CNF="$(mktemp)"
chmod 600 "$CNF"
trap 'rm -f "$CNF"' EXIT
printf '[client]\nhost=%s\nport=%s\nuser=%s\npassword=%s\n' \
  "$MYSQL_HOST" "$MYSQL_PORT" "$MYSQL_USER" "$MYSQL_PASSWORD" > "$CNF"

echo "[$(date -Is)] db backup start"
OUT="$BACKUP_DIR/db-$STAMP.sql.gz"
mysqldump --defaults-extra-file="$CNF" \
  --single-transaction --quick --triggers \
  --default-character-set=utf8mb4 \
  "$MYSQL_DATABASE" | gzip -6 > "$OUT"

# 基本完整性校验：gzip 能解、能看到 CREATE TABLE
gzip -t "$OUT"
if ! gzip -dc "$OUT" | head -n 400 | grep -q 'CREATE TABLE'; then
  echo "!! 备份疑似损坏：$OUT" >&2
  exit 1
fi
SIZE="$(du -h "$OUT" | cut -f1)"
echo "[$(date -Is)] db backup ok  $OUT ($SIZE)"

if [[ "$MODE" != "db-only" ]]; then
  echo "[$(date -Is)] objects backup start"
  TAR="$BACKUP_DIR/objects-$DAY.tgz"
  tar -czf "$TAR" -C "$(dirname "$DATA_DIR")" "$(basename "$DATA_DIR")"
  echo "[$(date -Is)] objects backup ok  $TAR ($(du -h "$TAR" | cut -f1))"
fi

# 保留：库 14 天，对象 7 天（同一批小时级备份也按天收敛）
find "$BACKUP_DIR" -name 'db-*.sql.gz' -mtime +14 -delete
find "$BACKUP_DIR" -name 'objects-*.tgz' -mtime +7 -delete

# 异地（可选）：COS / rsync / rclone，二选一打开
# rclone copy "$BACKUP_DIR" cos:panel-backup --include 'db-*.sql.gz'
echo "[$(date -Is)] done"
