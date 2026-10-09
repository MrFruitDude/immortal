/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The card re-reads Home Assistant every 5 minutes; Hydro-Québec answers come from a 10-minute cache. */
private const val ENERGY_POLL_MS = 5L * 60 * 1000

// The weather card's glass, so the two read as a set.
private val EnergyFill = Color(0x33101826)
private val EnergyEdge = Color(0x29FFFFFF)
private val EFaint = Color(0x26FFFFFF)
private val ESoft = Color(0xB3FFFFFF)
private val EMuted = Color(0xFFA9A9B2)
private val EWarm = Color(0xFFFFB547)
private val EGrid = Color(0xFF6FB6FF)

private val SourceColors =
    mapOf(
        EnergyData.Source.HYDRO to Color(0xFF4C8DFF),
        EnergyData.Source.WIND to Color(0xFF5ED6C3),
        EnergyData.Source.SOLAR to Color(0xFFFFD25A),
        EnergyData.Source.OTHER to Color(0xFFA9A9B2),
    )

/**
 * The energy card: the home's power right now with its last 24 hours and the rooms drawing it
 * (Home Assistant + Hilo, hidden when Home Assistant isn't connected), any Hilo défi, and the
 * Québec grid — total demand, its last 24 hours and the production mix (Hydro-Québec open data,
 * always available). Wide, home and grid sit side by side; narrow, they stack, dropping the room
 * row and the mix legend when the card is short. Polls only while the dashboard is resumed.
 */
@Composable
internal fun DashboardEnergyCard(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var snap by remember { mutableStateOf(EnergyData.last) }
  LaunchedEffect(lifecycleOwner) {
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      // The card moves between rows when music starts or stops; don't re-read on every move.
      val age = System.currentTimeMillis() - EnergyData.lastAt
      if (snap?.grid != null && age in 0 until ENERGY_POLL_MS) delay(ENERGY_POLL_MS - age)
      while (true) {
        snap = withContext(Dispatchers.IO) { runCatching { EnergyData.load(context) }.getOrNull() } ?: snap
        // Right after boot the network may not be up yet: retry in a minute until the grid answers.
        delay(if (snap?.grid == null) 60_000L else ENERGY_POLL_MS)
      }
    }
  }
  val use24h = remember { ImmortalSettings.use24HourClock(context) }
  val now by rememberMinuteTicker()
  val tz = TimeZone.getDefault()
  val locale = Locale.getDefault()

  BoxWithConstraints(
      modifier
          .dashboardCardSurface(26.dp, androidx.compose.ui.graphics.SolidColor(EnergyFill), EnergyEdge)
          .padding(18.dp)) {
        val s = snap
        val home = s?.home?.takeIf { s.haConfigured }
        val grid = s?.grid
        val defi = home?.defi?.let { EnergyData.defiLabel(it, now, tz, locale, use24h) }
        // Content height left under the title row (36dp incl. its gap).
        val avail = maxHeight - 36.dp
        val wide = maxWidth >= 520.dp
        Column(Modifier.fillMaxSize()) {
          Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Energy", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.weight(1f).width(8.dp))
            when {
              defi != null -> Chip(defi, strong = defi.contains("in progress"))
              home?.rewards != null ->
                  Text(
                      "Hilo rewards " + String.format(locale, "$%.2f", home.rewards),
                      color = EMuted,
                      fontSize = 12.sp,
                      maxLines = 1)
            }
          }
          Spacer(Modifier.height(10.dp))
          if (s == null) {
            Text("Loading…", color = EMuted, fontSize = 14.sp)
            return@Column
          }
          if (home == null && grid == null) {
            Text("Energy data isn't available right now.", color = EMuted, fontSize = 14.sp)
            return@Column
          }
          if (home != null && grid != null && wide) {
            // Side by side: each half needs ~90dp; with room to spare (a full-width card ~260dp
            // tall) the sparklines move under the numbers and take the extra height.
            val tall = avail >= 150.dp
            Row(Modifier.fillMaxWidth().weight(1f)) {
              HomeSection(home, locale, showRooms = avail >= 80.dp, Modifier.weight(1f).fillMaxHeight(), tall)
              Spacer(Modifier.padding(horizontal = 16.dp).width(1.dp).fillMaxHeight().background(EFaint))
              GridSection(grid, now, tz, locale, use24h, showLegend = avail >= 96.dp, Modifier.weight(1f).fillMaxHeight(), tall)
            }
          } else if (home != null && grid != null) {
            // Stacked: home ~44dp + 33 for the rooms, rule 25, grid ~70 + 22 for the legend. Rooms
            // matter more than the legend, so they're the last to go.
            val base = 142.dp
            val showRooms = avail >= base + 33.dp
            val showLegend = avail >= base + (if (showRooms) 33.dp else 0.dp) + 22.dp
            HomeSection(home, locale, showRooms, Modifier.fillMaxWidth())
            Spacer(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).background(EFaint))
            GridSection(grid, now, tz, locale, use24h, showLegend, Modifier.fillMaxWidth())
          } else if (grid != null) {
            GridSection(grid, now, tz, locale, use24h, showLegend = avail >= 96.dp, Modifier.fillMaxWidth().weight(1f), tall = true)
          } else if (home != null) {
            HomeSection(home, locale, showRooms = avail >= 76.dp, Modifier.fillMaxWidth().weight(1f), tall = true)
          }
        }
      }
}

/** "1.3 kW now" with the day's sparkline beside it (below it when [tall]), then the rooms. */
@Composable
private fun HomeSection(home: EnergyData.Home, locale: Locale, showRooms: Boolean, modifier: Modifier, tall: Boolean = false) {
  Column(modifier) {
    Row(verticalAlignment = Alignment.Bottom) {
      if (home.watts != null) {
        Text(EnergyData.formatWatts(home.watts, locale), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Light, lineHeight = 38.sp, maxLines = 1)
        Text(" now", color = EMuted, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(bottom = 6.dp))
      } else {
        Text("Home", color = ESoft, fontSize = 14.sp, maxLines = 1)
      }
      if (!tall && home.history.size > 1) {
        Spacer(Modifier.width(14.dp))
        Sparkline(home.history, EWarm, Modifier.weight(1f).height(34.dp).padding(bottom = 4.dp))
      }
    }
    if (tall && home.history.size > 1) {
      Sparkline(home.history, EWarm, Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp))
    }
    if (showRooms && home.rooms.isNotEmpty()) {
      Spacer(Modifier.height(8.dp))
      Rooms(home.rooms, locale)
    }
  }
}

/** Kitchen 435 W · Living room 596 W · Bedroom 0 W, each over a thin bar on a shared scale. */
@Composable
private fun Rooms(rooms: List<EnergyData.Room>, locale: Locale) {
  // Heaters run 1–3 kW: a floor on the scale keeps a lone 200 W room from looking maxed out.
  val scale = maxOf(rooms.maxOf { it.watts }, 1000.0)
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    rooms.forEach { r ->
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.Bottom) {
          Text(
              r.name,
              color = ESoft,
              fontSize = 12.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false))
          Text(" " + EnergyData.formatWatts(r.watts, locale), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Spacer(Modifier.height(5.dp))
        Bar((r.watts / scale).toFloat(), EWarm)
      }
    }
  }
}

/** "Québec grid" with any peak event, demand + sparkline, then the production mix. */
@Composable
private fun GridSection(
    grid: EnergyData.Grid,
    now: Long,
    tz: TimeZone,
    locale: Locale,
    use24h: Boolean,
    showLegend: Boolean,
    modifier: Modifier,
    tall: Boolean = false,
) {
  Column(modifier) {
    Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("Québec grid", color = EMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
      Spacer(Modifier.weight(1f).width(8.dp))
      grid.peak?.let { Chip(EnergyData.peakLabel(it, now, tz, locale, use24h), strong = now >= it.start, small = true) }
    }
    Row(verticalAlignment = Alignment.Bottom) {
      if (grid.demandMw != null) {
        Text(EnergyData.formatMw(grid.demandMw, locale), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Light, lineHeight = 30.sp, maxLines = 1)
        Text(" demand", color = EMuted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
      }
      if (!tall && grid.demand.size > 1) {
        Spacer(Modifier.width(14.dp))
        Sparkline(grid.demand, EGrid, Modifier.weight(1f).height(28.dp).padding(bottom = 4.dp))
      }
    }
    if (tall && grid.demand.size > 1) {
      Sparkline(grid.demand, EGrid, Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp))
    }
    if (grid.mix.isNotEmpty()) {
      Spacer(Modifier.height(8.dp))
      MixBar(grid.mix)
      if (showLegend) {
        Spacer(Modifier.height(6.dp))
        MixLegend(grid.mix)
      }
    }
  }
}

@Composable
private fun MixBar(mix: List<EnergyData.MixSlice>) {
  Canvas(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
    drawRect(EFaint)
    var x = 0f
    mix.forEach { m ->
      val w = size.width * m.fraction.toFloat()
      if (w > 0f) drawRect(SourceColors.getValue(m.source), topLeft = Offset(x, 0f), size = Size(w, size.height))
      x += w
    }
  }
}

/** Hydro 81% · Wind 16% · Other 4% (sources under half a percent — solar at night — left out). */
@Composable
private fun MixLegend(mix: List<EnergyData.MixSlice>) {
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    mix.filter { it.fraction >= 0.005 }.forEach { m ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(SourceColors.getValue(m.source)))
        Text(
            " ${m.source.label} ${Math.round(m.fraction * 100)}%",
            color = ESoft,
            fontSize = 12.sp,
            maxLines = 1)
      }
    }
  }
}

/** A thin track with a [fraction] fill. */
@Composable
private fun Bar(fraction: Float, color: Color) {
  Canvas(Modifier.fillMaxWidth().height(4.dp)) {
    val r = CornerRadius(size.height / 2)
    drawRoundRect(EFaint, cornerRadius = r)
    val f = fraction.coerceIn(0f, 1f)
    if (f > 0f) drawRoundRect(color, size = Size((size.width * f).coerceAtLeast(size.height), size.height), cornerRadius = r)
  }
}

/** A day of values as a line with a soft fill beneath and a dot on the latest value. Static. */
@Composable
private fun Sparkline(values: List<Double>, color: Color, modifier: Modifier) {
  Canvas(modifier) {
    if (values.size < 2 || size.width <= 0f || size.height <= 0f) return@Canvas
    val lo = values.min()
    val hi = values.max()
    val span = (hi - lo).takeIf { it > 1e-9 }
    val inset = 3.dp.toPx()
    val h = size.height - 2 * inset
    fun y(v: Double) = if (span == null) size.height / 2 else inset + h * (1f - ((v - lo) / span).toFloat())
    val dx = (size.width - inset) / (values.size - 1)
    val line = Path()
    values.forEachIndexed { i, v -> if (i == 0) line.moveTo(0f, y(v)) else line.lineTo(i * dx, y(v)) }
    val fill = Path().apply {
      addPath(line)
      lineTo((values.size - 1) * dx, size.height)
      lineTo(0f, size.height)
      close()
    }
    drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
    drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(color, radius = 3.dp.toPx(), center = Offset((values.size - 1) * dx, y(values.last())))
  }
}

/** A pill: warm outline for an upcoming event, solid warm while it's on. */
@Composable
private fun Chip(text: String, strong: Boolean, small: Boolean = false) {
  Text(
      text,
      color = if (strong) Color(0xFF1A1206) else EWarm,
      fontSize = if (small) 11.sp else 12.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier =
          Modifier.clip(RoundedCornerShape(50))
              .background(if (strong) EWarm else Color(0x29FFB547))
              .padding(horizontal = if (small) 8.dp else 10.dp, vertical = if (small) 2.dp else 4.dp))
}

/**
 * Stacks the energy card under [content] in one column (the landscape smart-home column), giving
 * [content] the larger share. [content] receives the modifier to apply.
 */
@Composable
internal fun WithEnergyBelow(modifier: Modifier, energyWeight: Float = 0.8f, content: @Composable ColumnScope.(Modifier) -> Unit) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
    content(Modifier.fillMaxWidth().weight(1f))
    DashboardEnergyCard(Modifier.fillMaxWidth().weight(energyWeight))
  }
}
