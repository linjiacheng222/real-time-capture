#Requires -Version 5.1
<#
One-command user long-run: create camera at player pose, then sample health for N hours.
User must be in a world with the game running; keep this terminal open.

Usage:
  powershell -ExecutionPolicy Bypass -File scripts\s5-user-long-run.ps1
  powershell -ExecutionPolicy Bypass -File scripts\s5-user-long-run.ps1 -Hours 4
  # quick smoke: -DurationSeconds 30
#>
param(
    [double]$Hours = 4,
    [int]$DurationSeconds = 0,
    [string]$WsUrl = 'ws://127.0.0.1:25581/'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if ($DurationSeconds -le 0) { $DurationSeconds = [Math]::Max(60, [int]($Hours * 3600)) }
Write-Host "=== CaptureAPI 长跑（$DurationSeconds 秒）==="
Write-Host '请确认已进入世界并戴好耳机；按 Ctrl+C 可提前结束。'
Write-Host '1) 创建相机...'
& (Join-Path $root 'scripts\s5-long-run-start.ps1') -WsUrl $WsUrl
if ($LASTEXITCODE -ne 0) { throw "相机创建失败（exit=$LASTEXITCODE）" }
Write-Host '2) 开始健康采样；可正常游玩/挂机。'
& (Join-Path $root 'scripts\long-run-health-log.ps1') -DurationSeconds $DurationSeconds
Write-Host '完成：请把 docs/evidence/S5/long-run/health-*.csv 与体感反馈给开发端。'
