#Requires -Version 5.1
<#
S5 long-run start helper: enter world, unpause, create a camera at player eye and leave it active.
Then run long-run-health-log.ps1 for >=4h and play normally.
#>
param(
    [string]$WsUrl = 'ws://127.0.0.1:25581/'
)
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
Write-Host ('unpause: ' + (WsCommand '{"id":"u","action":"client.unpause","params":{}}'))
Start-Sleep -Milliseconds 500
$pose = (WsCommand '{"id":"p","action":"client.playerPose","params":{}}' | ConvertFrom-Json).result
if (-not $pose) { Write-Host '未进入世界/无法获取玩家位姿'; exit 2 }
$st = WsCommand '{"id":"st","action":"camera.status","params":{}}' | ConvertFrom-Json
if ($st.ok -and $st.result.active -and [int]$st.result.active -gt 0) {
    WsCommand (@{ id = 'd'; action = 'camera.delete'; params = @{ id = [int]$st.result.active } } | ConvertTo-Json -Compress -Depth 4) | Out-Null
}
$resp = WsCommand (@{ id = 'c'; action = 'camera.create'; params = @{ x = $pose.x; y = $pose.eyeY; z = $pose.z; yaw = $pose.yaw; pitch = $pose.pitch; fov = 70.0 } } | ConvertTo-Json -Compress -Depth 4)
Write-Host "camera: $resp"
Write-Host '现在可以正常游玩；另开终端运行长跑采样：'
Write-Host '  powershell -ExecutionPolicy Bypass -File scripts\long-run-health-log.ps1 -DurationSeconds 14400'
Write-Host '结束（>=4h）后把 CSV 与体感/异常反馈给开发端。'
