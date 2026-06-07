#!/usr/bin/env bash
#
# Copyright (c) Meta Platforms, Inc. and affiliates.
# Licensed under the MIT license found in the LICENSE file in the repo root.
#
# One-shot Portal provisioner. Finds (or downloads) ADB, waits for a connected
# Portal, then: installs the client app, pushes photo assets, pre-grants
# permissions, disables Meta's package verifier, and sets the custom launcher
# and screensaver. Run with --restore to undo everything.
#
# Usage:
#   ./provision.sh            provision the connected Portal
#   ./provision.sh --restore  put the stock launcher/screensaver/verifier back
#   ./provision.sh --status   show what's currently set
#   ./provision.sh --installd restart only the silent-install daemon
#   ./provision.sh --overlay-fix  fix the Gen-1 white-on-white installer dialog
#   ./provision.sh --shizuku  start the Shizuku server (optional; for apps that
#                             use the Shizuku API, e.g. Aurora's Shizuku mode)

set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ----- pretty output ---------------------------------------------------------
if [ -t 1 ]; then B=$'\033[1m'; G=$'\033[32m'; Y=$'\033[33m'; R=$'\033[31m'; D=$'\033[2m'; N=$'\033[0m'; else B=; G=; Y=; R=; D=; N=; fi
step() { printf "%s==>%s %s\n" "$B" "$N" "$1"; }
ok()   { printf "  %s✓%s %s\n" "$G" "$N" "$1"; }
warn() { printf "  %s!%s %s\n" "$Y" "$N" "$1"; }
die()  { printf "%sERROR:%s %s\n" "$R" "$N" "$1" >&2; exit 1; }

# ----- load config -----------------------------------------------------------
[ -f config.env ] || die "config.env not found next to this script."
# shellcheck disable=SC1091
set -a; . ./config.env; set +a

# Per-device snapshot of the ORIGINAL stock launcher/screensaver, written on the
# device itself the first time we provision. Restore reads this so it works on
# any Portal model (Go/Mini/Plus/TV/Gen-1) and from any computer — the hardcoded
# STOCK_* in config.env are only fallbacks if this snapshot is missing.
STATE_FILE=/sdcard/immortal_restore.env

# ----- resolve adb (bundled -> PATH -> download) -----------------------------
resolve_adb() {
  if [ -x "$SCRIPT_DIR/platform-tools/adb" ]; then ADB="$SCRIPT_DIR/platform-tools/adb"; return; fi
  if command -v adb >/dev/null 2>&1; then ADB="$(command -v adb)"; return; fi
  step "Android platform-tools (adb) not found — downloading the official package from Google"
  local os zip url
  case "$(uname -s)" in
    Darwin) os=darwin ;;
    Linux)  os=linux ;;
    *) die "Unsupported OS for auto-download. Install Android platform-tools and re-run." ;;
  esac
  url="https://dl.google.com/android/repository/platform-tools-latest-${os}.zip"
  zip="$SCRIPT_DIR/platform-tools.zip"
  curl -fL "$url" -o "$zip" || die "Download failed. Check your internet connection."
  unzip -oq "$zip" -d "$SCRIPT_DIR" || die "Could not unzip platform-tools."
  rm -f "$zip"
  [ -x "$SCRIPT_DIR/platform-tools/adb" ] || die "adb missing after download."
  ADB="$SCRIPT_DIR/platform-tools/adb"
  ok "platform-tools installed locally"
}

a() { "$ADB" "$@"; }

# ----- wait for an authorized device -----------------------------------------
wait_for_device() {
  step "Looking for your Portal"
  a start-server >/dev/null 2>&1
  local printed_plug=0 printed_auth=0 printed_adb=0
  while true; do
    local line state devs n
    if [ -n "${ANDROID_SERIAL:-}" ]; then
      # Pinned (by the user, or by the multi-device prompt below): adb itself
      # honours ANDROID_SERIAL, so just probe that device's state.
      state="$(a get-state 2>/dev/null | tr -d '\r')"
      [ "$state" = "device" ] || state=""
    else
      devs="$(a devices | awk 'NR>1 && $2=="device"{print $1}')"
      n="$(printf "%s" "$devs" | grep -c . || true)"
      if [ "$n" -gt 1 ]; then
        # adb refuses every command when more than one device is attached —
        # without this the run dies later at the first install with a bare
        # "Install failed." Offer a numbered pick-list up front instead.
        [ -t 0 ] || die "Multiple devices are connected. Unplug the others, or re-run with ANDROID_SERIAL=<serial>."
        local dev_serials=() dev_labels=() dline dserial dmodel idx choice
        while IFS= read -r dline; do
          [ -n "$dline" ] || continue
          dserial="${dline%% *}"
          dmodel="$(printf "%s" "$dline" | sed -n 's/.*model:\([^ ]*\).*/\1/p')"
          dev_serials+=("$dserial")
          dev_labels+=("${dmodel:-unknown}  ($dserial)")
        done <<EOF
$(a devices -l | awk 'NR>1 && $2=="device"')
EOF
        warn "More than one device is connected — which one is this setup for?"
        idx=1
        for dline in "${dev_labels[@]}"; do
          printf "    %s%d)%s %s\n" "$B" "$idx" "$N" "$dline"
          idx=$((idx + 1))
        done
        while :; do
          printf "  Number: "
          read -r choice || die "No selection made."
          case "$choice" in
            *[!0-9]* | "") : ;;
            *) [ "$choice" -ge 1 ] && [ "$choice" -le "${#dev_serials[@]}" ] && break ;;
          esac
          warn "Enter a number between 1 and ${#dev_serials[@]}."
        done
        ANDROID_SERIAL="${dev_serials[$((choice - 1))]}"
        export ANDROID_SERIAL
        ok "Using ${dev_labels[$((choice - 1))]}"
        continue
      elif [ "$n" = 1 ]; then
        # Pin the one authorized device so a second one appearing mid-run
        # (or a lingering unauthorized entry) can't break later commands.
        ANDROID_SERIAL="$devs"
        export ANDROID_SERIAL
        state="device"
      else
        line="$(a devices | awk 'NR>1 && NF{print; exit}')"
        state="$(printf "%s" "$line" | awk '{print $2}')"
      fi
    fi
    case "$state" in
      device)
        local model; model="$(a shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
        ok "Connected: ${model:-device}"
        case "$model" in
          *Portal*) : ;;
          "" ) : ;;
          *) warn "This doesn't look like a Portal (model: $model). Continuing anyway." ;;
        esac
        return ;;
      unauthorized)
        if [ "$printed_auth" = 0 ]; then
          printf "  %sOn the Portal screen, tap %sAllow%s (check \"Always allow from this computer\").%s\n" "$Y" "$B" "$N$Y" "$N"
          printed_auth=1
        fi ;;
      "")
        if [ "$printed_plug" = 0 ]; then
          printf "  %sPlug the Portal into this computer with a USB-C cable.%s\n" "$Y" "$N"
          printf "  %sOn the Portal: Settings > Debug > ADB Enabled.%s\n" "$D" "$N"
          printed_plug=1
        fi ;;
      *)
        if [ "$printed_adb" = 0 ]; then warn "Device state: $state — waiting…"; printed_adb=1; fi ;;
    esac
    sleep 2
  done
}

# ----- individual actions ----------------------------------------------------
install_client() {
  local apk
  apk="$(ls $APK_GLOB 2>/dev/null | head -1)"
  if [ -z "$apk" ] && [ -n "${RELEASE_APK_URL:-}" ]; then
    step "No local APK — downloading the latest Immortal release"
    mkdir -p "$(dirname "$APK_GLOB")"
    apk="$(dirname "$APK_GLOB")/immortal.apk"
    curl -fL "$RELEASE_APK_URL" -o "$apk" || die "Could not download the release APK. Check your connection."
    ok "Downloaded $(basename "$apk")"
  fi
  [ -n "$apk" ] || die "No client APK found matching '$APK_GLOB'. Drop your signed APK in apks/."
  step "Installing client app ($(basename "$apk"))"
  a install -r "$apk" >/dev/null 2>&1 && ok "Installed $PKG" || die "Install failed."
}

start_installd() {
  # Start the shell-privileged install daemon so Immortal's on-device App Store
  # can install apps SILENTLY (the launcher drops APKs in a queue; this installs
  # them as the shell user). Essential on the Gen-1 Portal+, whose built-in
  # installer dialog is broken; a nice one-tap upgrade on every other model too.
  # Like all non-root helpers (Shizuku, etc.), it does NOT survive a reboot —
  # re-run `provision.sh --installd` (or the kit) to restart it.
  [ -f installd.sh ] || { warn "installd.sh missing — skipping silent-install daemon"; return; }
  step "Starting the silent-install daemon"
  a push installd.sh /data/local/tmp/installd.sh >/dev/null 2>&1
  a shell "chmod 755 /data/local/tmp/installd.sh" >/dev/null 2>&1
  # Kill any previous instance(s), then start detached. (toybox `ps` shows the
  # process as "sh", not the script path, so scan /proc/*/cmdline instead;
  # exclude our own shell so the scan doesn't kill itself.)
  a shell 'me=$$; for d in /proc/[0-9]*; do p=${d#/proc/}; [ "$p" = "$me" ] && continue; c=$(cat "$d/cmdline" 2>/dev/null | tr "\0" " "); case "$c" in *installd.sh*) kill "$p" 2>/dev/null;; esac; done' >/dev/null 2>&1
  # Remove any stale heartbeat so the check below can only pass on a FRESH one
  # from the daemon we're about to start.
  a shell "rm -f /sdcard/Android/data/$PKG/files/installq/.heartbeat" >/dev/null 2>&1
  a shell "setsid sh /data/local/tmp/installd.sh /sdcard/Android/data/$PKG/files/installq >/dev/null 2>&1 &" >/dev/null 2>&1
  # The first heartbeat can take a few seconds on a busy device (right after an
  # app install the FUSE/package services are still churning), so poll instead
  # of a single fixed-sleep check — a one-shot probe here used to false-fail.
  local hb_try=0
  while [ "$hb_try" -lt 15 ]; do
    if a shell "cat /sdcard/Android/data/$PKG/files/installq/.heartbeat 2>/dev/null" | grep -q '[0-9]'; then
      ok "Silent-install daemon running"
      return
    fi
    hb_try=$((hb_try + 1))
    sleep 1
  done
  warn "Daemon didn't report a heartbeat (the store will fall back to the system installer)"
}

start_shizuku() {
  # OPTIONAL power-user add-on. If Shizuku (moe.shizuku.privileged.api) is on the
  # device, start its server over ADB so third-party apps that speak the Shizuku
  # API — e.g. Aurora Store's "Shizuku" install mode — can install silently too.
  #
  # Most users do NOT need this: Immortal's own "Open with Immortal" handler
  # already gives Aurora and friends a silent path (point their installer at
  # "Session"/"Native" and the install intent lands on Immortal -> our daemon).
  # Shizuku is the belt-and-suspenders option for apps that ONLY speak its API.
  #
  # Like our own daemon — and by Shizuku's own design — the server does NOT
  # survive a reboot. Re-run `provision.sh --shizuku` to restart it.
  local SZ=moe.shizuku.privileged.api
  local installed sdk
  installed="$(a shell pm list packages "$SZ" 2>/dev/null | tr -d '\r' | grep -c "package:$SZ")"
  sdk="$(a shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
  if [ "${installed:-0}" = 0 ]; then
    # Auto-install Shizuku only where it's actually needed: the Gen-1 Portal+
    # (API < 29), whose stock installer is broken. Newer Portals install fine
    # without it, so don't clutter them. (If a user installs Shizuku themselves on
    # any model, we'll still start its server below.)
    if [ -n "${SHIZUKU_APK_URL:-}" ] && [ "${sdk:-99}" -lt 29 ] 2>/dev/null; then
      step "Installing Shizuku (enables Aurora Store etc. on this Gen-1 Portal)"
      local tmp="$(dirname "$APK_GLOB")/shizuku.apk"; mkdir -p "$(dirname "$tmp")"
      if curl -fsL "$SHIZUKU_APK_URL" -o "$tmp" 2>/dev/null && a install -r "$tmp" >/dev/null 2>&1; then
        ok "Shizuku installed"
      else
        warn "Shizuku install failed — skipping"; rm -f "$tmp"; return
      fi
      rm -f "$tmp"
    else
      return  # Not installed and not a Gen-1 (or no URL): nothing to do, silently.
    fi
  fi
  step "Starting Shizuku server (for third-party silent installs)"
  # Derive the version-specific starter path. Shizuku ships a tiny native binary
  # (libshizuku.so) under its install dir's lib/<abi>/ folder; running it as the
  # shell user starts the server. The install-dir hash and ABI vary per device,
  # so resolve both at runtime rather than hard-coding them.
  local apkpath apkdir starter
  apkpath="$(a shell pm path "$SZ" 2>/dev/null | tr -d '\r' | sed 's/^package://' | head -1)"
  apkdir="$(a shell "dirname '$apkpath'" 2>/dev/null | tr -d '\r')"
  starter="$(a shell "ls $apkdir/lib/*/libshizuku.so 2>/dev/null" | tr -d '\r' | head -1)"
  [ -n "$starter" ] || { warn "Couldn't find Shizuku's starter — open the Shizuku app and tap Start once"; return; }
  if a shell "$starter" 2>&1 | grep -q 'exit with 0'; then
    ok "Shizuku server running"
  else
    warn "Shizuku didn't confirm startup — open the Shizuku app to check its status"
  fi
}

install_apps() {
  # Silent adb-install of the configured apps. Works on every model — the only
  # reliable path on devices whose on-device installer dialog is broken.
  local tmp="$(dirname "$APK_GLOB")"; mkdir -p "$tmp"
  for spec in ${PREINSTALL_FDROID:-}; do
    local id="${spec%%:*}" vc=""
    case "$spec" in *:*) vc="${spec##*:}" ;; esac
    if [ -z "$vc" ]; then
      vc="$(curl -fsL "https://f-droid.org/api/v1/packages/$id" 2>/dev/null | grep -o '"suggestedVersionCode":[0-9]*' | grep -o '[0-9]*' | head -1)"
    fi
    [ -n "$vc" ] || { warn "Skipping $id (couldn't resolve a version)"; continue; }
    step "Installing $id"
    if curl -fsL "https://f-droid.org/repo/${id}_${vc}.apk" -o "$tmp/$id.apk" 2>/dev/null \
       && a install -r "$tmp/$id.apk" >/dev/null 2>&1; then ok "$id installed"; else warn "$id failed"; fi
    rm -f "$tmp/$id.apk"
  done
  for url in ${PREINSTALL_APKS:-}; do
    local f="$tmp/$(basename "$url")"
    step "Installing $(basename "$url")"
    if curl -fsL "$url" -o "$f" 2>/dev/null && a install -r "$f" >/dev/null 2>&1; then ok "installed"; else warn "failed"; fi
    rm -f "$f"
  done
}

push_assets() {
  [ -d "$ASSET_DIR" ] || { warn "No assets/ folder — skipping photos"; return; }
  local dir="/sdcard/Android/data/$PKG/files"
  a shell mkdir -p "$dir" >/dev/null 2>&1
  local first=1 n=0
  for img in "$ASSET_DIR"/*.jpg "$ASSET_DIR"/*.jpeg "$ASSET_DIR"/*.png; do
    [ -e "$img" ] || continue
    if [ "$first" = 1 ]; then a push "$img" "$dir/frame.jpg" >/dev/null 2>&1; first=0; else a push "$img" "$dir/" >/dev/null 2>&1; fi
    n=$((n+1))
  done
  [ "$n" -gt 0 ] && ok "Pushed $n image(s) to the photo frame" || warn "No images in assets/"
}

apply_system_tweaks() {
  step "Applying system display tweaks"
  # Hide the status bar across all apps by default. Swipe from the top still
  # reveals it transiently. Eliminates the white-on-white problem on
  # light-background apps (Aurora, Android Settings, etc.). Android 5.0+.
  a shell settings put global policy_control "immersive.status=*" >/dev/null 2>&1
  # Force system-wide dark mode. On Android 9 (Portal+) some apps respond; on
  # Android 10 (Portal Go) it's a first-class feature — far more apps follow it,
  # meaning the status bar (when transiently visible) will be dark-styled.
  a shell settings put secure ui_night_mode 2 >/dev/null 2>&1
  # Allow apps (including Immortal) to call internal Android APIs that would
  # otherwise be blocked by the hidden-API blacklist. 1 = warn-only (calls
  # succeed; logcat warning only). Covers both pre-P and P+ app targets.
  a shell settings put global hidden_api_policy_pre_p_apps 1 >/dev/null 2>&1
  a shell settings put global hidden_api_policy_p_apps 1 >/dev/null 2>&1
  a shell settings put global hidden_api_policy 1 >/dev/null 2>&1
  # Unlock developer options (needed for ADB tooling and the debug menu).
  a shell settings put global development_settings_enabled 1 >/dev/null 2>&1
  ok "System tweaks applied"
}

grant_perms() {
  step "Granting permissions"
  for p in $PERMISSIONS; do a shell pm grant "$PKG" "$p" >/dev/null 2>&1; done
  # Self-healing: lets Immortal reaffirm its screensaver settings if reset.
  a shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1
  # Lets the "Install an APK" browser see downloaded APKs without a prompt.
  a shell pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE >/dev/null 2>&1
  # Lets Immortal bring the photo frame back instantly when the system force-wakes
  # the screensaver (~2 min in, a quirk of Meta's power manager) even if another
  # app is in the foreground. SYSTEM_ALERT_WINDOW holders may start activities
  # from the background on Android 10.
  a shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
  ok "Permissions granted"
}

disable_verifier() {
  [ "${DISABLE_VERIFIER:-true}" = true ] || return
  step "Disabling Meta's install verifier (lets the client install other apps on-device)"
  a shell pm disable-user --user 0 "$VERIFIER_PKG" >/dev/null 2>&1
  a shell settings put global package_verifier_enable 0 >/dev/null 2>&1
  ok "Verifier disabled"
}

disable_installer_overlay() {
  # GEN-1 PORTAL+ ONLY (Android 9). Meta ships a Runtime Resource Overlay (RRO)
  # that re-themes framework resources so the *stock* package-installer dialog
  # renders white-on-white: the confirm screen comes up blank with an invisible
  # "Install" button in the bottom-right corner. Disabling the overlay restores
  # the normal styling, so the on-device installer dialog becomes usable again.
  #
  # Why we do this even though Immortal's silent daemon already bypasses the
  # dialog: the daemon (and Shizuku) DON'T survive a reboot, but overlay state is
  # persisted by the framework in /data/system/overlays.xml and DOES. So a
  # rebooted Gen-1 with the daemon down still has a working stock installer —
  # third-party stores (Aurora/F-Droid) and APK sideloads keep working until the
  # kit is re-run. `cmd overlay` takes effect immediately; no reboot required, so
  # it won't kill the daemon/Shizuku the way a night-mode reboot would.
  [ "${DISABLE_INSTALLER_OVERLAY:-true}" = true ] || return
  local sdk; sdk="$(a shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
  # Newer Portals (API >= 29) have a working installer dialog and don't ship this
  # overlay — skip them so we don't touch theming that isn't broken.
  [ "${sdk:-99}" -lt 29 ] 2>/dev/null || return
  step "Fixing the on-device installer dialog (disabling Meta's white-on-white overlay)"
  local did=0
  for ov in $INSTALLER_OVERLAY_PKGS; do
    # Only act on overlays actually present on this device.
    a shell "cmd overlay list 2>/dev/null" | tr -d '\r' | grep -q "$ov" || continue
    a shell "cmd overlay disable --user 0 $ov" >/dev/null 2>&1 && { ok "Disabled $ov"; did=1; }
  done
  if [ "$did" = 1 ]; then
    # Marker the app reads (no hidden IOverlayManager API needed) to know the
    # stock dialog is usable, so the store can fall back to it when the daemon is
    # down instead of telling the user to reconnect.
    a shell settings put global immortal_overlay_fix 1 >/dev/null 2>&1
    ok "Installer dialog fix applied"
  else
    warn "No installer overlay found to disable (already fixed, or not a Gen-1)"
  fi
}

disable_ota() {
  [ "${DISABLE_OTA:-false}" = true ] || return
  step "Disabling Meta OS updates (so a future OTA can't undo this setup)"
  for p in $OTA_PACKAGES; do a shell pm disable-user --user 0 "$p" >/dev/null 2>&1; done
  ok "OS updates disabled"
}

disable_presence() {
  # OFF BY DEFAULT. The system uses Meta's presence detector to choose ambient
  # vs sleep at the screen timeout (someone nearby → photos; empty room → real
  # sleep), and Immortal cooperates with that. Disable only if you want the
  # camera never used at all — the trade-off is the device can't tell an empty
  # room from an occupied one. Reversible — restore re-enables it.
  [ "${DISABLE_PRESENCE:-false}" = true ] || return
  step "Disabling Meta's presence detector (camera off; loses empty-room sleep smarts)"
  a shell pm disable-user --user 0 "$PRESENCE_PKG" >/dev/null 2>&1
  ok "Presence detector disabled"
}

snapshot_stock() {
  # Record this device's real stock components for an accurate restore. Done
  # once per device; a re-provision keeps the original values.
  if a shell "[ -f $STATE_FILE ]" >/dev/null 2>&1; then return; fi
  local home dream ddream
  # The stock home is whichever HOME activity isn't ours, the system resolver, or
  # Settings' fallback. query-activities lists them all as flattened components,
  # so this is reliable across Portal models even with our launcher installed.
  home="$(a shell 'cmd package query-activities --components -a android.intent.action.MAIN -c android.intent.category.HOME' 2>/dev/null \
            | tr -d '\r' \
            | grep -E '^[A-Za-z0-9_.]+/' \
            | grep -v "^$PKG/" \
            | grep -v '^android/' \
            | grep -v '^com.android.settings/' \
            | head -1)"
  # Screensaver: the live setting is the stock dream on a first provision (we
  # haven't overwritten it yet); guard against capturing ours on a re-run.
  dream="$(a shell settings get secure screensaver_components 2>/dev/null | tr -d '\r')"
  ddream="$(a shell settings get secure screensaver_default_component 2>/dev/null | tr -d '\r')"
  [ -n "$home" ] || home="$STOCK_HOME"
  case "$dream" in "$PKG"/*|""|null) dream="$STOCK_DREAM" ;; esac
  case "$ddream" in "$PKG"/*|""|null) ddream="$STOCK_DEFAULT_DREAM" ;; esac
  printf 'STOCK_HOME=%s\nSTOCK_DREAM=%s\nSTOCK_DEFAULT_DREAM=%s\n' "$home" "$dream" "$ddream" \
    | a shell "cat > $STATE_FILE" 2>/dev/null
  ok "Saved this device's stock launcher/screensaver for restore"
}

load_state() {
  # Pull the per-device snapshot (if any) over the config fallbacks.
  a shell "[ -f $STATE_FILE ]" >/dev/null 2>&1 || { warn "No saved snapshot on device — using config.env fallbacks for restore"; return; }
  local key val
  while IFS='=' read -r key val; do
    val="$(printf '%s' "$val" | tr -d '\r')"
    case "$key" in
      STOCK_HOME) [ -n "$val" ] && STOCK_HOME="$val" ;;
      STOCK_DREAM) [ -n "$val" ] && STOCK_DREAM="$val" ;;
      STOCK_DEFAULT_DREAM) [ -n "$val" ] && STOCK_DEFAULT_DREAM="$val" ;;
    esac
  done < <(a shell cat "$STATE_FILE" 2>/dev/null)
}

set_launcher() {
  [ "${SET_LAUNCHER:-true}" = true ] || return
  step "Setting custom home launcher"
  a shell cmd package set-home-activity "$HOME_ACTIVITY" >/dev/null 2>&1 && ok "Home = $HOME_ACTIVITY" || warn "Could not set launcher"
}

set_screensaver() {
  [ "${SET_SCREENSAVER:-true}" = true ] || return
  step "Setting custom screensaver (active + dock/idle default)"
  # Active component (what Settings shows) AND the dock/idle default — Portal
  # uses the latter for its docked photo-frame path, so set both.
  a shell settings put secure screensaver_components "$DREAM_SERVICE" >/dev/null 2>&1
  a shell settings put secure screensaver_default_component "$DREAM_SERVICE" >/dev/null 2>&1
  a shell settings put secure screensaver_enabled 1 >/dev/null 2>&1
  a shell settings put secure screensaver_activate_on_dock 1 >/dev/null 2>&1
  a shell settings put secure screensaver_activate_on_sleep 1 >/dev/null 2>&1
  ok "Screensaver = $DREAM_SERVICE"
}

# ----- modes -----------------------------------------------------------------
do_provision() {
  printf "%sPortal Provisioner%s\n" "$B" "$N"
  printf "%sThis will modify your Portal: install an app, replace the home screen and screensaver,\nand disable Meta's app-install verifier. Run with --restore to undo. %s\n\n" "$D" "$N"
  resolve_adb
  wait_for_device
  install_client
  start_installd
  start_shizuku
  install_apps
  push_assets
  grant_perms
  apply_system_tweaks
  disable_verifier
  disable_installer_overlay
  disable_ota
  disable_presence
  snapshot_stock
  set_launcher
  set_screensaver
  a shell input keyevent KEYCODE_HOME >/dev/null 2>&1
  printf "\n%s✓ Done. Your Portal is provisioned.%s\n" "$G$B" "$N"
  printf "%sTo undo everything: re-run and choose restore (./provision.sh --restore).%s\n" "$D" "$N"
}

do_restore() {
  printf "%sPortal Restore%s\n\n" "$B" "$N"
  resolve_adb
  wait_for_device
  load_state # pull this device's real stock components (falls back to config)
  step "Restoring system display settings"
  a shell settings delete global policy_control >/dev/null 2>&1
  a shell settings delete secure ui_night_mode >/dev/null 2>&1
  a shell settings delete global hidden_api_policy_pre_p_apps >/dev/null 2>&1
  a shell settings delete global hidden_api_policy_p_apps >/dev/null 2>&1
  a shell settings delete global hidden_api_policy >/dev/null 2>&1
  a shell settings put global development_settings_enabled 0 >/dev/null 2>&1
  ok "System settings restored"
  step "Re-enabling Meta's install verifier"
  a shell pm enable "$VERIFIER_PKG" >/dev/null 2>&1
  a shell settings put global package_verifier_enable 1 >/dev/null 2>&1; ok "Verifier restored"
  step "Re-enabling Meta's installer overlay"
  for ov in $INSTALLER_OVERLAY_PKGS; do
    a shell "cmd overlay list 2>/dev/null" | tr -d '\r' | grep -q "$ov" \
      && a shell "cmd overlay enable --user 0 $ov" >/dev/null 2>&1
  done
  a shell settings delete global immortal_overlay_fix >/dev/null 2>&1; ok "Installer overlay restored"
  step "Re-enabling Meta OS updates"
  for p in $OTA_PACKAGES; do a shell pm enable "$p" >/dev/null 2>&1; done; ok "OS updates restored"
  step "Re-enabling Meta's presence detector"
  a shell pm enable "$PRESENCE_PKG" >/dev/null 2>&1; ok "Presence detector restored"
  step "Restoring stock launcher"
  a shell cmd package set-home-activity "$STOCK_HOME" >/dev/null 2>&1; ok "Home restored ($STOCK_HOME)"
  step "Restoring stock screensaver"
  a shell settings put secure screensaver_components "$STOCK_DREAM" >/dev/null 2>&1
  a shell settings put secure screensaver_default_component "$STOCK_DEFAULT_DREAM" >/dev/null 2>&1
  ok "Screensaver restored"
  a shell input keyevent KEYCODE_HOME >/dev/null 2>&1
  printf "\n%s✓ Stock Portal settings restored.%s\n" "$G$B" "$N"
  printf "%sThe client app is left installed — uninstall with: adb uninstall %s%s\n" "$D" "$PKG" "$N"
}

do_status() {
  resolve_adb; wait_for_device
  step "Current state"
  local pc dm
  pc="$(a shell settings get global policy_control 2>/dev/null | tr -d '\r')"
  dm="$(a shell settings get secure ui_night_mode 2>/dev/null | tr -d '\r')"
  printf "  status bar: %s\n" "$(printf '%s' "$pc" | grep -q 'immersive' && echo 'hidden (immersive)' || echo 'stock')"
  printf "  dark mode:  %s\n" "$([ "$dm" = "2" ] && echo 'on' || echo 'off')"
  printf "  home:       %s\n" "$(a shell 'cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME' 2>/dev/null | awk -F= '/packageName/{print $2; exit}' | tr -d '\r')"
  printf "  screensaver:%s\n" " $(a shell settings get secure screensaver_components 2>/dev/null | tr -d '\r')"
  printf "  verifier:   %s\n" "$(a shell pm list packages -d "$VERIFIER_PKG" 2>/dev/null | tr -d '\r' | grep -q . && echo disabled || echo enabled)"
  printf "  installer dialog: %s\n" "$([ "$(a shell settings get global immortal_overlay_fix 2>/dev/null | tr -d '\r')" = "1" ] && echo 'fixed (overlay disabled)' || echo 'stock')"
  printf "  OS updates: %s\n" "$(a shell pm list packages -d 2>/dev/null | tr -d '\r' | grep -q 'alohaotasetup' && echo disabled || echo enabled)"
  printf "  client:     %s\n" "$(a shell pm list packages "$PKG" 2>/dev/null | tr -d '\r' | grep -q . && echo installed || echo 'not installed')"
}

case "${1:-}" in
  --restore|-r) do_restore ;;
  --status|-s)  do_status ;;
  --apps|-a)    resolve_adb; wait_for_device; install_apps ;;
  --installd|-d) resolve_adb; wait_for_device; start_installd ;;
  --overlay-fix) resolve_adb; wait_for_device; disable_installer_overlay ;;
  --shizuku|-z) resolve_adb; wait_for_device; start_shizuku ;;
  --help|-h)    sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//' ;;
  "")           do_provision ;;
  *)            die "Unknown option: $1 (use --restore, --status, or no argument)" ;;
esac
