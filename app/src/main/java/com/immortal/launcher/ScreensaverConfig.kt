/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context

/**
 * How the frame relates to the Portal's presence-driven power policy.
 *
 *  - [ALWAYS_ON]  — pin the screen so the frame is permanent (the original wall-frame
 *    behaviour). The dream/sleep presence proxy is masked, so presence reads UNKNOWN and the
 *    music must defer to Home Assistant / a manual override.
 *  - [PRESENCE]   — don't pin: let the Portal's presence policy sleep the screen when the room
 *    empties and re-dream when someone returns. This is the shared baseline the screensaver and
 *    the music both follow (see snapcast-multiroom.md → *Presence*). Confirmed on the Portal Go
 *    on battery; verify empty-room sleep on a mains Portal before making it the global default.
 */
enum class FrameMode {
  ALWAYS_ON,
  PRESENCE,
}

/**
 * User configuration for the photo-frame screensaver, persisted across restarts.
 *
 * The default source is Immortal's built-in photo feed; the user can instead point
 * the screensaver at a local folder of photos/videos — including one on a USB-C or
 * SD card plugged into the Portal (any folder reachable through the system file
 * picker) — or paste a public share link from iCloud or Google Photos. If the
 * chosen source can't be read (e.g. the drive is unplugged, the album was unshared)
 * the screensaver falls back to the default feed, so it's never blank.
 */
object ScreensaverConfig {

  private const val PREFS = "immortal_screensaver"

  const val SOURCE_DEFAULT = "default"
  const val SOURCE_FOLDER = "folder"
  const val SOURCE_URL = "url"
  const val FIT_FILL = "fill" // crop to fill the screen
  const val FIT_FIT = "fit" // letterbox to show the whole image
  const val DEFAULT_INTERVAL = 30
  const val DEFAULT_ALBUM_REFRESH_MIN = 60

  data class Settings(
      // Master on/off for Immortal's photo-frame screensaver. When off, Immortal
      // stops asserting itself as the system Dream and lets the Portal sleep / lets
      // the user run their own screensaver (e.g. Home Assistant + Immich frame).
      val enabled: Boolean = true,
      val source: String = SOURCE_DEFAULT,
      val folderPath: String? = null,
      val albumUrl: String? = null,
      val fit: String = FIT_FILL,
      val intervalSec: Int = DEFAULT_INTERVAL,
      val albumRefreshMin: Int = DEFAULT_ALBUM_REFRESH_MIN,
      val shuffle: Boolean = false,
      val includeVideo: Boolean = true,
      // Calendar widget: a public iCalendar (.ics) feed link (Google "secret iCal"
      // address or an Apple iCloud public-calendar / webcal link) and how much of it
      // to show on the frame. Empty link = the widget is off.
      val calendarUrl: String? = null,
      val calendarRange: String = CalendarFeed.RANGE_DAY,
      // Battery models (Portal Go) only: pause the screensaver while unplugged so
      // the device can actually sleep, instead of showing photos until empty.
      val batterySaver: Boolean = true,
      // Show the current track + album art on the frame while music is playing.
      val showNowPlaying: Boolean = true,
      // Whether the frame is pinned on (ALWAYS_ON) or follows the Portal's presence policy
      // (PRESENCE — the shared screensaver/music baseline). Defaults to ALWAYS_ON to preserve
      // the original permanent-frame behaviour until PRESENCE is verified on mains hardware.
      val presenceMode: FrameMode = FrameMode.ALWAYS_ON,
      // Idle screen-off (off by default): minutes the screensaver runs before the
      // screen turns off. 0 = never (Immortal's always-on photo frame).
      val idleSleepMin: Int = 0,
      // Overnight screen-off (off by default): keep the screen off between two times
      // each night. Times are minutes-from-midnight (e.g. 22:00 = 1320).
      val overnightEnabled: Boolean = false,
      val overnightStartMin: Int = 22 * 60,
      val overnightEndMin: Int = 7 * 60,
  ) {
    /** True when the idle screen-off timeout is active. */
    val idleSleepOn: Boolean
      get() = idleSleepMin > 0
    /** True when the user has chosen a local folder for us to read. */
    val usesFolder: Boolean
      get() = source == SOURCE_FOLDER && !folderPath.isNullOrBlank()
    /** True when the user has pasted a public album share link for us to fetch. */
    val usesUrl: Boolean
      get() = source == SOURCE_URL && !albumUrl.isNullOrBlank()
    /** True when a calendar feed link is set, so the widget should show. */
    val usesCalendar: Boolean
      get() = !calendarUrl.isNullOrBlank()
  }

  /** Keep the slideshow interval sane (5s … 10min). */
  fun clampInterval(sec: Int): Int = sec.coerceIn(5, 600)

  /** Keep the album refresh sane (15 min … 24h). Floor matches the settings stepper. */
  fun clampAlbumRefresh(min: Int): Int = min.coerceIn(15, 24 * 60)

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(context: Context): Settings {
    val p = prefs(context)
    return Settings(
        enabled = p.getBoolean("enabled", true),
        source = p.getString("source", SOURCE_DEFAULT) ?: SOURCE_DEFAULT,
        folderPath = p.getString("folder_path", null),
        albumUrl = p.getString("album_url", null),
        fit = p.getString("fit", FIT_FILL) ?: FIT_FILL,
        intervalSec = clampInterval(p.getInt("interval_sec", DEFAULT_INTERVAL)),
        albumRefreshMin =
            clampAlbumRefresh(p.getInt("album_refresh_min", DEFAULT_ALBUM_REFRESH_MIN)),
        shuffle = p.getBoolean("shuffle", false),
        includeVideo = p.getBoolean("include_video", true),
        calendarUrl = p.getString("calendar_url", null),
        calendarRange = CalendarFeed.clampRange(p.getString("calendar_range", CalendarFeed.RANGE_DAY)),
        batterySaver = p.getBoolean("battery_saver", true),
        showNowPlaying = p.getBoolean("show_now_playing", true),
        presenceMode =
            runCatching { FrameMode.valueOf(p.getString("presence_mode", FrameMode.ALWAYS_ON.name)!!) }
                .getOrDefault(FrameMode.ALWAYS_ON),
        idleSleepMin = p.getInt("idle_sleep_min", 0),
        overnightEnabled = p.getBoolean("overnight_enabled", false),
        overnightStartMin = p.getInt("overnight_start_min", 22 * 60),
        overnightEndMin = p.getInt("overnight_end_min", 7 * 60),
    )
  }

  /** Keep the idle timeout sane (0 = off, else 1…120 min). */
  fun clampIdle(min: Int): Int = if (min <= 0) 0 else min.coerceIn(1, 120)

  /** Minutes-from-midnight wrapped into 0…1439. */
  fun wrapMinuteOfDay(min: Int): Int = ((min % 1440) + 1440) % 1440

  fun setFolder(c: Context, path: String) =
      prefs(c).edit().putString("folder_path", path).putString("source", SOURCE_FOLDER).apply()

  fun setAlbumUrl(c: Context, url: String) =
      prefs(c).edit().putString("album_url", url.trim()).putString("source", SOURCE_URL).apply()

  fun useDefault(c: Context) = prefs(c).edit().putString("source", SOURCE_DEFAULT).apply()

  fun setFit(c: Context, fit: String) = prefs(c).edit().putString("fit", fit).apply()

  fun setInterval(c: Context, sec: Int) =
      prefs(c).edit().putInt("interval_sec", clampInterval(sec)).apply()

  fun setAlbumRefreshMin(c: Context, min: Int) =
      prefs(c).edit().putInt("album_refresh_min", clampAlbumRefresh(min)).apply()

  fun setShuffle(c: Context, on: Boolean) = prefs(c).edit().putBoolean("shuffle", on).apply()

  /** Save (or clear, when blank) the calendar feed link. */
  fun setCalendarUrl(c: Context, url: String) {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) prefs(c).edit().remove("calendar_url").apply()
    else prefs(c).edit().putString("calendar_url", trimmed).apply()
  }

  fun clearCalendarUrl(c: Context) = prefs(c).edit().remove("calendar_url").apply()

  fun setCalendarRange(c: Context, range: String) =
      prefs(c).edit().putString("calendar_range", CalendarFeed.clampRange(range)).apply()

  fun setIncludeVideo(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("include_video", on).apply()

  fun setBatterySaver(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("battery_saver", on).apply()

  fun setShowNowPlaying(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("show_now_playing", on).apply()

  fun setPresenceMode(c: Context, mode: FrameMode) =
      prefs(c).edit().putString("presence_mode", mode.name).apply()

  fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("enabled", on).apply()

  fun setIdleSleepMin(c: Context, min: Int) =
      prefs(c).edit().putInt("idle_sleep_min", clampIdle(min)).apply()

  fun setOvernightEnabled(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("overnight_enabled", on).apply()

  fun setOvernightStartMin(c: Context, min: Int) =
      prefs(c).edit().putInt("overnight_start_min", wrapMinuteOfDay(min)).apply()

  fun setOvernightEndMin(c: Context, min: Int) =
      prefs(c).edit().putInt("overnight_end_min", wrapMinuteOfDay(min)).apply()
}
