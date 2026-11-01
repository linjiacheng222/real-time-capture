#Requires -Version 5.1
<#
S5 stability short-run automated check.
Keeps a camera active, adjusts it periodically, samples /health + JVM heap,
and verifies frames continue without pipeline failures / encode drops.

Usage: powershell -ExecutionPolicy Bypass -File scripts\s5-stability-check.ps1 -DurationSeconds 120
#>
param(
    [string]$BaseUrl = 'http://127.0.0.1:25580',
    [string]$WsUrl = 'ws://127.0.0.1:25581/',
    [int]$DurationSeconds = 120,
    [int]$IntervalSeconds = 5,
    [string]$OutDir = ''
)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if (-not $OutDir) { $OutDir = Join-Path $root "docs\evidence\S5\stability-$stamp" }
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
function GetHealth { try { return (Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/health" -TimeoutSec 3).Content | ConvertFrom-Json } catch { return $null } }
function GetJavaPid {
    $p = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'forgeclientuserdev' } | Select-Object -First 1
    if ($p) { return $p.ProcessId }
    return 0
}
function GetHeapMb([int]$targetPid) {
    if ($targetPid -le 0) { return '' }
    try {
        $out = & 'E:\Tools\jdk17\bin\jcmd.exe' $targetPid GC.heap_info 2>&1
        $text = $out -join "`n"
        $m = [regex]::Match($text, 'used\s+(\d+)([KMG]?)')
        if ($m.Success) {
            $v = [double]$m.Groups[1].Value
            switch ($m.Groups[2].Value) { 'K' { $v /= 1024.0 } 'M' { $v = $v } 'G' { $v *= 1024.0 } }
            return [Math]::Round($v, 1)
        }
    } catch {}
    try {
        $lines = & 'E:\Tools\jdk17\bin\jstat.exe' -gc $targetPid 2>&1
        $data = $lines | Select-Object -Last 1
        if ($data) {
            $p = ($data -split '\s+') | Where-Object { $_ -ne '' }
            if ($p.Count -ge 8) { return [Math]::Round(([double]$p[2] + [double]$p[3] + [double]$p[5] + [double]$p[7]) / 1024.0, 1) }
        }
    } catch {}
    return ''
}
Log "S5 stability start: $stamp, duration=${DurationSeconds}s"
$h0 = GetHealth
if (-not $h0) { Log 'FAIL health'; $summary | Set-Content (Join-Path $OutDir 'summary.txt') -Encoding UTF8; exit 2 }
Log ('unpause: ' + (WsCommand '{"id":"u","action":"client.unpause","params":{}}'))
Start-Sleep -Milliseconds 500
$pose = (WsCommand '{"id":"p","action":"client.playerPose","params":{}}' | ConvertFrom-Json).result
if (-not $pose) { Log 'FAIL no pose'; exit 3 }
$st = WsCommand '{"id":"st","action":"camera.status","params":{}}' | ConvertFrom-Json
if ($st.ok -and $st.result.active -and [int]$st.result.active -gt 0) {
    WsCommand (@{ id = 'd'; action = 'camera.delete'; params = @{ id = [int]$st.result.active } } | ConvertTo-Json -Compress -Depth 4) | Out-Null
}
$cam = (WsCommand (@{ id = 'c'; action = 'camera.create'; params = @{ x = $pose.x; y = $pose.eyeY; z = $pose.z; yaw = $pose.yaw; pitch = $pose.pitch; fov = $pose.fov } } | ConvertTo-Json -Compress -Depth 4) | ConvertFrom-Json)
if (-not $cam.ok) { Log 'FAIL camera'; exit 4 }
$camId = [int]$cam.result.id
$javaPid = GetJavaPid
Log "camera=$camId javaPid=$javaPid"
$csv = Join-Path $OutDir 'samples.csv'
'datetime,elapsed_s,game_fps,offscreen,readback,encoded,published,dropped,failed,connections,http_streams,heap_mb' | Set-Content $csv -Encoding UTF8
$start = Get-Date
$peakHeap = 0.0; $startHeap = ''; $endHeap = ''
$step = 0
while (((Get-Date) - $start).TotalSeconds -lt $DurationSeconds) {
    Start-Sleep -Seconds $IntervalSeconds
    $h = GetHealth
    if (-not $h) { continue }
    $elapsed = [Math]::Round(((Get-Date) - $start).TotalSeconds, 1)
    $heap = GetHeapMb $javaPid
    if ($heap -ne '') {
        if ($startHeap -eq '') { $startHeap = $heap }
        $endHeap = $heap
        if ([double]$heap -gt $peakHeap) { $peakHeap = [double]$heap }
    }
    ('{0},{1},{2},{3},{4},{5},{6},{7},{8},{9},{10},{11}' -f (Get-Date -Format s), $elapsed, $h.game_fps,
        $h.offscreen_rendered_frames, $h.readback_frames, $h.encoded_frames, $h.video_frames_published,
        $h.encode_dropped_frames, $h.encode_failed_frames, $h.connections, $h.http_streams, $heap) | Add-Content $csv -Encoding UTF8
    $step++
    if ($step % 3 -eq 0) {
        # 周期性调整相机，覆盖参数变更路径
        $yaw = $pose.yaw + (($step % 6) * 15)
        $adj = @{ id = 'adj'; action = 'camera.adjust'; params = @{ id = $camId; x = $pose.x; y = $pose.eyeY; z = $pose.z; yaw = $yaw; pitch = $pose.pitch; fov = $pose.fov } } | ConvertTo-Json -Compress -Depth 4
        WsCommand $adj | Out-Null
        Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/camera/0/frame" -TimeoutSec 3 | Out-Null
    }
}
$h1 = GetHealth
$frameDelta = [int]$h1.video_frames_published - [int]$h0.video_frames_published
$ok = ($h1.pipeline_failed_frames -eq $h0.pipeline_failed_frames) -and ($h1.encode_failed_frames -eq $h0.encode_failed_frames)
Log "frames delta=$frameDelta; pipeline_failed=$($h0.pipeline_failed_frames)->$($h1.pipeline_failed_frames); encode_failed=$($h0.encode_failed_frames)->$($h1.encode_failed_frames)"
Log "heap start=$startHeap MB end=$endHeap MB peak=$peakHeap MB"
$del = WsCommand (@{ id = 'd'; action = 'camera.delete'; params = @{ id = $camId } } | ConvertTo-Json -Compress -Depth 4)
Log ('camera delete: ' + $del)
$h1 | ConvertTo-Json -Compress | Set-Content (Join-Path $OutDir 'health-after.json') -Encoding UTF8
$summary | Set-Content (Join-Path $OutDir 'summary.txt') -Encoding UTF8
Log "S5 RESULT: $(if ($ok -and $frameDelta -gt 0) { 'PASS' } else { 'FAIL' }) evidence=$OutDir"
if ($ok -and $frameDelta -gt 0) { exit 0 } else { exit 1 }
