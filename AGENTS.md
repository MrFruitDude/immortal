# AGENTS.md

Guidance for AI coding agents working in the Immortal repository. This is the canonical
agent guide; tool-specific files (e.g. `CLAUDE.md`) just point here. Human contributors
should read [`CONTRIBUTING.md`](CONTRIBUTING.md) — this file is a superset aimed at agents.

## What this project is

Immortal is a custom home-screen layer (launcher + screensaver + app store + fleet tooling)
for discontinued Meta Portal devices. It is a **single Android app**, package
`com.immortal.launcher`, Jetpack Compose, Kotlin, `minSdk 24` / `targetSdk 36`, targeting
Portal hardware on Android 9 (API 28) and Android 10 (API 29), arm64, **no Google services**.
See [`README.md`](README.md) for the full feature tour.

## Build, test, and validate

Always run the relevant check below after a change and fix failures before finishing.

| Task | Command |
|------|---------|
| Build the app (debug) | `./gradlew :app:assembleDebug` |
| Run unit tests (CI gate) | `./gradlew :app:testDebugUnitTest --no-daemon` |
| Install to a connected Portal | `adb install -r app/build/outputs/apk/debug/app-debug.apk` |
| Launch | `adb shell am start -n com.immortal.launcher/.HomeActivity` |
| Validate the store catalog | `python3 scripts/validate_catalog.py --network` |
| Lint provisioning scripts | `bash -n provisioning/provision.sh` |
| Build the fleet CLI | `cd provisioning && rustc -O fleet.rs -o fleetctl` |

CI workflows live in [`.github/workflows/`](.github/workflows/): `tests.yml` (unit tests),
`catalog.yml` (catalog schema + network checks), `provisioning.yml` (bash/PowerShell parse +
ASCII guard), `docs.yml`, `release-guard.yml`.

The debug build uses a `.debug` application-id suffix so it installs alongside a provisioned
release.

## Repository layout

```
app/                         The Android app (single module)
  src/main/java/com/immortal/launcher/   ~99 Kotlin files, flat package, grouped by name prefix
  src/main/assets/           bundled catalog.json fallback, clock faces, fonts, fallback photos
  src/main/res/              Compose theme lives in .../launcher/ui/theme/
  src/test/java/             unit tests
  build.gradle.kts           app build config (signing via keystore.properties)
provisioning/                Provisioning kit + the fleet CLI
  provision.sh / provision.ps1   one-double-click device setup (macOS/Linux, Windows)
  fleet.rs                   source of the fleet CLI (std-lib-only Rust, no crates)
  fleetctl                   prebuilt fleet CLI binary
  fleet-backup.sh / fleet-restore.sh   snapshot/restore a Portal's app data
  fleet/<serial>.json        device registry — SECRETS, git-ignored, never commit
  config.env                 provisioning options
docs/                        MkDocs site; docs/features/*.md and docs/design/*.md
scripts/                     validate_catalog.py, cut-release.sh, check-version-sync.sh, …
catalog.json                 hosted app-store catalog (schema v2)
version.json                 self-update manifest (versionCode/versionName + apkUrl)
.kiro/skills/                bundled agent skills (see "Agent skills" below)
```

### Finding code by feature

The Kotlin package is flat; files are grouped by name prefix:

- **Launcher / home grid:** `HomeActivity`, `UserLayout`, `QuickBar*`, `AppSwitcherActivity`
- **Screensaver / photo frame:** `PhotoDreamService`, `PhotoFrameController`, `Screensaver*`,
  `Face*` / `ClockFaces` / `FlipWebClockFaceView`, `DreamPolicy`, `PresenceState`, `AntiBurnIn`
- **Photo sources:** `LocalMedia`, `ImmichSource`, `SmbSource`, `DavSource`, `RemoteAlbum`,
  `Weather`, `CalendarFeed`
- **App store / install:** `StoreActivity`, `StoreCatalog`, `UpdateManager`, `InstallDaemon`,
  `HeadlessInstaller`, `ApkInstallActivity`, `ApkBrowserActivity`, `InstallConfirmService`
- **Fleet agent (on-device HTTP API):** `FleetAgentService`, `FleetHttpServer`, `FleetRoutes`,
  `FleetConfig`, `FleetFs`, `FleetDiag`, `FleetCalendar`, `FleetScreensaver`
- **Multi-room audio / now playing:** `MultiRoom*`, `NowPlaying*`, `Snapcast*`, `Ma*`,
  `MediaSession*`, `MediaNotificationListenerService`
- **Smart home (MQTT):** `Mqtt*`
- **Remote / Portal TV:** `Remote*`, `TvFocus`
- **Boot / lifecycle:** `ImmortalApp`, `BootReceiver`, `BootLaunch`, `Sleep*`, `ScreenControl`

When in doubt, search by symbol rather than guessing the file.

## Fleet management and the `fleetctl` skill

A wall of Portals is managed over WiFi with the **`fleetctl`** CLI, which drives each device's
in-app Fleet Agent HTTP API. There is a bundled agent skill that documents the full workflow:

- **Skill:** [`.kiro/skills/immortal-fleet/SKILL.md`](.kiro/skills/immortal-fleet/SKILL.md)
- **Use it when** deploying/installing/updating apps on Portals, pushing config, managing the
  screensaver or calendar over the air, iterating on a local build (`dev update`), browsing
  device files, or reading logcat/diagnostics across one or many devices.
- **Source / docs:** `provisioning/fleet.rs`, [`docs/features/fleet.md`](docs/features/fleet.md),
  and the Fleet sections of [`provisioning/README.md`](provisioning/README.md).

Quick reference (run from `provisioning/`):

```bash
./fleetctl devices                       # list registered Portals
./fleetctl info --device all             # identity/version/state for every device
./fleetctl install <pkg> --device all    # roll an app to the whole fleet
./fleetctl update --check                # dry-run available updates
./fleetctl dev update <app.apk> --device "<name>"   # install a local build (same signing key!)
```

## Conventions and hard rules

- **Match existing style.** Keep changes focused; prefer editing over rewrites. Reuse existing
  subsystems (the Fleet routes, for example, are a pure consumer of the catalog/installer/
  settings — don't duplicate that logic).
- **Release builds must be signed with the same key every time** — in-place self-update and
  `dev update` are signature-checked by Android. Signing comes from `keystore.properties` (repo
  root, git-ignored) or `~/.immortal-signing/` (preferred). **Never commit a keystore.**
- **Secrets:** `provisioning/fleet/<serial>.json` holds per-device agent tokens and is
  git-ignored. Never print token values or commit that folder. The repo is public.
- **Windows-executed scripts must be pure ASCII** (`provision.ps1`, `*.bat`) — Windows
  PowerShell 5.1 mis-decodes non-ASCII bytes and breaks parsing. CI enforces this. Use `-`
  instead of `—`. `provision.sh` is exempt (macOS/Linux, UTF-8).
- **Gen-1 Portal+ installer is broken** (white-on-white system dialog). On-device installs route
  through the shell-privileged daemon the kit starts — read the README's first-gen section and
  the comments in `InstallDaemon` / `ApkInstallActivity` / the provisioning scripts before
  touching that path.
- **Catalog changes** (`catalog.json`) must pass `scripts/validate_catalog.py`; keep the bundled
  fallback at `app/src/main/assets/catalog.json` in mind.
- **Releases:** the self-update asset must be named `immortal.apk`; bump `version.json`. See
  [`docs/releasing.md`](docs/releasing.md) and `scripts/cut-release.sh`.

## Hardware limits (don't try to "fix" these)

No Google Play Services; bootloader can't be unlocked (no root); USB-C thumb drives don't mount
reliably. See [`docs/limitations.md`](docs/limitations.md). These are firmware facts, not bugs.

## Trademark / scope

Immortal is an independent community project, **not affiliated with or endorsed by Meta**.
See [`DISCLAIMER.md`](DISCLAIMER.md).
