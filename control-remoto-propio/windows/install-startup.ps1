# Starts the interactive desktop host at user sign-in, NOT as a Session 0 service.
# Execute in a normal PowerShell window, after building the executable.
param([Parameter(Mandatory=$true)][string]$ExePath)
if (-not (Test-Path $ExePath)) { throw "Executable not found: $ExePath" }
$resolved = (Resolve-Path $ExePath).Path
$action = New-ScheduledTaskAction -Execute $resolved
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName 'RemoteHostOwn' -Action $action -Trigger $trigger -Principal $principal -Description 'Visible and authorized remote desktop host after sign-in' -Force
Write-Output 'Scheduled interactive host at logon. Use Unregister-ScheduledTask to remove.'
