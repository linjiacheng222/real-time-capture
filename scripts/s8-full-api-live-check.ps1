#Requires -Version 5.1
<#
.SYNOPSIS
    全量指令实测脚本：启动 dev 客户端 → 自动进世界 → 逐项执行全部指令/API → 导出结果 JSON＋产物文件 → 退出。

.DESCRIPTION
    流程：
      1) 归档旧日志，启动 gradlew runClient（后台），轮询至主菜单（Sound engine started）；
      2) WS 连接 ws://127.0.0.1:25581/，发送 client.loadWorld 进入 run/saves/s1test；
      3) 等待进入世界（client.playerPose 返回有效坐标）；
      4) 按顺序执行全部测试组（相机/统一截取/多路/音频通道/滤镜/叠加/名单/录像/参数/光影/信息/HTTP）；
      5) 每项记录 ok/响应摘要/产物路径；截图类产物存 docs/evidence/S8/full-api/；
      6) 全部完成后 client.leaveWorld → 等待退出 → taskkill 清理；
      7) 输出 JSON 报告 docs/evidence/S8/full-api/full-api-report.json。

.NOTES
    仅测试构建可用（需要 test_commands.enabled 标记，脚本自动在 run/config 创建）。
#>
[CmdletBinding()]
param(
    [int]$TimeoutMinutes = 20,
    [string]$EvidenceDir = "docs\evidence\S8\full-api",
    [string]$JdkHome = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
$report = [ordered]@{ date = (Get-Date -Format s); phases = @(); artifacts = @() }

function Add-Phase([string]$name, [object]$result, [string[]]$artifactPaths) {
    $script:report.phases += [ordered]@{ name = $name; ok = $null -ne $result -and "$($result.ok)" -eq 'True'; detail = ($result | ConvertTo-Json -Depth 6 -Compress); ts = (Get-Date -Format s) }
    if ($artifactPaths) { $script:report.artifacts += $artifactPaths }
    Write-Host ("[{0}] {1} -> {2}" -f (Get-Date -Format HH:mm:ss), $name, $(if ($report.phases[-1].ok) { 'PASS' } else { $result | ConvertTo-Json -Depth 4 -Compress }))
}

# ===== 1. 启动客户端 =====
# 归档旧 latest.log（避免读到上次残留）
$oldLog = Join-Path $root 'run\logs\latest.log'
if (Test-Path $oldLog) {
    Move-Item $oldLog (Join-Path $root "run\logs\latest-prev-s8-$stamp.log") -Force
}
Write-Host "[BOOT] 启动 runClient（JDK 自动解析）..."
$envFile = Join-Path $root 'build\reports\env'
New-Item -ItemType Directory -Force -Path $envFile | Out-Null
$stdoutLog = Join-Path $envFile "s8-fullapi-stdout-$stamp.log"
$jdk = if ($JdkHome) { $JdkHome } elseif (Test-Path 'E:\Tools\jdk17\bin\java.exe') { 'E:\Tools\jdk17' } else { 'C:\Users\26217\AppData\Roaming\.minecraft\runtime\java-runtime-beta' }
$env:JAVA_HOME = $jdk
$proc = Start-Process -FilePath (Join-Path $root 'gradlew.bat') -ArgumentList 'runClient', '--console=plain' -WorkingDirectory $root -RedirectStandardOutput $stdoutLog -RedirectStandardError "$stdoutLog.err" -PassThru -WindowStyle Hidden
$latestLog = Join-Path $root 'run\logs\latest.log'
$menuReached = $false
$netReady = $false
$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
Write-Host "[BOOT] 等待主菜单（最长 $TimeoutMinutes 分钟）..."
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    # gradlew wrapper 会 spawn 子 java 进程后自身退出；以「java 进程存在」为准
    $javaAlive = @(Get-Process java -ErrorAction SilentlyContinue).Count -gt 0
    if (-not $javaAlive -and (Get-Date) -gt ((Get-Date).AddSeconds(-90))) {
        # 启动初期 90 秒内允许 java 尚未出现
        $bootGrace = $true
    }
    try {
        $raw = [IO.File]::ReadAllText($latestLog, [Text.Encoding]::GetEncoding('GBK'))
        $tail = $raw.Substring([Math]::Max(0, $raw.Length - 8000))
    } catch { $tail = '' }
    if (-not $netReady -and $tail -match 'NET/START') { $netReady = $true; Write-Host '[BOOT] NET 服务就绪' }
    if ($tail -match 'Sound engine started') { $menuReached = $true; Write-Host '[BOOT] 主菜单就绪'; break }
    if ($tail -match 'crash-report|Mixin apply failed|FATAL') { Write-Host '[BOOT][WARN] 检测到异常标记，继续观察...' }
}
if ($menuReached -and -not $netReady) { Start-Sleep -Seconds 3 }

# 收尾改为：杀全部 java（gradle wrapper 与游戏分离）
function Kill-Game {
    Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like '*jdk*' -or $_.Path -like '*java-runtime*' } | Stop-Process -Force -Confirm:$false -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}
if (-not $menuReached) {
    $report.boot = 'FAILED_TO_REACH_MENU'; $report | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $EvidenceDir "full-api-report.json") -Encoding UTF8
    Kill-Game; exit 1
}

# ===== 2. WS 连接与进世界 =====
# ClientWebSocket 属 System（无 Add-Type，直接可用）
function WsSend([string]$json, [int]$timeoutMs = 15000) {
    $ws = [System.Net.WebSockets.ClientWebSocket]::new()
    $ct = [System.Threading.CancellationTokenSource]::new($timeoutMs)
    $ws.ConnectAsync([Uri]'ws://127.0.0.1:25581/', $ct.Token).GetAwaiter().GetResult()
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    $ws.SendAsync([ArraySegment[byte]]::new($bytes), [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $ct.Token).GetAwaiter().GetResult()
    $buf = New-Object byte[] 1048576
    $res = $ws.ReceiveAsync([ArraySegment[byte]]::new($buf), $ct.Token).GetAwaiter().GetResult()
    $resp = [Text.Encoding]::UTF8.GetString($buf, 0, $res.Count)
    $ws.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, 'done', [System.Threading.CancellationToken]::None).GetAwaiter().GetResult()
    return ($resp | ConvertFrom-Json)
}

Write-Host '[WORLD] 载入世界 s1test ...'
# WS 连接重试（NET 端口就绪可能晚于主菜单标记，最多重试 12 次×5s）
$loadSent = $false
for ($i = 0; $i -lt 12 -and -not $loadSent; $i++) {
    try {
        $null = WsSend '{"id":"w1","action":"client.loadWorld","params":{"name":"s1test"}}' 30000
        $loadSent = $true
    } catch {
        Write-Host "[WORLD] WS 连接重试 $($i+1)/12（$($_.Exception.Message)）"
        Start-Sleep -Seconds 5
    }
}
if (-not $loadSent) { Write-Host '[WORLD][FAIL] WS 12 次重试均失败' }
$inWorld = $false
$wdl = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $wdl) {
    Start-Sleep -Seconds 4
    try { $p = WsSend '{"id":"p1","action":"client.playerPose"}' 8000 } catch { continue }
    if ($p.ok -and $p.result -and $p.result.x) { $inWorld = $true; break }
}
Add-Phase 'boot+world' ([ordered]@{ ok = $inWorld; menu = $menuReached; inWorld = $inWorld; pose = ($p.result | ConvertTo-Json -Compress) }) $null
if (-not $inWorld) {
    $report | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $EvidenceDir "full-api-report.json") -Encoding UTF8
    Kill-Game; exit 1
}

# ===== 3. 相机管理 =====
$r = WsSend '{"id":"c1","action":"camera.create","params":{"x":100,"y":64,"z":200,"fov":70}}'; $cam1 = $r.result.id
Add-Phase 'camera.create#1' $r $null
$r = WsSend '{"id":"c2","action":"camera.create","params":{"x":300,"y":70,"z":100,"yaw":180,"fov":90}}'; $cam2 = $r.result.id
Add-Phase 'camera.create#2' $r $null
$r = WsSend '{"id":"ca","action":"camera.adjust","params":{"id":2,"yaw":90}}'
Add-Phase 'camera.adjust' $r $null
$r = WsSend '{"id":"cp","action":"camera.pause","params":{"id":2}}'
Add-Phase 'camera.pause' $r $null
$r = WsSend '{"id":"cr","action":"camera.resume","params":{"id":2}}'
Add-Phase 'camera.resume' $r $null
$r = WsSend '{"id":"cl","action":"camera.list"}'
Add-Phase 'camera.list(2台)' ([ordered]@{ ok = ($r.ok -and $r.result.cameras.Count -ge 2); count = $r.result.cameras.Count }) $null
$r = WsSend '{"id":"cs","action":"camera.status"}'
Add-Phase 'camera.status' $r $null
$r = WsSend '{"id":"cd","action":"camera.delete","params":{"id":2}}'
Add-Phase 'camera.delete#2' $r $null

# 等首帧
Start-Sleep -Seconds 5

# ===== 4. 统一截取（WS capture action） =====
$r = WsSend '{"id":"u1","action":"capture","params":{"out":"run/evidence-s8-ws-capture.jpg"}}'
Add-Phase 'capture(ws,default)' ([ordered]@{ ok = $r.ok; loc = $r.result.location; size = $r.result.sizeBytes }) @(Join-Path $root 'run\evidence-s8-ws-capture.jpg')
$r = WsSend '{"id":"u2","action":"capture","params":{"camera":1,"filter":"grayscale","quality":90,"out":"run/evidence-s8-cam1-gray.jpg"}}'
Add-Phase 'capture(ws,camera:1+filter)' ([ordered]@{ ok = $r.ok; size = $r.result.sizeBytes }) @(Join-Path $root 'run\evidence-s8-cam1-gray.jpg')
$r = WsSend '{"id":"u3","action":"capture","params":{"audio":true,"audioChunks":5,"out":"run/evidence-s8-audio.jpg"}}'
Add-Phase 'capture(ws,audio)' ([ordered]@{ ok = $r.ok; audioSamples = $r.result.audio.sampleCount; audioBytes = $r.result.audio.pcmBase64.Length }) @(Join-Path $root 'run\evidence-s8-audio.jpg')

# ===== 5. 游戏内指令（经 client.command 代理） =====
foreach ($cmdPair in @(
    @{ n = 'cmd.camera.create'; j = '{"id":"g1","action":"client.command","params":{"command":"captureapi camera create pos:150,64,250"}}' },
    @{ n = 'cmd.camera.list'; j = '{"id":"g2","action":"client.command","params":{"command":"captureapi camera list"}}' },
    @{ n = 'cmd.capture.filter'; j = '{"id":"g3","action":"client.command","params":{"command":"captureapi capture filter:grayscale quality:88 out:run/evidence-s8-cmd.jpg"}}' },
    @{ n = 'cmd.capture.camera'; j = '{"id":"g4","action":"client.command","params":{"command":"captureapi capture camera:1 out:run/evidence-s8-cmd-cam1.jpg"}}' },
    @{ n = 'cmd.filter.list'; j = '{"id":"g5","action":"client.command","params":{"command":"captureapi filter list"}}' },
    @{ n = 'cmd.overlay.list'; j = '{"id":"g6","action":"client.command","params":{"command":"captureapi overlay list"}}' },
    @{ n = 'cmd.config.get'; j = '{"id":"g7","action":"client.command","params":{"command":"captureapi info config fps_max"}}' },
    @{ n = 'cmd.health'; j = '{"id":"g8","action":"client.command","params":{"command":"captureapi info health"}}' },
    @{ n = 'cmd.frame'; j = '{"id":"g9","action":"client.command","params":{"command":"captureapi info frame"}}' },
    @{ n = 'cmd.shader'; j = '{"id":"g10","action":"client.command","params":{"command":"captureapi info shader"}}' },
    @{ n = 'cmd.help'; j = '{"id":"g11","action":"client.command","params":{"command":"captureapi help"}}' },
    @{ n = 'cmd.renderfilter.list'; j = '{"id":"g12","action":"client.command","params":{"command":"captureapi renderfilter list"}}' },
    @{ n = 'cmd.audiofilter.list'; j = '{"id":"g13","action":"client.command","params":{"command":"captureapi audiofilter list"}}' },
    @{ n = 'cmd.recording.status'; j = '{"id":"g14","action":"client.command","params":{"command":"captureapi record status"}}' }
)) {
    try { $r = WsSend $cmdPair.j 20000; Add-Phase $cmdPair.n ([ordered]@{ ok = $r.ok; resp = ($r | ConvertTo-Json -Depth 4 -Compress).Substring(0, [Math]::Min(300, ($r | ConvertTo-Json -Depth 4 -Compress).Length)) }) $(if ($cmdPair.n -like '*capture*') { @(Join-Path $root 'run\evidence-s8-cmd.jpg') } else { $null }) }
    catch { Add-Phase $cmdPair.n ([ordered]@{ ok = $false; error = $_.Exception.Message }) $null }
}

# ===== 6. 多路视频（0x04） =====
$r = WsSend '{"id":"m1","action":"video.subscribe","params":{"cameras":[1]}}'
Add-Phase 'video.subscribe(1)' $r $null
Start-Sleep -Seconds 6
$r = WsSend '{"id":"m2","action":"video.unsubscribe","params":{}}'
Add-Phase 'video.unsubscribe' $r $null

# ===== 7. 多监听音频 =====
$r = WsSend '{"id":"a1","action":"audio.channels.list"}'
Add-Phase 'audio.channels.list' $r $null
$r = WsSend '{"id":"a2","action":"audio.channels.peek","params":{"channel":0}}'
Add-Phase 'audio.channels.peek(player)' ([ordered]@{ ok = $r.ok; rms = $r.result.rms; peak = $r.result.peak }) $null
$r = WsSend '{"id":"a3","action":"audio.subscribe","params":{"channels":[0]}}'
Add-Phase 'audio.subscribe(player)' $r $null
Start-Sleep -Seconds 4
$r = WsSend '{"id":"a4","action":"audio.unsubscribe","params":{}}'
Add-Phase 'audio.unsubscribe' $r $null
$r = WsSend '{"id":"a5","action":"audio.section","params":{"x":100,"y":64,"z":200}}'
Add-Phase 'audio.section' ([ordered]@{ ok = $r.ok; samples = $r.result.samples }) $null

# ===== 8. 滤镜/叠加/名单/光影/参数 =====
$r = WsSend '{"id":"f1","action":"filter.list"}'
Add-Phase 'filter.list' ([ordered]@{ ok = $r.ok; names = ($r.result.filters -join ',') }) $null
$r = WsSend '{"id":"f2","action":"filter.set","params":{"name":"grayscale"}}'
Add-Phase 'filter.set(grayscale)' $r $null
$r = WsSend '{"id":"f3","action":"filter.set","params":{"name":""}}'
Add-Phase 'filter.set(none)' $r $null
$r = WsSend '{"id":"o1","action":"overlay.list"}'
Add-Phase 'overlay.list' $r $null
$r = WsSend '{"id":"rf1","action":"renderfilter.list"}'
Add-Phase 'renderfilter.list' $r $null
$r = WsSend '{"id":"af1","action":"audiofilter.list"}'
Add-Phase 'audiofilter.list' $r $null
$r = WsSend '{"id":"sh1","action":"shader.status"}'
Add-Phase 'shader.status' ([ordered]@{ ok = $r.ok; backend = $r.result.backend; detected = $r.result.detected }) $null
$r = WsSend '{"id":"cg1","action":"config.get","params":{"key":"fps_max"}}'
Add-Phase 'config.get(fps_max)' ([ordered]@{ ok = $r.ok; value = $r.result.value }) $null
$r = WsSend '{"id":"cs1","action":"config.set","params":{"key":"fps_max","value":15}}'
Add-Phase 'config.set(15)' $r $null
$r = WsSend '{"id":"cs2","action":"config.set","params":{"key":"fps_max","value":30}}'
Add-Phase 'config.set(30)' $r $null

# ===== 9. 录像（相机1，含音频） =====
$r = WsSend '{"id":"v1","action":"recording.start","params":{"camera":1,"fps":15,"audio":true}}'
Add-Phase 'recording.start(cam1)' $r $null
Start-Sleep -Seconds 12
$r = WsSend '{"id":"v2","action":"recording.stop"}'
$recPath = $r.result.path
Add-Phase 'recording.stop' ([ordered]@{ ok = $r.ok; frames = $r.result.frames; path = $recPath; audio = $r.result.audioAvailable }) $(if ($recPath) { @($recPath) })

# ===== 10. HTTP 端点 =====
$httpResults = [ordered]@{}
try {
    $h = Invoke-WebRequest 'http://127.0.0.1:25580/health' -UseBasicParsing -TimeoutSec 8
    $httpResults.health = "HTTP $($h.StatusCode)"
    $health = $h.Content | ConvertFrom-Json
    $httpResults.rendered = $health.video_rendered_frames
    $httpResults.audioLoopback = $health.audio_loopback
} catch { $httpResults.health = "FAIL: $($_.Exception.Message)" }
try {
    $f = Invoke-WebRequest 'http://127.0.0.1:25580/camera/1/frame' -UseBasicParsing -TimeoutSec 8 -OutFile (Join-Path $EvidenceDir 'http-frame-cam1.jpg') -PassThru
    $httpResults.frame1 = "HTTP $($f.StatusCode) $($f.Headers['X-Frame-Timestamp'])"
} catch { $httpResults.frame1 = "FAIL: $($_.Exception.Message)" }
try {
    $s = Invoke-WebRequest 'http://127.0.0.1:25580/camera/1/stream' -UseBasicParsing -TimeoutSec 5
    $httpResults.stream1 = "HTTP $($s.StatusCode) $($s.Headers['Content-Type'])"
} catch { $httpResults.stream1 = $_.Exception.Response.StatusCode.value__ }
Add-Phase 'http(endpoints)' ([ordered]@{ ok = ($httpResults.health -like 'HTTP 200*'); detail = $httpResults }) @(Join-Path $EvidenceDir 'http-frame-cam1.jpg')

# ===== 11. 收尾 =====
try { $null = WsSend '{"id":"lw","action":"client.leaveWorld"}' 10000 } catch {}
Start-Sleep -Seconds 5
Kill-Game
Start-Sleep -Seconds 3
$report.end = (Get-Date -Format s)
$reportJson = Join-Path $EvidenceDir 'full-api-report.json'
$report | ConvertTo-Json -Depth 10 | Set-Content $reportJson -Encoding UTF8
$pass = ($report.phases | Where-Object { $_.ok }).Count
$total = $report.phases.Count
Write-Host ""
Write-Host "===== 全量指令实测完成：PASS $pass / $total ====="
Write-Host "报告: $reportJson"
Write-Host "产物目录: $EvidenceDir"
exit $(if ($pass -eq $total) { 0 } else { 1 })
