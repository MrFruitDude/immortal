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
