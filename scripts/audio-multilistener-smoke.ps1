# CaptureAPI 多 listener 后端 smoke（Phase 4d/5；不做用户听感验收）
# 前置：游戏已进入世界，JVM 带 -Dcaptureapi.debug.multilistener_feature=true，dev test gate 开启。
param(
    [string]$WsUrl = "ws://127.0.0.1:25581/",
    [string]$Out = "docs/evidence/S6/multilistener-phase4d.json"
)
$ErrorActionPreference = "Stop"

function WsCmd([string]$json, [int]$timeoutMs = 8000) {
    $ws = [System.Net.WebSockets.ClientWebSocket]::new()
    $ct = [Threading.CancellationToken]::None
    try {
        $ws.ConnectAsync([Uri]$WsUrl, $ct).Wait(5000) | Out-Null
        $bytes = [Text.Encoding]::UTF8.GetBytes($json)
        $seg = [ArraySegment[byte]]::new($bytes)
        $ws.SendAsync($seg, [Net.WebSockets.WebSocketMessageType]::Text, $true, $ct).Wait(5000) | Out-Null
        $deadline = [DateTime]::UtcNow.AddMilliseconds($timeoutMs)
        while ([DateTime]::UtcNow -lt $deadline) {
            $buf = New-Object byte[] 65536
            $seg = [ArraySegment[byte]]::new($buf)
            $task = $ws.ReceiveAsync($seg, $ct)
            if (-not $task.Wait(1000)) { continue }
            if ($task.Result.MessageType -eq [Net.WebSockets.WebSocketMessageType]::Text) {
                return [Text.Encoding]::UTF8.GetString($buf, 0, $task.Result.Count) | ConvertFrom-Json
            }
        }
        throw "WS timeout: $json"
    } finally {
        $ws.Dispose()
    }
}

$result = [ordered]@{}
$result.time = (Get-Date).ToString("o")
$result.channels_initial = WsCmd '{"id":"ch0","action":"audio.channels.list","params":{}}'
$result.unpause = WsCmd '{"id":"u","action":"client.unpause","params":{}}'
$pose = (WsCmd '{"id":"p","action":"client.playerPose","params":{}}').result
$cameraBody = @{
    x = $pose.x; y = $pose.eyeY; z = $pose.z
    yaw = $pose.yaw; pitch = $pose.pitch; fov = 70
} | ConvertTo-Json -Compress
$result.camera = WsCmd ('{"id":"c","action":"camera.create","params":' + $cameraBody + '}')
Start-Sleep -Milliseconds 1500
$result.channels_ready = WsCmd '{"id":"ch1","action":"audio.channels.list","params":{}}'
$result.play = WsCmd '{"id":"s","action":"client.playPosSound","params":{"dx":0,"dy":0,"dz":0,"volume":1.0}}'
Start-Sleep -Milliseconds 150
$cameraId = $result.camera.result.id
$result.camera_peek = WsCmd ('{"id":"pc","action":"audio.channels.peek","params":{"channel":' + $cameraId + '}}')
$result.player_before = WsCmd '{"id":"pb","action":"audio.channels.peek","params":{"channel":0}}'
$sectionBody = @{ x = $pose.x; y = $pose.eyeY; z = $pose.z; chunks = 5 } | ConvertTo-Json -Compress
$result.audio_section = WsCmd ('{"id":"sec","action":"audio.section","params":' + $sectionBody + '}')
Start-Sleep -Milliseconds 100
$result.player_after = WsCmd '{"id":"pa","action":"audio.channels.peek","params":{"channel":0}}'
$recBody = @{ cameraId = 0; fps = 30; includeAudio = $true; audioChannel = $cameraId } | ConvertTo-Json -Compress
$result.recording_start = WsCmd ('{"id":"rs","action":"recording.start","params":' + $recBody + '}')
Start-Sleep -Milliseconds 1200
$result.recording_stop = WsCmd '{"id":"rt","action":"recording.stop"}'
$result.delete = WsCmd ('{"id":"d","action":"camera.delete","params":{"id":' + $cameraId + '}}')
Start-Sleep -Milliseconds 500
$result.channels_after_delete = WsCmd '{"id":"ch2","action":"audio.channels.list","params":{}}'

$dir = Split-Path -Parent $Out
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
$result | ConvertTo-Json -Depth 12 | Set-Content -Path $Out -Encoding UTF8
$result | ConvertTo-Json -Depth 12
