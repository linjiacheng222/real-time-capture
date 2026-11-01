#Requires -Version 5.1
<#
S3 E2E automated portion (E2E-1/2/3/5/6 + health/leak snapshot).
Prerequisite: client running in a world; script creates a temporary camera and removes it.
Usage: powershell -ExecutionPolicy Bypass -File scripts\s3-e2e-check.ps1
#>
param(
    [string]$BaseUrl = 'http://127.0.0.1:25580',
    [string]$WsUrl = 'ws://127.0.0.1:25581/',
    [string]$OutDir = ''
)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if (-not $OutDir) { $OutDir = Join-Path $root "docs\evidence\S3\e2e-$stamp" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$summary = New-Object System.Collections.Generic.List[string]
function Log([string]$t) { Write-Host $t; $summary.Add($t) }
function Http-Bytes([string]$url, [int]$timeoutMs = 5000) {
    $req = [System.Net.HttpWebRequest]::Create($url)
    $req.Timeout = $timeoutMs
    $req.ReadWriteTimeout = $timeoutMs
    $resp = $null
    try { $resp = $req.GetResponse() } catch [System.Net.WebException] {
        if ($_.Exception.Response) { $resp = $_.Exception.Response } else { return [pscustomobject]@{ Code = 0; Body = $null; Headers = $null; Error = $_.Exception.Message } }
    }
    if ($null -eq $resp) { return [pscustomobject]@{ Code = 0; Body = $null; Headers = $null; Error = 'no response' } }
    $code = [int]$resp.StatusCode
    $body = $null
    if ($code -eq 200) { $ms = New-Object System.IO.MemoryStream; $resp.GetResponseStream().CopyTo($ms); $body = $ms.ToArray(); $ms.Dispose() }
    $headers = @{}
    foreach ($key in $resp.Headers.AllKeys) { $headers[$key] = $resp.Headers[$key] }
    $resp.Dispose()
    return [pscustomobject]@{ Code = $code; Body = $body; Headers = $headers; Error = '' }
}
function WsCommand([string]$json) {
    $ws = New-Object System.Net.WebSockets.ClientWebSocket
    $ct = [Threading.CancellationToken]::None
    try {
        $ws.ConnectAsync([Uri]$WsUrl, $ct).Wait(5000) | Out-Null
        $bytes = [Text.Encoding]::UTF8.GetBytes($json); $seg = New-Object ArraySegment[byte] -ArgumentList @(,$bytes)
        $ws.SendAsync($seg, [Net.WebSockets.WebSocketMessageType]::Text, $true, $ct).Wait(5000) | Out-Null
        $deadline = (Get-Date).AddSeconds(5); $sb = New-Object Text.StringBuilder
        while ((Get-Date) -lt $deadline) {
            $buf = New-Object byte[] 65536; $rseg = New-Object ArraySegment[byte] -ArgumentList @(,$buf)
            try { $task = $ws.ReceiveAsync($rseg, $ct); if (-not $task.Wait(3000)) { continue } } catch { return "ERR: $($_.Exception.Message)" }
            if ($task.Result.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Text) {
                [void]$sb.Append([Text.Encoding]::UTF8.GetString($buf, 0, $task.Result.Count))
                if ($task.Result.EndOfMessage) { return $sb.ToString() }
            }
        }
        return 'TIMEOUT'
    } catch { return "ERR: $($_.Exception.Message)" } finally { try { $ws.Dispose() } catch {} }
}
function WsBinaryCapture([double]$seconds) {
    $ws = New-Object System.Net.WebSockets.ClientWebSocket
    $ct = [Threading.CancellationToken]::None
    $ws.ConnectAsync([Uri]$WsUrl, $ct).Wait(5000) | Out-Null
    $deadline = (Get-Date).AddSeconds($seconds)
    $msg = New-Object System.IO.MemoryStream; $msgType = $null
    $video = 0; $audio = 0; $bad = 0
    while ((Get-Date) -lt $deadline) {
        $buf = New-Object byte[] 65536; $rseg = New-Object ArraySegment[byte] -ArgumentList @(,$buf)
        try { $task = $ws.ReceiveAsync($rseg, $ct); if (-not $task.Wait(3000)) { continue } } catch { break }
        if ($task.Result.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Close) { break }
        if ($null -eq $msgType) { $msgType = $task.Result.MessageType }
        if ($task.Result.Count -gt 0) { $msg.Write($buf, 0, $task.Result.Count) }
        if (-not $task.Result.EndOfMessage) { continue }
        $arr = $msg.ToArray(); $msg.SetLength(0) | Out-Null; $type = $msgType; $msgType = $null
        if ($type -ne [Net.WebSockets.WebSocketMessageType]::Binary -or $arr.Length -lt 16) { continue }
        # 视频头 <qhhI> = 16B；音频头 20B。先尝试视频
        $ts = [BitConverter]::ToInt64($arr, 0)
        $w = [BitConverter]::ToInt16($arr, 8)
        $h = [BitConverter]::ToInt16($arr, 10)
        $plen = [BitConverter]::ToInt32($arr, 12)
        if ($w -gt 0 -and $h -gt 0 -and $plen -eq ($arr.Length - 16) -and $arr.Length -gt 16 -and $arr[16] -eq 0xFF -and $arr[17] -eq 0xD8) { $video++; continue }
        if ($arr.Length -ge 20) {
            $samples = [BitConverter]::ToInt32($arr, 16)
            if ($samples -eq 441 -and ($arr.Length - 20) -eq 1764) { $audio++; continue }
        }
        $bad++
    }
    try { if ($ws.State -eq [Net.WebSockets.WebSocketState]::Open) { $ws.CloseAsync([Net.WebSockets.WebSocketCloseStatus]::NormalClosure, 'bye', $ct).Wait(2000) | Out-Null } } catch {}
    $ws.Dispose()
    return [pscustomobject]@{ Video = $video; Audio = $audio; Bad = $bad }
}
function Get-Mjpeg([string]$url, [int]$timeoutMs = 5000) {
    $req = [System.Net.HttpWebRequest]::Create($url)
    $req.Timeout = $timeoutMs; $req.ReadWriteTimeout = $timeoutMs
    $resp = $null
    try { $resp = $req.GetResponse() } catch { return [pscustomobject]@{ Code = 0; ContentType = ''; Head = $null } }
    $code = [int]$resp.StatusCode
    $ctype = $resp.ContentType
    $stream = $resp.GetResponseStream()
    $buf = New-Object byte[] 8192
    $total = 0
    try {
        $stream.ReadTimeout = $timeoutMs
        while ($total -lt $buf.Length) {
            $n = $stream.Read($buf, $total, $buf.Length - $total)
            if ($n -le 0) { break }
            $total += $n
            if ($total -gt 64 -and [System.Text.Encoding]::ASCII.GetString($buf, 0, $total) -match 'Content-Type: image/jpeg') { break }
        }
    } catch {}
    try { $stream.Close() } catch {}
    try { $resp.Close() } catch {}
    return [pscustomobject]@{ Code = $code; ContentType = $ctype; Head = ($buf[0..([Math]::Min($total, $buf.Length) - 1)] | ForEach-Object { $_ }) }
}
Log "S3 E2E check start: $stamp"
$health = Http-Bytes "$BaseUrl/health"
if ($health.Code -ne 200) { Log 'FAIL health'; $summary | Set-Content (Join-Path $OutDir 'summary.txt') -Encoding UTF8; exit 2 }
$healthJson = [Text.Encoding]::UTF8.GetString($health.Body)
$healthJson | Set-Content (Join-Path $OutDir 'health-before.json') -Encoding UTF8
Log 'PASS health'
Write-Output ('unpause: ' + (WsCommand '{"id":"u","action":"client.unpause","params":{}}')) | Out-Null
$pose = (WsCommand '{"id":"p","action":"client.playerPose","params":{}}' | ConvertFrom-Json).result
if (-not $pose) { Log 'FAIL player pose'; exit 3 }
$st = WsCommand '{"id":"st","action":"camera.status","params":{}}' | ConvertFrom-Json
if ($st.ok -and $st.result.active -and [int]$st.result.active -gt 0) {
    WsCommand (@{ id = 'del'; action = 'camera.delete'; params = @{ id = [int]$st.result.active } } | ConvertTo-Json -Compress -Depth 4) | Out-Null
}
$create = WsCommand (@{ id = 'cam'; action = 'camera.create'; params = @{ x = $pose.x; y = $pose.eyeY; z = $pose.z; yaw = $pose.yaw; pitch = $pose.pitch; fov = $pose.fov } } | ConvertTo-Json -Compress -Depth 4)
Log "camera.create: $create"
$cam = $create | ConvertFrom-Json
if (-not $cam.ok) { Log 'FAIL camera create'; exit 4 }
$camId = [int]$cam.result.id
Start-Sleep -Seconds 3

# E2E-1 frame
$frame = Http-Bytes "$BaseUrl/camera/0/frame"
if ($frame.Code -eq 200 -and $frame.Body.Length -gt 2 -and $frame.Body[0] -eq 0xFF -and $frame.Body[1] -eq 0xD8) {
    [IO.File]::WriteAllBytes((Join-Path $OutDir 'frame-e2e1.jpg'), $frame.Body)
    Log "PASS E2E-1 frame 200 jpeg=$($frame.Body.Length)B ts=$($frame.Headers['X-Frame-Timestamp'])"
} else { Log "FAIL E2E-1 code=$($frame.Code)" }
($frame.Headers | ConvertTo-Json) | Set-Content (Join-Path $OutDir 'frame-headers.json') -Encoding UTF8

# E2E-2 WS binary
$wsData = WsBinaryCapture 3
Log "E2E-2 ws video=$($wsData.Video) audio=$($wsData.Audio) bad=$($wsData.Bad)"
if ($wsData.Video -gt 0 -and $wsData.Audio -gt 0) { Log 'PASS E2E-2 binary framing' } else { Log 'FAIL E2E-2 missing video/audio binary' }

# E2E-3 MJPEG
$mj = Get-Mjpeg "$BaseUrl/camera/0/stream" 5000
$mjOk = $mj.Code -eq 200 -and $mj.ContentType -match 'multipart/x-mixed-replace'
Log "E2E-3 mjpeg code=$($mj.Code) content-type=$($mj.ContentType)"
if ($mjOk) { Log 'PASS E2E-3 MJPEG headers' } else { Log 'FAIL E2E-3 MJPEG headers' }

# E2E-5 control
$adj = WsCommand (@{ id = 'adj'; action = 'camera.adjust'; params = @{ id = $camId; x = $pose.x; y = $pose.eyeY; z = $pose.z; yaw = ($pose.yaw + 10); pitch = $pose.pitch; fov = $pose.fov } } | ConvertTo-Json -Compress -Depth 4)
$bad = WsCommand '{"id":"bad","action":"unknown.action","params":{}}' 
$badObj = $bad | ConvertFrom-Json
Log "E2E-5 adjust=$adj bad=$bad"
if (($adj | ConvertFrom-Json).ok -and (-not $badObj.ok)) { Log 'PASS E2E-5 control/error' } else { Log 'FAIL E2E-5 control/error' }

# E2E-6 mode switch: frame -> ws -> frame
$f2 = Http-Bytes "$BaseUrl/camera/0/frame"
$wsData2 = WsBinaryCapture 1
$f3 = Http-Bytes "$BaseUrl/camera/0/frame"
$health2 = Http-Bytes "$BaseUrl/health"
$h2 = [Text.Encoding]::UTF8.GetString($health2.Body) | ConvertFrom-Json
Log "E2E-6 frame2=$($f2.Code) ws=$($wsData2.Video)/$($wsData2.Audio) frame3=$($f3.Code) connections=$($h2.connections) streams=$($h2.http_streams)"
if ($f2.Code -eq 200 -and $f3.Code -eq 200 -and [int]$h2.http_streams -eq 0) { Log 'PASS E2E-6 mode switch/leak' } else { Log 'FAIL E2E-6 mode switch/leak' }

$delCam = WsCommand (@{ id = 'del'; action = 'camera.delete'; params = @{ id = $camId } } | ConvertTo-Json -Compress -Depth 4)
Log "camera.delete: $delCam"
$summary | Set-Content (Join-Path $OutDir 'summary.txt') -Encoding UTF8
Log "evidence: $OutDir"
exit 0
