param([string]$Address = '192.168.1.7')
$ErrorActionPreference = 'Stop'
$user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew
$backupAction = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ('-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + (Join-Path $PSScriptRoot 'backup-maintenance.ps1') + '"')
$daily = New-ScheduledTaskTrigger -Daily -At '03:00'
Register-ScheduledTask -TaskName 'Classroom-DailyBackup' -Action $backupAction -Trigger $daily -Principal $principal -Settings $settings -Force | Out-Null
$healthAction = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ('-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + (Join-Path $PSScriptRoot 'server-health.ps1') + '" -Address ' + $Address)
$repeat = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 5)
Register-ScheduledTask -TaskName 'Classroom-HealthCheck' -Action $healthAction -Trigger $repeat -Principal $principal -Settings $settings -Force | Out-Null
$resumeAction = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ('-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + (Join-Path $PSScriptRoot 'resume-server.ps1') + '" -Address ' + $Address)
$logon = New-ScheduledTaskTrigger -AtLogOn -User $user
Register-ScheduledTask -TaskName 'Classroom-ResumeServer' -Action $resumeAction -Trigger $logon -Principal $principal -Settings $settings -Force | Out-Null
Write-Host 'Registered daily backup at 03:00 and HTTPS readiness every 5 minutes for the current Windows user.'
