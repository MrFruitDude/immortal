/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlfredAvatarTest {

  private fun frame(a: AlfredAvatar, mode: Alfred.Mode, t: Float, happy: Float = 0f, level: Float = 0.5f): IntArray {
    // Run a second of frames first so the palette blend and timers settle.
    var tt = t - 1f
    while (tt < t) {
      a.render(AlfredAvatar.Pose(mode, tt, tt - (t - 1f) + 0.5f, level, happy))
      tt += 0.04f
    }
    a.render(AlfredAvatar.Pose(mode, t, 1.5f, level, happy))
    return a.pixels.copyOf()
  }

  @Test
  fun everyModeDrawsACharacterOnBlack() {
    for (mode in Alfred.Mode.values()) {
      val px = frame(AlfredAvatar(), mode, 3f)
      assertEquals("corner stays background in $mode", 0xff000000.toInt(), px[0])
      val centre = px[38 * 64 + 32]
      assertNotEquals("Alfred's body covers the centre in $mode", 0xff000000.toInt(), centre)
      assertTrue("a varied, shaded frame in $mode", px.toSet().size > 12)
    }
  }

  @Test
  fun animatesOverTime() {
    val a = AlfredAvatar()
    val f1 = frame(a, Alfred.Mode.SPEAKING, 2f)
    val f2 = frame(a, Alfred.Mode.SPEAKING, 2.37f)
    assertTrue("speaking frames differ", !f1.contentEquals(f2))
  }

  @Test
  fun accentFollowsTheMode() {
    val a = AlfredAvatar()
    frame(a, Alfred.Mode.ERROR, 4f)
    val r = (a.accent() shr 16) and 0xff
    assertTrue("error accent is red-ish", r > 200)
  }

  /** `ALFRED_DUMP=/some/dir ./gradlew testDebugUnitTest --tests '*AlfredAvatarTest*'` writes PNGs. */
  @Test
  fun dumpFramesWhenAsked() {
    val dir = System.getenv("ALFRED_DUMP") ?: return
    File(dir).mkdirs()
    val modes = Alfred.Mode.values().map { it to 0f } + (Alfred.Mode.IDLE to 1f)
    val sheet = BufferedImage(64 * 8 * modes.size, 64 * 8, BufferedImage.TYPE_INT_RGB)
    modes.forEachIndexed { k, (mode, happy) ->
      val px = frame(AlfredAvatar(), mode, 3.3f, happy)
      for (y in 0 until 64 * 8) for (x in 0 until 64 * 8) sheet.setRGB(k * 512 + x, y, px[(y / 8) * 64 + x / 8])
    }
    ImageIO.write(sheet, "png", File(dir, "alfred-modes.png"))
  }
}
