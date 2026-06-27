# Alexa & voice (first-gen Portals)

Immortal can revive the Portal's original on-device **Amazon Alexa** client for text, voice, and
visual answers. The always-listening **"Hey Alexa"** wake-word helper is available as a separate
opt-in because it can keep the microphone busy during Messenger calls on some Gen-1 hardware.

!!! danger "First-gen (Android 9) Portals only"
    Alexa revival and the wake word are supported on **first-gen Portals (Android 9)** — the
    Portal/Portal+ Gen-1 and Portal TV.

    They are **not supported on Android 10 models (Portal Go, Portal Mini, and the 2nd-gen
    Portal+) at this time.** Those Portals hard-block background microphone capture for sideloaded
    apps, so the revived Alexa never receives audio for the wake word or button hand-off. The
    Immortal launcher itself installs and works fine on those models — only the Alexa/voice
    feature is unavailable. (Android 10 support is in research.)

## How it works (and why it's allowed)

Alexa support has two pieces:

- **falcon** — the Portal's original Amazon Alexa client (`com.amazon.alexa.multimodal.falcon`),
  revived so it runs again on the locked, unrooted device.
- **"hey" (Millennium)** — an optional wake-word app that drives falcon hands-free and adds the
  launcher mic button.

Immortal **never hosts Amazon's binary.** The provisioning kit either downloads a
**patched-and-signed** falcon build, or reconstructs it on your machine from the **public Portal
firmware dump plus a binary diff** (`bspatch`) — so what's distributed is only the diff, not
Amazon's APK.

## Setup

Run the Alexa step from the [provisioning kit](../provisioning.md):

```bash
./provision.sh --alexa
```

(Or answer **yes** when a normal `./provision.sh` asks *"Restore Amazon Alexa on this Portal?"*.)

The kit then:

1. **Installs falcon** (~115 MB download) and grants it the privileged permissions it needs
   (microphone, etc.), persisted across reboots. It uses `install -r` only and **never uninstalls**
   falcon — see the warning below.
2. **Opens Alexa to link your Amazon account.** A sign-in code appears on the Portal — go to
   [amazon.com/code](https://www.amazon.com/code) and enter it. You can do this while the rest of
   setup runs.
3. **Skips the "hey" (Millennium) wake-word app by default** so Messenger calls keep exclusive
   access to clean microphone audio.
4. **Waits for Alexa to connect** (it watches for the device reaching its ready state).

Once linked, you can hide falcon's icon from the launcher — it runs headless.

!!! warning "Wake word is explicit opt-in"
    Set `INSTALL_ALEXA_WAKE_WORD=true` in `provisioning/config.env`, then re-run
    `./provision.sh --alexa`, to install the Millennium wake-word app and show the launcher mic
    button. This restores hands-free "Hey Alexa", but on at least one Gen-1 Portal+ it caused
    Facebook Messenger call microphone audio to cut in and out. Leave it off if Messenger calling
    matters on that device.

!!! warning "Windows: the reconstruct path needs bspatch"
    macOS and Linux ship (or can install) `bspatch`. Windows doesn't, so on Windows use the hosted
    patched build (the default) or point `BSPATCH_EXE` at a `bspatch.exe` in `config.env`.

## Using the "hey" button

When `INSTALL_ALEXA_WAKE_WORD=true` and the Millennium app is installed, a **"hey" mic button**
appears in the home header:

- **Tap** — push-to-talk: wakes your assistant without saying the wake word.
- **Long-press** — pick *which* assistant to talk to (the chooser is provided by Millennium;
  Premium unlocks more than the built-in Alexa).

The trigger is guarded so only the launcher itself can fire it, not other apps on the device.

## Removing it

`./provision.sh --restore` removes **only** the "hey" wake-word app. Running
`./provision.sh --alexa` with the default `INSTALL_ALEXA_WAKE_WORD=false` also removes Millennium
if an older setup installed it.

!!! warning "falcon is left installed on purpose"
    Uninstalling falcon drops its Amazon registration and would mint a **new ghost device** with
    Amazon on the next install. The restore step leaves it in place deliberately — remove it by hand
    only if you understand that.

## Troubleshooting

- **"Skipping Alexa — not supported on this Portal yet"** — you're on an Android 10 model; this is
  expected (see the note at the top).
- **Alexa didn't connect within ~6 minutes** — check Wi-Fi and that the Amazon account finished
  linking at [amazon.com/code](https://www.amazon.com/code), then re-run `./provision.sh --alexa`.
- **Duplicate-permission conflict with `com.amazon.dee.app`** — the standalone Amazon Alexa app
  conflicts with falcon's permissions; a coexistence build is needed, so remove that app or skip it.
- **Wake word never triggers** — set `INSTALL_ALEXA_WAKE_WORD=true` in `config.env`, then re-run
  the Alexa step so the "hey" (Millennium) app is installed and holds the microphone permission.
- **Messenger calls sound broken after enabling wake word** — turn the wake word back off by
  setting `INSTALL_ALEXA_WAKE_WORD=false` and re-running `./provision.sh --alexa`, or run
  `./provision.sh --restore`.
