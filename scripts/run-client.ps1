#Requires -Version 5.1
<#
.SYNOPSIS
    捕获接口（captureapi）开发客户端运行脚本：启动 gradlew runClient，轮询日志直至到达主菜单，留证后清理进程树。

.DESCRIPTION
    流程：
      1) 自行解析并固定 JDK 17（不依赖外部 JAVA_HOME/PATH）；
      2) 归档旧 run\logs\latest.log，启动 `gradlew.bat runClient`（控制台输出重定向到 run\client-stdout-<时间戳>.log）；
      3) 每 PollSeconds 秒轮询 run\logs\latest.log（以共享读方式打开，游戏写入中也可读），记录以下标记：
         Forge 47.4.23 启动、captureapi/Embeddium/ModernFix 被加载、GL 后端（Backend library）、
         OpenAL 初始化、主菜单（Sound engine started）；
      4) 检测 run\crash-reports 新增、Mixin 崩溃/致命错误、进程提前退出、超时；
      5) 结束后（含异常中断）taskkill /PID <pid> /T /F 清理进程树，并确认无残留 java 进程；
      6) 输出标记表、耗时、日志路径与结论摘要；成功（到达主菜单且无崩溃/残留）退出码 0，否则 1。

    注意：GUI 窗口出现在桌面属正常现象，本脚本不会点击/操作游戏窗口；“进入世界”无法自动验收，需人工确认。
    首次运行可能下载/解压 Minecraft 资源，耗时较长（默认超时 15 分钟）。

.PARAMETER TimeoutMinutes
    等待主菜单的超时分钟数（默认 15）。

.PARAMETER PollSeconds
    日志轮询间隔秒数（默认 3）。

.PARAMETER GradleArgs
    追加给 gradlew 的额外参数（例如 "--offline"）。

.PARAMETER NoKill
    不在结束时清理进程（仅排查用；默认必须清理，且会校验无残留 java）。

.PARAMETER JdkHome
    指定 JDK 17 根目录（默认 E:\Tools\jdk17）。

.EXAMPLE
    .\scripts\run-client.ps1
    .\scripts\run-client.ps1 -TimeoutMinutes 20
#>
[CmdletBinding()]
param(
    [int]$TimeoutMinutes = 15,
    [int]$PollSeconds = 3,
    [string]$GradleArgs = '',
    [switch]$NoKill,
    [string]$JdkHome = ''
)

$ErrorActionPreference = 'Stop'

# ================= 通用部分 =================
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

function Write-RunLog {
    param([string]$Message, [ValidateSet('INFO', 'PASS', 'WARN', 'FAIL', 'STEP')][string]$Level = 'INFO')
    $color = @{ INFO = 'Gray'; PASS = 'Green'; WARN = 'Yellow'; FAIL = 'Red'; STEP = 'Cyan' }[$Level]
    Write-Host ("[RUN/{0}] [{1}] {2}" -f $Level, (Get-Date -Format 'HH:mm:ss'), $Message) -ForegroundColor $color
}

function ConvertFrom-BytesAuto {
    <# 按字节自动识别 UTF-8 / 系统编码（GBK）解码，避免中文乱码。 #>
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
    <# 读取可能正被其它进程写入的文本文件（以 FileShare.ReadWrite 打开）；失败返回空串，不抛异常。 #>
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return '' }
    try {
        $fs = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        try {
            $ms = New-Object System.IO.MemoryStream
            try {
                $fs.CopyTo($ms)
                return (ConvertFrom-BytesAuto -Bytes $ms.ToArray())
            }
            finally { $ms.Dispose() }
        }
        finally { $fs.Dispose() }
    }
    catch {
        return ''
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

# ================= 主流程 =================
$swRun = [System.Diagnostics.Stopwatch]::StartNew()
Write-Host ''
Write-Host '=== 捕获接口开发客户端（runClient）运行与验证 ===' -ForegroundColor Cyan
Write-RunLog ("工程根目录: " + $ProjectRoot) STEP

$jdk = Resolve-Jdk17
Set-JdkEnvironment -JdkPath $jdk
Write-RunLog ("JDK: " + $jdk) PASS

$RunDir = Join-Path $ProjectRoot 'run'
$LogsDir = Join-Path $RunDir 'logs'
$CrashDir = Join-Path $RunDir 'crash-reports'
New-Item -ItemType Directory -Path $RunDir -Force | Out-Null
New-Item -ItemType Directory -Path $LogsDir -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$latestLog = Join-Path $LogsDir 'latest.log'
$debugLog = Join-Path $LogsDir 'debug.log'
$stdoutLog = Join-Path $RunDir ('client-stdout-' + $stamp + '.log')

# 归档旧日志（保留证据，不删除历史）
$prevLogArchive = ''
if (Test-Path -LiteralPath $latestLog) {
    $prevLogArchive = Join-Path $LogsDir ('latest-prev-' + $stamp + '.log')
    Move-Item -LiteralPath $latestLog -Destination $prevLogArchive -Force
    Write-RunLog ("已归档旧 latest.log -> " + $prevLogArchive) INFO
}

$prevDebugArchive = ''
if (Test-Path -LiteralPath $debugLog) {
    $prevDebugArchive = Join-Path $LogsDir ('debug-prev-' + $stamp + '.log')
    Move-Item -LiteralPath $debugLog -Destination $prevDebugArchive -Force
    Write-RunLog ("已归档旧 debug.log -> " + $prevDebugArchive) INFO
}
# 记录启动前 crash report 快照
$beforeCrashes = @{}
if (Test-Path -LiteralPath $CrashDir) {
    foreach ($f in @(Get-ChildItem -LiteralPath $CrashDir -File -ErrorAction SilentlyContinue)) {
        $beforeCrashes[$f.Name] = $true
    }
}

# 标记定义（Forge 47.4.23 / 模组加载 / GL / 声音 / 主菜单）
$script:Markers = [ordered]@{
    'forge-version' = [pscustomobject]@{ Label = 'Forge 47.4.23 启动'; Pattern = '(fml\.forgeVersion, 47\.4\.23|Forge mod loading, version 47\.4\.23)'; Required = $true; Found = $false; Line = '' }
    'captureapi'    = [pscustomobject]@{ Label = 'captureapi 本体加载'; Pattern = '(?i)captureapi'; Required = $true; Found = $false; Line = '' }
    'embeddium'     = [pscustomobject]@{ Label = 'Embeddium 加载'; Pattern = '(?i)Embeddium'; Required = $true; Found = $false; Line = '' }
    'modernfix'     = [pscustomobject]@{ Label = 'ModernFix 加载'; Pattern = '(?i)ModernFix'; Required = $true; Found = $false; Line = '' }
    'gl-backend'    = [pscustomobject]@{ Label = 'GL 后端初始化（Backend library）'; Pattern = 'Backend library:'; Required = $true; Found = $false; Line = '' }
    'openal'        = [pscustomobject]@{ Label = 'OpenAL 初始化'; Pattern = 'OpenAL initialized'; Required = $true; Found = $false; Line = '' }
    'main-menu'     = [pscustomobject]@{ Label = '主菜单（Sound engine started）'; Pattern = 'Sound engine started'; Required = $true; Found = $false; Line = '' }
    'atlas'         = [pscustomobject]@{ Label = '资源图集完成（辅助证据）'; Pattern = 'minecraft:textures/atlas/blocks\.png-atlas'; Required = $false; Found = $false; Line = '' }
    'core-selftest' = [pscustomobject]@{ Label = 'M1 核心自检 PASS（T1.2/T1.3，dev）'; Pattern = '核心自检 PASS'; Required = $false; Found = $false; Line = '' }
    'config-skeleton' = [pscustomobject]@{ Label = '配置骨架快照建立（T1.5，dev）'; Pattern = '参数快照建立（Loading）'; Required = $false; Found = $false; Line = '' }
}
$fatalPattern = 'A fatal error has been detected|Exception in thread "main"|MixinApplyError|MixinTransformerError|InvalidMixinException|Failed to start the game|LWJGL Exception|Failed to create window|\[(?:main|Render thread)/FATAL\]'
$fatalLine = ''
$state = 'running'
$launcher = $null
$startTime = Get-Date
$elapsedToMenu = 0
$guiTitles = @()
$newCrashes = @()
$cleanupOk = $true
$residuals = @()
$fatalError = $null

try {
    # 启动 runClient（cmd 内重定向，合并 stdout/stderr 到单个文件；窗口隐藏，游戏 GUI 窗口不受影响）
    $gradlewArgs = 'runClient --console=plain'
    if (-not [string]::IsNullOrWhiteSpace($GradleArgs)) { $gradlewArgs += ' ' + $GradleArgs }
    $cmdLine = '/c ""' + $GradlewBat + '" ' + $gradlewArgs + ' > "' + $stdoutLog + '" 2>&1"'
    Write-RunLog ("启动命令: gradlew.bat " + $gradlewArgs) STEP
    Write-RunLog ("stdout 落盘: " + $stdoutLog) STEP
    $launcher = Start-Process -FilePath $env:ComSpec -ArgumentList $cmdLine -WorkingDirectory $ProjectRoot -PassThru -WindowStyle Hidden
    $startTime = Get-Date
    $deadline = $startTime.AddMinutes($TimeoutMinutes)
    Write-RunLog ("等待主菜单，超时上限 " + $TimeoutMinutes + " 分钟（每 " + $PollSeconds + "s 轮询）") STEP

    $nextHeartbeat = (Get-Date).AddSeconds(10)
    while ($true) {
        Start-Sleep -Seconds $PollSeconds
        $now = Get-Date
        try { $launcher.Refresh() } catch { }
        $exited = $false
        try { $exited = $launcher.HasExited } catch { $exited = $false }

        # latest.log 可能尚未生成或正被写入：安全读取，失败则视为空
        $latestText = Read-TextSafe -Path $latestLog
        $lines = @()
        if ($latestText) { $lines = @($latestText -split "`r?`n") }
        $debugText = Read-TextSafe -Path $debugLog
        $debugLines = @()
        if ($debugText) { $debugLines = @($debugText -split "`r?`n") }

        foreach ($key in @($script:Markers.Keys)) {
            $marker = $script:Markers[$key]
            if ($marker.Found) { continue }
            $found = $false
            for ($i = 0; $i -lt $lines.Count; $i++) {
                if ($lines[$i] -match $marker.Pattern) {
                    $marker.Found = $true
                    $marker.Line = ('latest.log L' + ($i + 1) + ': ' + $lines[$i].Trim())
                    $found = $true
                    break
                }
            }
            if (-not $found) {
                for ($i = 0; $i -lt $debugLines.Count; $i++) {
                    if ($debugLines[$i] -match $marker.Pattern) {
                        $marker.Found = $true
                        $marker.Line = ('debug.log L' + ($i + 1) + ': ' + $debugLines[$i].Trim())
                        break
                    }
                }
            }
        }
        if (-not $fatalLine -and $lines.Count -gt 0) {
            foreach ($line in $lines) {
                if ($line -match $fatalPattern) { $fatalLine = $line.Trim(); break }
            }
        }
        if (-not $fatalLine -and $debugLines.Count -gt 0) {
            foreach ($line in $debugLines) {
                if ($line -match $fatalPattern) { $fatalLine = $line.Trim(); break }
            }
        }

        if ($script:Markers['main-menu'].Found) { $state = 'main-menu'; break }
        if ($exited) { $state = 'exited'; break }
        if ($now -ge $deadline) { $state = 'timeout'; break }

        if ($now -ge $nextHeartbeat) {
            $sizeKB = 0
            if (Test-Path -LiteralPath $latestLog) { $sizeKB = [math]::Round((Get-Item -LiteralPath $latestLog).Length / 1KB, 1) }
            $lastLine = ''
            if ($lines.Count -gt 0) {
                for ($i = $lines.Count - 1; $i -ge 0; $i--) {
                    if ($lines[$i].Trim()) { $lastLine = $lines[$i].Trim(); break }
                }
            }
            # 游戏日志未出现时，尝试从 stdout（Gradle/资源下载输出）取最后一行；文件被占用时安全返回空
            if (-not $lastLine) {
                $stdoutText = Read-TextSafe -Path $stdoutLog
                if ($stdoutText) {
                    $stdoutLines = @($stdoutText -split "`r?`n" | Where-Object { $_.Trim() })
                    if ($stdoutLines.Count -gt 0) { $lastLine = $stdoutLines[-1].Trim() }
                }
            }
            if ($lastLine.Length -gt 200) { $lastLine = $lastLine.Substring(0, 200) + '...' }
            $elapsedSec = [int]($now - $startTime).TotalSeconds
            Write-RunLog ("已等待 " + $elapsedSec + "s；latest.log=" + $sizeKB + " KB；最后一行: " + $lastLine) INFO
            $nextHeartbeat = $now.AddSeconds(10)
        }
    }

    $elapsedToMenu = [int]((Get-Date) - $startTime).TotalSeconds
    if ($state -eq 'main-menu') {
        Start-Sleep -Seconds 2
        $guiTitles = @(Get-Process -ErrorAction SilentlyContinue | Where-Object {
                $_.MainWindowTitle -and ($_.MainWindowTitle -match '(?i)minecraft')
            } | ForEach-Object { ('PID ' + $_.Id + ': ' + $_.MainWindowTitle) })
    }
}
catch {
    $fatalError = $_
    if ($state -eq 'running') { $state = 'error' }
    Write-RunLog ("运行期异常: " + $_.Exception.Message) FAIL
}
finally {
    # 无论成功/失败/异常，都必须清理进程树并确认无残留 java
    $residuals = @()
    if (-not $NoKill) {
        if ($null -ne $launcher) {
            try {
                Write-RunLog ("清理进程树: taskkill /PID " + $launcher.Id + " /T /F") STEP
                $killRes = Invoke-NativeCapture -FilePath (Join-Path $env:SystemRoot 'System32\taskkill.exe') -Arguments ('/PID ' + $launcher.Id + ' /T /F') -TimeoutSec 60
                Write-RunLog ("taskkill 输出: " + (($killRes.Output -replace "`r?`n", ' | ').Trim())) INFO
            }
            catch {
                Write-RunLog ("taskkill 调用异常: " + $_.Exception.Message) WARN
            }
        }
        Start-Sleep -Seconds 3
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            $residuals = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue | Where-Object {
                    $_.CommandLine -and (($_.CommandLine -like ('*' + $ProjectRoot + '*')) -or ($_.CommandLine -like '*captureapi*'))
                })
            if ($residuals.Count -eq 0) { break }
            foreach ($p in $residuals) {
                Write-RunLog ("发现残留 java 进程 PID=" + $p.ProcessId + "，执行 taskkill /F") WARN
                try {
                    $k = Invoke-NativeCapture -FilePath (Join-Path $env:SystemRoot 'System32\taskkill.exe') -Arguments ('/F /PID ' + $p.ProcessId) -TimeoutSec 30
                    Write-RunLog ("强杀 PID " + $p.ProcessId + " 输出: " + (($k.Output -replace "`r?`n", ' | ').Trim())) INFO
                }
                catch {
                    Write-RunLog ("强杀 PID " + $p.ProcessId + " 失败: " + $_.Exception.Message) WARN
                }
            }
            Start-Sleep -Seconds 2
        }
        $stillResidual = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue | Where-Object {
                $_.CommandLine -and (($_.CommandLine -like ('*' + $ProjectRoot + '*')) -or ($_.CommandLine -like '*captureapi*'))
            })
        if ($stillResidual.Count -gt 0) {
            $cleanupOk = $false
            $residuals = $stillResidual
            Write-RunLog ("清理后仍有残留 java 进程：" + (($residuals | ForEach-Object { $_.ProcessId }) -join ', ')) FAIL
        }
        else {
            Write-RunLog '进程树已清理，无残留 java 进程。' PASS
        }
    }
}

# 新增 crash report（清理后统计，避免读取被锁文件）
$newCrashes = @()
if (Test-Path -LiteralPath $CrashDir) {
    $newCrashes = @(Get-ChildItem -LiteralPath $CrashDir -File -ErrorAction SilentlyContinue | Where-Object { -not $beforeCrashes.ContainsKey($_.Name) })
}
$stdoutText = Read-TextSafe -Path $stdoutLog

# 判定
$missingRequired = @($script:Markers.Keys | Where-Object { $script:Markers[$_].Required -and -not $script:Markers[$_].Found })
$success = ($state -eq 'main-menu') -and ($missingRequired.Count -eq 0) -and ([string]::IsNullOrEmpty($fatalLine)) -and ($newCrashes.Count -eq 0) -and $cleanupOk -and ($null -eq $fatalError)

# 输出摘要
Write-Host ''
Write-Host '=== runClient 标记检查 ===' -ForegroundColor Cyan
$markerRows = @($script:Markers.Keys | ForEach-Object {
        [pscustomobject]@{
            Marker   = $script:Markers[$_].Label
            Found    = $(if ($script:Markers[$_].Found) { 'YES' } else { 'NO' })
            Required = $(if ($script:Markers[$_].Required) { 'YES' } else { 'no' })
            Evidence = $script:Markers[$_].Line
        }
    })
$markerRows | Format-Table -AutoSize | Out-String -Width 320 | Write-Host

Write-Host ''
Write-Host '=== runClient 结果 ===' -ForegroundColor Cyan
Write-Host ('状态          : ' + $state)
Write-Host ('等待主菜单耗时: ' + $elapsedToMenu + 's（超时上限 ' + $TimeoutMinutes + ' 分钟）')
Write-Host ('缺失必需标记  : ' + $(if ($missingRequired.Count -eq 0) { '无' } else { ($missingRequired -join ', ') }))
Write-Host ('运行期异常    : ' + $(if ($fatalError) { $fatalError.Exception.Message } else { '无' }))
Write-Host ('致命错误行    : ' + $(if ($fatalLine) { $fatalLine } else { '无' }))
Write-Host ('新增 crash    : ' + $(if ($newCrashes.Count -eq 0) { '无' } else { (($newCrashes | ForEach-Object { $_.Name }) -join ', ') }))
Write-Host ('残留 java     : ' + $(if ($residuals.Count -eq 0) { '无' } else { (($residuals | ForEach-Object { $_.ProcessId }) -join ', ') }))
Write-Host ('GUI 窗口      : ' + $(if ($guiTitles.Count -eq 0) { '未检测到标题（可能窗口标题不含 minecraft）' } else { ($guiTitles -join ' ; ') }))
Write-Host ('latest.log    : ' + $latestLog)
Write-Host ('stdout 日志   : ' + $stdoutLog)
if ($prevLogArchive) { Write-Host ('旧日志归档    : ' + $prevLogArchive) }
Write-Host ('总耗时        : ' + [math]::Round($swRun.Elapsed.TotalSeconds, 1) + 's')

if ($state -eq 'exited' -or $state -eq 'error' -or $state -eq 'timeout') {
    Write-Host ('--- stdout 末尾 20 行（' + $state + '）---') -ForegroundColor DarkGray
    (@($stdoutText -split "`r?`n") | Select-Object -Last 20) | ForEach-Object { Write-Host ('  ' + $_) -ForegroundColor DarkGray }
}

# 写结果摘要文件（证据留档）
$summaryPath = Join-Path $LogsDir ('run-client-summary-' + $stamp + '.txt')
$summaryLines = New-Object System.Collections.ArrayList
[void]$summaryLines.Add('captureapi runClient 验证摘要')
[void]$summaryLines.Add('时间: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
[void]$summaryLines.Add('工程: ' + $ProjectRoot)
[void]$summaryLines.Add('JDK: ' + $jdk)
[void]$summaryLines.Add('状态: ' + $state)
[void]$summaryLines.Add('等待主菜单耗时(s): ' + $elapsedToMenu)
[void]$summaryLines.Add('超时上限(min): ' + $TimeoutMinutes)
[void]$summaryLines.Add('缺失必需标记: ' + $(if ($missingRequired.Count -eq 0) { '无' } else { ($missingRequired -join ',') }))
[void]$summaryLines.Add('运行期异常: ' + $(if ($fatalError) { $fatalError.Exception.Message } else { '无' }))
[void]$summaryLines.Add('致命错误行: ' + $(if ($fatalLine) { $fatalLine } else { '无' }))
[void]$summaryLines.Add('新增 crash report: ' + $(if ($newCrashes.Count -eq 0) { '无' } else { (($newCrashes | ForEach-Object { $_.Name }) -join ',') }))
[void]$summaryLines.Add('残留 java: ' + $(if ($residuals.Count -eq 0) { '无' } else { (($residuals | ForEach-Object { $_.ProcessId }) -join ',') }))
[void]$summaryLines.Add('GUI 窗口: ' + $(if ($guiTitles.Count -eq 0) { '未检测到标题' } else { ($guiTitles -join ' ; ') }))
[void]$summaryLines.Add('latest.log: ' + $latestLog)
[void]$summaryLines.Add('stdout: ' + $stdoutLog)
[void]$summaryLines.Add('debug.log: ' + $debugLog)
[void]$summaryLines.Add('')
[void]$summaryLines.Add('标记:')
foreach ($key in $script:Markers.Keys) {
    [void]$summaryLines.Add(('  ' + $script:Markers[$key].Label + ' => ' + $(if ($script:Markers[$key].Found) { 'YES' } else { 'NO' }) + ' | ' + $script:Markers[$key].Line))
}
[void]$summaryLines.Add('')
[void]$summaryLines.Add('结论: ' + $(if ($success) { 'PASS' } else { 'FAIL' }))
[System.IO.File]::WriteAllText($summaryPath, ($summaryLines -join "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
Write-Host ('摘要文件      : ' + $summaryPath)

Write-Host ''
if ($success) {
    Write-Host 'runClient 结果: PASS（已到达主菜单，无 crash，进程已清理）' -ForegroundColor Green
    Write-Host '注意：进入世界/实际游戏内行为无法自动验收，属人工验收项。' -ForegroundColor Yellow
    exit 0
}
Write-Host 'runClient 结果: FAIL' -ForegroundColor Red
exit 1