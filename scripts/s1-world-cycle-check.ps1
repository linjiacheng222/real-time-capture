#Requires -Version 5.1
<#
S1.4 world enter/leave 10 rounds automation.
Uses test-only commands: client.unpause / client.playerPose / client.screenshot / client.leaveWorld / client.loadWorld.
Prerequisite: client running with world loaded; run:
  powershell -ExecutionPolicy Bypass -File scripts\s1-world-cycle-check.ps1 -Rounds 10
#>
param(
    [string]$BaseUrl = 'http://127.0.0.1:25580',
    [string]$WsUrl = 'ws://127.0.0.1:25581/',
    [string]$World = 's1test',
    [int]$Rounds = 10,
    [int]$LoadTimeoutSec = 40,
    [string]$OutDir = ''
)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if (-not $OutDir) { $OutDir = Join-Path $root "docs\evidence\S1\cycle-$stamp" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$summary = New-Object System.Collections.Generic.List[string]
function Log([string]$text) { Write-Host $text; $summary.Add($text) }
function Ws([string]$json) {
    $ws = New-Object System.Net.WebSockets.ClientWebSocket
    $ct = [Threading.CancellationToken]::None
    try {
        $ws.ConnectAsync([Uri]$WsUrl, $ct).Wait(5000) | Out-Null
        $bytes = [Text.Encoding]::UTF8.GetBytes($json)
        $seg = New-Object ArraySegment[byte] -ArgumentList @(,$bytes)
        $ws.SendAsync($seg, [Net.WebSockets.WebSocketMessageType]::Text, $true, $ct).Wait(5000) | Out-Null
        for ($i = 0; $i -lt 30; $i++) {
            $buf = New-Object byte[] 65536
            $rseg = New-Object ArraySegment[byte] -ArgumentList @(,$buf)
            try { $t = $ws.ReceiveAsync($rseg, $ct); if (-not $t.Wait(3000)) { continue } } catch { return "ERR: $($_.Exception.Message)" }
            if ($t.Result.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Text) { return [Text.Encoding]::UTF8.GetString($buf, 0, $t.Result.Count) }
        }
        return 'TIMEOUT'
    } catch { return "ERR: $($_.Exception.Message)" } finally { try { $ws.Dispose() } catch {} }
}
function Health() {
    try { return (Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/health" -TimeoutSec 3).Content | ConvertFrom-Json } catch { return $null }
}
function Wait-Pose([int]$timeoutSec, [bool]$expectPlayer) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $r = Ws '{"id":"pose","action":"client.playerPose","params":{}}'
        $ok = ($r -notmatch 'ERR' -and $r -notmatch 'TIMEOUT' -and $r -match '"ok":true')
        if ($ok -eq $expectPlayer) { return $r }
        Start-Sleep -Milliseconds 800
    }
    return 'TIMEOUT'
}
function Fetch-Frame([string]$path) {
    $deadline = (Get-Date).AddSeconds(15)
    while ((Get-Date) -lt $deadline) {
        $req = [Net.HttpWebRequest]::Create("$BaseUrl/camera/0/frame")
        $req.Timeout = 5000
        try { $resp = $req.GetResponse() } catch { Start-Sleep -Milliseconds 500; continue }
        if ([int]$resp.StatusCode -ne 200) { $resp.Dispose(); Start-Sleep -Milliseconds 500; continue }
        $ms = New-Object IO.MemoryStream
        $resp.GetResponseStream().CopyTo($ms)
        $bytes = $ms.ToArray()
        $resp.Dispose()
        if ($bytes.Length -gt 2) {
            [IO.File]::WriteAllBytes($path, $bytes)
            return $bytes.Length
        }
        Start-Sleep -Milliseconds 500
    }
    return 0
}
Log "S1.4 cycle check start: $stamp, rounds=$Rounds, world=$World"
$failures = 0
# ensure a world is loaded before the first round
if ((Ws '{"id":"pose0","action":"client.playerPose","params":{}}') -notmatch '"ok":true') {
    $initLoad = @{ id = 'initload'; action = 'client.loadWorld'; params = @{ name = $World } } | ConvertTo-Json -Compress -Depth 4
    Log "initial load: $(Ws $initLoad)"
    Log "initial wait: $(Wait-Pose $LoadTimeoutSec $true)"
}
for ($round = 1; $round -le $Rounds; $round++) {
    Log "=== round $round ==="
    Ws '{"id":"u","action":"client.unpause","params":{}}' | Out-Null
    $poseResp = Ws '{"id":"p","action":"client.playerPose","params":{}}'
    $pose = $poseResp | ConvertFrom-Json
    if (-not $pose.ok) {
        Log "round $round FAIL: no in-world player pose ($poseResp)"
        $failures++
        continue
    }
    # clear an existing active camera (idempotent)
    $st = Ws '{"id":"st","action":"camera.status","params":{}}' | ConvertFrom-Json
    if ($st.ok -and $st.result.active -and [int]$st.result.active -gt 0) {
        $delOld = @{ id = 'del'; action = 'camera.delete'; params = @{ id = [int]$st.result.active } } | ConvertTo-Json -Compress -Depth 4
        Ws $delOld | Out-Null
    }
    $c = @{ id = "c"; action = "camera.create"; params = @{ x = $pose.result.x; y = $pose.result.eyeY; z = $pose.result.z; yaw = $pose.result.yaw; pitch = $pose.result.pitch; fov = $pose.result.fov } } | ConvertTo-Json -Compress -Depth 5
    $create = Ws $c | ConvertFrom-Json
    if (-not $create.ok) {
        Log "round $round FAIL: camera.create $($create | ConvertTo-Json -Compress)"
        $failures++
        continue
    }
    Start-Sleep -Seconds 3
    $frame = Join-Path $OutDir ("round-{0:D2}.jpg" -f $round)
    $bytes = Fetch-Frame $frame
    if ($bytes -gt 0) {
        $hash = (Get-FileHash $frame -Algorithm SHA256).Hash
        Log "round ${round}: frame bytes=$bytes hash=$hash"
    } else {
        Log "round $round FAIL: no frame"
        $failures++
    }
    # delete camera
    $delCam = @{ id = 'del'; action = 'camera.delete'; params = @{ id = [int]$create.result.id } } | ConvertTo-Json -Compress -Depth 4
    Ws $delCam | Out-Null
    # leave world; wait until player pose no longer available
    Ws '{"id":"leave","action":"client.leaveWorld","params":{}}' | Out-Null
    $left = Wait-Pose 20 $false
    Log "round ${round}: left world=$($left -match 'ok.\":false|ERR|TIMEOUT') resp=$left"
    # load world again
    $loadJson = @{ id = 'load'; action = 'client.loadWorld'; params = @{ name = $World } } | ConvertTo-Json -Compress -Depth 4
    $load = Ws $loadJson
    Log "round ${round}: load resp=$load"
    $back = Wait-Pose $LoadTimeoutSec $true
    if ($back -match '"ok":true') {
        Log "round ${round}: re-entered world"
    } else {
        Log "round $round FAIL: could not re-enter world ($back)"
        $failures++
        break
    }
}
$h = Health
if ($h) { $h | ConvertTo-Json -Compress | Set-Content -Path (Join-Path $OutDir 'health-final.json') -Encoding UTF8 }
$summary | Set-Content -Path (Join-Path $OutDir 'summary.txt') -Encoding UTF8
Log "S1.4 RESULT: $(if ($failures -eq 0) { 'PASS' } else { "FAIL ($failures)" }) ; evidence=$OutDir"
if ($failures -eq 0) { exit 0 } else { exit 1 }
