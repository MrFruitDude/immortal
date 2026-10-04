/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.SampleAppTheme
import com.immortal.launcher.settings.SettingsDomains
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Muse on the Portal: connection status, the pairing window, and push-to-talk. Hold the button
 * and speak (or tap once to start and again to send); Muse's reply is shown and spoken.
 * Opened from the home "hey" button (when Muse is set up), Tools, and Settings › Muse.
 */
class MuseActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Arriving here is a deliberate exit from the screensaver, not a force-wake.
    DreamPolicy.userExitAt = System.currentTimeMillis()
    setContent { SampleAppTheme(darkTheme = true) { MuseScreen(startTalking = intent.getBooleanExtra(EXTRA_TALK, false)) } }
  }

  companion object {
    const val EXTRA_TALK = "talk"
  }
}

@Composable
private fun MuseScreen(startTalking: Boolean) {
  val context = LocalContext.current
  val activity = context as? Activity
  val main = remember { Handler(Looper.getMainLooper()) }
  var status by remember { mutableStateOf(MuseRuntime.status) }
  var turnState by remember { mutableStateOf<MuseVoiceTurn.State?>(null) }
  var detail by remember { mutableStateOf("") }
  var reply by remember { mutableStateOf("") }
  var turn by remember { mutableStateOf<MuseVoiceTurn?>(null) }
  var now by remember { mutableStateOf(System.currentTimeMillis()) }
  var settings by remember { mutableStateOf(MuseConfig.load(context)) }

  DisposableEffect(Unit) {
    val l: (MuseStatus) -> Unit = { s -> main.post { status = s } }
    MuseRuntime.addListener(l)
    if (MuseConfig.isEnabled(context)) MuseService.sync(context)
    onDispose {
      MuseRuntime.removeListener(l)
      turn?.cancel()
    }
  }
  LaunchedEffect(Unit) {
    while (true) {
      now = System.currentTimeMillis()
      delay(1000)
    }
  }

  fun startTurn() {
    val link = MuseRuntime.currentLink()
    if (link == null) {
      turnState = MuseVoiceTurn.State.FAILED
      detail = "Not connected to Muse yet"
      return
    }
    reply = ""
    detail = ""
    val t = MuseVoiceTurn(context, link, MuseConfig.identity(context).nodeId, object : MuseVoiceTurn.Listener {
      override fun onState(state: MuseVoiceTurn.State, message: String) {
        main.post {
          turnState = state
          if (message.isNotEmpty()) detail = message
        }
      }

      override fun onReply(text: String) {
        main.post { reply = if (reply.isEmpty()) text else "$reply\n\n$text" }
      }
    })
    turn = t
    // begin() opens the mic and a stream on the Muse socket: never on the main thread.
    Thread({ if (!t.begin()) main.post { if (turn === t) turn = null } }, "muse-voice-begin").start()
  }

  // Provisioned Portals have the mic pre-granted; a fresh install asks once, like Intercom.
  val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
      androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) startTurn()
    else {
      turnState = MuseVoiceTurn.State.FAILED
      detail = "Muse needs the microphone to hear you"
    }
  }

  fun beginTurn() {
    val granted = android.content.pm.PackageManager.PERMISSION_GRANTED ==
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
    if (granted) startTurn() else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
  }

  LaunchedEffect(startTalking) { if (startTalking && MuseRuntime.currentLink() != null) beginTurn() }

  Box(Modifier.fillMaxSize().background(Color(0xFF0E0E12))) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      Text("Muse", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
      StatusCard(status, now)

      when (status.state) {
        MuseStatus.State.DISABLED, MuseStatus.State.UNPAIRED -> {
          Text("Pair this Portal as a Muse gadget. In the Muse app, turn on Settings › Devices › Developer mode, " +
              "then Add Device and pick ${MuseConfig.identity(context).bleName}. No Wi-Fi password is needed.",
              color = Color(0xFFB0B0B8), fontSize = 16.sp, textAlign = TextAlign.Center)
          if (MuseConfig.sdkToken(context) == null)
            Text("Tip: add your SDK token from gadgets.muse.ai in Settings › Muse first — Muse will soon require one.",
                color = Color(0xFFE0B060), fontSize = 14.sp, textAlign = TextAlign.Center)
          Pill("Start pairing", Color(0xFF6C5CE7)) { MuseService.pair(context) }
        }
        MuseStatus.State.PAIRING -> Pill("Stop pairing", Color(0xFF555560)) { MuseService.stopPairing(context) }
        else -> Unit
      }

      if (status.state == MuseStatus.State.CONNECTED) {
        TalkButton(
            state = turnState,
            onPress = { if (turn == null || turnState in setOf(MuseVoiceTurn.State.DONE, MuseVoiceTurn.State.FAILED)) beginTurn() },
            onRelease = { held -> if (held) turn?.end() },
            onTapWhileListening = { turn?.end() },
        )
        val caption = when (turnState) {
          MuseVoiceTurn.State.LISTENING -> "Listening… release to send"
          MuseVoiceTurn.State.SENDING -> "Sending…"
          MuseVoiceTurn.State.THINKING -> "Muse is thinking…"
          MuseVoiceTurn.State.SPEAKING -> ""
          MuseVoiceTurn.State.FAILED -> detail.ifEmpty { "Something went wrong" }
          else -> "Hold to talk to Muse"
        }
        if (caption.isNotEmpty()) Text(caption, color = Color(0xFFB0B0B8), fontSize = 18.sp, textAlign = TextAlign.Center)
      }

      if (reply.isNotEmpty()) {
        Surface(color = Color(0xFF1C1C24), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
          Text(reply, color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(22.dp))
        }
      }

      // What Muse may do here: the `muse` registry domain (same specs as the phone remote).
      Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        SettingsList(SettingsDomains.muse, settings) { k, v ->
          SettingsDomains.muse.apply(context, JSONObject().put(k, v))
          settings = MuseConfig.load(context)
        }
      }
    }
  }
  FolderBackButton(onClick = {
    turn?.cancel()
    activity?.finish()
  })
}

@Composable
private fun StatusCard(s: MuseStatus, now: Long) {
  val (dot, label) = when (s.state) {
    MuseStatus.State.CONNECTED -> Color(0xFF3DDC84) to "Connected${if (s.vmName.isNotEmpty()) " to ${s.vmName}" else ""}"
    MuseStatus.State.CONNECTING -> Color(0xFFE0B060) to s.detail.ifEmpty { "Connecting" }
    MuseStatus.State.PAIRING -> Color(0xFF6C9CFF) to "Pairing open (${((s.pairingUntil - now) / 1000).coerceAtLeast(0) / 60}:${"%02d".format(((s.pairingUntil - now) / 1000).coerceAtLeast(0) % 60)})"
    MuseStatus.State.OFFLINE -> Color(0xFFE06060) to s.detail.ifEmpty { "Offline" }
    MuseStatus.State.UNPAIRED -> Color(0xFF888890) to "Not paired"
    MuseStatus.State.DISABLED -> Color(0xFF888890) to "Off"
  }
  Surface(color = Color(0xFF1C1C24), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(12.dp).background(dot, CircleShape))
        Text(label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
      }
      if (s.state == MuseStatus.State.PAIRING || (s.detail.isNotEmpty() && s.state != MuseStatus.State.CONNECTED && s.detail != label))
        Text(s.detail, color = Color(0xFFB0B0B8), fontSize = 15.sp)
    }
  }
}

@Composable
private fun TalkButton(
    state: MuseVoiceTurn.State?,
    onPress: () -> Unit,
    onRelease: (held: Boolean) -> Unit,
    onTapWhileListening: () -> Unit,
) {
  val listening = state == MuseVoiceTurn.State.LISTENING
  val color = if (listening) Color(0xFFE0565B) else Color(0xFF6C5CE7)
  Box(
      Modifier.size(150.dp).background(color, CircleShape).pointerInput(listening) {
        detectTapGestures(onPress = {
          if (listening) {
            // Tap-to-talk mode: a second tap sends.
            onTapWhileListening()
            return@detectTapGestures
          }
          val down = System.currentTimeMillis()
          onPress()
          tryAwaitRelease()
          // A quick tap starts tap-to-talk (keep listening); a real hold sends on release.
          onRelease(System.currentTimeMillis() - down > 450)
        })
      },
      contentAlignment = Alignment.Center,
  ) {
    Text(if (listening) "●" else "🎙", fontSize = 56.sp, color = Color.White)
  }
}

@Composable
private fun Pill(label: String, color: Color, onClick: () -> Unit) {
  Surface(color = color, shape = RoundedCornerShape(14.dp),
      modifier = Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f) { onClick() }) {
    Text(label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth())
  }
}
