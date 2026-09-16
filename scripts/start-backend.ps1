$projectRoot = Split-Path -Parent $PSScriptRoot

# 加载 .env（Spring Boot 不会自动读取，缺了它会退回 application.yml 的占位默认值）
$envFile = Join-Path $projectRoot ".env"
if (Test-Path $envFile) {
    Get-Content $envFile -Encoding UTF8 | Where-Object { $_ -match '^\s*[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
        $name, $value = $_ -split '=', 2
        [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), 'Process')
    }
    Write-Host "已加载 $envFile"
} else {
    Write-Warning "未找到 $envFile，将使用 application.yml 的占位默认值（数据库连接会失败）"
}

$logDir = Join-Path $projectRoot "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$output = Join-Path $logDir "backend.log"
$errorLog = Join-Path $logDir "backend-error.log"

Set-Location (Join-Path $projectRoot "backend")
mvn spring-boot:run *> $output 2> $errorLog
