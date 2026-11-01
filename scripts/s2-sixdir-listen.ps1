#Requires -Version 5.1
<#
S2.4 user listening helper:
- unpause, create camera at player eye yaw=0,pitch=0
- play six 3D bell sounds in fixed order: front, back, left, right, up, down
- print the order (user writes perceived direction sequence)
- delete camera at end
User should run this while the game is in a world and listen through the game's audio output (relay).
#>
param(
    [string]$WsUrl = 'ws://127.0.0.1:25581/',
    [double]$Volume = 4.0,
    [double]$GapSeconds = 2.5
)
$ErrorActionPreference = 'Continue'
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
Write-Host '=== S2.4/S2.1 listening test ==='
Write-Host '1) 进入世界，戴耳机，确认音量正常。'
Write-Host '2) 脚本会依次播放六声：front/back/left/right/up/down。'
Write-Host '3) 请记录你实际听到的方向序列；也可反馈 S2.1 是否有静音/断续/异常回声。'
Read-Host '按 Enter 开始'
Write-Host ('unpause: ' + (WsCommand '{"id":"u","action":"client.unpause","params":{}}'))
Start-Sleep -Milliseconds 500
$pose = (WsCommand '{"id":"p","action":"client.playerPose","params":{}}' | ConvertFrom-Json).result
if (-not $pose) { Write-Host '未进入世界/无法获取玩家位姿'; exit 2 }
$st = WsCommand '{"id":"st","action":"camera.status","params":{}}' | ConvertFrom-Json
if ($st.ok -and $st.result.active -and [int]$st.result.active -gt 0) {
    WsCommand (@{ id = 'd'; action = 'camera.delete'; params = @{ id = [int]$st.result.active } } | ConvertTo-Json -Compress -Depth 4) | Out-Null
}
$create = WsCommand (@{ id = 'c'; action = 'camera.create'; params = @{ x = $pose.x; y = $pose.eyeY; z = $pose.z; yaw = 0.0; pitch = 0.0; fov = 70.0 } } | ConvertTo-Json -Compress -Depth 4)
$cam = $create | ConvertFrom-Json
if (-not $cam.ok) { Write-Host "相机创建失败: $create"; exit 3 }
$camId = [int]$cam.result.id
Start-Sleep -Seconds 1
$dirs = @(
    @{ Name = 'front'; Dx = 0.0;  Dy = 0.0;  Dz = 8.0 },
    @{ Name = 'back';  Dx = 0.0;  Dy = 0.0;  Dz = -8.0 },
    @{ Name = 'left';  Dx = 8.0;  Dy = 0.0;  Dz = 0.0 },
    @{ Name = 'right'; Dx = -8.0; Dy = 0.0;  Dz = 0.0 },
    @{ Name = 'up';    Dx = 0.0;  Dy = 8.0;  Dz = 0.0 },
    @{ Name = 'down';  Dx = 0.0;  Dy = -8.0; Dz = 0.0 }
)
$order = @()
foreach ($d in $dirs) {
    Write-Host ("播放: {0} (相机 yaw=0 期望: {1})" -f $d.Name, $d.Name)
    $order += $d.Name
    $cmd = @{ id = 'snd'; action = 'client.playPosSound'; params = @{ dx = $d.Dx; dy = $d.Dy; dz = $d.Dz; volume = $Volume } } | ConvertTo-Json -Compress -Depth 4
    $resp = WsCommand $cmd
    Write-Host "  $resp"
    Start-Sleep -Seconds $GapSeconds
}
$del = WsCommand (@{ id = 'd'; action = 'camera.delete'; params = @{ id = $camId } } | ConvertTo-Json -Compress -Depth 4)
Write-Host "sequence: $($order -join ' -> ')"
Write-Host '请把实际听到的方向序列（以及 S2.1 听感）回复给开发端；感谢。'
Write-Host "camera delete: $del"
