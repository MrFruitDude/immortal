/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.util.Log
import com.immortal.launcher.settings.SettingsDomains
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/*
 * "Connect your smart home" (SmartHomeConnectActivity): finds Home Assistant and the Hue bridge
 * on the LAN and connects them without copy-pasting URLs or tokens. The result lands in the same
 * MuseConfig fields Settings › Muse uses, so Alfred and the dashboard pick it up.
 *
 * Home Assistant: HA's own OAuth2 / IndieAuth login runs on the Portal in a WebView. The client
 * id and redirect URI share one scheme + host, which is the case where HA accepts the redirect
 * without fetching the client id page (homeassistant/components/auth/indieauth.py,
 * verify_redirect_uri). The short-lived access token from the code exchange is used once, over
 * the WebSocket API, to mint a long-lived token named after this Portal; the temporary refresh
 * token is then revoked.
 *
 * Tokens are secrets: nothing here logs them, the callback URL (it carries the code) or a token
 * response body.
 */

/** HA's login flow, pure parts (no android.*: unit-tested on the JVM). */
object HaAuth {
  /** IndieAuth client id: a URL on a host we own the meaning of (it never resolves). */
  const val CLIENT_ID = "http://immortal.portal/"

  /** Same scheme + host as [CLIENT_ID], so HA validates it locally. Intercepted, never loaded. */
  const val REDIRECT_URI = "http://immortal.portal/auth_callback"

  const val DEFAULT_PORT = 8123

  fun newState(random: SecureRandom = SecureRandom()): String =
      ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 0xff) }

  /**
   * What the user typed (or discovery found) → `scheme://host[:port]`, or null if unusable.
   * A bare host gets http:// and HA's default port 8123; a path, query or fragment is dropped.
   */
  fun normalizeBaseUrl(input: String): String? {
    val t = input.trim().trimEnd('/')
    if (t.isEmpty()) return null
    val hasScheme = t.contains("://")
    val uri = runCatching { URI(if (hasScheme) t else "http://$t") }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
    if (uri.userInfo != null) return null
    val port = when {
      uri.port > 0 -> uri.port
      !hasScheme -> DEFAULT_PORT
      else -> -1
    }
    val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    return "$scheme://$h" + (if (port > 0) ":$port" else "")
  }

  fun authorizeUrl(base: String, state: String): String =
      base.trimEnd('/') + "/auth/authorize?response_type=code" +
          "&client_id=" + enc(CLIENT_ID) +
          "&redirect_uri=" + enc(REDIRECT_URI) +
          "&state=" + enc(state)

  /** Is this navigation the login handing back to us (and so must never be loaded)? */
  fun isCallback(url: String?): Boolean {
    if (url == null) return false
    val u = runCatching { URI(url) }.getOrNull() ?: return false
    val r = URI(REDIRECT_URI)
    return u.scheme.equals(r.scheme, true) && u.host.equals(r.host, true) && (u.path ?: "").trimEnd('/') == r.path
  }

  sealed class Callback {
    data class Code(val code: String) : Callback()

    data class Failed(val reason: String) : Callback()

    object NotOurs : Callback()
  }

  /** Parses the redirect HA sends the browser to; the state must be the one we sent. */
  fun parseCallback(url: String, expectedState: String): Callback {
    if (!isCallback(url)) return Callback.NotOurs
    val q = query(URI(url).rawQuery)
    if (q["state"] != expectedState) return Callback.Failed("The login answer didn't match this request. Try again.")
    q["error"]?.let { return Callback.Failed("Home Assistant said: " + (q["error_description"] ?: it)) }
    val code = q["code"]?.takeIf { it.isNotEmpty() } ?: return Callback.Failed("Home Assistant didn't send a login code.")
    return Callback.Code(code)
  }

  /** `POST /auth/token` body (form-encoded, as HA requires). */
  fun tokenRequestBody(code: String): String =
      "grant_type=authorization_code&code=" + enc(code) + "&client_id=" + enc(CLIENT_ID)

  data class Tokens(val access: String, val refresh: String?)

  /** Null if the body isn't a token response. Never put [body] into a message: it's a secret. */
  fun parseTokenResponse(body: String): Tokens? {
    val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val access = o.optString("access_token").takeIf { it.isNotEmpty() } ?: return null
    return Tokens(access, o.optString("refresh_token").takeIf { it.isNotEmpty() })
  }

  /** The long-lived token's name in HA (profile › Security). HA wants names unique per user. */
  fun tokenName(deviceName: String, suffix: String? = null): String {
    val base = "Immortal – " + deviceName.trim().ifEmpty { "Portal" }
    return if (suffix.isNullOrBlank()) base else "$base ($suffix)"
  }

  internal fun query(raw: String?): Map<String, String> {
    if (raw.isNullOrEmpty()) return emptyMap()
    val out = linkedMapOf<String, String>()
    raw.split('&').forEach { kv ->
      if (kv.isEmpty()) return@forEach
      val k = URLDecoder.decode(kv.substringBefore('='), "UTF-8")
      val v = URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
      if (k !in out) out[k] = v
    }
    return out
  }

  private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/** A Home Assistant found on the network. */
data class HaInstance(val name: String, val url: String, val version: String = "", val uuid: String = "")

/** `_home-assistant._tcp` services → [HaInstance]s with a URL the Portal can actually reach. */
object HaDiscovery {
  const val SERVICE = "_home-assistant._tcp"

  fun fromMdns(services: List<MuseMdns.Service>): List<HaInstance> =
      services
          .mapNotNull { s ->
            val url = baseUrlFor(s) ?: return@mapNotNull null
            HaInstance(
                name = s.txt["location_name"]?.takeIf { it.isNotBlank() } ?: s.instance.ifBlank { "Home Assistant" },
                url = url,
                version = s.txt["version"].orEmpty(),
                uuid = s.txt["uuid"].orEmpty())
          }
          .distinctBy { it.uuid.ifEmpty { it.url } }

  /**
   * Android 9/10 can't resolve `.local` names (no mDNS in the system resolver or the WebView), so
   * a `.local` host is swapped for the IPv4 address the mDNS answer carried. HA's TXT
   * `internal_url` (else `base_url`) is preferred for its scheme and port; failing that,
   * http://<IPv4>:<SRV port>.
   */
  internal fun baseUrlFor(s: MuseMdns.Service): String? {
    val ipv4 = s.addresses.firstOrNull { IPV4.matches(it) }
    val txtUrl = listOf("internal_url", "base_url").firstNotNullOfOrNull { k -> s.txt[k]?.takeIf { it.isNotBlank() } }
    txtUrl?.let { HaAuth.normalizeBaseUrl(it) }?.let { norm ->
      val u = URI(norm)
      val host = u.host.orEmpty()
      return when {
        !host.endsWith(".local", true) -> norm // an IP or a real DNS name: use as published
        ipv4 != null -> "${u.scheme}://$ipv4" + (if (u.port > 0) ":${u.port}" else if (s.port > 0) ":${s.port}" else "")
        else -> null
      }
    }
    val host = ipv4 ?: s.host.trimEnd('.').takeIf { it.isNotEmpty() && !it.endsWith(".local", true) } ?: return null
    return "http://$host:${if (s.port > 0) s.port else HaAuth.DEFAULT_PORT}"
  }

  private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
}

/** What a freshly connected Home Assistant has that Immortal can use. */
data class HaSummary(
    val thermostats: Int,
    val lights: Int,
    /** Entities that look like they come from the Hilo integration (0 = none found). */
    val hiloEntities: Int,
    /** Hilo thermostats, when the entity registry was readable (null = unknown). */
    val hiloThermostats: Int?,
    /** False when the device list couldn't be read: the counts are unknown, not zero. */
    val surveyed: Boolean = true,
) {
  val hasHilo: Boolean
    get() = hiloEntities > 0

  companion object {
    /** Connected, but the device list couldn't be read. */
    val UNKNOWN = HaSummary(0, 0, 0, null, surveyed = false)

    /**
     * [states] is HA's raw state list (`/api/states` or WS `get_states`), [registry] the entity
     * registry (WS `config/entity_registry/list`) if it could be read. An entity counts as Hilo
     * when the registry says `platform: hilo`, or its id, name or attributes mention Hilo (the
     * Hilo integration's gateway, rate and challenge sensors all do; its thermostats are named
     * after the room, which is why the registry is asked too).
     */
    fun of(states: JSONArray, registry: JSONArray? = null): HaSummary {
      val hiloIds = linkedSetOf<String>()
      registry?.let { r ->
        for (i in 0 until r.length()) {
          val e = r.optJSONObject(i) ?: continue
          if (e.optString("platform").equals("hilo", true)) hiloIds += e.optString("entity_id")
        }
      }
      var thermostats = 0
      var lights = 0
      for (i in 0 until states.length()) {
        val e = states.optJSONObject(i) ?: continue
        val id = e.optString("entity_id")
        if (id.startsWith("climate.")) thermostats++
        if (id.startsWith("light.")) lights++
        if (mentionsHilo(e)) hiloIds += id
      }
      hiloIds.remove("")
      return HaSummary(
          thermostats = thermostats,
          lights = lights,
          hiloEntities = hiloIds.size,
          hiloThermostats = registry?.let { hiloIds.count { it.startsWith("climate.") } })
    }

    internal fun mentionsHilo(entity: JSONObject): Boolean {
      if (entity.optString("entity_id").contains("hilo", true)) return true
      val attrs = entity.optJSONObject("attributes") ?: return false
      return attrs.keys().asSequence().any { k -> k.contains("hilo", true) || attrs.opt(k).toString().contains("hilo", true) }
    }
  }
}

/** A Hue bridge found on the network. */
data class HueBridge(val name: String, val ip: String, val id: String = "")

object HueDiscovery {
  const val SERVICE = "_hue._tcp"
  const val CLOUD_URL = "https://discovery.meethue.com/"

  fun fromMdns(services: List<MuseMdns.Service>): List<HueBridge> =
      services
          .mapNotNull { s ->
            val ip = s.addresses.firstOrNull { isPrivateIpv4(it) } ?: return@mapNotNull null
            HueBridge(s.instance.ifBlank { "Hue Bridge" }, ip, s.txt["bridgeid"].orEmpty())
          }
          .distinctBy { it.ip }

  /** discovery.meethue.com's `[{"id":…,"internalipaddress":…,"port":443}]`; private IPv4 only. */
  fun parseCloud(body: String): List<HueBridge> {
    val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
    return (0 until arr.length())
        .mapNotNull { arr.optJSONObject(it) }
        .mapNotNull { o ->
          val ip = o.optString("internalipaddress").takeIf { isPrivateIpv4(it) } ?: return@mapNotNull null
          val id = o.optString("id")
          HueBridge(if (id.length >= 6) "Hue Bridge ${id.takeLast(6).uppercase()}" else "Hue Bridge", ip, id)
        }
        .distinctBy { it.ip }
  }

  internal fun isPrivateIpv4(s: String): Boolean {
    if (!Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(s)) return false
    if (s.split('.').any { it.toInt() > 255 }) return false
    return isPrivateLan(InetAddress.getByName(s)) // a literal: no DNS lookup
  }
}

/** The network half: discovery, the code exchange, minting the token, and saving the result. */
object SmartHomeConnect {
  private const val TAG = "ImmortalSmartHome"

  /** One line for the Settings › Muse nav row. */
  fun statusLabel(c: Context): String {
    val parts = listOfNotNull(
        "Home Assistant".takeIf { MuseHomeAssistant.configured(c) }, "Hue".takeIf { MuseHue.paired(c) })
    return if (parts.isEmpty()) "Not connected" else parts.joinToString(" · ")
  }

  // --- Home Assistant -----------------------------------------------------------------------

  fun discoverHa(timeoutMs: Long = 3000): List<HaInstance> =
      runCatching { HaDiscovery.fromMdns(MuseMdns.browse(listOf(HaDiscovery.SERVICE), timeoutMs)) }
          .onFailure { Log.w(TAG, "HA discovery: ${it.javaClass.simpleName}") }
          .getOrDefault(emptyList())

  /**
   * After the WebView caught the code: exchange it, mint a long-lived token, read what's there,
   * save URL + token through the settings registry (so Muse re-registers its commands), and
   * revoke the temporary login. Blocking; call off the main thread. Throws with a user-facing
   * message (never containing a token).
   */
  fun finishHa(c: Context, base: String, code: String): HaSummary {
    val tokens = exchangeCode(base, code)
    try {
      val (longLived, summary) = mintAndSurvey(base, tokens.access, FleetConfig.name(c))
      SettingsDomains.muse.apply(c, JSONObject().put("haUrl", base).put("haToken", longLived))
      return summary
    } finally {
      tokens.refresh?.let { revoke(base, it) }
    }
  }

  fun disconnectHa(c: Context) {
    // haToken is a secret spec: a blank apply means "leave it", so it's cleared here; clearing
    // the URL through the registry fires the Muse domain's hook (Muse drops the ha.* commands).
    MuseConfig.setHaToken(c, "")
    SettingsDomains.muse.apply(c, JSONObject().put("haUrl", ""))
  }

  private fun exchangeCode(base: String, code: String): HaAuth.Tokens {
    val conn = URL(base.trimEnd('/') + "/auth/token").openConnection() as HttpURLConnection
    try {
      conn.requestMethod = "POST"
      conn.connectTimeout = 8_000
      conn.readTimeout = 15_000
      conn.doOutput = true
      conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
      conn.outputStream.use { it.write(HaAuth.tokenRequestBody(code).toByteArray()) }
      val status = conn.responseCode
      val body = (if (status >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.readText().orEmpty()
      if (status !in 200..299) throw IllegalStateException("Home Assistant refused the login (HTTP $status). Try again.")
      return HaAuth.parseTokenResponse(body) ?: throw IllegalStateException("Home Assistant sent an unexpected answer.")
    } finally {
      conn.disconnect()
    }
  }

  /** One WebSocket session: auth, mint the long-lived token, then states + entity registry. */
  private fun mintAndSurvey(base: String, access: String, deviceName: String): Pair<String, HaSummary> {
    val u = URI(base)
    val secure = u.scheme.equals("https", true)
    val port = if (u.port > 0) u.port else if (secure) 443 else 80
    val ws = MaWebSocket(u.host.trim('[', ']'), port, "/api/websocket", secure)
    val ok = runCatching { ws.connect(10_000) }.getOrDefault(false)
    if (!ok) {
      ws.close()
      throw IllegalStateException("Couldn't open Home Assistant's WebSocket API.")
    }
    try {
      val hello = JSONObject(ws.readText() ?: throw IllegalStateException("Home Assistant closed the connection."))
      if (hello.optString("type") != "auth_required") throw IllegalStateException("Unexpected Home Assistant greeting.")
      ws.sendText(JSONObject().put("type", "auth").put("access_token", access).toString())
      val auth = JSONObject(ws.readText() ?: throw IllegalStateException("Home Assistant closed the connection."))
      if (auth.optString("type") != "auth_ok") throw IllegalStateException("Home Assistant didn't accept the login.")

      var nextId = 1
      fun request(msg: JSONObject): JSONObject {
        val id = nextId++
        ws.sendText(msg.put("id", id).toString())
        repeat(500) {
          val t = ws.readText() ?: throw IllegalStateException("Home Assistant closed the connection.")
          val m = runCatching { JSONObject(t) }.getOrNull() ?: return@repeat
          if (m.optInt("id", -1) == id && m.optString("type") == "result") return m
        }
        throw IllegalStateException("No answer from Home Assistant.")
      }

      fun mint(name: String) =
          request(JSONObject().put("type", "auth/long_lived_access_token").put("client_name", name).put("lifespan", 3650))

      // HA refuses a second long-lived token with the same name, e.g. when reconnecting.
      var r = mint(HaAuth.tokenName(deviceName))
      if (!r.optBoolean("success")) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        r = mint(HaAuth.tokenName(deviceName, stamp))
      }
      val longLived = r.optString("result").takeIf { r.optBoolean("success") && it.isNotEmpty() }
          ?: throw IllegalStateException(
              "Home Assistant wouldn't create a token: " + (r.optJSONObject("error")?.optString("message") ?: "unknown error"))

      val states = runCatching { request(JSONObject().put("type", "get_states")).optJSONArray("result") }.getOrNull()
          ?: return longLived to HaSummary.UNKNOWN
      // Admin-only on some HA versions; the summary falls back to name/attribute hints.
      val registry = runCatching {
        request(JSONObject().put("type", "config/entity_registry/list")).takeIf { it.optBoolean("success") }?.optJSONArray("result")
      }.getOrNull()
      return longLived to HaSummary.of(states, registry)
    } finally {
      ws.close()
    }
  }

  /** Best effort: drop the login's refresh token so HA's security page only lists the named one. */
  private fun revoke(base: String, refresh: String) {
    runCatching {
      val conn = URL(base.trimEnd('/') + "/auth/revoke").openConnection() as HttpURLConnection
      try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 5_000
        conn.readTimeout = 8_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(("token=" + URLEncoder.encode(refresh, "UTF-8")).toByteArray()) }
        conn.responseCode
      } finally {
        conn.disconnect()
      }
    }.onFailure { Log.w(TAG, "revoke temporary HA login: ${it.javaClass.simpleName}") }
  }

  // --- Hue ----------------------------------------------------------------------------------

  /** mDNS first; the Hue cloud directory (rate-limited) only when asked and mDNS found nothing. */
  fun discoverHue(useCloud: Boolean, timeoutMs: Long = 3000): List<HueBridge> {
    val local = runCatching { HueDiscovery.fromMdns(MuseMdns.browse(listOf(HueDiscovery.SERVICE), timeoutMs)) }
        .onFailure { Log.w(TAG, "Hue discovery: ${it.javaClass.simpleName}") }
        .getOrDefault(emptyList())
    if (local.isNotEmpty() || !useCloud) return local
    return runCatching {
      val conn = URL(HueDiscovery.CLOUD_URL).openConnection() as HttpURLConnection
      try {
        conn.connectTimeout = 6_000
        conn.readTimeout = 8_000
        HueDiscovery.parseCloud(conn.inputStream.bufferedReader().readText())
      } finally {
        conn.disconnect()
      }
    }.getOrDefault(emptyList())
  }

  data class HueSummary(val rooms: Int, val scenes: Int, val lights: Int)

  /** One pairing attempt: true once the bridge's button was pressed and it handed out a key. */
  fun tryPairHue(c: Context, ip: String): Boolean {
    val paired = MuseHue.pair(c, ip).optBoolean("paired")
    if (paired) MuseService.reconnect(c) // Muse re-registers with hue.lights / hue.set
    return paired
  }

  fun hueSummary(c: Context): HueSummary {
    val all = MuseHue.lights(c)
    val rooms = all.getJSONArray("rooms")
    return HueSummary(
        rooms = (0 until rooms.length()).count { rooms.getJSONObject(it).optString("type") == "Room" },
        scenes = all.getJSONArray("scenes").length(),
        lights = all.getJSONArray("lights").length())
  }

  fun disconnectHue(c: Context) {
    MuseConfig.setHue(c, "", "")
    MuseService.reconnect(c)
  }
}
