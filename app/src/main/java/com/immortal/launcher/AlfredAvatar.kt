/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Alfred, drawn procedurally as 64x64 pixel art: an eager office nerd — a messy brown mop, big
 * round tortoiseshell glasses, a huge grin with braces, grey suit, white button-down, brown
 * polka-dot tie and a pocket protector full of pens — in the style the Muse gadgets use for their
 * avatars (ordered-dither shading, a hard 1 px outline, top-left light, a mode-tinted rim and
 * aura). Chibi proportions keep the face readable at 64 px.
 *
 * He has the gadgets' states — boot, idle, listening, thinking, speaking, error, off — plus a
 * happy reaction when petted. Pure Kotlin over an IntArray: renders off any thread and is
 * unit-testable; the UI scales the frame up with nearest-neighbour filtering. The character is
 * this project's own; only the gadgets' public avatar contract (modes, beats, per-mode colour
 * schemes) is followed.
 */
class AlfredAvatar(seed: Int = 7, private val transparentBackground: Boolean = false) {
  data class Pose(
      val mode: Alfred.Mode,
      /** Seconds since start. */
      val t: Float,
      /** Seconds in the current mode. */
      val modeT: Float,
      /** 0..1 live audio level (mic when listening, voice when speaking). */
      val level: Float,
      /** 0..1 petting reaction, eased out by the caller. */
      val happy: Float,
  )

  /** The frame, ARGB, row-major [W]x[H]. */
  val pixels = IntArray(W * H)

  private val idx = ByteArray(W * H)
  private val part = ByteArray(W * H)
  private val rnd = Random(seed)

  private val glow = FloatArray(12)
  private val accent = FloatArray(3)
  private var lastT = -1f
  private var blinkAt = 2f
  private var blinkDouble = false
  private var gazeX = 0f
  private var gazeY = 0f
  private var gazeTX = 0f
  private var gazeTY = 0f
  private var gazeNext = 1.5f
  private val sparklePhase = FloatArray(SPARKLES) { rnd.nextFloat() * 6.283f }
  private val sparkleSpeed = FloatArray(SPARKLES) { 0.5f + rnd.nextFloat() * 0.6f }

  init {
    val s = SCHEMES[Alfred.Mode.BOOT]!!
    for (i in 0 until 4) for (c in 0 until 3) glow[i * 3 + c] = channel(s[i], c)
    for (c in 0 until 3) accent[c] = channel(s[4], c)
  }

  /** The UI's accent colour for the current (blended) mode, 0xRRGGBB. */
  fun accent(): Int = rgb(accent[0], accent[1], accent[2])

  fun render(p: Pose) {
    val dt = if (lastT < 0) 0.04f else (p.t - lastT).coerceIn(0f, 0.2f)
    lastT = p.t
    blendScheme(p.mode, dt)
    timers(p, dt)
    idx.fill(BG)
    part.fill(0)

    val happy = if (p.mode == Alfred.Mode.ERROR) 0f else p.happy.coerceIn(0f, 1f)
    val breathe = sin(p.t * 1.8f) * 0.03f
    var cx = 32f
    var bob = 0f
    var squash = 1f
    when (p.mode) {
      Alfred.Mode.BOOT -> squash = 0.6f + 0.4f * easeOutBack(min(1f, p.modeT / 0.6f))
      Alfred.Mode.IDLE -> bob = sin(p.t * 1.6f) * 0.7f
      Alfred.Mode.LISTENING -> bob = -0.5f
      Alfred.Mode.THINKING -> cx += sin(p.t * 0.9f) * 0.8f + 0.6f
      Alfred.Mode.SPEAKING -> bob = -abs(sin(p.t * 9f)) * (0.5f + p.level * 1.4f)
      Alfred.Mode.ERROR -> if (p.modeT < 0.6f) cx += sin(p.modeT * 40f) * 2f * (1f - p.modeT / 0.6f)
      Alfred.Mode.OFF -> squash = 1f - min(1f, p.modeT / 1.3f) * 0.06f
    }
    if (happy > 0f) bob -= abs(sin(p.t * 11f)) * 3f * happy

    val feetY = 57f
    // The torso squashes on boot; the head rides on top of it.
    val hipY = 50f + bob * 0.5f
    val shoulderY = hipY - 14f * squash * (1f + breathe)
    val headCy = shoulderY - 14f + bob * 0.4f
    val headA = 12.2f
    val headB = 13.2f * (0.92f + 0.08f * squash)

    drawAura(p, cx, headCy + 8f, happy)
    drawShadow(cx, feetY + 0.5f, 26f)
    drawLegs(p, cx, hipY, feetY)
    drawArms(p, cx, shoulderY, happy, back = true)
    drawTorso(cx, shoulderY, hipY, breathe)
    drawNeck(cx, headCy + headB - 2f, shoulderY)
    drawEars(cx, headCy, headA)
    drawHead(cx, headCy, headA, headB)
    drawHair(p, cx, headCy, headA, headB)
    drawArms(p, cx, shoulderY, happy, back = false)
    outline()
    rimLight(cx, headCy)
    drawShirtFront(cx, shoulderY, hipY)
    drawPocket(cx, shoulderY)
    val eyeY = headCy + 1.5f
    drawEyes(p, cx, eyeY, happy)
    drawGlasses(cx, eyeY)
    drawBrows(p, cx, eyeY, happy)
    drawNose(cx, eyeY)
    drawMouth(p, cx, headCy + 7.5f, happy)
    if (happy > 0.15f || p.mode == Alfred.Mode.SPEAKING) blush(cx, eyeY + 4f)
    drawExtras(p, cx, headCy - headB, happy)
    drawSparkles(p, cx, headCy + 8f, front = true)
    resolve()
  }

  // --- scheme & timers -----------------------------------------------------------------

  private fun blendScheme(mode: Alfred.Mode, dt: Float) {
    val s = SCHEMES[mode]!!
    val k = 1f - exp(-dt * 7f)
    for (i in 0 until 4) for (c in 0 until 3) glow[i * 3 + c] += (channel(s[i], c) - glow[i * 3 + c]) * k
    for (c in 0 until 3) accent[c] += (channel(s[4], c) - accent[c]) * k
  }

  private fun timers(p: Pose, dt: Float) {
    if (p.t > blinkAt + 0.16f * (if (blinkDouble) 2.4f else 1f)) {
      blinkAt = p.t + 2.2f + rnd.nextFloat() * 3f
      blinkDouble = rnd.nextFloat() < 0.25f
    }
    if (p.t > gazeNext) {
      gazeTX = (rnd.nextFloat() - 0.5f) * 2.2f
      gazeTY = (rnd.nextFloat() - 0.5f) * 0.9f
      gazeNext = p.t + 1.2f + rnd.nextFloat() * 2.4f
    }
    when (p.mode) {
      Alfred.Mode.LISTENING, Alfred.Mode.SPEAKING -> {
        gazeTX = 0f
        gazeTY = 0f
      }
      Alfred.Mode.THINKING -> {
        gazeTX = sin(p.t * 1.3f) * 1.2f
        gazeTY = -1f
      }
      else -> Unit
    }
    val k = 1f - exp(-dt * 14f)
    gazeX += (gazeTX - gazeX) * k
    gazeY += (gazeTY - gazeY) * k
    for (i in 0 until SPARKLES) sparklePhase[i] += dt * sparkleSpeed[i] * (if (p.mode == Alfred.Mode.THINKING) 2.2f else 1f)
  }

  private fun blinking(p: Pose): Boolean {
    if (p.mode == Alfred.Mode.BOOT && p.modeT < 0.9f) return true
    if (p.mode == Alfred.Mode.OFF && p.modeT > 0.8f) return true
    val d = p.t - blinkAt
    if (d in 0f..0.16f) return true
    return blinkDouble && d in 0.24f..0.4f
  }

  // --- background ----------------------------------------------------------------------

  private fun drawAura(p: Pose, cx: Float, cy: Float, happy: Float) {
    val fade = when (p.mode) {
      Alfred.Mode.OFF -> max(0f, 1f - p.modeT / 1.3f)
      Alfred.Mode.BOOT -> min(1f, p.modeT / 0.8f)
      else -> 1f
    }
    // A sparse dithered halo only — the UI paints the soft glow; a solid fill reads as a cape.
    val pulse = 0.85f + 0.15f * sin(p.t * 2.2f) + p.level * 0.25f + happy * 0.2f
    for (y in 0 until H) for (x in 0 until W) {
      val dx = (x - cx) / 30f
      val dy = (y - cy) / 33f
      val r = sqrt(dx * dx + dy * dy)
      val a = (1f - abs(r - 0.86f) / 0.14f) * pulse * fade
      if (a > 0.55f + bayer(x, y) * 0.6f && (hash(x, y) and 3) == 0) idx[y * W + x] = AURA2
    }
    if (p.mode == Alfred.Mode.LISTENING || p.mode == Alfred.Mode.SPEAKING) {
      for (k in 0 until 3) {
        val r = ((p.t * 14f + k * 8f) % 24f) + 19f + p.level * 4f
        val n = (r * 2.2f).toInt()
        for (i in 0 until n step 2) {
          val ang = i * 6.2832f / n
          plot((cx + cos(ang) * r).toInt(), (cy + sin(ang) * r * 1.05f).toInt(), if (r < 31f) GLOW1 else GLOW2)
        }
      }
    }
    drawSparkles(p, cx, cy, front = false)
  }

  private fun drawSparkles(p: Pose, cx: Float, cy: Float, front: Boolean) {
    val count = if (p.mode == Alfred.Mode.BOOT) min(SPARKLES, (p.modeT * 6).toInt()) else SPARKLES
    for (i in 0 until count) {
      val a = sparklePhase[i]
      if ((sin(a) > 0) != front) continue
      val x = (cx + cos(a) * (23f + (i % 3) * 3f)).toInt()
      val y = (cy - 8f + sin(a) * 6f - (i % 4) * 5f + sin(p.t * 2f + i) * 2f).toInt()
      val tw = sin(p.t * 5f + i * 1.7f) > 0.3f
      plot(x, y, if (tw) SPARKLE else GLOW1)
      if (tw && i % 2 == 0) {
        plot(x - 1, y, GLOW1); plot(x + 1, y, GLOW1); plot(x, y - 1, GLOW1); plot(x, y + 1, GLOW1)
      }
    }
  }

  private fun drawShadow(cx: Float, y: Float, w: Float) {
    for (yy in (y - 2).toInt()..(y + 2).toInt()) for (xx in (cx - w * 0.55f).toInt()..(cx + w * 0.55f).toInt()) {
      val dx = (xx - cx) / (w * 0.55f)
      val dy = (yy - y) / 2.2f
      val d = dx * dx + dy * dy
      if (d < 1f && bayer(xx, yy) < 0.75f - d * 0.5f) plot(xx, yy, SHADOW)
    }
  }

  // --- body --------------------------------------------------------------------------

  private fun drawLegs(p: Pose, cx: Float, hipY: Float, feetY: Float) {
    for (side in intArrayOf(-1, 1)) {
      val shuffle = if (p.mode == Alfred.Mode.SPEAKING) max(0f, sin(p.t * 8f + side)) * 1.2f else 0f
      val lx = cx + side * 3.4f
      for (y in hipY.toInt()..(feetY - 1.5f - shuffle).toInt()) for (x in (lx - 2.4f).toInt()..(lx + 2.4f).toInt()) {
        if (!inside(x, y)) continue
        shade(x, y, (x + 0.5f - lx) / 2.6f, 0f, SUIT, 4, P_LEGS)
      }
      // Shoes: dark brown loafers.
      val sy = feetY - 1f - shuffle
      for (y in (sy - 1.5f).toInt()..(sy + 1.5f).toInt()) for (x in (lx - 3.5f + side).toInt()..(lx + 3.5f + side).toInt()) {
        val u = (x + 0.5f - lx - side) / 3.6f
        val v = (y + 0.5f - sy) / 1.6f
        if (u * u + v * v <= 1f && inside(x, y)) shade(x, y, u, v, SHOE, 2, P_LEGS)
      }
    }
  }

  private fun drawTorso(cx: Float, shoulderY: Float, hipY: Float, breathe: Float) {
    val cy = (shoulderY + hipY) / 2f
    val a = 12.4f * (1f + breathe)
    val b = (hipY - shoulderY) / 2f + 1f
    for (y in (cy - b).toInt()..(cy + b).toInt()) for (x in (cx - a - 1).toInt()..(cx + a + 1).toInt()) {
      if (!inside(x, y)) continue
      val u = (x + 0.5f - cx) / a
      val v = (y + 0.5f - cy) / b
      // Boxy shoulders, slightly narrower at the waist.
      val uu = u / (1f - max(0f, v) * 0.1f)
      if (abs(uu).pow(3f) + abs(v).pow(2.6f) > 1f) continue
      shade(x, y, uu * 0.9f, v * 0.7f, SUIT, 4, P_BODY)
    }
  }

  /** Shirt V, collar points, tie with polka dots, and the lapel edges. */
  private fun drawShirtFront(cx: Float, shoulderY: Float, hipY: Float) {
    val top = shoulderY.toInt()
    val bottom = (hipY - 3f).toInt()
    for (y in top..bottom) {
      val half = 1f + (y - top) * 0.42f
      if (half > 5.5f) break
      for (x in (cx - half).toInt()..(cx + half).toInt()) {
        if (!inside(x, y) || part[y * W + x] != P_BODY) continue
        val edge = abs(x + 0.5f - cx) > half - 1f
        idx[y * W + x] = if (edge) SUIT // lapel edge
        else if (x + 0.5f < cx) SHIRT_LIGHT else SHIRT_SHADE
      }
    }
    // Jacket hem, so the trousers read as separate.
    val hem = (hipY + 1f).toInt()
    for (x in (cx - 10f).toInt()..(cx + 10f).toInt()) if (inside(x, hem) && part[hem * W + x] != 0.toByte()) idx[hem * W + x] = SUIT
    // Button-down collar points.
    plot(cx.toInt() - 3, top, SHIRT_LIGHT); plot(cx.toInt() - 2, top + 1, SHIRT_LIGHT)
    plot(cx.toInt() + 2, top, SHIRT_SHADE); plot(cx.toInt() + 1, top + 1, SHIRT_SHADE)
    // The tie: knot, then widening to a point.
    val tx = cx.toInt()
    plot(tx - 1, top + 1, TIE); plot(tx, top + 1, TIE); plot(tx, top + 2, TIE_DOT)
    for (y in (top + 2)..min(bottom + 2, top + 13)) {
      val w = if (y > top + 11) 0 else if (y > top + 3) 1 else 0
      for (x in tx - w..tx + w) {
        if (!inside(x, y)) continue
        idx[y * W + x] = if ((x + y * 2) % 3 == 0) TIE_DOT else if (x > tx) TIE_DARK else TIE
      }
    }
  }

  /** Pocket protector with orange, blue and white pens (his left = our right). */
  private fun drawPocket(cx: Float, shoulderY: Float) {
    val px = (cx + 5f).toInt()
    val py = (shoulderY + 6f).toInt()
    for (y in py..py + 2) for (x in px..px + 4) if (inside(x, y) && part[y * W + x] == P_BODY) idx[y * W + x] = SHIRT_LIGHT
    plot(px + 1, py - 1, PEN_ORANGE); plot(px + 1, py - 2, PEN_ORANGE)
    plot(px + 2, py - 1, PEN_BLUE); plot(px + 2, py - 2, PEN_BLUE); plot(px + 2, py - 3, PEN_BLUE)
    plot(px + 3, py - 1, WHITE); plot(px + 3, py - 2, WHITE)
  }

  private fun drawNeck(cx: Float, top: Float, bottom: Float) {
    for (y in top.toInt()..bottom.toInt()) for (x in (cx - 2.5f).toInt()..(cx + 2.5f).toInt()) {
      if (inside(x, y) && part[y * W + x] == 0.toByte()) shade(x, y, (x + 0.5f - cx) / 3f, 0.5f, SKIN, 4, P_SKIN)
    }
  }

  private fun drawEars(cx: Float, cy: Float, a: Float) {
    for (side in intArrayOf(-1, 1)) {
      val ex = cx + side * (a - 0.5f)
      val ey = cy + 2f
      for (y in (ey - 3).toInt()..(ey + 3).toInt()) for (x in (ex - 2).toInt()..(ex + 2).toInt()) {
        val u = (x + 0.5f - ex) / 2.1f
        val v = (y + 0.5f - ey) / 3f
        if (u * u + v * v <= 1f && inside(x, y)) shade(x, y, u, v, SKIN, 4, P_SKIN)
      }
    }
  }

  private fun drawHead(cx: Float, cy: Float, a: Float, b: Float) {
    for (y in (cy - b).toInt()..(cy + b).toInt()) for (x in (cx - a).toInt()..(cx + a).toInt()) {
      if (!inside(x, y)) continue
      val v = (y + 0.5f - cy) / b
      // A narrower, rounder chin.
      val u = (x + 0.5f - cx) / (a * (1f - max(0f, v) * 0.2f))
      if (abs(u).pow(2.3f) + abs(v).pow(2.2f) > 1f) continue
      shade(x, y, u, v, SKIN, 4, P_SKIN)
    }
  }

  /** The messy brown mop: volume over the crown, a jagged side-swept fringe, tufts that sway. */
  private fun drawHair(p: Pose, cx: Float, cy: Float, a: Float, b: Float) {
    val hcy = cy - 2.2f
    val ha = a + 1.6f
    val hb = b + 0.6f
    for (y in (hcy - hb - 1).toInt()..(cy + 3).toInt()) for (x in (cx - ha - 1).toInt()..(cx + ha + 1).toInt()) {
      if (!inside(x, y)) continue
      val u = (x + 0.5f - cx) / ha
      val v = (y + 0.5f - hcy) / hb
      if (abs(u).pow(2.4f) + abs(v).pow(2.2f) > 1f) continue
      // Fringe: jagged, swept to his left, lower at the sides (over the temples).
      val jag = (hash(x, 3) and 3) * 0.55f
      val fringe = cy - 5.8f + jag + (x - cx) * 0.18f + abs(u) * 2.5f
      val sides = abs(u) > 0.84f && y < cy + 1.5f
      if (y > fringe && !sides) continue
      shade(x, y, u * 0.9f, v, HAIR, 3, P_HAIR)
    }
    // Tufts sticking up from the crown.
    val sway = sin(p.t * 1.7f) * (if (p.mode == Alfred.Mode.SPEAKING) 1.2f else 0.6f)
    for ((k, dx) in intArrayOf(-6, -2, 2, 6).withIndex()) {
      val height = 2 + (k % 2)
      for (h in 1..height) {
        val x = (cx + dx + sway * h / height).toInt()
        val y = (hcy - hb + 1 - h).toInt()
        if (inside(x, y)) {
          idx[y * W + x] = if (h == height) HAIR_LIGHT else HAIR_MID
          part[y * W + x] = P_HAIR
        }
      }
    }
  }

  private fun drawArms(p: Pose, cx: Float, shoulderY: Float, happy: Float, back: Boolean) {
    for (side in intArrayOf(-1, 1)) {
      var ang = 0.18f + sin(p.t * 1.6f + side) * 0.07f
      var front = false
      when (p.mode) {
        Alfred.Mode.LISTENING -> { ang = 2.45f; front = true } // hands cupped by his ears
        Alfred.Mode.THINKING -> if (side == 1) { ang = 2.75f; front = true } // hand to chin
        Alfred.Mode.SPEAKING -> ang = 0.4f + abs(sin(p.t * 4f + side)) * (0.4f + p.level * 0.8f)
        Alfred.Mode.ERROR -> ang = 0.12f
        Alfred.Mode.OFF -> if (side == 1) ang = 2.5f + sin(p.t * 9f) * 0.35f // waving goodbye
        Alfred.Mode.BOOT -> ang = 0.5f
        else -> Unit
      }
      if (happy > 0f) {
        ang = ang * (1f - happy) + (2.8f + sin(p.t * 14f + side) * 0.25f) * happy
        front = false
      }
      if (front == back) continue
      val sx = cx + side * 11.2f
      val sy = shoulderY + 2f
      val len = 11f
      val dirX = side * sin(ang)
      val dirY = cos(ang)
      val mx = sx + dirX * len * 0.5f
      val my = sy + dirY * len * 0.5f
      for (y in (my - len).toInt()..(my + len).toInt()) for (x in (mx - len).toInt()..(mx + len).toInt()) {
        if (!inside(x, y)) continue
        val rx = x + 0.5f - mx
        val ry = y + 0.5f - my
        val along = rx * dirX + ry * dirY
        val across = -rx * dirY + ry * dirX
        if (sq(along / (len * 0.55f)) + sq(across / 2.6f) > 1f) continue
        shade(x, y, across / 2.6f * -side, along / (len * 0.55f), SUIT, 4, P_ARM)
      }
      // Hand.
      val hx = sx + dirX * (len + 0.5f)
      val hy = sy + dirY * (len + 0.5f)
      for (y in (hy - 2).toInt()..(hy + 2).toInt()) for (x in (hx - 2).toInt()..(hx + 2).toInt()) {
        val u = (x + 0.5f - hx) / 2f
        val v = (y + 0.5f - hy) / 2f
        if (u * u + v * v <= 1f && inside(x, y)) shade(x, y, u, v, SKIN, 4, P_ARM)
      }
    }
  }

  // --- face ---------------------------------------------------------------------------

  private fun drawEyes(p: Pose, cx: Float, ey: Float, happy: Float) {
    val gx = gazeX.toInt()
    val gy = gazeY.toInt()
    for (side in intArrayOf(-1, 1)) {
      val ex = (cx + side * 5.2f).toInt()
      val bmp = when {
        p.mode == Alfred.Mode.ERROR -> EYE_X
        happy > 0.3f -> EYE_HAPPY
        blinking(p) -> EYE_CLOSED
        p.mode == Alfred.Mode.LISTENING -> EYE_WIDE
        p.mode == Alfred.Mode.THINKING -> EYE_UP
        else -> EYE_OPEN
      }
      val dx = if (bmp === EYE_OPEN || bmp === EYE_WIDE || bmp === EYE_UP) gx else 0
      stamp(bmp, ex - bmp[0].length / 2 + dx, ey.toInt() - bmp.size / 2 + (if (bmp === EYE_OPEN) gy else 0),
          mapOf('w' to WHITE, 'i' to IRIS, 'p' to PUPIL, 'k' to HAIR_DARK))
    }
  }

  /** Big round tortoiseshell frames, a bridge, temples to the ears, and a glint on each lens. */
  private fun drawGlasses(cx: Float, ey: Float) {
    for (side in intArrayOf(-1, 1)) {
      val gx = cx + side * 5.4f
      for (y in (ey - 6).toInt()..(ey + 6).toInt()) for (x in (gx - 6).toInt()..(gx + 6).toInt()) {
        val d = sqrt(sq(x + 0.5f - gx) + sq(y + 0.5f - ey))
        if (d in 3.9f..5.0f) plot(x, y, if ((hash(x, y) and 3) == 0) FRAME_LIGHT else FRAME)
      }
      plot((gx - 2f).toInt(), (ey - 2.5f).toInt(), WHITE) // lens glint
      // Temple arm back to the ear.
      val ox = (gx + side * 5f).toInt()
      for (k in 0..2) plot(ox + side * k, ey.toInt() - 1, FRAME)
    }
    plot(cx.toInt() - 1, ey.toInt() - 1, FRAME); plot(cx.toInt(), ey.toInt() - 1, FRAME)
  }

  private fun drawBrows(p: Pose, cx: Float, ey: Float, happy: Float) {
    val raise = when {
      p.mode == Alfred.Mode.LISTENING || happy > 0.3f -> 2
      p.mode == Alfred.Mode.ERROR -> 0
      else -> 1 // perpetually eager
    }
    for (side in intArrayOf(-1, 1)) {
      val bx = (cx + side * 5.4f).toInt()
      val by = (ey - 5f).toInt() - raise
      val quirk = if (p.mode == Alfred.Mode.THINKING && side == 1) -1 else 0
      plot(bx - 2, by + 1, HAIR_DARK); plot(bx - 1, by + quirk, HAIR_DARK); plot(bx, by + quirk, HAIR_DARK); plot(bx + 1, by + 1, HAIR_DARK)
    }
  }

  private fun drawNose(cx: Float, ey: Float) {
    plot(cx.toInt(), (ey + 2).toInt(), SKIN)
    plot(cx.toInt(), (ey + 3).toInt(), SKIN)
    plot(cx.toInt() + 1, (ey + 3).toInt(), SKIN)
  }

  /** The grin with braces; it opens with his voice when speaking. */
  private fun drawMouth(p: Pose, cx: Float, my: Float, happy: Float) {
    val x0 = cx.toInt()
    val y0 = my.toInt()
    val colors = mapOf('m' to MOUTH, 't' to TEETH, 'b' to BRACES, 'r' to TONGUE, 's' to SKIN)
    val open = when {
      p.mode == Alfred.Mode.SPEAKING -> (p.level * 3.2f + abs(sin(p.t * 17f)) * 0.8f).toInt().coerceIn(1, 3)
      happy > 0.3f -> 3
      else -> 0
    }
    val bmp = when {
      p.mode == Alfred.Mode.ERROR -> MOUTH_FLAT
      p.mode == Alfred.Mode.LISTENING -> MOUTH_O
      p.mode == Alfred.Mode.THINKING -> MOUTH_HMM
      open >= 3 -> GRIN_BIG
      open == 2 -> GRIN_OPEN
      else -> GRIN_TEETH // his resting face: the eager braces grin
    }
    stamp(bmp, x0 - bmp[0].length / 2, y0, colors)
  }

  private fun blush(cx: Float, y: Float) {
    for (side in intArrayOf(-1, 1)) {
      val bx = (cx + side * 8f).toInt()
      for (dx in 0..1) {
        val x = bx + dx * side
        if (inside(x, y.toInt()) && part[y.toInt() * W + x] == P_SKIN) plot(x, y.toInt(), BLUSH)
      }
    }
  }

  private fun drawExtras(p: Pose, cx: Float, headTop: Float, happy: Float) {
    val top = headTop.toInt()
    when (p.mode) {
      Alfred.Mode.THINKING -> for (k in 0 until 3) {
        if (((p.t * 2.5f).toInt() % 4) > k) stamp(DOT, (cx + 13 + k * 3).toInt(), top + 4 - k * 4, mapOf('#' to GLOW0, 'o' to GLOW2))
      }
      Alfred.Mode.ERROR -> stamp(ALERT, (cx + 15).toInt(), top + 2, mapOf('#' to ACCENT, 'o' to OUTLINE))
      else -> Unit
    }
    if (happy > 0.2f) {
      for (k in 0 until 2) {
        val rise = (p.t * 0.9f + k * 0.5f) % 1f
        stamp(HEART, (cx + (if (k == 0) -17 else 13)).toInt(), (top + 8 - rise * 14).toInt(), mapOf('#' to HEART_C, 'o' to WHITE))
      }
    }
  }

  // --- shading helpers -----------------------------------------------------------------

  /** Shades a part pixel from a fake normal (u, v in -1..1), top-left light, Bayer-dithered. */
  private fun shade(x: Int, y: Int, u: Float, v: Float, darkest: Byte, tones: Int, partId: Byte) {
    val nz = sqrt(max(0f, 1f - u * u - v * v))
    val d = (-u * LX - v * LY + nz * LZ).coerceIn(-1f, 1f)
    var t = (d * 0.5f + 0.5f) * (tones - 0.8f)
    t += ((hash(x, y) and 7) - 3.5f) * 0.03f
    val base = floor(t).toInt().coerceIn(0, tones - 1)
    val tone = if (t - base > bayer(x, y) && base < tones - 1) base + 1 else base
    idx[y * W + x] = (darkest + tone).toByte()
    part[y * W + x] = partId
  }

  /** 1 px outline around the silhouette, plus seams where parts meet. */
  private fun outline() {
    val copy = part.copyOf()
    for (y in 0 until H) for (x in 0 until W) {
      val i = y * W + x
      val me = copy[i]
      var edge = false
      for ((dx, dy) in N4) {
        val nx = x + dx
        val ny = y + dy
        val other = if (nx in 0 until W && ny in 0 until H) copy[ny * W + nx] else 0
        if (me == 0.toByte() && other != 0.toByte()) edge = true
        // Seams: hair over skin (the fringe edge), arms over the torso.
        if (me == P_SKIN && other == P_HAIR && dy == -1) edge = true
        if (me == P_ARM && other == P_BODY && bayer(x, y) < 0.5f) edge = true
      }
      if (edge) idx[i] = OUTLINE
    }
  }

  private fun rimLight(cx: Float, cy: Float) {
    for (y in 1 until H - 1) for (x in 1 until W - 1) {
      val i = y * W + x
      val pt = part[i]
      if (pt != P_HAIR && pt != P_BODY) continue
      if (idx[i - 1] == OUTLINE && x < cx - 6 && y > cy - 6) idx[i] = GLOW1
    }
  }

  private fun resolve() {
    val pal = IntArray(PALETTE_SIZE)
    for (k in 0 until PALETTE_SIZE) pal[k] = FIXED[k]
    for (i in 0 until 4) pal[GLOW0.toInt() + i] = rgb(glow[i * 3], glow[i * 3 + 1], glow[i * 3 + 2])
    pal[ACCENT.toInt()] = accent()
    pal[AURA1.toInt()] = mix(pal[GLOW3.toInt()], 0x000000, 0.55f)
    pal[AURA2.toInt()] = mix(pal[GLOW2.toInt()], 0x000000, 0.35f)
    pal[SPARKLE.toInt()] = mix(pal[GLOW0.toInt()], 0xffffff, 0.5f)
    for (i in pixels.indices) {
      val k = idx[i].toInt()
      pixels[i] = if (k == 0 && transparentBackground) 0 else 0xff000000.toInt() or pal[k]
    }
  }

  // --- tiny utils ------------------------------------------------------------------------

  private fun inside(x: Int, y: Int) = x in 0 until W && y in 0 until H

  private fun plot(x: Int, y: Int, c: Byte) {
    if (inside(x, y)) idx[y * W + x] = c
  }

  private fun stamp(rows: Array<String>, x0: Int, y0: Int, colors: Map<Char, Byte>) {
    for ((r, row) in rows.withIndex()) for ((c, ch) in row.withIndex()) colors[ch]?.let { plot(x0 + c, y0 + r, it) }
  }

  private fun bayer(x: Int, y: Int) = BAYER[(y and 3) * 4 + (x and 3)]

  private fun hash(x: Int, y: Int): Int {
    var h = x * 374761393 + y * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    return h xor (h ushr 16)
  }

  private fun sq(f: Float) = f * f

  private fun easeOutBack(k: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    return 1f + c3 * (k - 1f).pow(3) + c1 * (k - 1f).pow(2)
  }

  companion object {
    const val W = 64
    const val H = 64
    private const val SPARKLES = 7
    private val LX = -0.5f / 0.94f
    private val LY = -0.6f / 0.94f
    private val LZ = 0.62f / 0.94f
    private val N4 = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5).map { (it + 0.5f) / 16f }.toFloatArray()

    // Palette roles (consecutive tone ramps start at their darkest entry).
    private const val BG: Byte = 0
    private const val OUTLINE: Byte = 1
    private const val SKIN: Byte = 2 // 2..5
    private const val HAIR: Byte = 6 // 6..8
    private const val HAIR_DARK: Byte = 6
    private const val HAIR_MID: Byte = 7
    private const val HAIR_LIGHT: Byte = 8
    private const val SUIT: Byte = 9 // 9..12
    private const val SHIRT_SHADE: Byte = 13
    private const val SHIRT_LIGHT: Byte = 14
    private const val TIE_DARK: Byte = 15
    private const val TIE: Byte = 16
    private const val TIE_DOT: Byte = 17
    private const val FRAME: Byte = 18
    private const val FRAME_LIGHT: Byte = 19
    private const val IRIS: Byte = 20
    private const val PUPIL: Byte = 21
    private const val MOUTH: Byte = 22
    private const val TEETH: Byte = 23
    private const val BRACES: Byte = 24
    private const val TONGUE: Byte = 25
    private const val BLUSH: Byte = 26
    private const val PEN_ORANGE: Byte = 27
    private const val PEN_BLUE: Byte = 28
    private const val SHOE: Byte = 29 // 29..30
    private const val GLOW0: Byte = 31 // 31..34: per-mode glow ramp, light→deep
    private const val GLOW1: Byte = 32
    private const val GLOW2: Byte = 33
    private const val GLOW3: Byte = 34
    private const val AURA1: Byte = 35
    private const val AURA2: Byte = 36
    private const val SPARKLE: Byte = 37
    private const val ACCENT: Byte = 38
    private const val SHADOW: Byte = 39
    private const val HEART_C: Byte = 40
    private const val WHITE: Byte = 41
    private const val PALETTE_SIZE = 42

    private const val P_SKIN: Byte = 1
    private const val P_HAIR: Byte = 2
    private const val P_BODY: Byte = 3
    private const val P_ARM: Byte = 4
    private const val P_LEGS: Byte = 5

    private val FIXED = IntArray(PALETTE_SIZE).also {
      it[OUTLINE.toInt()] = 0x1a1210
      // Skin: shadow → highlight.
      it[2] = 0xb5715a; it[3] = 0xd9937a; it[4] = 0xf0b597; it[5] = 0xffd2b8
      // Hair: a warm mid brown.
      it[6] = 0x3a2416; it[7] = 0x5c3a22; it[8] = 0x80562f
      // Grey suit.
      it[9] = 0x3c3d44; it[10] = 0x55565f; it[11] = 0x6f707a; it[12] = 0x8e8f99
      it[SHIRT_SHADE.toInt()] = 0xc9cad8
      it[SHIRT_LIGHT.toInt()] = 0xf0f0f7
      it[TIE_DARK.toInt()] = 0x4a2a1a
      it[TIE.toInt()] = 0x6e4127
      it[TIE_DOT.toInt()] = 0xc79a6a
      // Tortoiseshell frames.
      it[FRAME.toInt()] = 0x2b1a12
      it[FRAME_LIGHT.toInt()] = 0x7a4b25
      it[IRIS.toInt()] = 0x5f8fa8
      it[PUPIL.toInt()] = 0x101820
      it[MOUTH.toInt()] = 0x5a1e22
      it[TEETH.toInt()] = 0xf7f3ea
      it[BRACES.toInt()] = 0x9aa3b5
      it[TONGUE.toInt()] = 0xe0707a
      it[BLUSH.toInt()] = 0xff8f8f
      it[PEN_ORANGE.toInt()] = 0xf07a2a
      it[PEN_BLUE.toInt()] = 0x3a6fe0
      it[29] = 0x2a1a12; it[30] = 0x4a3020
      it[SHADOW.toInt()] = 0x0d0e1a
      it[HEART_C.toInt()] = 0xff4f7b
      it[WHITE.toInt()] = 0xffffff
    }

    /** Per-mode glow ramp (4) and accent: the Muse gadgets' published schemes. */
    private val SCHEMES = mapOf(
        Alfred.Mode.BOOT to intArrayOf(0xffffff, 0xcfe0ff, 0x8fa8ff, 0x5a5fe0, 0xa9c0ff),
        Alfred.Mode.IDLE to intArrayOf(0xf4e8ff, 0xc7a4ff, 0x9a6bff, 0x5b3fd9, 0xa77dff),
        Alfred.Mode.LISTENING to intArrayOf(0xe8faff, 0x8fdcff, 0x3fa2ff, 0x2a5bd7, 0x5cb8ff),
        Alfred.Mode.THINKING to intArrayOf(0xffe6ff, 0xff9cf0, 0xd35bff, 0x7a2bd9, 0xe07bff),
        Alfred.Mode.SPEAKING to intArrayOf(0xeafff4, 0x9ff5cf, 0x3fd9a0, 0x1f9a7a, 0x6ff0bf),
        Alfred.Mode.ERROR to intArrayOf(0xffd6d6, 0xff6b6b, 0xc7304a, 0x6b1a3a, 0xff5c5c),
        Alfred.Mode.OFF to intArrayOf(0xd8d4ff, 0x8f86d9, 0x5a4fb0, 0x2e2870, 0x7c72d0),
    )

    fun schemeAccent(mode: Alfred.Mode): Int = SCHEMES[mode]!![4]

    // Stamped bitmaps.
    private val EYE_OPEN = arrayOf(".ww.", "wipw", ".ww.")
    private val EYE_WIDE = arrayOf(".ww.", "wipw", "wiiw", ".ww.")
    private val EYE_UP = arrayOf("wipw", ".ww.")
    private val EYE_CLOSED = arrayOf("kkkk")
    private val EYE_HAPPY = arrayOf(".kk.", "k..k")
    private val EYE_X = arrayOf("k.k", ".k.", "k.k")
    private val SMILE = arrayOf("m.....m", ".mmmmm.")
    private val GRIN_TEETH = arrayOf("mmmmmmm", "mtbtbtm", ".mmmmm.")
    private val GRIN_OPEN = arrayOf("mmmmmmm", "mtbtbtm", "mmmmmmm", ".mrrrm.")
    private val GRIN_BIG = arrayOf("mmmmmmmmm", "mtbtbtbtm", "mmmmmmmmm", ".mmrrrmm.", "..mmmmm..")
    private val MOUTH_O = arrayOf(".mm.", "m..m", ".mm.")
    private val MOUTH_HMM = arrayOf("...mmm", ".mm...")
    private val MOUTH_FLAT = arrayOf("mmmmm", ".m.m.")
    private val DOT = arrayOf("o#", "#o")
    private val ALERT = arrayOf("o#o", "o#o", "o#o", "...", "o#o")
    private val HEART = arrayOf(".#.#.", "#####", ".###.", "..#..")

    private fun channel(rgb: Int, c: Int) = ((rgb shr (16 - 8 * c)) and 0xff).toFloat()

    private fun rgb(r: Float, g: Float, b: Float) =
        (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)

    private fun mix(a: Int, b: Int, k: Float) =
        rgb(channel(a, 0) * (1 - k) + channel(b, 0) * k, channel(a, 1) * (1 - k) + channel(b, 1) * k, channel(a, 2) * (1 - k) + channel(b, 2) * k)
  }
}
