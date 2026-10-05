/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The Portal as Muse's canvas: a full-screen web view Muse fills with whatever it wants to show
 * — a dashboard, a slideshow, an animation, a game, an interactive page. Sandboxed: no file or
 * content access, no navigation to anything but http(s), and one narrow bridge, `portal`:
 *
 *   portal.send("text")   — post a message to Muse as coming from this Portal (a tap on a button
 *                            in Muse's page becomes a turn in the conversation; rate-limited)
 *   portal.say("text")    — speak it on the Portal
 *   portal.close()        — dismiss the canvas
 *
 * Like the picture display, it cooperates with the screensaver: it marks the takeover as a
 * deliberate exit, and when it closes (timeout, tap-and-hold, or Muse) the frame comes back.
 */
class MuseCanvasActivity : Activity() {
  private lateinit var web: WebView
  private val handler = Handler(Looper.getMainLooper())
  private val closer = Runnable { finish() }

  @SuppressLint("SetJavaScriptEnabled")
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
    web = WebView(this).apply {
      setBackgroundColor(Color.BLACK)
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.mediaPlaybackRequiresUserGesture = false
      settings.allowFileAccess = false
      settings.allowContentAccess = false
      settings.setGeolocationEnabled(false)
      settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
      settings.useWideViewPort = true
      settings.loadWithOverviewMode = true
      addJavascriptInterface(Bridge(this@MuseCanvasActivity), "portal")
      webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
          val scheme = request.url.scheme?.lowercase()
          return scheme != "http" && scheme != "https" && scheme != "about" && scheme != "data"
        }

        override fun onPageFinished(view: WebView, url: String?) {
          loaded?.countDown()
        }
      }
      // Long-press anywhere closes the canvas (taps belong to the page).
      setOnLongClickListener {
        finish()
        true
      }
    }
    setContentView(web)
    current = WeakReference(this)
    load(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    load(intent)
  }

  private fun load(i: Intent) {
    val p = pending.getAndSet(null)
    if (p == null) {
      if (!this::web.isInitialized || web.url == null) finish()
      return
    }
    if (p.url != null) web.loadUrl(p.url) else web.loadDataWithBaseURL(BASE_URL, p.html ?: "", "text/html", "utf-8", null)
    handler.removeCallbacks(closer)
    if (p.seconds > 0) handler.postDelayed(closer, p.seconds * 1000L)
  }

  override fun onDestroy() {
    handler.removeCallbacks(closer)
    if (current?.get() === this) current = null
    runCatching {
      web.loadUrl("about:blank")
      web.destroy()
    }
    super.onDestroy()
  }

  private class Bridge(activity: MuseCanvasActivity) {
    private val ref = WeakReference(activity)
    @Volatile private var lastSend = 0L

    @JavascriptInterface
    fun send(text: String) {
      val now = System.currentTimeMillis()
      if (now - lastSend < 2_000 || text.isBlank()) return
      lastSend = now
      Thread({ MuseRuntime.sendChat(text.take(2000)) }, "canvas-send").start()
    }

    @JavascriptInterface
    fun say(text: String) {
      val c = ref.get() ?: return
      Thread({ MuseSpeech.speakAndWait(c.applicationContext, text.take(2000)) }, "canvas-say").start()
    }

    @JavascriptInterface
    fun close() {
      ref.get()?.let { a -> a.runOnUiThread { a.finish() } }
    }
  }

  private class Pending(val html: String?, val url: String?, val seconds: Int)

  companion object {
    private const val TAG = "MuseCanvas"
    private const val BASE_URL = "https://canvas.alfred.invalid/"
    private val pending = AtomicReference<Pending?>()
    @Volatile private var current: WeakReference<MuseCanvasActivity>? = null
    @Volatile private var loaded: CountDownLatch? = null
    private val main = Handler(Looper.getMainLooper())

    val isShowing: Boolean
      get() = current?.get() != null

    /** Shows [html] (or loads [url]) and waits for the page to load. Null on success, else why. */
    fun show(context: Context, html: String?, url: String?, seconds: Int): String? {
      if (url != null && Uri.parse(url).scheme?.lowercase() !in setOf("http", "https")) return "url must be http(s)"
      val latch = CountDownLatch(1)
      loaded = latch
      pending.set(Pending(html, url, seconds))
      DreamPolicy.userExitAt = System.currentTimeMillis()
      ScreenControl.wake(context)
      context.startActivity(
          Intent(context, MuseCanvasActivity::class.java)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION))
      return if (latch.await(20, TimeUnit.SECONDS)) null else "the page didn't finish loading (it may still appear)"
    }

    /** Runs [js] in the live page; returns its JSON-encoded result, or null if no canvas is up. */
    fun evaluate(js: String, timeoutMs: Long = 10_000): String? {
      val a = current?.get() ?: return null
      val out = AtomicReference<String?>(null)
      val latch = CountDownLatch(1)
      main.post {
        runCatching { a.web.evaluateJavascript(js) { r -> out.set(r); latch.countDown() } }
            .onFailure { latch.countDown() }
      }
      latch.await(timeoutMs, TimeUnit.MILLISECONDS)
      return out.get() ?: "null"
    }

    /** A JPEG of what the canvas shows (scaled to ≤ [maxSide] px), base64; null if no canvas. */
    fun snapshot(maxSide: Int = 960): String? {
      val a = current?.get() ?: return null
      val out = AtomicReference<String?>(null)
      val latch = CountDownLatch(1)
      main.post {
        try {
          val w = a.web.width
          val h = a.web.height
          if (w > 0 && h > 0) {
            val s = minOf(1f, maxSide.toFloat() / maxOf(w, h))
            val bmp = Bitmap.createBitmap((w * s).toInt(), (h * s).toInt(), Bitmap.Config.RGB_565)
            val c = Canvas(bmp)
            c.scale(s, s)
            a.web.draw(c)
            val buf = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 72, buf)
            bmp.recycle()
            out.set(B64.encode(buf.toByteArray()))
          }
        } catch (e: Exception) {
          Log.w(TAG, "snapshot failed", e)
        } finally {
          latch.countDown()
        }
      }
      latch.await(5, TimeUnit.SECONDS)
      return out.get()
    }

    fun close() {
      main.post { current?.get()?.finish() }
    }
  }
}
