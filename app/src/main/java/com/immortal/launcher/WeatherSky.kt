/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How the live weather wallpaper looks for one moment: pure, so the mapping is testable. */
internal data class SkyScene(
    val top: Color,
    val bottom: Color,
    /** 0..1 across the day (sunrise → sunset); null at night. */
    val sunProgress: Float?,
    val showMoon: Boolean,
    val stars: Boolean,
    /** 0 = none … 8 = overcast. */
    val clouds: Int,
    val cloudsDark: Boolean,
    val rain: Boolean,
    val snow: Boolean,
)

internal object WeatherScene {
  private val GREY = Color(0xFF6B7A8A) to Color(0xFFA3AEBA)
  private val SLATE = Color(0xFF34404E) to Color(0xFF5B6876)
  private val SNOWY = Color(0xFF8592A2) to Color(0xFFD6DEE7)
  private val STORM = Color(0xFF1E2430) to Color(0xFF3A4250)

  fun isRain(code: Int) = code in 51..67 || code in 80..82 || code in 95..99

  fun isSnow(code: Int) = code in 71..77 || code in 85..86

  fun sceneFor(nowMin: Int, sunriseMin: Int, sunsetMin: Int, code: Int?): SkyScene {
    var (top, bottom) = SkyColors.gradientFor(nowMin, sunriseMin, sunsetMin)
    val day = nowMin in sunriseMin until sunsetMin
    val c = code ?: 0
    // Overcast, rain and snow wash the time-of-day colours toward their own palette.
    val (wash, amount) =
        when {
          c in 95..99 -> STORM to 0.7f
          isRain(c) -> SLATE to 0.6f
          isSnow(c) -> SNOWY to 0.5f
          c == 45 || c == 48 -> GREY to 0.6f
          c == 3 -> GREY to 0.55f
          c == 2 -> GREY to 0.2f
          else -> null to 0f
        }
    if (wash != null) {
      // At night keep it dark: wash less so the sky doesn't glow grey.
      val a = if (day) amount else amount * 0.35f
      top = lerp(top, wash.first, a)
      bottom = lerp(bottom, wash.second, a)
    }
    val clouds =
        when {
          c == 0 -> 0
          c == 1 -> 2
          c == 2 -> 4
          else -> 7
        }
    val clear = c <= 1
    return SkyScene(
        top = top,
        bottom = bottom,
        sunProgress =
            if (day && c <= 2) ((nowMin - sunriseMin).toFloat() / (sunsetMin - sunriseMin).coerceAtLeast(1)) else null,
        showMoon = !day && c <= 2,
        stars = !day && clear,
        clouds = clouds,
        cloudsDark = !day || c >= 51,
        rain = isRain(c),
        snow = isSnow(c),
    )
  }
}

/**
 * The live weather wallpaper: the sky's colour follows the time of day (the real sunrise and
 * sunset), and the current conditions paint over it — sun or moon, stars on clear nights, clouds,
 * rain or snow. Redrawn once a minute (clouds drift a little each time); nothing animates in
 * between, so it costs nothing while the screen just sits there.
 */
@Composable
internal fun WeatherSky(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val conditions by
      produceState<Weather.Conditions?>(null) {
        while (true) {
          value = withContext(Dispatchers.IO) { Weather.fetchConditions(context) } ?: value
          delay(if (value == null) 60_000L else 15L * 60 * 1000)
        }
      }
  val now by rememberMinuteTicker()
  val cal = remember(now) { Calendar.getInstance().apply { timeInMillis = now } }
  val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
  fun minuteOf(ms: Long, fallback: Int) =
      if (ms <= 0L) fallback
      else Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
  val c = conditions
  val scene =
      WeatherScene.sceneFor(nowMin, minuteOf(c?.sunriseMillis ?: 0, 7 * 60), minuteOf(c?.sunsetMillis ?: 0, 19 * 60), c?.code)
  // Fixed per-day layouts so the picture is stable between redraws.
  val seed = cal.get(Calendar.DAY_OF_YEAR)
  val stars = remember(seed) { Random(seed).let { r -> List(90) { Triple(r.nextFloat(), r.nextFloat() * 0.6f, r.nextFloat()) } } }
  val puffs = remember(seed) { Random(seed * 31).let { r -> List(8) { Triple(r.nextFloat(), 0.06f + r.nextFloat() * 0.4f, 0.6f + r.nextFloat() * 0.8f) } } }
  val drops = remember(seed) { Random(seed * 7).let { r -> List(140) { r.nextFloat() to r.nextFloat() } } }

  Canvas(modifier.fillMaxSize()) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(scene.top, scene.bottom)))
    if (scene.stars) {
      stars.forEach { (x, y, a) -> drawCircle(Color.White.copy(alpha = 0.25f + a * 0.5f), radius = 1f + a * 1.6f, center = Offset(x * w, y * h)) }
    }
    val unit = minOf(w, h)
    scene.sunProgress?.let { p ->
      // Along a low arc across the upper sky.
      val cx = w * (0.1f + 0.8f * p)
      val cy = h * (0.32f - 0.22f * sin(PI * p).toFloat())
      val r = unit * 0.07f
      drawCircle(Brush.radialGradient(listOf(Color(0x66FFF3C4), Color.Transparent), Offset(cx, cy), r * 5f), r * 5f, Offset(cx, cy))
      drawCircle(Color(0xFFFFF4D6), r, Offset(cx, cy))
    }
    if (scene.showMoon) {
      val cx = w * 0.78f
      val cy = h * 0.14f
      val r = unit * 0.05f
      drawCircle(Brush.radialGradient(listOf(Color(0x33E8EEFF), Color.Transparent), Offset(cx, cy), r * 4f), r * 4f, Offset(cx, cy))
      drawCircle(Color(0xFFE9EDF5), r, Offset(cx, cy))
      drawCircle(scene.top, r * 0.92f, Offset(cx + r * 0.45f, cy - r * 0.2f)) // crescent
    }
    // Clouds: soft clusters of overlapping glows, drifting ~1% of the width a minute.
    val drift = (nowMin % 100) / 100f
    val cloudColor = if (scene.cloudsDark) Color(0x2EC8D0DC) else Color(0x66FFFFFF)
    puffs.take(scene.clouds).forEachIndexed { i, (x, y, s) ->
      val cx = ((x + drift * (0.3f + i * 0.05f)) % 1.2f - 0.1f) * w
      val cy = y * h
      val r = unit * 0.12f * s
      for (k in -2..2) {
        val pc = Offset(cx + k * r * 0.7f, cy - (2 - kotlin.math.abs(k)) * r * 0.18f)
        drawCircle(Brush.radialGradient(listOf(cloudColor, Color.Transparent), pc, r), r, pc)
      }
    }
    if (scene.rain) {
      drops.forEach { (x, y) ->
        val start = Offset(x * w, y * h)
        drawLine(Color(0x40D8E4F0), start, start + Offset(-unit * 0.012f, unit * 0.04f), strokeWidth = 2f, cap = StrokeCap.Round)
      }
    }
    if (scene.snow) {
      drops.forEach { (x, y) -> drawCircle(Color(0x99FFFFFF), 2.5f + (x * 7 % 1f) * 2f, Offset(x * w, y * h)) }
    }
  }
}
