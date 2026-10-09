/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the liquid-glass dashboard: palette mapping, rect conversion, frame policy. */
class GlassModelTest {

  private val sunrise = 7 * 60
  private val sunset = 19 * 60

  // --- palette ------------------------------------------------------------------------------

  @Test
  fun clearNight_hasStarsAuroraAndMoon() {
    val p = GlassPalette.forMoment(23 * 60, sunrise, sunset, 0)
    assertEquals(1f, p.stars)
    assertTrue("a clear night gets a strong aurora", p.aurora > 0.5f)
    assertTrue("the moon glows", p.glow > 0f)
    assertEquals(0.78f, p.glowX, 1e-4f)
  }

  @Test
  fun clearNoon_hasSunNoStars_andFollowsTheSkyGradient() {
    val p = GlassPalette.forMoment(13 * 60, sunrise, sunset, 0)
    assertEquals(0f, p.stars)
    assertTrue(p.glow > 0f)
    // Halfway through the day the sun sits mid-screen at the top of its arc.
    assertEquals(0.5f, p.glowX, 0.01f)
    assertEquals(0.10f, p.glowY, 0.01f)
    val scene = WeatherScene.sceneFor(13 * 60, sunrise, sunset, 0)
    assertEquals(scene.top, p.top)
    assertEquals(scene.bottom, p.bottom)
  }

  @Test
  fun rain_turnsOffAuroraStarsAndSun_andGreysTheBlobs() {
    val clear = GlassPalette.forMoment(13 * 60, sunrise, sunset, 0)
    val rain = GlassPalette.forMoment(13 * 60, sunrise, sunset, 63)
    assertEquals(0f, rain.aurora)
    assertEquals(0f, rain.stars)
    assertEquals(0f, rain.glow)
    val sat = { p: GlassPalette -> saturation(p.blobA) + saturation(p.blobB) + saturation(p.blobC) }
    assertTrue("rain desaturates the accents: ${sat(rain)} vs ${sat(clear)}", sat(rain) < sat(clear) * 0.8f)
  }

  @Test
  fun unknownWeather_isTreatedAsClear() {
    assertEquals(GlassPalette.forMoment(22 * 60, sunrise, sunset, 0), GlassPalette.forMoment(22 * 60, sunrise, sunset, null))
  }

  @Test
  fun palette_isStableWithinAMinute_soARedrawIsSkipped() {
    assertEquals(GlassPalette.forMoment(600, sunrise, sunset, 2), GlassPalette.forMoment(600, sunrise, sunset, 2))
    assertNotEquals(GlassPalette.forMoment(600, sunrise, sunset, 2), GlassPalette.forMoment(1300, sunrise, sunset, 2))
  }

  @Test
  fun blobs_areDistinctColours() {
    for (min in listOf(3 * 60, 7 * 60, 12 * 60, 18 * 60 + 50, 21 * 60)) {
      val p = GlassPalette.forMoment(min, sunrise, sunset, 1)
      assertNotEquals(p.blobA, p.blobB)
      assertNotEquals(p.blobB, p.blobC)
    }
  }

  private fun saturation(c: Color): Float {
    val mx = max(c.red, max(c.green, c.blue))
    val mn = min(c.red, min(c.green, c.blue))
    return if (mx <= 0f) 0f else (mx - mn) / mx
  }

  // --- geometry ------------------------------------------------------------------------------

  @Test
  fun bufferSize_isHalfAndNeverZero() {
    assertEquals(400 to 640, GlassGeometry.bufferSize(800, 1280, 0.5f))
    assertEquals(960 to 540, GlassGeometry.bufferSize(1920, 1080, 0.5f))
    assertEquals(1 to 1, GlassGeometry.bufferSize(1, 1, 0.5f))
  }

  @Test
  fun toBuffer_scalesAndFlipsY() {
    // Mini in portrait: an 800x1280 stage at the window origin, half-res buffer.
    val p = GlassGeometry.toBuffer(32f, 300f, 432f, 500f, 26f, 0f, 0f, 800, 1280, 400, 640)!!
    assertEquals(116f, p.cx, 1e-4f) // (32+432)/2 * 0.5
    assertEquals(100f, p.hw, 1e-4f) // 400/2 * 0.5
    assertEquals(50f, p.hh, 1e-4f) // 200/2 * 0.5
    assertEquals(640f - 200f, p.cy, 1e-4f) // y up: 640 - 400*0.5
    assertEquals(13f, p.radius, 1e-4f)
  }

  @Test
  fun toBuffer_subtractsTheStageOrigin() {
    val atOrigin = GlassGeometry.toBuffer(100f, 100f, 300f, 200f, 10f, 0f, 0f, 1000, 1000, 1000, 1000)!!
    val offset = GlassGeometry.toBuffer(150f, 120f, 350f, 220f, 10f, 50f, 20f, 1000, 1000, 1000, 1000)!!
    assertEquals(atOrigin, offset)
  }

  @Test
  fun toBuffer_clipsToTheStage_andClampsTheRadius() {
    val p = GlassGeometry.toBuffer(-50f, 0f, 50f, 20f, 100f, 0f, 0f, 200, 200, 200, 200)!!
    assertEquals(25f, p.hw, 1e-4f) // clipped to 0..50
    assertEquals(10f, p.hh, 1e-4f)
    assertEquals(10f, p.radius, 1e-4f) // never more than half the short side
  }

  @Test
  fun toBuffer_offStageOrEmpty_isNull() {
    assertNull(GlassGeometry.toBuffer(900f, 0f, 1000f, 100f, 10f, 0f, 0f, 800, 1280, 400, 640))
    assertNull(GlassGeometry.toBuffer(10f, 10f, 10f, 100f, 10f, 0f, 0f, 800, 1280, 400, 640))
    assertNull(GlassGeometry.toBuffer(10f, 10f, 100f, 100f, 10f, 0f, 0f, 0, 0, 0, 0))
  }

  // --- frame policy ----------------------------------------------------------------------------

  @Test
  fun notVisible_neverSchedules() {
    assertNull(GlassFramePolicy.nextDelayMs(1_000L, 1_000L, visible = false, speed = 1f, frameCostMs = 0L))
  }

  @Test
  fun active_runsAtAbout30fps_minusTheFrameCost() {
    val now = 50_000L
    assertEquals(33L, GlassFramePolicy.nextDelayMs(now, now - 1_000L, true, 1f, 0L))
    assertEquals(23L, GlassFramePolicy.nextDelayMs(now, now - 1_000L, true, 1f, 10L))
    assertEquals(0L, GlassFramePolicy.nextDelayMs(now, now - 1_000L, true, 1f, 80L))
  }

  @Test
  fun afterTheWindow_keepsDrawingOnlyWhileEasingOut_thenRests() {
    val poke = 10_000L
    val after = poke + GlassFramePolicy.ACTIVE_MS + 1
    assertEquals(0f, GlassFramePolicy.targetSpeed(after, poke))
    assertNotNull("still easing to a stop", GlassFramePolicy.nextDelayMs(after, poke, true, 0.5f, 0L))
    assertNull("at rest: static frame, nothing scheduled", GlassFramePolicy.nextDelayMs(after, poke, true, 0f, 0L))
  }

  @Test
  fun neverPoked_isStatic() {
    assertEquals(0f, GlassFramePolicy.targetSpeed(5_000L, 0L))
    assertNull(GlassFramePolicy.nextDelayMs(5_000L, 0L, true, 0f, 0L))
  }

  @Test
  fun easing_reachesRestInAFewSeconds_andRampsUpSmoothly() {
    var s = 1f
    var t = 0L
    while (s > 0f && t < 10_000L) {
      s = GlassFramePolicy.approach(s, 0f, GlassFramePolicy.FRAME_MS)
      t += GlassFramePolicy.FRAME_MS
    }
    assertEquals(0f, s)
    assertTrue("settles within ~5 s, took $t ms", t <= 5_000L)
    val first = GlassFramePolicy.approach(0f, 1f, GlassFramePolicy.FRAME_MS)
    assertTrue("no pop on start: $first", first > 0f && first < 0.1f)
  }

  @Test
  fun clock_wrapsAtThePeriod_andClampsLongGaps() {
    val near = GlassFramePolicy.PERIOD_S - 0.01f
    val wrapped = GlassFramePolicy.advance(near, 50L, 1f)
    assertTrue(wrapped in 0f..0.05f)
    // A 10 s hiccup advances at most 100 ms of animation.
    assertEquals(0.1f, GlassFramePolicy.advance(0f, 10_000L, 1f), 1e-4f)
    assertEquals(0f, GlassFramePolicy.advance(0f, 33L, 0f), 0f)
  }
}
