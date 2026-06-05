# Immortal

A custom home-screen layer for discontinued Meta Portal devices — a play on *Portal*, and on
keeping it alive after Meta wound the platform down. Immortal turns a Portal into a device you
own: your launcher, your screensaver, and an app store that installs a curated catalog
on-device, with remote self-update so it improves over time without a cable.

Package: `com.immortal.launcher` · Target: Meta Portal (Android 10 / API 29, arm64, no GMS).

## What's in it

- **Launcher** (`HomeActivity`) — a fullscreen app grid with a clock/date/weather header and an
  optional charge indicator (shown only on Portal Go, which has a battery). A photo-style
  **Screensaver** button sits top-left; a **Manage** button bottom-right. Manage mode lets you
  remove apps (tap the ✕) and organise the grid into **folders** by dragging one app onto
  another — name them, rename them, and drag apps back out, just like a phone. A green **Calls**
  tile bridges to the stock dialer/contacts for WhatsApp and Messenger calling.
- **Screensaver** (`PhotoDreamService` / `PhotoFrameController`) — a photo frame with stock-style
  clock/battery/date/weather widgets over a rotating photo feed. Swipe to change photos, tap to
  exit. The image source is pluggable (keyless Lorem Picsum by default, Unsplash-ready); weather
  is keyless Open-Meteo + IP geolocation.
- **App Store** (`StoreActivity` / `StoreCatalog`) — renders a hosted JSON catalog
  ([`catalog.json`](catalog.json)) by category and installs apps via `PackageInstaller`. F-Droid
  entries resolve the current APK at install time so the catalog never goes stale; your own apps
  use a direct `apkUrl`.
- **Self-update** (`UpdateManager`) — Immortal polls [`version.json`](version.json); when it
  advertises a higher `versionCode`, it downloads and installs the new build over itself. No
  cable, no laptop.
- **Provisioning kit** ([`provisioning/`](provisioning/)) — one double-click per device: installs
  Immortal, optionally sets it as the home screen and screensaver, and can freeze OS updates so
  the setup sticks. Fully reversible (`Restore-Portal` / `--restore`).

## Install on a Portal

The easiest path is the [provisioning kit](provisioning/): connect the Portal over USB-C with
ADB enabled, then double-click `Provision-Portal` (macOS/Linux) or `Provision-Portal.bat`
(Windows). It fetches the latest release automatically.

To build from source instead:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.immortal.launcher/.HomeActivity
```

## Releasing

Hosted from this repo:

- [`version.json`](version.json) — the self-update manifest. Bump `versionCode`/`versionName`,
  build a signed release, and attach it as `immortal.apk` to a GitHub Release; devices update on
  their next check.
- [`catalog.json`](catalog.json) — the app-store catalog. Edit and commit; clients pick it up on
  next open (a bundled copy ships as the offline fallback).

Release builds must be signed with the **same** key every time (in-place self-update is
signature-checked). Signing is configured via a git-ignored `keystore.properties`; keep that key
backed up safely — losing it means devices can no longer self-update.

## License

MIT — see [LICENSE](LICENSE).
