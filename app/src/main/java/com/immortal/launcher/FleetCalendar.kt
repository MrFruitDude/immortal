/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Calendar-widget slice of the Fleet Agent API (see [FleetRoutes] `/calendar`). Lets
 * the laptop fleet tool read and push the screensaver calendar's feed link and
 * display range over WiFi — no wireless ADB, no per-device tap-through.
 *
 * Kept tiny and split from the socket/routing layer so the JSON shaping is
 * JVM-unit-testable: [toJson] is a pure `Settings → JSON` mapping, and [apply] is the
 * only Context-touching part (it just funnels recognised keys to the existing
 * [ScreensaverConfig] setters, which already clamp/validate).
 *
 * Wire format (both directions use the same field names):
 * ```
 * { "url": "https://…/basic.ics",   // "" clears the calendar (widget off)
 *   "range": "day|3day|week|agenda" } // unknown values clamp to "day"
 * ```
 */
object FleetCalendar {

  /** The display ranges a client can choose from, advertised in [toJson]. */
  val RANGES =
      listOf(
          CalendarFeed.RANGE_DAY,
          CalendarFeed.RANGE_3DAY,
          CalendarFeed.RANGE_WEEK,
          CalendarFeed.RANGE_AGENDA,
      )

  /** Pure render of the calendar settings the agent reports back. */
  fun toJson(s: ScreensaverConfig.Settings): JSONObject {
    val url = s.calendarUrl.orEmpty()
    return JSONObject()
        // "enabled" = effective: a link is set AND the on/off toggle is on (the widget
        // actually shows). "widgetOn" is the toggle alone, "hasLink" whether a link is
        // saved — so a client can tell "linked but hidden" from "no link".
        .put("enabled", s.usesCalendar)
        .put("widgetOn", s.calendarEnabled)
        .put("hasLink", s.hasCalendarLink)
        .put("url", url)
        .put("range", s.calendarRange)
        .put("size", s.calendarSize)
        .put("side", s.calendarSide)
        .put("provider", if (url.isBlank()) "" else CalendarFeed.providerName(url))
        // True when the link looks like a fetchable ICS feed; a client can warn the
        // operator before saving an obviously-wrong link (the device stores it either way).
        .put("supported", url.isNotBlank() && CalendarFeed.isSupported(url))
        .put("ranges", JSONArray(RANGES))
  }

  /**
   * Apply a pushed calendar config. Only the keys actually present are touched, so a
   * partial push (e.g. just `{"range":"week"}`) leaves the link untouched. Returns the
   * list of applied keys for the response.
   */
  fun apply(context: Context, body: JSONObject): List<String> {
    val applied = ArrayList<String>(3)
    if (body.has("url")) {
      // setCalendarUrl trims and clears the widget when blank.
      ScreensaverConfig.setCalendarUrl(context, body.optString("url"))
      applied.add("url")
    }
    // The on/off toggle. Preferred write key is "widgetOn", which round-trips
    // symmetrically with the GET payload's "widgetOn"; "enabled" is accepted as an
    // alias for it (note: GET "enabled" is the *effective* value — link set AND toggle
    // on — so writing "enabled" sets only the toggle, by design).
    if (body.has("widgetOn") || body.has("enabled")) {
      val on = if (body.has("widgetOn")) body.optBoolean("widgetOn") else body.optBoolean("enabled")
      ScreensaverConfig.setCalendarEnabled(context, on)
      applied.add("widgetOn")
    }
    if (body.has("range")) {
      // setCalendarRange clamps unknown values back to a single day.
      ScreensaverConfig.setCalendarRange(context, body.optString("range"))
      applied.add("range")
    }
    if (body.has("size")) {
      // setCalendarSize clamps to 0..2 (Small/Medium/Large).
      ScreensaverConfig.setCalendarSize(context, body.optInt("size"))
      applied.add("size")
    }
    if (body.has("side")) {
      // setCalendarSide normalises anything but "left" to "right".
      ScreensaverConfig.setCalendarSide(context, body.optString("side"))
      applied.add("side")
    }
    return applied
  }
}
