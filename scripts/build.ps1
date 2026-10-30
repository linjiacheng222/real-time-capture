#Requires -Version 5.1
<#
.SYNOPSIS
    捕获接口（captureapi）构建脚本：调用 gradlew build/clean build，并校验产物与 mods.toml 元数据。

.DESCRIPTION
    特性：
      - 自行解析并固定 JDK 17（E:\Tools\jdk17 → Mojang 回退），不依赖外部 JAVA_HOME/PATH；
      - 默认执行 `clean build`；-SkipClean 时执行增量 `build`；
      - -Offline 时附加 --offline，用于离线可行性验证；
      - 校验产物 build\libs\<modId>-<modVersion>.jar：存在、非空、包含主类；
      - 解包校验 META-INF/mods.toml：UTF-8 无乱码（中文元数据）、loaderVersion=[47,)、4 项依赖及其版本区间；
      - 控制台输出写入 build\reports\env\build-<时间戳>.log，便于留证与排查；
      - 任一步失败即以非零退出码结束并给出明确原因。

.PARAMETER SkipClean
    跳过 clean，执行增量构建（`gradlew build`）。

.PARAMETER Offline
    附加 --offline，测试离线构建可行性。

.PARAMETER JdkHome
    指定 JDK 17 根目录（默认 E:\Tools\jdk17）。

.PARAMETER TimeoutSec
    构建超时秒数（默认 900）。

.EXAMPLE
    .\scripts\build.ps1
    .\scripts\build.ps1 -SkipClean
    .\scripts\build.ps1 -Offline -SkipClean
#>
[CmdletBinding()]
param(
    [switch]$SkipClean,
    [switch]$Offline,
    [string]$JdkHome = '',
    [int]$TimeoutSec = 900
)

$ErrorActionPreference = 'Stop'

# ================= 通用部分：路径 / JDK / 原生命令 =================
$ScriptDir = $PSScriptRoot
$ProjectRoot = Split-Path -Parent $ScriptDir
if (-not (Test-Path -LiteralPath (Join-Path $ProjectRoot 'gradlew.bat'))) {
    throw "无法从脚本目录解析工程根目录（期望包含 gradlew.bat）：$ProjectRoot"
}
$PreferredJdk = 'E:\Tools\jdk17'
$FallbackJdks = @(
    (Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-beta'),
    (Join-Path $env:USERPROFILE '.minecraft\runtime\java-runtime-beta')
)
$GradlewBat = Join-Path $ProjectRoot 'gradlew.bat'

function Write-StepLog {
    param([string]$Message, [ValidateSet('INFO', 'PASS', 'WARN', 'FAIL', 'STEP')][string]$Level = 'INFO')
    $color = @{ INFO = 'Gray'; PASS = 'Green'; WARN = 'Yellow'; FAIL = 'Red'; STEP = 'Cyan' }[$Level]
    Write-Host ("[BUILD/{0}] {1}" -f $Level, $Message) -ForegroundColor $color
}

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
}function Resolve-Jdk17 {
    $candidates = New-Object System.Collections.ArrayList
    if (-not [string]::IsNullOrWhiteSpace($JdkHome)) { [void]$candidates.Add($JdkHome) }
    [void]$candidates.Add($PreferredJdk)
    foreach ($f in $FallbackJdks) { [void]$candidates.Add($f) }
    $checked = New-Object System.Collections.ArrayList
    foreach ($candidate in $candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        $javaExe = Join-Path $candidate 'bin\java.exe'
        $javacExe = Join-Path $candidate 'bin\javac.exe'
        if (-not (Test-Path -LiteralPath $javaExe) -or -not (Test-Path -LiteralPath $javacExe)) {
            [void]$checked.Add($candidate); continue
        }
        $res = Invoke-NativeCapture -FilePath $javaExe -Arguments '-version' -TimeoutSec 60
        if ($res.ExitCode -ne 0 -or $res.Output -notmatch 'version "17\.') {
            [void]$checked.Add($candidate); continue
        }
        return (Get-Item -LiteralPath $candidate).FullName
    }
    throw ('未找到可用 JDK 17。已检查：' + ($checked -join '; ') + '；请检查 E:\Tools\jdk17 或使用 -JdkHome 指定。')
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

function Get-GradleProperty {
    param([string]$Name, [string]$PropertiesPath)
    if (-not (Test-Path -LiteralPath $PropertiesPath)) { return $null }
    foreach ($line in [System.IO.File]::ReadAllLines($PropertiesPath)) {
        if ($line -match ('^\s*' + [regex]::Escape($Name) + '\s*=\s*(.*)$')) {
            return $Matches[1].Trim()
        }
    }
    return $null
}

function ConvertFrom-JavaEscapes {
    <# 把 .properties 中的 \uXXXX 转义还原为字符（Gradle 读取 properties 时同样会还原）。 #>
    param([string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return $Text }
    $sb = New-Object System.Text.StringBuilder
    $idx = 0
    foreach ($m in [regex]::Matches($Text, '\\u([0-9a-fA-F]{4})')) {
        [void]$sb.Append($Text.Substring($idx, $m.Index - $idx))
        [void]$sb.Append([char][Convert]::ToInt32($m.Groups[1].Value, 16))
        $idx = $m.Index + $m.Length
    }
    [void]$sb.Append($Text.Substring($idx))
    return $sb.ToString()
}

function Get-JarEntryText {
    <# 读取 jar 内指定条目的文本（严格 UTF-8 解码；非 UTF-8 或缺失返回 $null）。 #>
    param([string]$JarPath, [string]$EntryName)
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
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
$modeText = if ($SkipClean) { '增量 build' } else { 'clean build' }
if ($Offline) { $modeText += ' + --offline' }
$swTotal = [System.Diagnostics.Stopwatch]::StartNew()
$script:checks = New-Object System.Collections.ArrayList

function Add-Check {
    param([string]$Name, [bool]$Ok, [string]$Detail)
    $status = if ($Ok) { 'PASS' } else { 'FAIL' }
    [void]$script:checks.Add([pscustomobject]@{ Name = $Name; Status = $status; Detail = $Detail })
    $color = if ($Ok) { 'Green' } else { 'Red' }
    Write-Host ("[{0}] {1} — {2}" -f $status, $Name, $Detail) -ForegroundColor $color
}

Write-Host ''
Write-Host '=== 捕获接口构建与产物校验 ===' -ForegroundColor Cyan
Write-StepLog ("工程根目录: " + $ProjectRoot) STEP
Write-StepLog ("构建模式: " + $modeText) STEP

# 1) JDK
$jdk = Resolve-Jdk17
Set-JdkEnvironment -JdkPath $jdk
$javaVerRes = Invoke-NativeCapture -FilePath (Join-Path $jdk 'bin\java.exe') -Arguments '-version' -TimeoutSec 60
$javaVerLine = ($javaVerRes.Output -split "`r?`n" | Where-Object { $_ -match 'version' } | Select-Object -First 1)
Write-StepLog ("JDK: " + $jdk + " | " + "$javaVerLine".Trim()) PASS

# 2) 元数据期望值
$gradleProps = Join-Path $ProjectRoot 'gradle.properties'
$modId = Get-GradleProperty -Name 'modId' -PropertiesPath $gradleProps
$modVersion = Get-GradleProperty -Name 'modVersion' -PropertiesPath $gradleProps
$minecraftVersion = Get-GradleProperty -Name 'minecraftVersion' -PropertiesPath $gradleProps
$minForgeVersion = Get-GradleProperty -Name 'minForgeVersion' -PropertiesPath $gradleProps
$devForgeVersion = Get-GradleProperty -Name 'devForgeVersion' -PropertiesPath $gradleProps
$embeddiumVersion = Get-GradleProperty -Name 'embeddiumVersion' -PropertiesPath $gradleProps
$modernfixVersion = Get-GradleProperty -Name 'modernfixVersion' -PropertiesPath $gradleProps
$modName = ConvertFrom-JavaEscapes (Get-GradleProperty -Name 'modName' -PropertiesPath $gradleProps)
$modAuthor = ConvertFrom-JavaEscapes (Get-GradleProperty -Name 'modAuthor' -PropertiesPath $gradleProps)
if (-not $modId -or -not $modVersion) { throw "gradle.properties 缺少 modId/modVersion：$gradleProps" }
Write-StepLog ("元数据期望: modId=$modId modVersion=$modVersion modName=$modName minecraft=$minecraftVersion minForge=$minForgeVersion devForge=$devForgeVersion") STEP

# 3) 构建
$tasks = if ($SkipClean) { 'build' } else { 'clean build' }
$argLine = $tasks + ' --console=plain --stacktrace'
if ($Offline) { $argLine += ' --offline' }
Write-StepLog ("执行: gradlew.bat " + $argLine) STEP
$swBuild = [System.Diagnostics.Stopwatch]::StartNew()
$buildResult = Invoke-NativeCapture -FilePath $GradlewBat -Arguments $argLine -TimeoutSec $TimeoutSec -WorkingDirectory $ProjectRoot
$swBuild.Stop()
$buildLogDir = Join-Path $ProjectRoot 'build\reports\env'
New-Item -ItemType Directory -Path $buildLogDir -Force | Out-Null
$buildLogPath = Join-Path $buildLogDir ('build-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.log')
[System.IO.File]::WriteAllText($buildLogPath, $buildResult.Output, (New-Object System.Text.UTF8Encoding($false)))
Write-StepLog ("gradle 退出码: " + $buildResult.ExitCode + "；耗时: " + [math]::Round($swBuild.Elapsed.TotalSeconds, 1) + "s；完整日志: " + $buildLogPath) STEP
Write-Host '--- gradle 输出（末尾 30 行）---' -ForegroundColor DarkGray
($buildResult.Output -split "`r?`n" | Select-Object -Last 30) | ForEach-Object { Write-Host ('  ' + $_) -ForegroundColor DarkGray }
Write-Host '--- 输出结束 ---' -ForegroundColor DarkGray

$buildOk = ($buildResult.ExitCode -eq 0) -and ($buildResult.Output -match 'BUILD SUCCESSFUL')
Add-Check -Name 'Gradle 构建成功（BUILD SUCCESSFUL）' -Ok $buildOk -Detail ("exit=" + $buildResult.ExitCode + "，耗时 " + [math]::Round($swBuild.Elapsed.TotalSeconds, 1) + "s")
if (-not $buildOk) {
    Write-StepLog '构建失败：请查看上方 gradle 输出与完整日志。' FAIL
    Write-Host ''
    Write-Host ('构建结果: FAIL（共 ' + $script:checks.Count + ' 项校验，' + (@($script:checks | Where-Object { $_.Status -eq 'FAIL' }).Count) + ' 项失败）') -ForegroundColor Red
    exit 1
}

# 4) 产物校验
$expectedJar = Join-Path $ProjectRoot ('build\libs\' + $modId + '-' + $modVersion + '.jar')
$jarOk = (Test-Path -LiteralPath $expectedJar) -and ((Get-Item -LiteralPath $expectedJar).Length -gt 0)
$jarSize = if (Test-Path -LiteralPath $expectedJar) { (Get-Item -LiteralPath $expectedJar).Length } else { 0 }
Add-Check -Name '构建产物存在且非空' -Ok $jarOk -Detail ($expectedJar + '（' + $jarSize + ' bytes）')
if (-not $jarOk) {
    Write-StepLog '产物缺失，终止元数据校验。' FAIL
    Write-Host ''
    Write-Host '构建结果: FAIL' -ForegroundColor Red
    exit 1
}

# 5) 解包检查 mods.toml
$toml = $null
$tomlError = ''
try {
    $toml = Get-JarEntryText -JarPath $expectedJar -EntryName 'META-INF/mods.toml'
}
catch {
    $tomlError = $_.Exception.Message
}
$tomlOk = ($null -ne $toml) -and ($toml.Length -gt 0)
Add-Check -Name 'jar 内含 META-INF/mods.toml（严格 UTF-8 可解码）' -Ok $tomlOk -Detail $(if ($tomlOk) { '长度 ' + $toml.Length + ' 字符' } else { '缺失或非 UTF-8：' + $tomlError })

if ($tomlOk) {
    Add-Check -Name ('中文元数据无乱码（displayName 含 "' + $modName + '"）') -Ok ($toml -match [regex]::Escape('displayName = "' + $modName + '"')) -Detail 'processResources filteringCharset=UTF-8 生效'
    Add-Check -Name ('作者中文元数据（authors 含 "' + $modAuthor + '"）') -Ok ($toml -match [regex]::Escape('authors = "' + $modAuthor + '"')) -Detail 'authors 字段'
    Add-Check -Name 'loaderVersion = "[47,)"' -Ok ($toml -match 'loaderVersion\s*=\s*"\[47,\)"') -Detail 'JavaFML 47+'
    Add-Check -Name ('modId / version = ' + $modId + ' / ' + $modVersion) -Ok (($toml -match ('modId\s*=\s*"' + [regex]::Escape($modId) + '"')) -and ($toml -match ('version\s*=\s*"' + [regex]::Escape($modVersion) + '"'))) -Detail 'mods.toml 变量替换结果'
    $depCount = ([regex]::Matches($toml, '\[\[dependencies\.')).Count
    Add-Check -Name '依赖声明 4 项' -Ok ($depCount -eq 4) -Detail ('实际 ' + $depCount + ' 项：forge / minecraft / embeddium / modernfix')
    Add-Check -Name 'forge 依赖版本区间 = [47.3.0,)' -Ok ($toml -match 'versionRange\s*=\s*"\[47\.3\.0,\)"') -Detail 'minForgeVersion 控制'
    Add-Check -Name 'minecraft 依赖版本区间 = [1.20.1]' -Ok ($toml -match 'versionRange\s*=\s*"\[1\.20\.1\]"') -Detail '精确版本'
    Add-Check -Name 'embeddium 依赖版本区间 = [0.3.31,)' -Ok ($toml -match 'versionRange\s*=\s*"\[0\.3\.31,\)"') -Detail 'mandatory（运行时校验）'
    Add-Check -Name 'modernfix 依赖版本区间 = [5.27.89,)' -Ok ($toml -match 'versionRange\s*=\s*"\[5\.27\.89,\)"') -Detail 'mandatory（运行时校验）'
    Add-Check -Name '四个依赖 modId 齐全' -Ok (($toml -match 'modId\s*=\s*"forge"') -and ($toml -match 'modId\s*=\s*"minecraft"') -and ($toml -match 'modId\s*=\s*"embeddium"') -and ($toml -match 'modId\s*=\s*"modernfix"')) -Detail 'forge/minecraft/embeddium/modernfix'
    $loaderOk = ($toml -match 'modLoader\s*=\s*"javafml"')
    Add-Check -Name 'modLoader = "javafml"' -Ok $loaderOk -Detail 'Forge 装载器类型'
}
else {
    Add-Check -Name 'mods.toml 元数据校验' -Ok $false -Detail '无法读取 mods.toml'
}

# 6) 主类存在（class 是二进制，仅检查 zip 条目存在性，不做文本解码）
$classOk = $false
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
    $zip2 = [System.IO.Compression.ZipFile]::OpenRead($expectedJar)
    try {
        $classOk = ($null -ne $zip2.GetEntry('com/captureapi/CaptureInterface.class'))
    }
    finally { $zip2.Dispose() }
}
catch { $classOk = $false }
Add-Check -Name '主类 com/captureapi/CaptureInterface.class 已打包' -Ok $classOk -Detail '空壳主类（业务代码未开始，属当前预期）'

# 7) 汇总
$swTotal.Stop()
$failCount = @($script:checks | Where-Object { $_.Status -eq 'FAIL' }).Count
$passCount = @($script:checks | Where-Object { $_.Status -eq 'PASS' }).Count
Write-Host ''
Write-Host '=== 校验汇总 ===' -ForegroundColor Cyan
$script:checks | Format-Table Name, Status, Detail -AutoSize | Out-String -Width 300 | Write-Host
Write-Host ("总耗时: " + [math]::Round($swTotal.Elapsed.TotalSeconds, 1) + "s；PASS=" + $passCount + " FAIL=" + $failCount) -ForegroundColor $(if ($failCount -eq 0) { 'Green' } else { 'Red' })
Write-Host ("构建日志: " + $buildLogPath)
Write-Host ("产物: " + $expectedJar)
if ($failCount -gt 0) {
    Write-Host '构建结果: FAIL' -ForegroundColor Red
    exit 1
}
Write-Host '构建结果: PASS' -ForegroundColor Green
exit 0
