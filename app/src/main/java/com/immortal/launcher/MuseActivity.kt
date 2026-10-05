/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.SampleAppTheme
import kotlinx.coroutines.delay

/**
 * Alfred's stage: the character, what he's saying, and the conversation. Hold Alfred (or the
 * talk button) to speak; tap him to pet him. Opened from the home "hey" button, Tools, Settings,
 * and by "Hey Alfred" — in which case it steps aside again once the exchange is over.
 */
class MuseActivity : ComponentActivity() {
  private var fromWake by mutableStateOf(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Arriving here is a deliberate exit from the screensaver, not a force-wake.
    DreamPolicy.userExitAt = System.currentTimeMillis()
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    if (android.os.Build.VERSION.SDK_INT >= 27) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    }
    fromWake = intent.getBooleanExtra(EXTRA_FROM_WAKE, false)
    val talk = intent.getBooleanExtra(EXTRA_TALK, false)
    setContent {
      SampleAppTheme(darkTheme = true) {
        MuseScreen(fromWake = fromWake, startTalking = talk, onClose = { finish() })
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    if (intent.getBooleanExtra(EXTRA_FROM_WAKE, false)) fromWake = true
  }

  companion object {
    const val EXTRA_TALK = "talk"
    const val EXTRA_FROM_WAKE = "from_wake"
  }
}

private val Ink = Color(0xFF0A0B14)
private val Panel = Color(0xFF151726)
private val Muted = Color(0xFF9CA0B8)

@Composable
private fun MuseScreen(fromWake: Boolean, startTalking: Boolean, onClose: () -> Unit) {
  val context = LocalContext.current
  val main = remember { Handler(Looper.getMainLooper()) }
  var status by remember { mutableStateOf(MuseRuntime.status) }
  var alfred by remember { mutableStateOf(Alfred.state) }
  var wakeStatus by remember { mutableStateOf(AlfredWake.status) }

  DisposableEffect(Unit) {
    val ls: (MuseStatus) -> Unit = { s -> main.post { status = s } }
    val la: (Alfred.State) -> Unit = { s -> main.post { alfred = s } }
    MuseRuntime.addListener(ls)
    Alfred.addListener(la)
    if (MuseConfig.isEnabled(context)) MuseService.sync(context)
    onDispose {
      MuseRuntime.removeListener(ls)
      Alfred.removeListener(la)
    }
  }
  LaunchedEffect(Unit) {
    while (true) {
      wakeStatus = AlfredWake.status
      delay(1000)
    }
  }
  // After "Hey Alfred": step aside a few seconds after the exchange settles.
  LaunchedEffect(fromWake, alfred.turnActive, alfred.mode) {
    if (fromWake && !alfred.turnActive && (alfred.mode == Alfred.Mode.IDLE || alfred.mode == Alfred.Mode.ERROR)) {
      delay(if (alfred.mode == Alfred.Mode.ERROR) 4000 else 7000)
      if (!Alfred.state.turnActive) onClose()
    }
  }

  val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) Thread({ Alfred.startPushToTalk(context) }, "alfred-ptt").start()
    else Alfred.announce("I need the microphone to hear you.")
  }
  fun talk() {
    if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        Thread({ Alfred.startPushToTalk(context) }, "alfred-ptt").start()
    else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
  }
  LaunchedEffect(startTalking) { if (startTalking && MuseRuntime.currentLink() != null) talk() }

  val connected = status.state == MuseStatus.State.CONNECTED
  val mode = when {
    status.state == MuseStatus.State.PAIRING -> Alfred.Mode.BOOT
    status.state == MuseStatus.State.DISABLED || status.state == MuseStatus.State.UNPAIRED -> Alfred.Mode.OFF
    status.state == MuseStatus.State.OFFLINE && !alfred.turnActive -> Alfred.Mode.ERROR
    else -> alfred.mode
  }

  BoxWithConstraints(
      Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF12142A), Ink, Color(0xFF05060B))))) {
    val landscape = maxWidth > maxHeight
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
      TopBar(status, wakeStatus, onSettings = {
        context.startActivity(Intent(context, MuseSettingsActivity::class.java))
      }, onClose = onClose)
      if (landscape) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Stage(mode, connected, ::talk)
          }
          Column(Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp), verticalArrangement = Arrangement.Center) {
            Conversation(alfred, status, connected, ::talk, modifier = Modifier.weight(1f, fill = false))
          }
        }
      } else {
        Box(Modifier.fillMaxWidth().weight(1.15f), contentAlignment = Alignment.Center) { Stage(mode, connected, ::talk) }
        Column(Modifier.fillMaxWidth().weight(1f)) { Conversation(alfred, status, connected, ::talk, modifier = Modifier.weight(1f)) }
      }
    }
  }
}

@Composable
private fun Stage(mode: Alfred.Mode, connected: Boolean, talk: () -> Unit) {
  AlfredStage(
      mode = mode,
      modifier = Modifier.fillMaxSize().padding(8.dp),
      onHoldStart = if (connected) talk else null,
      onHoldEnd = if (connected) ({ Alfred.endPushToTalk() }) else null)
}

@Composable
private fun TopBar(status: MuseStatus, wake: AlfredWake.Status, onSettings: () -> Unit, onClose: () -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(Alfred.NAME, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.width(14.dp))
    StatusChip(status)
    if (wake == AlfredWake.Status.LISTENING) {
      Spacer(Modifier.width(8.dp))
      Chip("● “Hey ${Alfred.NAME}” · on-device", Color(0xFF5CB8FF))
    }
    Spacer(Modifier.weight(1f))
    RoundIcon("⚙", onSettings)
    Spacer(Modifier.width(10.dp))
    RoundIcon("✕", onClose)
  }
}

@Composable
private fun StatusChip(s: MuseStatus) {
  val (color, label) = when (s.state) {
    MuseStatus.State.CONNECTED -> Color(0xFF3DDC84) to "Connected"
    MuseStatus.State.CONNECTING -> Color(0xFFE0B060) to "Connecting"
    MuseStatus.State.PAIRING -> Color(0xFF6C9CFF) to "Pairing"
    MuseStatus.State.OFFLINE -> Color(0xFFE06060) to "Offline"
    MuseStatus.State.UNPAIRED -> Muted to "Not paired"
    MuseStatus.State.DISABLED -> Muted to "Off"
  }
  Chip("● $label", color)
}

@Composable
private fun Chip(text: String, color: Color) {
  Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium,
      modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
private fun RoundIcon(glyph: String, onClick: () -> Unit) {
  Box(Modifier.size(46.dp).clip(CircleShape).background(Panel).clickable { onClick() }, contentAlignment = Alignment.Center) {
    Text(glyph, color = Color.White, fontSize = 20.sp)
  }
}

@Composable
private fun Conversation(alfred: Alfred.State, status: MuseStatus, connected: Boolean, talk: () -> Unit, modifier: Modifier) {
  val context = LocalContext.current
  Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    // The live caption: what Alfred is doing right now.
    val caption = when {
      status.state == MuseStatus.State.PAIRING -> "Pair me in the Muse app: Settings › Devices › Add Device"
      status.state == MuseStatus.State.UNPAIRED || status.state == MuseStatus.State.DISABLED -> "I'm not connected to your Muse yet."
      status.state == MuseStatus.State.OFFLINE && !alfred.turnActive -> status.detail.ifEmpty { "I can't reach Muse right now." }
      alfred.caption.isNotEmpty() -> alfred.caption
      alfred.mode == Alfred.Mode.SPEAKING -> ""
      else -> "Hold me to talk, or say “Hey ${Alfred.NAME}”."
    }
    AnimatedContent(caption, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "caption") { c ->
      Text(c, color = Muted, fontSize = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    }
    val list = rememberLazyListState()
    LaunchedEffect(alfred.transcript.size) { if (alfred.transcript.isNotEmpty()) list.animateScrollToItem(alfred.transcript.size - 1) }
    LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().widthIn(max = 760.dp), state = list,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
      items(alfred.transcript) { line -> Bubble(line) }
    }
    Spacer(Modifier.height(14.dp))
    when {
      connected -> TalkButton(alfred.mode == Alfred.Mode.LISTENING, alfred.turnActive, talk)
      status.state == MuseStatus.State.UNPAIRED || status.state == MuseStatus.State.DISABLED ->
          Pill("Pair with Muse", Color(0xFF6C5CE7)) { MuseService.pair(context) }
      status.state == MuseStatus.State.PAIRING -> Pill("Stop pairing", Color(0xFF3A3D55)) { MuseService.stopPairing(context) }
    }
  }
}

@Composable
private fun Bubble(line: Alfred.Line) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromAlfred) Arrangement.Start else Arrangement.End) {
    Text(
        line.text,
        color = if (line.fromAlfred) Color.White else Color(0xFFCFD3FF),
        fontSize = if (line.fromAlfred) 21.sp else 16.sp,
        lineHeight = 29.sp,
        modifier = Modifier.widthIn(max = 640.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (line.fromAlfred) Panel else Color(0xFF262A4A))
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp))
  }
}

@Composable
private fun TalkButton(listening: Boolean, busy: Boolean, talk: () -> Unit) {
  val color = if (listening) Color(0xFFE0565B) else Color(0xFF6C5CE7)
  Row(
      Modifier.clip(RoundedCornerShape(50)).background(color).clickable {
        if (listening) Alfred.endPushToTalk() else if (busy) Alfred.cancel() else talk()
      }.padding(horizontal = 30.dp, vertical = 16.dp),
      verticalAlignment = Alignment.CenterVertically) {
    Text(if (listening) "■  Send" else if (busy) "✕  Stop" else "🎙  Talk to ${Alfred.NAME}",
        color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
  }
  AnimatedVisibility(listening) {
    Text("Tap to send, or just stop talking", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
  }
}

@Composable
private fun Pill(label: String, color: Color, onClick: () -> Unit) {
  Text(label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
      modifier = Modifier.clip(RoundedCornerShape(50)).background(color).clickable { onClick() }
          .padding(horizontal = 30.dp, vertical = 15.dp))
}
