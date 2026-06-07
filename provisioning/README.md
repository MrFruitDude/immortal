# Portal Provisioner

A one-double-click setup tool that turns a connected Meta Portal into a custom device:
installs your client app, replaces the home screen and screensaver, pre-grants permissions,
and enables installing other apps directly on the device. A matching restore tool puts
everything back.

The owner never touches a terminal — they plug in a USB-C cable, accept one prompt on the
Portal, and double-click.

## For the end user

1. **Enable ADB on the Portal** (one time): Settings > Debug > **ADB Enabled**.
2. **Connect** the Portal to the computer with a USB-C cable.
3. **Double-click**:
   - macOS: `Provision-Portal.command`
   - Windows: `Provision-Portal.bat`
4. When the Portal shows **"Allow USB debugging?"**, tap **Allow** (check "Always allow").
5. Wait for "Done." To undo: double-click `Restore-Portal` (`.command` / `.bat`).

> macOS may warn that the file is from an unidentified developer. Right-click → Open the first
> time, or remove the quarantine flag: `xattr -d com.apple.quarantine Provision-Portal.command`.

> **Windows: "unblock" the downloaded files first.** Windows marks files downloaded from the
> internet as blocked, which makes the PowerShell script error out. After extracting the release,
> right-click the provisioning folder (or the individual files inside it) → **Properties** → check
> **Unblock** at the bottom → OK. Or do it in one PowerShell command from inside the folder:
> `Get-ChildItem -Recurse | Unblock-File`. (Thanks to a community member on Reddit for the tip.)

No Android tools required — if `adb` isn't found, the script downloads Google's official
platform-tools automatically into this folder.

## What it does (and how to change it)

Steps, in order: install client APK → push photos → grant permissions → enable on-device
installs → freeze OS updates → set launcher → set screensaver. Each step is toggleable in
`config.env`:

| Key | Meaning |
|---|---|
| `PKG`, `HOME_ACTIVITY`, `DREAM_SERVICE` | Your client app's package and components |
| `SET_LAUNCHER`, `SET_SCREENSAVER`, `DISABLE_VERIFIER`, `DISABLE_OTA` | `true`/`false` per step |
| `PERMISSIONS` | Runtime permissions to pre-grant |
| `PREINSTALL_FDROID`, `PREINSTALL_APKS` | Apps to pre-install during setup (see below) |
| `APK_GLOB` | Which APK to install (drop yours in `apks/`) |
| `ASSET_DIR` | Photos pushed to the frame (first becomes `frame.jpg`) |

To ship your own app instead of the sample, replace `apks/app-debug.apk`, drop photos in
`assets/`, and update `PKG`/`HOME_ACTIVITY`/`DREAM_SERVICE` in `config.env`.

### Silent on-device installs (the install daemon)

Provisioning starts a tiny **install daemon** (`installd.sh`) via ADB. It runs as
the shell user — the same trick [Shizuku](https://github.com/RikkaApps/Shizuku)
uses — so Immortal's on-device App Store and self-update can install apps
**silently** (the launcher drops an APK in a queue; the daemon `pm install`s it).

This is what makes on-device installs work on the **Gen-1 Portal+** (Android 9),
whose built-in install-confirmation dialog renders as a blank window with no
visible buttons. The cause is a Meta Runtime Resource Overlay
(`com.facebook.aloha.rro.niu.android`) that re-themes the framework dialog
white-on-white — the buttons are there, just invisible (you can blind-tap the
bottom-right corner to confirm).

Like all non-root helpers (Shizuku included), the daemon **does not survive a
reboot**. After a reboot, restart it (no full re-provision needed):

```bash
./provision.sh --installd          # macOS/Linux
# powershell ... provision.ps1 -Installd   # Windows
```

### Repairing the stock installer dialog (Gen-1)

Because the daemon doesn't persist across reboots, provisioning **also disables
the offending overlay** so the Portal's own installer dialog becomes usable
again — and that change *is* remembered across reboots (the framework stores
overlay state in `/data/system/overlays.xml`). So when the daemon is down, a
Gen-1 falls back to the now-visible system dialog instead of pausing installs.
It's applied with `cmd overlay disable` (immediate; no reboot, so it can't kill
the running daemon), gated on API < 29, and reversed by `--restore`.

```bash
./provision.sh --overlay-fix       # macOS/Linux (apply on its own)
# powershell ... provision.ps1 -OverlayFix   # Windows
```

Prefer to leave the stock dialog alone? Set `DISABLE_INSTALLER_OVERLAY=false` in
`config.env`; then a rebooted Gen-1 with the daemon down pauses new installs until
you restart the daemon. Newer Portals (API ≥ 29) have a working dialog and ship no
such overlay, so this step is skipped on them automatically.

When the daemon isn't running, the store falls back to the system installer
(visible on newer models, and on a Gen-1 once the overlay fix above is applied).

### Pre-installing apps

`PREINSTALL_FDROID` / `PREINSTALL_APKS` also install apps during setup via a
silent `adb install`, so every freshly provisioned Portal has a few useful apps
out of the box. You can run just this step later:

```bash
./provision.sh --apps          # macOS/Linux
# powershell ... provision.ps1 -Apps   # Windows
```

F-Droid entries are `id` or `id:versionCode` (pin the arm64 build for multi-ABI
apps like VLC). `PREINSTALL_APKS` are direct APK URLs for your own apps.

## Command line (optional)

```bash
./provision.sh            # provision
./provision.sh --status   # show current home / screensaver / install state / client
./provision.sh --restore  # undo
```

Windows: `powershell -ExecutionPolicy Bypass -File provision.ps1 [-Status|-Restore]`.

## What you should know

- **Enabling on-device installs is a security tradeoff.** It relaxes the device's default
  check that otherwise blocks installing apps that aren't signed by Meta. A production store
  should add its own package verification. Restore puts the default back.
- **Disabling OS updates (`DISABLE_OTA`)** stops Meta's updater (`alohaotasetup`) and update UI
  (`otaui`) via reversible `pm disable-user`, so a future OTA can't silently undo this setup or
  reset your launcher/screensaver. Portal is a discontinued line, so this mainly forgoes
  (unlikely) future patches; it's the right call to keep a provisioned device stable, but set
  `DISABLE_OTA=false` if you'd rather keep updates. Restore re-enables them.
- **This is a one-time, per-device step.** It requires the USB/ADB connection once. After
  that, the client app runs normally and can install/update other apps with a single on-screen
  tap — no computer needed again.
- **Reversible.** Restore puts the stock Aloha launcher and screensaver back and undoes the
  install changes. The client app is left installed (uninstall with `adb uninstall <PKG>`).
- Portal receives no further OS updates, so the provisioned state is stable.
