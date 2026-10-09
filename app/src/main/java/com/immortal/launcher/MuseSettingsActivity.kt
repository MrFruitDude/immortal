/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.SampleAppTheme
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Muse settings: pairing, the wake word and its privacy story, permissions, and what Muse did. */
class MuseSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { MuseSettingsScreen(onBack = { finish() }) } }
  }
}

private val SPanel = Color(0xFF151726)
private val SMuted = Color(0xFF9CA0B8)

@Composable
private fun MuseSettingsScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val main = remember { Handler(Looper.getMainLooper()) }
  var status by remember { mutableStateOf(MuseRuntime.status) }
  var settings by remember { mutableStateOf(MuseConfig.load(context)) }
  var wake by remember { mutableStateOf(AlfredWake.status to AlfredWake.detail) }
  var log by remember { mutableStateOf(MuseActionLog.all()) }
  // Re-read on resume: a sub-screen (Connect your smart home) may have changed what the rows
  // show, and Hue pairing isn't part of the settings snapshot, so the tick forces the redraw.
  var resumeTick by remember { mutableStateOf(0) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e ->
      if (e == Lifecycle.Event.ON_RESUME) {
        settings = MuseConfig.load(context)
        resumeTick++
      }
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }
  DisposableEffect(Unit) {
    val l: (MuseStatus) -> Unit = { s -> main.post { status = s } }
    MuseRuntime.addListener(l)
    onDispose { MuseRuntime.removeListener(l) }
  }
  LaunchedEffect(Unit) {
    while (true) {
      wake = AlfredWake.status to AlfredWake.detail
      log = MuseActionLog.all()
      delay(1000)
    }
  }

  Box(Modifier.fillMaxSize().background(Color(0xFF0A0B14))) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
      Column(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.size(44.dp).clip(CircleShape).background(SPanel).clickable { onBack() }, contentAlignment = Alignment.Center) {
            Text("‹", color = Color.White, fontSize = 26.sp)
          }
          Spacer(Modifier.width(14.dp))
          Text("${Alfred.NAME} & Muse", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))

        Card("Connection") {
          val id = MuseConfig.identity(context)
          Text(when (status.state) {
            MuseStatus.State.CONNECTED -> "Connected to Muse"
            MuseStatus.State.PAIRING -> "Pairing open — in the Muse app: Settings › Devices › Add Device › ${id.bleName}"
            else -> status.detail.ifEmpty { status.state.name.lowercase().replaceFirstChar { it.uppercase() } }
          }, color = Color.White, fontSize = 17.sp)
          Text("Gadget ${id.nodeId} · Bluetooth name ${id.bleName}", color = SMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
          Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (status.state == MuseStatus.State.PAIRING) Action("Stop pairing") { MuseService.stopPairing(context) }
            else Action(if (MuseConfig.isPaired(context)) "Pair again" else "Pair with Muse") { MuseService.pair(context) }
            if (MuseConfig.isPaired(context)) Action("Forget pairing", danger = true) {
              Thread({ MuseRuntime.unpair(context) }, "muse-unpair").start()
            }
          }
        }

        Card("“Hey ${Alfred.NAME}”") {
          Text(
              "When this is on, the Portal listens for “Hey ${Alfred.NAME}” itself, on the device — nothing is " +
                  "recorded, stored or sent while it waits. Only after it hears the wake word does one short " +
                  "voice note (until you stop talking, at most 15 s) go to your Muse. It only listens while " +
                  "Muse is connected, pauses while ${Alfred.NAME} is talking, and gives the microphone to the " +
                  "intercom or camera whenever they need it. First use downloads a 41 MB speech model.",
              color = SMuted, fontSize = 14.sp, lineHeight = 20.sp)
          val (ws, wd) = wake
          if (ws != AlfredWake.Status.OFF)
              Text("● ${wd.ifEmpty { ws.name.lowercase() }}", color = if (ws == AlfredWake.Status.ERROR) Color(0xFFE06060) else Color(0xFF5CB8FF),
                  fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        }

        key(resumeTick) {
          SettingsList(SettingsDomains.muse, settings) { k, v ->
            SettingsDomains.muse.apply(context, JSONObject().put(k, v))
            settings = MuseConfig.load(context)
          }
        }

        Card("What Muse did on this Portal") {
          if (log.isEmpty()) Text("Nothing yet.", color = SMuted, fontSize = 14.sp)
          log.take(25).forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
              Text(if (e.ok) "✓" else "✕", color = if (e.ok) Color(0xFF3DDC84) else Color(0xFFE06060), fontSize = 14.sp,
                  modifier = Modifier.width(22.dp))
              Column(Modifier.weight(1f)) {
                Text(e.command, color = Color.White, fontSize = 15.sp)
                if (e.summary.isNotEmpty()) Text(e.summary, color = SMuted, fontSize = 12.sp)
              }
              Text(DateUtils.getRelativeTimeSpanString(e.at).toString(), color = SMuted, fontSize = 12.sp)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
  Text(title.uppercase(), color = SMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SPanel).padding(18.dp)) { content() }
  Spacer(Modifier.height(22.dp))
}

@Composable
private fun Action(label: String, danger: Boolean = false, onClick: () -> Unit) {
  Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
      modifier = Modifier.clip(RoundedCornerShape(50)).background(if (danger) Color(0xFF5A2430) else Color(0xFF6C5CE7))
          .clickable { onClick() }.padding(horizontal = 18.dp, vertical = 10.dp))
}
