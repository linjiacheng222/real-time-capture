# M7 长跑健康采样脚本（T7.3）
# 运行示例（服务运行时）：
#   powershell -ExecutionPolicy Bypass -File scripts/long-run-health-log.ps1 -DurationSeconds 14400
param(
    [int]$DurationSeconds = 3600,
    [int]$IntervalSeconds = 5,
    [string]$Url = 'http://127.0.0.1:25580/health',
    [string]$Output = ''
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
if (-not $Output) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $dir = Join-Path $root 'docs\evidence\S5\long-run'
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $Output = Join-Path $dir "health-$stamp.csv"
}

$start = Get-Date
$deadline = $start.AddSeconds([Math]::Max(1, $DurationSeconds))
"time,http_ok,connections,http_streams,game_fps,offscreen_rendered,readback,encoded,published,video_frames,audio_written,audio_dropped,audio_silent,encode_dropped,pipeline_failed,sync_delta_ms_avg,sync_warnings,error" |
    Set-Content -Path $Output -Encoding UTF8

while ((Get-Date) -lt $deadline) {
    $time = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    try {
        $r = Invoke-RestMethod -Uri $Url -TimeoutSec 3
        $row = @(
            $time, 1, $r.connections, $r.http_streams, $r.game_fps,
            $r.offscreen_rendered_frames, $r.readback_frames, $r.encoded_frames,
            $r.video_frames_published, $r.video_frames_published, $r.audio_ring_written,
            $r.audio_ring_dropped, $r.audio_ring_silent, $r.encode_dropped_frames,
            $r.pipeline_failed_frames, $r.sync_delta_ms_avg, $r.sync_warnings, ''
        ) -join ','
        Add-Content -Path $Output -Value $row -Encoding UTF8
        Write-Host "[$time] ok conn=$($r.connections) fps=$($r.video_frames_published) audio=$($r.audio_ring_written)"
    } catch {
        $row = @($time, 0, '', '', '', '', '', '', '', '', '', '', '', '', '', '', '', $_.Exception.Message) -join ','
        Add-Content -Path $Output -Value $row -Encoding UTF8
        Write-Host "[$time] FAIL $($_.Exception.Message)"
    }
    Start-Sleep -Seconds ([Math]::Max(1, $IntervalSeconds))
}
Write-Host "采样完成: $Output"
