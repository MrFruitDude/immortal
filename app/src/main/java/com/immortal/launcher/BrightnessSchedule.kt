/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import java.util.Calendar

/**
 * Daylight brightness: the system screen brightness follows the sun — full from shortly after
 * sunrise through the early afternoon, then a slow fade to the evening level by sunset and on to
 * the night level by [ImmortalSettings.Settings.brightnessNightHour].
 *
 * It writes the *system* brightness (`Settings.System.SCREEN_BRIGHTNESS`, manual mode), so it
 * holds for the screensaver, the home screen and every app alike. That needs the WRITE_SETTINGS
 * app-op ("Modify system settings"), which provisioning grants; until it's held this is a no-op
 * and turning the setting on opens the system grant screen. Screens that set their own window
 * brightness — the sunrise wake light, the lamp, the night clock — still win while they're up.
 *
 * Driven by an inexact, non-waking alarm every [STEP_MS]: the fade moves in small steps nobody
 * notices, and nothing runs at all while the Portal is asleep.
 */
object BrightnessSchedule {
  const val ACTION_STEP = "com.immortal.launcher.BRIGHTNESS_STEP"
  private const val TAG = "ImmortalBrightness"
  private const val RC = 0x5B21
  private const val STEP_MS = 10L * 60 * 1000
  private const val SUN_PREFS = "immortal_brightness_sun"

  /** Sunrise/sunset fallback (minutes after midnight) until the forecast has answered once. */
  private const val DEFAULT_SUNRISE = 7 * 60
  private const val DEFAULT_SUNSET = 19 * 60

  /**
   * Brightness percent at [nowMin] (minutes after local midnight). Pure, for tests.
   *
   * - before sunrise and after the night hour: [night]
   * - sunrise → +60 min: ramp up to [day]
   * - until 3 h before sunset (never earlier than 1 pm): hold [day]
   * - → sunset: fade to [evening]
   * - sunset → night hour: fade to [night]
   */
  fun levelAt(nowMin: Int, sunriseMin: Int, sunsetMin: Int, day: Int, evening: Int, night: Int, nightHour: Int): Int {
    val rise = sunriseMin.coerceIn(0, 23 * 60)
    val set = sunsetMin.coerceIn(rise + 120, 24 * 60 - 1)
    val full = (rise + 60).coerceAtMost(set)
    val fadeStart = maxOf(set - 180, 13 * 60).coerceIn(full, set)
    val nightAt = (nightHour * 60).let { if (it <= set) set + 60 else it }.coerceAtMost(24 * 60)
    fun lerp(a: Int, b: Int, t: Float) = (a + (b - a) * t.coerceIn(0f, 1f)).toInt()
    return when {
      nowMin < rise -> night
      nowMin < full -> lerp(night, day, (nowMin - rise).toFloat() / (full - rise).coerceAtLeast(1))
      nowMin < fadeStart -> day
      nowMin < set -> lerp(day, evening, (nowMin - fadeStart).toFloat() / (set - fadeStart).coerceAtLeast(1))
      nowMin < nightAt -> lerp(evening, night, (nowMin - set).toFloat() / (nightAt - set).coerceAtLeast(1))
      else -> night
    }.coerceIn(1, 100)
  }

  /** Whether we may write the system brightness (the WRITE_SETTINGS app-op). */
  fun canWrite(c: Context): Boolean = SystemSounds.canWrite(c)

  /** Apply the level for right now and arm the next step (or cancel when off). */
  fun reschedule(c: Context) {
    val pi = pendingIntent(c)
    val alarms = c.getSystemService(AlarmManager::class.java) ?: return
    alarms.cancel(pi)
    if (!ImmortalSettings.load(c).brightnessSchedule) return
    applyNow(c)
    val app = c.applicationContext
    Thread {
          runCatching { refreshSunTimes(app) }
          applyNow(app)
        }
        .start()
    // Non-waking and inexact: steps simply pause while the Portal sleeps.
    runCatching {
      alarms.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + STEP_MS, STEP_MS, pi)
    }
  }

  /** Write the current level to the system brightness. */
  fun applyNow(c: Context) {
    val s = ImmortalSettings.load(c)
    if (!s.brightnessSchedule || !canWrite(c)) return
    val cal = Calendar.getInstance()
    val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    val (rise, set) = sunTimes(c)
    val pct = levelAt(now, rise, set, s.brightnessDay, s.brightnessEvening, s.brightnessNight, s.brightnessNightHour)
    val cr = c.contentResolver
    runCatching {
      Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
      val target = (pct * 255 / 100).coerceIn(1, 255)
      if (Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1) != target) {
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
        Log.i(TAG, "brightness $pct% (sun ${rise / 60}:%02d-${set / 60}:%02d)".format(rise % 60, set % 60))
      }
    }
        .onFailure { Log.w(TAG, "brightness write failed: ${it.message}") }
  }

  /** Today's sunrise/sunset in local minutes, from a once-a-day cache. */
  private fun sunTimes(c: Context): Pair<Int, Int> {
    val p = c.getSharedPreferences(SUN_PREFS, Context.MODE_PRIVATE)
    return p.getInt("rise", DEFAULT_SUNRISE) to p.getInt("set", DEFAULT_SUNSET)
  }

  /** Refresh the sun-time cache when it's from an earlier day. Blocking (network) — call off main. */
  internal fun refreshSunTimes(c: Context) {
    val p = c.getSharedPreferences(SUN_PREFS, Context.MODE_PRIVATE)
    val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    if (p.getInt("day", -1) == today) return
    val sun = Weather.fetchSunTimes(c) ?: return
    fun minutes(ms: Long) =
        Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
    p.edit().putInt("day", today).putInt("rise", minutes(sun.sunriseMillis)).putInt("set", minutes(sun.sunsetMillis)).apply()
  }

  private fun pendingIntent(c: Context): PendingIntent =
      PendingIntent.getBroadcast(
          c,
          RC,
          Intent(c, BrightnessReceiver::class.java).setAction(ACTION_STEP),
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/** One step of [BrightnessSchedule]: refresh today's sun times if needed, then apply. */
class BrightnessReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != BrightnessSchedule.ACTION_STEP) return
    val app = context.applicationContext
    val pending = goAsync()
    Thread {
          try {
            runCatching { BrightnessSchedule.refreshSunTimes(app) }
            BrightnessSchedule.applyNow(app)
          } finally {
            pending.finish()
          }
        }
        .start()
  }
}
