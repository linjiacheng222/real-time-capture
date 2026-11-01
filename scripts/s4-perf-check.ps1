#Requires -Version 5.1
<#
S4 performance/resource automated check.
Measures (camera active, 30fps target):
 - published frame rate from /health delta
 - single-frame HTTP latency (100 sequential requests, paced)
 - pipeline timing lines (log_pipeline_timing=true; every 300 frames)
 - JVM heap samples via jcmd (if available)
Saves CSV/JSON/summary under docs/evidence/S4/perf-<stamp>/.

Usage: powershell -ExecutionPolicy Bypass -File scripts\s4-perf-check.ps1
#>
param(
    [string]$BaseUrl = 'http://127.0.0.1:25580',
    [string]$WsUrl = 'ws://127.0.0.1:25581/',
    [int]$DurationSeconds = 25,
    [int]$FrameRequests = 100,
    [int]$RequestPaceMs = 40,
    [string]$OutDir = ''
)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if (-not $OutDir) { $OutDir = Join-Path $root "docs\evidence\S4\perf-$stamp" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$summary = New-Object System.Collections.Generic.List[string]
function Log([string]$t) { Write-Host $t; $summary.Add($t) }
function WsCommand([string]$json) {
    $ws = New-Object System.Net.WebSockets.ClientWebSocket
    $ct = [Threading.CancellationToken]::None
    try {
        $ws.ConnectAsync([Uri]$WsUrl, $ct).Wait(5000) | Out-Null
        $b = [Text.Encoding]::UTF8.GetBytes($json); $seg = New-Object ArraySegment[byte] -ArgumentList @(,$b)
        $ws.SendAsync($seg, [Net.WebSockets.WebSocketMessageType]::Text, $true, $ct).Wait(5000) | Out-Null
        $deadline = (Get-Date).AddSeconds(5); $sb = New-Object Text.StringBuilder
        while ((Get-Date) -lt $deadline) {
            $buf = New-Object byte[] 65536; $rs = New-Object ArraySegment[byte] -ArgumentList @(,$buf)
            try { $task = $ws.ReceiveAsync($rs, $ct); if (-not $task.Wait(3000)) { continue } } catch { return "ERR: $($_.Exception.Message)" }
            if ($task.Result.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Text) {
                [void]$sb.Append([Text.Encoding]::UTF8.GetString($buf, 0, $task.Result.Count))
                if ($task.Result.EndOfMessage) { return $sb.ToString() }
            }
        }
        return 'TIMEOUT'
    } catch { return "ERR: $($_.Exception.Message)" } finally { try { $ws.Dispose() } catch {} }
}
function Get-Health {
    try { return (Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/health" -TimeoutSec 3).Content | ConvertFrom-Json } catch { return $null }
}
function Get-JavaPid {
    $p = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'forgeclientuserdev' } | Select-Object -First 1
    if ($p) { return $p.ProcessId }
    return 0
}
function Get-HeapUsedMb([int]$targetPid) {
    if ($targetPid -le 0) { return '' }
    try {
        $out = & 'E:\Tools\jdk17\bin\jcmd.exe' $targetPid GC.heap_info 2>&1
        $text = $out -join "`n"
        $m = [regex]::Match($text, 'used\s+(\d+)K')
        if ($m.Success) { return [Math]::Round([double]$m.Groups[1].Value / 1024.0, 1) }
    } catch {}
    return ''
}
Log "S4 perf start: $stamp"
$health = Get-Health
if (-not $health) { Log 'FAIL health'; $summary | Set-Content (Join-Path $OutDir 'summary.txt') -Encoding UTF8; exit 2 }
$health | ConvertTo-Json -Compress | Set-Content (Join-Path $OutDir 'health-before.json') -Encoding UTF8
Log 'PASS health'
Log ('unpause: ' + (WsCommand '{"id":"u","action":"client.unpause","params":{}}'))
Start-Sleep -Milliseconds 500
$pose = (WsCommand '{"id":"p","action":"client.playerPose","params":{}}' | ConvertFrom-Json).result
if (-not $pose) { Log 'FAIL no pose'; exit 3 }
$st = WsCommand '{"id":"st","action":"camera.status","params":{}}' | ConvertFrom-Json
if ($st.ok -and $st.result.active -and [int]$st.result.active -gt 0) {
    WsCommand (@{ id = 'del'; action = 'camera.delete'; params = @{ id = [int]$st.result.active } } | ConvertTo-Json -Compress -Depth 4) | Out-Null
}
Log ('config timing: ' + (WsCommand '{"id":"cfg","action":"config.set","params":{"key":"log_pipeline_timing","value":true}}'))
$create = WsCommand (@{ id = 'cam'; action = 'camera.create'; params = @{ x = $pose.x; y = $pose.eyeY; z = $pose.z; yaw = $pose.yaw; pitch = $pose.pitch; fov = $pose.fov } } | ConvertTo-Json -Compress -Depth 4)
Log "camera.create: $create"
$cam = $create | ConvertFrom-Json
if (-not $cam.ok) { Log 'FAIL camera create'; exit 4 }
$camId = [int]$cam.result.id
$javaPid = Get-JavaPid
Log "java pid=$javaPid"
Start-Sleep -Seconds 2

$csv = Join-Path $OutDir 'samples.csv'
'datetime,elapsed_s,video_frames,audio_written,pipeline_failed,connections,http_streams,sync_delta_ms,heap_used_mb' | Set-Content $csv -Encoding UTF8
$h0 = Get-Health
$t0 = Get-Date
$lastHeap = 0
$samples = 0
while (((Get-Date) - $t0).TotalSeconds -lt $DurationSeconds) {
    Start-Sleep -Seconds 1
    $h = Get-Health
    if (-not $h) { continue }
    $elapsed = [Math]::Round(((Get-Date) - $t0).TotalSeconds, 1)
    $heap = ''
    if ($samples % 5 -eq 0) { $heap = Get-HeapUsedMb $javaPid }
    if ($heap -ne '') { $lastHeap = $heap }
    ('{0},{1},{2},{3},{4},{5},{6},{7},{8}' -f (Get-Date -Format s), $elapsed, $h.video_frames_published, $h.audio_ring_written, $h.pipeline_failed_frames, $h.connections, $h.http_streams, $h.sync_delta_ms_avg, $lastHeap) | Add-Content $csv -Encoding UTF8
    $samples++
}
$h1 = Get-Health
$elapsedTotal = ((Get-Date) - $t0).TotalSeconds
$frameDelta = [int]$h1.video_frames_published - [int]$h0.video_frames_published
$fps = if ($elapsedTotal -gt 0) { [Math]::Round($frameDelta / $elapsedTotal, 2) } else { 0 }
Log "frames: $($h0.video_frames_published) -> $($h1.video_frames_published) delta=$frameDelta in $([Math]::Round($elapsedTotal,1))s => fps=$fps"

# single-frame latency
$lat = New-Object System.Collections.Generic.List[double]
$fail = 0
for ($i = 0; $i -lt $FrameRequests; $i++) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $req = [System.Net.HttpWebRequest]::Create("$BaseUrl/camera/0/frame")
        $req.Timeout = 5000
        $resp = $req.GetResponse()
        $code = [int]$resp.StatusCode
        $resp.Dispose()
        $sw.Stop()
        if ($code -eq 200) { $lat.Add($sw.Elapsed.TotalMilliseconds) } else { $fail++ }
    } catch { $sw.Stop(); $fail++ }
    Start-Sleep -Milliseconds $RequestPaceMs
}
if ($lat.Count -gt 0) {
    $sorted = $lat | Sort-Object
    $p50 = $sorted[[int]([Math]::Floor($sorted.Count * 0.50))]
    $p95 = $sorted[[Math]::Min($sorted.Count - 1, [int]([Math]::Floor($sorted.Count * 0.95)))]
    $max = $sorted[$sorted.Count - 1]
    Log ("single-frame latency: n={0} fail={1} p50={2:N2}ms p95={3:N2}ms max={4:N2}ms" -f $lat.Count, $fail, $p50, $p95, $max)
} else { Log "single-frame latency: no successful requests fail=$fail" }

$logPath = Join-Path $root 'run\logs\latest.log'
if (Test-Path $logPath) {
    Copy-Item $logPath (Join-Path $OutDir 'latest.log') -Force
    $timing = Select-String -Path $logPath -Pattern 'fbo=|PBO|流水线|Pipeline' -Encoding Default | Select-Object -Last 5
    foreach ($line in $timing) { Log ("pipeline: " + $line.Line) }
}
$del = WsCommand (@{ id = 'del'; action = 'camera.delete'; params = @{ id = $camId } } | ConvertTo-Json -Compress -Depth 4)
Log ('camera delete: ' + $del)
Log ('config timing off: ' + (WsCommand '{"id":"cfg0","action":"config.set","params":{"key":"log_pipeline_timing","value":false}}'))
$h1 | ConvertTo-Json -Compress | Set-Content (Join-Path $OutDir 'health-after.json') -Encoding UTF8
$summary | Set-Content (Join-Path $OutDir 'summary.txt') -Encoding UTF8
Log "evidence: $OutDir"
exit 0
