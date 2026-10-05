/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import java.security.SecureRandom
import org.json.JSONObject

/**
 * The gadget identity Muse knows this Portal by. A random, locally administered MAC-shaped value
 * generated once and kept across unpairing (never the real Wi-Fi MAC). The node id and BLE name
 * end in the same six hex digits with no separator, which is how the Muse app matches them.
 */
data class MuseIdentity(val mac: String) {
  val suffix: String
    get() = mac.replace(":", "").takeLast(6)

  val nodeId: String
    get() = "homelink-$suffix"

  val deviceId: String
    get() = "hatch-link:$mac"

  val bleName: String
    get() = "MuseGadget${suffix.uppercase()}"

  companion object {
    private val MAC_RE = Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")

    fun isValid(mac: String?) = mac != null && MAC_RE.matches(mac)

    fun generate(random: (Int) -> ByteArray = { n -> ByteArray(n).also { SecureRandom().nextBytes(it) } }): MuseIdentity {
      val o = random(6)
      o[0] = ((o[0].toInt() and 0xFC) or 0x02).toByte() // unicast, locally administered
      return MuseIdentity(o.joinToString(":") { "%02x".format(it.toInt() and 0xff) })
    }
  }
}

/** The device tokens the Muse app handed over at pairing, plus where to use them. */
data class MusePairingRecord(
    val accessToken: String,
    val refreshToken: String,
    val username: String,
    val apiUrlV2: String,
    val noiseHost: String,
    val savedAtSec: Long,
) {
  fun toJson(): JSONObject =
      JSONObject()
          .put("access_token", accessToken)
          .put("refresh_token", refreshToken)
          .put("token_type", "device")
          .put("username", username)
          .put("api_url_v2", apiUrlV2)
          .put("noise_host", noiseHost)
          .put("access_token_saved_at", savedAtSec)

  companion object {
    fun fromJson(o: JSONObject): MusePairingRecord? {
      val access = o.optString("access_token")
      val refresh = o.optString("refresh_token")
      if (access.isEmpty() || refresh.isEmpty()) return null
      return MusePairingRecord(access, refresh, o.optString("username"), o.optString("api_url_v2"),
          o.optString("noise_host"), o.optLong("access_token_saved_at"))
    }
  }
}

/**
 * Config for [MuseService], the Muse gadget. Mirrors the [MqttConfig] prefs idiom. Off by
 * default: an un-paired, disabled Portal never opens Bluetooth or a connection.
 *
 * The SDK token and device tokens are secrets: they never leave this prefs file except in the
 * Muse protocol itself (the fleet `/muse` route reports only whether they're set).
 */
object MuseConfig {
  private const val PREFS = "muse_gadget"
  const val SDK_TOKEN_PATTERN = "mgst_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]"
  private val SDK_TOKEN_RE = Regex(SDK_TOKEN_PATTERN)
  const val DEFAULT_IMAGE_SECONDS = 120
  const val PAIRING_WINDOW_MS = 10 * 60 * 1000L

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** Immutable snapshot for the settings registry (the on-device list recomposes from it). */
  data class Settings(
      val enabled: Boolean = false,
      val speakReplies: Boolean = true,
      val allowDisplay: Boolean = true,
      val imageSeconds: Int = DEFAULT_IMAGE_SECONDS,
      val allowLan: Boolean = true,
      val heyButton: Boolean = true,
      val sdkToken: String = "",
      val wakeWord: Boolean = false,
      val wakeOnlyWhenPresent: Boolean = true,
      val allowApps: Boolean = true,
      val allowSmartHome: Boolean = true,
      val haUrl: String = "",
      val haToken: String = "",
  )

  fun load(c: Context) =
      Settings(isEnabled(c), speakReplies(c), allowDisplay(c), imageSeconds(c), allowLan(c), heyButton(c), sdkToken(c).orEmpty(),
          wakeWord(c), wakeOnlyWhenPresent(c), allowApps(c), allowSmartHome(c), haUrl(c), haToken(c))

  fun isEnabled(c: Context): Boolean = prefs(c).getBoolean("enabled", false)

  fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("enabled", on).apply()

  /** Speak Muse's replies with the Portal's text-to-speech engine (else captions only). */
  fun speakReplies(c: Context): Boolean = prefs(c).getBoolean("speak_replies", true)

  fun setSpeakReplies(c: Context, on: Boolean) = prefs(c).edit().putBoolean("speak_replies", on).apply()

  /** Let Muse put pictures on the screen (display.draw_url). Off = the command reports refusal. */
  fun allowDisplay(c: Context): Boolean = prefs(c).getBoolean("allow_display", true)

  fun setAllowDisplay(c: Context, on: Boolean) = prefs(c).edit().putBoolean("allow_display", on).apply()

  /** How long a picture Muse shows stays up before the screensaver/home comes back. */
  fun imageSeconds(c: Context): Int = prefs(c).getInt("image_seconds", DEFAULT_IMAGE_SECONDS)

  fun setImageSeconds(c: Context, v: Int) = prefs(c).edit().putInt("image_seconds", v).apply()

  /**
   * Let Muse reach devices on the home network through this Portal: mDNS discovery, HTTP to
   * private (RFC 1918) addresses, and Google Cast control. Off = those commands aren't offered.
   */
  fun allowLan(c: Context): Boolean = prefs(c).getBoolean("allow_lan", true)

  fun setAllowLan(c: Context, on: Boolean) = prefs(c).edit().putBoolean("allow_lan", on).apply()

  /**
   * "Hey Alfred": listen for the wake word on this Portal. Keyword spotting is on-device; audio
   * only goes to Muse after the wake word. Off by default (it opens the microphone).
   */
  fun wakeWord(c: Context): Boolean = prefs(c).getBoolean("wake_word", false)

  fun setWakeWord(c: Context, on: Boolean) = prefs(c).edit().putBoolean("wake_word", on).apply()

  /** Only listen for the wake word while someone is in the room (Meta's presence detector). */
  fun wakeOnlyWhenPresent(c: Context): Boolean = prefs(c).getBoolean("wake_presence", true)

  fun setWakeOnlyWhenPresent(c: Context, on: Boolean) = prefs(c).edit().putBoolean("wake_presence", on).apply()

  /** The home screen's "hey" button opens Muse push-to-talk instead of the stock assistant. */
  fun heyButton(c: Context): Boolean = prefs(c).getBoolean("hey_button", true)

  fun setHeyButton(c: Context, on: Boolean) = prefs(c).edit().putBoolean("hey_button", on).apply()

  /** Let Muse open apps, links and control media playback on this Portal. */
  fun allowApps(c: Context): Boolean = prefs(c).getBoolean("allow_apps", true)

  fun setAllowApps(c: Context, on: Boolean) = prefs(c).edit().putBoolean("allow_apps", on).apply()

  /** Let Muse control the smart home: Home Assistant (when configured) and Philips Hue. */
  fun allowSmartHome(c: Context): Boolean = prefs(c).getBoolean("allow_smart_home", true)

  fun setAllowSmartHome(c: Context, on: Boolean) = prefs(c).edit().putBoolean("allow_smart_home", on).apply()

  /** Home Assistant base URL, e.g. http://homeassistant.local:8123. */
  fun haUrl(c: Context): String = prefs(c).getString("ha_url", "")?.trim().orEmpty()

  fun setHaUrl(c: Context, v: String) = prefs(c).edit().putString("ha_url", v.trim()).apply()

  /** A Home Assistant long-lived access token (a secret). */
  fun haToken(c: Context): String = prefs(c).getString("ha_token", "")?.trim().orEmpty()

  fun setHaToken(c: Context, v: String) = prefs(c).edit().putString("ha_token", v.trim()).apply()

  /** Hue bridge address and application key, set by hue.pair (protocol state, not a setting). */
  fun hueBridge(c: Context): String = prefs(c).getString("hue_bridge", "").orEmpty()

  fun hueKey(c: Context): String = prefs(c).getString("hue_key", "").orEmpty()

  fun setHue(c: Context, bridge: String, key: String) =
      prefs(c).edit().putString("hue_bridge", bridge).putString("hue_key", key).apply()

  fun sdkToken(c: Context): String? = prefs(c).getString("sdk_token", null)?.takeIf { isValidSdkToken(it) }

  fun isValidSdkToken(t: String) = SDK_TOKEN_RE.matches(t.trim())

  /** Stores a token from gadgets.muse.ai; returns false (and stores nothing) if malformed. */
  fun setSdkToken(c: Context, token: String): Boolean {
    val t = token.trim()
    if (t.isEmpty()) {
      prefs(c).edit().remove("sdk_token").apply()
      return true
    }
    if (!isValidSdkToken(t)) return false
    prefs(c).edit().putString("sdk_token", t).apply()
    return true
  }

  fun identity(c: Context): MuseIdentity {
    val p = prefs(c)
    p.getString("identity_mac", null)?.takeIf { MuseIdentity.isValid(it) }?.let { return MuseIdentity(it) }
    return MuseIdentity.generate().also { p.edit().putString("identity_mac", it.mac).commit() }
  }

  fun pairing(c: Context): MusePairingRecord? =
      prefs(c).getString("pairing", null)?.let { runCatching { MusePairingRecord.fromJson(JSONObject(it)) }.getOrNull() }

  fun isPaired(c: Context) = pairing(c) != null

  /** commit(): pairing is only confirmed to the app after this is on disk. */
  fun savePairing(c: Context, r: MusePairingRecord): Boolean =
      prefs(c).edit().putString("pairing", r.toJson().toString()).commit()

  fun clearPairing(c: Context) = prefs(c).edit().remove("pairing").commit()

  /** Until when (wall clock) the BLE pairing window is open; 0 = closed. */
  fun pairingWindowUntil(c: Context): Long = prefs(c).getLong("pairing_until", 0L)

  fun setPairingWindowUntil(c: Context, at: Long) = prefs(c).edit().putLong("pairing_until", at).apply()
}
