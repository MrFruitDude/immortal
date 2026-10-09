/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min
import kotlin.math.sin

/**
 * Alfred's popover: while a conversation runs, a compact card near the bottom centre (Alfred
 * animated, what he's doing, his reply) and a soft glow along the screen edges in his current
 * colour — over whatever is on screen (home, another app, the photo frame) without taking it over.
 *
 * Hosted by [BarWatchService] as `TYPE_ACCESSIBILITY_OVERLAY` windows, like [QuickBar] and
 * [NotificationOverlay]: no extra permission, and drawn above the system bar and the dream.
 *  - The glow window is `FLAG_NOT_TOUCHABLE`: every touch goes to whatever is underneath.
 *  - The card window is only as big as the card, so touches outside it pass through too.
 * If the accessibility service isn't running, [AlfredPopoverActivity] shows the card (no edge
 * glow) as a small translucent window instead.
 *
 * Plain Views, not Compose: a Compose view in a service-added window needs lifecycle wiring, and
 * these must be cheap on a Portal. The avatar redraws ~25 fps and the glow only pulses its alpha,
 * and only while a conversation is on screen.
 */
object AlfredOverlay {
  private const val TAG = "AlfredOverlay"

  /** What the popover shows. */
  data class Model(val mode: Alfred.Mode, val label: String, val caption: String)

  private val main = Handler(Looper.getMainLooper())
  private var host: AccessibilityService? = null
  private var wm: WindowManager? = null
  private var glow: AlfredGlowView? = null
  private var card: AlfredCard? = null
  private var glowPulse: ObjectAnimator? = null
  private var pulseMs = 0L
  private var cardSuppressed = false
  private var closing = false
  @Volatile internal var fallbackShowing = false
  /** For the fallback Activity when the accessibility service isn't there to host us. */
  @Volatile private var app: Context? = null

  /** The popover being shown, or null. Main-thread state; read by the fallback Activity. */
  @Volatile var current: Model? = null
    private set

  private val listeners = CopyOnWriteArrayList<(Model?) -> Unit>()
  private val hideNow = Runnable { removeAll() }

  fun addListener(l: (Model?) -> Unit) = listeners.add(l)

  fun removeListener(l: (Model?) -> Unit) = listeners.remove(l)

  // --- lifecycle (called by BarWatchService) ---------------------------------------------

  fun attach(service: AccessibilityService) {
    main.post {
      host = service
      wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }
  }

  fun detach() {
    main.post {
      removeViews()
      host = null
      wm = null
    }
  }

  // --- API ---------------------------------------------------------------------------------

  /** Shows the popover (or replaces what it shows), cancelling a pending hide. */
  fun show(context: Context, model: Model) {
    app = context.applicationContext
    main.post { render(model, open = true) }
  }

  /** Updates a popover that's up; ignored once it's closing (late events from a finished turn). */
  fun update(model: Model) = main.post { if (current != null && !closing) render(model, open = false) }

  /** Hides it after [delayMs] (an error or goodbye lingers briefly). */
  fun hide(delayMs: Long) =
      main.post {
        closing = true
        main.removeCallbacks(hideNow)
        main.postDelayed(hideNow, delayMs)
      }

  /** The full Muse screen is in front: it has its own Alfred, so only the edge glow stays. */
  fun setCardSuppressed(suppressed: Boolean) =
      main.post {
        cardSuppressed = suppressed
        card?.visibility = if (suppressed) View.GONE else View.VISIBLE
      }

  // --- internals ---------------------------------------------------------------------------

  private fun render(model: Model, open: Boolean) {
    if (open) {
      closing = false
      main.removeCallbacks(hideNow)
    }
    current = model
    if (!ensureWindows()) launchFallback()
    card?.bind(model)
    glow?.let { g ->
      g.setColor(accentOf(model.mode))
      pulse(g, when (model.mode) {
        Alfred.Mode.THINKING -> 900L
        Alfred.Mode.SPEAKING -> 1_200L
        Alfred.Mode.ERROR -> 600L
        else -> 1_700L
      })
    }
    listeners.forEach { runCatching { it(model) } }
  }

  /** Adds the glow and card windows if they aren't up; false if there's no host (or it failed). */
  private fun ensureWindows(): Boolean {
    if (card != null) return true
    val ctx = host ?: return false
    val w = wm ?: return false
    val dm = DisplayMetrics().also { @Suppress("DEPRECATION") w.defaultDisplay.getRealMetrics(it) }
    val d = dm.density
    val g = AlfredGlowView(ctx)
    val glowLp =
        WindowManager.LayoutParams(
                dm.widthPixels,
                dm.heightPixels,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT)
            .apply {
              gravity = Gravity.TOP or Gravity.START
              x = 0
              y = 0
            }
    val c = AlfredCard(ctx) { AlfredSession.close() }
    // An explicit pixel width (MATCH_PARENT/WRAP_CONTENT misbehave on a11y overlays, see
    // NotificationOverlay); the window is the card, so touches beside it reach the app below.
    val cardLp =
        WindowManager.LayoutParams(
                min((620 * d).toInt(), dm.widthPixels - (48 * d).toInt()),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT)
            .apply {
              gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
              y = (32 * d).toInt()
            }
    val ok = runCatching {
      w.addView(g, glowLp)
      glow = g
      w.addView(c, cardLp)
      card = c
    }.onFailure { Log.w(TAG, "addView failed", it) }.isSuccess
    if (!ok) {
      removeViews()
      return false
    }
    c.visibility = if (cardSuppressed) View.GONE else View.VISIBLE
    g.alpha = 0f
    c.alpha = 0f
    c.translationY = 24 * d
    c.animate().alpha(1f).translationY(0f).setDuration(220).start()
    return true
  }

  private fun pulse(g: AlfredGlowView, ms: Long) {
    if (glowPulse != null && pulseMs == ms) return
    glowPulse?.cancel()
    pulseMs = ms
    glowPulse = ObjectAnimator.ofFloat(g, View.ALPHA, maxOf(g.alpha, 0.35f), 1f, 0.45f).apply {
      duration = ms
      repeatCount = ValueAnimator.INFINITE
      repeatMode = ValueAnimator.REVERSE
      start()
    }
  }

  private fun removeAll() {
    current = null
    closing = false
    val c = card
    val g = glow
    glowPulse?.cancel()
    glowPulse = null
    pulseMs = 0
    card = null
    glow = null
    // Fade out, then drop the windows (and with them every animation).
    c?.let { v -> v.animate().alpha(0f).setDuration(200).withEndAction { removeView(v) }.start() }
    g?.let { v -> v.animate().alpha(0f).setDuration(300).withEndAction { removeView(v) }.start() }
    // Belt and braces: the glow keeps the screen on, so never rely on an animation end alone.
    main.postDelayed({ c?.let { removeView(it) }; g?.let { removeView(it) } }, 600)
    listeners.forEach { runCatching { it(null) } }
  }

  private fun removeViews() {
    glowPulse?.cancel()
    glowPulse = null
    pulseMs = 0
    card?.let { removeView(it) }
    glow?.let { removeView(it) }
    card = null
    glow = null
  }

  private fun removeView(v: View) {
    runCatching { wm?.removeView(v) }
  }

  private fun launchFallback() {
    if (fallbackShowing) return
    val ctx = host ?: app ?: return
    fallbackShowing = true
    runCatching {
      ctx.startActivity(
          Intent(ctx, AlfredPopoverActivity::class.java)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }.onFailure {
      fallbackShowing = false
      Log.w(TAG, "couldn't show the popover", it)
    }
  }

  internal fun accentOf(mode: Alfred.Mode): Int = 0xff000000.toInt() or AlfredAvatar.schemeAccent(mode)
}

/** Alfred, animated: [AlfredAvatar] scaled up with crisp pixels over a soft glow. Tap to pet. */
internal class AlfredAvatarView(context: Context) : View(context) {
  var mode: Alfred.Mode = Alfred.Mode.LISTENING
    set(v) {
      if (field != v) {
        field = v
        modeSince = System.nanoTime()
      }
    }

  private val avatar = AlfredAvatar(transparentBackground = true)
  private val bitmap = Bitmap.createBitmap(AlfredAvatar.W, AlfredAvatar.H, Bitmap.Config.ARGB_8888)
  private val pixelPaint = Paint().apply {
    isFilterBitmap = false
    isAntiAlias = false
    isDither = false
  }
  private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private var glowFor = 0
  private val start = System.nanoTime()
  private var modeSince = start
  private val src = Rect(0, 0, AlfredAvatar.W, AlfredAvatar.H)
  private val dst = Rect()
  private var running = false
  private val frame = object : Runnable {
    override fun run() {
      if (!running) return
      invalidate()
      postDelayed(this, 40)
    }
  }

  init {
    setOnClickListener { Alfred.pet() }
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    running = true
    post(frame)
  }

  override fun onDetachedFromWindow() {
    running = false
    removeCallbacks(frame)
    super.onDetachedFromWindow()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    glowFor = 0
  }

  override fun onDraw(canvas: Canvas) {
    val now = System.nanoTime()
    val t = (now - start) / 1e9f
    val pose = AlfredAvatar.Pose(mode, t, (now - modeSince) / 1e9f, alfredLiveLevel(mode, t), alfredHappiness())
    avatar.render(pose)
    bitmap.setPixels(avatar.pixels, 0, AlfredAvatar.W, 0, 0, AlfredAvatar.W, AlfredAvatar.H)
    val side = min(width, height)
    val cx = width / 2f
    val cy = height / 2f
    val accent = 0xff000000.toInt() or avatar.accent()
    if (accent != glowFor) {
      glowFor = accent
      glowPaint.shader = RadialGradient(cx, cy, side * 0.6f,
          intArrayOf(withAlpha(accent, 0.38f), withAlpha(accent, 0.12f), Color.TRANSPARENT),
          floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }
    val pulse = (0.85f + 0.15f * sin(t * 2f) + pose.level * 0.3f).coerceIn(0f, 1f)
    glowPaint.alpha = (255 * pulse).toInt()
    canvas.drawCircle(cx, cy, side * 0.6f, glowPaint)
    val scale = (side / AlfredAvatar.W).coerceAtLeast(1)
    val px = AlfredAvatar.W * scale
    val left = (width - px) / 2
    val top = (height - px) / 2
    dst.set(left, top, left + px, top + px)
    canvas.drawBitmap(bitmap, src, dst, pixelPaint)
  }
}

/** The screen-edge glow: four cached edge gradients, redrawn only when the colour changes. */
internal class AlfredGlowView(context: Context) : View(context) {
  private val paint = Paint()
  private var color = 0
  private var built = false
  private val thick = 56 * context.resources.displayMetrics.density
  private val shaders = arrayOfNulls<Shader>(4)

  fun setColor(c: Int) {
    if (c == color) return
    color = c
    built = false
    invalidate()
  }

  override fun hasOverlappingRendering() = false

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    built = false
  }

  override fun onDraw(canvas: Canvas) {
    val w = width.toFloat()
    val h = height.toFloat()
    if (!built) {
      val colors = intArrayOf(withAlpha(color, 0.85f), withAlpha(color, 0.3f), Color.TRANSPARENT)
      val stops = floatArrayOf(0f, 0.35f, 1f)
      shaders[0] = LinearGradient(0f, 0f, 0f, thick, colors, stops, Shader.TileMode.CLAMP)
      shaders[1] = LinearGradient(0f, h, 0f, h - thick, colors, stops, Shader.TileMode.CLAMP)
      shaders[2] = LinearGradient(0f, 0f, thick, 0f, colors, stops, Shader.TileMode.CLAMP)
      shaders[3] = LinearGradient(w, 0f, w - thick, 0f, colors, stops, Shader.TileMode.CLAMP)
      built = true
    }
    paint.shader = shaders[0]
    canvas.drawRect(0f, 0f, w, thick, paint)
    paint.shader = shaders[1]
    canvas.drawRect(0f, h - thick, w, h, paint)
    paint.shader = shaders[2]
    canvas.drawRect(0f, 0f, thick, h, paint)
    paint.shader = shaders[3]
    canvas.drawRect(w - thick, 0f, w, h, paint)
  }
}

/** The popover card: Alfred, what he's doing, his reply, and a close button. */
internal class AlfredCard(context: Context, onClose: () -> Unit) : LinearLayout(context) {
  private val d = context.resources.displayMetrics.density
  private val avatar = AlfredAvatarView(context)
  private val bg = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = 28 * d
    setColor(0xF2141626.toInt())
  }
  private val label = TextView(context).apply {
    textSize = 15f
    typeface = Typeface.DEFAULT_BOLD
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
  }
  private val caption = TextView(context).apply {
    setTextColor(Color.WHITE)
    textSize = 18f
    maxLines = 3
    ellipsize = TextUtils.TruncateAt.END
    setLineSpacing(0f, 1.15f)
  }

  init {
    orientation = HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    background = bg
    setPadding(dp(10), dp(10), dp(12), dp(10))
    addView(avatar, LayoutParams(dp(92), dp(92)))
    val column = LinearLayout(context).apply {
      orientation = VERTICAL
      setPadding(dp(10), 0, dp(10), 0)
      addView(label)
      addView(caption)
    }
    addView(column, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val close = TextView(context).apply {
      text = "✕"
      textSize = 20f
      setTextColor(Color.WHITE)
      gravity = Gravity.CENTER
      contentDescription = "End the conversation"
      background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0x26FFFFFF)
      }
      setOnClickListener { onClose() }
    }
    addView(close, LayoutParams(dp(52), dp(52)))
  }

  fun bind(m: AlfredOverlay.Model) {
    val accent = AlfredOverlay.accentOf(m.mode)
    avatar.mode = m.mode
    label.text = m.label
    label.setTextColor(accent)
    caption.text = m.caption
    caption.visibility = if (m.caption.isEmpty()) View.GONE else View.VISIBLE
    bg.setStroke(dp(2), withAlpha(accent, 0.6f))
  }

  private fun dp(v: Int) = (v * d).toInt()
}

/**
 * The popover without the accessibility service: a small translucent window at the bottom of the
 * screen showing the same card. Touches outside it pass through (`FLAG_NOT_TOUCH_MODAL`); there's
 * no edge glow, since this window is only as big as the card.
 */
class AlfredPopoverActivity : Activity() {
  private lateinit var card: AlfredCard
  private val listener: (AlfredOverlay.Model?) -> Unit = { m ->
    runOnUiThread { if (m == null) finish() else card.bind(m) }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AlfredOverlay.fallbackShowing = true
    val d = resources.displayMetrics.density
    window.apply {
      setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
      setLayout(min((620 * d).toInt(), resources.displayMetrics.widthPixels - (48 * d).toInt()),
          WindowManager.LayoutParams.WRAP_CONTENT)
      attributes = attributes.apply { y = (32 * d).toInt() }
      addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }
    if (android.os.Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
    setFinishOnTouchOutside(false)
    card = AlfredCard(this) { AlfredSession.close() }
    setContentView(card)
    val now = AlfredOverlay.current
    if (now == null) {
      finish()
      return
    }
    card.bind(now)
    AlfredOverlay.addListener(listener)
  }

  override fun onDestroy() {
    AlfredOverlay.removeListener(listener)
    AlfredOverlay.fallbackShowing = false
    super.onDestroy()
  }
}

internal fun withAlpha(color: Int, a: Float): Int = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (color and 0xffffff)
