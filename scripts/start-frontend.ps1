$projectRoot = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $projectRoot "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$output = Join-Path $logDir "frontend.log"
$errorLog = Join-Path $logDir "frontend-error.log"

Set-Location (Join-Path $projectRoot "frontend")
npm run dev *> $output 2> $errorLog
