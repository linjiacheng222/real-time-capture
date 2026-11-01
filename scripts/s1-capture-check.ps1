#Requires -Version 5.1
<#
S1 world-frame verification helper (second phase / P0).
Prerequisites: Minecraft client running and a world loaded; mod network service on 127.0.0.1:25580/25581.

Usage (from repo root):
  powershell -ExecutionPolicy Bypass -File scripts\s1-capture-check.ps1 -X 100 -Y 70 -Z 200

What it does:
  1) GET /health
  2) WS client.unpause (close pause screen; S1 automation needs running world)
  3) WS camera.create (or use active camera with -NoCreate)
  4) Poll GET /camera/0/frame until 200 (or timeout); save JPEG/headers/analysis
  5) Adjust camera +N blocks on X; poll until the frame hash changes (S1.2)
  6) Delete template camera (unless -KeepCamera)
  7) Write summary.txt; exit 0 only if both frames are valid and different

Evidence goes to docs\evidence\S1\<timestamp>\ by default.
#>
param(
    [string]$BaseUrl = 'http://127.0.0.1:25580',
    [string]$WsUrl = 'ws://127.0.0.1:25581/',
    [double]$X = 0,
    [double]$Y = 64,
    [double]$Z = 0,
    [double]$Yaw = 0,
    [double]$Pitch = 0,
    [double]$Roll = 0,
    [double]$Fov = 70,
    [double]$MoveDX = 8,
    [int]$TimeoutSec = 20,
    [int]$ChangeTimeoutSec = 10,
    [switch]$NoCreate,
    [switch]$KeepCamera,
    [string]$OutDir = ''
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if (-not $OutDir) { $OutDir = Join-Path $root "docs\evidence\S1\$stamp" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$summary = New-Object System.Collections.Generic.List[string]
function Log([string]$text) {
    Write-Host $text
    $summary.Add($text)
}

function Http-Bytes([string]$url, [int]$timeoutMs = 5000) {
    $req = [System.Net.HttpWebRequest]::Create($url)
    $req.Timeout = $timeoutMs
    $req.ReadWriteTimeout = $timeoutMs
    $resp = $null
    try {
        $resp = $req.GetResponse()
    } catch [System.Net.WebException] {
        if ($_.Exception.Response) { $resp = $_.Exception.Response } else { return [pscustomobject]@{ Code = 0; Body = $null; Headers = $null; Error = $_.Exception.Message } }
    }
    if ($null -eq $resp) { return [pscustomobject]@{ Code = 0; Body = $null; Headers = $null; Error = 'no response' } }
    $code = [int]$resp.StatusCode
    $body = $null
    if ($code -eq 200) {
        $ms = New-Object System.IO.MemoryStream
        $resp.GetResponseStream().CopyTo($ms)
        $body = $ms.ToArray()
        $ms.Dispose()
    }
    $headers = @{}
    foreach ($key in $resp.Headers.AllKeys) { $headers[$key] = $resp.Headers[$key] }
    $resp.Dispose()
    return [pscustomobject]@{ Code = $code; Body = $body; Headers = $headers; Error = '' }
}

function Invoke-WsCommand([string]$json) {
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
            try {
                $task = $ws.ReceiveAsync($rseg, $ct)
                if (-not $task.Wait(3000)) { continue }
            } catch { return "WS-ERROR: $($_.Exception.Message)" }
            if ($task.Result.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Text) {
                [void]$sb.Append([Text.Encoding]::UTF8.GetString($buf, 0, $task.Result.Count))
                if ($task.Result.EndOfMessage) { return $sb.ToString() }
            } elseif ($task.Result.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Close) {
                return "WS-CLOSED: $($task.Result.CloseStatusDescription)"
            }
        }
        return 'WS-TIMEOUT'
    } catch {
        return "WS-ERROR: $($_.Exception.Message)"
    } finally {
        try {
            if ($ws.State -eq [Net.WebSockets.WebSocketState]::Open) {
                $ws.CloseAsync([Net.WebSockets.WebSocketCloseStatus]::NormalClosure, 'bye', $ct).Wait(2000) | Out-Null
            }
        } catch { }
        $ws.Dispose()
    }
}

function Image-Analysis([string]$path) {
    Add-Type -AssemblyName System.Drawing -ErrorAction SilentlyContinue
    if (-not (Test-Path $path)) { return 'missing' }
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $sig = if ($bytes.Length -ge 2 -and $bytes[0] -eq 0xFF -and $bytes[1] -eq 0xD8) { 'jpeg-signature=OK' } else { 'jpeg-signature=FAIL' }
    try {
        $img = [System.Drawing.Image]::FromFile($path)
        $w = $img.Width; $h = $img.Height
        $bmp = New-Object System.Drawing.Bitmap($img)
        $sum = 0.0; $sum2 = 0.0; $count = 0
        $stepX = [Math]::Max(1, [int]($w / 32)); $stepY = [Math]::Max(1, [int]($h / 32))
        for ($py = 0; $py -lt $h; $py += $stepY) {
            for ($px = 0; $px -lt $w; $px += $stepX) {
                $c = $bmp.GetPixel($px, $py)
                $v = ($c.R + $c.G + $c.B) / 3.0
                $sum += $v; $sum2 += $v * $v; $count++
            }
        }
        $mean = if ($count -gt 0) { $sum / $count } else { 0 }
        $var = if ($count -gt 0) { ($sum2 / $count) - ($mean * $mean) } else { 0 }
        $std = if ($var -gt 0) { [Math]::Sqrt($var) } else { 0 }
        $bmp.Dispose(); $img.Dispose()
        return ("{0}; {1}x{2}; mean={3:N2}; std={4:N2}" -f $sig, $w, $h, $mean, $std)
    } catch {
        return ("{0}; decode-failed={1}" -f $sig, $_.Exception.Message)
    }
}

function Fetch-Frame([string]$tag) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $r = Http-Bytes "$BaseUrl/camera/0/frame"
        if ($r.Code -eq 200 -and $r.Body -and $r.Body.Length -gt 0) {
            $path = Join-Path $OutDir "frame-$tag.jpg"
            [System.IO.File]::WriteAllBytes($path, $r.Body)
            ($r.Headers | ConvertTo-Json) | Set-Content -Path (Join-Path $OutDir "headers-$tag.json") -Encoding UTF8
            $analysis = Image-Analysis $path
            Log "PASS frame-$tag.jpg bytes=$($r.Body.Length) ts=$($r.Headers['X-Frame-Timestamp']) analysis=$analysis"
            return [pscustomobject]@{ OK = $true; Path = $path; Hash = (Get-FileHash $path -Algorithm SHA256).Hash; Analysis = $analysis }
        }
        if ($r.Code -eq 204) { Start-Sleep -Milliseconds 500; continue }
        Log "frame poll code=$($r.Code) error=$($r.Error)"
        Start-Sleep -Milliseconds 500
    }
    Log "FAIL frame-$tag timeout (no 200 within ${TimeoutSec}s)"
    return [pscustomobject]@{ OK = $false; Path = ''; Hash = ''; Analysis = '' }
}

Log "S1 check start: $stamp"
Log "OutDir: $OutDir"

$health = Http-Bytes "$BaseUrl/health"
if ($health.Code -ne 200) {
    Log "FAIL /health code=$($health.Code) error=$($health.Error)（游戏/世界未启动？）"
    $summary | Set-Content -Path (Join-Path $OutDir 'summary.txt') -Encoding UTF8
    exit 2
}
[Text.Encoding]::UTF8.GetString($health.Body) | Set-Content -Path (Join-Path $OutDir 'health.json') -Encoding UTF8
Log "PASS /health 200"

$unpause = Invoke-WsCommand '{"id":"s1-unpause","action":"client.unpause","params":{}}'
Log "WS unpause: $unpause"
Start-Sleep -Seconds 1

$cameraId = 0
if (-not $NoCreate) {
    $payload = @{ id = 's1-create'; action = 'camera.create'; params = @{ x = $X; y = $Y; z = $Z; yaw = $Yaw; pitch = $Pitch; roll = $Roll; fov = $Fov } } | ConvertTo-Json -Compress -Depth 6
    $resp = Invoke-WsCommand $payload
    Log "WS create: $resp"
    $resp | Set-Content -Path (Join-Path $OutDir 'ws-create.json') -Encoding UTF8
    try { $cameraId = [int](($resp | ConvertFrom-Json).result.id) } catch { $cameraId = 0 }
} else {
    $resp = Invoke-WsCommand '{"id":"s1-status","action":"camera.status","params":{}}'
    Log "WS status: $resp"
    try { $cameraId = [int](($resp | ConvertFrom-Json).result.active) } catch { $cameraId = 0 }
}
if ($cameraId -eq 0) {
    Log 'FAIL no active camera (create failed or none active)'
    $summary | Set-Content -Path (Join-Path $OutDir 'summary.txt') -Encoding UTF8
    exit 3
}
Log "camera id=$cameraId"

$first = Fetch-Frame 'original'
$moved = $null
$changed = $false
if ($first.OK) {
    if (-not $NoCreate -and $cameraId -gt 0) {
        $adjust = @{ id = 's1-adjust'; action = 'camera.adjust'; params = @{ id = $cameraId; x = ($X + $MoveDX); y = $Y; z = $Z; yaw = $Yaw; pitch = $Pitch; roll = $Roll; fov = $Fov } } | ConvertTo-Json -Compress -Depth 6
        $adjustResp = Invoke-WsCommand $adjust
        Log "WS adjust: $adjustResp"
        $deadline = (Get-Date).AddSeconds($ChangeTimeoutSec)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 800
            $candidate = Fetch-Frame 'moved'
            if ($candidate.OK -and $candidate.Hash -ne $first.Hash) {
                $moved = $candidate
                $changed = $true
                Log "S1.2 frame changed: True (hash $($first.Hash) -> $($candidate.Hash))"
                break
            }
        }
        if (-not $changed) {
            Log "S1.2 frame changed: False within ${ChangeTimeoutSec}s"
        }
    } else {
        Log 'S1.2 skipped (NoCreate)'
    }
}

if (-not $KeepCamera -and -not $NoCreate -and $cameraId -gt 0) {
    $del = @{ id = 's1-delete'; action = 'camera.delete'; params = @{ id = $cameraId } } | ConvertTo-Json -Compress -Depth 6
    Log "WS delete: $(Invoke-WsCommand $del)"
}

$ok = $first.OK -and ($first.Analysis -match 'jpeg-signature=OK')
if ($ok) { Log 'S1.1 RESULT: PASS (frame exists and is JPEG)' } else { Log 'S1.1 RESULT: FAIL (see analysis above)'; $ok = $false }
if ($changed) { Log 'S1.2 RESULT: PASS (camera adjustment changed frame)' } else { Log 'S1.2 RESULT: FAIL (frame unchanged after camera adjustment)' }
Log 'S1.3/S1.4: manual (compare with F2 screenshot; repeat world enter/leave 10 rounds)'
$summary | Set-Content -Path (Join-Path $OutDir 'summary.txt') -Encoding UTF8
Log "summary: $(Join-Path $OutDir 'summary.txt')"
if ($ok -and $changed) { exit 0 } else { exit 1 }
