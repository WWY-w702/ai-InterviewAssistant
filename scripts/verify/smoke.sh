#!/usr/bin/env bash
# 冒烟测试：后端跑起来之后执行，验证认证边界与核心链路。
#
#   ./scripts/start-backend.sh &
#   ./scripts/verify/smoke.sh
#
# 只用一个固定账号 smoke_probe，重复执行不会堆积垃圾用户。
set -uo pipefail

BASE="${BASE_URL:-http://localhost:8002}"
USER_NAME="smoke_probe"
USER_PASS="Probe123456"

pass=0
fail=0

check() { # 名称 期望 实际
    if [[ "$2" == "$3" ]]; then
        printf '  ✅ %s (期望 %s，实际 %s)\n' "$1" "$2" "$3"
        pass=$((pass + 1))
    else
        printf '  ❌ %s (期望 %s，实际 %s)\n' "$1" "$2" "$3"
        fail=$((fail + 1))
    fi
}

code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

pick_token() { printf '%s' "$1" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p'; }

echo "冒烟测试 → $BASE"

if ! curl -s -o /dev/null --max-time 5 "$BASE/"; then
    echo "❌ 后端未启动或不可达，先执行 ./scripts/start-backend.sh" >&2
    exit 1
fi

echo
echo "[1] 未认证访问受保护接口应被拒"
check "GET /api/interviews/reports 无 token" 401 "$(code "$BASE/api/interviews/reports")"
check "GET /api/profile/overview 无 token" 401 "$(code "$BASE/api/profile/overview")"
check "GET /api/pdf/anything 无 token" 401 "$(code "$BASE/api/pdf/anything")"

echo
echo "[2] 注册与登录"
reg=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASS\",\"displayName\":\"smoke\"}")
token=$(pick_token "$reg")
if [[ -z "$token" ]]; then
    login=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
        -d "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASS\"}")
    token=$(pick_token "$login")
    if [[ -n "$token" ]]; then
        printf '  ✅ 账号已存在，改用登录\n'
        pass=$((pass + 1))
    else
        printf '  ❌ 注册与登录均失败\n      注册: %s\n      登录: %s\n' "$reg" "$login"
        fail=$((fail + 1))
    fi
else
    printf '  ✅ 注册成功并返回 token\n'
    pass=$((pass + 1))
fi

if [[ -z "$token" ]]; then
    echo
    echo "无法取得 token，后续用例跳过。通过 $pass 项，失败 $fail 项"
    exit 1
fi

echo
echo "[3] 携带 token 的访问"
check "GET /api/health" 200 "$(code -H "Authorization: Bearer $token" "$BASE/api/health")"
check "GET /api/profile/overview" 200 "$(code -H "Authorization: Bearer $token" "$BASE/api/profile/overview")"
check "GET /api/interviews/reports" 200 "$(code -H "Authorization: Bearer $token" "$BASE/api/interviews/reports")"
check "GET /api/agent/sessions" 200 "$(code -H "Authorization: Bearer $token" "$BASE/api/agent/sessions")"

echo
printf '通过 %d 项，失败 %d 项\n' "$pass" "$fail"
[[ $fail -eq 0 ]]
