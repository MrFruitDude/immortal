/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.isActive

/**
 * Alfred on screen: renders [AlfredAvatar] every frame (~30 fps) and scales the 64x64 grid up
 * with crisp nearest-neighbour pixels over a soft glow in the current mode's accent colour.
 *
 * Tap him to pet him. [onHoldStart]/[onHoldEnd] make him the push-to-talk button (null = off).
 */
@Composable
fun AlfredStage(
    mode: Alfred.Mode,
    modifier: Modifier = Modifier,
    onHoldStart: (() -> Unit)? = null,
    onHoldEnd: (() -> Unit)? = null,
) {
  val avatar = remember { AlfredAvatar(transparentBackground = true) }
  val bitmap = remember { Bitmap.createBitmap(AlfredAvatar.W, AlfredAvatar.H, Bitmap.Config.ARGB_8888) }
  val image = remember(bitmap) { bitmap.asImageBitmap() }
  val start = remember { System.nanoTime() }
  var modeSince by remember { mutableLongStateOf(System.nanoTime()) }
  var lastMode by remember { mutableLongStateOf(-1L) }
  var frame by remember { mutableLongStateOf(0L) }
  if (lastMode != mode.ordinal.toLong()) {
    lastMode = mode.ordinal.toLong()
    modeSince = System.nanoTime()
  }
  LaunchedEffect(Unit) {
    var last = 0L
    while (isActive) {
      androidx.compose.runtime.withFrameNanos { now ->
        if (now - last >= 33_000_000L) {
          last = now
          frame = now
        }
      }
    }
  }
  Canvas(
      modifier.pointerInput(onHoldStart) {
        detectTapGestures(
            onTap = { Alfred.pet() },
            // With push-to-talk on, a long press is talking, never also a pet.
            onLongPress = if (onHoldStart != null) { _ -> } else null,
            onPress = {
              var holding = false
              if (onHoldStart != null) {
                // A press that lasts becomes push-to-talk; a quick one is a pet (onTap).
                val released = withTimeoutOrNullCompat(350) { tryAwaitRelease() }
                if (released == null) {
                  holding = true
                  onHoldStart()
                  tryAwaitRelease()
                }
              }
              if (holding) onHoldEnd?.invoke()
            })
      }) {
    @Suppress("UNUSED_VARIABLE") val tick = frame // redraw on each frame tick
    val now = System.nanoTime()
    val t = (now - start) / 1e9f
    val modeT = (now - modeSince) / 1e9f
    val pose = AlfredAvatar.Pose(mode, t, modeT, liveLevel(mode, t), happiness())
    avatar.render(pose)
    bitmap.setPixels(avatar.pixels, 0, AlfredAvatar.W, 0, 0, AlfredAvatar.W, AlfredAvatar.H)

    val side = min(size.width, size.height)
    val accent = Color(0xff000000.toInt() or avatar.accent())
    val centre = Offset(size.width / 2f, size.height / 2f)
    val pulse = 0.85f + 0.15f * sin(t * 2f) + pose.level * 0.3f
    drawCircle(
        brush = Brush.radialGradient(
            listOf(accent.copy(alpha = 0.34f * pulse), accent.copy(alpha = 0.10f), Color.Transparent),
            center = centre, radius = side * 0.62f),
        radius = side * 0.62f, center = centre)
    // Integer scale keeps every art pixel the same size.
    val scale = (side / AlfredAvatar.W).toInt().coerceAtLeast(1)
    val px = AlfredAvatar.W * scale
    drawImage(
        image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(AlfredAvatar.W, AlfredAvatar.H),
        dstOffset = IntOffset(((size.width - px) / 2f).toInt(), ((size.height - px) / 2f).toInt()),
        dstSize = IntSize(px, px),
        filterQuality = FilterQuality.None)
  }
}

/** Mic level while listening; a word-driven envelope while speaking (TTS gives word starts only). */
private fun liveLevel(mode: Alfred.Mode, t: Float): Float =
    when (mode) {
      Alfred.Mode.LISTENING -> Alfred.level
      Alfred.Mode.SPEAKING -> {
        val sinceWord = (System.currentTimeMillis() - MuseSpeech.lastWordAt) / 1000f
        (exp(-sinceWord / 0.22f) * 0.85f + 0.12f * (0.5f + 0.5f * sin(t * 23f))).coerceIn(0f, 1f)
      }
      else -> 0f
    }

/** Rises fast after a pet, then eases out over ~1.6 s. */
private fun happiness(): Float {
  val since = (System.currentTimeMillis() - Alfred.pettedAt) / 1000f
  return when {
    since < 0.15f -> since / 0.15f
    since < 1.75f -> 1f - (since - 0.15f) / 1.6f
    else -> 0f
  }
}

/** A tiny withTimeoutOrNull for the gesture scope (its own suspend context). */
private suspend fun <T> androidx.compose.foundation.gestures.PressGestureScope.withTimeoutOrNullCompat(
    ms: Long,
    block: suspend () -> T,
): T? = kotlinx.coroutines.withTimeoutOrNull(ms) { block() }
