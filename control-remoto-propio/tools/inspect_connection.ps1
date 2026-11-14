# Read-only diagnostics: does NOT open ports or disclose router credentials.
Write-Output '--- LAN IPv4 ---'
Get-NetIPAddress -AddressFamily IPv4 | Where-Object {$_.IPAddress -notlike '169.254*'} |
    Select-Object InterfaceAlias, IPAddress, PrefixLength | Format-Table
Write-Output '--- Public IPv4 test (outgoing HTTP request) ---'
try { (Invoke-RestMethod -Uri 'https://api.ipify.org' -TimeoutSec 8) }
catch { Write-Output 'External IP check unavailable.' }
Write-Output '--- TCP 8443 listener ---'
Get-NetTCPConnection -State Listen -LocalPort 8443 -ErrorAction SilentlyContinue |
    Select-Object LocalAddress, LocalPort, OwningProcess | Format-Table
Write-Output 'Compare the public IP with WAN IPv4 displayed by your router.'
