#Requires -Version 5.1
<#
Disable test-only WS commands for a game instance (removes marker file).
Usage:
  powershell -ExecutionPolicy Bypass -File scripts\disable-test-commands.ps1 -GameDir "E:\PCL\.minecraft\versions\1.20.1-Forge_Get_Test"
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$GameDir
)
$flag = Join-Path $GameDir 'config\captureapi\test_commands.enabled'
if (Test-Path -LiteralPath $flag) {
    Remove-Item -LiteralPath $flag -Force
    Write-Host "[TESTGATE/DISABLE] removed: $flag"
} else {
    Write-Host "[TESTGATE/DISABLE] not present: $flag"
}
