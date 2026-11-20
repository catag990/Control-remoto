$ErrorActionPreference='Stop'
$exe=Get-Command ffmpeg.exe -ErrorAction SilentlyContinue
if (-not $exe) { Write-Host 'FFmpeg no disponible. JPEG reserva limitado a 30 FPS.'; exit 2 }
Write-Host 'FFmpeg:' $exe.Source
& $exe.Source -hide_banner -filters 2>&1 | Select-String 'ddagrab'
& $exe.Source -hide_banner -encoders 2>&1 | Select-String 'h264_nvenc|h264_amf'
Write-Host 'Ver un filtro no prueba compatibilidad de hardware. Verificar streaming real.'
