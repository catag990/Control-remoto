# Solo LAN privada. No abre ni configura el router, no habilita acceso WAN.
# Ejecutar PowerShell COMO ADMINISTRADOR después de pruebas locales básicas.
$ErrorActionPreference='Stop'
$name='ControlRemotoPropio-LAN-TCP8443'
$rule=Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue
if ($rule) { Write-Host 'Regla LAN ya existe:' $name; exit 0 }
New-NetFirewallRule -DisplayName $name -Direction Inbound -Action Allow `
    -Protocol TCP -LocalPort 8443 -Profile Private -RemoteAddress LocalSubnet | Out-Null
Write-Host 'Regla LAN creada (solo LocalSubnet + perfil Privado). NO se abririeron puertos WAN.'
