#Requires -Version 5.1
<#
.SYNOPSIS
    捕获接口（captureapi）开发环境激活脚本：定位并固定 JDK 17，设置 JAVA_HOME / PATH。

.DESCRIPTION
    本脚本不依赖调用者当前 shell 的 JAVA_HOME / PATH，自行按「固定路径 → 回退路径」解析 JDK 17：
      1) 参数 -JdkHome 指定目录
      2) E:\Tools\jdk17（本机固定路径，由 java-runtime-beta 复制而来）
      3) %APPDATA%\.minecraft\runtime\java-runtime-beta（Mojang 启动器内置 JDK 回退）
    找到后验证 java -version / javac -version，并在当前进程内设置 JAVA_HOME 与 PATH。

    用法一（当前会话生效，推荐）：
        . .\scripts\dev-env.ps1
        java -version

    用法二（写入用户级环境变量，新开终端生效；只需执行一次）：
        .\scripts\dev-env.ps1 -Persist

    用法三（其他脚本内部调用，不依赖外部环境）：
        & .\scripts\dev-env.ps1

.PARAMETER JdkHome
    指定 JDK 17 根目录（含 bin\java.exe 与 bin\javac.exe）。

.PARAMETER Persist
    将 JAVA_HOME 与 PATH 写入「用户级」环境变量（HKCU\Environment），不修改系统级变量。

.PARAMETER Quiet
    仅输出关键结果（INFO 级别日志静默）。

.EXAMPLE
    . .\scripts\dev-env.ps1
    java -version

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\dev-env.ps1 -Persist
#>
[CmdletBinding()]
param(
    [string]$JdkHome = '',
    [switch]$Persist,
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'

# ---- 工程根目录：脚本固定位于 <root>\scripts\，自动解析，不依赖调用者当前目录 ----
$ScriptDir = $PSScriptRoot
$ProjectRoot = Split-Path -Parent $ScriptDir
if (-not (Test-Path -LiteralPath (Join-Path $ProjectRoot 'gradlew.bat'))) {
    throw "无法从脚本目录解析工程根目录（期望包含 gradlew.bat）：$ProjectRoot"
}

# ---- 常量：固定路径与基准版本 ----
$PreferredJdk = 'E:\Tools\jdk17'
$FallbackJdks = @(
    (Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-beta'),
    (Join-Path $env:USERPROFILE '.minecraft\runtime\java-runtime-beta')
)
$ExpectedJavaVersion = '17.0.15'
$DotSourced = ($MyInvocation.InvocationName -eq '.')

# ---- 输出函数 ----
function Write-EnvLog {
    param(
        [string]$Message,
        [ValidateSet('INFO', 'PASS', 'WARN', 'FAIL')][string]$Level = 'INFO'
    )
    if ($Quiet -and $Level -eq 'INFO') { return }
    $color = @{ INFO = 'Gray'; PASS = 'Green'; WARN = 'Yellow'; FAIL = 'Red' }[$Level]
    Write-Host ("[ENV/{0}] {1}" -f $Level, $Message) -ForegroundColor $color
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
}function Test-JdkHome {
    <# 校验候选目录是可用 JDK 17；返回 @{ Path; JavaVersion; JavaVersionText; JavacVersion } 或 $null。 #>
    param([string]$Candidate)
    if ([string]::IsNullOrWhiteSpace($Candidate)) { return $null }
    if (-not (Test-Path -LiteralPath $Candidate)) { return $null }
    $javaExe = Join-Path $Candidate 'bin\java.exe'
    $javacExe = Join-Path $Candidate 'bin\javac.exe'
    if (-not (Test-Path -LiteralPath $javaExe)) { return $null }
    if (-not (Test-Path -LiteralPath $javacExe)) { return $null }
    $javaRes = Invoke-NativeCapture -FilePath $javaExe -Arguments '-version' -TimeoutSec 60
    if ($javaRes.ExitCode -ne 0) { return $null }
    if ($javaRes.Output -notmatch 'version "17\.') { return $null }
    $javaVer = 'unknown'
    if ($javaRes.Output -match 'version "([^"]+)"') { $javaVer = $Matches[1] }
    $javacRes = Invoke-NativeCapture -FilePath $javacExe -Arguments '-version' -TimeoutSec 60
    if ($javacRes.ExitCode -ne 0) { return $null }
    $javacVer = $javacRes.Output.Trim()
    $full = (Get-Item -LiteralPath $Candidate).FullName
    return [pscustomobject]@{
        Path             = $full
        JavaVersion      = $javaVer
        JavaVersionText  = $javaRes.Output.Trim()
        JavacVersion     = $javacVer
    }
}

function Set-Prospect {
    <# 返回候选 JDK 路径列表（去重、保持优先级）。 #>
    $list = New-Object System.Collections.ArrayList
    if (-not [string]::IsNullOrWhiteSpace($JdkHome)) { [void]$list.Add($JdkHome) }
    [void]$list.Add($PreferredJdk)
    foreach ($f in $FallbackJdks) { [void]$list.Add($f) }
    $seen = @{}
    $result = New-Object System.Collections.ArrayList
    foreach ($p in $list) {
        if ([string]::IsNullOrWhiteSpace($p)) { continue }
        $key = $p.TrimEnd('\').ToLowerInvariant()
        if ($seen.ContainsKey($key)) { continue }
        $seen[$key] = $true
        [void]$result.Add($p)
    }
    return $result
}

function Add-UserPathVariable {
    <# 把目录追加到用户级 PATH（保留原注册表值类型 REG_SZ/REG_EXPAND_SZ）；返回是否新增。 #>
    param([string]$Directory)
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment', $true)
    if ($null -eq $key) { throw '无法打开 HKCU\Environment 注册表键' }
    try {
        $kind = [Microsoft.Win32.RegistryValueKind]::ExpandString
        try { $kind = $key.GetValueKind('Path') } catch { }
        $raw = $key.GetValue('Path', $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
        if ($null -eq $raw) { $raw = '' }
        $parts = @($raw -split ';' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        foreach ($p in $parts) {
            if ($p.TrimEnd('\') -eq $Directory.TrimEnd('\')) { return $false }
        }
        $newRaw = (@($parts) + $Directory) -join ';'
        $key.SetValue('Path', $newRaw, $kind)
        return $true
    }
    finally {
        $key.Close()
    }
}

# ---- 主流程 ----
Write-Host ''
Write-Host '=== 捕获接口开发环境（JDK 17 固定） ===' -ForegroundColor Cyan
Write-EnvLog ("工程根目录: " + $ProjectRoot)

$jdk = $null
$checked = New-Object System.Collections.ArrayList
foreach ($candidate in (Set-Prospect)) {
    $info = Test-JdkHome -Candidate $candidate
    if ($null -ne $info) {
        $jdk = $info
        break
    }
    [void]$checked.Add($candidate)
}
if ($null -eq $jdk) {
    $checkedText = ($checked | ForEach-Object { '  - ' + $_ }) -join "`n"
    throw ("未找到可用的 JDK 17（需要 bin\java.exe 与 bin\javac.exe，且 java -version 为 17.x）。`n已检查候选：`n" + $checkedText + "`n请确认 E:\Tools\jdk17 存在，或用 -JdkHome 指定 JDK17 目录。")
}

# 设置当前进程环境（不依赖外部环境）
$binDir = Join-Path $jdk.Path 'bin'
$oldJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $jdk.Path
$pathParts = @()
if ($env:PATH) { $pathParts = @($env:PATH -split ';' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) }
$pathParts = @($pathParts | Where-Object { $_.TrimEnd('\') -ne $binDir.TrimEnd('\') })
$env:PATH = $binDir + ';' + ($pathParts -join ';')

Write-EnvLog ("JDK 固定路径: " + $jdk.Path) PASS
$javaFirstLine = ($jdk.JavaVersionText -split "`r?`n" | Where-Object { $_ -match 'version' } | Select-Object -First 1)
if (-not $javaFirstLine) { $javaFirstLine = $jdk.JavaVersionText }
Write-EnvLog ("java -version: " + $javaFirstLine.Trim()) PASS
Write-EnvLog ("javac -version: " + $jdk.JavacVersion) PASS
Write-EnvLog ("JAVA_HOME = " + $env:JAVA_HOME)
Write-EnvLog ("PATH 前部 = " + $binDir + ";...")
if (-not [string]::IsNullOrWhiteSpace($oldJavaHome) -and $oldJavaHome -ne $jdk.Path) {
    Write-EnvLog ("已覆盖外部 JAVA_HOME（原值: " + $oldJavaHome + "）") WARN
}
if ($jdk.JavaVersion -ne $ExpectedJavaVersion) {
    Write-EnvLog ("JDK 版本与基线 " + $ExpectedJavaVersion + " 不一致（当前 " + $jdk.JavaVersion + "），请核对 docs\开发环境基线.md") WARN
}

# 用户级持久化（可选）
if ($Persist) {
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk.Path, 'User')
    Write-EnvLog ("已写入用户级 JAVA_HOME = " + $jdk.Path) PASS
    $added = Add-UserPathVariable -Directory $binDir
    if ($added) {
        Write-EnvLog ("已追加用户级 PATH: " + $binDir) PASS
    }
    else {
        Write-EnvLog ("用户级 PATH 已包含: " + $binDir) PASS
    }
    Write-EnvLog '提示：已打开的终端不会自动刷新；新开终端（或重新登录）后可直接使用 java/javac。' INFO
}

Write-EnvLog '当前进程已就绪：可直接运行 gradlew.bat build / gradlew.bat runClient，或使用 scripts\ 下封装脚本。'
if (-not $DotSourced) {
    Write-EnvLog '注意：本脚本以独立进程方式运行，环境变量仅在其子进程有效；要在当前会话生效请用点源：. .\scripts\dev-env.ps1' WARN
}
