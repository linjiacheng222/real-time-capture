#Requires -Version 5.1
<#
S2.4 listener flip check: camera yaw0 vs yaw180, fixed +X sound, fresh audio WS per segment.
If captured stereo channel difference flips sign, listener sync is affecting the loopback mix;
if not, captured audio keeps the player listener (design issue to record).

Usage: powershell -ExecutionPolicy Bypass -File scripts\s2-listener-flip-check.ps1
#>
param(
    [string]$WsUrl = 'ws://127.0.0.1:25581/',
    [string]$OutDir = '',
    [double]$Volume = 4.0,
    [int]$CaptureSeconds = 3
)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if (-not $OutDir) { $OutDir = Join-Path $root "docs\evidence\S2\listener-flip-$stamp" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
function Log([string]$t) { Write-Host $t; Add-Content -Path (Join-Path $OutDir 'summary.txt') -Value $t -Encoding UTF8 }
function WsCommand([string]$json) {
    $ws = New-Object System.Net.WebSockets.ClientWebSocket
    $ct = [Threading.CancellationToken]::None
    try {
        $ws.ConnectAsync([Uri]$WsUrl, $ct).Wait(5000) | Out-Null
        $bytes = [Text.Encoding]::UTF8.GetBytes($json)
        $seg = New-Object ArraySegment[byte] -ArgumentList @(,$bytes)
        $ws.SendAsync($seg, [Net.WebSockets.WebSocketMessageType]::Text, $true, $ct).Wait(5000) | Out-Null
        $deadline = (Get-Date).AddSeconds(5)
        $sb = New-Object Text.StringBuilder
        while ((Get-Date) -lt $deadline) {
            $buf = New-Object byte[] 65536
            $rseg = New-Object ArraySegment[byte] -ArgumentList @(,$buf)
            try { $task = $ws.ReceiveAsync($rseg, $ct); if (-not $task.Wait(3000)) { continue } } catch { return "ERR: $($_.Exception.Message)" }
            if ($task.Result.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Text) {
                [void]$sb.Append([Text.Encoding]::UTF8.GetString($buf, 0, $task.Result.Count))
                if ($task.Result.EndOfMessage) { return $sb.ToString() }
            }
        }
        return 'TIMEOUT'
    } catch { return "ERR: $($_.Exception.Message)" } finally { try { $ws.Dispose() } catch {} }
}
function Connect-AudioWs {
    $ws = New-Object System.Net.WebSockets.ClientWebSocket
    $ct = [Threading.CancellationToken]::None
    $ws.ConnectAsync([Uri]$WsUrl, $ct).Wait(5000) | Out-Null
    return $ws
}
function Capture-Pcm([System.Net.WebSockets.ClientWebSocket]$ws, [double]$seconds) {
    $ct = [Threading.CancellationToken]::None
    $deadline = (Get-Date).AddSeconds($seconds)
    $out = New-Object System.IO.MemoryStream
    $msg = New-Object System.IO.MemoryStream
    $msgType = $null
    $chunks = 0
    while ((Get-Date) -lt $deadline) {
        $buf = New-Object byte[] 65536
        $seg = New-Object ArraySegment[byte] -ArgumentList @(,$buf)
        try { $task = $ws.ReceiveAsync($seg, $ct); if (-not $task.Wait(3000)) { continue } } catch { break }
        if ($task.Result.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Close) { break }
        if ($null -eq $msgType) { $msgType = $task.Result.MessageType }
        if ($task.Result.Count -gt 0) { $msg.Write($buf, 0, $task.Result.Count) }
        if (-not $task.Result.EndOfMessage) { continue }
        $arr = $msg.ToArray(); $msg.SetLength(0) | Out-Null; $type = $msgType; $msgType = $null
        if ($type -ne [Net.WebSockets.WebSocketMessageType]::Binary) { continue }
        if ($arr.Length -lt 20 -or $arr.Length -gt 8192) { continue }
        $out.Write($arr, 20, $arr.Length - 20); $chunks++
    }
    return [pscustomobject]@{ Bytes = $out.ToArray(); Chunks = $chunks }
}
function Play-Pos([double]$dx, [double]$dy, [double]$dz) {
    $cmd = @{ id = 'snd'; action = 'client.playPosSound'; params = @{ dx = $dx; dy = $dy; dz = $dz; volume = $Volume } } | ConvertTo-Json -Compress -Depth 4
    return (WsCommand $cmd)
}
function Close-Ws([System.Net.WebSockets.ClientWebSocket]$ws) {
    try { if ($ws.State -eq [Net.WebSockets.WebSocketState]::Open) { $ws.CloseAsync([Net.WebSockets.WebSocketCloseStatus]::NormalClosure, 'bye', [Threading.CancellationToken]::None).Wait(2000) | Out-Null } } catch {}
    $ws.Dispose()
}
function Segment([double]$yaw, [string]$tag, [bool]$withSound) {
    Log "segment $tag yaw=$yaw sound=$withSound"
    $audio = Connect-AudioWs
    Start-Sleep -Milliseconds 300
    if ($withSound) { Log ("play -> " + (Play-Pos 8.0 0.0 0.0)) }
    $cap = Capture-Pcm $audio $CaptureSeconds
    Close-Ws $audio
    [IO.File]::WriteAllBytes((Join-Path $OutDir ("pcm-{0}.raw" -f $tag)), $cap.Bytes)
    Log "segment $tag chunks=$($cap.Chunks) bytes=$($cap.Bytes.Length)"
}

Log "listener flip start: $stamp"
try { (Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:25580/health' -TimeoutSec 3).Content | Out-Null; Log 'health OK' } catch { Log 'health FAIL'; exit 2 }
Log ('unpause: ' + (WsCommand '{"id":"u","action":"client.unpause","params":{}}'))
Start-Sleep -Milliseconds 500
$pose = (WsCommand '{"id":"p","action":"client.playerPose","params":{}}' | ConvertFrom-Json).result
if (-not $pose) { Log 'no player pose'; exit 3 }
$st = WsCommand '{"id":"st","action":"camera.status","params":{}}' | ConvertFrom-Json
if ($st.ok -and $st.result.active -and [int]$st.result.active -gt 0) {
    $del = @{ id = 'del'; action = 'camera.delete'; params = @{ id = [int]$st.result.active } } | ConvertTo-Json -Compress -Depth 4
    WsCommand $del | Out-Null
}
$create = @{ id = 'cam'; action = 'camera.create'; params = @{ x = $pose.x; y = $pose.eyeY; z = $pose.z; yaw = 0.0; pitch = 0.0; fov = 70.0 } } | ConvertTo-Json -Compress -Depth 4
$cr = WsCommand $create | ConvertFrom-Json
Log "camera create: $($cr | ConvertTo-Json -Compress)"
if (-not $cr.ok) { exit 4 }
$camId = [int]$cr.result.id
Start-Sleep -Seconds 2

Segment 0 'yaw0-silence' $false
Segment 0 'yaw0-sound' $true
Start-Sleep -Seconds 3
$adj = @{ id = 'adj'; action = 'camera.adjust'; params = @{ id = $camId; x = $pose.x; y = $pose.eyeY; z = $pose.z; yaw = 180.0; pitch = 0.0; fov = 70.0 } } | ConvertTo-Json -Compress -Depth 4
Log ("camera yaw180: " + (WsCommand $adj))
Start-Sleep -Seconds 2
Segment 180 'yaw180-silence' $false
Segment 180 'yaw180-sound' $true
$delCam = @{ id = 'del'; action = 'camera.delete'; params = @{ id = $camId } } | ConvertTo-Json -Compress -Depth 4
Log ("camera delete: " + (WsCommand $delCam))
Log 'DONE'
exit 0
