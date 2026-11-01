#Requires -Version 5.1
<#
Build a local release folder + zip:
  release/0.5.0/captureapi-0.5.0.jar
  release/0.5.0/captureapi-0.5.0-server.jar
  release/0.5.0/SHA256SUMS.txt
  release/0.5.0/RELEASE-README.md
  release/0.5.0/docs/*.md
  release/0.5.0/scripts/(test gate/install helpers)
Then zip to release/captureapi-0.5.0-release.zip.
Does NOT change source/build outputs.
#>
param(
    [string]$OutputDir = ''
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$releaseRoot = Join-Path $root 'release'
if (-not $OutputDir) { $OutputDir = Join-Path $releaseRoot '0.5.0' }
$out = [IO.Path]::GetFullPath($OutputDir)
if (-not $out.StartsWith([IO.Path]::GetFullPath($releaseRoot), [StringComparison]::OrdinalIgnoreCase)) {
    throw "输出目录必须在 $releaseRoot 下：$out"
}
$clientJar = Join-Path $root 'build\libs\captureapi-0.5.0.jar'
$serverJar = Join-Path $root 'build\libs\captureapi-0.5.0-server.jar'
foreach ($jar in @($clientJar, $serverJar)) {
    if (-not (Test-Path -LiteralPath $jar)) { throw "缺少构建产物：$jar（先运行 scripts\build.ps1）" }
}
if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $out 'docs') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $out 'scripts') | Out-Null

Copy-Item $clientJar $out
Copy-Item $serverJar $out
foreach ($d in @('S6终验报告.md', '专用服部署指南.md', '最终交付清单.md')) {
    $p = Join-Path $root "docs\$d"
    if (Test-Path $p) { Copy-Item $p (Join-Path $out 'docs') }
}
foreach ($s in @('build.ps1', 'install-to-instance.ps1', 'install-server-component.ps1', 'enable-test-commands.ps1', 'disable-test-commands.ps1', 'release-gate-check.ps1', 's5-user-long-run.ps1')) {
    $p = Join-Path $root "scripts\$s"
    if (Test-Path $p) { Copy-Item $p (Join-Path $out 'scripts') }
}
$readme = @'
# CaptureAPI v0.5.0 发布包（本地测试候选）

> 状态：**S6 条件放行候选**。用于用户验收，不是最终全面验收版本。

## 内容
- `captureapi-0.5.0.jar`：客户端模组（PCL 实例安装）
- `captureapi-0.5.0-server.jar`：可选专用服组件（服务端 mods）
- `docs/`：S6 终验报告、专用服部署指南、最终交付清单
- `scripts/`：安装、测试指令门控、发布门禁自检脚本
- `SHA256SUMS.txt`

## 快速验证
1. 客户端：客户端 jar 放 PCL 实例 mods；
2. 测试指令门控：PCL 实例运行
   `powershell -ExecutionPolicy Bypass -File scripts\enable-test-commands.ps1 -GameDir "PCL实例目录"`
   长跑一键脚本：`scripts\s5-user-long-run.ps1 -Hours 4`
   （正式发布不会预置该标记；测试后请 disable）
3. 专用服：`captureapi-0.5.0-server.jar` 放服务端 mods，按 `docs/专用服部署指南.md` 验证；
4. 发布门禁：`scripts/build.ps1` 24/24；`scripts/release-gate-check.ps1` PASS(GATED)。

## 仍待用户
- S2.1/S2.4 听辨；
- ≥4h 真实游玩长跑；
- Oculus + shaderpack 实测；
- GitHub 推送裁决。
'@
$readme | Set-Content (Join-Path $out 'RELEASE-README.md') -Encoding UTF8

$hashLines = Get-ChildItem -LiteralPath $out -Filter '*.jar' | ForEach-Object {
    $h = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash
    "{0}  {1}" -f $h.ToLowerInvariant(), $_.Name
}
$hashLines | Set-Content (Join-Path $out 'SHA256SUMS.txt') -Encoding UTF8

$zip = Join-Path $releaseRoot 'captureapi-0.5.0-release.zip'
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
Compress-Archive -Path (Join-Path $out '*') -DestinationPath $zip -CompressionLevel Optimal
Write-Host "[RELEASE/PASS] folder: $out"
Write-Host "[RELEASE/PASS] zip: $zip"
Get-Content (Join-Path $out 'SHA256SUMS.txt') | ForEach-Object { Write-Host "  $_" }
Get-Item $zip | Select-Object FullName, Length | Format-List | Out-String | Write-Host
