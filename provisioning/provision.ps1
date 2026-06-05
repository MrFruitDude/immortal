<#
  Copyright (c) Meta Platforms, Inc. and affiliates.
  Licensed under the MIT license found in the LICENSE file in the repo root.

  Windows Portal provisioner. Finds (or downloads) ADB, waits for a connected
  Portal, then installs the client app, pushes photos, grants permissions,
  disables Meta's verifier, and sets the custom launcher + screensaver.

  Usage:
    powershell -ExecutionPolicy Bypass -File provision.ps1            # provision
    powershell -ExecutionPolicy Bypass -File provision.ps1 -Restore   # undo
    powershell -ExecutionPolicy Bypass -File provision.ps1 -Status    # show state
#>
param([switch]$Restore, [switch]$Status)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ScriptDir

function Step($m){ Write-Host "==> $m" -ForegroundColor Cyan }
function Ok($m){ Write-Host "  [ok] $m" -ForegroundColor Green }
function Warn($m){ Write-Host "  [!] $m" -ForegroundColor Yellow }
function Die($m){ Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }

# ----- load config.env -------------------------------------------------------
if (-not (Test-Path config.env)) { Die "config.env not found next to this script." }
$cfg = @{}
Get-Content config.env | ForEach-Object {
  $line = $_.Trim()
  if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
    $i = $line.IndexOf("="); $val = $line.Substring($i+1).Trim().Trim('"').Trim("'")
    $cfg[$line.Substring(0,$i).Trim()] = $val
  }
}

# ----- resolve adb -----------------------------------------------------------
function Resolve-Adb {
  $bundled = Join-Path $ScriptDir "platform-tools\adb.exe"
  if (Test-Path $bundled) { return $bundled }
  $onPath = (Get-Command adb -ErrorAction SilentlyContinue)
  if ($onPath) { return $onPath.Source }
  Step "Android platform-tools (adb) not found — downloading the official package from Google"
  $url = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
  $zip = Join-Path $ScriptDir "platform-tools.zip"
  Invoke-WebRequest -Uri $url -OutFile $zip
  Expand-Archive -Path $zip -DestinationPath $ScriptDir -Force
  Remove-Item $zip
  if (-not (Test-Path $bundled)) { Die "adb missing after download." }
  Ok "platform-tools installed locally"
  return $bundled
}
$ADB = Resolve-Adb
function A { & $ADB @args }

# ----- wait for an authorized device -----------------------------------------
function Wait-Device {
  Step "Looking for your Portal"
  A start-server | Out-Null
  $plug=$false; $auth=$false
  while ($true) {
    $line = (A devices | Select-Object -Skip 1 | Where-Object { $_.Trim() } | Select-Object -First 1)
    $state = if ($line) { ($line -split "\s+")[1] } else { "" }
    switch ($state) {
      "device" {
        $model = (A shell getprop ro.product.model).Trim()
        Ok "Connected: $model"
        if ($model -and $model -notlike "*Portal*") { Warn "This doesn't look like a Portal (model: $model). Continuing." }
        return
      }
      "unauthorized" { if (-not $auth) { Warn "On the Portal screen, tap Allow (check 'Always allow from this computer')."; $auth=$true } }
      "" { if (-not $plug) { Warn "Plug the Portal into this PC via USB-C. On the Portal: Settings > Debug > ADB Enabled."; $plug=$true } }
      default { Warn "Device state: $state - waiting..." }
    }
    Start-Sleep -Seconds 2
  }
}

# ----- actions ---------------------------------------------------------------
function Install-Client {
  $apk = Get-ChildItem -Path $cfg["APK_GLOB"] -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $apk -and $cfg["RELEASE_APK_URL"]) {
    Step "No local APK - downloading the latest Immortal release"
    $dir = Split-Path -Parent $cfg["APK_GLOB"]
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $dest = Join-Path $dir "immortal.apk"
    Invoke-WebRequest -Uri $cfg["RELEASE_APK_URL"] -OutFile $dest
    $apk = Get-Item $dest
    Ok "Downloaded $($apk.Name)"
  }
  if (-not $apk) { Die "No client APK found matching '$($cfg["APK_GLOB"])'. Drop your signed APK in apks\." }
  Step "Installing client app ($($apk.Name))"
  A install -r $apk.FullName | Out-Null
  Ok "Installed $($cfg["PKG"])"
}
function Push-Assets {
  $dir = "/sdcard/Android/data/$($cfg["PKG"])/files"
  A shell mkdir -p $dir | Out-Null
  $imgs = Get-ChildItem -Path $cfg["ASSET_DIR"] -Include *.jpg,*.jpeg,*.png -File -ErrorAction SilentlyContinue
  if (-not $imgs) { Warn "No images in $($cfg["ASSET_DIR"])"; return }
  $first=$true; $n=0
  foreach ($img in $imgs) {
    if ($first) { A push $img.FullName "$dir/frame.jpg" | Out-Null; $first=$false } else { A push $img.FullName "$dir/" | Out-Null }
    $n++
  }
  Ok "Pushed $n image(s) to the photo frame"
}
function Grant-Perms {
  Step "Granting permissions"
  foreach ($p in ($cfg["PERMISSIONS"] -split "\s+")) { if ($p) { A shell pm grant $cfg["PKG"] $p | Out-Null } }
  # Self-healing: lets Immortal reaffirm its screensaver settings if reset.
  A shell pm grant $cfg["PKG"] android.permission.WRITE_SECURE_SETTINGS | Out-Null
  Ok "Permissions granted"
}
function Disable-Verifier {
  if ($cfg["DISABLE_VERIFIER"] -ne "true") { return }
  Step "Disabling Meta's install verifier (lets the client install other apps on-device)"
  A shell pm disable-user --user 0 $cfg["VERIFIER_PKG"] | Out-Null
  A shell settings put global package_verifier_enable 0 | Out-Null
  Ok "Verifier disabled"
}
function Disable-Ota {
  if ($cfg["DISABLE_OTA"] -ne "true") { return }
  Step "Disabling Meta OS updates (so a future OTA can't undo this setup)"
  foreach ($p in ($cfg["OTA_PACKAGES"] -split "\s+")) { A shell pm disable-user --user 0 $p | Out-Null }
  Ok "OS updates disabled"
}
function Set-Launcher {
  if ($cfg["SET_LAUNCHER"] -ne "true") { return }
  Step "Setting custom home launcher"
  A shell cmd package set-home-activity $cfg["HOME_ACTIVITY"] | Out-Null
  Ok "Home = $($cfg["HOME_ACTIVITY"])"
}
function Set-Screensaver {
  if ($cfg["SET_SCREENSAVER"] -ne "true") { return }
  Step "Setting custom screensaver (active + dock/idle default)"
  A shell settings put secure screensaver_components $cfg["DREAM_SERVICE"] | Out-Null
  A shell settings put secure screensaver_default_component $cfg["DREAM_SERVICE"] | Out-Null
  A shell settings put secure screensaver_enabled 1 | Out-Null
  A shell settings put secure screensaver_activate_on_dock 1 | Out-Null
  A shell settings put secure screensaver_activate_on_sleep 1 | Out-Null
  Ok "Screensaver = $($cfg["DREAM_SERVICE"])"
}

# ----- modes -----------------------------------------------------------------
if ($Status) {
  Wait-Device
  Step "Current state"
  $home = (A shell 'cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME') -match "packageName=" | Out-Null
  Write-Host "  screensaver: $((A shell settings get secure screensaver_components).Trim())"
  $disabled = (A shell pm list packages -d $cfg["VERIFIER_PKG"]).Trim()
  Write-Host "  verifier:    $(if ($disabled) {'disabled'} else {'enabled'})"
  $ota = (A shell pm list packages -d | Select-String "alohaotasetup")
  Write-Host "  OS updates:  $(if ($ota) {'disabled'} else {'enabled'})"
  $client = (A shell pm list packages $cfg["PKG"]).Trim()
  Write-Host "  client:      $(if ($client) {'installed'} else {'not installed'})"
  exit 0
}

if ($Restore) {
  Write-Host "Portal Restore`n"
  Wait-Device
  Step "Re-enabling Meta's install verifier"
  A shell pm enable $cfg["VERIFIER_PKG"] | Out-Null
  A shell settings put global package_verifier_enable 1 | Out-Null; Ok "Verifier restored"
  Step "Re-enabling Meta OS updates"
  foreach ($p in ($cfg["OTA_PACKAGES"] -split "\s+")) { A shell pm enable $p | Out-Null }
  Ok "OS updates restored"
  Step "Restoring stock launcher"
  A shell cmd package set-home-activity $cfg["STOCK_HOME"] | Out-Null; Ok "Home restored"
  Step "Restoring stock screensaver"
  A shell settings put secure screensaver_components $cfg["STOCK_DREAM"] | Out-Null
  A shell settings put secure screensaver_default_component $cfg["STOCK_DEFAULT_DREAM"] | Out-Null
  Ok "Screensaver restored"
  A shell input keyevent KEYCODE_HOME | Out-Null
  Write-Host "`n[ok] Stock Portal settings restored." -ForegroundColor Green
  exit 0
}

Write-Host "Portal Provisioner" -ForegroundColor White
Write-Host "This will modify your Portal: install an app, replace the home screen and screensaver," -ForegroundColor DarkGray
Write-Host "and disable Meta's app-install verifier. Run with -Restore to undo.`n" -ForegroundColor DarkGray
Wait-Device
Install-Client
Push-Assets
Grant-Perms
Disable-Verifier
Disable-Ota
Set-Launcher
Set-Screensaver
A shell input keyevent KEYCODE_HOME | Out-Null
Write-Host "`n[ok] Done. Your Portal is provisioned." -ForegroundColor Green
Write-Host "To undo: run provision.ps1 -Restore" -ForegroundColor DarkGray
