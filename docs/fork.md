# Running your own fork

A fork can be the update source for its own Portals: your key signs the builds, your GitHub
releases ship them, and each Portal's self-updater, app store and provisioning kit follow your
repo instead of the official one. You can still merge official updates whenever you choose.

## One setting

The repo a build follows is set in one place, `gradle.properties`:

```properties
immortal.homeRepo=you/immortal
```

That drives:

- **Self-update:** `https://raw.githubusercontent.com/<homeRepo>/main/version.json`, then
  `releases/latest/download/immortal.apk`.
- **App store catalog:** `<homeRepo>/main/catalog.json`.
- **Releases:** `scripts/cut-release.sh` publishes to it, through whichever git remote points at
  it.

Alongside it, a fork also changes:

- `RELEASE_REPO` in `provisioning/config.env`, so freshly provisioned Portals install your build.
- The `apkUrl` in `version.json`, and the Immortal entry in both catalogs (written for you by
  `cut-release.sh` and checked by `scripts/check-version-sync.sh`).

## Switching Portals from the official build to your fork (once per Portal)

Android only installs an update signed with the same key as the installed app. Moving a Portal
from the official build to yours is therefore a reinstall:

1. `provisioning/fleet-backup.sh "<name>"` saves every Immortal setting over Wi-Fi.
2. On the Portal: **Settings › Security › Device admin apps › Immortal › Deactivate** (Android
   won't let this be done remotely).
3. Over USB: `adb uninstall com.immortal.launcher`, then `./provision.sh` (installs your latest
   release and sets it as home and screensaver) and `./provision.sh --fleet` (re-registers the
   Portal with `fleetctl`; this fork's `config.env` has `ENABLE_FLEET=true`). Values in
   `config.env` override environment variables, so set a device name afterwards with
   `fleetctl config --name "…"`.
4. `provisioning/fleet-restore.sh "<name>"` brings the settings and apps back.

From then on the Portal updates itself from your releases.

## Shipping an update

```bash
scripts/cut-release.sh 1.76 "What changed"
```

The script bumps `versionCode`/`versionName` in `app/build.gradle.kts` and `version.json`
together, builds and verifies the signed APK, then tags, pushes and publishes the release.
Every Portal picks it up on its next update check.

## Pulling in official updates

Keep the official repo as a remote (here `origin`; often `upstream`) and merge when you want:

```bash
git fetch origin
git checkout main
git merge origin/main
```

Expect conflicts in only a few places. Resolve them like this:

- **`gradle.properties` `immortal.homeRepo`, `provisioning/config.env` `RELEASE_REPO`:** keep
  yours.
- **`version.json`, `versionCode` / `versionName` in `app/build.gradle.kts`:** keep yours, but
  if the official `versionCode` is higher, take the higher number. `cut-release.sh` bumps from
  whatever is there, and `versionCode` must only ever go up.
- **The Immortal entry in `catalog.json` / `app/src/main/assets/catalog.json`:** keep your
  `apkUrl`.

Then cut a release as above.
