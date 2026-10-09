/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrightnessScheduleTest {
  // Sunrise 7:00, sunset 18:30; 100% day, 45% at sunset, 15% from 22:00.
  private fun at(h: Int, m: Int = 0, rise: Int = 7 * 60, set: Int = 18 * 60 + 30) =
      BrightnessSchedule.levelAt(h * 60 + m, rise, set, day = 100, evening = 45, night = 15, nightHour = 22)

  @Test
  fun nightBeforeSunriseAndAfterNightHour() {
    assertEquals(15, at(3))
    assertEquals(15, at(6, 59))
    assertEquals(15, at(22))
    assertEquals(15, at(23, 59))
  }

  @Test
  fun fullThroughTheDay() {
    assertEquals(100, at(8))
    assertEquals(100, at(12))
    assertEquals(100, at(15, 30)) // fade starts 3h before sunset
  }

  @Test
  fun fadesMonotonicallyFromAfternoonToNight() {
    val samples = (15 * 60 + 30..22 * 60 step 10).map { at(it / 60, it % 60) }
    samples.zipWithNext().forEach { (a, b) -> assertTrue("$a then $b", b <= a) }
    assertEquals(45, at(18, 30))
  }

  @Test
  fun winterSunsetStillHoldsFullUntilEarlyAfternoon() {
    // Sunset 16:15: the fade can't start before 13:00.
    assertEquals(100, at(12, 59, rise = 7 * 60 + 30, set = 16 * 60 + 15))
    assertTrue(at(15, rise = 7 * 60 + 30, set = 16 * 60 + 15) < 100)
  }

  @Test
  fun morningRampsUpOverAnHour() {
    val mid = at(7, 30)
    assertTrue(mid in 16..99)
  }
}
