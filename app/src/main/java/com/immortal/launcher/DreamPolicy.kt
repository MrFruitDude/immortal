/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Works around the Portal build's dream policy. Meta's modified PowerManager
 * force-wakes ANY third-party dream at `last-activity + min(screen_off + 120s,
 * sleep_timeout)` — a dream can never run indefinitely and never hands off to
 * sleep. (Stock Portal masked this: its SuperFrame app caught the wake, and the
 * presence service kept the device awake forever.)
 *
 * Immortal's policy:
 *  - When the system bounces the dream (not a user tap, not a power-button
 *    sleep), instantly relaunch the same frame as [PhotoFramePreviewActivity],
 *    which holds the screen on — the photo frame becomes permanent, as on a
 *    stock Portal. One brief flicker ~2 minutes in, then stable.
 *  - On battery models (Portal Go) with "pause on battery" enabled (default),
 *    the dream is gated by charge state instead: unplugged → screensaver off so
 *    the device reaches real sleep; plugged in → permanent frame as above.
 *    The gate writes `screensaver_activate_on_sleep`, which provisioning's
 *    WRITE_SECURE_SETTINGS grant allows.
 */
object DreamPolicy {
  private const val TAG = "ImmortalDream"

  /** Set by [PhotoDreamService] just before finish() on a user tap. */
  @Volatile var userExitAt: Long = 0L

  fun hasBattery(context: Context): Boolean =
      runCatching {
            context
                .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) == true
          }
          .getOrDefault(false)

  fun isPowered(context: Context): Boolean =
      runCatching {
            (context
                .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
          }
          .getOrDefault(true)

  /** Pure decision (unit-tested): should the dream be enabled right now? */
  internal fun dreamShouldBeEnabled(
      hasBattery: Boolean,
      batterySaver: Boolean,
      powered: Boolean,
  ): Boolean = !hasBattery || !batterySaver || powered

  /** Pure decision (unit-tested): should a dream-stop relaunch the frame? */
  internal fun shouldRelaunch(
      userExitAgoMs: Long,
      interactive: Boolean,
      hasBattery: Boolean,
      batterySaver: Boolean,
      powered: Boolean,
  ): Boolean {
    if (userExitAgoMs in 0..4000) return false // user tapped out of the dream
    if (!interactive) return false // power button / real sleep — leave it be
    return dreamShouldBeEnabled(hasBattery, batterySaver, powered)
  }

  /** Called on ACTION_DREAMING_STOPPED: continue the frame unless the user ended it. */
  fun onDreamingStopped(context: Context) {
    val pm = context.getSystemService(PowerManager::class.java)
    val relaunch =
        shouldRelaunch(
            userExitAgoMs = System.currentTimeMillis() - userExitAt,
            interactive = pm?.isInteractive == true,
            hasBattery = hasBattery(context),
            batterySaver = ScreensaverConfig.load(context).batterySaver,
            powered = isPowered(context),
        )
    Log.i(TAG, "dream stopped; relaunch frame = $relaunch")
    if (!relaunch) return
    runCatching {
      context.startActivity(
          Intent(context, PhotoFramePreviewActivity::class.java)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
        .onFailure { Log.w(TAG, "frame relaunch failed", it) }
  }

  /**
   * Battery-saver gate: on battery models with the saver on, the screensaver only
   * activates while charging; unplugged, the device idles into real sleep.
   * No-ops without WRITE_SECURE_SETTINGS (provisioning grants it).
   */
  fun applyDreamGate(context: Context) {
    val enable =
        dreamShouldBeEnabled(
            hasBattery = hasBattery(context),
            batterySaver = ScreensaverConfig.load(context).batterySaver,
            powered = isPowered(context),
        )
    runCatching {
      val resolver = context.contentResolver
      val current = Settings.Secure.getInt(resolver, "screensaver_activate_on_sleep", 1)
      val want = if (enable) 1 else 0
      if (current != want) {
        Settings.Secure.putInt(resolver, "screensaver_activate_on_sleep", want)
        Log.i(TAG, "dream gate: screensaver_activate_on_sleep=$want")
      }
    }
  }
}
