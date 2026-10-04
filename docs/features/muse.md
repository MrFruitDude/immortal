# Muse

Immortal can turn a Portal into a **Muse gadget**: the Muse app pairs it like any device built
with Meta's open-source [Muse Gadget SDK](https://gadgets.muse.ai). Muse can then show things on
the Portal's screen, speak and play audio on it, read the room, and reach your other home
devices through it. You can also talk to Muse from the Portal with push-to-talk.

The screensaver keeps working. Muse runs as a quiet background service. A picture or note it
sends takes over the screen for a while (or until you tap it), then the photo frame comes back.

## Set it up

1. Get an **SDK token** at [gadgets.muse.ai](https://gadgets.muse.ai/settings/sdk-tokens)
   (Account › SDK tokens). Every gadget needs one to pair. Enter it on the Portal in
   **Settings › Muse › SDK token**, or from a laptop with `fleetctl muse token -`.
2. On the Portal, open **Settings › Muse** (or **Tools › Muse**) and tap **Start pairing**. The
   Portal advertises over Bluetooth as `MuseGadgetXXXXXX` for 10 minutes.
3. In the Muse app, turn on **Settings › Devices › Developer mode**, then **Add Device** and pick
   that name. Muse warns that it's a community device; continue if it's yours. When asked for
   Wi-Fi, pick the network shown. The Portal is already online, so no password is needed.

That's it. The Portal stays connected across reboots and reconnects on its own.

## What Muse can do on a Portal

| Command | What it does |
|---|---|
| `display.draw_url` | Show a picture full screen (JPEG/PNG/WebP/GIF), with an optional caption |
| `display.show_text` | Show a note, list or briefing in large type |
| `display.show_animation` | Clear it and go back to the home screen or photo frame |
| `speaker.say` | Speak text with the Portal's text-to-speech voice |
| `audio.play_url` / `audio.stop` | Play a stream or file (radio, MP3, AAC…) on the Portal |
| `voice.configure` | Read or set the media volume |
| `sensors.read` | Presence (Meta's own detector where available), screen state, light/temperature |
| `screensaver.start`, `screen.wake` | Start the photo frame, or turn the screen on |
| `device.health` | Model, Android version, memory, storage, battery, Wi-Fi, TTS availability |
| `lan.discover` | Find devices on your Wi-Fi with mDNS (Cast, AirPlay, Sonos, Hue, HomeKit, ESPHome…) |
| `lan.http` | Call a local device's HTTP API (Hue, Shelly, Elgato, Home Assistant…) |
| `cast.status`, `cast.play_url`, `cast.control`, `cast.volume` | Control Google Home / Nest speakers and displays, Chromecasts and **speaker groups** |
| `cast.say` | Speak something on a Google Home speaker or group (the Portal renders the speech and serves it to the speaker) |

So you can ask Muse things like *"show me tomorrow's weather on the kitchen Portal"*, *"play
BBC Radio 4 on the Portal"*, *"announce dinner on all the Google Homes"* or *"turn the Hue
lights down"*.

Turn any of these off under **Settings › Muse**. **Let Muse show things** covers the display
commands. **Home network access** covers `lan.*` and `cast.*`; these only ever reach private
(RFC 1918 / link-local) addresses and never follow redirects. Changes re-register with Muse
immediately.

There is deliberately **no shell**. The Linux SDK gives Muse `system.run`, but an unrooted
Android app user has no useful shell, and a remote shell on a living-room screen isn't something
to hand an agent.

## Talk to Muse

Once paired, the home screen's **hey** button opens Muse push-to-talk (turn this off under
**Settings › Muse › Hey button opens Muse**; long-press still reaches the stock assistant picker
where one is installed). Hold the big button and speak, then release to send. A quick tap
starts listening and a second tap sends. Muse transcribes the voice note, and the Portal shows
and speaks the reply. Muse doesn't voice gadget replies itself, so this needs a text-to-speech
engine on the Portal (`device.health` reports `tts_available`).

Android 10 Portals block background microphone use for sideloaded apps, so there's no wake
word. Push-to-talk works because the Muse screen is in the foreground.

## From a laptop (`fleetctl`)

```bash
./fleetctl muse status  --device "Kitchen"    # state, BLE name, paired / token set
./fleetctl muse token - --device "Kitchen"    # paste the mgst_ token on stdin
./fleetctl muse pair    --device "Kitchen"    # open the 10-minute pairing window
./fleetctl muse send "Remind me to water the plants at 6" --device "Kitchen"
./fleetctl muse say "Dinner's ready" --device all
./fleetctl muse unpair  --device "Kitchen"
```

The same is available as `GET/POST /muse` on the fleet agent (see [Fleet management](fleet.md)).
Tokens are never echoed back.

## How it works

Immortal speaks the gadget protocol natively in Kotlin. It doesn't run the SDK's Python
service, which would need a Linux userland and root:

- **Pairing** (`MusePairing`, `MuseBle`): a BLE GATT peripheral with the SDK's setup service,
  plus community pairing v5 (P-256 ECDH, HKDF-SHA256, AES-256-GCM records, `confirm_app`). It is
  verified against the SDK's published test vectors.
- **Session** (`MuseNoise`, `MuseLink`): a TLS WebSocket to `/v1/noise`, then a
  `Noise_XX_25519_AESGCM_SHA256` handshake (X25519 implemented in-app, since Android 9/10 have no
  provider), then HTTP-shaped streams multiplexed over it. `/link-control` carries `link.register`
  / `link.invoke` / `link.result`; voice notes go to `/chat/stream` and replies come back on
  `/chat/subscribe`.
- **Runtime** (`MuseService` / `MuseRuntime`): leased-VM lookup, reconnect with backoff, and
  device-token rotation every 3 hours, as in the SDK's `service.py`. It registers like the
  Linux SDK (`platform: linux`, `device_family: homehub`).

Pairing has the SDK's limits. Community pairing has no manufacturer verification, so it can't
stop an active man-in-the-middle during the 10-minute window. Pair on a network you trust.

## Google Home, Chromecast and AirPlay

- **Portal → Google Home / Nest:** Immortal is a Cast **sender** with no Google services. Muse
  (or you, through Muse) can play to any Cast speaker, display or speaker group and set its
  volume. A Portal cannot become a Cast **receiver** or join a Google Home speaker group: that
  needs a Google-fused device certificate, which only certified hardware has.
- **iPhone → Portal:** install **AirPlay Server** from the App Store (Media). It's an
  open-source AirPlay receiver (built on UxPlay) that needs no Google services. Open it once, and
  the Portal then shows up in the iPhone's AirPlay menu for audio, video and screen mirroring.
  Audio works on every Portal. Mirroring depends on the Portal's video decoder, so try it on
  yours.
