#!/usr/bin/env bash
# 发布冒烟：只打只读/幂等接口，不做写操作（避免污染生产数据）。
# 用法：BASE=http://127.0.0.1:8081 SMOKE_USER=... SMOKE_PASS=... ./smoke.sh
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8080}"
SMOKE_USER="${SMOKE_USER:-}"
SMOKE_PASS="${SMOKE_PASS:-}"

fail() { echo "SMOKE FAIL: $*" >&2; exit 1; }

echo "-- health"
curl -fsS --max-time 5 "$BASE/actuator/health" | grep -q '"status":"UP"' || fail "actuator health"
echo "   ok"

echo "-- api 可达（未登录应 401，不是 404/502）"
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$BASE/api/v1/kb" || true)
[[ "$code" == "401" || "$code" == "403" ]] || fail "GET /api/v1/kb 期望 401/403，实际 $code"
echo "   ok ($code)"

if [[ -n "$SMOKE_USER" && -n "$SMOKE_PASS" ]]; then
  echo "-- 登录"
  body=$(curl -fsS --max-time 10 -H 'Content-Type: application/json' \
    -d "{\"username\":\"$SMOKE_USER\",\"password\":\"$SMOKE_PASS\"}" \
    "$BASE/api/v1/auth/login") || fail "login request"
  token=$(printf '%s' "$body" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
  [[ -n "$token" ]] || fail "login no accessToken: $body"
  echo "   ok"

  for path in /api/v1/interview/resumes /api/v1/kb /api/v1/interview/sessions; do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
      -H "Authorization: Bearer $token" "$BASE$path" || true)
    [[ "$code" == "200" ]] || fail "GET $path 期望 200，实际 $code"
    echo "   ok $path"
  done
else
  echo "-- 跳过登录态冒烟（未提供 SMOKE_USER/SMOKE_PASS）"
fi

echo "SMOKE OK ($BASE)"
