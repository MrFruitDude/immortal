/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val NightText = Color(0xFFB8A894) // warm grey: easy on dark-adapted eyes
private val NightFaint = Color(0xFF5E564C)

/**
 * The overnight bedside screen: a big, dim clock on black and a row of light buttons (each room,
 * plus All off) for getting up in the dark. The window brightness is already turned right down
 * by the host ([PhotoFramePreviewActivity]); the whole face drifts a few pixels each minute so
 * nothing burns in. Tapping anywhere but a light button hands the Portal back.
 */
@Composable
internal fun NightClockScreen(use24Hour: Boolean, onDismiss: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val now by rememberMinuteTicker()
  var home by remember { mutableStateOf<HomeControls.Snapshot?>(null) }
  val flipped = remember { mutableStateMapOf<String, Boolean>() }
  LaunchedEffect(Unit) {
    while (true) {
      home = withContext(Dispatchers.IO) { runCatching { HomeControls.load(context) }.getOrNull() }
      delay(5L * 60 * 1000)
    }
  }
  val noRipple = remember { MutableInteractionSource() }
  BoxWithConstraints(
      Modifier.fillMaxSize().background(Color.Black).clickable(interactionSource = noRipple, indication = null) {
        onDismiss()
      },
      contentAlignment = Alignment.Center) {
        val shift = AntiBurnIn.shift(now, with(LocalDensity.current) { 14.dp.toPx() })
        val clockSize = (minOf(maxWidth.value, maxHeight.value) * 0.3f).sp
        Column(
            Modifier.offset(x = with(LocalDensity.current) { shift.x.toDp() }, y = with(LocalDensity.current) { shift.y.toDp() }),
            horizontalAlignment = Alignment.CenterHorizontally) {
              MinuteClockText(use24Hour, fontSize = clockSize, color = NightText, fontWeight = FontWeight.ExtraLight)
              MinuteDateText(fontSize = 22.sp, color = NightFaint, modifier = Modifier.padding(top = 4.dp))
              val snap = home
              if (snap != null && snap.lights.isNotEmpty()) {
                Spacer(Modifier.height(48.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                  snap.lights.take(4).forEach { light ->
                    val on = flipped[light.id] ?: light.on
                    NightButton(light.name, lit = on) {
                      flipped[light.id] = !on
                      scope.launch {
                        withContext(Dispatchers.IO) { runCatching { HomeControls.toggle(context, light.copy(on = on)) } }
                      }
                    }
                  }
                  NightButton("All off", lit = false) {
                    snap.lights.forEach { flipped[it.id] = false }
                    scope.launch { withContext(Dispatchers.IO) { runCatching { HomeControls.allOff(context) } } }
                  }
                }
              }
            }
      }
}

@Composable
private fun NightButton(label: String, lit: Boolean, onClick: () -> Unit) {
  Box(
      Modifier.border(1.dp, if (lit) NightText else NightFaint, RoundedCornerShape(50))
          .background(if (lit) Color(0x332B241C) else Color.Transparent, RoundedCornerShape(50))
          .tvFocusable(RoundedCornerShape(50)) { onClick() }
          .padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text(label, color = if (lit) NightText else NightFaint, fontSize = 16.sp, maxLines = 1)
      }
}
