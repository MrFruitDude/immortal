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
| `APK_GLOB` | Which APK to install (drop yours in `apks/`) |
| `ASSET_DIR` | Photos pushed to the frame (first becomes `frame.jpg`) |

To ship your own app instead of the sample, replace `apks/app-debug.apk`, drop photos in
`assets/`, and update `PKG`/`HOME_ACTIVITY`/`DREAM_SERVICE` in `config.env`.

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
