/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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

  // The overlay (clock / date / weather / battery / now-playing) is built and driven by the
  // FaceRenderer from a Face descriptor; this controller owns only the photo/video layer.
  private val faceRenderer = FaceRenderer(context, weatherRefreshMs)

  // Calendar widget (top-right): a clean upcoming-events panel fed by a public ICS
  // feed (Google secret-iCal / Apple iCloud public-calendar). Hidden when no link.
  private lateinit var calendarPanel: LinearLayout
  private lateinit var calendarHeader: TextView
  private lateinit var calendarRows: LinearLayout
  private var calendarEvents: List<CalendarFeed.Event> = emptyList()
  // Text/padding scale for the current calendar size (0/1/2 → small/medium/large),
  // applied as the panel is (re)rendered so a size change takes effect live.
  private var calScale: Float = 1f
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

  // Remote playback state (iCloud / Google Photos shared albums, or an Immich server).
  private var remoteMode = false
  private var remoteUrls: List<String> = emptyList()
  private var remoteIndex = -1
  private var remoteFailStreak = 0
  // Auth headers sent with each remote image download — empty for public shares, the
  // x-api-key for Immich. Applied in [advanceRemote]/[downloadBitmap].
  private var remoteHeaders: Map<String, String> = emptyMap()
  // Optional custom fetcher for the remote path: when set (SMB), each "url" is fetched through
  // this instead of an HTTP download, so SMB reuses all the remote advance/tick/fallback logic.
  private var remoteFetch: ((String) -> Bitmap?)? = null
  private var smbSource: SmbSource? = null
  // Web-page source: a fullscreen WebView that owns the whole frame (the page brings its own
  // clock/widgets, so the photo layer and Immortal overlay are skipped).
  private var webView: android.webkit.WebView? = null

  // Web-feed history so swipes can go back as well as forward.
  private val history = ArrayList<Bitmap>()
  private var index = -1

  /** Host (dream / preview activity) sets this to dismiss the frame on tap. */
  var onExit: (() -> Unit)? = null

  /** Debug/preview override: render this face instead of the built-in classic one. */
  var faceOverride: Face? = null

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
    // Web-page source takes over the whole frame — no photo feed, no Immortal overlay.
    if (faceOverride == null && settings.usesWebUrl) {
      startWebPage(settings.webUrl!!)
      return
    }
    applyFit()
    refreshCalendar.run()
    calendarTick.run()
    // Build + drive the overlay from the user's selected face ([FaceCatalog]); faceOverride lets
    // the debug preview harness (and the overnight night clock) render a specific face instead.
    faceRenderer.start(faceOverride ?: FaceCatalog.active(context))
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
              remoteHeaders = emptyMap()
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
      settings.usesImmich -> {
        val base = settings.immichUrl
        val key = settings.immichKey
        if (base.isNullOrBlank() || key.isNullOrBlank()) {
          startWeb()
          return
        }
        io.execute {
          val urls = ImmichSource.listImageUrls(base, key, settings.immichAlbumId).orEmpty()
          ui.post {
            if (urls.isNotEmpty()) {
              remoteUrls = if (settings.shuffle) urls.shuffled() else urls
              remoteHeaders = ImmichSource.authHeaders(key)
              remoteMode = true
              remoteIndex = -1
              remoteFailStreak = 0
              advanceRemote(+1)
            } else {
              // Server unreachable / album empty → never leave the frame blank.
              startWeb()
            }
          }
        }
      }
      settings.usesDav -> {
        val url = settings.davUrl
        if (url.isNullOrBlank()) {
          startWeb()
          return
        }
        io.execute {
          val urls = DavSource.listImageUrls(url, settings.davUser, settings.davPass).orEmpty()
          ui.post {
            if (urls.isNotEmpty()) {
              remoteUrls = if (settings.shuffle) urls.shuffled() else urls
              remoteHeaders = DavSource.authHeaders(settings.davUser, settings.davPass)
              remoteMode = true
              remoteIndex = -1
              remoteFailStreak = 0
              advanceRemote(+1)
            } else {
              startWeb()
            }
          }
        }
      }
      settings.usesSmb -> {
        val src =
            SmbSource(
                host = settings.smbHost.orEmpty(),
                shareName = settings.smbShare.orEmpty(),
                basePath = settings.smbPath.orEmpty(),
                user = settings.smbUser.orEmpty(),
                password = settings.smbPass.orEmpty(),
            )
        io.execute {
          val paths = if (src.connect()) src.listImages() else emptyList()
          ui.post {
            if (paths.isNotEmpty()) {
              smbSource = src
              remoteUrls = if (settings.shuffle) paths.shuffled() else paths
              remoteFetch = { p -> src.openStream(p)?.use { BitmapFactory.decodeStream(it) } }
              remoteMode = true
              remoteIndex = -1
              remoteFailStreak = 0
              advanceRemote(+1)
            } else {
              // Share unreachable / no images → never leave the frame blank.
              src.close()
              startWeb()
            }
          }
        }
      }
      else -> startWeb()
    }
  }

  /** Render an arbitrary web page fullscreen (Immich Kiosk, a dashboard, …). The page owns the
   *  whole frame; the host's touch handling still gives tap-to-exit. */
  @android.annotation.SuppressLint("SetJavaScriptEnabled")
  private fun startWebPage(url: String) {
    val wv = android.webkit.WebView(context)
    wv.setBackgroundColor(Color.BLACK)
    wv.isVerticalScrollBarEnabled = false
    wv.isHorizontalScrollBarEnabled = false
    wv.overScrollMode = View.OVER_SCROLL_NEVER
    wv.setOnTouchListener { _, _ -> false } // host consumes touch for tap-to-exit
    wv.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
      mediaPlaybackRequiresUserGesture = false
      loadWithOverviewMode = true
      useWideViewPort = true
    }
    wv.webViewClient = android.webkit.WebViewClient() // keep navigation inside the WebView
    (view as FrameLayout).addView(wv, FrameLayout.LayoutParams(MATCH, MATCH))
    wv.loadUrl(url)
    webView = wv
  }

  fun stop() {
    ui.removeCallbacksAndMessages(null)
    faceRenderer.stop()
    if (this::videoView.isInitialized) runCatching { videoView.stopPlayback() }
    webView?.let { runCatching { it.stopLoading(); it.destroy() } }
    webView = null
    // Close the SMB connection off-thread (network I/O) before the io executor is killed.
    smbSource?.let { s -> Thread { runCatching { s.close() } }.start() }
    smbSource = null
    io.shutdownNow()
  }

  // --- UI ---------------------------------------------------------------------
  // The photo/video layer lives here; the overlay (clock / widgets / now-playing) is the
  // FaceRenderer's [view], stacked on top from a Face descriptor.
  private fun buildUi(): View {
    val root = FrameLayout(context)
    root.setBackgroundColor(Color.BLACK)

    photo = ImageView(context)
    photo.scaleType = ImageView.ScaleType.CENTER_CROP
    root.addView(photo, FrameLayout.LayoutParams(MATCH, MATCH))

    videoView = VideoView(context)
    videoView.visibility = View.GONE
    root.addView(videoView, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))

    root.addView(faceRenderer.view)
    buildCalendar(root)
    return root
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
    // Size + position can change at runtime (settings screen or a fleet push), so
    // (re)apply the chrome each render. Scale drives text/padding; side drives the edge.
    calScale =
        when (settings.calendarSize) {
          0 -> 0.82f
          2 -> 1.22f
          else -> 1f
        }
    applyCalendarChrome()
    calendarHeader.textSize = 15f * calScale
    calendarHeader.text = rangeLabel(settings.calendarRange).uppercase(Locale.getDefault())
    calendarRows.removeAllViews()
    calendarPanel.visibility = View.VISIBLE

    if (shown.isEmpty()) {
      val empty = text(17f * calScale, 0xCCFFFFFF.toInt(), false)
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
        val header = text(13f * calScale, 0x99FFFFFF.toInt(), false)
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

  /** Position the panel against the chosen edge and scale its padding to the size. */
  private fun applyCalendarChrome() {
    val pad = (22 * calScale).toInt()
    val padV = (18 * calScale).toInt()
    calendarPanel.setPadding(dp(pad), dp(padV), dp(pad), dp(padV))
    val gravity =
        Gravity.TOP or
            (if (settings.calendarSide == ScreensaverConfig.CAL_SIDE_LEFT) Gravity.START
            else Gravity.END)
    val lp = FrameLayout.LayoutParams(WRAP, WRAP, gravity)
    val side = dp(40)
    if (settings.calendarSide == ScreensaverConfig.CAL_SIDE_LEFT) lp.setMargins(side, dp(40), 0, 0)
    else lp.setMargins(0, dp(40), side, 0)
    calendarPanel.layoutParams = lp
  }

  private fun buildEventRow(ev: CalendarFeed.Event): View {
    val row = LinearLayout(context)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    val rowLp = LinearLayout.LayoutParams(WRAP, WRAP)
    rowLp.topMargin = dp(6)

    val time = text(17f * calScale, Color.WHITE, false)
    time.text = if (ev.allDay) "All day" else timeLabel(ev.startMillis)
    time.width = dp((96 * calScale).toInt())

    val title = text(17f * calScale, Color.WHITE, false)
    title.typeface = Typeface.DEFAULT_BOLD
    title.text = ev.title
    title.maxLines = 1
    title.ellipsize = TextUtils.TruncateAt.END
    title.maxWidth = dp((360 * calScale).toInt())

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

  private fun applyFit() {
    // Video letterboxes either way (VideoView limitation); images honour the choice.
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

  private fun intervalMs(): Long = settings.intervalSec * 1000L

  // --- periodic loops ---------------------------------------------------------
  private val calendarTick =
      object : Runnable {
        override fun run() {
          // Re-window the calendar once per minute (cheap, no network): drops events
          // as they pass and rolls the Today/Tomorrow labels over at midnight.
          val now = Date()
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
      val fetch = remoteFetch
      val bmp =
          runCatching { fetch?.invoke(url) ?: downloadBitmap(url, remoteHeaders) }.getOrNull()
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
    remoteHeaders = emptyMap()
    remoteFetch = null
    smbSource?.let { s -> io.execute { runCatching { s.close() } } }
    smbSource = null
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
  private fun httpGet(spec: String): String {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 8000
    c.setRequestProperty("User-Agent", "PortalPhotoFrame/1.0")
    return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
  }

  private fun downloadBitmap(spec: String, headers: Map<String, String> = emptyMap()): Bitmap? {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 12000
    c.instanceFollowRedirects = true
    c.setRequestProperty("User-Agent", "PortalPhotoFrame/1.0")
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    return c.inputStream.use { BitmapFactory.decodeStream(it) }
  }

  private val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
  private val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
  private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
