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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
 * idle screen: full-screen rotating photos with a clock / battery / date /
 * weather cluster bottom-left.
 *
 * Image source is pluggable — leave [unsplashKey] blank for the no-key Lorem
 * Picsum feed, or supply a free Unsplash access key for curated photos. Weather
 * is Open-Meteo + IP geolocation (both keyless). All network is best-effort.
 */
class PhotoFrameController(
    private val context: Context,
    private val unsplashKey: String = "",
    private val unsplashQuery: String = "nature,landscape,scenic",
    private val rotateMs: Long = 30_000L,
    private val weatherRefreshMs: Long = 30 * 60_000L,
) {
  private val io = Executors.newSingleThreadExecutor()
  private val ui = Handler(Looper.getMainLooper())

  private lateinit var photo: ImageView
  private lateinit var clock: TextView
  private lateinit var battery: TextView
  private lateinit var date: TextView
  private lateinit var weather: TextView
  private var weatherText: String = ""

  // Photo history so swipes can go back as well as forward.
  private val history = ArrayList<Bitmap>()
  private var index = -1

  /** Host (dream / preview activity) sets this to dismiss the frame on tap. */
  var onExit: (() -> Unit)? = null

  // Deterministic gesture from raw down/up deltas (robust to synthetic input
  // that omits MOVE events): clear horizontal swipe = prev/next photo, clear
  // tap = exit, anything ambiguous is ignored.
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
    // No preset image: stay black and fade into the first photo (no jarring flash).
    tick.run()
    rotate.run()
    refreshWeather.run()
  }

  fun stop() {
    ui.removeCallbacksAndMessages(null)
    io.shutdownNow()
  }

  // --- UI ---------------------------------------------------------------------
  private fun buildUi(): View {
    val root = FrameLayout(context)
    root.setBackgroundColor(Color.BLACK)

    photo = ImageView(context)
    photo.scaleType = ImageView.ScaleType.CENTER_CROP
    root.addView(photo, FrameLayout.LayoutParams(MATCH, MATCH))

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
    battery = text(22f, Color.WHITE, false)
    date = text(22f, Color.WHITE, false)
    weather = text(22f, Color.WHITE, false)
    row.addView(battery)
    row.addView(divider())
    row.addView(date)
    row.addView(divider())
    row.addView(weather)
    col.addView(row)

    return root
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

  // --- periodic loops ---------------------------------------------------------
  private val tick =
      object : Runnable {
        override fun run() {
          val now = Date()
          clock.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(now)
          date.text = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now)
          val pct = batteryPct()
          battery.text = if (pct >= 0) "$pct%" else ""
          weather.text = weatherText
          ui.postDelayed(this, 1_000L)
        }
      }

  private val rotate =
      object : Runnable {
        override fun run() {
          next()
          ui.postDelayed(this, rotateMs)
        }
      }

  private val refreshWeather =
      object : Runnable {
        override fun run() {
          fetchWeather()
          ui.postDelayed(this, weatherRefreshMs)
        }
      }

  // --- data -------------------------------------------------------------------
  private fun batteryPct(): Int {
    val i =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level >= 0 && scale > 0) level * 100 / scale else -1
  }

  /** Forward through history, loading a fresh photo when at the end. */
  fun next() {
    if (index in 0 until history.size - 1) {
      index++
      show(history[index])
    } else {
      loadFresh()
    }
  }

  /** Back through history (no-op at the start). */
  fun prev() {
    if (index > 0) {
      index--
      show(history[index])
    }
  }

  private fun loadFresh() {
    io.execute {
      val bmp = runCatching { downloadBitmap(directImageUrl()) }.getOrNull() ?: return@execute
      ui.post {
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
    if (unsplashKey.isBlank())
        return "https://picsum.photos/1280/800?random=${System.currentTimeMillis()}"
    val json =
        httpGet(
            "https://api.unsplash.com/photos/random?orientation=landscape" +
                "&query=$unsplashQuery&client_id=$unsplashKey")
    return JSONObject(json).getJSONObject("urls").getString("regular")
  }

  private fun fetchWeather() {
    io.execute {
      runCatching {
            val geo = JSONObject(httpGet("https://ipapi.co/json/"))
            val lat = geo.getDouble("latitude")
            val lon = geo.getDouble("longitude")
            val w =
                JSONObject(
                    httpGet(
                        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                            "&current=temperature_2m,weather_code&temperature_unit=fahrenheit"))
            val cur = w.getJSONObject("current")
            val temp = cur.getDouble("temperature_2m").toInt()
            weatherText = "${weatherEmoji(cur.getInt("weather_code"))} $temp°"
          }
          .onFailure { weatherText = "" }
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

  private fun weatherEmoji(code: Int): String =
      when (code) {
        0 -> "☀️"
        1, 2 -> "🌤️"
        3 -> "☁️"
        in 45..48 -> "🌫️"
        in 51..67 -> "🌦️"
        in 71..77 -> "🌨️"
        in 80..82 -> "🌧️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
      }

  private val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
  private val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
  private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
