/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Remote-control support for the launcher and its screens. The Portal TV is driven
 * by a D-pad, so every interactive element needs to be focusable and show a clear
 * highlight when selected. [tvFocusable] makes an element focusable + clickable by
 * the remote's center button, draws a bright focus ring, and gives it a subtle
 * scale so the selection is obvious from across a room. On touch models it behaves
 * like a normal clickable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tvFocusable(
    shape: Shape = RoundedCornerShape(16.dp),
    focusScale: Float = 1.06f,
    ringColor: Color = Color.White,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
  if (!enabled) return this
  val source = remember { MutableInteractionSource() }
  val focused by source.collectIsFocusedAsState()
  val scale by animateFloatAsState(if (focused) focusScale else 1f, label = "tvFocusScale")
  val base =
      this.scale(scale)
          .border(
              BorderStroke(if (focused) 3.dp else 0.dp, if (focused) ringColor else Color.Transparent),
              shape,
          )
  // Stable clickable when there's no long-press handler (unchanged for every other
  // caller); only the "hey" button opts into combinedClickable for its picker.
  return if (onLongClick != null) {
    base.combinedClickable(
        interactionSource = source, indication = null, onClick = onClick, onLongClick = onLongClick)
  } else {
    base.clickable(interactionSource = source, indication = null, onClick = onClick)
  }
}

/**
 * Focus treatment for full-width list rows: a filled highlight instead of a ring +
 * scale, so the row doesn't grow outside its container on the TV. Center clicks it.
 */
@Composable
fun Modifier.tvFocusableRow(
    focusFill: Color = Color(0x402E6BE6),
    onClick: () -> Unit,
): Modifier {
  val source = remember { MutableInteractionSource() }
  val focused by source.collectIsFocusedAsState()
  return this.background(if (focused) focusFill else Color.Transparent)
      .clickable(interactionSource = source, indication = null, onClick = onClick)
}

/**
 * A focus ring for an element that's already focusable+clickable on its own (e.g. a
 * Material Button). Pass the element's [interactionSource] so the ring lights up when
 * the remote selects it, without adding a second click handler.
 */
@Composable
fun Modifier.focusRing(
    interactionSource: MutableInteractionSource,
    shape: Shape = RoundedCornerShape(14.dp),
    ringColor: Color = Color.White,
): Modifier {
  val focused by interactionSource.collectIsFocusedAsState()
  val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "focusRingScale")
  return this.scale(scale)
      .border(
          BorderStroke(if (focused) 3.dp else 0.dp, if (focused) ringColor else Color.Transparent),
          shape,
      )
}

/**
 * Remembers a [FocusRequester] and requests focus once the node is attached, so a
 * screen opens with something selected for the remote. Attach the returned modifier
 * to the element that should start focused.
 */
@Composable
fun rememberInitialFocus(): Pair<FocusRequester, Modifier> {
  val requester = remember { FocusRequester() }
  val modifier = Modifier.focusRequester(requester)
  DisposableEffect(Unit) {
    runCatching { requester.requestFocus() }
    onDispose {}
  }
  return requester to modifier
}
