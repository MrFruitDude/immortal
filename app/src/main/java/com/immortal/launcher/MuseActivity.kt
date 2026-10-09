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
 * Alfred's full screen: the character, the conversation so far, and Muse's connection and pairing
 * status. The talk button starts a hands-free conversation ([AlfredSession]); holding Alfred is
 * still push-to-talk; tap him to pet him. Opened from Tools, Settings, a long-press on the home
 * Alfred button, or when a conversation can't start from the popover.
 */
class MuseActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Arriving here is a deliberate exit from the screensaver, not a force-wake.
    DreamPolicy.userExitAt = System.currentTimeMillis()
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    if (android.os.Build.VERSION.SDK_INT >= 27) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    }
    val talk = intent.getBooleanExtra(EXTRA_TALK, false)
    setContent {
      SampleAppTheme(darkTheme = true) {
        MuseScreen(startTalking = talk, onClose = { finish() })
      }
    }
  }

  // This screen has its own Alfred: the conversation popover keeps only its edge glow here.
  override fun onResume() {
    super.onResume()
    AlfredOverlay.setCardSuppressed(true)
  }

  override fun onPause() {
    AlfredOverlay.setCardSuppressed(false)
    super.onPause()
  }

  companion object {
    const val EXTRA_TALK = "talk"
  }
}

private val Ink = Color(0xFF0A0B14)
private val Panel = Color(0xFF151726)
private val Muted = Color(0xFF9CA0B8)

@Composable
private fun MuseScreen(startTalking: Boolean, onClose: () -> Unit) {
  val context = LocalContext.current
  val main = remember { Handler(Looper.getMainLooper()) }
  var status by remember { mutableStateOf(MuseRuntime.status) }
  var alfred by remember { mutableStateOf(Alfred.state) }
  var wakeStatus by remember { mutableStateOf(AlfredWake.status) }
  var session by remember { mutableStateOf(AlfredSession.phase) }

  DisposableEffect(Unit) {
    val ls: (MuseStatus) -> Unit = { s -> main.post { status = s } }
    val la: (Alfred.State) -> Unit = { s -> main.post { alfred = s } }
    val lp: (AlfredSessionMachine.Phase) -> Unit = { p -> main.post { session = p } }
    MuseRuntime.addListener(ls)
    Alfred.addListener(la)
    AlfredSession.addListener(lp)
    if (MuseConfig.isEnabled(context)) MuseService.sync(context)
    onDispose {
      MuseRuntime.removeListener(ls)
      Alfred.removeListener(la)
      AlfredSession.removeListener(lp)
    }
  }
  LaunchedEffect(Unit) {
    while (true) {
      wakeStatus = AlfredWake.status
      delay(1000)
    }
  }
  // Talk = a hands-free conversation (turn detection, follow-ups); holding Alfred = push-to-talk.
  var pendingPtt by remember { mutableStateOf(false) }
  val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (!granted) Alfred.announce("I need the microphone to hear you.")
    else if (pendingPtt) Thread({ Alfred.startPushToTalk(context) }, "alfred-ptt").start()
    else AlfredSession.start(context)
  }
  fun hasMic() = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
  fun talk() {
    pendingPtt = false
    if (hasMic()) AlfredSession.start(context) else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
  }
  fun pushToTalk() {
    pendingPtt = true
    if (hasMic()) Thread({ Alfred.startPushToTalk(context) }, "alfred-ptt").start()
    else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
  }
  LaunchedEffect(startTalking) { if (startTalking && MuseRuntime.currentLink() != null) talk() }

  val connected = status.state == MuseStatus.State.CONNECTED
  val inSession = session != AlfredSessionMachine.Phase.IDLE
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
            Stage(mode, connected && !inSession, ::pushToTalk)
          }
          Column(Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp), verticalArrangement = Arrangement.Center) {
            Conversation(alfred, status, connected, inSession, ::talk, modifier = Modifier.weight(1f, fill = false))
          }
        }
      } else {
        Box(Modifier.fillMaxWidth().weight(1.15f), contentAlignment = Alignment.Center) { Stage(mode, connected && !inSession, ::pushToTalk) }
        Column(Modifier.fillMaxWidth().weight(1f)) { Conversation(alfred, status, connected, inSession, ::talk, modifier = Modifier.weight(1f)) }
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
private fun Conversation(
    alfred: Alfred.State,
    status: MuseStatus,
    connected: Boolean,
    inSession: Boolean,
    talk: () -> Unit,
    modifier: Modifier,
) {
  val context = LocalContext.current
  Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    // The live caption: what Alfred is doing right now.
    val caption = when {
      status.state == MuseStatus.State.PAIRING -> "Pair me in the Muse app: Settings › Devices › Add Device"
      status.state == MuseStatus.State.UNPAIRED || status.state == MuseStatus.State.DISABLED -> "I'm not connected to your Muse yet."
      status.state == MuseStatus.State.OFFLINE && !alfred.turnActive -> status.detail.ifEmpty { "I can't reach Muse right now." }
      alfred.caption.isNotEmpty() -> alfred.caption
      alfred.mode == Alfred.Mode.SPEAKING -> ""
      inSession -> "Just talk. I'll answer when you stop."
      else -> "Tap Talk, or say “Hey ${Alfred.NAME}”. You can also hold me to talk."
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
      connected -> TalkButton(alfred.mode == Alfred.Mode.LISTENING && !inSession, alfred.turnActive, inSession, talk)
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
private fun TalkButton(listening: Boolean, busy: Boolean, inSession: Boolean, talk: () -> Unit) {
  val color = if (listening || inSession) Color(0xFFE0565B) else Color(0xFF6C5CE7)
  Row(
      Modifier.clip(RoundedCornerShape(50)).background(color).clickable {
        when {
          inSession -> AlfredSession.close()
          listening -> Alfred.endPushToTalk()
          busy -> Alfred.cancel()
          else -> talk()
        }
      }.padding(horizontal = 30.dp, vertical = 16.dp),
      verticalAlignment = Alignment.CenterVertically) {
    Text(
        when {
          inSession -> "✕  End conversation"
          listening -> "■  Send"
          busy -> "✕  Stop"
          else -> "🎙  Talk to ${Alfred.NAME}"
        },
        color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
  }
  AnimatedVisibility(listening) {
    Text("Release to send", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
  }
}

@Composable
private fun Pill(label: String, color: Color, onClick: () -> Unit) {
  Text(label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
      modifier = Modifier.clip(RoundedCornerShape(50)).background(color).clickable { onClick() }
          .padding(horizontal = 30.dp, vertical = 15.dp))
}
