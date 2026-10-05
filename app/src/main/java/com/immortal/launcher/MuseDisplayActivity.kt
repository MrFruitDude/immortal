/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Full-screen surface for what Muse puts on the Portal (display.draw_url / display.show_text).
 *
 * It cooperates with the screensaver rather than fighting it: before it comes forward it marks the
 * dream stop as deliberate ([DreamPolicy.userExitAt]) so [DreamPolicy] doesn't relaunch the photo
 * frame over it; when it times out or is tapped it simply finishes, revealing whatever was there
 * (the frame, or home — after which the normal idle timeout brings the screensaver back).
 */
class MuseDisplayActivity : Activity() {

  sealed class Content {
    data class Image(val url: String, val caption: String) : Content()

    data class Text(val title: String, val text: String) : Content()
  }

  private val handler = Handler(Looper.getMainLooper())
  private val finisher = Runnable { finish() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (Build.VERSION.SDK_INT >= 27) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility =
        View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    current = WeakReference(this)
    render()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    render()
  }

  private fun render() {
    val pending = Pending.take() ?: run {
      finish()
      return
    }
    val root = FrameLayout(this).apply {
      setBackgroundColor(Color.BLACK)
      setOnClickListener { finish() }
    }
    when (val c = pending.content) {
      is Content.Image -> {
        val col = LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          gravity = Gravity.CENTER
        }
        val iv = ImageView(this).apply {
          scaleType = ImageView.ScaleType.FIT_CENTER
          setImageDrawable(pending.drawable)
          (pending.drawable as? AnimatedImageDrawable)?.start()
        }
        col.addView(iv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        if (c.caption.isNotBlank()) col.addView(label(c.caption, 28f).apply { setPadding(32, 16, 32, 24) })
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
      }
      is Content.Text -> {
        val col = LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(72, 56, 72, 56)
        }
        if (c.title.isNotBlank()) col.addView(label(c.title, 44f).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        col.addView(label(c.text, if (c.text.length > 280) 26f else 36f).apply { gravity = Gravity.START; setPadding(0, 24, 0, 0) })
        root.addView(ScrollView(this).apply { addView(col) }, FrameLayout.LayoutParams(-1, -1))
      }
    }
    setContentView(root)
    handler.removeCallbacks(finisher)
    handler.postDelayed(finisher, pending.seconds * 1000L)
    pending.shown.countDown()
  }

  private fun label(text: String, sp: Float) = TextView(this).apply {
    this.text = text
    setTextColor(Color.WHITE)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    gravity = Gravity.CENTER
  }

  override fun onDestroy() {
    handler.removeCallbacks(finisher)
    if (current?.get() === this) current = null
    super.onDestroy()
  }

  private class Pending(val content: Content, val drawable: Drawable?, val seconds: Int) {
    val shown = CountDownLatch(1)

    companion object {
      @Volatile private var next: Pending? = null

      fun put(p: Pending) {
        next = p
      }

      fun take(): Pending? = next.also { next = null }

      /** Drops [p] if it was never picked up, so a later launch can't show something stale. */
      fun clearIf(p: Pending) {
        if (next === p) next = null
      }
    }
  }

  companion object {
    private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
    @Volatile private var current: WeakReference<MuseDisplayActivity>? = null
    /** One display command at a time (Muse may run several invokes concurrently). */
    private val displayLock = Any()

    /**
     * Prepares [content] (downloads and decodes an image first, so a bad URL is reported rather
     * than shown), brings the screen forward, and waits until it's actually on screen. Returns
     * null on success or an error message. Call off the main thread.
     */
    fun showAndWait(context: Context, content: Content, seconds: Int): String? =
        synchronized(displayLock) { showLocked(context, content, seconds) }

    private fun showLocked(context: Context, content: Content, seconds: Int): String? {
      val drawable =
          when (content) {
            is Content.Image -> try {
              loadImage(context, content.url)
            } catch (e: Exception) {
              return "couldn't load the image: ${e.message ?: e.javaClass.simpleName}"
            }
            is Content.Text -> null
          }
      val pending = Pending(content, drawable, seconds)
      Pending.put(pending)
      // A deliberate takeover, not a force-woken dream: don't let DreamPolicy relaunch the frame.
      DreamPolicy.userExitAt = System.currentTimeMillis()
      ScreenControl.wake(context)
      context.startActivity(
          Intent(context, MuseDisplayActivity::class.java)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION))
      if (pending.shown.await(15, TimeUnit.SECONDS)) return null
      Pending.clearIf(pending)
      return "the screen didn't come forward"
    }

    fun dismiss(context: Context) {
      Handler(Looper.getMainLooper()).post { current?.get()?.finish() }
    }

    private fun loadImage(context: Context, url: String): Drawable {
      val conn = URL(url).openConnection() as HttpURLConnection
      val bytes = try {
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", MuseApi.userAgent())
        if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
        conn.inputStream.use { s ->
          val out = java.io.ByteArrayOutputStream()
          val buf = ByteArray(16 * 1024)
          while (true) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_IMAGE_BYTES) throw java.io.IOException("image larger than 20 MB")
          }
          out.toByteArray()
        }
      } finally {
        conn.disconnect()
      }
      val dm = context.resources.displayMetrics
      val maxW = maxOf(dm.widthPixels, dm.heightPixels)
      val maxH = minOf(dm.widthPixels, dm.heightPixels)
      if (Build.VERSION.SDK_INT >= 28) {
        val src = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
        return ImageDecoder.decodeDrawable(src) { decoder, info, _ ->
          val s = minOf(1.0, minOf(maxW.toDouble() / info.size.width, maxH.toDouble() / info.size.height))
          if (s < 1.0) decoder.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
        }
      }
      val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
      var sample = 1
      while (opts.outWidth / (sample * 2) >= maxW || opts.outHeight / (sample * 2) >= maxH) sample *= 2
      val bmp: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
          ?: throw java.io.IOException("not an image")
      return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
    }
  }
}
