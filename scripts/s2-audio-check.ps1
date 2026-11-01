#Requires -Version 5.1
<#
S2.2/S2.3 WS audio data check (second phase / P0).
- connects to WS, captures 20B-header audio chunks (441 samples/1764B expected)
- captures a baseline window, triggers a known bell via client.playTestSound, captures sound window
- saves raw PCM + CSV metadata for analysis; writes summary.txt

Usage:
  powershell -ExecutionPolicy Bypass -File scripts\s2-audio-check.ps1
#>
param(
    [string]$WsUrl = 'ws://127.0.0.1:25581/',
    [string]$OutDir = '',
    [int]$BaselineSeconds = 2,
    [int]$SoundSeconds = 4
)
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if (-not $OutDir) { $OutDir = Join-Path $root "docs\evidence\S2\$stamp" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$summary = New-Object System.Collections.Generic.List[string]
function Log([string]$t) { Write-Host $t; $summary.Add($t) }

function Connect-Ws {
    $ws = New-Object System.Net.WebSockets.ClientWebSocket
    $ct = [Threading.CancellationToken]::None
    $ws.ConnectAsync([Uri]$WsUrl, $ct).Wait(5000) | Out-Null
    return $ws
}
function Receive-Audio([System.Net.WebSockets.ClientWebSocket]$ws, [double]$seconds, [string]$tag) {
    $ct = [Threading.CancellationToken]::None
    $deadline = (Get-Date).AddSeconds($seconds)
    $chunks = New-Object System.Collections.Generic.List[object]
    $pcm = New-Object System.IO.MemoryStream
    $msg = New-Object System.IO.MemoryStream
    $msgType = $null
    while ((Get-Date) -lt $deadline) {
        $buf = New-Object byte[] 65536
        $seg = New-Object ArraySegment[byte] -ArgumentList @(,$buf)
        try {
            $task = $ws.ReceiveAsync($seg, $ct)
            if (-not $task.Wait(3000)) { continue }
        } catch { break }
        if ($task.Result.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Close) { break }
        if ($null -eq $msgType) { $msgType = $task.Result.MessageType }
        $count = $task.Result.Count
        if ($count -gt 0) { $msg.Write($buf, 0, $count) }
        if (-not $task.Result.EndOfMessage) { continue }
        $arr = $msg.ToArray()
        $msg.SetLength(0) | Out-Null
        $type = $msgType
        $msgType = $null
        if ($type -ne [Net.WebSockets.WebSocketMessageType]::Binary) { continue }
        if ($arr.Length -lt 20) { continue }
        $start = [BitConverter]::ToInt64($arr, 0)
        $end = [BitConverter]::ToInt64($arr, 8)
        $samples = [BitConverter]::ToInt32($arr, 16)
        $payload = $arr.Length - 20
        $pcm.Write($arr, 20, $payload)
        $chunks.Add([pscustomobject]@{ Start = $start; End = $end; Samples = $samples; PayloadBytes = $payload })
    }
    $pcmPath = Join-Path $OutDir ("pcm-$tag.raw")
    [System.IO.File]::WriteAllBytes($pcmPath, $pcm.ToArray())
    $csvPath = Join-Path $OutDir ("audio-$tag.csv")
    $chunks | Export-Csv -Path $csvPath -NoTypeInformation -Encoding UTF8
    return [pscustomobject]@{ Chunks = $chunks; PcmPath = $pcmPath; CsvPath = $csvPath }
}
function Close-Ws([System.Net.WebSockets.ClientWebSocket]$ws) {
    try { if ($ws.State -eq [Net.WebSockets.WebSocketState]::Open) { $ws.CloseAsync([Net.WebSockets.WebSocketCloseStatus]::NormalClosure, 'bye', [Threading.CancellationToken]::None).Wait(2000) | Out-Null } } catch {}
    $ws.Dispose()
}

Log "S2 audio check start: $stamp"
$health = $null
try { $health = (Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:25580/health' -TimeoutSec 3).Content | ConvertFrom-Json } catch {}
if (-not $health) { Log 'FAIL health unavailable'; $summary | Set-Content (Join-Path $OutDir 'summary.txt') -Encoding UTF8; exit 2 }
$health | ConvertTo-Json -Compress | Set-Content (Join-Path $OutDir 'health-before.json') -Encoding UTF8
Log 'PASS /health 200'

# baseline
$ws1 = Connect-Ws
Start-Sleep -Milliseconds 300
$base = Receive-Audio $ws1 $BaselineSeconds 'baseline'
Close-Ws $ws1
Log "baseline chunks=$($base.Chunks.Count) pcm=$($base.PcmPath)"

# sound window: unpause + trigger bell
$ws2 = Connect-Ws
Start-Sleep -Milliseconds 200
function Send-Text($ws, [string]$json) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    $seg = New-Object ArraySegment[byte] -ArgumentList @(,$bytes)
    $ws.SendAsync($seg, [Net.WebSockets.WebSocketMessageType]::Text, $true, [Threading.CancellationToken]::None).Wait(5000) | Out-Null
}
Send-Text $ws2 '{"id":"u","action":"client.unpause","params":{}}'
Start-Sleep -Milliseconds 500
Send-Text $ws2 '{"id":"t","action":"client.playTestSound","params":{}}'
$sound = Receive-Audio $ws2 $SoundSeconds 'sound'
Close-Ws $ws2
Log "sound chunks=$($sound.Chunks.Count) pcm=$($sound.PcmPath)"

$health2 = $null
try { $health2 = (Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:25580/health' -TimeoutSec 3).Content | ConvertFrom-Json } catch {}
if ($health2) { $health2 | ConvertTo-Json -Compress | Set-Content (Join-Path $OutDir 'health-after.json') -Encoding UTF8 }

$total = $base.Chunks.Count + $sound.Chunks.Count
$badFormat = @($base.Chunks + $sound.Chunks | Where-Object { $_.Samples -ne 441 -or $_.PayloadBytes -ne 1764 }).Count
$monotonic = $true
$prev = [long]::MinValue
foreach ($c in @($base.Chunks + $sound.Chunks)) { if ($c.Start -lt $prev) { $monotonic = $false; break }; $prev = $c.Start }
Log "total chunks=$total badFormat=$badFormat monotonic=$monotonic"
$summary | Set-Content (Join-Path $OutDir 'summary.txt') -Encoding UTF8
if ($total -gt 0 -and $badFormat -eq 0 -and $monotonic) { exit 0 } else { exit 1 }
