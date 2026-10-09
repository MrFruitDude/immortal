/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The pure, unit-tested half of the liquid-glass dashboard ([GlassStage] / [GlassRenderer]): what
 * colours the animated background uses, where a Compose card lands in the GL buffer, and when the
 * render thread may draw. Nothing here touches Android or GL.
 */

/**
 * The colours and switches the glass background's shader takes as uniforms, for one moment. Built
 * from the same time-of-day + weather mapping the classic [WeatherSky] uses ([WeatherScene]), so
 * both looks agree on what the sky "is" right now.
 */
internal data class GlassPalette(
    /** Vertical base gradient (top of the screen, bottom). */
    val top: Color,
    val bottom: Color,
    /** Three mesh-gradient blobs drifting over the base. */
    val blobA: Color,
    val blobB: Color,
    val blobC: Color,
    /** Aurora ribbon colour and strength (0 = none). */
    val ribbon: Color,
    val aurora: Float,
    /** Star brightness, 0 by day. */
    val stars: Float,
    /** Sun / moon glow: position in 0..1 screen space (x right, y DOWN, like Compose), strength. */
    val glowX: Float,
    val glowY: Float,
    val glow: Float,
    val glowColor: Color,
) {
  companion object {
    private val NIGHT_VIOLET = Color(0xFF4B3A9A)
    private val NIGHT_TEAL = Color(0xFF167A86)
    private val NIGHT_INDIGO = Color(0xFF1D2C78)
    private val AURORA_GREEN = Color(0xFF3CF2B0)
    private val DAY_PEACH = Color(0xFFFFB98A)
    private val DAY_CYAN = Color(0xFF7FE3FF)
    private val DAY_LILAC = Color(0xFFB7A4FF)
    private val SUN = Color(0xFFFFE6B0)
    private val MOON = Color(0xFFCFDBFF)
    private val GREY = Color(0xFF8A96A6)

    /**
     * The palette for [nowMin] (minute of day) given today's sunrise/sunset and the WMO weather
     * [code] (null when unknown — treated as clear, like [WeatherScene]).
     */
    fun forMoment(nowMin: Int, sunriseMin: Int, sunsetMin: Int, code: Int?): GlassPalette {
      val scene = WeatherScene.sceneFor(nowMin, sunriseMin, sunsetMin, code)
      val day = nowMin in sunriseMin until sunsetMin
      val c = code ?: 0
      val wet = WeatherScene.isRain(c) || WeatherScene.isSnow(c) || c == 45 || c == 48
      val overcast = c >= 3
      // How far the accents are pulled toward grey: clear skies keep their colour, rain loses it.
      val wash =
          when {
            c in 95..99 -> 0.7f
            wet -> 0.6f
            overcast -> 0.45f
            c == 2 -> 0.15f
            else -> 0f
          }
      val a: Color
      val b: Color
      val cc: Color
      if (day) {
        a = lerp(scene.top, DAY_CYAN, 0.45f)
        b = lerp(scene.bottom, DAY_PEACH, 0.6f)
        cc = lerp(scene.top, DAY_LILAC, 0.5f)
      } else {
        a = lerp(scene.top, NIGHT_VIOLET, 0.7f)
        b = lerp(scene.bottom, NIGHT_TEAL, 0.6f)
        cc = lerp(scene.top, NIGHT_INDIGO, 0.6f)
      }
      val greyed = { col: Color -> if (wash > 0f) lerp(col, lerp(GREY, scene.top, 0.4f), wash) else col }
      val aurora =
          when {
            wet || c in 95..99 -> 0f
            overcast -> 0.12f
            !day && c <= 1 -> 0.85f
            !day -> 0.45f
            c <= 1 -> 0.3f
            else -> 0.2f
          }
      val stars =
          when {
            scene.stars -> 1f
            !day && c == 2 -> 0.4f
            else -> 0f
          }
      // Sun follows the classic sky's arc; the moon sits upper right.
      val p = scene.sunProgress
      val (gx, gy, g, gc) =
          when {
            p != null -> Quad(0.1f + 0.8f * p, 0.32f - 0.22f * sin(PI * p).toFloat(), 0.75f, SUN)
            scene.showMoon -> Quad(0.78f, 0.14f, 0.35f, MOON)
            else -> Quad(0.5f, 0.2f, 0f, SUN)
          }
      return GlassPalette(
          top = scene.top,
          bottom = scene.bottom,
          blobA = greyed(a),
          blobB = greyed(b),
          blobC = greyed(cc),
          ribbon = if (day) lerp(Color.White, DAY_CYAN, 0.3f) else lerp(AURORA_GREEN, NIGHT_TEAL, 0.25f),
          aurora = aurora,
          stars = stars,
          glowX = gx,
          glowY = gy,
          glow = g,
          glowColor = gc,
      )
    }

    private data class Quad(val x: Float, val y: Float, val s: Float, val c: Color)
  }
}

/** Converts Compose window coordinates into the GL buffer's (scaled, y-up) pixel space. */
internal object GlassGeometry {
  /**
   * A panel in the GL buffer: [cx]/[cy] centre and [hw]/[hh] half extents in buffer pixels, with
   * the origin at the BOTTOM-left (GL's `gl_FragCoord`), and [radius] in buffer pixels.
   */
  data class Panel(val cx: Float, val cy: Float, val hw: Float, val hh: Float, val radius: Float)

  /** The GL buffer for a [viewW]×[viewH] view drawn at [scale] (e.g. 0.5 = half resolution). */
  fun bufferSize(viewW: Int, viewH: Int, scale: Float): Pair<Int, Int> =
      max(1, (viewW * scale).roundToInt()) to max(1, (viewH * scale).roundToInt())

  /**
   * A card's bounds in window pixels ([left]..[bottom], y down) → the GL buffer of a stage whose
   * top-left sits at ([stageLeft], [stageTop]) in the window and whose view is [stageW]×[stageH],
   * rendering into a [bufW]×[bufH] buffer. [cornerPx] is the card's corner radius in window pixels.
   * Null when the card doesn't overlap the stage (or is empty).
   */
  fun toBuffer(
      left: Float,
      top: Float,
      right: Float,
      bottom: Float,
      cornerPx: Float,
      stageLeft: Float,
      stageTop: Float,
      stageW: Int,
      stageH: Int,
      bufW: Int,
      bufH: Int,
  ): Panel? {
    if (stageW <= 0 || stageH <= 0 || bufW <= 0 || bufH <= 0) return null
    // Into the stage's own coordinates, clipped to it.
    val l = max(left - stageLeft, 0f)
    val t = max(top - stageTop, 0f)
    val r = min(right - stageLeft, stageW.toFloat())
    val b = min(bottom - stageTop, stageH.toFloat())
    if (r - l < 1f || b - t < 1f) return null
    val sx = bufW.toFloat() / stageW
    val sy = bufH.toFloat() / stageH
    val hw = (r - l) * sx / 2f
    val hh = (b - t) * sy / 2f
    val cx = (l + r) / 2f * sx
    // Flip: window y grows downward, gl_FragCoord.y grows upward.
    val cy = bufH - (t + b) / 2f * sy
    val radius = (cornerPx * min(sx, sy)).coerceIn(0f, min(hw, hh))
    return Panel(cx, cy, hw, hh, radius)
  }
}

/**
 * When the glass stage may draw. The background animates only for a short while after something
 * happens (a touch, a new track, the weather changing, a card appearing), eases to a stop, and
 * then sits as a static frame — redrawn only when its palette or a card's rect changes. Nothing is
 * scheduled at all while the dashboard isn't resumed.
 */
internal object GlassFramePolicy {
  /** Animate this long after the last poke. */
  const val ACTIVE_MS = 20_000L

  /** ~30 fps cap while animating. */
  const val FRAME_MS = 33L

  /** Time constant of the speed easing (start and stop), so motion never pops. */
  const val EASE_MS = 900L

  /** Below this the drift is invisible and the stage settles to a static frame. */
  const val REST_SPEED = 0.01f

  /** The animation clock wraps at this period; every shader frequency is a whole multiple of it. */
  const val PERIOD_S = 600f

  /** Whether the animation window from the last poke is still open. */
  fun active(nowMs: Long, lastPokeMs: Long): Boolean = lastPokeMs > 0L && nowMs - lastPokeMs in 0 until ACTIVE_MS

  /** The speed the drift heads toward right now: 1 while active, 0 otherwise. */
  fun targetSpeed(nowMs: Long, lastPokeMs: Long): Float = if (active(nowMs, lastPokeMs)) 1f else 0f

  /** One easing step of the drift speed from [current] toward [target] over [dtMs]. */
  fun approach(current: Float, target: Float, dtMs: Long): Float {
    val k = (dtMs.coerceAtLeast(0L).toFloat() / EASE_MS).coerceIn(0f, 1f)
    val next = current + (target - current) * k
    // Snap the tail so a settling stage actually reaches rest instead of creeping forever.
    return if (target == 0f && next < REST_SPEED) 0f else next.coerceIn(0f, 1f)
  }

  /**
   * Milliseconds until the next frame after one that took [frameCostMs], or null to schedule
   * nothing (static until the next poke or redraw request).
   */
  fun nextDelayMs(nowMs: Long, lastPokeMs: Long, visible: Boolean, speed: Float, frameCostMs: Long): Long? {
    if (!visible) return null
    if (!active(nowMs, lastPokeMs) && speed <= 0f) return null
    return (FRAME_MS - frameCostMs).coerceIn(0L, FRAME_MS)
  }

  /** Advance the wrapped animation clock (seconds) by [dtMs] at [speed]. */
  fun advance(clockS: Float, dtMs: Long, speed: Float): Float {
    // Clamp a long gap (thread hiccup, first frame after rest) so the drift never jumps.
    val dt = dtMs.coerceIn(0L, 100L) / 1000f
    val t = clockS + dt * speed
    return if (t >= PERIOD_S) t - PERIOD_S else t
  }
}
