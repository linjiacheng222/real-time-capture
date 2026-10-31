#Requires -Version 5.1
<#
.SYNOPSIS
    捕获接口（captureapi）开发环境自检脚本：逐项检查 JDK、Gradle/Forge 缓存、工程与依赖、工具链、网络、硬件，输出 PASS/FAIL 汇总表。

.DESCRIPTION
    检查项分六类：
      运行时   —— PowerShell 版本、JDK17 固定路径、java/javac 版本、脚本自解析 JAVA_HOME、用户级持久化；
      构建工具 —— gradlew/wrapper dist、Gradle 版本与 JVM、Forge/FG 缓存；
      工程依赖 —— 关键文件、gradle.properties 基准值、mods.toml 四依赖、run\mods 两个 dev 依赖、构建产物、git 状态；
      工具链   —— git、ffmpeg（libx264/AAC）、Python 3.12、PyPI；
      网络     —— Forge Maven / Maven Central / Gradle Plugins / 华为云镜像 / GitHub / codeload / 资源站；
      硬件环境 —— GPU/驱动、内存、CPU、E 盘剩余、端口 25580。
    任一 FAIL 时脚本以退出码 1 结束；WARN 项不阻断（例如尚未运行 build.ps1 / run-client.ps1 的产物类检查）。

.PARAMETER SkipNetwork
    跳过所有网络检查（离线/无网环境快速自检）。

.PARAMETER SkipGradle
    跳过 gradlew -version 检查（避免启动 Gradle，加快自检）。

.PARAMETER JdkHome
    指定 JDK 17 根目录（默认 E:\Tools\jdk17）。

.PARAMETER MarkdownOut
    可选：把检查结果以 Markdown 表格写入指定文件（用于证据/文档留档）。

.EXAMPLE
    .\scripts\verify-env.ps1
    .\scripts\verify-env.ps1 -SkipNetwork
    .\scripts\verify-env.ps1 -MarkdownOut .\build\reports\env\verify-env.md
#>
[CmdletBinding()]
param(
    [switch]$SkipNetwork,
    [switch]$SkipGradle,
    [string]$JdkHome = '',
    [string]$MarkdownOut = ''
)

$ErrorActionPreference = 'Stop'

$ScriptDir = $PSScriptRoot
$ProjectRoot = Split-Path -Parent $ScriptDir
if (-not (Test-Path -LiteralPath (Join-Path $ProjectRoot 'gradlew.bat'))) {
    throw "无法从脚本目录解析工程根目录（期望包含 gradlew.bat）：$ProjectRoot"
}

# 本机 C/D 盘空间紧张：Gradle 用户目录固定到 E 盘（外部已设置 GRADLE_USER_HOME 时尊重外部值）
if ([string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) {
    $env:GRADLE_USER_HOME = 'E:\Tools\gradle-home'
}
$PreferredJdk = 'E:\Tools\jdk17'
$FallbackJdks = @(
    (Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-beta'),
    (Join-Path $env:USERPROFILE '.minecraft\runtime\java-runtime-beta')
)
$ExpectedJavaVersion = '17.0.15'
$InitialJavaHome = $env:JAVA_HOME
$swAll = [System.Diagnostics.Stopwatch]::StartNew()
$script:Results = New-Object System.Collections.ArrayList
$script:Jdk = $null
$jdkResolveError = ''

# ================= 输出与结果收集 =================
function Write-CheckLog {
    param([string]$Message, [ValidateSet('INFO', 'PASS', 'WARN', 'FAIL', 'SKIP', 'STEP')][string]$Level = 'INFO')
    $color = @{ INFO = 'Gray'; PASS = 'Green'; WARN = 'Yellow'; FAIL = 'Red'; SKIP = 'DarkGray'; STEP = 'Cyan' }[$Level]
    Write-Host ("[VERIFY/{0}] {1}" -f $Level, $Message) -ForegroundColor $color
}

function Add-Result {
    param(
        [string]$Category,
        [string]$Name,
        [string]$Status,
        [string]$Detail,
        [double]$Seconds = 0
    )
    $row = [pscustomobject]@{
        Category = $Category
        Name     = $Name
        Status   = $Status
        Seconds  = [math]::Round($Seconds, 2)
        Detail   = $Detail
    }
    [void]$script:Results.Add($row)
    $secs = ''
    if ($row.Seconds -gt 0) { $secs = ' (' + $row.Seconds + 's)' }
    Write-CheckLog ("{0} :: {1} — {2}{3}" -f $Category, $Name, $Detail, $secs) $Status
}

function Invoke-Check {
    param([string]$Category, [string]$Name, [scriptblock]$Body, [string]$FailHint = '')
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $detail = & $Body
        $sw.Stop()
        if ($null -eq $detail) { $detail = '' }
        Add-Result -Category $Category -Name $Name -Status 'PASS' -Detail ([string]$detail) -Seconds $sw.Elapsed.TotalSeconds
    }
    catch {
        $sw.Stop()
        $msg = $_.Exception.Message
        if ($FailHint) { $msg = $msg + '（' + $FailHint + '）' }
        Add-Result -Category $Category -Name $Name -Status 'FAIL' -Detail $msg -Seconds $sw.Elapsed.TotalSeconds
    }
}

function Invoke-OptionalCheck {
    <# 预期失败不阻断整体的检查（产物/日志类），失败记为 WARN。 #>
    param([string]$Category, [string]$Name, [scriptblock]$Body, [string]$WarnHint = '')
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $detail = & $Body
        $sw.Stop()
        if ($null -eq $detail) { $detail = '' }
        Add-Result -Category $Category -Name $Name -Status 'PASS' -Detail ([string]$detail) -Seconds $sw.Elapsed.TotalSeconds
    }
    catch {
        $sw.Stop()
        $msg = $_.Exception.Message
        if ($WarnHint) { $msg = $msg + '（' + $WarnHint + '）' }
        Add-Result -Category $Category -Name $Name -Status 'WARN' -Detail $msg -Seconds $sw.Elapsed.TotalSeconds
    }
}

function Add-InfoResult {
    param([string]$Category, [string]$Name, [string]$Detail)
    Add-Result -Category $Category -Name $Name -Status 'INFO' -Detail $Detail
}

function Add-SkipResult {
    param([string]$Category, [string]$Name, [string]$Detail)
    Add-Result -Category $Category -Name $Name -Status 'SKIP' -Detail $Detail
}

# ================= 通用工具 =================
function Read-TextAuto {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return '' }
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -eq 0) { return '' }
    try {
        $strict = New-Object System.Text.UTF8Encoding($false, $true)
        return $strict.GetString($bytes).TrimStart([char]0xFEFF)
    }
    catch {
        return [System.Text.Encoding]::Default.GetString($bytes).TrimStart([char]0xFEFF)
    }
}

function Invoke-NativeCapture {
    <# 执行原生程序并捕获输出；返回 ExitCode / Output。
       实现说明：使用 .NET Process（PowerShell 5.1 下 Start-Process -PassThru 在重定向输出时 ExitCode 不可靠），
       输出按字节捕获后再自动识别 UTF-8 / 系统编码（兼容中文输出）。.bat/.cmd 自动经 cmd /c 包裹。 #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string]$Arguments = '',
        [int]$TimeoutSec = 120,
        [string]$WorkingDirectory = $ProjectRoot
    )
    $ext = [System.IO.Path]::GetExtension($FilePath).ToLowerInvariant()
    if ($ext -eq '.bat' -or $ext -eq '.cmd') {
        if ([string]::IsNullOrWhiteSpace($Arguments)) {
            $Arguments = '/c ""' + $FilePath + '""'
        }
        else {
            $Arguments = '/c ""' + $FilePath + '" ' + $Arguments + '"'
        }
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
        if ($TimeoutSec -gt 0) {
            if (-not $proc.WaitForExit($TimeoutSec * 1000)) { $timedOut = $true }
        }
        else {
            $proc.WaitForExit()
        }
        if ($timedOut) {
            try { $proc.Kill() } catch { }
            try { $proc.WaitForExit(5000) | Out-Null } catch { }
            throw "命令超时（${TimeoutSec}s）：$FilePath $Arguments"
        }
        try { $outTask.Wait(10000) | Out-Null } catch { }
        try { $errTask.Wait(10000) | Out-Null } catch { }
        $outBytes = $outStream.ToArray()
        $errBytes = $errStream.ToArray()
        $output = ''
        foreach ($bytes in @($outBytes, $errBytes)) {
            if ($null -eq $bytes -or $bytes.Length -eq 0) { continue }
            try {
                $strict = New-Object System.Text.UTF8Encoding($false, $true)
                $output += $strict.GetString($bytes).TrimStart([char]0xFEFF)
            }
            catch {
                $output += [System.Text.Encoding]::Default.GetString($bytes).TrimStart([char]0xFEFF)
            }
        }
        return [pscustomobject]@{ ExitCode = $proc.ExitCode; Output = $output }
    }
    finally {
        try { $proc.Dispose() } catch { }
        try { $outStream.Dispose() } catch { }
        try { $errStream.Dispose() } catch { }
    }
}function Get-GradleProperty {
    param([string]$Name, [string]$PropertiesPath)
    if (-not (Test-Path -LiteralPath $PropertiesPath)) { return $null }
    foreach ($line in [System.IO.File]::ReadAllLines($PropertiesPath)) {
        if ($line -match ('^\s*' + [regex]::Escape($Name) + '\s*=\s*(.*)$')) {
            return $Matches[1].Trim()
        }
    }
    return $null
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

function Test-TcpEndpoint {
    param([string]$TargetHost, [int]$Port = 443, [int]$TimeoutMs = 6000)
    # 3 次尝试：公网端点偶发瞬时不可达（如 codeload/PyPI），重试可避免误报环境退化
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $client = New-Object System.Net.Sockets.TcpClient
        try {
            $iar = $client.BeginConnect($TargetHost, $Port, $null, $null)
            if ($iar.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) {
                $client.EndConnect($iar)
                return $true
            }
        }
        catch { }
        finally { try { $client.Close() } catch { } }
        if ($attempt -lt 3) { Start-Sleep -Milliseconds 500 }
    }
    return $false
}

function Invoke-HttpHead {
    param([string]$Url, [int]$TimeoutSec = 20)
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    }
    catch { }
    $resp = Invoke-WebRequest -Uri $Url -Method Head -UseBasicParsing -TimeoutSec $TimeoutSec -ErrorAction Stop
    return [int]$resp.StatusCode
}

# ================= 准备：JDK =================
Write-Host ''
Write-Host '=== 捕获接口开发环境自检（verify-env） ===' -ForegroundColor Cyan
Write-CheckLog ("工程根目录: " + $ProjectRoot) STEP
try {
    $script:Jdk = Resolve-Jdk17
    Set-JdkEnvironment -JdkPath $script:Jdk
}
catch {
    $jdkResolveError = $_.Exception.Message
}

# ================= 1) 运行时 =================
Invoke-Check -Category '运行时' -Name 'PowerShell 可用' -Body {
    return ("版本 " + $PSVersionTable.PSVersion + "（" + $PSVersionTable.PSEdition + "）；" + $(if ($PSVersionTable.PSEdition -eq 'Desktop') { '注意：本机为 Windows PowerShell 5.1，脚本按 5.1/7 双兼容编写' } else { 'PowerShell 7' }))
}

Invoke-Check -Category '运行时' -Name 'JDK17 固定路径 E:\Tools\jdk17' -Body {
    $javaExe = Join-Path $PreferredJdk 'bin\java.exe'
    $javacExe = Join-Path $PreferredJdk 'bin\javac.exe'
    if (-not (Test-Path -LiteralPath $javaExe)) { throw ('缺少 ' + $javaExe) }
    if (-not (Test-Path -LiteralPath $javacExe)) { throw ('缺少 ' + $javacExe) }
    $files = @(Get-ChildItem -LiteralPath $PreferredJdk -Recurse -File -ErrorAction SilentlyContinue)
    $sizeMB = [math]::Round((($files | Measure-Object -Property Length -Sum).Sum / 1MB), 1)
    return ('完整 JDK（' + $files.Count + ' 个文件，' + $sizeMB + ' MB）')
}

Invoke-Check -Category '运行时' -Name 'java -version = 17.0.15' -Body {
    if (-not $script:Jdk) { throw $jdkResolveError }
    $res = Invoke-NativeCapture -FilePath (Join-Path $script:Jdk 'bin\java.exe') -Arguments '-version' -TimeoutSec 60
    if ($res.ExitCode -ne 0) { throw ('java -version 退出码 ' + $res.ExitCode) }
    if ($res.Output -notmatch ('version "' + [regex]::Escape($ExpectedJavaVersion) + '"')) {
        throw ('实际版本与基线不符：' + ($res.Output -replace "`r?`n", ' | '))
    }
    $line = ($res.Output -split "`r?`n" | Where-Object { $_ -match 'openjdk version|java version' } | Select-Object -First 1)
    return $line.Trim()
}

Invoke-Check -Category '运行时' -Name 'javac -version = 17.0.15' -Body {
    if (-not $script:Jdk) { throw $jdkResolveError }
    $res = Invoke-NativeCapture -FilePath (Join-Path $script:Jdk 'bin\javac.exe') -Arguments '-version' -TimeoutSec 60
    if ($res.ExitCode -ne 0) { throw ('javac 退出码 ' + $res.ExitCode) }
    if ($res.Output -notmatch 'javac 17\.0\.15') { throw ('实际：' + $res.Output.Trim()) }
    return $res.Output.Trim()
}

Invoke-Check -Category '运行时' -Name '脚本自解析 JAVA_HOME（不依赖外部环境）' -Body {
    if (-not $script:Jdk) { throw $jdkResolveError }
    if ($env:JAVA_HOME -ne $script:Jdk) { throw ('JAVA_HOME 未指向固定 JDK：' + $env:JAVA_HOME) }
    $initial = if ([string]::IsNullOrWhiteSpace($InitialJavaHome)) { '<未设置>' } else { $InitialJavaHome }
    return ('调用前外部 JAVA_HOME=' + $initial + '；解析结果=' + $script:Jdk)
}

Invoke-OptionalCheck -Category '运行时' -Name '用户级 JAVA_HOME 已持久化' -Body {
    if (-not $script:Jdk) { throw $jdkResolveError }
    $userJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
    if ($userJavaHome -ne $script:Jdk) { throw ('User 级 JAVA_HOME=' + $userJavaHome) }
    return $userJavaHome
} -WarnHint '执行 scripts\dev-env.ps1 -Persist 写入'

Invoke-OptionalCheck -Category '运行时' -Name '用户级 PATH 含 JDK bin' -Body {
    if (-not $script:Jdk) { throw $jdkResolveError }
    $binDir = Join-Path $script:Jdk 'bin'
    $userPath = [Environment]::GetEnvironmentVariable('PATH', 'User')
    if ([string]::IsNullOrEmpty($userPath)) { throw 'User 级 PATH 为空' }
    $hit = @($userPath -split ';' | Where-Object { $_.TrimEnd('\') -eq $binDir.TrimEnd('\') })
    if ($hit.Count -eq 0) { throw ('User 级 PATH 未包含 ' + $binDir) }
    return $binDir
} -WarnHint '执行 scripts\dev-env.ps1 -Persist 写入'

Invoke-OptionalCheck -Category '运行时' -Name 'Gradle 用户目录在 E 盘（GRADLE_USER_HOME）' -Body {
    $userGradle = [Environment]::GetEnvironmentVariable('GRADLE_USER_HOME', 'User')
    if ([string]::IsNullOrEmpty($userGradle)) { throw 'User 级 GRADLE_USER_HOME 未设置' }
    if (-not $userGradle.StartsWith('E:\')) { throw ('未在 E 盘：' + $userGradle) }
    if (-not (Test-Path -LiteralPath $userGradle)) { throw ('目录不存在：' + $userGradle) }
    $gb = [math]::Round((((Get-ChildItem -LiteralPath $userGradle -Recurse -File -ErrorAction SilentlyContinue) | Measure-Object -Property Length -Sum).Sum / 1GB), 2)
    return ($userGradle + '（' + $gb + ' GB）')
} -WarnHint '执行 scripts\dev-env.ps1 -Persist 写入；或设置用户级 GRADLE_USER_HOME=E:\Tools\gradle-home'

# ================= 2) 构建工具链 =================
Invoke-Check -Category '构建工具' -Name 'gradlew / wrapper / 工程文件齐全' -Body {
    $required = @(
        (Join-Path $ProjectRoot 'gradlew.bat'),
        (Join-Path $ProjectRoot 'gradlew'),
        (Join-Path $ProjectRoot 'gradle\wrapper\gradle-wrapper.properties'),
        (Join-Path $ProjectRoot 'settings.gradle'),
        (Join-Path $ProjectRoot 'build.gradle'),
        (Join-Path $ProjectRoot 'gradle.properties')
    )
    $missing = @($required | Where-Object { -not (Test-Path -LiteralPath $_) })
    if ($missing.Count -gt 0) { throw ('缺少：' + ($missing -join '; ')) }
    return ('6 个关键文件齐全')
}

Invoke-Check -Category '构建工具' -Name 'Gradle wrapper 8.14.5 dist 已缓存' -Body {
    $gradleHome = if ([string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) { Join-Path $env:USERPROFILE '.gradle' } else { $env:GRADLE_USER_HOME }
    $distsRoot = Join-Path $gradleHome 'wrapper\dists\gradle-8.14.5-bin'
    $candidates = @(Get-ChildItem -LiteralPath $distsRoot -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            Join-Path $_.FullName 'gradle-8.14.5\bin\gradle.bat'
        } | Where-Object { Test-Path -LiteralPath $_ })
    if ($candidates.Count -eq 0) { throw ('未找到缓存的 gradle-8.14.5 dist：' + $distsRoot) }
    return $candidates[0]
}

if ($SkipGradle) {
    Add-SkipResult -Category '构建工具' -Name 'gradlew -version（Gradle/JVM）' -Detail '已指定 -SkipGradle'
}
else {
    Invoke-Check -Category '构建工具' -Name 'gradlew -version（Gradle 8.14.5 + JVM 17）' -Body {
        if (-not $script:Jdk) { throw $jdkResolveError }
        $gradlewBat = Join-Path $ProjectRoot 'gradlew.bat'
        $res = Invoke-NativeCapture -FilePath $gradlewBat -Arguments '-version' -TimeoutSec 180 -WorkingDirectory $ProjectRoot
        if ($res.ExitCode -ne 0) { throw ('gradlew -version 退出码 ' + $res.ExitCode + '：' + ($res.Output -split "`r?`n" | Select-Object -Last 3) -join ' | ') }
        $gradleVer = 'unknown'
        if ($res.Output -match 'Gradle\s+(\d+\.\d+(?:\.\d+)?)') { $gradleVer = $Matches[1] }
        $jvmVer = 'unknown'
        if ($res.Output -match 'JVM:\s*(\d+\.\d+\.\d+)') { $jvmVer = $Matches[1] }
        if ($gradleVer -ne '8.14.5') { throw ('Gradle 版本 ' + $gradleVer + '，期望 8.14.5') }
        if ($jvmVer -notmatch '^17\.') { throw ('JVM 版本 ' + $jvmVer + '，期望 17.x') }
        return ('Gradle ' + $gradleVer + ' / JVM ' + $jvmVer)
    } -FailHint '检查 E:\Tools\jdk17 与 wrapper 缓存'
}

Invoke-Check -Category '构建工具' -Name 'Forge / ForgeGradle 缓存齐全' -Body {
    $gradleHome = if ([string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) { Join-Path $env:USERPROFILE '.gradle' } else { $env:GRADLE_USER_HOME }
    $fgRoot = Join-Path $gradleHome 'caches\forge_gradle'
    if (-not (Test-Path -LiteralPath $fgRoot)) { throw ('缺少缓存目录：' + $fgRoot) }
    $required = @('assets', 'mcp_repo', 'minecraft_repo', 'minecraft_user_repo')
    $missing = @($required | Where-Object { -not (Test-Path -LiteralPath (Join-Path $fgRoot $_)) })
    if ($missing.Count -gt 0) { throw ('缺少缓存子目录：' + ($missing -join ', ')) }
    $assets = Join-Path $fgRoot 'assets'
    $assetsMB = [math]::Round(((@(Get-ChildItem -LiteralPath $assets -Recurse -File -ErrorAction SilentlyContinue) | Measure-Object -Property Length -Sum).Sum / 1MB), 1)
    return ('assets ' + $assetsMB + ' MB + mcp_repo / minecraft_repo / minecraft_user_repo 均存在')
}

# ================= 3) 工程与依赖 =================
$gradleProps = Join-Path $ProjectRoot 'gradle.properties'
$script:ModId = Get-GradleProperty -Name 'modId' -PropertiesPath $gradleProps
$script:ModVersion = Get-GradleProperty -Name 'modVersion' -PropertiesPath $gradleProps
if (-not $script:ModId) { $script:ModId = 'captureapi' }
if (-not $script:ModVersion) { $script:ModVersion = '0.5.0' }

Invoke-Check -Category '工程依赖' -Name 'gradle.properties 基准值与规划一致' -Body {
    $expected = [ordered]@{
        modId              = 'captureapi'
        modVersion         = '0.5.0'
        minecraftVersion   = '1.20.1'
        minForgeVersion    = '47.3.0'
        devForgeVersion    = '47.4.23'
        embeddiumVersion   = '0.3.31'
        modernfixVersion   = '5.27.89'
        minModernfixVersion = '5.18.0'
        modForgeDisplayTest = 'IGNORE_ALL_VERSION'
        modMavenGroup      = 'com.captureapi'
    }
    $mismatches = New-Object System.Collections.ArrayList
    foreach ($key in $expected.Keys) {
        $actual = Get-GradleProperty -Name $key -PropertiesPath $gradleProps
        if ($actual -ne $expected[$key]) {
            [void]$mismatches.Add(('{0}={1}（期望 {2}）' -f $key, $actual, $expected[$key]))
        }
    }
    if ($mismatches.Count -gt 0) { throw ($mismatches -join '; ') }
    return ('10 个基准键一致：minecraft=1.20.1 / devForge=47.4.23 / minForge=47.3.0 / embeddium=0.3.31 / modernfix=5.27.89 / minModernfix=5.18.0')
}

Invoke-Check -Category '工程依赖' -Name '源 mods.toml 四依赖 + loaderVersion=[47,)' -Body {
    $modsToml = Join-Path $ProjectRoot 'src\main\resources\META-INF\mods.toml'
    if (-not (Test-Path -LiteralPath $modsToml)) { throw ('缺少 ' + $modsToml) }
    $text = Read-TextAuto -Path $modsToml
    if ($text -notmatch 'loaderVersion\s*=\s*"\[47,\)"') { throw 'loaderVersion 不是 [47,)（源文件）' }
    $depCount = ([regex]::Matches($text, '\[\[dependencies\.')).Count
    if ($depCount -ne 4) { throw ('依赖声明 ' + $depCount + ' 项，期望 4 项') }
    foreach ($dep in @('forge', 'minecraft', 'embeddium', 'modernfix')) {
        if ($text -notmatch ('modId\s*=\s*"' + $dep + '"')) { throw ('缺少依赖 ' + $dep) }
    }
    if ($text -notmatch [regex]::Escape('versionRange = "[${minModernfixVersion},)"')) {
        throw 'modernfix 依赖区间未引用 minModernfixVersion（D-MF-1）'
    }
    return 'loaderVersion=[47,)；4 项依赖齐全；modernfix 下限引用 minModernfixVersion（D-MF-1）'
}

Invoke-Check -Category '工程依赖' -Name 'run\mods dev 依赖 embeddium（named 变体）' -Body {
    $modsDir = Join-Path $ProjectRoot 'run\mods'
    if (-not (Test-Path -LiteralPath $modsDir)) { throw ('缺少目录 ' + $modsDir) }
    $dev = @(Get-ChildItem -LiteralPath $modsDir -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -like 'embeddium-*' -and $_.Name -like '*mapped_official*-dev.jar' })
    if ($dev.Count -gt 0) { return ($dev[0].Name + '（named 变体，' + [math]::Round($dev[0].Length / 1MB, 2) + ' MB）') }
    $orig = @(Get-ChildItem -LiteralPath $modsDir -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -like 'embeddium*.jar' -and $_.Name -match '0\.3\.31' })
    if ($orig.Count -gt 0) { throw ('只有生产 jar（' + $orig[0].Name + '）：named dev 环境会崩溃，先运行 scripts\prepare-dev-deps.ps1') }
    throw 'run\mods 未找到 embeddium 依赖（生产或 named 变体）'
}

Invoke-Check -Category '工程依赖' -Name 'run\mods dev 依赖 modernfix（named 变体）' -Body {
    $modsDir = Join-Path $ProjectRoot 'run\mods'
    if (-not (Test-Path -LiteralPath $modsDir)) { throw ('缺少目录 ' + $modsDir) }
    $dev = @(Get-ChildItem -LiteralPath $modsDir -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -like 'modernfix-*' -and $_.Name -like '*mapped_official*-dev.jar' })
    if ($dev.Count -gt 0) { return ($dev[0].Name + '（named 变体，' + [math]::Round($dev[0].Length / 1MB, 2) + ' MB）') }
    $orig = @(Get-ChildItem -LiteralPath $modsDir -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -like '*modernfix*.jar' -and $_.Name -match '5\.27\.89' })
    if ($orig.Count -gt 0) { throw ('只有生产 jar（' + $orig[0].Name + '）：named dev 环境会崩溃，先运行 scripts\prepare-dev-deps.ps1') }
    throw 'run\mods 未找到 modernfix 依赖（生产或 named 变体）'
}

Invoke-OptionalCheck -Category '工程依赖' -Name 'run\mods-original 生产 jar 备份' -Body {
    $origDir = Join-Path $ProjectRoot 'run\mods-original'
    if (-not (Test-Path -LiteralPath $origDir)) { throw '无 run\mods-original 目录' }
    $jars = @(Get-ChildItem -LiteralPath $origDir -Filter '*.jar' -File -ErrorAction SilentlyContinue)
    if ($jars.Count -eq 0) { throw 'run\mods-original 为空' }
    return (($jars | ForEach-Object { $_.Name }) -join ', ')
} -WarnHint 'scripts\prepare-dev-deps.ps1 会自动维护（生产 jar 不被 Forge 加载）'

Invoke-Check -Category '构建工具' -Name 'scripts 目录脚本齐全（6 个）' -Body {
    $scripts = @('dev-env.ps1', 'verify-env.ps1', 'build.ps1', 'run-client.ps1', 'install-to-instance.ps1', 'prepare-dev-deps.ps1')
    $missing = @($scripts | Where-Object { -not (Test-Path -LiteralPath (Join-Path $ScriptDir $_)) })
    if ($missing.Count -gt 0) { throw ('缺少：' + ($missing -join ', ')) }
    return '6 个脚本齐全（含 prepare-dev-deps.ps1）'
}

Invoke-OptionalCheck -Category '工程依赖' -Name '构建产物 build\libs jar' -Body {
    $jar = Join-Path $ProjectRoot ('build\libs\' + $script:ModId + '-' + $script:ModVersion + '.jar')
    if (-not (Test-Path -LiteralPath $jar)) { throw ('不存在：' + $jar) }
    $item = Get-Item -LiteralPath $jar
    return ($item.FullName + '（' + $item.Length + ' bytes，' + $item.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') + '）')
} -WarnHint '运行 scripts\build.ps1 生成'

Invoke-OptionalCheck -Category '工程依赖' -Name 'Mixin/AT/refmap 基建产物（I1.1.1）' -Body {
    $jar = Join-Path $ProjectRoot ('build\libs\' + $script:ModId + '-' + $script:ModVersion + '.jar')
    if (-not (Test-Path -LiteralPath $jar)) { throw ('不存在：' + $jar) }
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $required = @(($script:ModId + '.mixins.json'), ($script:ModId + '.refmap.json'), 'META-INF/accesstransformer.cfg', 'META-INF/MANIFEST.MF')
        $missing = @()
        foreach ($req in $required) {
            if ($null -eq $zip.GetEntry($req)) { $missing += $req }
        }
        if ($missing.Count -gt 0) { throw ('jar 缺少：' + ($missing -join ', ') + '（jar=' + $jar + '，entry 数=' + $zip.Entries.Count + '）') }
        $manifestEntry = $zip.GetEntry('META-INF/MANIFEST.MF')
        $reader = New-Object System.IO.StreamReader($manifestEntry.Open())
        try { $manifest = $reader.ReadToEnd() } finally { $reader.Close() }
        if ($manifest -notmatch ('MixinConfigs:\s*' + [regex]::Escape($script:ModId + '.mixins.json'))) { throw 'MANIFEST 缺少 MixinConfigs' }
        $refmapEntry = $zip.GetEntry($script:ModId + '.refmap.json')
        $reader = New-Object System.IO.StreamReader($refmapEntry.Open())
        try { $refmap = $reader.ReadToEnd() } finally { $reader.Close() }
        if ($refmap -notmatch 'm_\d+_') { throw 'refmap 未含 SRG 映射（Mixin AP 可能未生效）' }
        return 'mixins.json / refmap.json / accesstransformer.cfg / MixinConfigs 均齐备'
    }
    finally { $zip.Dispose() }
} -WarnHint '运行 scripts\build.ps1 生成产物后复检'

Invoke-OptionalCheck -Category '工程依赖' -Name 'dev 客户端 Mixin 自检日志（I1.1.1）' -Body {
    $latestLog = Join-Path $ProjectRoot 'run\logs\latest.log'
    if (-not (Test-Path -LiteralPath $latestLog)) { throw '不存在 run\logs\latest.log' }
    $text = Read-TextAuto -Path $latestLog
    if ($text -notmatch 'Mixin 基建自检') { throw '未找到 Mixin 基建自检日志（run-client.ps1 复跑后复检）' }
    if ($text -notmatch '\[CaptureAPI\] 底座就绪') { throw '未找到客户端 setup 基线日志（I1.1.2）' }
    return 'Mixin 注入自检 + 客户端 setup 基线日志均存在'
} -WarnHint '运行 scripts\run-client.ps1 验证'

Invoke-OptionalCheck -Category '工程依赖' -Name '配置骨架与快照日志（T1.5，dev）' -Body {
    $configFile = Join-Path $ProjectRoot 'run\config\captureapi-client.toml'
    if (-not (Test-Path -LiteralPath $configFile)) { throw '未生成 run\config\captureapi-client.toml（先运行 run-client.ps1）' }
    $configText = Read-TextAuto -Path $configFile
    foreach ($section in @('[video]', '[audio]', '[server]', '[log]')) {
        if ($configText -notmatch [regex]::Escape($section)) { throw '配置骨架缺少 section：' + $section }
    }
    $latestLog = Join-Path $ProjectRoot 'run\logs\latest.log'
    if (-not (Test-Path -LiteralPath $latestLog)) { throw '不存在 run\logs\latest.log' }
    $text = Read-TextAuto -Path $latestLog
    if ($text -notmatch '参数快照建立（Loading）') { throw '未找到参数快照建立日志（I1.5.3/1.5.4）' }
    return '四 section 配置骨架存在；Loading 快照建立日志存在'
} -WarnHint '运行 scripts\run-client.ps1（dev 自检属性开启时）生成配置与日志'

Invoke-OptionalCheck -Category '工程依赖' -Name 'M1 核心自检日志（T1.2/T1.3，dev）' -Body {
    $latestLog = Join-Path $ProjectRoot 'run\logs\latest.log'
    if (-not (Test-Path -LiteralPath $latestLog)) { throw '不存在 run\logs\latest.log' }
    $text = Read-TextAuto -Path $latestLog
    if ($text -notmatch '核心自检 PASS') { throw '未找到核心自检 PASS 日志（run-client.ps1 复跑后复检）' }
    if ($text -notmatch '资源管理器自检 PASS') { throw '未找到资源管理器自检 PASS 日志（I1.2.1）' }
    return 'GL 守卫正/负例 + 最新值通道 + 资源管理器生命周期自检 PASS 日志均存在'
} -WarnHint '运行 scripts\run-client.ps1（dev 自检属性 captureapi.debug.selftest=true 时）'

Invoke-OptionalCheck -Category '工程依赖' -Name 'dev 客户端日志（runClient 已实测）' -Body {
    $latestLog = Join-Path $ProjectRoot 'run\logs\latest.log'
    if (-not (Test-Path -LiteralPath $latestLog)) { throw '不存在 run\logs\latest.log' }
    $item = Get-Item -LiteralPath $latestLog
    $text = Read-TextAuto -Path $latestLog
    $marker = if ($text -match '(?i)Sound engine started') { '含主菜单标记 Sound engine started' } else { '未含主菜单标记' }
    return ('latest.log ' + [math]::Round($item.Length / 1KB, 1) + ' KB，' + $marker)
} -WarnHint '运行 scripts\run-client.ps1 生成'

Invoke-OptionalCheck -Category '工程依赖' -Name 'run\crash-reports 无残留' -Body {
    $crashDir = Join-Path $ProjectRoot 'run\crash-reports'
    if (-not (Test-Path -LiteralPath $crashDir)) { return '无 crash-reports 目录（dev 客户端无崩溃记录）' }
    $files = @(Get-ChildItem -LiteralPath $crashDir -File -ErrorAction SilentlyContinue)
    if ($files.Count -gt 0) { throw ('存在 ' + $files.Count + ' 个 crash report：' + (($files | ForEach-Object { $_.Name }) -join ', ')) }
    return 'crash-reports 目录为空'
} -WarnHint '若有 crash report 需排查'

Invoke-Check -Category '工程依赖' -Name 'git 工作区状态（按计划不提交）' -Body {
    $gitExe = (Get-Command git -ErrorAction SilentlyContinue).Source
    if (-not $gitExe) { throw 'git 不在 PATH' }
    $res = Invoke-NativeCapture -FilePath $gitExe -Arguments 'status --porcelain' -TimeoutSec 60 -WorkingDirectory $ProjectRoot
    if ($res.ExitCode -ne 0) { throw ('git status 失败：' + $res.Output) }
    $lines = @($res.Output -split "`r?`n" | Where-Object { $_.Trim() -ne '' })
    $remoteRes = Invoke-NativeCapture -FilePath $gitExe -Arguments 'remote get-url origin' -TimeoutSec 30 -WorkingDirectory $ProjectRoot
    $remote = $remoteRes.Output.Trim()
    return ('工作区 ' + $lines.Count + ' 个未提交修改（未提交，留待审查）；origin=' + $remote)
}

# ================= 4) 工具链 =================
Invoke-Check -Category '工具链' -Name 'git 可用' -Body {
    $gitExe = (Get-Command git -ErrorAction SilentlyContinue).Source
    if (-not $gitExe) { throw 'git 不在 PATH' }
    $res = Invoke-NativeCapture -FilePath $gitExe -Arguments '--version' -TimeoutSec 30
    if ($res.ExitCode -ne 0) { throw ('git --version 失败：' + $res.Output) }
    return ($res.Output.Trim() + '（' + $gitExe + '）')
}

Invoke-Check -Category '工具链' -Name 'ffmpeg 7.1 + libx264/AAC 编码器' -Body {
    $ffmpegExe = (Get-Command ffmpeg -ErrorAction SilentlyContinue).Source
    if (-not $ffmpegExe) { throw 'ffmpeg 不在 PATH' }
    $verRes = Invoke-NativeCapture -FilePath $ffmpegExe -Arguments '-version' -TimeoutSec 60
    if ($verRes.ExitCode -ne 0) { throw ('ffmpeg -version 失败：' + $verRes.Output) }
    $verLine = ($verRes.Output -split "`r?`n" | Select-Object -First 1)
    $encRes = Invoke-NativeCapture -FilePath $ffmpegExe -Arguments '-hide_banner -encoders' -TimeoutSec 120
    if ($encRes.ExitCode -ne 0) { throw ('ffmpeg -encoders 失败：' + $encRes.Output) }
    $hasX264 = ($encRes.Output -match 'libx264')
    $hasAac = ($encRes.Output -match '(?m)\baac\b')
    if (-not $hasX264) { throw '编码器列表缺少 libx264' }
    if (-not $hasAac) { throw '编码器列表缺少 aac' }
    return ($verLine.Trim() + '；libx264=YES aac=YES')
}

Invoke-Check -Category '工具链' -Name 'Python 3.12 可用' -Body {
    $pythonExe = (Get-Command python -ErrorAction SilentlyContinue).Source
    if (-not $pythonExe) { throw 'python 不在 PATH' }
    $res = Invoke-NativeCapture -FilePath $pythonExe -Arguments '--version' -TimeoutSec 60
    $text = $res.Output.Trim()
    if ($text -notmatch 'Python\s+3\.12') { throw ('实际：' + $res.Output.Trim()) }
    return ($res.Output.Trim() + '（' + $pythonExe + '）')
}

# ================= 5) 网络 =================
if ($SkipNetwork) {
    foreach ($name in @('git ls-remote origin', 'PyPI', 'Maven Forge', 'Maven Central', 'Gradle Plugins', '华为云镜像', 'GitHub', 'codeload.github.com', 'Minecraft 资源站')) {
        Add-SkipResult -Category '网络' -Name $name -Detail '已指定 -SkipNetwork'
    }
}
else {
    Invoke-Check -Category '网络' -Name 'git ls-remote origin' -Body {
        $gitExe = (Get-Command git -ErrorAction SilentlyContinue).Source
        if (-not $gitExe) { throw 'git 不在 PATH' }
        $res = $null
        $lastErr = ''
        $attemptsUsed = 0
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            $attemptsUsed = $attempt
            $res = Invoke-NativeCapture -FilePath $gitExe -Arguments 'ls-remote origin' -TimeoutSec 90 -WorkingDirectory $ProjectRoot
            if ($res.ExitCode -eq 0) { break }
            $lastErr = (($res.Output -split "`r?`n" | Where-Object { $_.Trim() } | Select-Object -Last 3) -join ' | ')
            if ($attempt -lt 3) {
                Start-Sleep -Seconds 2
                Write-CheckLog ("git ls-remote 第 " + $attempt + " 次失败（GitHub 偶发连接重置），2s 后重试：" + $lastErr) WARN
            }
        }
        if ($res.ExitCode -ne 0) { throw ('3 次尝试均失败：' + $lastErr) }
        $lines = @($res.Output -split "`r?`n" | Where-Object { $_.Trim() -ne '' })
        if ($lines.Count -eq 0) { throw '远端返回为空' }
        $head = @($lines | Where-Object { $_ -match '\sHEAD$' })
        $headSha = if ($head.Count -gt 0) { ($head[0] -split "`t")[0] } else { '<无 HEAD 行>' }
        return ('origin 可达（第 ' + $attemptsUsed + ' 次尝试成功），返回 ' + $lines.Count + ' 行，HEAD=' + $headSha.Substring(0, [Math]::Min(12, $headSha.Length)))
    } -FailHint '检查网络/代理/凭据'

    Invoke-Check -Category '网络' -Name 'PyPI 可达（Invoke-WebRequest pypi.org/simple/）' -Body {
        $code = Invoke-HttpHead -Url 'https://pypi.org/simple/' -TimeoutSec 20
        return ('HTTP ' + $code)
    } -FailHint '检查网络/TLS'

    $hostChecks = [ordered]@{
        'Maven Forge'          = 'maven.minecraftforge.net'
        'Maven Central'        = 'repo.maven.apache.org'
        'Gradle Plugins'       = 'plugins.gradle.org'
        '华为云镜像'           = 'mirrors.huaweicloud.com'
        'GitHub'               = 'github.com'
        'codeload.github.com'  = 'codeload.github.com'
    }
    foreach ($label in $hostChecks.Keys) {
        $target = $hostChecks[$label]
        Invoke-Check -Category '网络' -Name ($label + ' 443 可达') -Body {
            if (-not (Test-TcpEndpoint -TargetHost $target -Port 443 -TimeoutMs 6000)) { throw ('TCP 连接失败：' + $target + ':443') }
            return ($target + ':443 TCP 可达')
        } -FailHint '检查网络/防火墙'
    }

    Invoke-OptionalCheck -Category '网络' -Name 'Minecraft 资源站可达（runClient 首跑资源）' -Body {
        if (-not (Test-TcpEndpoint -TargetHost 'resources.download.minecraft.net' -Port 443 -TimeoutMs 6000)) {
            throw 'TCP 连接失败：resources.download.minecraft.net:443'
        }
        return 'resources.download.minecraft.net:443 TCP 可达'
    } -WarnHint 'assets 已缓存时可离线运行，但首次运行 runClient 可能需下载'
}

# ================= 6) 硬件与环境 =================
Invoke-Check -Category '硬件环境' -Name 'E 盘剩余空间 > 10GB' -Body {
    $disk = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='E:'" -ErrorAction Stop
    if ($null -eq $disk) { throw '未找到 E: 盘' }
    $freeGB = [math]::Round($disk.FreeSpace / 1GB, 1)
    $sizeGB = [math]::Round($disk.Size / 1GB, 1)
    if ($freeGB -lt 10) { throw ('E: 剩余 ' + $freeGB + ' GB，低于 10GB') }
    return ('{0} GB 可用 / {1} GB' -f $freeGB, $sizeGB)
}

Invoke-Check -Category '硬件环境' -Name '端口 25580 空闲（mod 默认端口）' -Body {
    $holders = @()
    try { $holders = @(Get-NetTCPConnection -LocalPort 25580 -State Listen -ErrorAction SilentlyContinue) }
    catch { $holders = @() }
    if ($holders.Count -gt 0) {
        throw ('端口 25580 已被监听（PID=' + $holders[0].OwningProcess + '）')
    }
    return '未被监听（可安全绑定）'
} -FailHint '关闭占用进程或让 mod 改用其他端口'

$gpus = @(Get-CimInstance Win32_VideoController -ErrorAction SilentlyContinue)
foreach ($gpu in $gpus) {
    Add-InfoResult -Category '硬件环境' -Name ('GPU: ' + $gpu.Name) -Detail ('驱动 ' + $gpu.DriverVersion + '，日期 ' + $gpu.DriverDate)
}
$cs = Get-CimInstance Win32_ComputerSystem -ErrorAction SilentlyContinue
if ($cs) {
    Add-InfoResult -Category '硬件环境' -Name '内存' -Detail ([math]::Round($cs.TotalPhysicalMemory / 1GB, 1).ToString() + ' GB')
}
$cpus = @(Get-CimInstance Win32_Processor -ErrorAction SilentlyContinue)
foreach ($cpu in $cpus) {
    Add-InfoResult -Category '硬件环境' -Name 'CPU' -Detail ($cpu.Name.Trim() + '，' + $cpu.NumberOfCores + ' 核 / ' + $cpu.NumberOfLogicalProcessors + ' 线程')
}
try {
    $diskE = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='E:'" -ErrorAction SilentlyContinue
    if ($diskE) { Add-InfoResult -Category '硬件环境' -Name 'E 盘' -Detail ([math]::Round($diskE.FreeSpace / 1GB, 1).ToString() + ' GB 可用 / ' + [math]::Round($diskE.Size / 1GB, 1) + ' GB') }
}
catch { }

# ================= 汇总 =================
$swAll.Stop()
$all = @($script:Results)
$passCount = @($all | Where-Object { $_.Status -eq 'PASS' }).Count
$failCount = @($all | Where-Object { $_.Status -eq 'FAIL' }).Count
$warnCount = @($all | Where-Object { $_.Status -eq 'WARN' }).Count
$skipCount = @($all | Where-Object { $_.Status -eq 'SKIP' }).Count
$infoCount = @($all | Where-Object { $_.Status -eq 'INFO' }).Count

Write-Host ''
Write-Host '=== 校验结果汇总表（PASS/FAIL） ===' -ForegroundColor Cyan
$tableRows = @($all | Where-Object { $_.Status -ne 'INFO' } | Select-Object Category, Name, Status, Seconds, Detail)
$tableText = ($tableRows | Format-Table -AutoSize | Out-String -Width 320)
Write-Host $tableText

Write-Host ''
Write-Host '=== 硬件信息（INFO） ===' -ForegroundColor Cyan
@($all | Where-Object { $_.Status -eq 'INFO' }) | ForEach-Object { Write-Host ('  - ' + $_.Name + ': ' + $_.Detail) -ForegroundColor Gray }

Write-Host ''
$verdict = if ($failCount -eq 0) { 'PASS' } else { 'FAIL' }
Write-Host ("总计: PASS={0} FAIL={1} WARN={2} SKIP={3} INFO={4}；总耗时 {5}s；结论: {6}" -f $passCount, $failCount, $warnCount, $skipCount, $infoCount, [math]::Round($swAll.Elapsed.TotalSeconds, 1), $verdict) -ForegroundColor $(if ($failCount -eq 0) { 'Green' } else { 'Red' })

if ($MarkdownOut) {
    $md = New-Object System.Text.StringBuilder
    [void]$md.AppendLine('# verify-env 校验结果（' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '）')
    [void]$md.AppendLine('')
    [void]$md.AppendLine('| 类别 | 检查项 | 结果 | 耗时(s) | 详情 |')
    [void]$md.AppendLine('| --- | --- | --- | --- | --- |')
    foreach ($row in $all) {
        $detail = ([string]$row.Detail) -replace '\|', '\|' -replace "`r?`n", ' '
        [void]$md.AppendLine('| ' + $row.Category + ' | ' + $row.Name + ' | ' + $row.Status + ' | ' + $row.Seconds + ' | ' + $detail + ' |')
    }
    [void]$md.AppendLine('')
    [void]$md.AppendLine(('总计: PASS=' + $passCount + ' FAIL=' + $failCount + ' WARN=' + $warnCount + ' SKIP=' + $skipCount + ' INFO=' + $infoCount + '；结论: ' + $verdict))
    $mdDir = Split-Path -Parent $MarkdownOut
    if ($mdDir -and -not (Test-Path -LiteralPath $mdDir)) { New-Item -ItemType Directory -Path $mdDir -Force | Out-Null }
    [System.IO.File]::WriteAllText($MarkdownOut, $md.ToString(), (New-Object System.Text.UTF8Encoding($false)))
    Write-Host ('Markdown 结果已写入: ' + $MarkdownOut) -ForegroundColor Gray
}

if ($failCount -gt 0) {
    Write-Host '环境自检结果: FAIL' -ForegroundColor Red
    exit 1
}
Write-Host '环境自检结果: PASS' -ForegroundColor Green
exit 0