# Ejecutar desde PowerShell con .NET 10 SDK instalado.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$target = Join-Path $repo 'dist\ControlRemoto-Windows'
$hostOut = Join-Path $target 'Host'
New-Item -ItemType Directory -Force -Path $target, $hostOut | Out-Null

dotnet publish (Join-Path $repo 'windows\RemoteHost\RemoteHost.csproj') -c Release -r win-x64 --self-contained true -o $hostOut
if ($LASTEXITCODE -ne 0) { throw 'Fallo la compilacion de RemoteHost' }

dotnet publish (Join-Path $PSScriptRoot 'ControlRemoto.UI.csproj') -c Release -r win-x64 --self-contained true -o $target
if ($LASTEXITCODE -ne 0) { throw 'Fallo la compilacion WPF' }
Write-Host "Aplicacion creada en $target\ControlRemoto.exe"
Write-Host 'Ejecutar desde una sesion interactiva iniciada de Windows.'
