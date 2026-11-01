#Requires -Version 5.1
<#
S2.4 six-direction objective check (fresh audio WS per direction to avoid slow-consumer queue).
Camera at player eye, yaw=0/pitch=0; 3D bell at front/back/left/right/up/down (8 blocks), volume 4.
Saves pcm-<dir>-sound.raw for analyze-s2-directional.py.

Usage: powershell -ExecutionPolicy Bypass -File scripts\s2-sixdir-check.ps1
#>
param(
    [string]$WsUrl = 'ws://127.0.0.1:25581/',
    [string]$OutDir = '',
    [double]$Volume = 4.0,
    [double]$CaptureSeconds = 2.0
)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if (-not $OutDir) { $OutDir = Join-Path $root "docs\evidence\S2\sixdir-$stamp" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
function Log([string]$t) { Write-Host $t; Add-Content -Path (Join-Path $OutDir 'summary.txt') -Value $t -Encoding UTF8 }
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
function Capture-Pcm-Fresh([double]$seconds) {
    $ws = New-Object System.Net.WebSockets.ClientWebSocket
    $ct = [Threading.CancellationToken]::None
    $ws.ConnectAsync([Uri]$WsUrl, $ct).Wait(5000) | Out-Null
    $out = New-Object System.IO.MemoryStream
    $msg = New-Object System.IO.MemoryStream
    $msgType = $null; $chunks = 0
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        $buf = New-Object byte[] 65536; $rseg = New-Object ArraySegment[byte] -ArgumentList @(,$buf)
        try { $task = $ws.ReceiveAsync($rseg, $ct); if (-not $task.Wait(3000)) { continue } } catch { break }
        if ($task.Result.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Close) { break }
        if ($null -eq $msgType) { $msgType = $task.Result.MessageType }
        if ($task.Result.Count -gt 0) { $msg.Write($buf, 0, $task.Result.Count) }
        if (-not $task.Result.EndOfMessage) { continue }
        $arr = $msg.ToArray(); $msg.SetLength(0) | Out-Null; $type = $msgType; $msgType = $null
        if ($type -ne [Net.WebSockets.WebSocketMessageType]::Binary) { continue }
        if ($arr.Length -lt 20 -or $arr.Length -gt 8192) { continue }
        $out.Write($arr, 20, $arr.Length - 20); $chunks++
    }
    try { if ($ws.State -eq [Net.WebSockets.WebSocketState]::Open) { $ws.CloseAsync([Net.WebSockets.WebSocketCloseStatus]::NormalClosure, 'bye', $ct).Wait(2000) | Out-Null } } catch {}
    $ws.Dispose()
    return [pscustomobject]@{ Bytes = $out.ToArray(); Chunks = $chunks }
}
Log "six-dir start: $stamp"
try { (Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:25580/health' -TimeoutSec 3).Content | Out-Null; Log 'health OK' } catch { Log 'health FAIL'; exit 2 }
Log ('unpause: ' + (WsCommand '{"id":"u","action":"client.unpause","params":{}}'))
Start-Sleep -Milliseconds 500
$pose = (WsCommand '{"id":"p","action":"client.playerPose","params":{}}' | ConvertFrom-Json).result
if (-not $pose) { Log 'no pose'; exit 3 }
$st = WsCommand '{"id":"st","action":"camera.status","params":{}}' | ConvertFrom-Json
if ($st.ok -and $st.result.active -and [int]$st.result.active -gt 0) {
    WsCommand (@{ id = 'del'; action = 'camera.delete'; params = @{ id = [int]$st.result.active } } | ConvertTo-Json -Compress -Depth 4) | Out-Null
}
$create = @{ id = 'cam'; action = 'camera.create'; params = @{ x = $pose.x; y = $pose.eyeY; z = $pose.z; yaw = 0.0; pitch = 0.0; fov = 70.0 } } | ConvertTo-Json -Compress -Depth 4
$cr = WsCommand $create | ConvertFrom-Json
Log "camera: $($cr | ConvertTo-Json -Compress)"
if (-not $cr.ok) { exit 4 }
$camId = [int]$cr.result.id
Start-Sleep -Seconds 2
$directions = @(
    @{ Name = 'front'; Dx = 0.0;  Dy = 0.0;  Dz = 8.0 },
    @{ Name = 'back';  Dx = 0.0;  Dy = 0.0;  Dz = -8.0 },
    @{ Name = 'left';  Dx = 8.0;  Dy = 0.0;  Dz = 0.0 },
    @{ Name = 'right'; Dx = -8.0; Dy = 0.0;  Dz = 0.0 },
    @{ Name = 'up';    Dx = 0.0;  Dy = 8.0;  Dz = 0.0 },
    @{ Name = 'down';  Dx = 0.0;  Dy = -8.0; Dz = 0.0 }
)
foreach ($d in $directions) {
    $cmd = @{ id = 'snd'; action = 'client.playPosSound'; params = @{ dx = $d.Dx; dy = $d.Dy; dz = $d.Dz; volume = $Volume } } | ConvertTo-Json -Compress -Depth 4
    $play = WsCommand $cmd
    $cap = Capture-Pcm-Fresh $CaptureSeconds
    [IO.File]::WriteAllBytes((Join-Path $OutDir ("pcm-{0}-sound.raw" -f $d.Name)), $cap.Bytes)
    Log ("{0}: chunks={1} bytes={2} play={3}" -f $d.Name, $cap.Chunks, $cap.Bytes.Length, $play)
    Start-Sleep -Milliseconds 800
}
$del = @{ id = 'del'; action = 'camera.delete'; params = @{ id = $camId } } | ConvertTo-Json -Compress -Depth 4
Log ('camera delete: ' + (WsCommand $del))
Log 'DONE'
exit 0
