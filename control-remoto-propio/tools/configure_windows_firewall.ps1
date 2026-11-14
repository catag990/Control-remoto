# Opt-in Windows Defender Firewall rule. DOES NOT modify the router.
# Default: LAN-only testing. Public exposure requires --Publish explicitly.
param([switch]$Publish, [switch]$Remove)
$name='RemoteHostOwn TCP 8443'
if ($Remove) {
  Remove-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue
  Write-Output 'Removed rule.'
  exit
}
if (Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue) {
  Remove-NetFirewallRule -DisplayName $name
}
$range=if($Publish){'Any'}else{'LocalSubnet'}
New-NetFirewallRule -DisplayName $name -Direction Inbound -Action Allow -Protocol TCP `
    -LocalPort 8443 -Profile Private -RemoteAddress $range
if ($Publish) { Write-Warning 'WAN ingress requested. Only continue AFTER completing local security tests.' }
else { Write-Output 'LAN-only rule. No public port was opened on the router.' }
