#Requires -Version 5.1
<#
Install the optional CaptureAPI server component jar into a dedicated server mods folder.
Read-only source; only creates the target dir and copies the jar.

Usage:
  powershell -ExecutionPolicy Bypass -File scripts\install-server-component.ps1 -ServerModsDir "D:\server\mods"
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$ServerModsDir,
    [string]$Jar = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $Jar) { $Jar = Join-Path $root 'build\libs\captureapi-0.5.0-server.jar' }
if (-not (Test-Path -LiteralPath $Jar)) {
    throw "服务端组件 jar 不存在：$Jar（先运行 scripts\build.ps1）"
}
$source = (Resolve-Path -LiteralPath $Jar).Path
$targetDir = [IO.Path]::GetFullPath($ServerModsDir)
if (-not (Test-Path -LiteralPath $targetDir)) {
    New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
}
$target = Join-Path $targetDir (Split-Path -Leaf $source)
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $source).Hash
Write-Host "[SERVER/STEP] 源: $source"
Write-Host "[SERVER/STEP] 目标目录: $targetDir"
Copy-Item -LiteralPath $source -Destination $target -Force
$targetHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash
if ($hash -ne $targetHash) { throw '复制后 SHA256 不一致' }
Write-Host "[SERVER/PASS] 已安装: $target"
Write-Host "[SERVER/INFO] 大小: $((Get-Item -LiteralPath $target).Length) bytes；SHA256: $hash"
Write-Host '[SERVER/INFO] 只放服务端组件 jar；客户端只放 captureapi-0.5.0.jar。详见 docs\专用服部署指南.md'
