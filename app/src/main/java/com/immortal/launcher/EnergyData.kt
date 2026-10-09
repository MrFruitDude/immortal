/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * The dashboard's energy card data, from two places:
 *  - **The home**, through Home Assistant (Settings › Muse): the whole-home power meter (Hilo's
 *    smart meter, `sensor.meter00_power`), the per-room power sensors (Hilo thermostats report
 *    their heater's draw), Hilo's "défi" peak-event sensor and its rewards. Found by discovery,
 *    not by fixed ids: the meter is a `power` sensor whose id contains "meter" (Hilo's preferred),
 *    rooms are the other `power` sensors, the défi is `sensor.defi_hilo*`.
 *  - **The Québec grid**, through Hydro-Québec's keyless open data: total demand every 15 minutes,
 *    the production mix, and announced peak events for residential customers.
 *
 * Every call here is blocking network I/O: call it off the main thread. Parsing is pure, for tests.
 */
object EnergyData {
  private const val TAG = "ImmortalEnergy"
  private const val HQ = "https://donnees.hydroquebec.com/api/explore/v2.1/catalog/datasets/"
  private const val CACHE_TTL_MS = 10L * 60 * 1000
  private const val DAY_MS = 24L * 60 * 60 * 1000
  /** 24 hours in half-hour steps. */
  const val SPARK_POINTS = 48
  private const val MAX_ROOMS = 4

  data class Room(val name: String, val watts: Double)

  /** Hilo's défi: the sensor's state (off / scheduled / pre_heat / reduction / …) and the next window. */
  data class Defi(val state: String, val start: Long?, val end: Long?)

  data class Home(
      /** Whole-home power in watts, or null when no meter sensor was found. */
      val watts: Double?,
      /** The meter's last 24 hours in watts, oldest first ([SPARK_POINTS] values), or empty. */
      val history: List<Double>,
      val rooms: List<Room>,
      val defi: Defi?,
      /** Hilo rewards so far (dollars), or null. */
      val rewards: Double?,
  )

  enum class Source(val label: String) {
    HYDRO("Hydro"),
    WIND("Wind"),
    SOLAR("Solar"),
    OTHER("Other"),
  }

  data class MixSlice(val source: Source, val mw: Double, val fraction: Double)

  /** An announced Hydro-Québec peak window for residential customers (Flex D / winter credits). */
  data class PeakEvent(val start: Long, val end: Long, val offers: List<String>)

  data class Grid(
      /** Latest total Québec demand in MW. */
      val demandMw: Double?,
      /** The last 24 hours of demand in MW, oldest first (15-minute steps). */
      val demand: List<Double>,
      val mix: List<MixSlice>,
      val peak: PeakEvent?,
  )

  data class Snapshot(val haConfigured: Boolean, val home: Home?, val grid: Grid?)

  /** The last snapshot, so a card that's recreated (or moved) shows data at once. */
  @Volatile
  var last: Snapshot? = null
    private set

  /** Reads both sources; keeps the previous value of a source whose read failed. */
  fun load(c: Context): Snapshot {
    val prev = last
    val ha = MuseHomeAssistant.configured(c)
    val home =
        if (!ha) null
        else
            runCatching { loadHome(c, System.currentTimeMillis()) }
                .onFailure { Log.w(TAG, "home: ${it.message}") }
                .getOrNull() ?: prev?.home
    val grid =
        runCatching { loadGrid(System.currentTimeMillis()) }.onFailure { Log.w(TAG, "grid: ${it.message}") }.getOrNull()
            ?: prev?.grid
    return Snapshot(ha, home, grid).also { last = it }
  }

  // --- home (Home Assistant) ----------------------------------------------------

  private fun loadHome(c: Context, now: Long): Home? {
    val entities = MuseHomeAssistant.states(c, "sensor", null, 2000).getJSONArray("entities")
    val meter = findMeter(entities)
    val home = parseHome(entities, now) ?: return null
    val history =
        meter?.let { m ->
          runCatching { meterHistory(c, m.optString("entity_id"), unitFactor(m.optString("unit_of_measurement")), now) }
              .onFailure { Log.w(TAG, "history: ${it.message}") }
              .getOrNull()
        }
    return home.copy(history = history.orEmpty())
  }

  @Volatile private var historyCache: Triple<String, Long, List<Double>>? = null

  /** The meter's last 24 h, downsampled; cached 10 minutes (a meter can report every few seconds). */
  private fun meterHistory(c: Context, entityId: String, factor: Double, now: Long): List<Double> {
    historyCache?.let { (id, at, v) -> if (id == entityId && now - at < CACHE_TTL_MS) return v }
    val start = now - DAY_MS
    val points = parseHistory(MuseHomeAssistant.history(c, entityId, utcIso(start))).map { it.first to it.second * factor }
    return downsample(points, start, now, SPARK_POINTS).also { historyCache = Triple(entityId, now, it) }
  }

  /**
   * Everything the card shows about the home from the compact `/api/states` entities (the shape
   * [MuseHomeAssistant.states] returns). Null when there's nothing energy-related at all.
   */
  internal fun parseHome(entities: JSONArray, now: Long): Home? {
    val all = (0 until entities.length()).mapNotNull { entities.optJSONObject(it) }
    val meter = findMeter(entities)
    val meterId = meter?.optString("entity_id")
    val rooms =
        all.filter { isPowerSensor(it) && it.optString("entity_id") != meterId && !it.optString("entity_id").contains("meter") }
            .mapNotNull { e ->
              val w = numeric(e) ?: return@mapNotNull null
              Room(roomName(e.optString("name"), e.optString("entity_id")), w * unitFactor(e.optString("unit_of_measurement")))
            }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .take(MAX_ROOMS)
    val defi = all.firstOrNull { it.optString("entity_id").startsWith("sensor.defi_hilo") }?.let { parseDefi(it, now) }
    val rewards =
        all.firstOrNull {
              val id = it.optString("entity_id")
              id.startsWith("sensor.recompenses_hilo") || id.startsWith("sensor.rewards_hilo")
            }
            ?.let { numeric(it) }
    val watts = meter?.let { m -> numeric(m)?.let { it * unitFactor(m.optString("unit_of_measurement")) } }
    if (watts == null && rooms.isEmpty() && defi == null && rewards == null) return null
    return Home(watts = watts, history = emptyList(), rooms = rooms, defi = defi, rewards = rewards)
  }

  /** The whole-home meter: a power sensor whose id contains "meter", Hilo's first. */
  internal fun findMeter(entities: JSONArray): JSONObject? {
    val candidates =
        (0 until entities.length())
            .mapNotNull { entities.optJSONObject(it) }
            .filter { isPowerSensor(it) && it.optString("entity_id").contains("meter") && numeric(it) != null }
    return candidates.firstOrNull { (it.optString("entity_id") + " " + it.optString("name")).contains("hilo", true) }
        ?: candidates.firstOrNull()
  }

  private fun isPowerSensor(e: JSONObject) =
      e.optString("entity_id").startsWith("sensor.") && e.optString("device_class") == "power"

  private fun numeric(e: JSONObject): Double? = e.optString("state").toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }

  /** Watts per reported unit. */
  internal fun unitFactor(unit: String): Double =
      when (unit.trim()) {
        "kW" -> 1_000.0
        "MW" -> 1_000_000.0
        else -> 1.0
      }

  /** "Kitchen Power" → "Kitchen"; `sensor.living_room_power` without a name → "Living room". */
  internal fun roomName(name: String, entityId: String): String {
    val base = name.ifBlank { entityId.substringAfter('.').replace('_', ' ') }.trim()
    val trimmed = base.replace(Regex("(?i)[\\s_-]*(power|puissance|consommation|consumption)$"), "").trim()
    return trimmed.ifBlank { base }.replaceFirstChar { it.uppercase() }
  }

  /**
   * The défi sensor: its state plus the next window from `next_events`. Hilo's integration lists
   * events with their times in a nested `phases` object (`reduction_start` / `reduction_end`, …);
   * older or other shapes put `start` / `end` at the top level, and some serialize the list as a
   * string — all accepted. Null when nothing is scheduled or running.
   */
  internal fun parseDefi(e: JSONObject, now: Long): Defi? {
    val state = e.optString("state").trim().lowercase(Locale.ROOT)
    val events =
        when (val raw = e.opt("next_events")) {
          is JSONArray -> raw
          is String -> runCatching { JSONArray(raw) }.getOrNull()
          else -> null
        }
    val next =
        events
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
            .orEmpty()
            .mapNotNull { eventWindow(it) }
            .filter { it.state != "completed" && (it.end ?: it.start ?: 0L) > now }
            .minByOrNull { it.start ?: Long.MAX_VALUE }
    if (next != null) return Defi(state.ifBlank { next.state }, next.start, next.end)
    return if (state in DEFI_IDLE) null else Defi(state, null, null)
  }

  private val DEFI_IDLE = setOf("", "off", "unknown", "unavailable", "idle", "completed", "none")
  private val START_KEYS = listOf("reduction_start", "start", "start_time", "starts_at", "startDateUTC", "start_date")
  private val END_KEYS = listOf("reduction_end", "end", "end_time", "ends_at", "endDateUTC", "end_date")

  private fun eventWindow(ev: JSONObject): Defi? {
    val phases = ev.optJSONObject("phases")
    fun find(keys: List<String>): Long? =
        keys.firstNotNullOfOrNull { k -> (phases?.optString(k)?.takeIf { it.isNotBlank() } ?: ev.optString(k)).let { parseIso(it) } }
    val start = find(START_KEYS)
    val end = find(END_KEYS)
    if (start == null && end == null) return null
    return Defi(ev.optString("state").trim().lowercase(Locale.ROOT), start, end)
  }

  /** `[[{state, last_changed}, …]]` → (millis, value) for the numeric states, oldest first. */
  internal fun parseHistory(json: String): List<Pair<Long, Double>> {
    val outer = JSONArray(json)
    val out = ArrayList<Pair<Long, Double>>()
    for (i in 0 until outer.length()) {
      val series = outer.optJSONArray(i) ?: continue
      for (j in 0 until series.length()) {
        val p = series.optJSONObject(j) ?: continue
        val v = p.optString("state").toDoubleOrNull()?.takeIf { !it.isNaN() } ?: continue
        val t = parseIso(p.optString("last_changed").ifBlank { p.optString("last_updated") }) ?: continue
        out += t to v
      }
    }
    return out.sortedBy { it.first }
  }

  /**
   * A step series (each value holds until the next) as [n] time-weighted averages over
   * [start]..[end]. Before the first point the first value is assumed. Empty when there's no data.
   */
  internal fun downsample(points: List<Pair<Long, Double>>, start: Long, end: Long, n: Int): List<Double> {
    if (points.isEmpty() || end <= start || n <= 0) return emptyList()
    val step = (end - start).toDouble() / n
    var idx = 0
    var cur = points[0].second
    while (idx < points.size && points[idx].first <= start) cur = points[idx++].second
    val out = ArrayList<Double>(n)
    for (b in 0 until n) {
      val b1 = start + (b + 1) * step
      var t = start + b * step
      var acc = 0.0
      while (idx < points.size && points[idx].first < b1) {
        val pt = points[idx].first.toDouble()
        acc += cur * (pt - t)
        t = pt
        cur = points[idx++].second
      }
      acc += cur * (b1 - t)
      out += acc / step
    }
    return out
  }

  // --- the Québec grid (Hydro-Québec open data) -------------------------------------

  private fun loadGrid(now: Long): Grid {
    val demand =
        runCatching {
              parseDemand(
                  hqGet(
                      "demande-electricite-quebec/records?" +
                          query("order_by" to "date desc", "where" to "valeurs_demandetotal is not null", "limit" to "96")))
            }
            .onFailure { Log.w(TAG, "demand: ${it.message}") }
            .getOrNull()
    val mix =
        runCatching {
              parseProduction(
                  hqGet(
                      "production-electricite-quebec/records?" +
                          query("order_by" to "date desc", "where" to "valeurs_total > 0", "limit" to "1")))
            }
            .onFailure { Log.w(TAG, "production: ${it.message}") }
            .getOrNull()
    // Only events that haven't ended; the sector is filtered here rather than server-side (see parsePeaks).
    val peak =
        runCatching {
              parsePeaks(
                  hqGet(
                      "evenements-pointe/records?" +
                          query("where" to "datefin >= now()", "order_by" to "datedebut asc", "limit" to "50")),
                  now)
            }
            .onFailure { Log.w(TAG, "peaks: ${it.message}") }
            .getOrNull()
    if (demand == null && mix == null) throw IllegalStateException("Hydro-Québec open data unavailable")
    return Grid(demandMw = demand?.lastOrNull(), demand = demand.orEmpty(), mix = mix.orEmpty(), peak = peak)
  }

  /** Demand records (newest first, future slots null) → MW values, oldest first. */
  internal fun parseDemand(json: String): List<Double> {
    val r = JSONObject(json).optJSONArray("results") ?: return emptyList()
    return (0 until r.length())
        .mapNotNull { r.optJSONObject(it) }
        .mapNotNull { o ->
          if (o.isNull("valeurs_demandetotal")) return@mapNotNull null
          val t = parseIso(o.optString("date")) ?: return@mapNotNull null
          val v = o.optDouble("valeurs_demandetotal").takeIf { !it.isNaN() } ?: return@mapNotNull null
          t to v
        }
        .sortedBy { it.first }
        .map { it.second }
  }

  /** The latest production record → hydro / wind / solar / other (other includes thermal). */
  internal fun parseProduction(json: String): List<MixSlice> {
    val o = JSONObject(json).optJSONArray("results")?.optJSONObject(0) ?: return emptyList()
    fun mw(k: String) = if (o.isNull(k)) 0.0 else o.optDouble(k).takeIf { !it.isNaN() && it > 0 } ?: 0.0
    val parts =
        listOf(
            Source.HYDRO to mw("valeurs_hydraulique"),
            Source.WIND to mw("valeurs_eolien"),
            Source.SOLAR to mw("valeurs_solaire"),
            Source.OTHER to mw("valeurs_autres") + mw("valeurs_thermique"),
        )
    val total = parts.sumOf { it.second }
    if (total <= 0) return emptyList()
    return parts.map { (s, v) -> MixSlice(s, v, v / total) }
  }

  /**
   * The next (or current) residential peak event. Hydro-Québec lists every program's events;
   * the residential ones are `secteurclient` "Residentiel" — today the offers CPC-D (winter credit
   * option, rate D) and TPC-DPC (Flex D), which usually share a window but not always. The sector
   * is matched loosely (accents, case) with the two offer codes as a fallback, and identical
   * windows are merged.
   */
  internal fun parsePeaks(json: String, now: Long): PeakEvent? {
    val r = JSONObject(json).optJSONArray("results") ?: return null
    return (0 until r.length())
        .mapNotNull { r.optJSONObject(it) }
        .filter { isResidential(it.optString("secteurclient"), it.optString("offre")) }
        .mapNotNull { o ->
          val s = parseIso(o.optString("datedebut")) ?: return@mapNotNull null
          val e = parseIso(o.optString("datefin")) ?: return@mapNotNull null
          Triple(s, e, o.optString("offre"))
        }
        .filter { it.second > now }
        .groupBy { it.first to it.second }
        .map { (w, list) -> PeakEvent(w.first, w.second, list.map { it.third }.filter { it.isNotBlank() }.distinct().sorted()) }
        .sortedWith(compareBy({ it.start }, { -it.end }))
        .firstOrNull()
  }

  private val RESIDENTIAL_OFFERS = setOf("CPC-D", "TPC-DPC")

  internal fun isResidential(sector: String, offer: String): Boolean =
      fold(sector).startsWith("resid") || offer.trim().uppercase(Locale.ROOT) in RESIDENTIAL_OFFERS

  private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").trim().lowercase(Locale.ROOT)

  private fun query(vararg kv: Pair<String, String>) =
      kv.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20") }

  private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

  /** GET with a 10-minute cache of successful answers (the card polls every 5). */
  private fun hqGet(path: String): String {
    val url = HQ + path
    val now = System.currentTimeMillis()
    cache[url]?.let { (at, body) -> if (now - at < CACHE_TTL_MS) return body }
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
      conn.connectTimeout = 8_000
      conn.readTimeout = 12_000
      conn.setRequestProperty("User-Agent", "Immortal/1.0")
      conn.setRequestProperty("Accept", "application/json")
      val code = conn.responseCode
      if (code !in 200..299) throw IllegalStateException("Hydro-Québec answered HTTP $code")
      val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
      cache[url] = now to body
      return body
    } finally {
      conn.disconnect()
    }
  }

  // --- time and formatting (pure) ---------------------------------------------------

  private val ISO =
      Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?\s*(Z|[+-]\d{2}:?\d{2})?$""")

  /**
   * ISO-8601 → epoch millis: `2026-10-09T22:15:00+00:00`, `…Z`, Home Assistant's microsecond
   * `2026-10-09T12:00:01.123456+00:00`, or a space instead of `T`. No offset means local time.
   */
  internal fun parseIso(s: String?): Long? {
    val m = ISO.matchEntire(s?.trim() ?: return null) ?: return null
    val g = m.groupValues
    val off = g[8]
    val tz =
        when {
          off.isEmpty() -> TimeZone.getDefault()
          off == "Z" -> TimeZone.getTimeZone("UTC")
          else -> TimeZone.getTimeZone("GMT" + off.substring(0, 3) + ":" + off.takeLast(2))
        }
    val cal = Calendar.getInstance(tz)
    cal.clear()
    cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
    val ms = g[7].take(3).padEnd(3, '0').takeIf { g[7].isNotEmpty() }?.toInt() ?: 0
    return cal.timeInMillis + ms
  }

  internal fun utcIso(ms: Long): String =
      SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

  /** "435 W", "1.3 kW". */
  internal fun formatWatts(w: Double, locale: Locale): String =
      if (Math.abs(w) < 999.5) "${Math.round(w)} W" else String.format(locale, "%.1f kW", w / 1000)

  /** "19.9 GW", "850 MW". */
  internal fun formatMw(mw: Double, locale: Locale): String =
      if (mw >= 999.5) String.format(locale, "%.1f GW", mw / 1000) else "${Math.round(mw)} MW"

  /**
   * "today 6–9 PM", "tomorrow 6–10 AM", "Mon 6–9 AM" (this week), "Mar 18 6–10 AM" (later);
   * 24-hour clocks get "06:00–10:00". Everything in [tz].
   */
  internal fun windowLabel(start: Long, end: Long?, now: Long, tz: TimeZone, locale: Locale, use24h: Boolean): String {
    val days = localDay(start, tz) - localDay(now, tz)
    val day =
        when (days) {
          0L -> "today"
          1L -> "tomorrow"
          in 2L..6L -> SimpleDateFormat("EEE", locale).apply { timeZone = tz }.format(Date(start))
          else -> SimpleDateFormat("MMM d", locale).apply { timeZone = tz }.format(Date(start))
        }
    return "$day ${timeRange(start, end, tz, locale, use24h)}"
  }

  private fun localDay(ms: Long, tz: TimeZone) = Math.floorDiv(ms + tz.getOffset(ms), DAY_MS)

  private fun timeRange(start: Long, end: Long?, tz: TimeZone, locale: Locale, use24h: Boolean): String {
    if (use24h) {
      val f = SimpleDateFormat("HH:mm", locale).apply { timeZone = tz }
      return if (end == null) f.format(Date(start)) else "${f.format(Date(start))}–${f.format(Date(end))}"
    }
    fun parts(ms: Long): Pair<String, String> {
      val c = Calendar.getInstance(tz, locale).apply { timeInMillis = ms }
      val h = c.get(Calendar.HOUR).let { if (it == 0) 12 else it }
      val min = c.get(Calendar.MINUTE)
      return (if (min == 0) "$h" else String.format(Locale.US, "%d:%02d", h, min)) to (if (c.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM")
    }
    val (s, sp) = parts(start)
    if (end == null) return "$s $sp"
    val (e, ep) = parts(end)
    return if (sp == ep) "$s–$e $ep" else "$s $sp–$e $ep"
  }

  /** The défi chip: "Défi in progress", "Défi Hilo tomorrow 6–9 AM", … or null for none. */
  internal fun defiLabel(d: Defi, now: Long, tz: TimeZone, locale: Locale, use24h: Boolean): String? {
    val start = d.start
    val end = d.end
    if (start != null && end != null && now in start until end) return "Défi in progress"
    if (d.state == "reduction") return "Défi in progress"
    if (start != null && start > now) return "Défi Hilo " + windowLabel(start, end, now, tz, locale, use24h)
    return when (d.state) {
      "pre_heat", "preheat" -> "Défi Hilo · pre-heating"
      "recovery" -> "Défi Hilo · recovery"
      in DEFI_IDLE -> null
      else -> "Défi Hilo scheduled"
    }
  }

  /** The grid chip: "Peak event now", "Peak event tomorrow 6–10 AM". */
  internal fun peakLabel(p: PeakEvent, now: Long, tz: TimeZone, locale: Locale, use24h: Boolean): String =
      if (now >= p.start) "Peak event until " + timeRange(p.end, null, tz, locale, use24h)
      else "Peak event " + windowLabel(p.start, p.end, now, tz, locale, use24h)
}
