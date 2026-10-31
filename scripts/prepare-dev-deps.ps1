#Requires -Version 5.1
<#
.SYNOPSIS
    准备 captureapi 开发环境依赖：把生产（SRG/reobf）模组 jar 重映射为 named/dev 变体并放入 run\mods。

.DESCRIPTION
    背景（2026-10-30 实测结论）：
      dev runClient 使用 Mojmap named 环境；若 run\mods 直接放生产 jar，Mixin 在 early display 阶段会因
      @Shadow 的 SRG 硬名字（如 m_5601_）在 named 目标类中不存在而崩溃：
      InvalidMixinException ... @Shadow method m_5601_ ... was not located ...
      仅加 mixin.env.remapRefMap 两个属性只能重映射 refmap 引用，救不了 @Shadow 成员解析
      （MixinTargetContext.findRemappedMethod 走 environment RemapperChain，不经过 refmap）。

    本脚本用 ForgeGradle 的 fg.deobf 把生产 jar 重映射为 named 变体（SRG→official/Mojmap），
    使 runClient 能正常加载 Embeddium / ModernFix。D-BUILD-1 机制不变（dev 依赖仍是 run/mods 本地 jar），
    只是 jar 形式由生产版换为 dev 变体；原始生产 jar 保存在 run\mods-original\（Forge 不加载该目录）。

    数据来源（按优先级）：
      1) run\mods-original\ 下的原始生产 jar；
      2) run\mods\ 下的原始生产 jar（脚本会自动移入 run\mods-original\）；
      3) -Download 时从 Modrinth 官方 CDN 下载（首次需联网；URL 由 api.modrinth.com 解析，
         并用 API 提供的 SHA1 校验）。
    生成过程：在 build\devmods-gen\ 生成临时 Gradle 工程（不入库、可重复生成）→
      运行 gradlew -p build\devmods-gen exportDevMods → 产物复制到 run\mods\。
    产物命名：embeddium-0.3.31_mapped_official_1.20.1-dev.jar、
              modernfix-5.27.89_mapped_official_1.20.1-dev.jar。
    组件升级时：同步修改本脚本顶部的版本常量，重新运行即可。

    首次生成需要网络（ForgeGradle 插件/Forge 依赖，本机已缓存则可离线）；
    产物落在 gitignore 的 run\ 与 build\ 下，重装环境后重跑本脚本一次即可。

.PARAMETER Download
    本地找不到原始生产 jar 时，从 Modrinth 下载（需联网）。

.PARAMETER Offline
    生成步骤附加 --offline（本机 FG/Forge 缓存齐全时可用）。

.PARAMETER Force
    跳过原始 jar 的版本校验（仅确认 modId 正确）。

.PARAMETER TimeoutSec
    gradle 生成步骤超时秒数（默认 600）。

.EXAMPLE
    .\scripts\prepare-dev-deps.ps1
    .\scripts\prepare-dev-deps.ps1 -Download
    .\scripts\prepare-dev-deps.ps1 -Offline
#>
[CmdletBinding()]
param(
    [switch]$Download,
    [switch]$Offline,
    [switch]$Force,
    [int]$TimeoutSec = 600,
    [string]$JdkHome = ''
)

$ErrorActionPreference = 'Stop'

# ================= 固定路径 =================
$ScriptDir = $PSScriptRoot
$ProjectRoot = Split-Path -Parent $ScriptDir
if (-not (Test-Path -LiteralPath (Join-Path $ProjectRoot 'gradlew.bat'))) {
    throw "无法从脚本目录解析工程根目录（期望包含 gradlew.bat）：$ProjectRoot"
}

# 本机 C/D 盘空间紧张：Gradle 用户目录固定到 E 盘（外部已设置 GRADLE_USER_HOME 时尊重外部值）
if ([string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) {
    $env:GRADLE_USER_HOME = 'E:\Tools\gradle-home'
}
$RunDir = Join-Path $ProjectRoot 'run'
$ModsDir = Join-Path $RunDir 'mods'
$OriginalDir = Join-Path $RunDir 'mods-original'
$GenProjectDir = Join-Path $ProjectRoot 'build\devmods-gen'
$LocalMavenDir = Join-Path $GenProjectDir 'local-maven'
$GenOutputDir = Join-Path $GenProjectDir 'dev-mods-out'
$GenLogPath = Join-Path $ProjectRoot ('build\reports\env\prepare-dev-deps-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
$GradlewBat = Join-Path $ProjectRoot 'gradlew.bat'
$PreferredJdk = 'E:\Tools\jdk17'
$FallbackJdks = @(
    (Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-beta'),
    (Join-Path $env:USERPROFILE '.minecraft\runtime\java-runtime-beta')
)

# ================= 依赖清单（升级组件时改这里） =================
$Deps = @(
    [pscustomobject]@{
        Name           = 'Embeddium'
        ModId          = 'embeddium'
        Version        = '0.3.31+mc1.20.1'
        MavenGroup     = 'dev.local'
        MavenArtifact  = 'embeddium'
        MavenVersion   = '0.3.31'
        OriginalFile   = 'embeddium-0.3.31+mc1.20.1.jar'
        ModrinthSlug   = 'embeddium'
        MixinSmokeClass = 'me/jellysquid/mods/sodium/mixin/features/render/immediate/matrix_stack/VertexConsumerMixin.class'
        MixinSmokeOldName = 'm_5601_'
    },
    [pscustomobject]@{
        Name           = 'ModernFix'
        ModId          = 'modernfix'
        Version        = '5.27.89+mc1.20.1'
        MavenGroup     = 'dev.local'
        MavenArtifact  = 'modernfix'
        MavenVersion   = '5.27.89'
        OriginalFile   = '[现代化修复] modernfix-forge-5.27.89+mc1.20.1.jar'
        ModrinthSlug   = 'modernfix'
        MixinSmokeClass = ''
        MixinSmokeOldName = ''
    }
)

# ================= 通用函数 =================
function Write-PrepLog {
    param([string]$Message, [ValidateSet('INFO', 'PASS', 'WARN', 'FAIL', 'STEP')][string]$Level = 'INFO')
    $color = @{ INFO = 'Gray'; PASS = 'Green'; WARN = 'Yellow'; FAIL = 'Red'; STEP = 'Cyan' }[$Level]
    Write-Host ("[PREP/{0}] {1}" -f $Level, $Message) -ForegroundColor $color
}

function ConvertFrom-BytesAuto {
    param([byte[]]$Bytes)
    if ($null -eq $Bytes -or $Bytes.Length -eq 0) { return '' }
    try {
        $strict = New-Object System.Text.UTF8Encoding($false, $true)
        return $strict.GetString($Bytes).TrimStart([char]0xFEFF)
    }
    catch {
        return [System.Text.Encoding]::Default.GetString($Bytes).TrimStart([char]0xFEFF)
    }
}

function Read-TextSafe {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return '' }
    try {
        $fs = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        try {
            $ms = New-Object System.IO.MemoryStream
            try { $fs.CopyTo($ms); return (ConvertFrom-BytesAuto -Bytes $ms.ToArray()) }
            finally { $ms.Dispose() }
        }
        finally { $fs.Dispose() }
    }
    catch { return '' }
}

function Invoke-NativeCapture {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string]$Arguments = '',
        [int]$TimeoutSec = 120,
        [string]$WorkingDirectory = $ProjectRoot
    )
    $ext = [System.IO.Path]::GetExtension($FilePath).ToLowerInvariant()
    if ($ext -eq '.bat' -or $ext -eq '.cmd') {
        if ([string]::IsNullOrWhiteSpace($Arguments)) { $Arguments = '/c ""' + $FilePath + '""' }
        else { $Arguments = '/c ""' + $FilePath + '" ' + $Arguments + '"' }
        $FilePath = $env:ComSpec
    }
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $FilePath
    $psi.Arguments = $Arguments
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    $outStream = New-Object System.IO.MemoryStream
    $errStream = New-Object System.IO.MemoryStream
    try {
        [void]$proc.Start()
        $outTask = $proc.StandardOutput.BaseStream.CopyToAsync($outStream)
        $errTask = $proc.StandardError.BaseStream.CopyToAsync($errStream)
        $timedOut = $false
        if ($TimeoutSec -gt 0) { if (-not $proc.WaitForExit($TimeoutSec * 1000)) { $timedOut = $true } }
        else { $proc.WaitForExit() }
        if ($timedOut) {
            try { $proc.Kill() } catch { }
            try { $proc.WaitForExit(5000) | Out-Null } catch { }
            throw "命令超时（${TimeoutSec}s）：$FilePath $Arguments"
        }
        try { $outTask.Wait(10000) | Out-Null } catch { }
        try { $errTask.Wait(10000) | Out-Null } catch { }
        $output = (ConvertFrom-BytesAuto -Bytes $outStream.ToArray()) + (ConvertFrom-BytesAuto -Bytes $errStream.ToArray())
        return [pscustomobject]@{ ExitCode = $proc.ExitCode; Output = $output }
    }
    finally {
        try { $proc.Dispose() } catch { }
        try { $outStream.Dispose() } catch { }
        try { $errStream.Dispose() } catch { }
    }
}

function Resolve-Jdk17 {
    $candidates = New-Object System.Collections.ArrayList
    if (-not [string]::IsNullOrWhiteSpace($JdkHome)) { [void]$candidates.Add($JdkHome) }
    [void]$candidates.Add($PreferredJdk)
    foreach ($f in $FallbackJdks) { [void]$candidates.Add($f) }
    foreach ($candidate in $candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        $javaExe = Join-Path $candidate 'bin\java.exe'
        $javacExe = Join-Path $candidate 'bin\javac.exe'
        if (-not (Test-Path -LiteralPath $javaExe) -or -not (Test-Path -LiteralPath $javacExe)) { continue }
        $res = Invoke-NativeCapture -FilePath $javaExe -Arguments '-version' -TimeoutSec 60
        if ($res.ExitCode -ne 0 -or $res.Output -notmatch 'version "17\.') { continue }
        return (Get-Item -LiteralPath $candidate).FullName
    }
    throw '未找到可用 JDK 17（E:\Tools\jdk17 / java-runtime-beta 均不可用）'
}

function Set-JdkEnvironment {
    param([string]$JdkPath)
    $binDir = Join-Path $JdkPath 'bin'
    $env:JAVA_HOME = $JdkPath
    $parts = @()
    if ($env:PATH) { $parts = @($env:PATH -split ';' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) }
    $parts = @($parts | Where-Object { $_.TrimEnd('\') -ne $binDir.TrimEnd('\') })
    $env:PATH = $binDir + ';' + ($parts -join ';')
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
        finally { $reader.Dispose(); $stream.Dispose() }
    }
    finally { $zip.Dispose() }
}

function Get-JarEntryBytes {
    param([string]$JarFile, [string]$EntryName)
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarFile)
    try {
        $entry = $zip.GetEntry($EntryName)
        if ($null -eq $entry) { return $null }
        $ms = New-Object System.IO.MemoryStream
        $stream = $entry.Open()
        try { $stream.CopyTo($ms); return $ms.ToArray() }
        finally { $stream.Dispose(); $ms.Dispose() }
    }
    finally { $zip.Dispose() }
}

function Get-ModrinthJar {
    <# 从 Modrinth 下载指定版本，并用 API 返回的 SHA1 校验。 #>
    param([string]$ProjectSlug, [string]$VersionNumber, [string]$DestinationFile)
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    Write-PrepLog ("查询 Modrinth: " + $ProjectSlug + " " + $VersionNumber) STEP
    $versions = Invoke-RestMethod -Uri ('https://api.modrinth.com/v2/project/' + $ProjectSlug + '/version') -UseBasicParsing -TimeoutSec 60
    $hit = @($versions | Where-Object { $_.version_number -eq $VersionNumber }) | Select-Object -First 1
    if ($null -eq $hit) { throw ('Modrinth 未找到版本 ' + $ProjectSlug + ' ' + $VersionNumber) }
    $file = @($hit.files | Where-Object { $_.primary -eq $true }) | Select-Object -First 1
    if ($null -eq $file) { $file = @($hit.files)[0] }
    Write-PrepLog ("下载: " + $file.url) STEP
    Invoke-WebRequest -Uri $file.url -OutFile $DestinationFile -UseBasicParsing -TimeoutSec 600
    $sha1 = (Get-FileHash -LiteralPath $DestinationFile -Algorithm SHA1).Hash.ToLowerInvariant()
    $expected = ([string]$file.hashes.sha1).ToLowerInvariant()
    if ($expected -and $sha1 -ne $expected) { throw ('SHA1 校验失败：期望 ' + $expected + '，实际 ' + $sha1) }
    Write-PrepLog ("下载并校验完成（SHA1 " + $sha1 + '）') PASS
}

# ================= 主流程 =================
Write-Host ''
Write-Host '=== 准备 dev 依赖（生产 jar → named 变体）===' -ForegroundColor Cyan
Write-PrepLog ("工程根目录: " + $ProjectRoot) STEP
$jdk = Resolve-Jdk17
Set-JdkEnvironment -JdkPath $jdk
Write-PrepLog ("JDK: " + $jdk) PASS

New-Item -ItemType Directory -Path $RunDir -Force | Out-Null
New-Item -ItemType Directory -Path $ModsDir -Force | Out-Null
New-Item -ItemType Directory -Path $OriginalDir -Force | Out-Null
New-Item -ItemType Directory -Path $LocalMavenDir -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path -Parent $GenLogPath) -Force | Out-Null

# 1) 定位原始生产 jar（mods-original 优先；mods 中的原始 jar 自动移走）
foreach ($dep in $Deps) {
    $origPath = Join-Path $OriginalDir $dep.OriginalFile
    if (-not (Test-Path -LiteralPath $origPath)) {
        $candidate = Join-Path $ModsDir $dep.OriginalFile
        if (Test-Path -LiteralPath $candidate) {
            Move-Item -LiteralPath $candidate -Destination $origPath -Force
            Write-PrepLog ("原始生产 jar 从 run\mods 移入 run\mods-original: " + $dep.OriginalFile) INFO
        }
    }
    if (-not (Test-Path -LiteralPath $origPath)) {
        if ($Download) {
            Get-ModrinthJar -ProjectSlug $dep.ModrinthSlug -VersionNumber $dep.Version -DestinationFile $origPath
        }
        else {
            throw ('缺少原始生产 jar：' + $origPath + '（将其放入该目录，或使用 -Download 从 Modrinth 下载）')
        }
    }
    $dep | Add-Member -NotePropertyName OriginalPath -NotePropertyValue $origPath -Force
    $sizeMB = [math]::Round((Get-Item -LiteralPath $origPath).Length / 1MB, 2)
    Write-PrepLog ("原始 jar: " + $dep.OriginalFile + "（" + $sizeMB + " MB）") PASS
}

# 2) 校验原始 jar 的 modId/版本（-Force 时仅校验 modId）
foreach ($dep in $Deps) {
    $toml = Get-JarEntryText -JarFile $dep.OriginalPath -EntryName 'META-INF/mods.toml'
    if (-not $toml) { throw ('原始 jar 缺少 mods.toml：' + $dep.OriginalPath) }
    if ($toml -notmatch ('modId\s*=\s*"' + [regex]::Escape($dep.ModId) + '"')) {
        throw ('modId 不匹配（期望 ' + $dep.ModId + '）：' + $dep.OriginalPath)
    }
    if (-not $Force) {
        $verMatch = [regex]::Match($toml, 'modId\s*=\s*"' + [regex]::Escape($dep.ModId) + '"\s*\r?\n\s*displayName\s*=.*\r?\n\s*description\s*=.*\r?\n\s*version\s*=\s*"([^"]+)"')
        # 版本字段位置随 mods.toml 布局而异，直接检查 jar 文件名版本号
        if ($dep.OriginalFile -notmatch [regex]::Escape($dep.Version)) {
            throw ('版本与脚本常量不一致：' + $dep.OriginalFile + ' 期望含 ' + $dep.Version + '（可用 -Force 跳过）')
        }
    }
    Write-PrepLog ("校验通过: " + $dep.ModId + " @ " + $dep.Version) PASS
}

# 3) 生成隔离 Gradle 工程（每次重写，保证可复现；位于 build\ 下不入库）
$settingsContent = @'
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            name = 'MinecraftForge'
            url = 'https://maven.minecraftforge.net/'
        }
    }
}

plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '0.8.0'
}

rootProject.name = 'devmods-gen'
'@

$depsBlock = ''
foreach ($dep in $Deps) {
    $depsBlock += "    implementation fg.deobf('" + $dep.MavenGroup + ":" + $dep.MavenArtifact + ":" + $dep.MavenVersion + "')" + [Environment]::NewLine
}
$buildContent = @'
// 本文件由 scripts/prepare-dev-deps.ps1 自动生成（临时验证工程，位于 build\ 下，不纳入版本库）
// 用途：用 ForgeGradle 的 fg.deobf 把生产(SRG)模组 jar 重映射为 named/dev 变体。
plugins {
    id 'net.minecraftforge.gradle' version '[6.0,6.2)'
}

version = '1.0.0'
group = 'dev.local'

java.toolchain.languageVersion = JavaLanguageVersion.of(17)

minecraft {
    mappings channel: 'official', version: '1.20.1'
}

repositories {
    maven {
        name = 'LocalDevMods'
        url = uri("${rootProject.projectDir}/local-maven")
    }
}

dependencies {
    minecraft 'net.minecraftforge:forge:1.20.1-47.4.23'
__DEPS__}

tasks.register('exportDevMods') {
    doLast {
        def outDir = file("${rootProject.projectDir}/dev-mods-out")
        outDir.mkdirs()
        configurations.runtimeClasspath.resolvedConfiguration.resolvedArtifacts.each { art ->
            def id = art.moduleVersion.id
            if (id.group == 'dev.local') {
                copy {
                    from art.file
                    into outDir
                    rename { String fn -> "${id.name}-${id.version}-dev.jar" }
                }
                println "DEV-MOD: ${id.name}:${id.version} -> ${outDir}/${id.name}-${id.version}-dev.jar"
            }
        }
    }
}
'@
$buildContent = $buildContent.Replace('__DEPS__', $depsBlock)
[System.IO.File]::WriteAllText((Join-Path $GenProjectDir 'settings.gradle'), $settingsContent, (New-Object System.Text.UTF8Encoding($false)))
[System.IO.File]::WriteAllText((Join-Path $GenProjectDir 'build.gradle'), $buildContent, (New-Object System.Text.UTF8Encoding($false)))
# 关闭生成工程的 daemon，避免遗留 java 进程
[System.IO.File]::WriteAllText((Join-Path $GenProjectDir 'gradle.properties'), "org.gradle.daemon=false`r`norg.gradle.jvmargs=-Xmx2G`r`n", (New-Object System.Text.UTF8Encoding($false)))
Write-PrepLog ("已生成隔离工程: " + $GenProjectDir) INFO

# 4) 组本地 maven 仓库（生产 jar 以 maven 坐标提供给 fg.deobf）
foreach ($dep in $Deps) {
    $groupDir = Join-Path $LocalMavenDir (($dep.MavenGroup -replace '\.', '/') + '/' + $dep.MavenArtifact + '/' + $dep.MavenVersion)
    New-Item -ItemType Directory -Path $groupDir -Force | Out-Null
    Copy-Item -LiteralPath $dep.OriginalPath -Destination (Join-Path $groupDir ($dep.MavenArtifact + '-' + $dep.MavenVersion + '.jar')) -Force
    $pom = @"
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>$($dep.MavenGroup)</groupId>
  <artifactId>$($dep.MavenArtifact)</artifactId>
  <version>$($dep.MavenVersion)</version>
  <packaging>jar</packaging>
</project>
"@
    [System.IO.File]::WriteAllText((Join-Path $groupDir ($dep.MavenArtifact + '-' + $dep.MavenVersion + '.pom')), $pom, (New-Object System.Text.UTF8Encoding($false)))
}
Write-PrepLog ("本地 maven 仓库就绪: " + $LocalMavenDir) INFO

# 5) 运行 FG deobf 生成任务
if (Test-Path -LiteralPath $GenOutputDir) { Remove-Item -LiteralPath $GenOutputDir -Recurse -Force }
$genArgs = '-p "' + $GenProjectDir + '" exportDevMods --console=plain'
if ($Offline) { $genArgs += ' --offline' }
Write-PrepLog ("执行: gradlew.bat " + $genArgs) STEP
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$genResult = Invoke-NativeCapture -FilePath $GradlewBat -Arguments $genArgs -TimeoutSec $TimeoutSec -WorkingDirectory $ProjectRoot
$sw.Stop()
[System.IO.File]::WriteAllText($GenLogPath, $genResult.Output, (New-Object System.Text.UTF8Encoding($false)))
Write-PrepLog ("gradle 退出码=" + $genResult.ExitCode + "，耗时 " + [math]::Round($sw.Elapsed.TotalSeconds, 1) + "s，日志: " + $GenLogPath) STEP
if ($genResult.ExitCode -ne 0 -or $genResult.Output -notmatch 'BUILD SUCCESSFUL') {
    Write-Host '--- gradle 输出末尾 30 行 ---' -ForegroundColor DarkGray
    ($genResult.Output -split "`r?`n" | Select-Object -Last 30) | ForEach-Object { Write-Host ('  ' + $_) -ForegroundColor DarkGray }
    throw 'fg.deobf 生成失败（见上方日志）'
}

# 6) 复制 dev 变体到 run\mods（先清理旧 dev 变体）
$devJars = @(Get-ChildItem -LiteralPath $GenOutputDir -Filter '*-dev.jar' -File -ErrorAction Stop)
if ($devJars.Count -ne $Deps.Count) { throw ('dev 变体数量不符：生成 ' + $devJars.Count + ' 个，期望 ' + $Deps.Count + ' 个') }
Get-ChildItem -LiteralPath $ModsDir -Filter '*_mapped_official_*-dev.jar' -File -ErrorAction SilentlyContinue | Remove-Item -Force
foreach ($jar in $devJars) {
    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $ModsDir $jar.Name) -Force
    Write-PrepLog ("已放置: " + (Join-Path $ModsDir $jar.Name)) PASS
}

# 7) 产物校验（modId 正确 / mixin @Shadow 已重映射 / 产物可读）
$failed = New-Object System.Collections.ArrayList
foreach ($dep in $Deps) {
    $devJar = @(Get-ChildItem -LiteralPath $ModsDir -Filter '*_mapped_official_*-dev.jar' -File | Where-Object {
            $_.Name -like ($dep.MavenArtifact + '-*')
        })
    if ($devJar.Count -ne 1) {
        [void]$failed.Add($dep.Name + ': dev jar 数量异常(' + $devJar.Count + ')')
        continue
    }
    $devJar = $devJar[0]
    $toml = Get-JarEntryText -JarFile $devJar.FullName -EntryName 'META-INF/mods.toml'
    if (-not $toml -or $toml -notmatch ('modId\s*=\s*"' + [regex]::Escape($dep.ModId) + '"')) {
        [void]$failed.Add($dep.Name + ': dev jar 内 mods.toml/modId 校验失败')
    }
    if ($dep.MixinSmokeClass) {
        $bytes = Get-JarEntryBytes -JarFile $devJar.FullName -EntryName $dep.MixinSmokeClass
        if ($null -eq $bytes) {
            [void]$failed.Add($dep.Name + ': 缺少 mixin 冒烟检查类 ' + $dep.MixinSmokeClass)
        }
        else {
            $latin1 = [System.Text.Encoding]::GetEncoding(28591)
            $text = $latin1.GetString($bytes)
            if ($text -match [regex]::Escape($dep.MixinSmokeOldName)) {
                [void]$failed.Add($dep.Name + ': mixin 类仍含 SRG 名 ' + $dep.MixinSmokeOldName + '（重映射失败）')
            }
        }
    }
    Write-PrepLog ("产物校验: " + $devJar.Name + "（" + [math]::Round($devJar.Length / 1MB, 2) + " MB, SHA256 " + (Get-FileHash -LiteralPath $devJar.FullName -Algorithm SHA256).Hash.Substring(0, 16) + "...）") PASS
}

Write-Host ''
Write-Host '=== 依赖准备汇总 ===' -ForegroundColor Cyan
Write-Host ('run\mods 内容: ' + ((@(Get-ChildItem -LiteralPath $ModsDir -File | ForEach-Object { $_.Name })) -join ', '))
Write-Host ('run\mods-original 内容: ' + ((@(Get-ChildItem -LiteralPath $OriginalDir -File | ForEach-Object { $_.Name })) -join ', '))
Write-Host ('生成日志: ' + $GenLogPath)
Write-Host '说明：dev runClient 加载 run\mods 中的 named 变体；生产 jar 存放在 run\mods-original（Forge 不加载）。'
Write-Host '      升级 Embeddium/ModernFix 时修改本脚本顶部 $Deps 版本常量后重跑。'
if ($failed.Count -gt 0) {
    foreach ($f in $failed) { Write-PrepLog ("校验失败: " + $f) FAIL }
    exit 1
}
Write-Host 'dev 依赖准备: PASS' -ForegroundColor Green
exit 0