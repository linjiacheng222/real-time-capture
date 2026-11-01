#Requires -Version 5.1
<#
Enable test-only WS commands for a game instance (creates marker file).
Release builds have these commands gated by default; run this only for tests.
Usage:
  powershell -ExecutionPolicy Bypass -File scripts\enable-test-commands.ps1 -GameDir "E:\PCL\.minecraft\versions\1.20.1-Forge_Get_Test"
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$GameDir
)
$flagDir = Join-Path $GameDir 'config\captureapi'
$flag = Join-Path $flagDir 'test_commands.enabled'
New-Item -ItemType Directory -Force -Path $flagDir | Out-Null
"captureapi test command gate enabled by script at $(Get-Date -Format s)" | Set-Content -Path $flag -Encoding UTF8
Write-Host "[TESTGATE/ENABLE] $flag"
Write-Host '[TESTGATE/INFO] 仅测试用；发布前运行 disable-test-commands.ps1 删除标记。'
