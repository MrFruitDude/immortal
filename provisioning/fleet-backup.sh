#!/usr/bin/env bash
#
# fleet-backup.sh — snapshot a Portal's Immortal state before a risky operation
# (e.g. a debug-key reinstall, which wipes app data). Pairs with fleet-restore.sh.
#
# For each target it saves, under backups/<serial>/<timestamp>/:
#   info.json       — /info (identity, version, install state, installed apps)
#   apps.json       — /apps (catalog + what's installed → the reinstall list)
#   registry.json   — the local fleet/<serial>.json (host/port/token)
#   shared_prefs/*.xml — every readable Immortal pref file. THIS is the config +
#                        credential store (fleet token, screensaver sources incl.
#                        Immich/SMB/WebDAV creds, multiroom user/pass, weather, …).
#
# The agent runs as com.immortal.launcher, so it can read its own /data/data
# shared_prefs over /fs/read — no root or adb needed.
#
# Usage:
#   ./fleet-backup.sh                 # back up every registered device
#   ./fleet-backup.sh all             # same
#   ./fleet-backup.sh "Portal Mini"   # one device by name
#   ./fleet-backup.sh 819LCM01Z09E4D12  # …or by serial
#
# The backups/ tree holds tokens + passwords in cleartext — it is git-ignored;
# keep it private and treat it like any other secret store.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$here"
FLEETCTL="${FLEETCTL:-./fleetctl}"
FLEET_DIR="${IMMORTAL_FLEET_DIR:-./fleet}"
PREFS_DIR="/data/data/com.immortal.launcher/shared_prefs"
STAMP="$(date +%Y%m%d-%H%M%S)"

[ -x "$FLEETCTL" ] || {
  echo "error: $FLEETCTL not found/executable — build it first:" >&2
  echo "       rustc -O fleet.rs -o fleetctl   (or: cargo build --release)" >&2
  exit 1
}

target="${1:-all}"

# Resolve the set of serials to back up from the registry filenames.
serials=()
if [ "$target" = "all" ]; then
  for f in "$FLEET_DIR"/*.json; do
    [ -e "$f" ] || continue
    serials+=("$(basename "$f" .json)")
  done
elif [ -f "$FLEET_DIR/$target.json" ]; then
  serials+=("$target")                       # matched a serial directly
else
  for f in "$FLEET_DIR"/*.json; do            # try to match a friendly name
    [ -e "$f" ] || continue
    nm="$(sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$f" | head -1)"
    [ "$nm" = "$target" ] && serials+=("$(basename "$f" .json)")
  done
fi
[ ${#serials[@]} -gt 0 ] || { echo "error: no device matching '$target' in $FLEET_DIR" >&2; exit 1; }

backed_up=0
for serial in "${serials[@]}"; do
  echo "=== backing up $serial ==="
  out="backups/$serial/$STAMP"
  mkdir -p "$out/shared_prefs"

  "$FLEETCTL" info --device "$serial" > "$out/info.json" 2>/dev/null \
    || { echo "  warn: /info unreachable — skipping $serial"; rm -rf "$out"; continue; }
  "$FLEETCTL" apps --device "$serial" > "$out/apps.json" 2>/dev/null || echo "  warn: /apps failed"
  cp "$FLEET_DIR/$serial.json" "$out/registry.json" 2>/dev/null || true

  names="$("$FLEETCTL" ls "$PREFS_DIR" --device "$serial" 2>/dev/null \
            | sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\.xml\)".*/\1/p')"
  if [ -z "$names" ]; then
    echo "  warn: no readable shared_prefs on $serial"
  else
    while IFS= read -r n; do
      [ -n "$n" ] || continue
      if "$FLEETCTL" pull "$PREFS_DIR/$n" "$out/shared_prefs/$n" --device "$serial" >/dev/null 2>&1; then
        echo "  saved shared_prefs/$n"
      else
        echo "  warn: could not pull $n"
      fi
    done <<< "$names"
  fi

  {
    echo "device:  $serial"
    echo "stamp:   $STAMP"
    grep -E '"versionCode"|"versionName"|"model"' "$out/info.json" 2>/dev/null | sed 's/^[[:space:]]*//'
    echo "installed apps:"
    awk '
      /"packageName"/ { line=$0; sub(/.*"packageName"[ \t]*:[ \t]*"/,"",line); sub(/".*/,"",line); pkg=line }
      /"installed"/   { if ($0 !~ /false/ && pkg != "") print "  " pkg }
    ' "$out/apps.json" 2>/dev/null | sort -u
    echo "shared_prefs:"
    ls -1 "$out/shared_prefs" 2>/dev/null | sed 's/^/  /'
  } > "$out/MANIFEST.txt"

  ln -sfn "$STAMP" "backups/$serial/latest"
  echo "  -> $out"
  backed_up=$((backed_up + 1))
done

echo
echo "backed up $backed_up device(s) to $here/backups/"
echo "WARNING: these files contain tokens and cleartext credentials — keep private (git-ignored)."
