#!/usr/bin/env bash
#
# Pre-release sanity check for the provisioning kit: bash syntax + PowerShell parse.
# CI runs the same checks (.github/workflows/provisioning.yml); run this locally
# before a release so a .ps1 typo never ships to Windows users.
#
#   scripts/check-provisioning.sh
#
# Needs `pwsh` for the PowerShell check (macOS: `brew install powershell`).
set -euo pipefail
cd "$(dirname "$0")/.."

fail=0

echo "• provision.sh — bash syntax"
if bash -n provisioning/provision.sh; then echo "  OK"; else echo "  FAILED"; fail=1; fi

echo "• provision.ps1 — PowerShell parse"
if command -v pwsh >/dev/null 2>&1; then
  if pwsh -NoProfile -Command '
      $e=$null
      [void][System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path "provisioning/provision.ps1").Path, [ref]$null, [ref]$e)
      if ($e) { $e | ForEach-Object { Write-Error "  line $($_.Extent.StartLineNumber): $($_.Message)" }; exit 1 }'; then
    echo "  OK"
  else
    echo "  FAILED"; fail=1
  fi
else
  echo "  SKIPPED — pwsh not installed (brew install powershell)"
fi

[ "$fail" -eq 0 ] && echo "Provisioning checks passed." || { echo "Provisioning checks FAILED."; exit 1; }
