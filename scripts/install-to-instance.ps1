#Requires -Version 5.1
<#
.SYNOPSIS
    捕获接口（captureapi）安装脚本：把构建产物 jar 复制到 PCL 游戏实例的 mods 目录（支持 -DryRun / -Remove）。

.DESCRIPTION
    默认行为：把 build\libs\<modId>-<modVersion>.jar 复制到
      E:\PCL\.minecraft\versions\1.20.1-Forge_Get_Test\mods\
    并在复制前校验 jar 内 mods.toml 的 modId 与本工程一致，复制后校验 SHA256 一致。

    -DryRun  只打印将要执行的操作，不做任何修改；
    -Remove  删除实例 mods 目录中的 captureapi*.jar（用于清理测试安装或旧版本）。

    注意：本脚本不触碰实例 mods 目录中的其它模组（如 embeddium / modernfix / oculus）。

.PARAMETER JarPath
    指定要安装的 jar 路径（默认 build\libs\<modId>-<modVersion>.jar；缺失时回退到 build\libs 下最新的 <modId>-*.jar）。

.PARAMETER InstancePath
    PCL 游戏实例的 mods 目录（默认 E:\PCL\.minecraft\versions\1.20.1-Forge_Get_Test\mods）。

.PARAMETER DryRun
    只打印计划，不复制/删除。

.PARAMETER Remove
    删除实例 mods 目录中的 captureapi*.jar。

.PARAMETER Force
    实例 mods 目录不存在时自动创建（默认报错，避免写错路径）。

.EXAMPLE
    .\scripts\install-to-instance.ps1 -DryRun
    .\scripts\install-to-instance.ps1
    .\scripts\install-to-instance.ps1 -Remove
#>
[CmdletBinding()]
param(
    [string]$JarPath = '',
    [string]$InstancePath = 'E:\PCL\.minecraft\versions\1.20.1-Forge_Get_Test\mods',
    [switch]$DryRun,
    [switch]$Remove,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$ScriptDir = $PSScriptRoot
$ProjectRoot = Split-Path -Parent $ScriptDir
if (-not (Test-Path -LiteralPath (Join-Path $ProjectRoot 'gradlew.bat'))) {
    throw "无法从脚本目录解析工程根目录（期望包含 gradlew.bat）：$ProjectRoot"
}

function Write-InstallLog {
    param([string]$Message, [ValidateSet('INFO', 'PASS', 'WARN', 'FAIL', 'STEP', 'DRYRUN')][string]$Level = 'INFO')
    $color = @{ INFO = 'Gray'; PASS = 'Green'; WARN = 'Yellow'; FAIL = 'Red'; STEP = 'Cyan'; DRYRUN = 'Magenta' }[$Level]
    Write-Host ("[INSTALL/{0}] {1}" -f $Level, $Message) -ForegroundColor $color
}

function Get-GradleProperty {
    param([string]$Name, [string]$PropertiesPath)
    if (-not (Test-Path -LiteralPath $PropertiesPath)) { return $null }
    foreach ($line in [System.IO.File]::ReadAllLines($PropertiesPath)) {
        if ($line -match ('^\s*' + [regex]::Escape($Name) + '\s*=\s*(.*)$')) { return $Matches[1].Trim() }
    }
    return $null
}

function Get-JarEntryText {
    param([string]$JarFile, [string]$EntryName)
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarFile)
    try {
        $entry = $zip.GetEntry($EntryName)
        if ($null -eq $entry) { return $null }
        $stream = $entry.Open()
        try {
            $reader = New-Object System.IO.StreamReader($stream, (New-Object System.Text.UTF8Encoding($false, $true)))
            return $reader.ReadToEnd()
        }
        finally {
            $reader.Dispose()
            $stream.Dispose()
        }
    }
    finally {
        $zip.Dispose()
    }
}

# ================= 主流程 =================
Write-Host ''
Write-Host '=== captureapi 安装到 PCL 游戏实例 ===' -ForegroundColor Cyan
$gradleProps = Join-Path $ProjectRoot 'gradle.properties'
$modId = Get-GradleProperty -Name 'modId' -PropertiesPath $gradleProps
$modVersion = Get-GradleProperty -Name 'modVersion' -PropertiesPath $gradleProps
if (-not $modId) { $modId = 'captureapi' }
if (-not $modVersion) { $modVersion = '0.5.0' }
Write-InstallLog ("工程根目录: " + $ProjectRoot) STEP
Write-InstallLog ("实例 mods 目录: " + $InstancePath) STEP
if ($DryRun) { Write-InstallLog '模式: DryRun（只打印计划，不做修改）' DRYRUN }

# 定位源 jar
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $JarPath = Join-Path $ProjectRoot ('build\libs\' + $modId + '-' + $modVersion + '.jar')
    if (-not (Test-Path -LiteralPath $JarPath)) {
        $libsDir = Join-Path $ProjectRoot 'build\libs'
        $candidates = @()
        if (Test-Path -LiteralPath $libsDir) {
            $candidates = @(Get-ChildItem -LiteralPath $libsDir -Filter ($modId + '-*.jar') -File -ErrorAction SilentlyContinue |
                    Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
                    Sort-Object LastWriteTime -Descending)
        }
        if ($candidates.Count -eq 0) {
            throw ('未找到构建产物：' + $JarPath + '（请先运行 scripts\build.ps1 构建）')
        }
        $JarPath = $candidates[0].FullName
        Write-InstallLog ("默认产物不存在，回退使用最新 jar: " + $JarPath) WARN
    }
}
if (-not (Test-Path -LiteralPath $JarPath)) {
    throw ('源 jar 不存在：' + $JarPath + '（请先运行 scripts\build.ps1）')
}
$sourceJar = (Get-Item -LiteralPath $JarPath)
if ($sourceJar.Length -le 0) { throw ('源 jar 为空文件：' + $JarPath) }
if ($sourceJar.Name -notmatch ('^' + [regex]::Escape($modId) + '-')) {
    throw ('源 jar 文件名不符合 ' + $modId + '-*.jar 约定：' + $sourceJar.Name)
}

# 校验 jar 内 modId（防止把无关 jar 装进实例）
$tomlText = $null
try { $tomlText = Get-JarEntryText -JarFile $sourceJar.FullName -EntryName 'META-INF/mods.toml' }
catch { $tomlText = $null }
if (-not $tomlText -or ($tomlText -notmatch ('modId\s*=\s*"' + [regex]::Escape($modId) + '"'))) {
    throw ('jar 内 META-INF/mods.toml 未声明 modId=' + $modId + '，拒绝安装：' + $sourceJar.FullName)
}
$sourceHash = (Get-FileHash -LiteralPath $sourceJar.FullName -Algorithm SHA256).Hash
Write-InstallLog ("源 jar: " + $sourceJar.FullName) PASS
Write-InstallLog ("大小: " + $sourceJar.Length + " bytes；SHA256: " + $sourceHash) INFO

# 目标目录校验
if (-not (Test-Path -LiteralPath $InstancePath)) {
    if ($Remove) {
        Write-InstallLog ("实例 mods 目录不存在，无需删除: " + $InstancePath) INFO
        exit 0
    }
    if ($DryRun) {
        Write-InstallLog ("[DryRun] 实例 mods 目录不存在，实际执行时将报错（可用 -Force 自动创建）: " + $InstancePath) DRYRUN
    }
    elseif ($Force) {
        New-Item -ItemType Directory -Path $InstancePath -Force | Out-Null
        Write-InstallLog ("已创建实例 mods 目录（-Force）: " + $InstancePath) WARN
    }
    else {
        throw ('实例 mods 目录不存在：' + $InstancePath + '（请确认 PCL 实例路径，或用 -Force 自动创建）')
    }
}

$existing = @(Get-ChildItem -LiteralPath $InstancePath -Filter ($modId + '*.jar') -File -ErrorAction SilentlyContinue)

# ---- Remove 模式 ----
if ($Remove) {
    if ($existing.Count -eq 0) {
        Write-InstallLog ('实例 mods 中未发现 ' + $modId + '*.jar，无需删除。') PASS
        exit 0
    }
    foreach ($jar in $existing) {
        if ($DryRun) {
            Write-InstallLog ('[DryRun] 将删除: ' + $jar.FullName) DRYRUN
        }
        else {
            Remove-Item -LiteralPath $jar.FullName -Force
            if (Test-Path -LiteralPath $jar.FullName) { throw ('删除失败: ' + $jar.FullName) }
            Write-InstallLog ('已删除: ' + $jar.FullName) PASS
        }
    }
    $left = @(Get-ChildItem -LiteralPath $InstancePath -Filter ($modId + '*.jar') -File -ErrorAction SilentlyContinue)
    if ($left.Count -gt 0) { throw ('删除后仍残留: ' + (($left | ForEach-Object { $_.Name }) -join ', ')) }
    Write-InstallLog ('删除后确认: 实例 mods 中已无 ' + $modId + '*.jar') PASS
    exit 0
}

# ---- 安装模式 ----
$targetPath = Join-Path $InstancePath $sourceJar.Name
$otherJars = @(Get-ChildItem -LiteralPath $InstancePath -Filter '*.jar' -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch ('^' + [regex]::Escape($modId)) })
Write-InstallLog ("实例 mods 现有 " + $otherJars.Count + " 个其它模组 jar（本脚本不触碰）：" + (($otherJars | ForEach-Object { $_.Name }) -join ', ')) INFO
$oldVersions = @($existing | Where-Object { $_.Name -ne $sourceJar.Name })
if ($oldVersions.Count -gt 0) {
    Write-InstallLog ("检测到旧版本 captureapi jar（建议 -Remove 清理，避免重复加载）：" + (($oldVersions | ForEach-Object { $_.Name }) -join ', ')) WARN
}

if ($DryRun) {
    Write-InstallLog ('[DryRun] 将复制: ' + $sourceJar.FullName) DRYRUN
    Write-InstallLog ('[DryRun]        -> ' + $targetPath) DRYRUN
    Write-InstallLog ('[DryRun] 未做任何修改；去掉 -DryRun 即实际安装。') DRYRUN
    exit 0
}

Copy-Item -LiteralPath $sourceJar.FullName -Destination $targetPath -Force
if (-not (Test-Path -LiteralPath $targetPath)) { throw ('复制失败，目标不存在: ' + $targetPath) }
$targetHash = (Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash
if ($targetHash -ne $sourceHash) { throw ('复制后 SHA256 不一致（源 ' + $sourceHash + ' / 目标 ' + $targetHash + '）') }
$targetItem = Get-Item -LiteralPath $targetPath
Write-InstallLog ('已安装: ' + $targetPath) PASS
Write-InstallLog ('目标大小: ' + $targetItem.Length + ' bytes；SHA256: ' + $targetHash + '（与源一致）') PASS

$installed = @(Get-ChildItem -LiteralPath $InstancePath -Filter ($modId + '*.jar') -File -ErrorAction SilentlyContinue)
if ($installed.Count -ne 1) {
    Write-InstallLog ("实例 mods 中 captureapi jar 数量为 " + $installed.Count + "（预期 1），请检查是否需要 -Remove 清理旧版本。") WARN
}
Write-InstallLog ('安装完成。启动 PCL 实例即可加载 ' + $sourceJar.Name) PASS
exit 0
