#!/usr/bin/env bash
# 本地启动后端。
#
# 为什么需要这个脚本：Spring Boot 不会自动读取 .env，直接用 `mvn spring-boot:run`
# 会退回 application.yml 里的占位默认值（数据库密码、JWT 密钥都是假的）。
set -euo pipefail

projectRoot="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
envFile="$projectRoot/.env"

if [[ -f "$envFile" ]]; then
    # 逐行导出而不是 `source`：.env 里的 JDBC URL 含 & 和 =，
    # 直接 source 会被 shell 当成控制符拆开。
    while IFS='=' read -r key value; do
        export "$key=$value"
    done < <(sed '1s/^\xEF\xBB\xBF//' "$envFile" | tr -d '\r' | grep -E '^[A-Za-z_][A-Za-z0-9_]*=')
    echo "已加载 $envFile"
else
    echo "警告: 未找到 $envFile，将使用 application.yml 的占位默认值（数据库连接会失败）" >&2
fi

logDir="$projectRoot/logs"
mkdir -p "$logDir"
echo "启动后端，日志: $logDir/backend.log"

cd "$projectRoot/backend"
exec mvn spring-boot:run
