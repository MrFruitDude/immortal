/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The liquid-glass dashboard ("Dashboard style: Liquid glass"): a GL stage behind the Compose UI
 * that draws an animated time-of-day/weather sky, plus a "glass" panel under each card that
 * refracts and frosts that sky. Compose cards register their rects with [glassPanel] and then draw
 * only their content (with a faint fill for legibility) on top.
 *
 * Everything degrades to the classic look: no GLES 2, an EGL/shader error, no first frame, or a
 * native driver crash during a previous init all turn the stage off (see [usable]).
 *
 * Main-thread only, except where noted.
 */
internal object GlassStage {
  private const val TAG = "ImmortalGlass"

  /** GL buffer scale against the view: half resolution, upscaled by the TextureView for free. */
  const val RENDER_SCALE = 0.75f

  /** Give up (for this visit) if GL hasn't put frames on screen this long after resuming. */
  private const val FIRST_FRAME_TIMEOUT_MS = 4_000L

  private const val GUARD_PREFS = "glass_guard"
  private const val KEY_PENDING = "init_pending"
  private const val KEY_BROKEN = "broken"

  /** GL failed in this process (init/shader/EGL error): stay classic until the process restarts. */
  val failed = mutableStateOf(false)

  /** No frame arrived in time on this visit; cleared on the next resume ([clearStall]). */
  val stalled = mutableStateOf(false)

  /** A live stage is on screen: dashboard cards draw as glass. */
  val active = mutableStateOf(false)

  private var crashMarkerChecked = false
  private var renderer: GlassRenderer? = null
  private val panels = LinkedHashMap<String, WindowPanel>()
  private val ids = AtomicInteger()
  private var stageLeft = 0f
  private var stageTop = 0f
  private var stageW = 0
  private var stageH = 0
  private var bufW = 0
  private var bufH = 0
  private var density = 1f

  private data class WindowPanel(val l: Float, val t: Float, val r: Float, val b: Float, val corner: Float)

  /**
   * Whether the glass stage may run here. False without GLES 2, after a GL failure in this
   * process, or when a previous init never finished (the process died inside the driver — a
   * native crash no Java handler can catch). That last verdict persists until the user picks the
   * glass style again ([resetGuard]).
   */
  fun usable(context: Context): Boolean {
    if (failed.value) return false
    val prefs = context.getSharedPreferences(GUARD_PREFS, Context.MODE_PRIVATE)
    if (!crashMarkerChecked) {
      crashMarkerChecked = true
      if (prefs.getBoolean(KEY_PENDING, false)) {
        Log.w(TAG, "previous GL init never finished (driver crash?) — liquid glass off until re-selected")
        prefs.edit().putBoolean(KEY_PENDING, false).putBoolean(KEY_BROKEN, true).apply()
      }
    }
    if (prefs.getBoolean(KEY_BROKEN, false)) return false
    val gles =
        runCatching {
              (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).deviceConfigurationInfo.reqGlEsVersion
            }
            .getOrDefault(0)
    return gles >= 0x20000
  }

  /** The user (re)picked a dashboard style: forget a recorded failure so glass gets another try. */
  fun resetGuard(context: Context) {
    context.getSharedPreferences(GUARD_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_PENDING, false)
        .putBoolean(KEY_BROKEN, false)
        .apply()
    Handler(Looper.getMainLooper()).post {
      failed.value = false
      stalled.value = false
    }
  }

  fun clearStall() {
    stalled.value = false
  }

  /** A touch or other user-visible event: animate for a while. Cheap no-op without a stage. */
  fun poke() {
    renderer?.poke()
  }

  internal fun newPanelId(): String = "glass-" + ids.incrementAndGet()

  fun updatePanel(id: String, l: Float, t: Float, r: Float, b: Float, cornerPx: Float) {
    val p = WindowPanel(l, t, r, b, cornerPx)
    val old = panels.put(id, p)
    if (old == p) return
    pushPanels()
    // A card appearing is an event worth a little motion; a relayout (e.g. text changing) is not.
    if (old == null) poke()
  }

  fun removePanel(id: String) {
    if (panels.remove(id) != null) pushPanels()
  }

  private fun createRenderer(context: Context): GlassRenderer {
    val app = context.applicationContext
    val prefs = app.getSharedPreferences(GUARD_PREFS, Context.MODE_PRIVATE)
    val main = Handler(Looper.getMainLooper())
    return GlassRenderer(
        guard =
            object : GlassRenderer.Guard {
              // commit(): the marker must be on disk before the driver gets a chance to crash.
              override fun beginInit() {
                prefs.edit().putBoolean(KEY_PENDING, true).commit()
              }

              override fun endInit(ok: Boolean) {
                prefs.edit().putBoolean(KEY_PENDING, false).apply()
              }
            },
        onFailed = { reason ->
          main.post {
            Log.w(TAG, "falling back to the classic dashboard: $reason")
            failed.value = true
          }
        },
    )
  }

  private fun attach(r: GlassRenderer, density: Float) {
    renderer = r
    this.density = density
    active.value = true
    if (stageW > 0) r.setUnit(density * bufW / stageW)
    pushPanels()
  }

  private fun detach(r: GlassRenderer) {
    if (renderer === r) {
      renderer = null
      active.value = false
    }
  }

  /** The TextureView moved or resized (window px) / its GL buffer changed. */
  private fun onStage(left: Float, top: Float, w: Int, h: Int, bw: Int, bh: Int) {
    if (left == stageLeft && top == stageTop && w == stageW && h == stageH && bw == bufW && bh == bufH) return
    stageLeft = left
    stageTop = top
    stageW = w
    stageH = h
    bufW = bw
    bufH = bh
    if (w > 0) renderer?.setUnit(density * bw / w)
    pushPanels()
  }

  private fun pushPanels() {
    val r = renderer ?: return
    r.setPanels(
        panels.values.mapNotNull {
          GlassGeometry.toBuffer(it.l, it.t, it.r, it.b, it.corner, stageLeft, stageTop, stageW, stageH, bufW, bufH)
        })
  }

  /**
   * Draw [decor] into a software [canvas] the way the screen shows it. `View.draw` on a software
   * canvas can't see GL content (a TextureView draws nothing there), so when a glass stage is in
   * the tree this paints, in order: the window background, each stage's last frame
   * ([TextureView.getBitmap], scaled to the view's bounds at its window position), then the decor's
   * children (the Compose UI) on top — skipping the decor's own opaque background, which would
   * otherwise cover the GL frame. Without a stage it's exactly `decor.draw(canvas)`.
   * Must run on the UI thread (it does: [HomeActivity.captureForeground]).
   */
  fun drawWindow(decor: View, canvas: Canvas) {
    val stages = ArrayList<GlassTextureView>()
    collect(decor, stages)
    if (stages.isEmpty() || decor !is ViewGroup) {
      decor.draw(canvas)
      return
    }
    decor.background?.draw(canvas)
    val loc = IntArray(2)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    stages.forEach { tv ->
      val bmp = runCatching { tv.bitmap }.getOrNull() ?: return@forEach
      tv.getLocationInWindow(loc)
      canvas.drawBitmap(bmp, null, Rect(loc[0], loc[1], loc[0] + tv.width, loc[1] + tv.height), paint)
      bmp.recycle()
    }
    // A TextureView can't draw into a software canvas and paints a blank (white) layer instead, which
    // would cover the sky we just drew — so hide the stages while the rest of the window draws.
    val alphas = stages.map { it.alpha }
    stages.forEach { it.alpha = 0f }
    try {
      for (i in 0 until decor.childCount) {
        val child = decor.getChildAt(i)
        if (child.visibility != View.VISIBLE) continue
        val save = canvas.save()
        canvas.translate(child.left.toFloat() - decor.scrollX, child.top.toFloat() - decor.scrollY)
        child.draw(canvas)
        canvas.restoreToCount(save)
      }
    } finally {
      stages.forEachIndexed { i, tv -> tv.alpha = alphas[i] }
    }
  }

  /** Only the GL stages' last frames (`/dev/screenshot?layer=glass`), for debugging the capture. */
  fun drawStagesOnly(decor: View, canvas: Canvas) {
    val stages = ArrayList<GlassTextureView>()
    collect(decor, stages)
    val loc = IntArray(2)
    stages.forEach { tv ->
      val bmp = runCatching { tv.bitmap }.getOrNull() ?: return@forEach
      tv.getLocationInWindow(loc)
      canvas.drawBitmap(bmp, null, Rect(loc[0], loc[1], loc[0] + tv.width, loc[1] + tv.height), Paint(Paint.FILTER_BITMAP_FLAG))
      bmp.recycle()
    }
  }

  private fun collect(v: View, out: MutableList<GlassTextureView>) {
    if (v is GlassTextureView) {
      if (v.isShown) out += v
      return
    }
    if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
  }

  /** The GL surface: a TextureView whose buffer is [RENDER_SCALE] of its size. */
  internal class GlassTextureView(context: Context, private val r: GlassRenderer) :
      TextureView(context), TextureView.SurfaceTextureListener {
    private val loc = IntArray(2)
    private var bw = 0
    private var bh = 0

    init {
      isOpaque = true
      surfaceTextureListener = this
      importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
      sizeBuffer(st, width, height)
      r.surfaceAvailable(st, bw, bh)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
      // TextureView has just reset the buffer to the full view size; put it back to our scale.
      sizeBuffer(st, width, height)
      r.surfaceResized(bw, bh)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
      r.surfaceDestroyed() // blocks until the render thread has dropped its EGL surface
      return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
      super.onLayout(changed, left, top, right, bottom)
      report()
    }

    private fun sizeBuffer(st: SurfaceTexture, w: Int, h: Int) {
      val (sw, sh) = GlassGeometry.bufferSize(w, h, RENDER_SCALE)
      bw = sw
      bh = sh
      st.setDefaultBufferSize(sw, sh)
      report()
    }

    private fun report() {
      getLocationInWindow(loc)
      val (sw, sh) = if (bw > 0) bw to bh else GlassGeometry.bufferSize(width, height, RENDER_SCALE)
      onStage(loc[0].toFloat(), loc[1].toFloat(), width, height, sw, sh)
    }
  }

  /**
   * The full-screen stage. Animates for a while after resume, a touch ([HomeActivity] pokes on
   * every touch-down), a change of [pokeKey] (e.g. the track) or of the weather, then settles to a
   * static frame; it redraws once a minute only if the palette moved. Stops entirely while the
   * dashboard isn't resumed.
   */
  @Composable
  fun LiquidGlassBackground(modifier: Modifier = Modifier, pokeKey: Any? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val dens = LocalDensity.current.density
    val renderer = remember { createRenderer(context) }
    DisposableEffect(renderer) {
      attach(renderer, dens)
      onDispose {
        detach(renderer)
        renderer.release()
      }
    }
    DisposableEffect(lifecycleOwner, renderer) {
      fun resumed() {
        renderer.setStats(ImmortalSettings.glassFrameLog(context))
        renderer.setVisible(true)
        renderer.poke()
      }
      val obs = LifecycleEventObserver { _, e ->
        when (e) {
          Lifecycle.Event.ON_RESUME -> resumed()
          Lifecycle.Event.ON_PAUSE -> renderer.setVisible(false)
          else -> Unit
        }
      }
      lifecycleOwner.lifecycle.addObserver(obs)
      onDispose {
        lifecycleOwner.lifecycle.removeObserver(obs)
        renderer.setVisible(false)
      }
    }

    // The palette: same inputs as the classic WeatherSky (time of day, sunrise/sunset, conditions).
    val conditions by
        produceState<Weather.Conditions?>(null) {
          while (true) {
            value = withContext(Dispatchers.IO) { runCatching { Weather.fetchConditions(context) }.getOrNull() } ?: value
            delay(if (value == null) 60_000L else 15L * 60 * 1000)
          }
        }
    val now by rememberMinuteTicker()
    val c = conditions
    val palette =
        remember(now, c) {
          fun minuteOf(ms: Long, fallback: Int) =
              if (ms <= 0L) fallback
              else Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
          val cal = Calendar.getInstance().apply { timeInMillis = now }
          GlassPalette.forMoment(
              cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE),
              minuteOf(c?.sunriseMillis ?: 0L, 7 * 60),
              minuteOf(c?.sunsetMillis ?: 0L, 19 * 60),
              c?.code)
        }
    SideEffect { renderer.setPalette(palette) } // equal palettes are ignored: no redraw
    LaunchedEffect(c?.code) { if (c != null) renderer.poke() }
    LaunchedEffect(pokeKey) { renderer.poke() }
    // Watchdog: a stage that never shows a frame (no hardware layer, a wedged driver) must not
    // leave the dashboard on a black background.
    LaunchedEffect(renderer, lifecycleOwner) {
      lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        delay(FIRST_FRAME_TIMEOUT_MS)
        if (!renderer.healthy) {
          Log.w(TAG, "no GL frame after ${FIRST_FRAME_TIMEOUT_MS}ms — classic dashboard for this visit")
          stalled.value = true
        }
      }
    }
    AndroidView(factory = { GlassTextureView(it, renderer) }, modifier = modifier.fillMaxSize())
  }
}

/** See [GlassStage.LiquidGlassBackground]. */
@Composable
internal fun LiquidGlassBackground(modifier: Modifier = Modifier, pokeKey: Any? = null) =
    GlassStage.LiquidGlassBackground(modifier, pokeKey)

/**
 * Registers this element as a liquid-glass panel: its window bounds (reported on every global
 * re-position, de-duplicated) become a rounded-rect lens of [cornerRadius] on the GL stage. Place
 * it before any padding so the bounds are the card's outline. Unregisters when it leaves
 * composition, so a swapped-out card leaves no ghost panel behind.
 */
internal fun Modifier.glassPanel(id: String, cornerRadius: Dp): Modifier = composed {
  val corner = with(LocalDensity.current) { cornerRadius.toPx() }
  DisposableEffect(id) { onDispose { GlassStage.removePanel(id) } }
  Modifier.onGloballyPositioned { coords ->
    val b = coords.boundsInWindow()
    GlassStage.updatePanel(id, b.left, b.top, b.right, b.bottom, corner)
  }
}

/** Faint fill a glass card keeps under its content, for text legibility on bright skies. */
private val GlassCardFill = Color(0x1C0A0E18)

/**
 * A dashboard card's container: with a live glass stage, a glass panel plus a very faint fill
 * (and [glassTint] on top, e.g. the album colour); otherwise the classic [classicFill] and
 * [classicEdge] hairline. One line per card, so new cards get glass for free.
 */
@Composable
internal fun Modifier.dashboardCardSurface(
    radius: Dp,
    classicFill: Brush,
    classicEdge: Color,
    glassTint: Brush? = null,
): Modifier {
  val shape = RoundedCornerShape(radius)
  if (GlassStage.active.value) {
    val id = remember { GlassStage.newPanelId() }
    val m = this.glassPanel(id, radius).clip(shape).background(GlassCardFill)
    return if (glassTint != null) m.background(glassTint) else m
  }
  return this.clip(shape).background(classicFill).border(1.dp, classicEdge, shape)
}
