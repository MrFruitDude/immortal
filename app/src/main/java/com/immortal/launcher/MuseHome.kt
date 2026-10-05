/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/*
 * The smart-home and media reach Alfred gets on top of the generic LAN bridge (MuseLan): first-class
 * commands for the things people actually ask for, so Muse doesn't have to re-derive each API.
 *  - Home Assistant (the whole smart home, if you run it): states and service calls.
 *  - Philips Hue: pairs with the bridge (press its button), then lights, rooms and scenes.
 *  - Music: internet radio by name or genre (radio-browser.info, no account), on the Portal or
 *    any Google Cast speaker / group.
 *  - Apps and media on the Portal: launch apps, open links, transport controls.
 */

/** Home Assistant over its REST API with a long-lived access token (Settings › Muse). */
object MuseHomeAssistant {
  fun configured(c: Context) = MuseConfig.haUrl(c).isNotEmpty() && MuseConfig.haToken(c).isNotEmpty()

  /** Entities, compact: optionally one domain (light, switch, climate, media_player, …) or a search. */
  fun states(c: Context, domain: String?, search: String?, limit: Int): JSONObject {
    val all = JSONArray(call(c, "GET", "/api/states", null))
    val out = JSONArray()
    for (i in 0 until all.length()) {
      val e = all.getJSONObject(i)
      val id = e.optString("entity_id")
      val attrs = e.optJSONObject("attributes") ?: JSONObject()
      val name = attrs.optString("friendly_name")
      if (!domain.isNullOrBlank() && !id.startsWith("$domain.")) continue
      if (!search.isNullOrBlank() && !(id.contains(search, true) || name.contains(search, true))) continue
      val keep = JSONObject().put("entity_id", id).put("state", e.optString("state")).put("name", name)
      for (k in listOf("brightness", "color_mode", "temperature", "current_temperature", "hvac_action", "unit_of_measurement",
          "media_title", "media_artist", "volume_level", "device_class")) if (attrs.has(k)) keep.put(k, attrs.get(k))
      out.put(keep)
      if (out.length() >= limit) break
    }
    return JSONObject().put("entities", out).put("total_matched", out.length())
  }

  fun callService(c: Context, domain: String, service: String, data: JSONObject): JSONObject {
    require(Regex("[a-z0-9_]+").matches(domain) && Regex("[a-z0-9_]+").matches(service)) { "bad domain/service" }
    val r = call(c, "POST", "/api/services/$domain/$service", data.toString())
    val changed = runCatching { JSONArray(r) }.getOrNull()
    return JSONObject().put("changed", changed?.length() ?: 0)
  }

  private fun call(c: Context, method: String, path: String, body: String?): String {
    val conn = URL(MuseConfig.haUrl(c).trimEnd('/') + path).openConnection() as HttpURLConnection
    try {
      conn.requestMethod = method
      conn.connectTimeout = 8_000
      conn.readTimeout = 20_000
      conn.setRequestProperty("Authorization", "Bearer ${MuseConfig.haToken(c)}")
      conn.setRequestProperty("Content-Type", "application/json")
      if (body != null) {
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
      }
      val code = conn.responseCode
      val text = (if (code >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.readText().orEmpty()
      if (code !in 200..299) throw IllegalStateException("Home Assistant answered HTTP $code: ${text.take(200)}")
      return text
    } finally {
      conn.disconnect()
    }
  }
}

/** Philips Hue bridge (local API v1): pairing by link button, lights, groups and scenes. */
object MuseHue {
  fun paired(c: Context) = MuseConfig.hueBridge(c).isNotEmpty() && MuseConfig.hueKey(c).isNotEmpty()

  /** Finds the bridge (mDNS) and asks it for a key; the bridge's button must have been pressed. */
  fun pair(c: Context, host: String?): JSONObject {
    val ip = host?.takeIf { it.isNotBlank() } ?: MuseMdns.browse(listOf("_hue._tcp"), 2500).firstOrNull()
        ?.addresses?.firstOrNull { !it.contains(':') } ?: throw IllegalStateException("no Hue bridge found on the network")
    resolvePrivate(ip)
    val r = JSONArray(http("POST", "http://$ip/api", JSONObject().put("devicetype", "immortal#alfred").toString()))
    val first = r.getJSONObject(0)
    first.optJSONObject("error")?.let {
      if (it.optInt("type") == 101) return JSONObject().put("paired", false).put("bridge", ip)
          .put("next", "Press the round link button on the Hue bridge, then run hue.pair again within 30 seconds.")
      throw IllegalStateException(it.optString("description"))
    }
    MuseConfig.setHue(c, ip, first.getJSONObject("success").getString("username"))
    return JSONObject().put("paired", true).put("bridge", ip)
  }

  fun lights(c: Context): JSONObject {
    val lights = JSONObject(api(c, "GET", "/lights", null))
    val groups = JSONObject(api(c, "GET", "/groups", null))
    val scenes = JSONObject(api(c, "GET", "/scenes", null))
    fun compactLights() = JSONArray().also { a ->
      lights.keys().forEach { id ->
        val l = lights.getJSONObject(id)
        val st = l.optJSONObject("state") ?: JSONObject()
        a.put(JSONObject().put("id", id).put("name", l.optString("name")).put("on", st.optBoolean("on"))
            .put("brightness", (st.optInt("bri") * 100 / 254)).put("reachable", st.optBoolean("reachable")))
      }
    }
    fun compactGroups() = JSONArray().also { a ->
      groups.keys().forEach { id ->
        val g = groups.getJSONObject(id)
        a.put(JSONObject().put("id", id).put("name", g.optString("name")).put("type", g.optString("type"))
            .put("any_on", g.optJSONObject("state")?.optBoolean("any_on")))
      }
    }
    fun compactScenes() = JSONArray().also { a ->
      scenes.keys().forEach { id ->
        val s = scenes.getJSONObject(id)
        a.put(JSONObject().put("id", id).put("name", s.optString("name")).put("group", s.optString("group")))
      }
    }
    return JSONObject().put("lights", compactLights()).put("rooms", compactGroups()).put("scenes", compactScenes())
  }

  /** Sets a light or a room (group): on/off, brightness 0–100, colour (#rrggbb), white 2000–6500 K. */
  fun set(c: Context, p: JSONObject): JSONObject {
    val body = JSONObject()
    if (p.has("on")) body.put("on", p.getBoolean("on"))
    if (p.has("brightness")) {
      val b = p.getInt("brightness").coerceIn(0, 100)
      if (b == 0) body.put("on", false) else body.put("on", true).put("bri", (b * 254 / 100).coerceAtLeast(1))
    }
    p.optString("color").takeIf { it.startsWith("#") && it.length == 7 }?.let { hex ->
      val (x, y) = xy(hex)
      body.put("xy", JSONArray().put(x).put(y)).put("on", true)
    }
    if (p.has("kelvin")) body.put("ct", (1_000_000 / p.getInt("kelvin").coerceIn(2000, 6500)).coerceIn(153, 500)).put("on", true)
    p.optString("scene").takeIf { it.isNotEmpty() }?.let { body.put("scene", it) }
    if (p.has("transition_ms")) body.put("transitiontime", p.getInt("transition_ms") / 100)
    val path = when {
      p.optString("light").isNotEmpty() -> "/lights/${resolve(c, "lights", p.getString("light"))}/state"
      p.optString("room").isNotEmpty() -> "/groups/${resolve(c, "groups", p.getString("room"))}/action"
      else -> "/groups/0/action" // every light
    }
    return JSONObject().put("result", JSONArray(api(c, "PUT", path, body.toString())))
  }

  /** Accepts an id or a (case-insensitive) name. */
  private fun resolve(c: Context, kind: String, key: String): String {
    if (key.all { it.isDigit() }) return key
    val all = JSONObject(api(c, "GET", "/$kind", null))
    all.keys().forEach { id -> if (all.getJSONObject(id).optString("name").equals(key, true)) return id }
    all.keys().forEach { id -> if (all.getJSONObject(id).optString("name").contains(key, true)) return id }
    throw IllegalArgumentException("no $kind named '$key'")
  }

  private fun api(c: Context, method: String, path: String, body: String?): String {
    resolvePrivate(MuseConfig.hueBridge(c))
    return http(method, "http://${MuseConfig.hueBridge(c)}/api/${MuseConfig.hueKey(c)}$path", body)
  }

  private fun http(method: String, url: String, body: String?): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
      conn.requestMethod = method
      conn.connectTimeout = 5_000
      conn.readTimeout = 10_000
      if (body != null) {
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
      }
      return conn.inputStream.bufferedReader().readText()
    } finally {
      conn.disconnect()
    }
  }

  /** sRGB hex → CIE xy (the Hue gamut maps it to the nearest colour it can show). */
  internal fun xy(hex: String): Pair<Double, Double> {
    fun lin(v: Int): Double {
      val c = v / 255.0
      return if (c > 0.04045) Math.pow((c + 0.055) / 1.055, 2.4) else c / 12.92
    }
    val r = lin(hex.substring(1, 3).toInt(16))
    val g = lin(hex.substring(3, 5).toInt(16))
    val b = lin(hex.substring(5, 7).toInt(16))
    val x = r * 0.664511 + g * 0.154324 + b * 0.162028
    val y = r * 0.283881 + g * 0.668433 + b * 0.047685
    val z = r * 0.000088 + g * 0.072310 + b * 0.986039
    val sum = x + y + z
    return if (sum == 0.0) 0.3127 to 0.3290 else (x / sum) to (y / sum)
  }
}

/** Internet radio via radio-browser.info (community directory, no account or key). */
object MuseRadio {
  data class Station(val name: String, val url: String, val codec: String, val tags: String, val country: String)

  fun search(query: String, limit: Int = 8): List<Station> {
    val q = URLEncoder.encode(query, "UTF-8")
    val byName = fetch("https://all.api.radio-browser.info/json/stations/search?name=$q&limit=$limit&hidebroken=true&order=clickcount&reverse=true")
    val list = if (byName.isNotEmpty()) byName
        else fetch("https://all.api.radio-browser.info/json/stations/search?tag=$q&limit=$limit&hidebroken=true&order=clickcount&reverse=true")
    return list
  }

  private fun fetch(url: String): List<Station> {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
      conn.connectTimeout = 8_000
      conn.readTimeout = 12_000
      conn.setRequestProperty("User-Agent", MuseApi.userAgent())
      val arr = JSONArray(conn.inputStream.bufferedReader().readText())
      return (0 until arr.length()).map { arr.getJSONObject(it) }
          .filter { it.optString("url_resolved").startsWith("http") }
          .map { Station(it.optString("name").trim(), it.getString("url_resolved"), it.optString("codec"), it.optString("tags"), it.optString("countrycode")) }
    } finally {
      conn.disconnect()
    }
  }

  fun mime(codec: String) = when (codec.uppercase()) {
    "AAC", "AAC+" -> "audio/aac"
    "OGG" -> "audio/ogg"
    "FLAC" -> "audio/flac"
    else -> "audio/mpeg"
  }
}

/** Apps and media on the Portal itself. */
object MuseApps {
  fun list(c: Context): JSONObject {
    val pm = c.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val apps = JSONArray()
    pm.queryIntentActivities(launcher, 0).sortedBy { it.loadLabel(pm).toString().lowercase() }.forEach {
      apps.put(JSONObject().put("name", it.loadLabel(pm).toString()).put("package", it.activityInfo.packageName))
    }
    return JSONObject().put("apps", apps)
  }

  /** Opens an app by package or (fuzzy) name. */
  fun launch(c: Context, pkgOrName: String): JSONObject {
    val pm = c.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val match = pm.queryIntentActivities(launcher, 0).let { all ->
      all.firstOrNull { it.activityInfo.packageName == pkgOrName }
          ?: all.firstOrNull { it.loadLabel(pm).toString().equals(pkgOrName, true) }
          ?: all.firstOrNull { it.loadLabel(pm).toString().contains(pkgOrName, true) }
    } ?: throw IllegalArgumentException("no app called '$pkgOrName' on this Portal")
    DreamPolicy.userExitAt = System.currentTimeMillis()
    ScreenControl.wake(c)
    c.startActivity(pm.getLaunchIntentForPackage(match.activityInfo.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return JSONObject().put("opened", match.loadLabel(pm).toString())
  }

  /** Opens a link in whatever handles it (a YouTube link in SmartTube, a web page in the browser). */
  fun open(c: Context, url: String): JSONObject {
    val u = Uri.parse(url)
    require(u.scheme?.lowercase() in setOf("http", "https")) { "only http(s) links" }
    DreamPolicy.userExitAt = System.currentTimeMillis()
    ScreenControl.wake(c)
    c.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return JSONObject().put("opened", url)
  }
}

/**
 * Music Assistant: Spotify and the rest of your library, played on any room — Google Homes and
 * speaker groups, the Portals (Snapcast) or sync groups of them. Uses the same server settings as
 * Immortal's multi-room audio (address, API port, optional login).
 */
object MuseMusic {
  private const val DEVICE = "Immortal Alfred"

  fun configured(c: Context) = ImmortalSettings.snapcastHost(c).isNotBlank()

  /** Every player Music Assistant knows: speakers, groups, Portals. */
  fun players(c: Context): JSONObject {
    val arr = call(c, "players/all", null).optJSONArray("result") ?: JSONArray()
    val out = JSONArray()
    for (i in 0 until arr.length()) {
      val p = arr.getJSONObject(i)
      if (!p.optBoolean("available", true) || p.optBoolean("hidden", false)) continue
      val cm = p.optJSONObject("current_media")
      out.put(JSONObject()
          .put("id", p.optString("player_id"))
          .put("name", p.optString("display_name").ifEmpty { p.optString("name") })
          .put("type", p.optString("type"))
          .put("provider", p.optString("provider"))
          .put("state", p.optString("state"))
          .put("volume", p.opt("volume_level"))
          .put("group_members", p.optJSONArray("group_childs") ?: JSONArray())
          .put("now_playing", cm?.let { JSONObject().put("title", it.optString("title")).put("artist", it.optString("artist")) } ?: JSONObject.NULL))
    }
    return JSONObject().put("players", out)
  }

  /** Search the library and Spotify; returns compact items with their `uri`. */
  fun search(c: Context, query: String, types: List<String>, limit: Int): JSONObject {
    val args = JSONObject().put("search_query", query).put("limit", limit)
    if (types.isNotEmpty()) args.put("media_types", JSONArray(types))
    val r = call(c, "music/search", args).optJSONObject("result") ?: JSONObject()
    val out = JSONObject()
    for (k in listOf("tracks", "albums", "artists", "playlists", "radio", "podcasts", "audiobooks")) {
      val items = r.optJSONArray(k) ?: continue
      out.put(k, JSONArray().also { a ->
        for (i in 0 until minOf(items.length(), limit)) {
          val it = items.getJSONObject(i)
          val artists = it.optJSONArray("artists")?.let { ar -> (0 until ar.length()).joinToString { n -> ar.getJSONObject(n).optString("name") } }
          a.put(JSONObject().put("name", it.optString("name")).put("uri", it.optString("uri"))
              .put("artist", artists ?: it.optString("owner")).put("provider", it.optString("provider")))
        }
      })
    }
    return out
  }

  /**
   * Plays [uri] — or the best match for [query] — on [player] (name or id). `enqueue`: play
   * (default), replace, next or add. `radio` keeps similar music going after it.
   */
  fun play(c: Context, p: JSONObject): JSONObject {
    val player = resolvePlayer(c, p.getString("player"))
    val uri = p.optString("uri").ifEmpty {
      val query = p.optString("query").ifEmpty { throw IllegalArgumentException("give a uri or a query") }
      val type = p.optString("type").ifEmpty { null }
      val found = search(c, query, listOfNotNull(type), 5)
      val order = if (type != null) listOf(type + "s", type) else listOf("playlists", "albums", "tracks", "artists", "radio")
      order.firstNotNullOfOrNull { k -> found.optJSONArray(k)?.optJSONObject(0)?.optString("uri")?.takeIf { it.isNotEmpty() } }
          ?: throw IllegalStateException("nothing found for '$query'")
    }
    val args = JSONObject().put("queue_id", player.first).put("media", uri).put("option", p.optString("enqueue", "play"))
    if (p.optBoolean("radio", false)) args.put("radio_mode", true)
    call(c, "player_queues/play_media", args)
    return JSONObject().put("playing", uri).put("on", player.second)
  }

  /** play / pause / stop / next / previous, and/or volume 0–100, on a player. */
  fun control(c: Context, p: JSONObject): JSONObject {
    val (id, name) = resolvePlayer(c, p.getString("player"))
    p.optString("action").takeIf { it.isNotEmpty() }?.let { a ->
      val cmd = when (a) {
        "play", "pause", "stop", "next", "previous" -> a
        "play_pause", "toggle" -> "play_pause"
        else -> throw IllegalArgumentException("action must be play, pause, play_pause, stop, next or previous")
      }
      call(c, "players/cmd/$cmd", JSONObject().put("player_id", id))
    }
    if (p.has("volume")) call(c, "players/cmd/volume_set", JSONObject().put("player_id", id).put("volume_level", p.getInt("volume").coerceIn(0, 100)))
    return JSONObject().put("player", name)
  }

  /** (player_id, display name) for a name or id; fuzzy on names. */
  private fun resolvePlayer(c: Context, key: String): Pair<String, String> {
    val arr = players(c).getJSONArray("players")
    val all = (0 until arr.length()).map { arr.getJSONObject(it) }
    val hit = all.firstOrNull { it.optString("id") == key }
        ?: all.firstOrNull { it.optString("name").equals(key, true) }
        ?: all.firstOrNull { it.optString("name").contains(key, true) }
        ?: throw IllegalArgumentException("no Music Assistant player called '$key' (try music.players)")
    return hit.getString("id") to hit.getString("name")
  }

  /** One request on a fresh socket (auth first when a login is configured); returns the reply. */
  internal fun call(c: Context, command: String, args: JSONObject?): JSONObject {
    val ws = MaWebSocket(ImmortalSettings.snapcastHost(c), ImmortalSettings.maPort(c))
    if (!ws.connect(8000)) throw IllegalStateException("can't reach Music Assistant at ${ImmortalSettings.snapcastHost(c)}")
    try {
      ws.readText() // server hello
      val user = ImmortalSettings.maUser(c)
      val pass = ImmortalSettings.maPass(c)
      if (user.isNotBlank() && pass.isNotBlank()) {
        val login = request(ws, "auth/login", JSONObject().put("username", user).put("password", pass).put("device_name", DEVICE))
        val tok = login.optJSONObject("result")?.let { it.optString("access_token").ifEmpty { it.optString("token") } }
        if (tok.isNullOrEmpty()) throw IllegalStateException("Music Assistant login failed")
        request(ws, "auth", JSONObject().put("token", tok).put("device_name", DEVICE))
      }
      return request(ws, command, args)
    } finally {
      ws.close()
    }
  }

  private fun request(ws: MaWebSocket, command: String, args: JSONObject?): JSONObject {
    val id = java.util.UUID.randomUUID().toString()
    val o = JSONObject().put("command", command).put("message_id", id)
    if (args != null) o.put("args", args)
    ws.sendText(o.toString())
    repeat(200) {
      val t = ws.readText() ?: throw IllegalStateException("Music Assistant closed the connection")
      val m = runCatching { JSONObject(t) }.getOrNull() ?: return@repeat
      if (m.optString("message_id") != id) return@repeat
      if (m.has("error_code")) throw IllegalStateException("Music Assistant: ${m.optString("details").ifEmpty { m.optString("error_code") }}")
      return m
    }
    throw IllegalStateException("no answer from Music Assistant to $command")
  }
}
