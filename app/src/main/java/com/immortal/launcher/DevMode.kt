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
 * Developer mode for iterating on Immortal over the fleet channel.
 *
 * When ON, Immortal's over-the-air self-updater ([UpdateManager.checkForUpdate]) is
 * paused, so a locally-built APK pushed via the fleet agent isn't silently
 * overwritten the next time the device polls the official `version.json`. It changes
 * nothing else — the launcher, store, and screensaver run exactly as before.
 *
 * Toggled (and read) over the fleet API via `/dev`, and surfaced in `/info`. The
 * companion CLI command is `fleetctl dev on|off|status|update <apk>`.
 */
object DevMode {

  private const val PREFS = "immortal_dev"

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun isEnabled(c: Context): Boolean = prefs(c).getBoolean("enabled", false)

  fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("enabled", on).apply()

  /** Pure status payload for the `/dev` and `/info` responses (no Context needed). */
  internal fun statusJson(enabled: Boolean, versionCode: Long, versionName: String): JSONObject =
      JSONObject()
          .put("enabled", enabled)
          .put("versionCode", versionCode)
          .put("versionName", versionName)
}
