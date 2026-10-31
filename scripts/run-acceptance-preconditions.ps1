param([switch]$SkipVerifyEnv)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'E:\Tools\jdk17' }
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = 'E:\Tools\gradle-home' }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outDir = Join-Path $root 'docs\证据\M7\预检'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$log = Join-Path $outDir "precheck-$stamp.txt"
$results = New-Object System.Collections.Generic.List[object]
function Add-Result([string]$name, [bool]$ok, [string]$detail) {
    $results.Add([pscustomobject]@{ Name = $name; Status = $(if ($ok) { 'PASS' } else { 'FAIL' }); Detail = $detail })
    $line = "[$($(if ($ok) { 'PASS' } else { 'FAIL' }))] $name — $detail"
    Write-Host $line
    Add-Content -Path $log -Value $line -Encoding UTF8
}
Write-Host "=== M7 acceptance precheck $stamp ==="
Add-Content -Path $log -Value "=== M7 acceptance precheck $stamp ===" -Encoding UTF8
$buildOut = & (Join-Path $root 'scripts\build.ps1') -SkipClean 2>&1
$buildExit = $LASTEXITCODE
$buildJar = Join-Path $root 'build\libs\captureapi-0.5.0.jar'
$buildOk = ($buildExit -eq 0) -and (Test-Path $buildJar)
$buildDetail = "exit=$buildExit; jar=$(if (Test-Path $buildJar) { (Get-Item $buildJar).Length.ToString() + ' bytes' } else { 'missing' })"
Add-Result 'build.ps1 build/artifact' $buildOk $buildDetail
if (-not $SkipVerifyEnv) {
    $verifyOut = & (Join-Path $root 'scripts\verify-env.ps1') 2>&1
    $verifyExit = $LASTEXITCODE
    $verifyLine = ($verifyOut | Select-String -Pattern 'PASS=\d+ FAIL=\d+ WARN=\d+' | Select-Object -Last 1).Line
    $verifyDetail = if ($verifyLine) { $verifyLine } else { "exit=$verifyExit" }
    Add-Result 'verify-env.ps1' ($verifyExit -eq 0) $verifyDetail
}
$pyOk = $false
$pyDetail = ''
if (Get-Command python -ErrorAction SilentlyContinue) {
    $pyOut = & python (Join-Path $root 'scripts\verify-config-consistency.py') 2>&1
    $pyOk = ($LASTEXITCODE -eq 0)
    $pyDetail = ($pyOut | Select-Object -Last 1)
    Add-Result 'appendix-B vs CaptureConfig names' $pyOk $pyDetail
} else {
    Add-Result 'appendix-B vs CaptureConfig names' $false 'python missing'
}
$busy = Get-NetTCPConnection -LocalPort 25580,25581 -State Listen -ErrorAction SilentlyContinue
Add-Result 'ports 25580/25581 free' ($null -eq $busy) $(if ($null -eq $busy) { 'free' } else { 'busy' })
$required = @(
    'docs\验收测试计划.md','docs\验收报告模板.md','docs\音视频时间戳与对齐协议.md',
    'docs\examples\ws_aligned_client.py','docs\控制指令schema.md','docs\网络推流协议.md',
    'docs\参数治理说明.md','scripts\verify-config-consistency.py'
)
$missing = @()
foreach ($rel in $required) { if (-not (Test-Path (Join-Path $root $rel))) { $missing += $rel } }
Add-Result 'M7 key deliverables present' ($missing.Count -eq 0) $(if ($missing.Count -eq 0) { "$($required.Count) files" } else { "missing: $($missing -join ', ')" })
Add-Result 'evidence dir writable' (Test-Path $outDir -PathType Container) $outDir
$passCount = ($results | Where-Object { $_.Status -eq 'PASS' }).Count
$summary = "precheck: PASS=$passCount FAIL=$($results.Count - $passCount) total=$($results.Count)"
Write-Host $summary
Add-Content -Path $log -Value $summary -Encoding UTF8
$results | Format-Table -AutoSize | Out-String | Add-Content -Path $log -Encoding UTF8
Write-Host "report: $log"
exit $(if ($passCount -eq $results.Count) { 0 } else { 1 })