#!/usr/bin/env bash
#
# Prints the GitHub repo this checkout releases to and its Portals update from:
# $RELEASE_REPO if set, else immortal.homeRepo in gradle.properties, else the official repo.
# Used by cut-release.sh and check-version-sync.sh so a fork changes it in one place.
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -n "${RELEASE_REPO:-}" ]; then echo "$RELEASE_REPO"; exit 0; fi
r="$(sed -n 's/^immortal\.homeRepo=\(.*\)$/\1/p' gradle.properties | tr -d '[:space:]' | head -1)"
echo "${r:-starbrightlab/immortal}"
