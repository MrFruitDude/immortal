/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

  /** Pure render of the screensaver settings the agent reports back. */
  fun toJson(s: ScreensaverConfig.Settings): JSONObject =
      JSONObject()
          .put("enabled", s.enabled)
          .put("source", s.source)
          .put("folderPath", s.folderPath ?: "")
          .put("albumUrl", s.albumUrl ?: "")
          .put("albumRefreshMin", s.albumRefreshMin)
          .put("fit", s.fit)
          .put("intervalSec", s.intervalSec)
          .put("shuffle", s.shuffle)
          .put("includeVideo", s.includeVideo)
          .put("batterySaver", s.batterySaver)
          .put("showNowPlaying", s.showNowPlaying)
          .put("presenceMode", s.presenceMode.name)
          .put("idleSleepMin", s.idleSleepMin)
          .put("overnightEnabled", s.overnightEnabled)
          .put("overnightStartMin", s.overnightStartMin)
          .put("overnightEndMin", s.overnightEndMin)

  /** Coerce a fit string to a known value, or null if unrecognised. Pure. */
  internal fun coerceFit(v: String?): String? =
      when (v) {
        ScreensaverConfig.FIT_FILL, ScreensaverConfig.FIT_FIT -> v
        else -> null
      }

  /** Parse a presence-mode name; defaults to ALWAYS_ON on anything unexpected. Pure. */
  internal fun coercePresenceMode(v: String?): FrameMode =
      runCatching { FrameMode.valueOf((v ?: "").uppercase()) }.getOrDefault(FrameMode.ALWAYS_ON)

  /** Source keys understood by [apply] beyond the value-driven folder/url paths. */
  internal val RECOGNIZED_KEYS =
      setOf(
          "enabled",
          "source",
          "folderPath",
          "albumUrl",
          "albumRefreshMin",
          "fit",
          "intervalSec",
          "shuffle",
          "includeVideo",
          "batterySaver",
          "showNowPlaying",
          "presenceMode",
          "idleSleepMin",
          "overnightEnabled",
          "overnightStartMin",
          "overnightEndMin",
      )

  /**
   * Apply a pushed screensaver config. Returns the list of applied keys, plus a flag
   * (via [applied] containing any "overnight*" key) the caller uses to reschedule the
   * overnight window.
   */
  fun apply(context: Context, body: JSONObject): List<String> {
    val applied = ArrayList<String>()

    if (body.has("enabled")) {
      ScreensaverConfig.setEnabled(context, body.optBoolean("enabled"))
      applied.add("enabled")
    }
    // Source: "default" resets to the built-in feed; folder/url are driven by their
    // value keys below (which also flip the source), so an explicit folder/url here
    // is a no-op unless its path/url is supplied.
    if (body.has("source") && body.optString("source") == ScreensaverConfig.SOURCE_DEFAULT) {
      ScreensaverConfig.useDefault(context)
      applied.add("source")
    }
    if (body.has("folderPath")) {
      val p = body.optString("folderPath")
      if (p.isNotBlank()) {
        ScreensaverConfig.setFolder(context, p)
        applied.add("folderPath")
      }
    }
    if (body.has("albumUrl")) {
      val u = body.optString("albumUrl")
      if (u.isNotBlank()) {
        ScreensaverConfig.setAlbumUrl(context, u)
        applied.add("albumUrl")
      }
    }
    if (body.has("albumRefreshMin")) {
      ScreensaverConfig.setAlbumRefreshMin(context, body.optInt("albumRefreshMin"))
      applied.add("albumRefreshMin")
    }
    coerceFit(if (body.has("fit")) body.optString("fit") else null)?.let {
      ScreensaverConfig.setFit(context, it)
      applied.add("fit")
    }
    if (body.has("intervalSec")) {
      ScreensaverConfig.setInterval(context, body.optInt("intervalSec"))
      applied.add("intervalSec")
    }
    if (body.has("shuffle")) {
      ScreensaverConfig.setShuffle(context, body.optBoolean("shuffle"))
      applied.add("shuffle")
    }
    if (body.has("includeVideo")) {
      ScreensaverConfig.setIncludeVideo(context, body.optBoolean("includeVideo"))
      applied.add("includeVideo")
    }
    if (body.has("batterySaver")) {
      ScreensaverConfig.setBatterySaver(context, body.optBoolean("batterySaver"))
      applied.add("batterySaver")
    }
    if (body.has("showNowPlaying")) {
      ScreensaverConfig.setShowNowPlaying(context, body.optBoolean("showNowPlaying"))
      applied.add("showNowPlaying")
    }
    if (body.has("presenceMode")) {
      ScreensaverConfig.setPresenceMode(context, coercePresenceMode(body.optString("presenceMode")))
      applied.add("presenceMode")
    }
    if (body.has("idleSleepMin")) {
      ScreensaverConfig.setIdleSleepMin(context, body.optInt("idleSleepMin"))
      applied.add("idleSleepMin")
    }
    if (body.has("overnightEnabled")) {
      ScreensaverConfig.setOvernightEnabled(context, body.optBoolean("overnightEnabled"))
      applied.add("overnightEnabled")
    }
    if (body.has("overnightStartMin")) {
      ScreensaverConfig.setOvernightStartMin(context, body.optInt("overnightStartMin"))
      applied.add("overnightStartMin")
    }
    if (body.has("overnightEndMin")) {
      ScreensaverConfig.setOvernightEndMin(context, body.optInt("overnightEndMin"))
      applied.add("overnightEndMin")
    }
    return applied
  }
}
