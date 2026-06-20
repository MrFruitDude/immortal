#!/usr/bin/env bash
#
# fleet-restore.sh — restore a Portal after a clean reinstall, from a
# fleet-backup.sh snapshot.
#
# Prereqs: the Portal has been reinstalled with a fleet-capable Immortal build
# and re-registered (./provision.sh --fleet over USB), so fleet/<serial>.json
# holds the CURRENT token and the /screensaver & /calendar endpoints exist.
#
# Modes (combine freely; default = --apps --prefs):
#   --apps     reinstall the third-party apps that were installed (from apps.json)
#   --prefs    push the saved shared_prefs back verbatim → FULL fidelity, restores
#              every credential (Immich/SMB/WebDAV, multiroom, etc.). fleet_agent.xml
#              is skipped so the freshly-provisioned token is kept. A reboot follows
#              so the app reloads prefs from disk.
#   --config   instead of raw prefs, re-apply only the endpoint-covered screensaver
#              + calendar subset (no stored credentials). Use when you don't want to
#              overwrite the fresh install's prefs wholesale.
#   --dry-run  print what would happen, change nothing.
#
# Usage:
#   ./fleet-restore.sh 819LCM01Z09E4D12                       # apps + prefs + reboot
#   ./fleet-restore.sh "Portal Mini" --config --dry-run
#   ./fleet-restore.sh 819LCM01Z09E4D12 --backup backups/819LCM01Z09E4D12/20260620-200000
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$here"
FLEETCTL="${FLEETCTL:-./fleetctl}"
FLEET_DIR="${IMMORTAL_FLEET_DIR:-./fleet}"
PREFS_DIR="/data/data/com.immortal.launcher/shared_prefs"

usage() { sed -n '2,30p' "$0"; exit 2; }

target="${1:-}"; [ -n "$target" ] || usage; shift || true

# Map a friendly name to its serial (backups are keyed by serial).
serial="$target"
if [ ! -f "$FLEET_DIR/$serial.json" ]; then
  for f in "$FLEET_DIR"/*.json; do
    [ -e "$f" ] || continue
    nm="$(sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$f" | head -1)"
    [ "$nm" = "$target" ] && serial="$(basename "$f" .json)"
  done
fi

backup="backups/$serial/latest"
do_apps=0; do_prefs=0; do_config=0; dry=0
while [ $# -gt 0 ]; do
  case "$1" in
    --backup) backup="$2"; shift 2;;
    --apps)   do_apps=1; shift;;
    --prefs)  do_prefs=1; shift;;
    --config) do_config=1; shift;;
    --dry-run) dry=1; shift;;
    *) echo "unknown option: $1" >&2; usage;;
  esac
done
# Default: reinstall apps and restore prefs (full-fidelity, incl. credentials).
[ $do_apps -eq 0 ] && [ $do_prefs -eq 0 ] && [ $do_config -eq 0 ] && { do_apps=1; do_prefs=1; }

[ -d "$backup" ] || { echo "error: backup not found: $backup" >&2; exit 1; }
[ -x "$FLEETCTL" ] || { echo "error: $FLEETCTL not built (rustc -O fleet.rs -o fleetctl)" >&2; exit 1; }

echo "restoring '$serial' from $backup  (apps=$do_apps prefs=$do_prefs config=$do_config dry-run=$dry)"
run() { if [ $dry -eq 1 ]; then echo "  + $*"; else "$@"; fi; }

# 1) Reinstall third-party apps (never Immortal itself). apps.json lists the whole
#    catalog with an "installed" marker (false = not installed, else a versionCode),
#    so we keep only the ones that were actually installed.
if [ $do_apps -eq 1 ] && [ -f "$backup/apps.json" ]; then
  echo "-- apps --"
  pkgs="$(awk '
    /"packageName"/ { line=$0; sub(/.*"packageName"[ \t]*:[ \t]*"/,"",line); sub(/".*/,"",line); pkg=line }
    /"installed"/   { if ($0 !~ /false/ && pkg != "") print pkg }
  ' "$backup/apps.json" | grep -vx 'com.immortal.launcher' | sort -u)"
  if [ -z "$pkgs" ]; then echo "  (no third-party apps were installed)"; fi
  while IFS= read -r p; do
    [ -n "$p" ] || continue
    echo "  install $p"
    run "$FLEETCTL" install "$p" --device "$serial"
  done <<< "$pkgs"
fi

# 2) Push raw shared_prefs back = full-fidelity restore of config + credentials.
if [ $do_prefs -eq 1 ]; then
  echo "-- prefs (full restore) --"
  pushed=0
  for x in "$backup"/shared_prefs/*.xml; do
    [ -e "$x" ] || continue
    n="$(basename "$x")"
    if [ "$n" = "fleet_agent.xml" ]; then
      echo "  skip $n (keep freshly-provisioned token)"
      continue
    fi
    echo "  push $n"
    run "$FLEETCTL" push "$x" "$PREFS_DIR/$n" --device "$serial"
    pushed=$((pushed + 1))
  done
  if [ $pushed -gt 0 ]; then
    echo "  rebooting so prefs reload from disk"
    run "$FLEETCTL" action reboot --device "$serial"
  fi
fi

# 3) Alternative soft restore: re-apply the endpoint-covered screensaver/calendar
#    subset from the saved prefs (no stored credentials, no reboot).
if [ $do_config -eq 1 ] && [ -f "$backup/shared_prefs/immortal_screensaver.xml" ]; then
  echo "-- config (screensaver + calendar via endpoints) --"
  ss="$backup/shared_prefs/immortal_screensaver.xml"
  sval() { sed -n "s/.*<string name=\"$1\"[^>]*>\([^<]*\)<.*/\1/p" "$ss" | head -1; }
  bval() { sed -n "s/.*<boolean name=\"$1\"[^>]*value=\"\([^\"]*\)\".*/\1/p" "$ss" | head -1; }
  ival() { sed -n "s/.*<int name=\"$1\"[^>]*value=\"\([^\"]*\)\".*/\1/p" "$ss" | head -1; }

  source_v="$(sval source)"; folder_v="$(sval folder_path)"; album_v="$(sval album_url)"
  fit_v="$(sval fit)"; interval_v="$(ival interval_sec)"; shuffle_v="$(bval shuffle)"
  video_v="$(bval include_video)"; cal_url="$(sval calendar_url)"; cal_range="$(sval calendar_range)"

  args=()
  case "$source_v" in default) args+=(--source default);; esac
  [ -n "$folder_v" ]   && args+=(--folder "$folder_v")
  [ -n "$album_v" ]    && args+=(--album-url "$album_v")
  [ -n "$fit_v" ]      && args+=(--fit "$fit_v")
  [ -n "$interval_v" ] && args+=(--interval "$interval_v")
  [ -n "$shuffle_v" ]  && args+=(--shuffle "$shuffle_v")
  [ -n "$video_v" ]    && args+=(--videos "$video_v")
  if [ ${#args[@]} -gt 0 ]; then
    echo "  screensaver ${args[*]}"
    run "$FLEETCTL" screensaver set "${args[@]}" --device "$serial"
  fi
  if [ -n "$cal_url" ]; then
    cargs=(--url "$cal_url"); [ -n "$cal_range" ] && cargs+=(--range "$cal_range")
    echo "  calendar ${cargs[*]}"
    run "$FLEETCTL" calendar set "${cargs[@]}" --device "$serial"
  fi
  echo "  note: --config does NOT restore Immich/SMB/WebDAV/multiroom credentials;"
  echo "        use --prefs for those."
fi

echo "done."
