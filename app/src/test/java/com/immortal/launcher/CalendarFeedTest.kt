package com.immortal.launcher

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarFeedTest {

  @Test
  fun isSupported_recognisesIcsAndKnownHosts() {
    assertTrue(
        CalendarFeed.isSupported(
            "https://calendar.google.com/calendar/ical/abc%40group.calendar.google.com/private-x/basic.ics"))
    assertTrue(CalendarFeed.isSupported("webcal://p01-caldav.icloud.com/published/2/MT234"))
    assertTrue(CalendarFeed.isSupported("https://example.com/feed.ics"))
    assertFalse(CalendarFeed.isSupported("https://example.com/album/123"))
    assertFalse(CalendarFeed.isSupported(""))
    assertFalse(CalendarFeed.isSupported("not a url"))
  }

  @Test
  fun providerName_distinguishesProviders() {
    assertEquals(
        "Google Calendar",
        CalendarFeed.providerName("https://calendar.google.com/calendar/ical/x/basic.ics"))
    assertEquals(
        "Apple iCloud", CalendarFeed.providerName("webcal://p01-caldav.icloud.com/published/2/x"))
    assertEquals("Calendar feed", CalendarFeed.providerName("https://example.com/feed.ics"))
  }

  @Test
  fun normalizeUrl_rewritesWebcalToHttps() {
    assertEquals(
        "https://p01-caldav.icloud.com/x",
        CalendarFeed.normalizeUrl("webcal://p01-caldav.icloud.com/x"))
    assertEquals(
        "https://example.com/feed.ics",
        CalendarFeed.normalizeUrl("  https://example.com/feed.ics "))
  }

  @Test
  fun clampRange_fallsBackToDay() {
    assertEquals(CalendarFeed.RANGE_WEEK, CalendarFeed.clampRange("week"))
    assertEquals(CalendarFeed.RANGE_AGENDA, CalendarFeed.clampRange("agenda"))
    assertEquals(CalendarFeed.RANGE_DAY, CalendarFeed.clampRange("bogus"))
    assertEquals(CalendarFeed.RANGE_DAY, CalendarFeed.clampRange(null))
  }

  @Test
  fun unescapeText_resolvesEscapes() {
    assertEquals("a,b c", CalendarFeed.unescapeText("""a\,b\nc"""))
    assertEquals("semi;colon", CalendarFeed.unescapeText("""semi\;colon"""))
  }

  @Test
  fun unfold_joinsContinuationLines() {
    val ics = "SUMMARY:Long\r\n  title here\r\nDTSTART:20231115T140000Z"
    val lines = CalendarFeed.unfold(ics)
    assertEquals("SUMMARY:Long title here", lines[0])
    assertEquals("DTSTART:20231115T140000Z", lines[1])
  }

  @Test
  fun parse_readsTimedAndAllDayEvents() {
    val now = 1_700_000_000_000L // 2023-11-14T22:13:20Z
    val ics =
        """
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        SUMMARY:Team standup
        DTSTART:20231115T140000Z
        DTEND:20231115T143000Z
        END:VEVENT
        BEGIN:VEVENT
        SUMMARY:Holiday\, all day
        DTSTART;VALUE=DATE:20231116
        END:VEVENT
        END:VCALENDAR
        """
            .trimIndent()
    val events = CalendarFeed.parse(ics, now)
    assertEquals(2, events.size)
    assertEquals("Team standup", events[0].title)
    assertFalse(events[0].allDay)
    assertEquals("Holiday, all day", events[1].title)
    assertTrue(events[1].allDay)
  }

  @Test
  fun parse_dropsEventsThatAlreadyEnded() {
    val now = 1_700_000_000_000L
    val ics =
        """
        BEGIN:VCALENDAR
        BEGIN:VEVENT
        SUMMARY:Yesterday
        DTSTART:20231113T140000Z
        DTEND:20231113T150000Z
        END:VEVENT
        BEGIN:VEVENT
        SUMMARY:Soon
        DTSTART:20231115T140000Z
        DTEND:20231115T150000Z
        END:VEVENT
        END:VCALENDAR
        """
            .trimIndent()
    val events = CalendarFeed.parse(ics, now)
    assertEquals(1, events.size)
    assertEquals("Soon", events[0].title)
  }

  @Test
  fun expandRecurrence_weeklyCountYieldsThree() {
    val start = 1_700_000_000_000L
    val horizon = start + 60L * 24 * 60 * 60 * 1000
    val occ = CalendarFeed.expandRecurrence(start, "FREQ=WEEKLY;COUNT=3", emptyList(), horizon)
    assertEquals(3, occ.size)
    assertEquals(start, occ[0])
  }

  @Test
  fun expandRecurrence_honoursExdate() {
    val start = 1_700_000_000_000L
    val horizon = start + 60L * 24 * 60 * 60 * 1000
    val full = CalendarFeed.expandRecurrence(start, "FREQ=DAILY;COUNT=3", emptyList(), horizon)
    assertEquals(3, full.size)
    val excluded =
        CalendarFeed.expandRecurrence(start, "FREQ=DAILY;COUNT=3", listOf(full[1]), horizon)
    assertEquals(2, excluded.size)
    assertFalse(excluded.contains(full[1]))
  }

  @Test
  fun expandRecurrence_stopsAtHorizon() {
    val start = 1_700_000_000_000L
    val horizon = start + 10L * 24 * 60 * 60 * 1000 // 10 days
    val occ = CalendarFeed.expandRecurrence(start, "FREQ=DAILY", emptyList(), horizon)
    // No COUNT/UNTIL: bounded only by the 10-day horizon → 11 daily occurrences (days 0..10).
    assertEquals(11, occ.size)
  }

  @Test
  fun expandRecurrence_unknownRuleFallsBackToSingle() {
    val start = 1_700_000_000_000L
    val occ = CalendarFeed.expandRecurrence(start, "GARBAGE", emptyList(), start + 1000)
    assertEquals(listOf(start), occ)
  }

  // --- windowing (anchored to local noon so day boundaries are deterministic) ---

  private fun localNoon(): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 12)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
  }

  private fun ev(start: Long, title: String) =
      CalendarFeed.Event(start, start + 60L * 60 * 1000, title, false)

  @Test
  fun window_dayRangeKeepsOnlyToday() {
    val now = localNoon()
    val hour = 60L * 60 * 1000
    val day = 24 * hour
    val events =
        listOf(
            ev(now - 2 * hour, "ended"), // already over
            ev(now + hour, "today"), // 1pm today
            ev(now + 30 * hour, "tomorrow"), // ~next day
            ev(now + 8 * day, "next week"),
        )
    val shown = CalendarFeed.window(events, CalendarFeed.RANGE_DAY, now)
    assertEquals(listOf("today"), shown.map { it.title })
  }

  @Test
  fun window_weekRangeKeepsThroughSevenDays() {
    val now = localNoon()
    val day = 24L * 60 * 60 * 1000
    val events =
        listOf(
            ev(now + 2 * 60 * 60 * 1000, "today"),
            ev(now + 2 * day, "in 2 days"),
            ev(now + 5 * day, "in 5 days"),
            ev(now + 10 * day, "in 10 days"),
        )
    val shown = CalendarFeed.window(events, CalendarFeed.RANGE_WEEK, now)
    assertEquals(listOf("today", "in 2 days", "in 5 days"), shown.map { it.title })
  }

  @Test
  fun window_agendaIgnoresDateAndCaps() {
    val now = localNoon()
    val day = 24L * 60 * 60 * 1000
    val events = (1..12).map { ev(now + it * day, "event $it") }
    val shown = CalendarFeed.window(events, CalendarFeed.RANGE_AGENDA, now)
    assertEquals(CalendarFeed.MAX_EVENTS, shown.size)
    assertEquals("event 1", shown.first().title)
  }
}
