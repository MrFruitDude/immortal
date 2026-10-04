/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.SampleAppTheme

/**
 * The full-page "Tools" screen, opened from the built-in Tools tile on the home grid. A plain
 * launcher for the in-process tool features. See docs/design/feature-integration.md (step 3).
 *
 * Tools that already have their own Activity (cameras, countdowns, lamp, bedtime, intercom) launch
 * it directly. Tools whose UI formerly lived only in ForkHome open as an in-page overlay hosted here
 * (see [HomeToolOverlays]); this change adds the sky/space pair (ISS passes, aurora outlook).
 */
class ToolsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { ToolsRoot(::finish) } }
  }
}

/** Which in-page tool overlay is showing over the list (or [ToolPage.LIST] for the list itself). */
private enum class ToolPage {
  LIST,
  ISS,
  AURORA,
  SPEEDTEST,
  TIMERS,
  NOTES,
  CONVERTER,
}

@Composable
private fun ToolsRoot(onExit: () -> Unit) {
  var page by remember { mutableStateOf(ToolPage.LIST) }
  when (page) {
    ToolPage.LIST -> ToolsScreen(onExit = onExit, onOpen = { page = it })
    ToolPage.ISS -> IssOverlay { page = ToolPage.LIST }
    ToolPage.AURORA -> AuroraOverlay { page = ToolPage.LIST }
    ToolPage.SPEEDTEST -> SpeedTestOverlay { page = ToolPage.LIST }
    ToolPage.TIMERS -> TimersOverlay { page = ToolPage.LIST }
    ToolPage.NOTES -> NotesOverlay { page = ToolPage.LIST }
    ToolPage.CONVERTER -> ConverterOverlay { page = ToolPage.LIST }
  }
}

@Composable
private fun ToolsScreen(onExit: () -> Unit, onOpen: (ToolPage) -> Unit) {
  val context = LocalContext.current
  val activity = context as? Activity
  val firstFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

  Column(
      modifier =
          Modifier.fillMaxSize()
              .onPreviewKeyEvent { e ->
                if (e.key == Key.Back) {
                  if (e.type == KeyEventType.KeyUp) onExit()
                  true
                } else false
              }
              .background(Color(0xFF101012))
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 32.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp).focusRequester(firstFocus).focusGroup()) {
      Text("Tools", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
      Text(
          "Extra utilities for your Portal.",
          color = Color(0xFF9A9A9A),
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp),
      )
      Spacer(Modifier.size(26.dp))
      Card {
        ToolRow("Cameras", "View a saved RTSP camera feed") {
          context.startActivity(Intent(context, CameraViewerActivity::class.java))
        }
        ToolRow("Countdowns", "Days until birthdays and events") {
          context.startActivity(Intent(context, CountdownSettingsActivity::class.java))
        }
        ToolRow("Lamp", "A full-screen warm-white light") {
          context.startActivity(Intent(context, LampActivity::class.java))
        }
        ToolRow("Bedtime story", "Public-domain tales read aloud") {
          context.startActivity(Intent(context, BedtimeStoryActivity::class.java))
        }
        ToolRow("Intercom", "Talk to another Portal on your Wi-Fi") {
          context.startActivity(Intent(context, IntercomActivity::class.java))
        }
        ToolRow("Muse", "Talk to Muse, or pair this Portal as a Muse gadget") {
          context.startActivity(Intent(context, MuseActivity::class.java))
        }
        ToolRow("Timers", "Kitchen timers with a live countdown") { onOpen(ToolPage.TIMERS) }
        ToolRow("Leave a note", "A sticky note or a quick voice memo") { onOpen(ToolPage.NOTES) }
        ToolRow("Converter", "Units and currency") { onOpen(ToolPage.CONVERTER) }
        ToolRow("ISS passes", "When the space station flies over") { onOpen(ToolPage.ISS) }
        ToolRow("Aurora outlook", "Northern-lights chance for your location") { onOpen(ToolPage.AURORA) }
        ToolRow("Speed test", "Check your internet speed (Cloudflare)") { onOpen(ToolPage.SPEEDTEST) }
      }
    }
  }
}

@Composable
private fun ToolRow(title: String, subtitle: String, onOpen: () -> Unit) {
  Row(
      modifier = Modifier.fillMaxWidth().tvFocusableRow { onOpen() }.padding(18.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title, color = Color.White, fontSize = 17.sp)
      Text(
          subtitle,
          color = Color(0xFF9A9A9A),
          fontSize = 13.sp,
          modifier = Modifier.padding(top = 2.dp),
      )
    }
    Text("›", color = Color(0xFF7C7C7C), fontSize = 26.sp)
  }
}
