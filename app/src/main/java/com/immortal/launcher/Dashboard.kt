/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How often the smart-home cards re-read Home Assistant / Hue while the dashboard is on screen. */
private const val HOME_POLL_MS = 45_000L

/** Let a burst of thermostat taps settle into one service call. */
private const val SETPOINT_DEBOUNCE_MS = 900L

private val CardFill = Color(0x8C10141C)
private val CardEdge = Color(0x1FFFFFFF)
private val Muted = Color(0xFFA9A9B2)
private val Accent = Color(0xFF4C8DFF)
private val Warm = Color(0xFFFFB547)

/**
 * The widget home screen: greeting and clock, what's playing, the weather, the thermostats and
 * the lights, with Calls / Apps / Tools / Store / Settings in the header. Every card reads a real
 * source — [NowPlayingHub], [Weather], [HomeControls] — and hides or explains itself when that
 * source isn't set up. Polling only runs while the dashboard is resumed; nothing animates
 * forever.
 */
@Composable
internal fun DashboardScreen(
    onOpenApps: () -> Unit,
    onCalls: () -> Unit,
    onOpenStore: () -> Unit,
    onScreensaver: () -> Unit,
) {
  val context = LocalContext.current
  var museHey by remember { mutableStateOf(museHeyEnabled(context)) }
  var use24Hour by remember { mutableStateOf(ImmortalSettings.use24HourClock(context)) }
  var timerRinging by remember { mutableStateOf(TimerStore.load(context).ringing) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e ->
      if (e == Lifecycle.Event.ON_RESUME) {
        museHey = museHeyEnabled(context)
        use24Hour = ImmortalSettings.use24HourClock(context)
      }
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }
  DisposableEffect(Unit) {
    val l: () -> Unit = { timerRinging = TimerStore.load(context).ringing }
    TimerStore.addListener(l)
    onDispose { TimerStore.removeListener(l) }
  }

  // What's playing (the hub notifies off-main; hop back before touching state).
  var np by remember { mutableStateOf(NowPlayingHub.current) }
  val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
  DisposableEffect(Unit) {
    val l = NowPlayingHub.Listener { s -> main.post { np = s } }
    NowPlayingHub.addListener(l)
    onDispose { NowPlayingHub.removeListener(l) }
  }
  val playing = np?.active == true

  // Smart home: re-read while resumed; actions update optimistically and re-read shortly after.
  var home by remember { mutableStateOf<HomeControls.Snapshot?>(null) }
  var refresh by remember { mutableStateOf(0) }
  LaunchedEffect(lifecycleOwner, refresh) {
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (true) {
        home = withContext(Dispatchers.IO) { runCatching { HomeControls.load(context) }.getOrNull() }
        delay(HOME_POLL_MS)
      }
    }
  }

  Box(Modifier.fillMaxSize()) {
    // The live weather sky, unless the user picked a photo or a fixed gradient as their wallpaper.
    val wallpaper = remember { WallpaperConfig.load(context).mode }
    if (wallpaper in setOf(WallpaperConfig.DARK, WallpaperConfig.SKY, WallpaperConfig.WEATHER)) {
      WeatherSky(Modifier.fillMaxSize())
      Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x33000000), Color(0x59000000)))))
    } else {
      HomeBackground(Modifier.fillMaxSize())
      Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x66000000), Color(0x99000000)))))
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 24.dp)) {
      val portrait = maxHeight > maxWidth
      val snap = home
      val actions: @Composable () -> Unit = {
        DashboardActions(museHey, onCalls = onCalls, onOpenApps = onOpenApps, onOpenStore = onOpenStore, onScreensaver = onScreensaver)
      }
      // Smart-home column(s): thermostats + lights when connected, else one card explaining how.
      val homeCards: @Composable (Modifier, Modifier) -> Unit = { climateMod, lightsMod ->
        if (snap != null && snap.configured) {
          if (snap.homeAssistant) ClimateCard(snap, climateMod, onChanged = { refresh++ })
          LightsCard(snap, lightsMod, onChanged = { refresh++ })
        } else {
          SetupCard(loading = snap == null, climateMod)
        }
      }
      if (portrait) {
        // Portal Mini standing up (and Portal Go): clock on top, then a stack of cards.
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Greeting()
          Row(verticalAlignment = Alignment.Bottom) {
            MinuteClockText(use24Hour, fontSize = 88.sp)
            Spacer(Modifier.width(20.dp))
            MinuteDateText(fontSize = 22.sp, color = Color(0xFFDADADA), modifier = Modifier.padding(bottom = 12.dp))
          }
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { actions() }
          // Idle, the music card only needs room for its hint; the rest goes to the other cards.
          NowPlayingCard(np, Modifier.fillMaxWidth().weight(if (playing) 0.8f else 0.4f))
          Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DashboardWeatherCard(Modifier.weight(1f).fillMaxHeight())
            if (snap != null && snap.configured && snap.homeAssistant) {
              ClimateCard(snap, Modifier.weight(1f).fillMaxHeight(), onChanged = { refresh++ })
            } else if (snap == null || !snap.configured) {
              SetupCard(loading = snap == null, Modifier.weight(1f).fillMaxHeight())
            }
          }
          if (snap != null && snap.configured) {
            LightsCard(snap, Modifier.fillMaxWidth().weight(0.9f), onChanged = { refresh++ })
          }
        }
      } else {
        Column(Modifier.fillMaxSize()) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Greeting()
              Row(verticalAlignment = Alignment.Bottom) {
                MinuteClockText(use24Hour, fontSize = 60.sp)
                Spacer(Modifier.width(18.dp))
                MinuteDateText(fontSize = 20.sp, color = Color(0xFFDADADA), modifier = Modifier.padding(bottom = 8.dp))
              }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) { actions() }
          }
          Spacer(Modifier.height(20.dp))
          Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1.25f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
              NowPlayingCard(np, Modifier.fillMaxWidth().weight(if (playing) 1f else 0.6f))
              DashboardWeatherCard(Modifier.fillMaxWidth().weight(1f))
            }
            homeCards(Modifier.weight(1f).fillMaxHeight(), Modifier.weight(1f).fillMaxHeight())
          }
        }
      }
    }
    if (timerRinging) TimerAlarmOverlay(onStop = { TimerAlarm.stop(context) })
  }
}

// --- header --------------------------------------------------------------------

/** Calls / Apps / Tools / Store / Settings, the photo frame and (when paired) Alfred. */
@Composable
private fun DashboardActions(
    museHey: Boolean,
    onCalls: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenStore: () -> Unit,
    onScreensaver: () -> Unit,
) {
  val context = LocalContext.current
  if (museHey) {
    HeaderAction("Alfred", null, Color(0x33FFFFFF), onLongClick = { openMuse(context, talk = false) }) {
      openMuse(context, talk = true)
    }
  }
  HeaderAction("Photos", GLYPH_PHOTO, Color(0x33FFFFFF), onClick = onScreensaver)
  HeaderAction("Calls", GLYPH_CALL, Color(0xFF1FA463), onClick = onCalls)
  HeaderAction("Apps", GLYPH_APPS, Accent, onClick = onOpenApps)
  HeaderAction("Tools", GLYPH_TOOLS, Color(0x33FFFFFF)) {
    runCatching { context.startActivity(Intent(context, ToolsActivity::class.java)) }
  }
  HeaderAction("Store", GLYPH_STORE, Color(0x33FFFFFF), onClick = onOpenStore)
  HeaderAction("Settings", GLYPH_GEAR, Color(0x33FFFFFF)) {
    runCatching { context.startActivity(Intent(context, ImmortalSettingsActivity::class.java)) }
  }
}

@Composable
private fun Greeting() {
  val now by rememberMinuteTicker()
  val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
  val text =
      when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        in 18..22 -> "Good evening"
        else -> "Good night"
      }
  Text(text, color = Muted, fontSize = 18.sp, fontWeight = FontWeight.Medium)
}

/** A round header button with a small caption; [glyph] null draws the microphone. */
@Composable
private fun HeaderAction(
    label: String,
    glyph: String?,
    fill: Color,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(fill).tvFocusable(CircleShape, onLongClick = onLongClick) {
          onClick()
        },
        contentAlignment = Alignment.Center,
    ) {
      if (glyph == null) MicGlyph() else Glyph(glyph, 26.dp)
    }
    Text(label, color = Color(0xFFDADADA), fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp), maxLines = 1)
  }
}

// --- cards ---------------------------------------------------------------------

@Composable
private fun DashCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Column(
      modifier
          .clip(RoundedCornerShape(26.dp))
          .background(CardFill)
          .border(1.dp, CardEdge, RoundedCornerShape(26.dp))
          .padding(18.dp)) {
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.height(12.dp))
        content()
      }
}

/**
 * What's playing, album-art first (like Apple Music's now-playing): the cover on the left, the
 * card tinted with the cover's own colour, title/artist, a progress bar and the transport. Idle,
 * it shrinks to a one-line hint.
 */
@Composable
private fun NowPlayingCard(np: NowPlayingState?, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val s = np
  val cover by
      produceState<android.graphics.Bitmap?>(s?.artBitmap, s?.artBitmap, s?.artUrl) {
        if (value == null && !s?.artUrl.isNullOrBlank())
            value = withContext(Dispatchers.IO) { runCatching { MediaArt.resolveUri(context, s!!.artUrl) }.getOrNull() }
      }
  if (s == null || !s.active) {
    DashCard("Now playing", modifier) {
      Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x22FFFFFF)),
            contentAlignment = Alignment.Center) {
              Glyph(GLYPH_NOTE, 30.dp, Muted)
            }
        Spacer(Modifier.width(16.dp))
        Column {
          Text("Nothing playing", color = Color.White, fontSize = 20.sp)
          Text("Ask Alfred to play something", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
      }
    }
    return
  }
  val bmp = cover
  val tint = remember(bmp) { bmp?.let { dominantColor(it) } ?: Color(0xFF2A2F3A) }
  Row(
      modifier
          .clip(RoundedCornerShape(26.dp))
          .background(Brush.linearGradient(listOf(tint, darken(tint, 0.55f))))
          .border(1.dp, CardEdge, RoundedCornerShape(26.dp))
          .padding(14.dp),
      verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.fillMaxHeight().aspectSquare().clip(RoundedCornerShape(18.dp)).background(Color(0x22FFFFFF))) {
              if (bmp != null)
                  Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
              else Glyph(GLYPH_NOTE, 40.dp, Muted, Modifier.align(Alignment.Center))
            }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
          Text(
              s.title,
              color = Color.White,
              fontSize = 24.sp,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              modifier = Modifier.basicMarquee())
          if (s.artist.isNotBlank())
              Text(s.artist, color = Color(0xCCFFFFFF), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Spacer(Modifier.height(14.dp))
          PlaybackProgress(s)
          Spacer(Modifier.height(14.dp))
          Row(
              Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceEvenly,
              verticalAlignment = Alignment.CenterVertically) {
                RoundButton(GLYPH_PREV, 52.dp, Color.Transparent) { NowPlayingHub.previous() }
                RoundButton(if (s.state == PlaybackState.PLAYING) GLYPH_PAUSE else GLYPH_PLAY, 64.dp, Color(0x33FFFFFF)) {
                  NowPlayingHub.playPause()
                }
                RoundButton(GLYPH_NEXT, 52.dp, Color.Transparent) { NowPlayingHub.next() }
              }
        }
      }
}

/**
 * Elapsed / remaining with a thin bar. The hub only notifies on track changes, so while playing
 * this reads the latest position once a second itself — and only while the card is on screen.
 */
@Composable
private fun PlaybackProgress(s: NowPlayingState) {
  if (s.durationMs <= 0L) return
  val pos by
      produceState(s.positionMs, s.title, s.state) {
        while (true) {
          value = NowPlayingHub.current?.positionMs ?: value
          if (s.state != PlaybackState.PLAYING) break
          delay(1000)
        }
      }
  val frac = (pos.toFloat() / s.durationMs).coerceIn(0f, 1f)
  Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF))) {
    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Color(0xE6FFFFFF)))
  }
  Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
    Text(mmss(pos), color = Color(0x99FFFFFF), fontSize = 12.sp)
    Spacer(Modifier.weight(1f))
    Text("-" + mmss(s.durationMs - pos), color = Color(0x99FFFFFF), fontSize = 12.sp)
  }
}

private fun mmss(ms: Long): String {
  val t = (ms / 1000).coerceAtLeast(0)
  return "%d:%02d".format(t / 60, t % 60)
}

/** The cover's average colour, darkened a touch so white text always reads on it. */
private fun dominantColor(b: android.graphics.Bitmap): Color {
  val small = android.graphics.Bitmap.createScaledBitmap(b, 8, 8, true)
  var r = 0
  var g = 0
  var bl = 0
  for (x in 0 until 8) for (y in 0 until 8) {
    val p = small.getPixel(x, y)
    r += android.graphics.Color.red(p)
    g += android.graphics.Color.green(p)
    bl += android.graphics.Color.blue(p)
  }
  if (small !== b) small.recycle()
  return darken(Color(r / 64, g / 64, bl / 64), 0.8f)
}

private fun darken(c: Color, f: Float) = Color(c.red * f, c.green * f, c.blue * f, 1f)

@Composable
private fun ClimateCard(snap: HomeControls.Snapshot, modifier: Modifier, onChanged: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  // Optimistic set-points while a change is in flight, keyed by entity.
  val pending = remember { mutableStateMapOf<String, Double>() }
  val jobs = remember { HashMap<String, Job>() }
  DashCard("Climate", modifier) {
    if (snap.thermostats.isEmpty()) {
      Text(
          "No thermostats in Home Assistant yet. Add the Hilo integration (or any thermostat) and they appear here.",
          color = Muted,
          fontSize = 14.sp)
      return@DashCard
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      snap.thermostats.forEach { t ->
        val target = pending[t.entityId] ?: t.target
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x14FFFFFF)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text(t.name, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.Bottom) {
                  Text(t.current?.let { fmtTemp(it) } ?: "—", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Light)
                  val heating = t.action.equals("heating", true)
                  Text(
                      if (heating) "  heating" else "  ${t.action}",
                      color = if (heating) Warm else Muted,
                      fontSize = 12.sp,
                      modifier = Modifier.padding(bottom = 6.dp))
                }
              }
              if (target != null) {
                RoundButton(GLYPH_MINUS, 40.dp, Color(0x26FFFFFF)) {
                  nudge(context, scope, pending, jobs, t, target - HomeControls.step(t), onChanged)
                }
                Text(
                    fmtTemp(target),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp))
                RoundButton(GLYPH_PLUS, 40.dp, Color(0x26FFFFFF)) {
                  nudge(context, scope, pending, jobs, t, target + HomeControls.step(t), onChanged)
                }
              }
            }
      }
    }
  }
}

private fun nudge(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    pending: MutableMap<String, Double>,
    jobs: HashMap<String, Job>,
    t: HomeControls.Thermostat,
    value: Double,
    onChanged: () -> Unit,
) {
  val v = if (t.unit.contains("F")) value.coerceIn(41.0, 86.0) else value.coerceIn(5.0, 30.0)
  pending[t.entityId] = v
  jobs[t.entityId]?.cancel()
  jobs[t.entityId] =
      scope.launch {
        delay(SETPOINT_DEBOUNCE_MS)
        withContext(Dispatchers.IO) { runCatching { HomeControls.setTarget(context, t, v) } }
        delay(1500)
        pending.remove(t.entityId)
        onChanged()
      }
}

@Composable
private fun LightsCard(snap: HomeControls.Snapshot, modifier: Modifier, onChanged: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val flipped = remember { mutableStateMapOf<String, Boolean>() }
  DashCard("Lights", modifier) {
    if (snap.lights.isEmpty()) {
      Text(
          snap.error ?: "No lights found. Pair Hue in Settings › Muse, or add lights to Home Assistant.",
          color = Muted,
          fontSize = 14.sp)
      return@DashCard
    }
    val rows = snap.lights.chunked(2)
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (snap.scenes.isNotEmpty()) SceneChips(snap, onChanged)
      rows.forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          pair.forEach { light ->
            val on = flipped[light.id] ?: light.on
            Row(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (on) Color(0xFFF4F1E8) else Color(0x14FFFFFF))
                    .tvFocusable(RoundedCornerShape(18.dp)) {
                      flipped[light.id] = !on
                      scope.launch {
                        withContext(Dispatchers.IO) { runCatching { HomeControls.toggle(context, light.copy(on = on)) } }
                        delay(1500)
                        flipped.remove(light.id)
                        onChanged()
                      }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                  Glyph(GLYPH_BULB, 26.dp, if (on) Warm else Muted)
                  Spacer(Modifier.width(10.dp))
                  Column {
                    Text(
                        light.name,
                        color = if (on) Color(0xFF16161A) else Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(
                        if (!on) "Off" else light.brightness?.let { "$it%" } ?: "On",
                        color = if (on) Color(0xFF55555E) else Muted,
                        fontSize = 12.sp)
                  }
                }
          }
          if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
      }
    }
  }
}

/** Hue scenes as tappable chips ("Honolulu", "Pumpkin Spice"); the room is added when a name repeats. */
@Composable
private fun SceneChips(snap: HomeControls.Snapshot, onChanged: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var active by remember { mutableStateOf<String?>(null) }
  val repeated = snap.scenes.groupingBy { it.name.lowercase() }.eachCount()
  Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    snap.scenes.forEach { sc ->
      val label = if ((repeated[sc.name.lowercase()] ?: 0) > 1 && sc.room.isNotBlank()) "${sc.name} · ${sc.room}" else sc.name
      val on = active == sc.id
      Text(
          label,
          color = if (on) Color(0xFF16161A) else Color.White,
          fontSize = 14.sp,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          modifier =
              Modifier.clip(RoundedCornerShape(50))
                  .background(if (on) Color(0xFFF4F1E8) else Color(0x1FFFFFFF))
                  .tvFocusable(RoundedCornerShape(50)) {
                    active = sc.id
                    scope.launch {
                      withContext(Dispatchers.IO) { runCatching { HomeControls.activate(context, sc) } }
                      delay(1500)
                      onChanged()
                    }
                  }
                  .padding(horizontal = 14.dp, vertical = 9.dp))
    }
  }
}

@Composable
private fun SetupCard(loading: Boolean, modifier: Modifier) {
  val context = LocalContext.current
  DashCard("Home", modifier) {
    if (loading) {
      Text("Loading…", color = Muted, fontSize = 14.sp)
      return@DashCard
    }
    Text(
        "Connect Home Assistant and Hue to control your thermostats (including Hilo), lights and " +
            "scenes from here.",
        color = Muted,
        fontSize = 14.sp)
    Spacer(Modifier.height(14.dp))
    Text(
        "Connect",
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier =
            Modifier.clip(RoundedCornerShape(50))
                .background(Accent)
                .tvFocusable(RoundedCornerShape(50)) {
                  runCatching { context.startActivity(SmartHomeConnectActivity.intent(context)) }
                }
                .padding(horizontal = 22.dp, vertical = 12.dp))
  }
}

// --- small pieces ---------------------------------------------------------------

@Composable
private fun RoundButton(glyph: String, size: Dp, fill: Color, tint: Color = Color.White, onClick: () -> Unit) {
  Box(
      Modifier.size(size).clip(CircleShape).background(fill).tvFocusable(CircleShape) { onClick() },
      contentAlignment = Alignment.Center) {
        Glyph(glyph, size * 0.5f, tint)
      }
}

/** A 24×24 Material path, drawn as a vector. */
@Composable
private fun Glyph(path: String, size: Dp, color: Color = Color.White, modifier: Modifier = Modifier) {
  val p = remember(path) { PathParser().parsePathString(path).toPath() }
  Canvas(modifier.size(size)) {
    val s = this.size.minDimension / 24f
    scale(s, s, pivot = Offset.Zero) { drawPath(p, color) }
  }
}

private fun Modifier.aspectSquare() = this.then(Modifier.aspectRatio(1f))

private fun fmtTemp(v: Double): String =
    if (v % 1.0 == 0.0) "${v.toInt()}°" else String.format(Locale.getDefault(), "%.1f°", v)

private const val GLYPH_APPS =
    "M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4zm-6 4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z"
private const val GLYPH_TOOLS =
    "M22.7 19l-9.1-9.1c.9-2.3.4-5-1.5-6.9-2-2-5-2.4-7.4-1.3L9 6 6 9 1.6 4.7C.4 7.1.9 10.1 2.9 12.1c1.9 1.9 4.6 2.4 6.9 1.5l9.1 9.1c.4.4 1 .4 1.4 0l2.3-2.3c.5-.4.5-1.1 0-1.4z"
private const val GLYPH_STORE =
    "M18 6h-2c0-2.21-1.79-4-4-4S8 3.79 8 6H6c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm-6-2c1.1 0 2 .9 2 2h-4c0-1.1.9-2 2-2zm0 10c-2.76 0-5-2.24-5-5h2c0 1.66 1.34 3 3 3s3-1.34 3-3h2c0 2.76-2.24 5-5 5z"
private const val GLYPH_PLAY = "M8 5v14l11-7z"
private const val GLYPH_PAUSE = "M6 19h4V5H6v14zm8-14v14h4V5h-4z"
private const val GLYPH_NEXT = "M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"
private const val GLYPH_PREV = "M6 6h2v12H6zm3.5 6l8.5 6V6z"
private const val GLYPH_NOTE =
    "M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"
private const val GLYPH_BULB =
    "M9 21c0 .55.45 1 1 1h4c.55 0 1-.45 1-1v-1H9v1zm3-19C8.14 2 5 5.14 5 9c0 2.38 1.19 4.47 3 5.74V17c0 .55.45 1 1 1h6c.55 0 1-.45 1-1v-2.26c1.81-1.27 3-3.36 3-5.74 0-3.86-3.14-7-7-7z"
private const val GLYPH_MINUS = "M19 13H5v-2h14v2z"
private const val GLYPH_PLUS = "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"
private const val GLYPH_CALL =
    "M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z"
private const val GLYPH_PHOTO =
    "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z"
private const val GLYPH_GEAR =
    "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"
