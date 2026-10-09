/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WeatherConditionsTest {
  private lateinit var tz: TimeZone
  private lateinit var locale: Locale

  @Before
  fun pin() {
    tz = TimeZone.getDefault()
    locale = Locale.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    Locale.setDefault(Locale.US)
  }

  @After
  fun restore() {
    TimeZone.setDefault(tz)
    Locale.setDefault(locale)
  }

  private val json =
      """
      {"current":{"temperature_2m":9.6,"apparent_temperature":6.8,"weather_code":2,"is_day":0,"wind_speed_10m":14.2},
       "daily":{"time":["2026-10-09","2026-10-10"],"weather_code":[2,61],"temperature_2m_max":[12.4,10.0],
                "temperature_2m_min":[4.6,5.0],"sunrise":["2026-10-09T07:02","2026-10-10T07:03"],
                "sunset":["2026-10-09T18:20","2026-10-10T18:18"],"precipitation_probability_max":[10,80]},
       "hourly":{"time":["2026-10-09T18:00","2026-10-09T19:00"],"weather_code":[2,3],"temperature_2m":[9.6,8.9]}}
      """

  @Test
  fun parsesNowTodayAndSun() {
    val now = 1_791_568_800_000L // 2026-10-09T18:00Z
    val c = Weather.parseConditions(json, "Montréal", fahrenheit = false, nowMillis = now)
    assertEquals(10, c.temp)
    assertEquals(7, c.feelsLike)
    assertEquals(2, c.code)
    assertFalse(c.isDay)
    assertEquals(14, c.wind)
    assertEquals("km/h", c.windUnit)
    assertEquals(12, c.hi)
    assertEquals(5, c.lo)
    assertEquals(10, c.precipChance)
    assertTrue(c.sunsetMillis > c.sunriseMillis)
    assertEquals(2, c.days.size)
    assertEquals("Partly cloudy", Weather.conditionName(c.code))
  }

  @Test
  fun conditionsUrl_asksForSunAndCurrent() {
    val u = Weather.conditionsUrl(45.5, -73.6, fahrenheit = false)
    assertTrue(u.contains("sunrise,sunset"))
    assertTrue(u.contains("&current=temperature_2m,apparent_temperature,weather_code,is_day"))
    assertTrue(u.contains("wind_speed_unit=kmh"))
  }
}
