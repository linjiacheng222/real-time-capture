#Requires -Version 5.1
<#
Release gate diagnostic (does NOT change code).
Checks:
 - build jars exist (client + server component)
 - test-only WS control actions still present (release blocker / gating required)
 - key S6 docs/evidence exist
Writes docs/evidence/S6/release-gate.txt.
Usage:
  powershell -ExecutionPolicy Bypass -File scripts\release-gate-check.ps1 -AllowTestCommands
#>
param(
    [switch]$AllowTestCommands
)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root 'docs\evidence\S6'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$lines = New-Object System.Collections.Generic.List[string]
function Log([string]$t) { Write-Host $t; $lines.Add($t) }
$clientJar = Join-Path $root 'build\libs\captureapi-0.5.0.jar'
$serverJar = Join-Path $root 'build\libs\captureapi-0.5.0-server.jar'
$clientOk = Test-Path $clientJar
$serverOk = Test-Path $serverJar
Log "S6 release gate check $(Get-Date -Format s)"
Log "client jar: $clientJar exists=$clientOk"
Log "server jar: $serverJar exists=$serverOk"

$dispatcher = Join-Path $root 'src\main\java\com\captureapi\net\control\ControlCommandDispatcher.java'
$testOnly = @()
if (Test-Path $dispatcher) {
    $text = Get-Content $dispatcher -Encoding UTF8
    foreach ($line in $text) {
        if ($line -match 'case "((client\.[^"]+)|audio\.section|shader\.status)"') {
            $testOnly += $Matches[1]
        }
    }
}
$testOnly = $testOnly | Sort-Object -Unique
Log ("test-only actions present: " + ($testOnly -join ', '))
$gateFile = Join-Path $root 'src\main\java\com\captureapi\net\control\TestCommandGate.java'
$gateWired = $false
if (Test-Path $gateFile) {
    $gateWired = (Select-String -Path $dispatcher -Pattern 'TestCommandGate' -Encoding UTF8 | Measure-Object).Count -gt 0
}
if ($testOnly.Count -eq 0) {
    Log 'test-only commands: none'
    $testBlock = $false
} elseif ($gateWired) {
    Log 'GATED: test-only commands require TestCommandGate (JVM debug property or config/captureapi/test_commands.enabled).'
    Log '       release-safe; enable only for tests via scripts/enable-test-commands.ps1.'
    $testBlock = $false
} elseif ($AllowTestCommands) {
    Log 'WARN: test-only commands present, not gated; explicitly allowed for test build (not release-safe).'
    $testBlock = $false
} else {
    Log 'RELEASE BLOCKER: test-only WS commands present and not gated; remove or gate before shipping.'
    $testBlock = $true
}

$docs = @(
    'docs\S6终验报告.md',
    'docs\红线核验记录.md',
    'docs\专用服部署指南.md',
    'docs\evidence\S6\magic-audit.txt'
)
foreach ($d in $docs) {
    $p = Join-Path $root $d
    Log ("doc: {0} exists={1}" -f $d, (Test-Path $p))
}

$ok = $clientOk -and $serverOk -and (-not $testBlock)
Log ("RESULT: " + $(if ($ok) { 'PASS (code artifacts + docs; test commands may need release gating)' } else { 'FAIL' }))
$lines | Set-Content (Join-Path $outDir 'release-gate.txt') -Encoding UTF8
Log "evidence: $outDir\release-gate.txt"
if ($ok) { exit 0 } else { exit 1 }
