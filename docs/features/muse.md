# Muse

Immortal can turn a Portal into a **Muse gadget**: the Muse app pairs it like any device built
with Meta's open-source [Muse Gadget SDK](https://gadgets.muse.ai). Muse can then show things on
the Portal's screen, speak and play audio on it, read the room, and reach your other home
devices through it. You can also talk to Muse from the Portal, hands-free, through Alfred.

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

## Alfred

On the Portal, Muse has a face: **Alfred**, a pixel-art office nerd with a messy mop of hair,
round tortoiseshell glasses, a braces grin, a grey suit, a polka-dot tie and a pocket protector.
He's drawn live at 64x64 in the style of the Muse gadgets' avatars, and acts out what's
happening:

- **Listening:** hands to his ears.
- **Thinking:** hand to chin, with thought dots.
- **Speaking:** his mouth moves with the voice.
- **Error:** X eyes.
- **Off:** he waves goodbye.
- **Happy:** tap him to pet him and he hops.

### Talking to Alfred

Say **"Hey Alfred"**, or tap the **Alfred** button on the home screen, and he pops up over
whatever is on screen: the home screen, another app or the photo frame. He doesn't take over
the screen. A small card near the bottom shows him, what he's doing (*Listening*, *Thinking*,
then his reply as he speaks it), and a close button. The screen edges glow softly in his colour
for as long as the conversation lasts. The glow doesn't catch touches, and neither does anything
outside the card, so the app underneath keeps working.

There's no button to hold. It's a conversation:

1. **You talk.** Alfred notices when you start (you don't need to start right after the wake
   word; he waits about 6 seconds) and when you've finished: about 0.8 seconds of silence ends
   your turn (**Settings › Muse › End of turn**). A cough or a door doesn't count, and a turn is
   at most 15 seconds.
2. **He thinks and answers.** Your words go to Muse as a voice note and he speaks the reply.
   While he's thinking and talking the microphone is ignored, so he can't hear himself.
3. **You can answer back.** After he finishes, he keeps listening for about 8 seconds
   (**Follow-up window**) without needing "Hey Alfred" again. If you talk, that's the next turn.
   If you don't, the conversation ends quietly.

The conversation ends when:

- you stay quiet after his reply;
- Alfred decides you're done. Muse can call `conversation.end`, for example after you say
  thanks or your request is complete, and he finishes his sentence first;
- you say **"thanks Alfred"**, **"that's all"**, **"stop"**, **"goodbye"** or **"never mind"**
  on its own. This is recognised on the device, so that phrase never reaches Muse. It needs
  the "Hey Alfred" voice model, so it only works when the wake word is on;
- you tap the card's **✕**, or the Alfred button again;
- the intercom or another higher-priority app takes the microphone;
- it reaches 3 minutes. A reply in progress still finishes.

Turn off **Follow-up listening** for one question and answer per "Hey Alfred".

The popover is drawn by Immortal's accessibility service, the same one behind the quick
buttons, so it needs no extra permission. If that service isn't running, a smaller version
appears in a floating window at the bottom of the screen, with no edge glow.

**Tools › Muse** (or a long-press on the Alfred button) still opens Alfred's full screen, with
the conversation so far and the connection and pairing status. There you can also hold him to
talk.

### "Hey Alfred"

Turn on **Settings › Muse › "Hey Alfred"** and the Portal listens for its wake word. It's built
around privacy:

- **On the device.** Wake-word spotting runs locally (Kaldi via Vosk). Audio stays in memory,
  is never written to disk, and nothing leaves the Portal while it waits. The recogniser only
  runs while there's sound in the room.
- **Only what you say to it.** After "Hey Alfred" (a chime plays), nothing is sent until you
  actually start talking. Then a voice note goes to Muse, starting about half a second before
  you spoke and ending when you stop, at most 15 seconds. Follow-up turns work the same way:
  while the follow-up window waits, nothing leaves the Portal.
- **Only when it makes sense.** It listens only while Muse is connected and, by default, only
  while someone is in the room (Meta's presence sensing); a follow-up window also closes if the
  room empties. It ignores the microphone while Alfred talks, so it can't wake itself, and hands
  the microphone to the intercom, the camera or a voice note whenever they want it (during a
  conversation, only the intercom can take it).
- **Visible.** The glowing edges show whenever Alfred is listening to you, and the Muse screen
  shows when the wake word is on.

The first time it's turned on, it downloads a 41 MB speech model. The download is
checksum-verified.

## What Muse can do on a Portal

| Command | What it does |
|---|---|
| `canvas.show` / `canvas.update` / `canvas.snapshot` / `canvas.close` | **The Portal as Muse's canvas:** full-screen HTML/CSS/JS/SVG (dashboards, briefings, animations, games, interactive pages). See below. |
| `display.draw_url`, `display.show_text`, `display.show_animation` | Show a picture or large text, or clear the screen |
| `speaker.say` | Speak text with the Portal's voice |
| `conversation.end` | End the hands-free conversation once Alfred's reply has been spoken |
| `audio.play_url` / `audio.stop` | Play a stream or file on the Portal |
| `music.radio` | Find a station by name or genre (radio-browser.info) and play it here or on a Google Home / Cast group |
| `voice.configure` | Read or set the volume |
| `app.list`, `app.launch`, `app.open_url`, `media.control` | Open apps and links; play/pause/skip what's playing |
| `ha.states`, `ha.call` | Home Assistant: read entities, call services (once connected, see [Smart home](#smart-home)) |
| `hue.pair`, `hue.lights`, `hue.set` | Philips Hue: pair (press the bridge button), then lights, rooms, colours, scenes |
| `cast.status`, `cast.play_url`, `cast.say`, `cast.control`, `cast.volume` | Google Home / Nest speakers, displays and **speaker groups** |
| `lan.discover`, `lan.http` | Find devices with mDNS; call local HTTP APIs (private addresses only) |
| `sensors.read`, `device.health`, `screensaver.start`, `screen.wake` | Presence and room sensors, status, screen |

### The canvas

`canvas.show` gives Muse the whole screen as a sandboxed web page. Inside it, three calls reach
back:

- `portal.send("…")` posts a message to Muse as coming from this Portal, so a button on Muse's
  page can carry the conversation forward (at most one per 2 s).
- `portal.say("…")` speaks on the Portal.
- `portal.close()` dismisses the canvas.

The page has no access to device files or other apps. A long-press closes it, and so does its
timeout, after which the photo frame comes back. `canvas.snapshot` lets Muse see what it drew.

### Smart home

**Settings › Muse › Smart home › Connect your smart home** (also opened from the home dashboard)
connects Home Assistant and Philips Hue without pasting anything. Alfred and the dashboard's
thermostat and light cards both use these connections.

- **Home Assistant.** The Portal looks for Home Assistant on your network (mDNS
  `_home-assistant._tcp`) and lists what it finds, or you can type its address. Tap one and sign
  in with your Home Assistant account on the Portal's screen; that's HA's own login page. Immortal
  then creates a long-lived token named `Immortal – <Portal name>`. You'll find it, and can revoke
  it, in your HA profile › Security. The temporary login is revoked straight away. The screen
  then shows how many thermostats and lights it found and whether any come from the **Hilo**
  integration (Hydro-Québec). If none do, install Hilo through HACS in Home Assistant.
- **Philips Hue.** The Portal finds the bridge (mDNS `_hue._tcp`; **Search again** also asks
  Philips' discovery service). Tap it and press the round button on the bridge within 30 seconds.
  The screen then shows its rooms and scenes.
- **Disconnect** clears the stored URL and token, or the bridge key. The manual **Home Assistant
  URL** and **token** fields stay under Smart home if you'd rather paste a token yourself.

How the HA sign-in works: the Portal uses HA's standard OAuth2/IndieAuth flow with client id
`http://immortal.portal/` and redirect `http://immortal.portal/auth_callback`. Both are on the
same host, so Home Assistant accepts the redirect without fetching anything. The redirect never
loads; the Portal catches it, checks the `state`, swaps the code for a short-lived token, and
uses HA's WebSocket API once to mint the long-lived one. Tokens are never logged.

### Staying in control

Each capability has its own switch under **Settings › Muse**:

- **Let Muse show things:** display and canvas.
- **Apps & media**
- **Smart home control:** Home Assistant and Hue.
- **Home network access:** LAN and Cast.

Changes take effect immediately: Muse re-registers with only the allowed commands.
**What Muse did on this Portal** lists every command it ran, with a short summary that never
includes message text or credentials.

There is deliberately **no shell**. An unrooted Android app user has no useful shell, and a
remote shell on a living-room screen isn't something to hand an agent.

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
