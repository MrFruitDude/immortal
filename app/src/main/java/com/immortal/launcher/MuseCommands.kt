/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * The commands Muse can invoke on a Portal, advertised in `link.register` as `commands_v2`.
 *
 * Shaped for what a Portal is — a screen, a speaker, sensors and a seat on the home network —
 * rather than the Linux SDK's shell access: an unrooted app user has no useful shell, and a
 * remote shell on a living-room screen isn't something to hand an agent anyway.
 */
class MuseCommands(private val context: Context) {

  /** `commands_v2`: name → {description, required, optional, timeout_ms?}. */
  fun specs(): JSONObject {
    val c = JSONObject()
    val (w, h) = screenSize()
    c.put("device.health", spec("Portal status: model, Android version, uptime, memory, storage, battery, " +
        "Wi-Fi, volume, presence and whether text-to-speech is available."))
    c.put("sensors.read", spec("Read the room: presence (someone in front of the Portal, from Meta's own " +
        "detector when available), whether the screen is on, and the ambient sensors this model has " +
        "(light in lux, temperature in °C, humidity, pressure)."))
    if (MuseConfig.allowDisplay(context)) {
      c.put("display.draw_url", spec(
          "Show an image full screen on this Portal's ${w}x$h colour touch screen (landscape). Takes a " +
              "JPEG, PNG, WebP or GIF over http:// or https://. Wakes the screen; the picture stays " +
              "up for `seconds` (default ${MuseConfig.imageSeconds(context)}) or until someone taps it, then the " +
              "home screen or photo frame comes back. Replies once the image is on screen.",
          required = mapOf("url" to p("string", "http:// or https:// URL of the image.")),
          optional = mapOf(
              "seconds" to p("integer", "How long to keep it up, 5 to 3600."),
              "caption" to p("string", "Optional line of text shown under the image.")),
          timeoutMs = 60_000))
      c.put("display.show_text", spec(
          "Show large text full screen (a note, reminder, shopping list, briefing). Same timing as " +
              "display.draw_url.",
          required = mapOf("text" to p("string", "The text; newlines are kept.")),
          optional = mapOf(
              "title" to p("string", "Optional heading."),
              "seconds" to p("integer", "How long to keep it up, 5 to 3600."))))
      c.put("display.show_animation", spec("Clear whatever Muse put on the screen and go back to the " +
          "home screen / photo frame."))
    }
    c.put("voice.configure", spec(
        "Set or read the Portal's media volume (0 to 100). Without volume, reports the current one.",
        optional = mapOf("volume" to p("integer", "Speaker volume, 0 to 100."))))
    c.put("speaker.say", spec(
        "Speak text out loud on this Portal with its text-to-speech voice. Replies when it has " +
            "finished speaking.",
        required = mapOf("text" to p("string", "What to say.")),
        optional = mapOf("language" to p("string", "BCP-47 language tag, e.g. en-US. Default: the Portal's.")),
        timeoutMs = 120_000))
    c.put("audio.play_url", spec(
        "Play an audio stream or file (MP3, AAC, OGG, WAV, internet radio) on this Portal's speaker. " +
            "Replies once playback has started.",
        required = mapOf("url" to p("string", "http:// or https:// URL.")),
        optional = mapOf("volume" to p("integer", "Set the media volume first, 0 to 100.")),
        timeoutMs = 30_000))
    c.put("audio.stop", spec("Stop audio started with audio.play_url or speaker.say."))
    c.put("screensaver.start", spec("Start the photo-frame screensaver now."))
    c.put("screen.wake", spec("Turn the screen on (for example before showing something)."))
    if (MuseConfig.allowLan(context)) {
      c.put("lan.discover", spec(
          "Find devices on the home network with mDNS/DNS-SD (Google Cast speakers and displays, " +
              "AirPlay, Sonos, Hue, HomeKit, ESPHome, Shelly, printers, Home Assistant, ...). Returns each " +
              "service's name, type, host, port, IP addresses and TXT data.",
          optional = mapOf(
              "types" to p("array", "Service types such as _googlecast._tcp; default: a broad common set."),
              "timeout_ms" to p("integer", "How long to listen, 1000 to 10000. Default 3000.")),
          timeoutMs = 15_000))
      c.put("lan.http", spec(
          "Make an HTTP request to a device on the home network (private IP addresses or .local names " +
              "only; redirects are not followed). For local device APIs such as Hue, Shelly, Elgato, " +
              "ESPHome or Home Assistant. Text bodies come back as `body`, others as `body_b64`, cut at 96 KiB.",
          required = mapOf("url" to p("string", "http:// or https:// URL on the LAN.")),
          optional = mapOf(
              "method" to p("string", "GET, POST, PUT, DELETE, ... Default GET."),
              "headers" to p("object", "Request headers."),
              "body" to p("string", "Text request body."),
              "body_b64" to p("string", "Binary request body, base64."),
              "insecure_tls" to p("boolean", "Accept self-signed HTTPS certificates. Default false."),
              "timeout_ms" to p("integer", "Read timeout, 1000 to 60000. Default 15000.")),
          timeoutMs = 65_000))
      val castTarget = mapOf("host" to p("string", "IP address of the Cast device or speaker group (from lan.discover _googlecast._tcp)."))
      val castPort = "port" to p("integer", "Its advertised port. Default 8009; speaker groups use their own.")
      c.put("cast.status", spec(
          "Read a Google Cast receiver (Google Home / Nest speaker or display, Chromecast, speaker " +
              "group): volume, mute, running app and, if media is playing, its state.",
          required = castTarget, optional = mapOf(castPort), timeoutMs = 20_000))
      c.put("cast.play_url", spec(
          "Play a media URL on a Google Cast receiver or speaker group with the Default Media " +
              "Receiver. Replaces what it was playing. Replies once it reports PLAYING.",
          required = castTarget + mapOf(
              "url" to p("string", "Media URL the speaker itself can fetch."),
              "content_type" to p("string", "MIME type, e.g. audio/mpeg, audio/aac, video/mp4.")),
          optional = mapOf(castPort,
              "title" to p("string", "Title to show."),
              "live" to p("boolean", "A live stream (radio) rather than a file. Default false.")),
          timeoutMs = 45_000))
      c.put("cast.say", spec(
          "Speak text out loud on a Google Cast speaker or speaker group: this Portal synthesizes the " +
              "speech and serves it to the speaker on the home network. Interrupts what it was playing.",
          required = castTarget + mapOf("text" to p("string", "What to say.")),
          optional = mapOf(castPort, "language" to p("string", "BCP-47 language tag.")),
          timeoutMs = 60_000))
      c.put("cast.control", spec(
          "Pause, resume or stop media on a Google Cast receiver.",
          required = castTarget + mapOf("action" to p("string", "pause, resume or stop.")),
          optional = mapOf(castPort), timeoutMs = 20_000))
      c.put("cast.volume", spec(
          "Set a Google Cast receiver's volume (0 to 100) and/or mute; replies with the read-back value.",
          required = castTarget,
          optional = mapOf(castPort,
              "volume" to p("integer", "0 to 100."),
              "muted" to p("boolean", "Mute or unmute.")),
          timeoutMs = 20_000))
    }
    if (MuseConfig.allowDisplay(context)) {
      c.put("canvas.show", spec(
          "Use the Portal's ${w}x$h screen as your canvas: render any HTML/CSS/JavaScript/SVG page full " +
              "screen (dashboards, briefings, photo walls, clocks, animations, games, interactive pages). " +
              "Inside the page, `portal.send(text)` posts a message back to you as coming from this Portal " +
              "(so buttons and taps can drive the conversation; at most one per 2 s), `portal.say(text)` " +
              "speaks on the Portal, `portal.close()` dismisses it. Long-press closes it too. Pages can " +
              "load anything over https; no access to device files. Replies once the page has loaded.",
          optional = mapOf(
              "html" to p("string", "A complete HTML document to render."),
              "url" to p("string", "Or an http(s) URL to load instead."),
              "seconds" to p("integer", "How long to keep it up; 0 = until closed. Default 300.")),
          timeoutMs = 30_000))
      c.put("canvas.update", spec(
          "Run JavaScript in the canvas page that's showing (update a live dashboard, advance a slide, " +
              "animate). Returns the expression's value as JSON.",
          required = mapOf("js" to p("string", "JavaScript to evaluate in the page.")), timeoutMs = 15_000))
      c.put("canvas.snapshot", spec(
          "See what the canvas currently shows: a JPEG screenshot (base64) of the page, to check your work.",
          timeoutMs = 10_000))
      c.put("canvas.close", spec("Close the canvas and return to the home screen / photo frame."))
    }
    if (MuseConfig.allowApps(context)) {
      c.put("app.list", spec("List the apps installed on this Portal (name and package)."))
      c.put("app.launch", spec("Open an app on this Portal by package name or name (e.g. SmartTube, VLC, Jellyfin).",
          required = mapOf("app" to p("string", "Package name or app name."))))
      c.put("app.open_url", spec("Open a link on this Portal in the app that handles it (a YouTube link opens " +
          "in the YouTube app, a web page in the browser).", required = mapOf("url" to p("string", "http(s) link."))))
      c.put("media.control", spec("Control what's playing on this Portal (any app with a media session).",
          required = mapOf("action" to p("string", "play_pause, next or previous."))))
    }
    if (MuseMusic.configured(context)) {
      c.put("music.players", spec(
          "List the Music Assistant players: every Google Home / Nest speaker and speaker group, the Portals " +
              "(Snapcast) and sync groups — with what each is playing and its volume.", timeoutMs = 20_000))
      c.put("music.search", spec(
          "Search Music Assistant (Spotify and the rest of the library) for tracks, albums, artists, " +
              "playlists or radio. Returns items with a `uri` to play.",
          required = mapOf("query" to p("string", "What to look for.")),
          optional = mapOf(
              "types" to p("array", "Any of track, album, artist, playlist, radio. Default: all."),
              "limit" to p("integer", "Per type, default 5.")),
          timeoutMs = 30_000))
      c.put("music.play", spec(
          "Play music on any room through Music Assistant: a `uri` from music.search, or the best match for " +
              "`query` (a song, album, artist, playlist like 'Discover Weekly', or a genre). Plays on " +
              "`player` (name or id from music.players: a Google Home, a speaker group, a Portal or a sync " +
              "group, all in sync).",
          required = mapOf("player" to p("string", "Player or group name/id.")),
          optional = mapOf(
              "uri" to p("string", "Media URI from music.search."),
              "query" to p("string", "Or what to search and play."),
              "type" to p("string", "Prefer this type for query: track, album, artist, playlist, radio."),
              "enqueue" to p("string", "play (default), replace, next or add."),
              "radio" to p("boolean", "Keep similar music playing afterwards.")),
          timeoutMs = 45_000))
      c.put("music.control", spec(
          "Control a Music Assistant player: play, pause, play_pause, stop, next, previous, and/or volume 0-100.",
          required = mapOf("player" to p("string", "Player or group name/id.")),
          optional = mapOf("action" to p("string", "play, pause, play_pause, stop, next, previous."),
              "volume" to p("integer", "0-100.")),
          timeoutMs = 20_000))
    }
    c.put("music.radio", spec(
        "Find an internet radio station by name or genre (e.g. 'BBC Radio 4', 'jazz', 'lofi', 'classical') and " +
            "play it — on this Portal, or on a Google Cast speaker or speaker group if `host` is given. " +
            "Returns the station played and alternatives.",
        required = mapOf("query" to p("string", "Station name or genre.")),
        optional = mapOf(
            "host" to p("string", "Play on this Cast device/group instead (from lan.discover)."),
            "port" to p("integer", "Its Cast port (groups use their own; default 8009)."),
            "pick" to p("integer", "Which search result to play, 0-based. Default 0.")),
        timeoutMs = 60_000))
    if (MuseConfig.allowSmartHome(context)) {
      if (MuseHomeAssistant.configured(context)) {
        c.put("ha.states", spec(
            "Read the smart home from Home Assistant: entities with their state (lights, switches, climate, " +
                "sensors, locks, media players, …). Filter by domain and/or a search on id or name.",
            optional = mapOf(
                "domain" to p("string", "e.g. light, switch, climate, sensor, media_player, cover, lock."),
                "search" to p("string", "Substring of the entity id or friendly name."),
                "limit" to p("integer", "Max entities, default 60.")),
            timeoutMs = 25_000))
        c.put("ha.call", spec(
            "Call a Home Assistant service — turn things on/off, set temperatures, run scripts and scenes, " +
                "lock doors, play media… e.g. domain=light service=turn_on data={entity_id, brightness_pct, rgb_color}.",
            required = mapOf("domain" to p("string", "Service domain."), "service" to p("string", "Service name.")),
            optional = mapOf("data" to p("object", "Service data, including entity_id.")),
            timeoutMs = 25_000))
      }
      c.put("hue.pair", spec(
          "Connect to the Philips Hue bridge on this network. The first call asks the user to press the " +
              "bridge's link button; call again within 30 s to finish. Only needed once.",
          optional = mapOf("host" to p("string", "Bridge IP if discovery doesn't find it.")), timeoutMs = 20_000))
      if (MuseHue.paired(context)) {
        c.put("hue.lights", spec("List Hue lights (on, brightness), rooms and scenes.", timeoutMs = 15_000))
        c.put("hue.set", spec(
            "Control Hue lights: a light or a room (by id or name), or every light if neither is given. " +
                "Turn on/off, brightness 0-100, colour as #rrggbb, white temperature in kelvin, or recall a scene id.",
            optional = mapOf(
                "light" to p("string", "Light id or name."),
                "room" to p("string", "Room/zone id or name."),
                "on" to p("boolean", "On or off."),
                "brightness" to p("integer", "0-100."),
                "color" to p("string", "#rrggbb."),
                "kelvin" to p("integer", "2000-6500."),
                "scene" to p("string", "Scene id (from hue.lights), with room."),
                "transition_ms" to p("integer", "Fade time.")),
            timeoutMs = 15_000))
      }
    }
    return c
  }

  fun run(command: String, params: JSONObject, timeoutMs: Long?): JSONObject {
    val result = dispatch(command, params)
    MuseActionLog.record(command, params, result)
    return result
  }

  private fun dispatch(command: String, params: JSONObject): JSONObject =
      try {
        when (command) {
          "device.health" -> ok(health())
          "sensors.read" -> ok(sensors())
          "display.draw_url" -> drawUrl(params)
          "display.show_text" -> showText(params)
          "display.show_animation" -> {
            MuseDisplayActivity.dismiss(context)
            ok(JSONObject())
          }
          "voice.configure" -> volume(params)
          "speaker.say" -> say(params)
          "audio.play_url" -> playUrl(params)
          "audio.stop" -> {
            MuseAudio.stop()
            MuseSpeech.stop()
            ok(JSONObject())
          }
          "screensaver.start" -> {
            context.startActivity(Intent(context, PhotoFramePreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ok(JSONObject())
          }
          "screen.wake" -> {
            ScreenControl.wake(context)
            ok(JSONObject())
          }
          "lan.discover" -> lanOnly { discover(params) }
          "lan.http" -> lanOnly { ok(MuseLanHttp.request(params)) }
          "cast.status" -> lanOnly { cast(params) { it.status() } }
          "cast.play_url" -> lanOnly {
            cast(params) { it.play(params.getString("url"), params.getString("content_type"), params.optString("title"), params.optBoolean("live")) }
          }
          "cast.say" -> lanOnly { castSay(params) }
          "cast.control" -> lanOnly { cast(params) { it.control(params.getString("action").lowercase()) } }
          "cast.volume" -> lanOnly {
            cast(params) {
              it.volume(
                  if (params.has("volume")) params.getInt("volume").coerceIn(0, 100) / 100.0 else null,
                  if (params.has("muted")) params.getBoolean("muted") else null)
            }
          }
          "canvas.show" -> displayOnly {
            val err = MuseCanvasActivity.show(context, params.optString("html").ifEmpty { null },
                params.optString("url").ifEmpty { null }, params.optInt("seconds", 300).coerceIn(0, 86_400))
            if (err == null) ok(JSONObject().put("shown", true)) else error(err)
          }
          "canvas.update" -> displayOnly {
            MuseCanvasActivity.evaluate(params.getString("js"))?.let { ok(JSONObject().put("result", it)) }
                ?: error("no canvas is showing; use canvas.show first")
          }
          "canvas.snapshot" -> displayOnly {
            MuseCanvasActivity.snapshot()?.let { ok(JSONObject().put("mime_type", "image/jpeg").put("data_base64", it)) }
                ?: error("no canvas is showing")
          }
          "canvas.close" -> {
            MuseCanvasActivity.close()
            ok(JSONObject())
          }
          "app.list" -> appsOnly { ok(MuseApps.list(context)) }
          "app.launch" -> appsOnly { ok(MuseApps.launch(context, params.getString("app"))) }
          "app.open_url" -> appsOnly { ok(MuseApps.open(context, params.getString("url"))) }
          "media.control" -> appsOnly {
            when (params.getString("action")) {
              "play_pause", "play", "pause" -> NowPlayingHub.playPause()
              "next" -> NowPlayingHub.next()
              "previous" -> NowPlayingHub.previous()
              else -> return error("action must be play_pause, next or previous")
            }
            ok(JSONObject())
          }
          "music.radio" -> radio(params)
          "music.players" -> ok(MuseMusic.players(context))
          "music.search" -> ok(MuseMusic.search(context, params.getString("query"),
              params.optJSONArray("types")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
              params.optInt("limit", 5).coerceIn(1, 20)))
          "music.play" -> ok(MuseMusic.play(context, params))
          "music.control" -> ok(MuseMusic.control(context, params))
          "ha.states" -> smartHome {
            ok(MuseHomeAssistant.states(context, params.optString("domain").ifEmpty { null },
                params.optString("search").ifEmpty { null }, params.optInt("limit", 60).coerceIn(1, 300)))
          }
          "ha.call" -> smartHome {
            ok(MuseHomeAssistant.callService(context, params.getString("domain"), params.getString("service"),
                params.optJSONObject("data") ?: JSONObject()))
          }
          "hue.pair" -> smartHome { ok(MuseHue.pair(context, params.optString("host").ifEmpty { null })) }
          "hue.lights" -> smartHome { ok(MuseHue.lights(context)) }
          "hue.set" -> smartHome { ok(MuseHue.set(context, params)) }
          else -> error("unsupported command: $command")
        }
      } catch (e: Exception) {
        Log.w(TAG, "$command failed", e)
        error("${e.javaClass.simpleName}: ${e.message}")
      }

  // --- handlers ---------------------------------------------------------------

  private fun health(): JSONObject {
    val am = context.getSystemService(AudioManager::class.java)
    val rt = Runtime.getRuntime()
    val mem = android.app.ActivityManager.MemoryInfo().also {
      context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(it)
    }
    val data = android.os.StatFs(context.filesDir.path)
    val p = PresenceHub.current
    return JSONObject()
        .put("name", FleetConfig.name(context))
        .put("model", Build.MODEL)
        .put("android", Build.VERSION.RELEASE)
        .put("immortal_version", BuildConfigCompat.versionName)
        .put("uptime_s", SystemClock.elapsedRealtime() / 1000)
        .put("memory_mb", JSONObject().put("total", mem.totalMem / 1048576).put("available", mem.availMem / 1048576)
            .put("app_heap_used", (rt.totalMemory() - rt.freeMemory()) / 1048576))
        .put("storage_gb", JSONObject().put("total", "%.1f".format(data.totalBytes / 1e9)).put("free", "%.1f".format(data.availableBytes / 1e9)))
        .put("battery", battery())
        .put("volume", am?.let { volumePercent(it) } ?: JSONObject.NULL)
        .put("presence", p.presence.name)
        .put("screen", p.screen.name)
        .put("tts_available", MuseSpeech.available(context))
        .put("screen_size", screenSize().let { "${it.first}x${it.second}" })
  }

  private fun battery(): Any {
    if (!DreamPolicy.hasBattery(context)) return JSONObject.NULL
    val i = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return JSONObject.NULL
    val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
    return JSONObject().put("percent", if (level >= 0) level * 100 / scale else JSONObject.NULL).put("charging", DreamPolicy.isPowered(context))
  }

  private fun sensors(): JSONObject {
    val p = PresenceHub.current
    val out = JSONObject()
        .put("presence", p.presence.name)
        .put("presence_source", p.source.name)
        .put("confident", p.confident)
        .put("screen", p.screen.name)
    val sm = context.getSystemService(SensorManager::class.java) ?: return out
    val readings = JSONObject()
    for (kind in AmbientKind.values()) {
      val sensor = runCatching { sm.getDefaultSensor(kind.sensorType) }.getOrNull() ?: continue
      readings.put(kind.key, readOnce(sm, sensor)?.let { v ->
        val c = if (kind == AmbientKind.TEMPERATURE) AmbientPolicy.calibrate(v, MqttConfig.tempOffset(context)) else v
        JSONObject().put("value", AmbientPolicy.format(c, kind.decimals).toDoubleOrNull() ?: c).put("unit", kind.unit)
      } ?: JSONObject.NULL)
    }
    return out.put("ambient", readings)
  }

  private fun readOnce(sm: SensorManager, sensor: Sensor): Double? {
    val latch = CountDownLatch(1)
    var value: Double? = null
    val l = object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) {
        value = event.values.firstOrNull()?.toDouble()
        latch.countDown()
      }
      override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
    }
    sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    latch.await(2, TimeUnit.SECONDS)
    sm.unregisterListener(l)
    return value
  }

  private fun drawUrl(params: JSONObject): JSONObject {
    if (!MuseConfig.allowDisplay(context)) return error("showing pictures is turned off on this Portal")
    val url = params.getString("url")
    require(url.startsWith("http://") || url.startsWith("https://")) { "url must be http:// or https://" }
    val result = MuseDisplayActivity.showAndWait(context, MuseDisplayActivity.Content.Image(url, params.optString("caption")), seconds(params))
    return if (result == null) ok(JSONObject().put("shown", true)) else error(result)
  }

  private fun showText(params: JSONObject): JSONObject {
    if (!MuseConfig.allowDisplay(context)) return error("showing things on screen is turned off on this Portal")
    val result = MuseDisplayActivity.showAndWait(context, MuseDisplayActivity.Content.Text(params.optString("title"), params.getString("text")), seconds(params))
    return if (result == null) ok(JSONObject().put("shown", true)) else error(result)
  }

  private fun seconds(params: JSONObject) =
      (if (params.has("seconds")) params.optInt("seconds") else MuseConfig.imageSeconds(context)).coerceIn(5, 3600)

  private fun volume(params: JSONObject): JSONObject {
    val am = context.getSystemService(AudioManager::class.java) ?: return error("no audio service")
    if (params.has("volume")) setVolume(am, params.getInt("volume"))
    return ok(JSONObject().put("volume", volumePercent(am)))
  }

  private fun say(params: JSONObject): JSONObject {
    val text = params.getString("text")
    val err = MuseSpeech.speakAndWait(context, text, params.optString("language"))
    return if (err == null) ok(JSONObject().put("spoken", true)) else error(err)
  }

  private fun playUrl(params: JSONObject): JSONObject {
    val url = params.getString("url")
    require(url.startsWith("http://") || url.startsWith("https://")) { "url must be http:// or https://" }
    if (params.has("volume")) context.getSystemService(AudioManager::class.java)?.let { setVolume(it, params.getInt("volume")) }
    val err = MuseAudio.playAndWaitStart(context, url)
    return if (err == null) ok(JSONObject().put("playing", true)) else error(err)
  }

  private fun discover(params: JSONObject): JSONObject {
    val types = params.optJSONArray("types")?.let { a -> (0 until a.length()).map { a.getString(it) } }?.takeIf { it.isNotEmpty() }
        ?: MuseMdns.COMMON_TYPES
    val found = MuseMdns.browse(types, params.optLong("timeout_ms", 3000).coerceIn(1000, 10_000))
    return ok(JSONObject().put("services", JSONArray(found.map { it.toJson() })))
  }

  private fun cast(params: JSONObject, op: (MuseCast) -> JSONObject): JSONObject =
      MuseCast(params.getString("host"), params.optInt("port", 8009)).use { ok(op(it.connect())) }

  private fun castSay(params: JSONObject): JSONObject {
    val file = MuseSpeech.synthesizeToFile(context, params.getString("text"), params.optString("language"))
        ?: return error("this Portal has no text-to-speech voice installed")
    val url = MuseMedia.publish(context, file, "audio/wav") ?: return error("no LAN address to serve the speech from")
    return cast(params) { it.play(url, "audio/wav", "Muse", live = false) }
  }

  private fun radio(params: JSONObject): JSONObject {
    val found = MuseRadio.search(params.getString("query"))
    if (found.isEmpty()) return error("no station found for '${params.getString("query")}'")
    val st = found[params.optInt("pick", 0).coerceIn(0, found.size - 1)]
    val host = params.optString("host")
    val played = if (host.isNotEmpty()) {
      if (!MuseConfig.allowLan(context)) return error("home-network access is turned off on this Portal")
      MuseCast(host, params.optInt("port", 8009)).use { it.connect().play(st.url, MuseRadio.mime(st.codec), st.name, live = true) }
      "cast to $host"
    } else {
      MuseAudio.playAndWaitStart(context, st.url)?.let { return error(it) }
      "this Portal"
    }
    val alternatives = org.json.JSONArray(found.map { JSONObject().put("name", it.name).put("tags", it.tags).put("country", it.country) })
    return ok(JSONObject().put("playing", st.name).put("on", played).put("alternatives", alternatives))
  }

  private inline fun displayOnly(block: () -> JSONObject): JSONObject =
      if (!MuseConfig.allowDisplay(context)) error("showing things on screen is turned off on this Portal") else block()

  private inline fun appsOnly(block: () -> JSONObject): JSONObject =
      if (!MuseConfig.allowApps(context)) error("apps & media control is turned off on this Portal") else block()

  private inline fun smartHome(block: () -> JSONObject): JSONObject =
      if (!MuseConfig.allowSmartHome(context)) error("smart home control is turned off on this Portal") else block()

  private inline fun lanOnly(block: () -> JSONObject): JSONObject =
      if (!MuseConfig.allowLan(context)) error("home-network access is turned off on this Portal") else block()

  private fun screenSize(): Pair<Int, Int> {
    val dm = context.resources.displayMetrics
    return maxOf(dm.widthPixels, dm.heightPixels) to minOf(dm.widthPixels, dm.heightPixels)
  }

  companion object {
    private const val TAG = "MuseCommands"

    fun ok(payload: JSONObject) = JSONObject().put("ok", true).put("payload", payload)

    fun error(message: String) = JSONObject().put("ok", false).put("error", message)

    fun p(type: String, description: String) = JSONObject().put("type", type).put("description", description)

    fun spec(
        description: String,
        required: Map<String, JSONObject> = emptyMap(),
        optional: Map<String, JSONObject> = emptyMap(),
        timeoutMs: Int? = null,
    ): JSONObject {
      val o = JSONObject()
          .put("description", description)
          .put("required", JSONObject(required as Map<*, *>))
          .put("optional", JSONObject(optional as Map<*, *>))
      if (timeoutMs != null) o.put("timeout_ms", timeoutMs)
      return o
    }

    fun volumePercent(am: AudioManager): Int {
      val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
      return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    fun setVolume(am: AudioManager, percent: Int) {
      val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
      am.setStreamVolume(AudioManager.STREAM_MUSIC, (percent.coerceIn(0, 100) * max + 50) / 100, 0)
    }
  }
}

/**
 * What Muse did on this Portal: the last 80 commands, newest first, with a short, secret-free
 * summary (hosts and lengths, never message text or tokens). Shown in Muse settings so nothing
 * Muse does here is invisible.
 */
object MuseActionLog {
  data class Entry(val at: Long, val command: String, val ok: Boolean, val summary: String)

  private val entries = java.util.concurrent.ConcurrentLinkedDeque<Entry>()

  fun all(): List<Entry> = entries.toList()

  fun record(command: String, params: JSONObject, result: JSONObject) {
    val ok = result.optBoolean("ok")
    entries.addFirst(Entry(System.currentTimeMillis(), command, ok, if (ok) summarize(params) else result.optString("error").take(120)))
    while (entries.size > 80) entries.pollLast()
  }

  internal fun summarize(p: JSONObject): String {
    val parts = ArrayList<String>()
    p.optString("host").takeIf { it.isNotEmpty() }?.let { parts += it }
    p.optString("url").takeIf { it.isNotEmpty() }?.let { u -> runCatching { java.net.URL(u).host }.getOrNull()?.let { parts += it } }
    p.optString("action").takeIf { it.isNotEmpty() }?.let { parts += it }
    p.optString("entity_id").takeIf { it.isNotEmpty() }?.let { parts += it }
    p.optString("package").takeIf { it.isNotEmpty() }?.let { parts += it }
    if (p.has("volume")) parts += "volume ${p.opt("volume")}"
    p.optString("text").takeIf { it.isNotEmpty() }?.let { parts += "${it.length} chars" }
    p.optString("html").takeIf { it.isNotEmpty() }?.let { parts += "${it.length} B page" }
    return parts.joinToString(" · ")
  }
}

/** The Portal's text-to-speech engine, shared by Muse replies, speaker.say and cast.say. */
object MuseSpeech {
  private const val TAG = "MuseSpeech"
  @Volatile private var tts: TextToSpeech? = null
  @Volatile private var ready = false
  private val waiters = ConcurrentHashMap<String, CountDownLatch>()
  private val active = java.util.concurrent.atomic.AtomicInteger(0)

  /** True while an utterance is playing (Alfred's mouth moves; the wake word stops listening). */
  val isSpeaking: Boolean
    get() = active.get() > 0

  /** When the last word began (TTS range callbacks), for the avatar's mouth. */
  @Volatile var lastWordAt = 0L
    private set
  private val initLock = Any()

  /** Whether any TTS engine is installed (cheap; doesn't bind one). */
  fun available(context: Context): Boolean =
      context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()

  @Volatile private var defaultLocale: Locale? = null

  /** The shared engine, bound on first use. Blocks up to 10 s: never call on the main thread. */
  private fun engine(context: Context): TextToSpeech? {
    synchronized(initLock) {
      tts?.let { if (ready) return it }
      if (!available(context)) return null
      val latch = CountDownLatch(1)
      val attempt = java.util.concurrent.atomic.AtomicReference<TextToSpeech?>()
      val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
      var status = TextToSpeech.ERROR
      Handler(Looper.getMainLooper()).post {
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(context.applicationContext) { s ->
          status = s
          // An init that finishes after we gave up would otherwise leak a bound engine.
          if (abandoned.get()) engine.shutdown() else latch.countDown()
        }
        attempt.set(engine)
      }
      if (!latch.await(10, TimeUnit.SECONDS) || status != TextToSpeech.SUCCESS) {
        abandoned.set(true)
        Log.w(TAG, "TTS init failed ($status)")
        // Init already answered (failed, or raced the timeout): shut it down here. Otherwise its
        // own callback will, now that it's marked abandoned.
        if (latch.count == 0L) attempt.get()?.shutdown()
        return null
      }
      tts = attempt.get()
      defaultLocale = runCatching { tts!!.voice?.locale }.getOrNull() ?: Locale.getDefault()
      tts!!.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
      tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(id: String) {
          if (waiters.containsKey(id)) active.incrementAndGet()
        }
        override fun onDone(id: String) = finished(id)
        @Deprecated("Deprecated in Java") override fun onError(id: String) = finished(id)
        override fun onStop(id: String, interrupted: Boolean) = finished(id)
        override fun onRangeStart(id: String, start: Int, end: Int, frame: Int) {
          lastWordAt = System.currentTimeMillis()
        }
      })
      ready = true
      return tts
    }
  }

  private fun finished(id: String) {
    // Synthesis to a file (cast.say) never "starts" playback, so only count down what started.
    waiters.remove(id)?.let {
      if (active.get() > 0) active.decrementAndGet()
      it.countDown()
    }
  }

  /** A per-call language; without one, back to the engine's default (overrides never stick). */
  private fun applyLanguage(t: TextToSpeech, language: String?) {
    val locale = if (language.isNullOrBlank()) defaultLocale else Locale.forLanguageTag(language)
    if (locale != null) runCatching { t.language = locale }
  }

  /** Speaks [text]; returns null once done, or an error. Blocks (off the main thread). */
  fun speakAndWait(context: Context, text: String, language: String? = null, queue: Boolean = false): String? {
    val t = engine(context) ?: return "this Portal has no text-to-speech voice installed"
    applyLanguage(t, language)
    val id = UUID.randomUUID().toString()
    val latch = CountDownLatch(1)
    waiters[id] = latch
    val r = t.speak(text, if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, id)
    if (r != TextToSpeech.SUCCESS) return "text-to-speech failed"
    latch.await(5, TimeUnit.MINUTES)
    return null
  }

  fun stop() {
    tts?.stop()
    waiters.values.forEach { it.countDown() }
    waiters.clear()
    active.set(0)
  }

  /** Renders [text] to a WAV in the cache; null if there's no engine. */
  fun synthesizeToFile(context: Context, text: String, language: String? = null): File? {
    val t = engine(context) ?: return null
    applyLanguage(t, language)
    val dir = File(context.cacheDir, "muse-speech").apply { mkdirs() }
    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 15 * 60_000 }?.forEach { it.delete() }
    val file = File(dir, "${UUID.randomUUID()}.wav")
    val id = UUID.randomUUID().toString()
    val latch = CountDownLatch(1)
    waiters[id] = latch
    if (t.synthesizeToFile(text, null, file, id) != TextToSpeech.SUCCESS) return null
    latch.await(60, TimeUnit.SECONDS)
    return file.takeIf { it.length() > 44 }
  }
}

/** One MediaPlayer for audio Muse asks the Portal to play. */
object MuseAudio {
  private const val TAG = "MuseAudio"
  private val main = Handler(Looper.getMainLooper())
  @Volatile private var player: MediaPlayer? = null

  /** Starts [url]; returns null once playing, or an error. Blocks up to 25 s. */
  fun playAndWaitStart(context: Context, url: String): String? {
    val latch = CountDownLatch(1)
    var error: String? = "timed out starting playback"
    main.post {
      releaseInternal()
      val mp = MediaPlayer()
      player = mp
      try {
        mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        mp.setDataSource(url)
        mp.setOnPreparedListener {
          it.start()
          error = null
          latch.countDown()
        }
        mp.setOnErrorListener { _, what, extra ->
          error = "player error $what/$extra"
          latch.countDown()
          releaseInternal()
          true
        }
        mp.setOnCompletionListener { releaseInternal() }
        mp.prepareAsync()
      } catch (e: Exception) {
        error = "${e.javaClass.simpleName}: ${e.message}"
        latch.countDown()
        releaseInternal()
      }
    }
    if (!latch.await(25, TimeUnit.SECONDS)) {
      // Don't let a slow stream start playing after we've already reported failure.
      main.post { releaseInternal() }
    }
    return error
  }

  fun stop() = main.post { releaseInternal() }

  private fun releaseInternal() {
    val mp = player ?: return
    player = null
    runCatching { mp.stop() }
    runCatching { mp.release() }
    Log.i(TAG, "player released")
  }
}

/**
 * Short-lived files served to LAN devices (cast.say speech), at `/muse/media/<id>` on the fleet
 * agent's port without the bearer token: Cast speakers can't send one. Ids are 128-bit random
 * and expire after 15 minutes; the agent already refuses non-LAN peers.
 */
object MuseMedia {
  private const val TTL_MS = 15 * 60_000L
  private class Entry(val file: File, val type: String, val at: Long)
  private val entries = ConcurrentHashMap<String, Entry>()

  fun publish(context: Context, file: File, type: String): String? {
    val ip = localIp() ?: return null
    val now = System.currentTimeMillis()
    entries.entries.removeAll { now - it.value.at > TTL_MS }
    val id = UUID.randomUUID().toString().replace("-", "")
    entries[id] = Entry(file, type, now)
    return "http://$ip:${FleetConfig.port(context)}/muse/media/$id"
  }

  fun serve(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val id = req.path.removePrefix("/muse/media/")
    val e = entries[id]?.takeIf { System.currentTimeMillis() - it.at <= TTL_MS && it.file.exists() }
        ?: return FleetHttpServer.Response(404, "{\"ok\":false,\"error\":\"not_found\"}")
    return FleetHttpServer.Response.stream(200, e.type, e.file.length()) { out -> e.file.inputStream().use { it.copyTo(out) } }
  }

  private fun localIp(): String? =
      runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && isPrivateLan(it) }?.hostAddress
      }.getOrNull()
}
