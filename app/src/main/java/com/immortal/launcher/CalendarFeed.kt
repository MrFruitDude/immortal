/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Read upcoming events from a public iCalendar (.ics) feed URL — no account or API
 * key, the calendar equivalent of [RemoteAlbum]'s public share links. Both Google
 * Calendar and Apple iCloud expose a calendar this way:
 *
 *  - **Google Calendar** → Settings → *Integrate calendar* → "Secret address in
 *    iCal format" (a private `…/basic.ics` URL — keep it private; anyone with it can
 *    read the calendar).
 *  - **Apple iCloud** → Calendar app → share a calendar → *Public Calendar* → a
 *    `webcal://…/.ics` link.
 *
 * This is a deliberately small RFC 5545 reader: it handles line-unfolding, the
 * common `DTSTART`/`DTEND` forms (UTC `Z`, `TZID=`, floating, and all-day `VALUE=DATE`),
 * `SUMMARY`/`LOCATION` with text escapes, and the recurrence rules people actually
 * use (`RRULE` with `FREQ`/`INTERVAL`/`COUNT`/`UNTIL`, weekly `BYDAY`, plus `EXDATE`
 * exclusions). Anything it can't make sense of is skipped rather than crashing, and a
 * malformed recurrence falls back to showing the event once — the frame degrades
 * gracefully, like the rest of the screensaver.
 *
 * On any network/parse failure [fetch] returns an empty list and the widget simply
 * shows nothing.
 */
object CalendarFeed {

  /** A single calendar occurrence, already resolved to absolute wall-clock millis. */
  data class Event(
      val startMillis: Long,
      val endMillis: Long,
      val title: String,
      val allDay: Boolean,
      val location: String? = null,
  )

  // Display windows for the screensaver widget. Stored verbatim in ScreensaverConfig.
  const val RANGE_DAY = "day" // today only
  const val RANGE_3DAY = "3day" // today + next 2 days
  const val RANGE_WEEK = "week" // today + next 6 days
  const val RANGE_AGENDA = "agenda" // the next N events, however far out ("only events")

  /** Clamp a stored range to a known value (defaults to a single day). */
  fun clampRange(v: String?): String =
      when (v) {
        RANGE_DAY, RANGE_3DAY, RANGE_WEEK, RANGE_AGENDA -> v
        else -> RANGE_DAY
      }

  /** Greatest number of rows we ever render, to keep the widget clean. */
  const val MAX_EVENTS = 8

  // --- URL handling -----------------------------------------------------------

  /** True for a link we can actually fetch as an ICS feed. */
  fun isSupported(url: String): Boolean {
    val u = url.trim()
    if (u.isEmpty()) return false
    val lower = u.lowercase(Locale.US)
    val schemeOk =
        lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("webcal://")
    if (!schemeOk) return false
    // Accept anything that looks like an ICS endpoint or a known calendar host —
    // Google/Apple feeds don't always end in ".ics" (Google uses ".../basic.ics" but
    // iCloud uses an opaque path), so we also recognise the provider hosts.
    return lower.contains(".ics") ||
        lower.startsWith("webcal://") ||
        isGoogle(lower) ||
        isApple(lower)
  }

  private fun isGoogle(lower: String): Boolean =
      lower.contains("calendar.google.com") || lower.contains("google.com/calendar")

  private fun isApple(lower: String): Boolean =
      lower.contains("icloud.com") || lower.contains("apple.com")

  /** Human label for the settings screen. */
  fun providerName(url: String): String {
    val lower = url.trim().lowercase(Locale.US)
    return when {
      isGoogle(lower) -> "Google Calendar"
      isApple(lower) -> "Apple iCloud"
      else -> "Calendar feed"
    }
  }

  /** `webcal://` is just `https://` for a calendar; normalise so we can fetch it. */
  fun normalizeUrl(url: String): String {
    val u = url.trim()
    return when {
      u.startsWith("webcal://", ignoreCase = true) -> "https://" + u.substring("webcal://".length)
      else -> u
    }
  }

  // --- fetch ------------------------------------------------------------------

  /**
   * Fetch and parse the feed, returning every occurrence between [nowMillis] and
   * [nowMillis] + [horizonDays] (already filtered, sorted, and de-duplicated).
   * Returns an empty list on any failure.
   */
  fun fetch(
      url: String,
      nowMillis: Long = System.currentTimeMillis(),
      horizonDays: Int = 45,
  ): List<Event> =
      runCatching {
            val ics = httpGet(normalizeUrl(url)) ?: return emptyList()
            parse(ics, nowMillis, horizonDays)
          }
          .getOrDefault(emptyList())

  // --- parsing (pure; unit-tested) --------------------------------------------

  /**
   * Pure parse of an iCalendar document. [nowMillis] is the reference "now" (passed
   * in rather than read from the clock so tests are deterministic); occurrences that
   * have already ended, or that start beyond the horizon, are dropped.
   */
  internal fun parse(ics: String, nowMillis: Long, horizonDays: Int = 45): List<Event> {
    val horizonEnd = nowMillis + horizonDays * 24L * 60 * 60 * 1000
    val lines = unfold(ics)
    val out = ArrayList<Event>()
    var i = 0
    while (i < lines.size) {
      if (lines[i].trim().equals("BEGIN:VEVENT", ignoreCase = true)) {
        val end = run {
          var j = i + 1
          while (j < lines.size && !lines[j].trim().equals("END:VEVENT", ignoreCase = true)) j++
          j
        }
        parseEvent(lines.subList(i + 1, minOf(end, lines.size)), nowMillis, horizonEnd, out)
        i = end + 1
      } else {
        i++
      }
    }
    // Sort by start, then cap defensively so a runaway recurrence can't flood the UI.
    return out.sortedBy { it.startMillis }.take(500)
  }

  private fun parseEvent(
      body: List<String>,
      nowMillis: Long,
      horizonEnd: Long,
      out: MutableList<Event>,
  ) {
    var start: Long? = null
    var startAllDay = false
    var end: Long? = null
    var title = ""
    var location: String? = null
    var rrule: String? = null
    val exdates = ArrayList<Long>()

    for (raw in body) {
      val p = parseLine(raw) ?: continue
      when (p.name.uppercase(Locale.US)) {
        "DTSTART" -> parseDt(p)?.let { (m, allDay) -> start = m; startAllDay = allDay }
        "DTEND" -> parseDt(p)?.let { (m, _) -> end = m }
        "SUMMARY" -> title = unescapeText(p.value)
        "LOCATION" -> location = unescapeText(p.value).ifBlank { null }
        "RRULE" -> rrule = p.value
        "EXDATE" -> parseDt(p)?.let { (m, _) -> exdates.add(m) }
      }
    }

    val s = start ?: return
    if (title.isBlank()) title = "(busy)"
    // No DTEND → all-day events span the day; timed events are treated as instantaneous.
    val duration = (end ?: if (startAllDay) s + 24L * 60 * 60 * 1000 else s) - s
    // Drop occurrences that already finished (with an all-day grace so "today"'s
    // all-day events stay visible all day).
    val floor = nowMillis

    val starts =
        if (rrule.isNullOrBlank()) listOf(s)
        else expandRecurrence(s, rrule, exdates, horizonEnd)
    for (st in starts) {
      val en = st + duration
      if (en < floor) continue
      if (st > horizonEnd) continue
      out.add(Event(st, en, title, startAllDay, location))
    }
  }

  /**
   * Unfold RFC 5545 continuation lines: a CRLF (or LF) followed by a space or tab is
   * a fold of the previous logical line.
   */
  internal fun unfold(ics: String): List<String> {
    val rawLines = ics.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    val out = ArrayList<String>(rawLines.size)
    for (line in rawLines) {
      if (line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t') && out.isNotEmpty()) {
        out[out.size - 1] = out[out.size - 1] + line.substring(1)
      } else {
        out.add(line)
      }
    }
    return out
  }

  private data class Prop(val name: String, val params: Map<String, String>, val value: String)

  /** Split a content line into name, parameters, and value (best-effort). */
  private fun parseLine(line: String): Prop? {
    if (line.isBlank()) return null
    val colon = line.indexOf(':')
    if (colon < 0) return null
    val left = line.substring(0, colon)
    val value = line.substring(colon + 1)
    val parts = left.split(';')
    val name = parts[0]
    val params = HashMap<String, String>()
    for (k in 1 until parts.size) {
      val eq = parts[k].indexOf('=')
      if (eq > 0) {
        params[parts[k].substring(0, eq).uppercase(Locale.US)] =
            parts[k].substring(eq + 1).trim('"')
      }
    }
    return Prop(name, params, value)
  }

  /**
   * Parse a date/date-time property to absolute millis. Returns the instant and
   * whether it's an all-day (date-only) value. Handles:
   *  - `VALUE=DATE` or bare `yyyyMMdd` → local midnight, all-day
   *  - `yyyyMMdd'T'HHmmss'Z'` → UTC
   *  - `;TZID=Zone:yyyyMMdd'T'HHmmss` → that zone
   *  - `yyyyMMdd'T'HHmmss` → device-local (floating)
   *
   * EXDATE values can carry a comma-separated list; only the first is parsed here
   * (multiple exclusions on one line are rare and degrade safely to "not excluded").
   */
  private fun parseDt(p: Prop): Pair<Long, Boolean>? {
    val v = p.value.substringBefore(',').trim()
    if (v.isEmpty()) return null
    val dateOnly = p.params["VALUE"].equals("DATE", true) || (v.length == 8 && !v.contains('T'))
    return runCatching {
          if (dateOnly) {
            val cal = Calendar.getInstance()
            cal.clear()
            cal.set(
                v.substring(0, 4).toInt(),
                v.substring(4, 6).toInt() - 1,
                v.substring(6, 8).toInt(),
                0,
                0,
                0)
            cal.timeInMillis to true
          } else {
            val utc = v.endsWith("Z")
            val basic = v.removeSuffix("Z")
            val zone =
                when {
                  utc -> TimeZone.getTimeZone("UTC")
                  p.params["TZID"] != null -> TimeZone.getTimeZone(p.params["TZID"])
                  else -> TimeZone.getDefault()
                }
            val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
            fmt.timeZone = zone
            (fmt.parse(basic)?.time ?: return null) to false
          }
        }
        .getOrNull()
  }

  /** Resolve `\n`, `\,`, `\;`, `\\` text escapes used in SUMMARY/LOCATION. */
  internal fun unescapeText(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
      val c = s[i]
      if (c == '\\' && i + 1 < s.length) {
        when (s[i + 1]) {
          'n', 'N' -> sb.append(' ')
          ',', ';', '\\' -> sb.append(s[i + 1])
          else -> sb.append(s[i + 1])
        }
        i += 2
      } else {
        sb.append(c)
        i++
      }
    }
    return sb.toString().trim()
  }

  /**
   * Expand an RRULE into occurrence start-times up to [horizonEnd]. Supports the
   * common shapes (FREQ DAILY/WEEKLY/MONTHLY/YEARLY, INTERVAL, COUNT, UNTIL, and
   * weekly BYDAY); on anything unexpected it falls back to a single occurrence so the
   * event still shows once.
   */
  internal fun expandRecurrence(
      start: Long,
      rrule: String,
      exdates: List<Long>,
      horizonEnd: Long,
  ): List<Long> =
      runCatching {
            val rules =
                rrule
                    .split(';')
                    .mapNotNull {
                      val eq = it.indexOf('=')
                      if (eq > 0) it.substring(0, eq).uppercase(Locale.US) to it.substring(eq + 1)
                      else null
                    }
                    .toMap()
            val freq = rules["FREQ"]?.uppercase(Locale.US) ?: return@runCatching listOf(start)
            val interval = rules["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val count = rules["COUNT"]?.toIntOrNull()
            val until = rules["UNTIL"]?.let { parseUntil(it) }
            val byDay =
                rules["BYDAY"]?.split(',')?.mapNotNull { weekdayOf(it.trim()) }?.takeIf {
                  it.isNotEmpty()
                }

            val cap = 366
            val out = ArrayList<Long>()
            val exSet = exdates.toHashSet()
            val cal = Calendar.getInstance()
            cal.timeInMillis = start
            // RFC 5545: COUNT counts generated occurrences; EXDATE then removes some
            // from that set. So we track generation separately from what we keep.
            var generated = 0

            // Emit one occurrence (subject to EXDATE). Returns false once we've run
            // past UNTIL / the horizon, so the caller can stop generating.
            fun emit(t: Long): Boolean {
              if (until != null && t > until) return false
              if (t > horizonEnd) return false
              if (t !in exSet) out.add(t)
              return true
            }

            if (freq == "WEEKLY" && byDay != null) {
              // Walk week by week (INTERVAL weeks apart); within each week emit every
              // listed weekday, preserving the original time-of-day.
              val hour = cal.get(Calendar.HOUR_OF_DAY)
              val min = cal.get(Calendar.MINUTE)
              val sec = cal.get(Calendar.SECOND)
              // Move to the Sunday that starts this event's week.
              val weekStart = Calendar.getInstance()
              weekStart.timeInMillis = start
              weekStart.set(Calendar.HOUR_OF_DAY, hour)
              weekStart.set(Calendar.MINUTE, min)
              weekStart.set(Calendar.SECOND, sec)
              weekStart.set(Calendar.MILLISECOND, 0)
              weekStart.add(Calendar.DAY_OF_MONTH, -(weekStart.get(Calendar.DAY_OF_WEEK) - 1))
              var weeks = 0
              while (weeks < cap) {
                var anyInHorizon = false
                for (dow in byDay.sorted()) {
                  val occ = weekStart.clone() as Calendar
                  occ.add(Calendar.DAY_OF_MONTH, dow - 1) // dow: 1=Sun..7=Sat
                  val t = occ.timeInMillis
                  if (t < start) continue // skip days before the series actually begins
                  anyInHorizon = anyInHorizon || t <= horizonEnd
                  if (count != null && generated >= count) return@runCatching out
                  generated++
                  if (!emit(t) && until != null) return@runCatching out
                }
                if (!anyInHorizon && weekStart.timeInMillis > horizonEnd) break
                weekStart.add(Calendar.DAY_OF_MONTH, 7 * interval)
                weeks++
              }
              return@runCatching out
            }

            var iterations = 0
            while (iterations < cap) {
              if (count != null && generated >= count) break
              val t = cal.timeInMillis
              generated++
              if (!emit(t)) {
                if (t > horizonEnd && (until == null || t > until)) break
              }
              when (freq) {
                "DAILY" -> cal.add(Calendar.DAY_OF_MONTH, interval)
                "WEEKLY" -> cal.add(Calendar.DAY_OF_MONTH, 7 * interval)
                "MONTHLY" -> cal.add(Calendar.MONTH, interval)
                "YEARLY" -> cal.add(Calendar.YEAR, interval)
                else -> return@runCatching listOf(start)
              }
              iterations++
            }
            out
          }
          .getOrDefault(listOf(start))

  /** RRULE UNTIL is a date or UTC date-time; resolve to millis. */
  private fun parseUntil(v: String): Long? =
      runCatching {
            val s = v.trim()
            if (s.length == 8 && !s.contains('T')) {
              val cal = Calendar.getInstance()
              cal.clear()
              cal.set(s.substring(0, 4).toInt(), s.substring(4, 6).toInt() - 1, s.substring(6, 8).toInt(), 23, 59, 59)
              cal.timeInMillis
            } else {
              val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
              fmt.timeZone = TimeZone.getTimeZone(if (s.endsWith("Z")) "UTC" else TimeZone.getDefault().id)
              fmt.parse(s.removeSuffix("Z"))?.time
            }
          }
          .getOrNull()

  /** "MO","TU",… (optionally prefixed like "2MO") → Calendar.DAY_OF_WEEK. */
  private fun weekdayOf(token: String): Int? {
    val day = token.takeLast(2).uppercase(Locale.US)
    return when (day) {
      "SU" -> Calendar.SUNDAY
      "MO" -> Calendar.MONDAY
      "TU" -> Calendar.TUESDAY
      "WE" -> Calendar.WEDNESDAY
      "TH" -> Calendar.THURSDAY
      "FR" -> Calendar.FRIDAY
      "SA" -> Calendar.SATURDAY
      else -> null
    }
  }

  // --- windowing (pure; unit-tested) ------------------------------------------

  /**
   * Pick the events to show for a display [range], relative to [nowMillis]. For the
   * day/3-day/week ranges this is everything that overlaps the window [now → end of
   * the Nth day]; for the agenda range it's simply the next [MAX_EVENTS] events,
   * however far out. Already-finished events are dropped either way.
   */
  fun window(events: List<Event>, range: String, nowMillis: Long): List<Event> {
    val live = events.filter { it.endMillis > nowMillis }.sortedBy { it.startMillis }
    if (range == RANGE_AGENDA) return live.take(MAX_EVENTS)
    val days =
        when (range) {
          RANGE_WEEK -> 7
          RANGE_3DAY -> 3
          else -> 1
        }
    val cal = Calendar.getInstance()
    cal.timeInMillis = nowMillis
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    cal.add(Calendar.DAY_OF_MONTH, days)
    val windowEnd = cal.timeInMillis // exclusive: start of the day after the window
    return live.filter { it.startMillis < windowEnd }.take(MAX_EVENTS)
  }

  // --- net --------------------------------------------------------------------

  private fun httpGet(spec: String): String? {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 12000
    c.instanceFollowRedirects = true
    c.setRequestProperty("User-Agent", "Immortal/1.0")
    c.setRequestProperty("Accept", "text/calendar, text/plain, */*")
    return runCatching { c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
  }
}
