/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.immortal.launcher.ui.theme.SampleAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Connect your smart home": Home Assistant (found by mDNS, signed in on the Portal with HA's own
 * login page — no token to paste) and Philips Hue (found by mDNS, paired with the link button).
 * Reached from Settings › Muse › Smart home, and from the dashboard via [intent].
 * The plumbing and the pure parts live in SmartHomeConnect.kt.
 */
class SmartHomeConnectActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { SmartHomeConnectScreen(onDone = { finish() }) } }
  }

  companion object {
    fun intent(c: Context): Intent = Intent(c, SmartHomeConnectActivity::class.java)
  }
}

private val Bg = Color(0xFF101012)
private val Panel = Color(0xFF1C1C1E)
private val Muted = Color(0xFF9A9A9A)
private val Good = Color(0xFF3DDC84)
private val Bad = Color(0xFFE89090)
private val Accent = Color(0xFF2E6BE6)

private const val HUE_POLL_MS = 2_000L
private const val HUE_WINDOW_MS = 30_000L

@Composable
private fun SmartHomeConnectScreen(onDone: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  // --- Home Assistant state ---
  var haUrl by remember { mutableStateOf(if (MuseHomeAssistant.configured(context)) MuseConfig.haUrl(context) else "") }
  var haFound by remember { mutableStateOf<List<HaInstance>?>(null) }
  var haManual by remember { mutableStateOf("") }
  var haBusy by remember { mutableStateOf(false) }
  var haError by remember { mutableStateOf<String?>(null) }
  var haSummary by remember { mutableStateOf<HaSummary?>(null) }
  // Non-null while HA's login page is up: (base URL, OAuth state).
  var login by remember { mutableStateOf<Pair<String, String>?>(null) }

  // --- Hue state ---
  var hueBridge by remember { mutableStateOf(if (MuseHue.paired(context)) MuseConfig.hueBridge(context) else "") }
  var hueFound by remember { mutableStateOf<List<HueBridge>?>(null) }
  var hueSearches by remember { mutableStateOf(0) }
  var huePairing by remember { mutableStateOf<String?>(null) } // bridge IP while waiting for the button
  var hueSecondsLeft by remember { mutableStateOf(0) }
  var hueError by remember { mutableStateOf<String?>(null) }
  var hueSummary by remember { mutableStateOf<SmartHomeConnect.HueSummary?>(null) }

  fun searchHa() {
    haFound = null
    scope.launch {
      val found = withContext(Dispatchers.IO) { SmartHomeConnect.discoverHa() }
      haFound = found
    }
  }

  fun searchHue() {
    hueFound = null
    val cloud = hueSearches > 0 // the rate-limited cloud directory only on an explicit retry
    hueSearches++
    scope.launch {
      val found = withContext(Dispatchers.IO) { SmartHomeConnect.discoverHue(useCloud = cloud) }
      hueFound = found
    }
  }

  fun startLogin(raw: String) {
    val base = HaAuth.normalizeBaseUrl(raw)
    if (base == null) {
      haError = "That doesn't look like a Home Assistant address (e.g. http://192.168.1.20:8123)."
      return
    }
    haError = null
    haSummary = null
    login = base to HaAuth.newState()
  }

  fun onLoginCode(base: String, code: String) {
    login = null
    haBusy = true
    scope.launch {
      val result = withContext(Dispatchers.IO) { runCatching { SmartHomeConnect.finishHa(context, base, code) } }
      haBusy = false
      result
          .onSuccess {
            haSummary = it
            haUrl = base
          }
          .onFailure { haError = it.message ?: "Couldn't finish connecting to Home Assistant." }
    }
  }

  fun pairHue(ip: String) {
    huePairing = ip
    hueError = null
    hueSummary = null
    scope.launch {
      val deadline = System.currentTimeMillis() + HUE_WINDOW_MS
      var lastError: String? = null
      var paired = false
      while (System.currentTimeMillis() < deadline) {
        hueSecondsLeft = ((deadline - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
        val r = withContext(Dispatchers.IO) { runCatching { SmartHomeConnect.tryPairHue(context, ip) } }
        if (r.getOrNull() == true) {
          paired = true
          break
        }
        lastError = r.exceptionOrNull()?.message
        delay(HUE_POLL_MS)
      }
      huePairing = null
      if (paired) {
        hueBridge = ip
        hueSummary = withContext(Dispatchers.IO) { runCatching { SmartHomeConnect.hueSummary(context) }.getOrNull() }
      } else {
        hueError = lastError?.let { "Couldn't reach the bridge: $it" }
            ?: "The bridge's button wasn't pressed in time. Try again."
      }
    }
  }

  LaunchedEffect(Unit) {
    if (haUrl.isEmpty()) searchHa()
    if (hueBridge.isEmpty()) searchHue()
  }

  val activeLogin = login
  if (activeLogin != null) {
    HaLoginView(
        base = activeLogin.first,
        state = activeLogin.second,
        onCode = { code -> onLoginCode(activeLogin.first, code) },
        onFailed = { reason ->
          login = null
          haError = reason
        },
        onCancel = { login = null })
    return
  }

  BackHandler { onDone() }

  Column(
      modifier =
          Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState())
              .padding(horizontal = 28.dp, vertical = 32.dp),
  ) {
    Column(modifier = Modifier.widthIn(max = 1100.dp)) {
      Text("Connect your smart home", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
      Text(
          "Your thermostats and lights on the dashboard, and Alfred's smart-home control, use these " +
              "connections. Everything stays on your home network.",
          color = Muted,
          fontSize = 16.sp,
          modifier = Modifier.padding(top = 6.dp))
      if (!MuseConfig.allowSmartHome(context)) {
        Text(
            "Smart home control for Muse is off (Settings › Muse); the dashboard still uses these connections.",
            color = Muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp))
      }
      Spacer(Modifier.height(22.dp))

      // ---------------- Home Assistant ----------------
      Section("Home Assistant") {
        if (haUrl.isNotEmpty()) {
          StatusRow("Connected to $haUrl", connected = true) {
            SmartHomeConnect.disconnectHa(context)
            haUrl = ""
            haSummary = null
            searchHa()
          }
        } else {
          StatusRow("Not connected", connected = false, onDisconnect = null)
        }

        haSummary?.let { s -> HaSummaryText(s) }

        if (haBusy) Hint("Finishing up: creating a token for this Portal…")
        haError?.let { Text(it, color = Bad, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)) }

        if (haUrl.isEmpty() && !haBusy) {
          Spacer(Modifier.height(12.dp))
          val found = haFound
          when {
            found == null -> Hint("Looking for Home Assistant on your network…")
            found.isEmpty() -> Hint("No Home Assistant found automatically. Enter its address below.")
            else -> {
              Hint("Found on your network — tap to sign in:")
              found.forEach { h ->
                ListRow(h.name, h.url + (if (h.version.isNotEmpty()) " · ${h.version}" else "")) { startLogin(h.url) }
              }
            }
          }
          if (found != null) SmallButton("Search again") { searchHa() }

          Spacer(Modifier.height(14.dp))
          OutlinedTextField(
              value = haManual,
              onValueChange = { haManual = it },
              placeholder = { Text("http://192.168.1.20:8123", color = Color(0xFF777777)) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
              shape = RoundedCornerShape(14.dp))
          Spacer(Modifier.height(10.dp))
          PrimaryButton("Sign in to Home Assistant", enabled = haManual.isNotBlank()) { startLogin(haManual) }
          Hint(
              "You'll sign in with your Home Assistant account on this screen. Immortal then keeps a " +
                  "long-lived token named after this Portal (you can revoke it in your HA profile › Security).")
        }
      }

      Spacer(Modifier.height(18.dp))

      // ---------------- Philips Hue ----------------
      Section("Philips Hue") {
        if (hueBridge.isNotEmpty()) {
          StatusRow("Connected to bridge $hueBridge", connected = true) {
            SmartHomeConnect.disconnectHue(context)
            hueBridge = ""
            hueSummary = null
            searchHue()
          }
        } else {
          StatusRow("Not connected", connected = false, onDisconnect = null)
        }
        hueSummary?.let {
          Text(
              "${plural(it.rooms, "room")}, ${plural(it.scenes, "scene")}, ${plural(it.lights, "light")}.",
              color = Color.White,
              fontSize = 15.sp,
              modifier = Modifier.padding(top = 10.dp))
        }
        hueError?.let { Text(it, color = Bad, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)) }

        val pairing = huePairing
        if (pairing != null) {
          Text(
              "Press the round button on your Hue bridge now.",
              color = Color.White,
              fontSize = 20.sp,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.padding(top = 14.dp))
          Hint("Waiting for bridge $pairing… ${hueSecondsLeft}s")
        } else if (hueBridge.isEmpty()) {
          Spacer(Modifier.height(12.dp))
          val found = hueFound
          when {
            found == null -> Hint("Looking for a Hue bridge on your network…")
            found.isEmpty() ->
                Hint("No Hue bridge found. Check it's powered and on the same network, then search again.")
            else -> {
              Hint("Tap your bridge, then press its button:")
              found.forEach { b -> ListRow(b.name, b.ip) { pairHue(b.ip) } }
            }
          }
          if (found != null) SmallButton("Search again") { searchHue() }
        }
      }

      Spacer(Modifier.height(22.dp))
      Surface(
          color = Panel,
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) { onDone() }) {
        Text(
            "Done",
            color = Color(0xFFDDDDDD),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth())
      }
    }
  }
}

@Composable
private fun HaSummaryText(s: HaSummary) {
  Text(
      "Found ${plural(s.thermostats, "thermostat")} and ${plural(s.lights, "light")}." +
          when {
            s.hiloThermostats != null && s.hiloThermostats > 0 -> " ${plural(s.hiloThermostats, "Hilo thermostat")}."
            s.hasHilo -> " Hilo is set up."
            else -> ""
          },
      color = Color.White,
      fontSize = 15.sp,
      modifier = Modifier.padding(top = 10.dp))
  if (!s.hasHilo) {
    Hint(
        "Have Hydro-Québec Hilo thermostats? Install the Hilo integration through HACS in Home Assistant " +
            "and they'll show up here and on the dashboard.")
  }
}

/** HA's login page, full screen. The redirect back to [HaAuth.REDIRECT_URI] is caught, never loaded. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HaLoginView(base: String, state: String, onCode: (String) -> Unit, onFailed: (String) -> Unit, onCancel: () -> Unit) {
  var web by remember { mutableStateOf<WebView?>(null) }
  BackHandler {
    val w = web
    if (w != null && w.canGoBack()) w.goBack() else onCancel()
  }
  Column(Modifier.fillMaxSize().background(Bg)) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
      Text("Sign in to Home Assistant", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
      SmallButton("Cancel") { onCancel() }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            var handled = false
            /** True if [url] was the callback (handled here, so the WebView must not load it). */
            fun intercept(view: WebView, url: String?): Boolean {
              if (!HaAuth.isCallback(url)) return false
              view.stopLoading()
              if (handled) return true
              handled = true
              // Posted: leaving the login tears this WebView down, which mustn't happen inside its own callback.
              when (val cb = HaAuth.parseCallback(url!!, state)) {
                is HaAuth.Callback.Code -> view.post { onCode(cb.code) }
                is HaAuth.Callback.Failed -> view.post { onFailed(cb.reason) }
                HaAuth.Callback.NotOurs -> handled = false
              }
              return true
            }
            webViewClient =
                object : WebViewClient() {
                  override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                      intercept(view, request.url.toString())

                  @Deprecated("pre-API 24 path")
                  override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean = intercept(view, url)

                  // Older WebViews don't always route a script's location change through the above.
                  override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (!intercept(view, url)) super.onPageStarted(view, url, favicon)
                  }
                }
            loadUrl(HaAuth.authorizeUrl(base, state))
            web = this
          }
        },
        onRelease = { w ->
          w.stopLoading()
          w.destroy()
        })
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
      Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
      content()
    }
  }
}

@Composable
private fun StatusRow(text: String, connected: Boolean, onDisconnect: (() -> Unit)?) {
  Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(
        (if (connected) "● " else "○ ") + text,
        color = if (connected) Good else Muted,
        fontSize = 16.sp,
        modifier = Modifier.weight(1f))
    if (onDisconnect != null) SmallButton("Disconnect", danger = true, onClick = onDisconnect)
  }
}

@Composable
private fun ListRow(title: String, subtitle: String, onClick: () -> Unit) {
  Column(
      Modifier.fillMaxWidth().padding(top = 6.dp).background(Color(0xFF26262A), RoundedCornerShape(12.dp))
          .tvFocusableRow { onClick() }.padding(horizontal = 16.dp, vertical = 12.dp)) {
    Text(title, color = Color.White, fontSize = 17.sp)
    Text(subtitle, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
  }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
  Surface(
      color = if (enabled) Accent else Color(0xFF2A2A2C),
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) { if (enabled) onClick() }) {
    Text(
        label,
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth())
  }
}

@Composable
private fun SmallButton(label: String, danger: Boolean = false, onClick: () -> Unit) {
  Surface(
      color = Color(0xFF2A2A2E),
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier.padding(top = 8.dp).tvFocusable(RoundedCornerShape(12.dp)) { onClick() }) {
    Text(
        label,
        color = if (danger) Bad else Color.White,
        fontSize = 15.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
  }
}

@Composable
private fun Hint(text: String) {
  Text(text, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
}

private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"
