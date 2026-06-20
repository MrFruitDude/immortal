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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.exifinterface.media.ExifInterface
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import org.json.JSONObject

/**
 * The photo-frame UI + update logic, decoupled from any host. Used by both
 * [PhotoDreamService] (the real screensaver) and [PhotoFramePreviewActivity]
 * (an in-app preview you can launch on demand). Reproduces the stock Portal
 * idle screen: full-screen rotating media with a clock / battery / date /
 * weather cluster bottom-left.
 *
 * Source is configurable via [ScreensaverConfig]:
 *  - **default** — the built-in keyless photo feed (Lorem Picsum, or Unsplash if a
 *    key is supplied). This is also the fallback whenever any other source is unset,
 *    empty, or unreachable (e.g. a USB drive was unplugged, a shared album was
 *    unshared), so the frame is never blank.
 *  - **folder** — photos and videos from a folder the user picked (internal, SD, or
 *    a USB-C drive), read through the Storage Access Framework.
 *  - **url** — a public share link to an iCloud Shared Album or a Google Photos
 *    shared album. Fetched once on start; the screensaver rotates through the
 *    returned direct image URLs and silently falls back to the default feed if the
 *    album can't be resolved.
 *
 * Weather is Open-Meteo + IP geolocation (both keyless). All network is best-effort.
 */
class PhotoFrameController(
    private val context: Context,
    private val unsplashKey: String = "",
    private val unsplashQuery: String = "nature,landscape,scenic",
    private val weatherRefreshMs: Long = 30 * 60_000L,
    private val calendarRefreshMs: Long = 30 * 60_000L,
) {
  private val io = Executors.newSingleThreadExecutor()
  private val ui = Handler(Looper.getMainLooper())

  private lateinit var photo: ImageView
  private lateinit var videoView: VideoView
  private lateinit var clock: TextView
  private lateinit var battery: TextView
  private lateinit var date: TextView
  private lateinit var weather: TextView
  private lateinit var batteryDot: View
  private lateinit var weatherDot: View
  private var weatherText: String = ""

  // Now-playing card (driven by the device's media session; hidden when nothing plays).
  private lateinit var nowPlayingCard: LinearLayout
  private lateinit var npArt: ImageView
  private lateinit var npTitle: TextView
  private lateinit var npArtist: TextView
  private var lastArtUrl: String = ""
  private var lastArtBitmap: Bitmap? = null
  private var npListener: NowPlayingHub.Listener? = null

  // Calendar widget (top-right): a clean upcoming-events panel fed by a public ICS
  // feed (Google secret-iCal / Apple iCloud public-calendar). Hidden when no link.
  private lateinit var calendarPanel: LinearLayout
  private lateinit var calendarHeader: TextView
  private lateinit var calendarRows: LinearLayout
  private var calendarEvents: List<CalendarFeed.Event> = emptyList()
  // The minute we last re-windowed the calendar, so the 1s tick only rebuilds it
  // when the clock minute changes (drops past events, refreshes Today/Tomorrow).
  private var lastCalMinute: Long = -1L

  private var settings = ScreensaverConfig.Settings()

  // Local-folder playback state.
  private var localMode = false
  private var playlist: List<MediaItem> = emptyList()
  private var localIndex = -1
  // Bumped on every advance so a slow image-decode or an old video's completion
  // callback can tell it's been superseded and bow out.
  private var gen = 0

  // Remote-album playback state (iCloud / Google Photos shared albums).
  private var remoteMode = false
  private var remoteUrls: List<String> = emptyList()
  private var remoteIndex = -1
  private var remoteFailStreak = 0

  // Web-feed history so swipes can go back as well as forward.
  private val history = ArrayList<Bitmap>()
  private var index = -1

  /** Host (dream / preview activity) sets this to dismiss the frame on tap. */
  var onExit: (() -> Unit)? = null

  // Deterministic gesture from raw down/up deltas (robust to synthetic input
  // that omits MOVE events): clear horizontal swipe = prev/next, clear tap = exit.
  private var downX = 0f
  private var downY = 0f

  /** Hosts forward their touch events here. */
  fun onTouch(ev: MotionEvent) {
    when (ev.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        downX = ev.x
        downY = ev.y
      }
      MotionEvent.ACTION_UP -> {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dx) > 120 && abs(dx) > abs(dy) * 1.5f) {
          if (dx < 0) next() else prev()
        } else if (abs(dx) < 48 && abs(dy) < 48) {
          onExit?.invoke()
        }
      }
    }
  }

  val view: View by lazy { buildUi() }

  fun start() {
    settings = ScreensaverConfig.load(context)
    applyFit()
    tick.run()
    refreshWeather.run()
    refreshCalendar.run()
    // Listen for now-playing updates (replays the current track immediately).
    npListener = NowPlayingHub.Listener { state -> ui.post { updateNowPlaying(state) } }
    NowPlayingHub.addListener(npListener!!)
    when {
      settings.usesFolder -> {
        val path = settings.folderPath
        if (path.isNullOrBlank()) {
          startWeb()
          return
        }
        io.execute {
          val list =
              if (LocalMedia.isAccessible(path)) LocalMedia.enumerate(path, settings.includeVideo)
              else emptyList()
          ui.post {
            if (list.isNotEmpty()) {
              playlist = if (settings.shuffle) list.shuffled() else list
              localMode = true
              localIndex = -1
              advanceLocal(+1)
            } else {
              // Folder empty / unreachable → never leave the frame blank.
              startWeb()
            }
          }
        }
      }
      settings.usesUrl -> {
        val shareUrl = settings.albumUrl
        if (shareUrl.isNullOrBlank()) {
          startWeb()
          return
        }
        val m = context.resources.displayMetrics
        io.execute {
          val album = RemoteAlbum.fetch(shareUrl, m.widthPixels, m.heightPixels)
          val urls = album?.photoUrls.orEmpty()
          ui.post {
            if (urls.isNotEmpty()) {
              remoteUrls = if (settings.shuffle) urls.shuffled() else urls
              remoteMode = true
              remoteIndex = -1
              remoteFailStreak = 0
              advanceRemote(+1)
              scheduleRemoteRefresh()
            } else {
              // Album unshared / unreachable → never leave the frame blank.
              startWeb()
            }
          }
        }
      }
      else -> startWeb()
    }
  }

  fun stop() {
    ui.removeCallbacksAndMessages(null)
    npListener?.let { NowPlayingHub.removeListener(it) }
    npListener = null
    if (this::videoView.isInitialized) runCatching { videoView.stopPlayback() }
    io.shutdownNow()
  }

  // --- UI ---------------------------------------------------------------------
  private fun buildUi(): View {
    val root = FrameLayout(context)
    root.setBackgroundColor(Color.BLACK)

    photo = ImageView(context)
    photo.scaleType = ImageView.ScaleType.CENTER_CROP
    root.addView(photo, FrameLayout.LayoutParams(MATCH, MATCH))

    videoView = VideoView(context)
    videoView.visibility = View.GONE
    root.addView(videoView, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))

    val scrim = View(context)
    scrim.background =
        GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xCC000000.toInt(), 0x00000000),
        )
    root.addView(scrim, FrameLayout.LayoutParams(MATCH, dp(320), Gravity.BOTTOM))

    val col = LinearLayout(context)
    col.orientation = LinearLayout.VERTICAL
    val colLp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START)
    colLp.setMargins(dp(40), 0, 0, dp(40))
    root.addView(col, colLp)

    clock = text(96f, Color.WHITE, true)
    col.addView(clock)

    val row = LinearLayout(context)
    row.gravity = Gravity.CENTER_VERTICAL
    // Date is always present; battery and weather are optional. Each optional
    // field owns the divider that precedes it, so the dot disappears with the
    // field (no orphaned "•" when a battery-less Portal reports no charge).
    date = text(22f, Color.WHITE, false)
    battery = text(22f, Color.WHITE, false)
    weather = text(22f, Color.WHITE, false)
    batteryDot = divider()
    weatherDot = divider()
    row.addView(date)
    row.addView(batteryDot)
    row.addView(battery)
    row.addView(weatherDot)
    row.addView(weather)
    col.addView(row)

    buildNowPlaying(root)
    buildCalendar(root)
    return root
  }

  /** A now-playing card bottom-right (album art + track / artist), over the scrim.
   *  Hidden until something is playing. */
  private fun buildNowPlaying(root: FrameLayout) {
    nowPlayingCard = LinearLayout(context)
    nowPlayingCard.orientation = LinearLayout.HORIZONTAL
    nowPlayingCard.gravity = Gravity.CENTER_VERTICAL
    nowPlayingCard.visibility = View.GONE
    val lp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END)
    lp.setMargins(0, 0, dp(40), dp(44))
    root.addView(nowPlayingCard, lp)

    // Text first, art second: the cover sits at the right edge and the title/artist
    // hug it (right-aligned), so there's no dead horizontal space on a bottom-right card.
    val npCol = LinearLayout(context)
    npCol.orientation = LinearLayout.VERTICAL
    npCol.gravity = Gravity.END
    nowPlayingCard.addView(npCol, LinearLayout.LayoutParams(WRAP, WRAP))

    // Explicit WRAP params: a vertical LinearLayout otherwise defaults children to
    // MATCH_PARENT width, which pins the column to its first width and re-truncates
    // longer titles when the track changes.
    npTitle = text(26f, Color.WHITE, false)
    npTitle.typeface = Typeface.DEFAULT_BOLD
    npTitle.maxLines = 1
    npTitle.ellipsize = TextUtils.TruncateAt.END
    npTitle.gravity = Gravity.END
    npTitle.maxWidth = dp(560)
    npCol.addView(npTitle, LinearLayout.LayoutParams(WRAP, WRAP))

    npArtist = text(18f, 0xCCFFFFFF.toInt(), false)
    npArtist.maxLines = 1
    npArtist.ellipsize = TextUtils.TruncateAt.END
    npArtist.gravity = Gravity.END
    npArtist.maxWidth = dp(560)
    npCol.addView(npArtist, LinearLayout.LayoutParams(WRAP, WRAP))

    npArt = ImageView(context)
    npArt.scaleType = ImageView.ScaleType.CENTER_CROP
    npArt.clipToOutline = true
    npArt.outlineProvider =
        object : ViewOutlineProvider() {
          override fun getOutline(v: View, o: Outline) {
            o.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat())
          }
        }
    val artLp = LinearLayout.LayoutParams(dp(72), dp(72))
    artLp.setMarginStart(dp(16))
    nowPlayingCard.addView(npArt, artLp)
  }

  /** Reflect the latest now-playing state (called on the main thread). */
  private fun updateNowPlaying(s: NowPlayingState?) {
    if (s == null || !s.active || !settings.showNowPlaying) {
      nowPlayingCard.visibility = View.GONE
      lastArtUrl = ""
      lastArtBitmap = null
      return
    }
    nowPlayingCard.visibility = View.VISIBLE
    npTitle.text = s.title
    npArtist.text = s.artist
    npArtist.visibility = if (s.artist.isBlank()) View.GONE else View.VISIBLE
    val bitmap = s.artBitmap
    if (bitmap != null) {
      // Native art is a ready, downscaled bitmap — set it directly (no decode/network).
      if (bitmap !== lastArtBitmap) {
        lastArtBitmap = bitmap
        lastArtUrl = ""
        npArt.visibility = View.VISIBLE
        npArt.setImageBitmap(bitmap)
      }
    } else if (s.artUrl != lastArtUrl) {
      // Fallback for URI-only metadata (no embedded bitmap): download/decode off-thread.
      lastArtBitmap = null
      lastArtUrl = s.artUrl
      npArt.setImageBitmap(null)
      val want = s.artUrl
      npArt.visibility = if (want.isNotBlank()) View.VISIBLE else View.GONE
      if (want.isNotBlank())
          io.execute {
            val bmp = runCatching { downloadBitmap(want) }.getOrNull()
            ui.post { if (lastArtUrl == want) npArt.setImageBitmap(bmp) }
          }
    }
  }

  /** A clean upcoming-events panel top-right, over the photo. Its own translucent
   *  rounded backing keeps it legible without a full-screen scrim. Hidden until a
   *  calendar link is set and there's something to show. */
  private fun buildCalendar(root: FrameLayout) {
    calendarPanel = LinearLayout(context)
    calendarPanel.orientation = LinearLayout.VERTICAL
    calendarPanel.visibility = View.GONE
    val bg = GradientDrawable()
    bg.setColor(0x66000000)
    bg.cornerRadius = dp(18).toFloat()
    calendarPanel.background = bg
    calendarPanel.setPadding(dp(22), dp(18), dp(22), dp(18))
    val lp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END)
    lp.setMargins(0, dp(40), dp(40), 0)
    root.addView(calendarPanel, lp)

    calendarHeader = text(15f, 0xCCFFFFFF.toInt(), false)
    calendarHeader.typeface = Typeface.DEFAULT_BOLD
    calendarHeader.letterSpacing = 0.08f
    calendarPanel.addView(calendarHeader)

    calendarRows = LinearLayout(context)
    calendarRows.orientation = LinearLayout.VERTICAL
    val rowsLp = LinearLayout.LayoutParams(WRAP, WRAP)
    rowsLp.topMargin = dp(10)
    calendarPanel.addView(calendarRows, rowsLp)
  }

  /** Rebuild the calendar panel from the cached events for the chosen range. Cheap
   *  (no network) — called when a fetch lands and once per minute from [tick]. */
  private fun renderCalendar() {
    if (!this::calendarPanel.isInitialized) return
    if (!settings.usesCalendar) {
      calendarPanel.visibility = View.GONE
      return
    }
    val now = System.currentTimeMillis()
    val shown = CalendarFeed.window(calendarEvents, settings.calendarRange, now)
    calendarHeader.text = rangeLabel(settings.calendarRange).uppercase(Locale.getDefault())
    calendarRows.removeAllViews()
    calendarPanel.visibility = View.VISIBLE

    if (shown.isEmpty()) {
      val empty = text(17f, 0xCCFFFFFF.toInt(), false)
      empty.text = "Nothing scheduled"
      calendarRows.addView(empty)
      return
    }

    val agenda = settings.calendarRange == CalendarFeed.RANGE_AGENDA
    var lastDayKey = ""
    for (ev in shown) {
      // Day header whenever the day changes (and always in agenda mode, which spans
      // arbitrary dates). A single-day range needs no per-day header.
      val key = dayKey(ev.startMillis)
      if (key != lastDayKey && (agenda || settings.calendarRange != CalendarFeed.RANGE_DAY)) {
        val header = text(13f, 0x99FFFFFF.toInt(), false)
        header.typeface = Typeface.DEFAULT_BOLD
        header.text = dayHeader(ev.startMillis).uppercase(Locale.getDefault())
        val hlp = LinearLayout.LayoutParams(WRAP, WRAP)
        hlp.topMargin = if (calendarRows.childCount == 0) 0 else dp(12)
        calendarRows.addView(header, hlp)
      }
      lastDayKey = key
      calendarRows.addView(buildEventRow(ev))
    }
  }

  private fun buildEventRow(ev: CalendarFeed.Event): View {
    val row = LinearLayout(context)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    val rowLp = LinearLayout.LayoutParams(WRAP, WRAP)
    rowLp.topMargin = dp(6)

    val time = text(17f, Color.WHITE, false)
    time.text = if (ev.allDay) "All day" else timeLabel(ev.startMillis)
    time.width = dp(96)

    val title = text(17f, Color.WHITE, false)
    title.typeface = Typeface.DEFAULT_BOLD
    title.text = ev.title
    title.maxLines = 1
    title.ellipsize = TextUtils.TruncateAt.END
    title.maxWidth = dp(360)

    row.addView(time)
    row.addView(title)
    row.layoutParams = rowLp
    return row
  }

  private fun rangeLabel(range: String): String =
      when (range) {
        CalendarFeed.RANGE_3DAY -> "Next 3 days"
        CalendarFeed.RANGE_WEEK -> "This week"
        CalendarFeed.RANGE_AGENDA -> "Upcoming"
        else -> "Today"
      }

  private fun timeLabel(millis: Long): String {
    val pattern = if (ImmortalSettings.use24HourClock(context)) "H:mm" else "h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
  }

  /** A stable per-day key (yyyyDDD) so the renderer can tell when the day changes. */
  private fun dayKey(millis: Long): String =
      SimpleDateFormat("yyyyDDD", Locale.US).format(Date(millis))

  /** "Today" / "Tomorrow" / "Sat, Jun 21" relative to now. */
  private fun dayHeader(millis: Long): String {
    val today = dayKey(System.currentTimeMillis())
    val tomorrow = dayKey(System.currentTimeMillis() + 24L * 60 * 60 * 1000)
    return when (dayKey(millis)) {
      today -> "Today"
      tomorrow -> "Tomorrow"
      else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(millis))
    }
  }

  private fun applyFit() {    // Video letterboxes either way (VideoView limitation); images honour the choice.
    photo.scaleType =
        if (settings.fit == ScreensaverConfig.FIT_FIT) ImageView.ScaleType.FIT_CENTER
        else ImageView.ScaleType.CENTER_CROP
  }

  private fun text(sizeSp: Float, color: Int, light: Boolean): TextView {
    val t = TextView(context)
    t.textSize = sizeSp
    t.setTextColor(color)
    if (light) t.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    t.setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
    return t
  }

  private fun divider(): View {
    val v = TextView(context)
    v.text = "   •   "
    v.textSize = 22f
    v.setTextColor(0x88FFFFFF.toInt())
    return v
  }

  private fun intervalMs(): Long = settings.intervalSec * 1000L

  // --- periodic loops ---------------------------------------------------------
  private val tick =
      object : Runnable {
        override fun run() {
          val now = Date()
          val clockPattern = if (ImmortalSettings.use24HourClock(context)) "H:mm" else "h:mm"
          clock.text = SimpleDateFormat(clockPattern, Locale.getDefault()).format(now)
          date.text = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now)
          val pct = batteryPct()
          val hasBattery = pct >= 0
          battery.text = if (hasBattery) "$pct%" else ""
          battery.visibility = if (hasBattery) View.VISIBLE else View.GONE
          batteryDot.visibility = if (hasBattery) View.VISIBLE else View.GONE
          val hasWeather = weatherText.isNotBlank()
          weather.text = weatherText
          weather.visibility = if (hasWeather) View.VISIBLE else View.GONE
          weatherDot.visibility = if (hasWeather) View.VISIBLE else View.GONE
          // Re-window the calendar once per minute (cheap, no network): drops events
          // as they pass and rolls the Today/Tomorrow labels over at midnight.
          val minute = now.time / 60_000L
          if (minute != lastCalMinute) {
            lastCalMinute = minute
            renderCalendar()
          }
          ui.postDelayed(this, 1_000L)
        }
      }

  private val rotate =
      object : Runnable {
        override fun run() {
          webNext()
          ui.postDelayed(this, intervalMs())
        }
      }

  private val refreshWeather =
      object : Runnable {
        override fun run() {
          fetchWeather()
          ui.postDelayed(this, weatherRefreshMs)
        }
      }

  private val refreshCalendar =
      object : Runnable {
        override fun run() {
          // Pick up calendar changes pushed over the fleet API (or via in-app
          // settings) without restarting the dream: reload just the calendar fields,
          // leaving the live slideshow state untouched.
          val fresh = ScreensaverConfig.load(context)
          if (fresh.calendarUrl != settings.calendarUrl ||
              fresh.calendarRange != settings.calendarRange) {
            settings = settings.copy(calendarUrl = fresh.calendarUrl, calendarRange = fresh.calendarRange)
            renderCalendar()
          }
          val url = settings.calendarUrl
          if (settings.usesCalendar && !url.isNullOrBlank()) {
            io.execute {
              val events = CalendarFeed.fetch(url)
              ui.post {
                calendarEvents = events
                renderCalendar()
              }
            }
          } else {
            calendarEvents = emptyList()
            renderCalendar()
          }
          ui.postDelayed(this, calendarRefreshMs)
        }
      }

  // --- navigation (branches on the active source) -----------------------------
  fun next() {
    when {
      localMode -> advanceLocal(+1)
      remoteMode -> advanceRemote(+1)
      else -> webNext()
    }
  }

  fun prev() {
    when {
      localMode -> advanceLocal(-1)
      remoteMode -> advanceRemote(-1)
      else -> webPrev()
    }
  }

  // --- local folder playback --------------------------------------------------
  private val localTick =
      object : Runnable {
        override fun run() {
          advanceLocal(+1)
        }
      }

  private fun advanceLocal(dir: Int) {
    ui.removeCallbacks(localTick)
    if (playlist.isEmpty()) {
      startWeb()
      return
    }
    gen++
    localIndex = ((localIndex + dir) % playlist.size + playlist.size) % playlist.size
    val item = playlist[localIndex]
    if (item.isVideo) showVideo(item.path, gen) else showLocalImage(item.path, gen)
  }

  private fun showLocalImage(path: String, g: Int) {
    stopVideo()
    io.execute {
      val bmp = runCatching { decodeCorrected(path) }.getOrNull()
      ui.post {
        if (g != gen) return@post // superseded by a newer advance
        if (bmp == null) {
          advanceLocal(+1) // skip an unreadable file
          return@post
        }
        photo.visibility = View.VISIBLE
        show(bmp)
        ui.postDelayed(localTick, intervalMs())
      }
    }
  }

  /**
   * Decode a local image and apply its EXIF orientation. Phone cameras save the
   * raw sensor buffer and record the intended rotation in an EXIF tag rather than
   * baking it into the pixels, so [BitmapFactory.decodeFile] alone shows portrait
   * shots sideways and some landscapes upside-down. The web feed is unaffected (its
   * images carry no rotation flag), so this only matters for the folder source.
   *
   * Reading the tag is best-effort: a missing or unreadable EXIF block falls back to
   * the upright orientation so a quirky file still shows (just unrotated) instead of
   * being skipped.
   */
  private fun decodeCorrected(path: String): Bitmap? {
    val bmp = BitmapFactory.decodeFile(path) ?: return null
    val orientation =
        runCatching {
              ExifInterface(path)
                  .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.preScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(-90f)
        matrix.preScale(-1f, 1f)
      }
      else -> return bmp // NORMAL / UNDEFINED — already upright, no copy needed
    }
    // createBitmap allocates a second full-size bitmap; free the source once the
    // rotated copy exists so peak memory stays at one image (Portal heaps are small
    // and phone photos are large).
    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    if (rotated != bmp) bmp.recycle()
    return rotated
  }

  private fun showVideo(path: String, g: Int) {
    photo.setImageDrawable(null)
    photo.visibility = View.GONE
    videoView.visibility = View.VISIBLE
    runCatching {
          videoView.setOnPreparedListener { mp ->
            mp.isLooping = false
            runCatching { mp.setVolume(0f, 0f) } // a screensaver shouldn't blare audio
            if (g == gen) videoView.start()
          }
          videoView.setOnCompletionListener {
            if (g == gen) advanceLocal(+1)
          }
          videoView.setOnErrorListener { _, _, _ ->
            if (g == gen) advanceLocal(+1)
            true
          }
          videoView.setVideoPath(path)
          // Safety net: advance even if a clip is very long or never reports done.
          ui.postDelayed(localTick, maxOf(intervalMs(), 5 * 60_000L))
        }
        .onFailure { if (g == gen) advanceLocal(+1) }
  }

  private fun stopVideo() {
    if (this::videoView.isInitialized && videoView.visibility != View.GONE) {
      runCatching { videoView.stopPlayback() }
      videoView.visibility = View.GONE
    }
  }

  // --- remote album playback (iCloud / Google Photos public shares) -----------
  private val remoteTick =
      object : Runnable {
        override fun run() {
          advanceRemote(+1)
        }
      }

  // Shuffle is applied once at start, not on refresh — re-shuffling every tick
  // would scramble the user's current position.
  private val remoteRefresh =
      object : Runnable {
        override fun run() {
          if (!remoteMode) return
          val shareUrl = settings.albumUrl
          if (shareUrl.isNullOrBlank()) return
          val m = context.resources.displayMetrics
          io.execute {
            val fresh = RemoteAlbum.fetch(shareUrl, m.widthPixels, m.heightPixels)
            val urls = fresh?.photoUrls.orEmpty()
            ui.post {
              if (remoteMode && urls.isNotEmpty()) {
                remoteUrls = urls
                if (remoteIndex >= remoteUrls.size) remoteIndex = -1
                remoteFailStreak = 0
              }
              scheduleRemoteRefresh()
            }
          }
        }
      }

  private fun scheduleRemoteRefresh() {
    ui.removeCallbacks(remoteRefresh)
    ui.postDelayed(remoteRefresh, settings.albumRefreshMin * 60_000L)
  }

  private fun advanceRemote(dir: Int) {
    ui.removeCallbacks(remoteTick)
    if (remoteUrls.isEmpty()) {
      startWeb()
      return
    }
    // One failure per URL = the whole album is unreachable; bail to the web feed
    // so a dead share doesn't spin 8-12s timeouts indefinitely.
    if (remoteFailStreak >= remoteUrls.size) {
      startWeb()
      return
    }
    gen++
    remoteIndex = ((remoteIndex + dir) % remoteUrls.size + remoteUrls.size) % remoteUrls.size
    val url = remoteUrls[remoteIndex]
    val g = gen
    stopVideo()
    io.execute {
      val bmp = runCatching { downloadBitmap(url) }.getOrNull()
      ui.post {
        if (g != gen) return@post // superseded by a newer advance
        if (!remoteMode) return@post // raced with startWeb() flipping us off
        if (bmp == null) {
          remoteFailStreak++
          advanceRemote(+1)
          return@post
        }
        remoteFailStreak = 0
        photo.visibility = View.VISIBLE
        show(bmp)
        ui.postDelayed(remoteTick, intervalMs())
      }
    }
  }

  // --- web feed (default + fallback) ------------------------------------------
  private fun startWeb() {
    localMode = false
    remoteMode = false
    ui.removeCallbacks(remoteTick)
    ui.removeCallbacks(remoteRefresh)
    stopVideo()
    rotate.run()
  }

  /** Forward through history, loading a fresh photo when at the end. */
  private fun webNext() {
    if (index in 0 until history.size - 1) {
      index++
      show(history[index])
    } else {
      loadFresh()
    }
  }

  /** Back through history (no-op at the start). */
  private fun webPrev() {
    if (index > 0) {
      index--
      show(history[index])
    }
  }

  private fun loadFresh() {
    io.execute {
      val bmp = runCatching { downloadBitmap(directImageUrl()) }.getOrNull() ?: return@execute
      ui.post {
        photo.visibility = View.VISIBLE
        if (history.size >= 6) history.removeAt(0) // cap memory; GC reclaims
        history.add(bmp)
        index = history.size - 1
        show(bmp)
      }
    }
  }

  private fun show(bmp: Bitmap) {
    photo
        .animate()
        .alpha(0.15f)
        .setDuration(220)
        .withEndAction {
          photo.setImageBitmap(bmp)
          photo.animate().alpha(1f).setDuration(420).start()
        }
        .start()
  }

  private fun directImageUrl(): String {
    val m = context.resources.displayMetrics
    val w = m.widthPixels
    val h = m.heightPixels
    if (unsplashKey.isBlank())
        return "https://picsum.photos/$w/$h?random=${System.currentTimeMillis()}"
    // Match Unsplash crop to the screen aspect so portrait panels (e.g. Portal
    // Mini at 800x1280) don't get a letterboxed/upscaled landscape shot.
    val orientation = if (h > w) "portrait" else "landscape"
    val json =
        httpGet(
            "https://api.unsplash.com/photos/random?orientation=$orientation" +
                "&query=$unsplashQuery&client_id=$unsplashKey")
    return JSONObject(json).getJSONObject("urls").getString("regular")
  }

  // --- data -------------------------------------------------------------------
  private fun batteryPct(): Int {
    val i =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
    // Only Portal Go has a battery; the mains-powered Portals (Portal+, Mini,
    // gen-2, TV) report no battery present but still publish a bogus level=0, so
    // gate on EXTRA_PRESENT to avoid showing a permanent "0%". -1 hides the field.
    if (!i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) return -1
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level >= 0 && scale > 0) level * 100 / scale else -1
  }

  private fun fetchWeather() {
    io.execute {
      // Shared resilient fetch: cached location + multi-provider geolocation.
      val w = Weather.fetch(context)
      if (w.isNotBlank()) weatherText = w
    }
  }

  private fun httpGet(spec: String): String {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 8000
    c.setRequestProperty("User-Agent", "PortalPhotoFrame/1.0")
    return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
  }

  private fun downloadBitmap(spec: String): Bitmap? {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 12000
    c.instanceFollowRedirects = true
    c.setRequestProperty("User-Agent", "PortalPhotoFrame/1.0")
    return c.inputStream.use { BitmapFactory.decodeStream(it) }
  }

  private val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
  private val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
  private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
