# Ejecutar desde PowerShell con Python, JDK17, Android SDK y .NET10 instalados.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root
python -m pytest tests -v
if ($LASTEXITCODE -ne 0) { throw 'Fallo pytest' }
& (Join-Path $root 'windows/ControlRemoto.UI/build_windows.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Fallo .NET' }
Set-Location (Join-Path $root 'android')
& .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
if ($LASTEXITCODE -ne 0) { throw 'Fallo Android' }
Write-Host 'Pruebas de compilacion completadas. Falta hardware ESP32 e integracion LAN.'
