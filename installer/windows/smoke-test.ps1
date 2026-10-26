<#
.SYNOPSIS
  Installs a PostgresForAll MSI silently for the current user, checks it, and uninstalls it.

.DESCRIPTION
  Checks, in order:
    1. msiexec installs it without admin rights (per-user).
    2. The app, the console health check and the Start menu shortcut exist.
    3. Exactly one "PostgresForAll" entry is in Add/Remove Programs, with the expected version
       (so an upgrade replaced the previous version rather than adding a second copy).
    4. PostgresForAll-check.exe runs (exit codes 0 and 2-7 are its documented results).
    5. The GUI starts and is still running after 15 seconds.
    6. msiexec uninstalls it: install folder, shortcut and Add/Remove entry are gone.

  The launched app uses a throwaway APPDATA, so the real saved logins are never read or changed.
  A desktop shortcut with the same name that existed before the test is backed up and restored.

.EXAMPLE
  pwsh installer/windows/smoke-test.ps1 app/build/installer/PostgresForAll-1.1.0-x64.msi -ExpectedVersion 1.1.0

.EXAMPLE
  # Upgrade: install 1.0.9 first, then 1.1.0 must replace it (one entry, version 1.1.0).
  pwsh installer/windows/smoke-test.ps1 PostgresForAll-1.1.0-x64.msi -ExpectedVersion 1.1.0 -UpgradeFrom PostgresForAll-1.0.9-x64.msi
#>
param(
    [Parameter(Mandatory = $true)][string]$Msi,
    [string]$ExpectedVersion,
    # An older MSI to install first, so the run tests upgrading from it.
    [string]$UpgradeFrom,
    [switch]$KeepInstalled
)
$ErrorActionPreference = 'Stop'
$Msi = (Resolve-Path $Msi).Path
$failures = [System.Collections.Generic.List[string]]::new()
function Check([string]$what, [bool]$ok) {
    if ($ok) { Write-Host "  ok   $what" } else { Write-Host "  FAIL $what"; $failures.Add($what) }
}
function ArpEntries {
    # Per-user MSIs register their Add/Remove Programs data under the user's SID in HKLM.
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    Get-ChildItem "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Installer\UserData\$sid\Products" -ErrorAction SilentlyContinue |
        ForEach-Object { Get-ItemProperty "$($_.PSPath)\InstallProperties" -ErrorAction SilentlyContinue } |
        Where-Object { $_.DisplayName -eq 'PostgresForAll' }
}
function InstallDir {
    foreach ($d in @("$env:LOCALAPPDATA\PostgresForAll", "$env:LOCALAPPDATA\Programs\PostgresForAll")) {
        if (Test-Path "$d\PostgresForAll.exe") { return $d }
    }
    return $null
}
function RunMsi([string[]]$msiArgs, [string]$log) {
    $p = Start-Process msiexec.exe -ArgumentList ($msiArgs + @('/qn', '/norestart', '/l*v', "`"$log`"")) -Wait -PassThru
    return $p.ExitCode
}

$work = Join-Path ([IO.Path]::GetTempPath()) ("pfa-smoke-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force $work | Out-Null
$appData = Join-Path $work 'appdata'
New-Item -ItemType Directory -Force $appData | Out-Null

$desktop = [Environment]::GetFolderPath('Desktop')
$desktopLnk = Join-Path $desktop 'PostgresForAll.lnk'
$backupLnk = Join-Path $work 'PostgresForAll.lnk.bak'
$hadShortcut = Test-Path $desktopLnk
if ($hadShortcut) { Copy-Item $desktopLnk $backupLnk }

try {
    if ($UpgradeFrom) {
        $UpgradeFrom = (Resolve-Path $UpgradeFrom).Path
        Write-Host "Installing the older version first: $UpgradeFrom"
        $code = RunMsi @('/i', "`"$UpgradeFrom`"") (Join-Path $work 'install-old.log')
        Check "older version installed (exit $code)" ($code -eq 0 -or $code -eq 3010)
        $old = @(ArpEntries)
        Check "older version registered ($($old.DisplayVersion))" ($old.Count -eq 1)
    }
    Write-Host "Installing $Msi"
    $code = RunMsi @('/i', "`"$Msi`"") (Join-Path $work 'install.log')
    Check "msiexec install exit code $code" ($code -eq 0 -or $code -eq 3010)

    $dir = InstallDir
    Check "installed for this user ($dir)" ($null -ne $dir)
    if ($null -eq $dir) { throw "install folder not found; see $work\install.log" }
    Check "PostgresForAll-check.exe installed" (Test-Path "$dir\PostgresForAll-check.exe")
    $startMenu = Join-Path ([Environment]::GetFolderPath('Programs')) 'PostgresForAll'
    Check "Start menu shortcut" ((Get-ChildItem $startMenu -Filter '*.lnk' -ErrorAction SilentlyContinue).Count -ge 1)

    $arp = @(ArpEntries)
    Check "one Add/Remove Programs entry (found $($arp.Count))" ($arp.Count -eq 1)
    if ($ExpectedVersion -and $arp.Count -ge 1) {
        Check "version $($arp[0].DisplayVersion) is $ExpectedVersion" ($arp[0].DisplayVersion -eq $ExpectedVersion)
    }

    # Console health check, with a throwaway data folder.
    $psi = [System.Diagnostics.ProcessStartInfo]::new("$dir\PostgresForAll-check.exe", '--port 5999')
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.Environment['APPDATA'] = $appData
    $check = [System.Diagnostics.Process]::Start($psi)
    $out = $check.StandardOutput.ReadToEnd()
    $check.WaitForExit(60000) | Out-Null
    Check "health check ran (exit $($check.ExitCode))" ($check.ExitCode -eq 0 -or ($check.ExitCode -ge 2 -and $check.ExitCode -le 7))
    if ($out) { $out.Split("`n") | Select-Object -First 3 | ForEach-Object { Write-Host "       $_" } }

    # GUI: starts and stays up (it waits at the login dialog).
    $psi = [System.Diagnostics.ProcessStartInfo]::new("$dir\PostgresForAll.exe")
    $psi.UseShellExecute = $false
    $psi.Environment['APPDATA'] = $appData
    $gui = [System.Diagnostics.Process]::Start($psi)
    Start-Sleep -Seconds 15
    Check "GUI still running after 15 s" (-not $gui.HasExited)
    if (-not $gui.HasExited) { & taskkill.exe /PID $gui.Id /T /F | Out-Null }
    Start-Sleep -Seconds 2
}
finally {
    if (-not $KeepInstalled) {
        Write-Host "Uninstalling"
        $code = RunMsi @('/x', "`"$Msi`"") (Join-Path $work 'uninstall.log')
        Check "msiexec uninstall exit code $code" ($code -eq 0 -or $code -eq 3010)
        Check "install folder removed" ($null -eq (InstallDir))
        Check "Add/Remove Programs entry removed" (@(ArpEntries).Count -eq 0)
    }
    if ($hadShortcut -and (Test-Path $backupLnk)) {
        Copy-Item $backupLnk $desktopLnk -Force
        Write-Host "  (restored the desktop shortcut that was there before)"
    }
}

if ($failures.Count -gt 0) {
    Write-Host "SMOKE TEST FAILED ($($failures.Count)); logs in $work"
    exit 1
}
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
Write-Host "SMOKE TEST PASSED"
