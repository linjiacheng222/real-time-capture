#Requires -Version 5.1
<#
S2.4 directional audio data check (second phase / P0).
Creates a camera at player eye pose facing +Z (yaw=0,pitch=0), plays a 3D bell at
front/back/left/right/up/down offsets, captures WS audio PCM for baseline + sound,
then rotates camera 180 and repeats left/right (flip check).

Output: docs/evidence/S2/directional-<stamp>/
Usage: powershell -ExecutionPolicy Bypass -File scripts\s2-directional-check.ps1
#>
param(
    [string]$BaseUrl = 'http://127.0.0.1:25580',
    [string]$WsUrl = 'ws://127.0.0.1:25581/',
    [int]$BaselineSeconds = 1,
    [int]$SoundSeconds = 2,
    [string]$OutDir = ''
)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if (-not $OutDir) { $OutDir = Join-Path $root "docs\evidence\S2\directional-$stamp" }
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
    $count = 0
    while ((Get-Date) -lt $deadline) {
        $buf = New-Object byte[] 65536
        $seg = New-Object ArraySegment[byte] -ArgumentList @(,$buf)
        try { $task = $ws.ReceiveAsync($seg, $ct); if (-not $task.Wait(3000)) { continue } } catch { break }
        if ($task.Result.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Close) { break }
        if ($null -eq $msgType) { $msgType = $task.Result.MessageType }
        if ($task.Result.Count -gt 0) { $msg.Write($buf, 0, $task.Result.Count) }
        if (-not $task.Result.EndOfMessage) { continue }
        $arr = $msg.ToArray()
        $msg.SetLength(0) | Out-Null
        $type = $msgType
        $msgType = $null
        if ($type -ne [Net.WebSockets.WebSocketMessageType]::Binary) { continue }
        # audio chunk = 20B header + <= a few KB PCM; skip large video frames
        if ($arr.Length -lt 20 -or $arr.Length -gt 8192) { continue }
        $out.Write($arr, 20, $arr.Length - 20)
        $count++
    }
    return [pscustomobject]@{ Bytes = $out.ToArray(); Chunks = $count }
}
function Play-Pos([double]$dx, [double]$dy, [double]$dz) {
    $cmd = @{ id = 'snd'; action = 'client.playPosSound'; params = @{ dx = $dx; dy = $dy; dz = $dz; volume = 1.0 } } | ConvertTo-Json -Compress -Depth 4
    return (WsCommand $cmd)
}

Log "S2.4 directional check start: $stamp, out=$OutDir"
try { $h = (Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/health" -TimeoutSec 3).Content | ConvertFrom-Json } catch { $h = $null }
if (-not $h) { Log 'FAIL health unavailable'; exit 2 }
$h | ConvertTo-Json -Compress | Set-Content -Path (Join-Path $OutDir 'health.json') -Encoding UTF8
Log 'PASS /health 200'

Log ('unpause: ' + (WsCommand '{"id":"u","action":"client.unpause","params":{}}'))
Start-Sleep -Milliseconds 500
$poseResp = WsCommand '{"id":"p","action":"client.playerPose","params":{}}'
Log "playerPose: $poseResp"
$pose = $poseResp | ConvertFrom-Json
if (-not $pose.ok) { Log 'FAIL no player pose'; exit 3 }
# remove existing cameras
$st = WsCommand '{"id":"st","action":"camera.status","params":{}}' | ConvertFrom-Json
if ($st.ok -and $st.result.active -and [int]$st.result.active -gt 0) {
    $del = @{ id = 'del'; action = 'camera.delete'; params = @{ id = [int]$st.result.active } } | ConvertTo-Json -Compress -Depth 4
    Log ('delete camera: ' + (WsCommand $del))
}
$create = @{ id = 'cam'; action = 'camera.create'; params = @{ x = $pose.result.x; y = $pose.result.eyeY; z = $pose.result.z; yaw = 0.0; pitch = 0.0; fov = 70.0 } } | ConvertTo-Json -Compress -Depth 4
$createResp = WsCommand $create
Log "camera.create yaw=0: $createResp"
$cam = $createResp | ConvertFrom-Json
if (-not $cam.ok) { Log 'FAIL camera create'; exit 4 }
$camId = [int]$cam.result.id
Start-Sleep -Seconds 2

$audioWs = Connect-AudioWs
$directions = @(
    @{ Name = 'front'; Dx = 0.0;  Dy = 0.0; Dz = 8.0;  Expected = 'L≈R, total high' },
    @{ Name = 'back';  Dx = 0.0;  Dy = 0.0; Dz = -8.0; Expected = 'L≈R, total high (front/back may be indistinguishable in plain stereo)' },
    @{ Name = 'left';  Dx = 8.0;  Dy = 0.0; Dz = 0.0;  Expected = 'left channel higher' },
    @{ Name = 'right'; Dx = -8.0; Dy = 0.0; Dz = 0.0;  Expected = 'right channel higher' },
    @{ Name = 'up';    Dx = 0.0;  Dy = 8.0; Dz = 0.0;  Expected = 'L≈R (vertical may be limited in stereo)' },
    @{ Name = 'down';  Dx = 0.0;  Dy = -8.0; Dz = 0.0; Expected = 'L≈R (vertical may be limited in stereo)' }
)
foreach ($d in $directions) {
    $baseline = Capture-Pcm $audioWs $BaselineSeconds
    [IO.File]::WriteAllBytes((Join-Path $OutDir ("pcm-{0}-baseline.raw" -f $d.Name)), $baseline.Bytes)
    Log ("{0}: baseline chunks={1}" -f $d.Name, $baseline.Chunks)
    $playResp = Play-Pos $d.Dx $d.Dy $d.Dz
    Log ("{0}: playPos -> {1}" -f $d.Name, $playResp)
    $sound = Capture-Pcm $audioWs $SoundSeconds
    [IO.File]::WriteAllBytes((Join-Path $OutDir ("pcm-{0}-sound.raw" -f $d.Name)), $sound.Bytes)
    Log ("{0}: sound chunks={1} expected={2}" -f $d.Name, $sound.Chunks, $d.Expected)
    Start-Sleep -Milliseconds 500
}

# 180° flip: adjust camera yaw=180, repeat left/right
$adjust = @{ id = 'adj'; action = 'camera.adjust'; params = @{ id = $camId; x = $pose.result.x; y = $pose.result.eyeY; z = $pose.result.z; yaw = 180.0; pitch = 0.0; fov = 70.0 } } | ConvertTo-Json -Compress -Depth 4
Log ('camera yaw=180: ' + (WsCommand $adjust))
Start-Sleep -Seconds 2
foreach ($d in @(@{Name='flip-left';Dx=8.0;Dy=0.0;Dz=0.0}, @{Name='flip-right';Dx=-8.0;Dy=0.0;Dz=0.0})) {
    $b = Capture-Pcm $audioWs $BaselineSeconds
    $p = Play-Pos $d.Dx $d.Dy $d.Dz
    Log ("{0}: playPos -> {1}" -f $d.Name, $p)
    $s = Capture-Pcm $audioWs $SoundSeconds
    [IO.File]::WriteAllBytes((Join-Path $OutDir ("pcm-{0}-sound.raw" -f $d.Name)), $s.Bytes)
    Log ("{0}: sound chunks={1}" -f $d.Name, $s.Chunks)
}
try { if ($audioWs.State -eq [Net.WebSockets.WebSocketState]::Open) { $audioWs.CloseAsync([Net.WebSockets.WebSocketCloseStatus]::NormalClosure, 'bye', [Threading.CancellationToken]::None).Wait(2000) | Out-Null } } catch {}
$audioWs.Dispose()
$delCam = @{ id = 'del'; action = 'camera.delete'; params = @{ id = $camId } } | ConvertTo-Json -Compress -Depth 4
Log ('delete camera: ' + (WsCommand $delCam))
Log 'DONE. Run scripts/analyze-s2-directional.py <outDir> to compute channel energy.'
exit 0
