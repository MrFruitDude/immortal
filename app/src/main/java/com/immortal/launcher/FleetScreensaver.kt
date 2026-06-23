/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import org.json.JSONObject

/**
 * Screensaver slice of the Fleet Agent API (see [FleetRoutes] `/screensaver`). Lets
 * the laptop fleet tool read and push the whole photo-frame configuration over WiFi
 * — source, fit, interval, shuffle, videos, now-playing, presence/power, and the
 * idle/overnight screen-off windows — so a wall of Portals can be set up without
 * wireless ADB or a per-device tap-through. (The calendar widget has its own
 * [FleetCalendar] / `/calendar` endpoint and is intentionally left out here.)
 *
 * Like [FleetCalendar], the JSON shaping is split out so it's JVM-unit-testable:
 * [toJson] is a pure `Settings → JSON` mapping; [apply] is the only Context-touching
 * part and just funnels recognised keys to the existing [ScreensaverConfig] setters,
 * which already clamp/validate. Only keys actually present in the body are touched,
 * so a partial push leaves everything else alone.
 *
 * Note: display changes (source/fit/interval/…) take effect on the next screensaver
 * cycle, exactly as they do from the in-app settings screen — the agent doesn't
 * force-restart a running dream. The `enabled` and overnight changes apply right
 * away ([FleetRoutes] reaffirms ownership and reschedules the overnight window).
 */
object FleetScreensaver {

  internal interface Writer {
    fun setEnabled(value: Boolean)
    fun useDefault()
    fun setFolder(path: String)
    fun setAlbumUrl(url: String)
    fun setImmich(url: String, key: String)
    fun setSmb(host: String, share: String, path: String, user: String, pass: String)
    fun setDav(url: String, user: String, pass: String)
    fun setWebUrl(url: String)
    fun setAlbumRefreshMin(value: Int)
    fun setFit(value: String)
    fun setInterval(value: Int)
    fun setShuffle(value: Boolean)
    fun setIncludeVideo(value: Boolean)
    fun setBatterySaver(value: Boolean)
    fun setShowNowPlaying(value: Boolean)
    fun setPresenceMode(value: FrameMode)
    fun setIdleSleepMin(value: Int)
    fun setOvernightEnabled(value: Boolean)
    fun setOvernightStartMin(value: Int)
    fun setOvernightEndMin(value: Int)
  }

  private class AndroidWriter(private val context: Context) : Writer {
    override fun setEnabled(value: Boolean) = ScreensaverConfig.setEnabled(context, value)
    override fun useDefault() = ScreensaverConfig.useDefault(context)
    override fun setFolder(path: String) = ScreensaverConfig.setFolder(context, path)
    override fun setAlbumUrl(url: String) = ScreensaverConfig.setAlbumUrl(context, url)
    override fun setImmich(url: String, key: String) = ScreensaverConfig.setImmich(context, url, key)
    override fun setSmb(host: String, share: String, path: String, user: String, pass: String) =
        ScreensaverConfig.setSmb(context, host, share, path, user, pass)
    override fun setDav(url: String, user: String, pass: String) =
        ScreensaverConfig.setDav(context, url, user, pass)
    override fun setWebUrl(url: String) = ScreensaverConfig.setWebUrl(context, url)
    override fun setAlbumRefreshMin(value: Int) = ScreensaverConfig.setAlbumRefreshMin(context, value)
    override fun setFit(value: String) = ScreensaverConfig.setFit(context, value)
    override fun setInterval(value: Int) = ScreensaverConfig.setInterval(context, value)
    override fun setShuffle(value: Boolean) = ScreensaverConfig.setShuffle(context, value)
    override fun setIncludeVideo(value: Boolean) = ScreensaverConfig.setIncludeVideo(context, value)
    override fun setBatterySaver(value: Boolean) = ScreensaverConfig.setBatterySaver(context, value)
    override fun setShowNowPlaying(value: Boolean) = ScreensaverConfig.setShowNowPlaying(context, value)
    override fun setPresenceMode(value: FrameMode) = ScreensaverConfig.setPresenceMode(context, value)
    override fun setIdleSleepMin(value: Int) = ScreensaverConfig.setIdleSleepMin(context, value)
    override fun setOvernightEnabled(value: Boolean) =
        ScreensaverConfig.setOvernightEnabled(context, value)
    override fun setOvernightStartMin(value: Int) =
        ScreensaverConfig.setOvernightStartMin(context, value)
    override fun setOvernightEndMin(value: Int) = ScreensaverConfig.setOvernightEndMin(context, value)
  }

  /**
   * Pure render of the screensaver display settings — delegates to the `screensaver` settings
   * domain ([com.immortal.launcher.settings.SettingsDomains.screensaver]), which owns the flat wire
   * format declaratively. (`apply` below is not yet routed through the domain — see that domain's
   * note — so this is the read half of the façade only.)
   */
  fun toJson(s: ScreensaverConfig.Settings): JSONObject =
      com.immortal.launcher.settings.SettingsDomains.screensaver.flatJson(s)

  /**
   * The photo-source setup the remote's Setup form reads to pre-fill — the active source type plus
   * every source's stored fields (so editing round-trips). Served only to a paired remote on the
   * LAN; the secret fields (Immich key, share/WebDAV passwords) are included for that pre-fill,
   * matching the on-Portal connect screens.
   */
  fun sourcesJson(s: ScreensaverConfig.Settings): JSONObject =
      JSONObject()
          .put("source", currentSource(s))
          .put("immichUrl", s.immichUrl ?: "")
          .put("immichKey", s.immichKey ?: "")
          .put("smbHost", s.smbHost ?: "")
          .put("smbShare", s.smbShare ?: "")
          .put("smbPath", s.smbPath ?: "")
          .put("smbUser", s.smbUser ?: "")
          .put("smbPass", s.smbPass ?: "")
          .put("davUrl", s.davUrl ?: "")
          .put("davUser", s.davUser ?: "")
          .put("davPass", s.davPass ?: "")
          .put("webUrl", s.webUrl ?: "")
          .put("albumUrl", s.albumUrl ?: "")

  /** The active photo-source as a Setup-form key (immich/smb/dav/web/album/default). Pure. */
  internal fun currentSource(s: ScreensaverConfig.Settings): String = PhotoFrameSource.from(s).setupKey

  /** Coerce a fit string to a known value, or null if unrecognised. Pure. */
  internal fun coerceFit(v: String?): String? =
      when (v) {
        ScreensaverConfig.FIT_FILL, ScreensaverConfig.FIT_FIT -> v
        else -> null
      }

  /**
   * Parse a presence-mode name, or null if unrecognised. Pure. Returning null (rather
   * than defaulting) lets [apply] skip an unknown value instead of silently flipping
   * the mode on a typo — the same fail-safe shape as [coerceFit].
   */
  internal fun coercePresenceMode(v: String?): FrameMode? =
      runCatching { FrameMode.valueOf((v ?: "").uppercase()) }.getOrNull()

  /**
   * Apply a pushed screensaver config. Returns the list of applied keys, plus a flag
   * (via [applied] containing any "overnight*" key) the caller uses to reschedule the
   * overnight window.
   */
  fun apply(context: Context, body: JSONObject): List<String> = apply(AndroidWriter(context), body)

  internal fun apply(writer: Writer, body: JSONObject): List<String> {
    val applied = ArrayList<String>()

    if (body.has("enabled")) {
      writer.setEnabled(body.optBoolean("enabled"))
      applied.add("enabled")
    }
    // Source: "default" resets to the built-in feed; folder/url are driven by their
    // value keys below (which also flip the source), so an explicit folder/url here
    // is a no-op unless its path/url is supplied.
    if (body.has("source") && body.optString("source") == ScreensaverConfig.SOURCE_DEFAULT) {
      writer.useDefault()
      applied.add("source")
    }
    if (body.has("folderPath")) {
      val p = body.optString("folderPath")
      if (p.isNotBlank()) {
        writer.setFolder(p)
        applied.add("folderPath")
      }
    }
    if (body.has("albumUrl")) {
      val u = body.optString("albumUrl")
      if (u.isNotBlank()) {
        writer.setAlbumUrl(u)
        applied.add("albumUrl")
      }
    }
    // Credentialed photo sources (the remote's Setup form / fleet push). Each is atomic — it only
    // applies when its required fields are present — so a partial push or a different source's
    // fields don't accidentally switch the source. Mirrors the same ScreensaverConfig setters the
    // on-Portal connect screens use.
    run {
      val url = body.optString("immichUrl")
      val key = body.optString("immichKey")
      if (url.isNotBlank() && key.isNotBlank()) {
        writer.setImmich(url, key)
        applied.add("immich")
      }
    }
    run {
      val host = body.optString("smbHost")
      val share = body.optString("smbShare")
      if (host.isNotBlank() && share.isNotBlank()) {
        writer.setSmb(
            host, share, body.optString("smbPath"), body.optString("smbUser"), body.optString("smbPass"))
        applied.add("smb")
      }
    }
    run {
      val url = body.optString("davUrl")
      if (url.isNotBlank()) {
        writer.setDav(url, body.optString("davUser"), body.optString("davPass"))
        applied.add("dav")
      }
    }
    run {
      val url = body.optString("webUrl")
      if (url.isNotBlank()) {
        writer.setWebUrl(url)
        applied.add("webUrl")
      }
    }
    if (body.has("albumRefreshMin")) {
      writer.setAlbumRefreshMin(body.optInt("albumRefreshMin"))
      applied.add("albumRefreshMin")
    }
    coerceFit(if (body.has("fit")) body.optString("fit") else null)?.let {
      writer.setFit(it)
      applied.add("fit")
    }
    if (body.has("intervalSec")) {
      writer.setInterval(body.optInt("intervalSec"))
      applied.add("intervalSec")
    }
    if (body.has("shuffle")) {
      writer.setShuffle(body.optBoolean("shuffle"))
      applied.add("shuffle")
    }
    if (body.has("includeVideo")) {
      writer.setIncludeVideo(body.optBoolean("includeVideo"))
      applied.add("includeVideo")
    }
    if (body.has("batterySaver")) {
      writer.setBatterySaver(body.optBoolean("batterySaver"))
      applied.add("batterySaver")
    }
    if (body.has("showNowPlaying")) {
      writer.setShowNowPlaying(body.optBoolean("showNowPlaying"))
      applied.add("showNowPlaying")
    }
    if (body.has("presenceMode")) {
      // Ignore an unrecognised mode rather than defaulting (which would silently flip
      // the setting on a typo); a valid value is applied.
      coercePresenceMode(body.optString("presenceMode"))?.let {
        writer.setPresenceMode(it)
        applied.add("presenceMode")
      }
    }
    if (body.has("idleSleepMin")) {
      writer.setIdleSleepMin(body.optInt("idleSleepMin"))
      applied.add("idleSleepMin")
    }
    if (body.has("overnightEnabled")) {
      writer.setOvernightEnabled(body.optBoolean("overnightEnabled"))
      applied.add("overnightEnabled")
    }
    if (body.has("overnightStartMin")) {
      writer.setOvernightStartMin(body.optInt("overnightStartMin"))
      applied.add("overnightStartMin")
    }
    if (body.has("overnightEndMin")) {
      writer.setOvernightEndMin(body.optInt("overnightEndMin"))
      applied.add("overnightEndMin")
    }
    return applied
  }
}
