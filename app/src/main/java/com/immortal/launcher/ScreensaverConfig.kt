/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context

/**
 * User configuration for the photo-frame screensaver, persisted across restarts.
 *
 * The default source is Immortal's built-in photo feed; the user can instead point
 * the screensaver at a local folder of photos/videos — including one on a USB-C or
 * SD card plugged into the Portal (any folder reachable through the system file
 * picker). If the chosen folder can't be read (e.g. the drive is unplugged) the
 * screensaver falls back to the default feed, so it's never blank.
 */
object ScreensaverConfig {

  private const val PREFS = "immortal_screensaver"

  const val SOURCE_DEFAULT = "default"
  const val SOURCE_FOLDER = "folder"
  const val FIT_FILL = "fill" // crop to fill the screen
  const val FIT_FIT = "fit" // letterbox to show the whole image
  const val DEFAULT_INTERVAL = 30

  data class Settings(
      val source: String = SOURCE_DEFAULT,
      val folderPath: String? = null,
      val fit: String = FIT_FILL,
      val intervalSec: Int = DEFAULT_INTERVAL,
      val shuffle: Boolean = false,
      val includeVideo: Boolean = true,
      // Battery models (Portal Go) only: pause the screensaver while unplugged so
      // the device can actually sleep, instead of showing photos until empty.
      val batterySaver: Boolean = true,
  ) {
    /** True when the user has chosen a local folder for us to read. */
    val usesFolder: Boolean
      get() = source == SOURCE_FOLDER && !folderPath.isNullOrBlank()
  }

  /** Keep the slideshow interval sane (5s … 10min). */
  fun clampInterval(sec: Int): Int = sec.coerceIn(5, 600)

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun load(context: Context): Settings {
    val p = prefs(context)
    return Settings(
        source = p.getString("source", SOURCE_DEFAULT) ?: SOURCE_DEFAULT,
        folderPath = p.getString("folder_path", null),
        fit = p.getString("fit", FIT_FILL) ?: FIT_FILL,
        intervalSec = clampInterval(p.getInt("interval_sec", DEFAULT_INTERVAL)),
        shuffle = p.getBoolean("shuffle", false),
        includeVideo = p.getBoolean("include_video", true),
        batterySaver = p.getBoolean("battery_saver", true),
    )
  }

  fun setFolder(c: Context, path: String) =
      prefs(c).edit().putString("folder_path", path).putString("source", SOURCE_FOLDER).apply()

  fun useDefault(c: Context) = prefs(c).edit().putString("source", SOURCE_DEFAULT).apply()

  fun setFit(c: Context, fit: String) = prefs(c).edit().putString("fit", fit).apply()

  fun setInterval(c: Context, sec: Int) =
      prefs(c).edit().putInt("interval_sec", clampInterval(sec)).apply()

  fun setShuffle(c: Context, on: Boolean) = prefs(c).edit().putBoolean("shuffle", on).apply()

  fun setIncludeVideo(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("include_video", on).apply()

  fun setBatterySaver(c: Context, on: Boolean) =
      prefs(c).edit().putBoolean("battery_saver", on).apply()
}
