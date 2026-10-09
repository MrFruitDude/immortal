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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val Faint = Color(0x26FFFFFF)
private val Soft = Color(0xB3FFFFFF)

/**
 * The dashboard's weather card, in the spirit of the iOS weather widgets: city, the big
 * temperature with the condition and today's high/low, the next hours, then as many days of the
 * week as fit, each with a temperature-range bar on the week's scale.
 */
@Composable
internal fun DashboardWeatherCard(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val fahrenheit = remember { ImmortalSettings.useFahrenheit(context) }
  val c by
      produceState<Weather.Conditions?>(null) {
        while (true) {
          value = withContext(Dispatchers.IO) { Weather.fetchConditions(context) } ?: value
          delay(if (value == null) 60_000L else 15L * 60 * 1000)
        }
      }
  BoxWithConstraints(
      modifier
          .clip(RoundedCornerShape(26.dp))
          .background(Color(0x33101826))
          .border(1.dp, Color(0x29FFFFFF), RoundedCornerShape(26.dp))
          .padding(18.dp)) {
        val w = c
        if (w == null) {
          Text("Weather", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
          return@BoxWithConstraints
        }
        // Header ~110dp, hours ~84dp, then 34dp per day.
        val dayRows = ((maxHeight.value - 110 - 84 - 24) / 34).toInt().coerceIn(0, w.days.size)
        val wide = maxWidth.value > 300
        Column {
          Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
              Text(
                  w.city.ifBlank { "Weather" },
                  color = Color.White,
                  fontSize = 18.sp,
                  fontWeight = FontWeight.SemiBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis)
              Text("${w.temp}°", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Light, lineHeight = 60.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
              Text(icon(w.code, w.isDay), fontSize = 26.sp)
              Text(Weather.conditionName(w.code), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
              Text("H:${w.hi}°  L:${w.lo}°", color = Soft, fontSize = 13.sp)
              if (wide) Text("Feels ${w.feelsLike}° · ${w.wind} ${w.windUnit}", color = Soft, fontSize = 12.sp)
            }
          }
          Spacer(Modifier.height(10.dp))
          Rule()
          Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            w.hours.take(if (wide) 6 else 5).forEachIndexed { i, h ->
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(h.label, color = Soft, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(icon(h.code, w.isDay || i > 0), fontSize = 18.sp, modifier = Modifier.padding(vertical = 4.dp))
                Text("${h.temp}°", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
              }
            }
          }
          if (dayRows > 0) {
            Rule()
            Spacer(Modifier.height(6.dp))
            val weekLo = w.days.minOf { it.lo }
            val weekHi = w.days.maxOf { it.hi }
            w.days.take(dayRows).forEach { d ->
              Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(d.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(54.dp))
                Text(icon(d.code, true), fontSize = 17.sp, modifier = Modifier.width(30.dp))
                Text("${d.lo}°", color = Soft, fontSize = 14.sp, modifier = Modifier.width(34.dp))
                RangeBar(d.lo, d.hi, weekLo, weekHi, fahrenheit, Modifier.weight(1f).padding(horizontal = 6.dp))
                Text("${d.hi}°", color = Color.White, fontSize = 14.sp, modifier = Modifier.width(34.dp).padding(start = 6.dp))
              }
            }
          }
        }
      }
}

@Composable
private fun Rule() {
  Spacer(Modifier.fillMaxWidth().height(1.dp).background(Faint))
}

/** A day's low→high on the week's scale, coloured cool → warm like the iOS weather app. */
@Composable
private fun RangeBar(lo: Int, hi: Int, weekLo: Int, weekHi: Int, fahrenheit: Boolean, modifier: Modifier) {
  val span = (weekHi - weekLo).coerceAtLeast(1).toFloat()
  Canvas(modifier.height(5.dp)) {
    val r = CornerRadius(size.height / 2)
    drawRoundRect(Faint, cornerRadius = r)
    val x0 = size.width * ((lo - weekLo) / span)
    val x1 = size.width * ((hi - weekLo) / span)
    drawRoundRect(
        Brush.horizontalGradient(listOf(tempColor(lo, fahrenheit), tempColor(hi, fahrenheit)), startX = x0, endX = x1.coerceAtLeast(x0 + 1)),
        topLeft = Offset(x0, 0f),
        size = Size((x1 - x0).coerceAtLeast(size.height), size.height),
        cornerRadius = r)
  }
}

/** Cool → warm colour ramp on the Celsius scale. */
private fun tempColor(t: Int, fahrenheit: Boolean): Color {
  val c = if (fahrenheit) (t - 32) * 5 / 9 else t
  return when {
    c <= -10 -> Color(0xFF7FB3FF)
    c <= 0 -> Color(0xFF6CC6F0)
    c <= 10 -> Color(0xFF7ED9B5)
    c <= 18 -> Color(0xFFD7E36A)
    c <= 25 -> Color(0xFFF5C344)
    else -> Color(0xFFF59144)
  }
}

private fun icon(code: Int, day: Boolean): String = if (!day && code <= 1) "🌙" else Weather.emoji(code)
