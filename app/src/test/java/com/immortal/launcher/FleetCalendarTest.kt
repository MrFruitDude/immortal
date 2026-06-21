package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JSON-shaping for the fleet `/calendar` endpoint (no Context needed). */
class FleetCalendarTest {

  @Test
  fun toJson_emptyWhenNoLink() {
    val json = FleetCalendar.toJson(ScreensaverConfig.Settings())
    assertFalse(json.getBoolean("enabled"))
    assertEquals("", json.getString("url"))
    assertEquals(CalendarFeed.RANGE_DAY, json.getString("range"))
    assertEquals("", json.getString("provider"))
    assertFalse(json.getBoolean("supported"))
    // Advertises the four selectable ranges for the client UI.
    assertEquals(4, json.getJSONArray("ranges").length())
  }

  @Test
  fun toJson_reportsGoogleLink() {
    val url = "https://calendar.google.com/calendar/ical/x%40group.calendar.google.com/private-y/basic.ics"
    val json =
        FleetCalendar.toJson(
            ScreensaverConfig.Settings(calendarUrl = url, calendarRange = CalendarFeed.RANGE_WEEK))
    assertTrue(json.getBoolean("enabled"))
    assertEquals(url, json.getString("url"))
    assertEquals(CalendarFeed.RANGE_WEEK, json.getString("range"))
    assertEquals("Google Calendar", json.getString("provider"))
    assertTrue(json.getBoolean("supported"))
  }

  @Test
  fun toJson_flagsUnsupportedLinkButStaysEnabled() {
    val json =
        FleetCalendar.toJson(ScreensaverConfig.Settings(calendarUrl = "https://example.com/not-a-feed"))
    // A link is set, so the widget is "enabled", but it doesn't look fetchable.
    assertTrue(json.getBoolean("enabled"))
    assertFalse(json.getBoolean("supported"))
    assertEquals("Calendar feed", json.getString("provider"))
  }

  @Test
  fun toJson_reportsDisabledWidgetKeepingLink() {
    // A link is saved but the on/off toggle is off: the widget is hidden, so the
    // effective "enabled" is false, while "hasLink" stays true and "widgetOn" false.
    val url = "https://calendar.google.com/calendar/ical/x/basic.ics"
    val json =
        FleetCalendar.toJson(
            ScreensaverConfig.Settings(calendarUrl = url, calendarEnabled = false))
    assertFalse(json.getBoolean("enabled"))
    assertFalse(json.getBoolean("widgetOn"))
    assertTrue(json.getBoolean("hasLink"))
    assertEquals(url, json.getString("url"))
  }

  @Test
  fun toJson_reportsSizeAndSide() {
    val def = FleetCalendar.toJson(ScreensaverConfig.Settings(calendarUrl = "https://x/basic.ics"))
    assertEquals(1, def.getInt("size")) // medium default
    assertEquals(ScreensaverConfig.CAL_SIDE_RIGHT, def.getString("side"))

    val custom =
        FleetCalendar.toJson(
            ScreensaverConfig.Settings(
                calendarUrl = "https://x/basic.ics",
                calendarSize = 2,
                calendarSide = ScreensaverConfig.CAL_SIDE_LEFT))
    assertEquals(2, custom.getInt("size"))
    assertEquals(ScreensaverConfig.CAL_SIDE_LEFT, custom.getString("side"))
  }

  @Test
  fun ranges_coverAllDisplayOptions() {
    assertEquals(
        listOf(
            CalendarFeed.RANGE_DAY,
            CalendarFeed.RANGE_3DAY,
            CalendarFeed.RANGE_WEEK,
            CalendarFeed.RANGE_AGENDA),
        FleetCalendar.RANGES)
  }
}
