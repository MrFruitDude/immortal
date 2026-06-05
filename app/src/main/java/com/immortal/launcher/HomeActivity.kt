/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageInstaller
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.immortal.launcher.ui.theme.SampleAppTheme
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class AppEntry(
    val label: String,
    val component: ComponentName,
    val icon: ImageBitmap,
    val folder: String? = null,
)

/**
 * The custom Portal home launcher. Replaces the stock Aloha home (selected via
 * `cmd package set-home-activity`). Shows a clock/date/weather header, an App
 * Store tile, and a grid of every installed launchable app. Built for Portal's
 * form factor: dark theme, top 64dp reserved for the system overlay, large
 * touch targets, landscape.
 */
class HomeActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enterImmersive()
    setContent {
      SampleAppTheme(darkTheme = true) {
        LauncherScreen(
            onLaunch = { cn ->
              runCatching {
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(cn)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              }
            },
            onOpenStore = {
              runCatching { startActivity(Intent(this, StoreActivity::class.java)) }
            },
            onStartScreensaver = {
              runCatching {
                startActivity(Intent(this, PhotoFramePreviewActivity::class.java))
              }
            },
            onExitHome = {
              // Open the STOCK Portal home — the only caller Meta trusts to
              // launch Contacts/calling/camera. Cold start redirects (bounces
              // back here) while its task spins up, so we fire twice: the first
              // creates the task, the second (after a beat) brings it forward so
              // it stays. Home button returns to Immortal.
              val stock =
                  Intent(Intent.ACTION_MAIN)
                      .addCategory(Intent.CATEGORY_LAUNCHER)
                      .setComponent(
                          ComponentName(
                              "com.facebook.alohaapps.launcher",
                              "com.facebook.aloha.app.home.touch.HomeActivity"))
                      .addFlags(
                          Intent.FLAG_ACTIVITY_NEW_TASK or
                              Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
              runCatching { startActivity(stock) }
              window.decorView.postDelayed({ runCatching { startActivity(stock) } }, 600)
            },
            onUninstall = { pkg ->
              // System uninstall dialog; no special permission needed.
              runCatching {
                startActivity(
                    Intent(Intent.ACTION_DELETE)
                        .setData(android.net.Uri.parse("package:$pkg"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              }
            },
        )
      }
    }
  }

  // Re-assert immersive fullscreen whenever the launcher regains focus — e.g.
  // after the screensaver or another app closes — since the system restores the
  // bars when another window takes over.
  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) enterImmersive()
  }

  // Self-heal: if anything (e.g. the stock launcher) reset our screensaver
  // settings, put them back every time Immortal comes to the foreground.
  override fun onResume() {
    super.onResume()
    SettingsGuard.reaffirmScreensaver(this)
  }

  private fun enterImmersive() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
  }
}

@Composable
private fun LauncherScreen(
    onLaunch: (ComponentName) -> Unit,
    onOpenStore: () -> Unit,
    onStartScreensaver: () -> Unit,
    onExitHome: () -> Unit,
    onUninstall: (String) -> Unit,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  // Bumped whenever an app is installed/removed so the grid refreshes live.
  var reload by remember { mutableStateOf(0) }
  DisposableEffect(Unit) {
    val receiver =
        object : android.content.BroadcastReceiver() {
          override fun onReceive(c: android.content.Context, i: Intent) {
            reload++
          }
        }
    val filter =
        android.content.IntentFilter().apply {
          addAction(Intent.ACTION_PACKAGE_ADDED)
          addAction(Intent.ACTION_PACKAGE_REMOVED)
          addAction(Intent.ACTION_PACKAGE_REPLACED)
          addDataScheme("package")
        }
    if (android.os.Build.VERSION.SDK_INT >= 33)
        context.registerReceiver(
            receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
    else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
    onDispose { runCatching { context.unregisterReceiver(receiver) } }
  }
  val apps by
      produceState(initialValue = emptyList<AppEntry>(), reload) {
        value = withContext(Dispatchers.IO) { loadApps(context) }
      }
  var editMode by remember { mutableStateOf(false) }
  var openFolder by remember { mutableStateOf<String?>(null) }

  // User-created folder assignments (via drag-drop), persisted, overlaying the
  // curated defaults: a user override wins over Curation.folderFor(). An empty
  // string is an explicit "ungrouped" override (used when dragging out of a
  // folder), so it beats a curated default too.
  val assignments = remember { mutableStateMapOf<String, String>() }
  LaunchedEffect(Unit) { assignments.putAll(UserLayout.load(context)) }
  val appsEff =
      remember(apps, assignments.toMap()) {
        apps.map { a ->
          val pkg = a.component.packageName
          val eff = if (assignments.containsKey(pkg)) assignments[pkg]!!.ifEmpty { null } else a.folder
          a.copy(folder = eff)
        }
      }
  val ungrouped = remember(appsEff) { appsEff.filter { it.folder == null } }
  val folderNames = remember(appsEff) { appsEff.mapNotNull { it.folder }.distinct().sorted() }

  // --- drag-and-drop folder management (Manage mode) --------------------------
  val tileBounds = remember { mutableStateMapOf<String, Rect>() }
  var containerOrigin by remember { mutableStateOf(Offset.Zero) }
  var dragPkg by remember { mutableStateOf<String?>(null) }
  var dragPos by remember { mutableStateOf(Offset.Zero) }
  // Pending folder creation awaiting a name (source+target packages).
  var pendingPair by remember { mutableStateOf<Pair<String, String>?>(null) }
  // Folder currently being renamed.
  var renaming by remember { mutableStateOf<String?>(null) }

  fun persist() = UserLayout.save(context, assignments.toMap())
  fun assign(pkg: String, folder: String) {
    assignments[pkg] = folder
    persist()
  }
  fun createFolder(a: String, b: String, name: String) {
    val n = name.trim().ifEmpty { "Folder" }
    assignments[a] = n
    assignments[b] = n
    persist()
    openFolder = n
  }
  fun renameFolder(old: String, raw: String) {
    val new = raw.trim()
    if (new.isEmpty() || new == old) return
    appsEff.filter { it.folder == old }.forEach { assignments[it.component.packageName] = new }
    persist()
    openFolder = new
  }
  fun moveOut(pkg: String) {
    val folder = appsEff.firstOrNull { it.component.packageName == pkg }?.folder ?: return
    assignments[pkg] = "" // explicit ungroup
    // No single-app folders: if only one remains, pop it out too.
    val remaining = appsEff.filter { it.folder == folder && it.component.packageName != pkg }
    if (remaining.size == 1) assignments[remaining[0].component.packageName] = ""
    persist()
    if (remaining.size <= 1) openFolder = null
  }
  fun onDrop(sourcePkg: String, targetKey: String?) {
    if (targetKey == null) return
    when {
      targetKey.startsWith(FOLDER_KEY) -> assign(sourcePkg, targetKey.removePrefix(FOLDER_KEY))
      targetKey.startsWith(APP_KEY) -> {
        val targetPkg = targetKey.removePrefix(APP_KEY)
        if (targetPkg == sourcePkg) return
        pendingPair = sourcePkg to targetPkg // ask for a name first
      }
    }
  }

  // --- over-the-air self-update -----------------------------------------------
  var update by remember { mutableStateOf<UpdateInfo?>(null) }
  var updateStatus by remember { mutableStateOf<String?>(null) }
  var pendingConfirm by remember { mutableStateOf<Intent?>(null) }
  val confirmLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
  // Check on launch, then periodically while the launcher runs (it's the
  // long-lived home, so a one-shot check would go stale). The Updates tile also
  // lets the user force a check at any time.
  LaunchedEffect(Unit) {
    while (true) {
      UpdateManager.checkForUpdate(context) { update = it }
      delay(UPDATE_CHECK_INTERVAL_MS)
    }
  }
  LaunchedEffect(pendingConfirm) {
    pendingConfirm?.let {
      confirmLauncher.launch(it)
      pendingConfirm = null
    }
  }
  DisposableEffect(Unit) {
    val receiver =
        object : android.content.BroadcastReceiver() {
          override fun onReceive(c: android.content.Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
              PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                pendingConfirm =
                    if (android.os.Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                updateStatus = "Confirm to update…"
              }
              PackageInstaller.STATUS_SUCCESS -> updateStatus = "Updated"
              else -> updateStatus = "Update failed"
            }
          }
        }
    val filter = android.content.IntentFilter(UPDATE_INSTALL_ACTION)
    if (android.os.Build.VERSION.SDK_INT >= 33)
        context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
    else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
    onDispose { runCatching { context.unregisterReceiver(receiver) } }
  }

  Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A))) {
    Column(
        modifier =
            Modifier.fillMaxHeight()
                // Cap the content width and center it so the grid stays
                // comfortably sized on large displays (e.g. Portal+ 1920px)
                // instead of stretching 6 columns across the whole panel. On the
                // smaller models this is effectively full-width (unchanged).
                // (widthIn must precede fillMaxWidth so the cap wins, then align
                // centers the capped content.)
                .align(Alignment.TopCenter)
                .widthIn(max = 1264.dp)
                .fillMaxWidth()
                // Top is padded clear of the 60dp systemui status-bar window,
                // which silently eats touches even while hidden in immersive —
                // so header action buttons stay tappable.
                .padding(start = 32.dp, end = 32.dp, top = 40.dp, bottom = 24.dp)
    ) {
      HeaderBar(onScreensaver = onStartScreensaver)
      Spacer(Modifier.size(20.dp))
      Box(
          modifier =
              Modifier.fillMaxSize()
                  .onGloballyPositioned { containerOrigin = it.boundsInWindow().topLeft }
                  .pointerInput(editMode) {
                    if (!editMode) return@pointerInput
                    detectDragGesturesAfterLongPress(
                        onDragStart = { local ->
                          val win = local + containerOrigin
                          dragPkg =
                              tileBounds.entries
                                  .firstOrNull {
                                    it.key.startsWith(APP_KEY) && it.value.contains(win)
                                  }
                                  ?.key
                                  ?.removePrefix(APP_KEY)
                          dragPos = win
                        },
                        onDrag = { change, delta ->
                          change.consume()
                          dragPos += delta
                        },
                        onDragEnd = {
                          dragPkg?.let { src ->
                            val target =
                                tileBounds.entries
                                    .firstOrNull {
                                      it.key != APP_KEY + src && it.value.contains(dragPos)
                                    }
                                    ?.key
                            onDrop(src, target)
                          }
                          dragPkg = null
                        },
                        onDragCancel = { dragPkg = null },
                    )
                  },
      ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
          // Special + folder tiles persist in Manage mode (non-uninstallable);
          // only regular apps get a delete badge and become draggable.
          item { PortalHomeTile(onExitHome) }
          item { StoreTile(onOpenStore) }
          items(folderNames, key = { it }) { name ->
            FolderTile(
                name = name,
                apps = appsEff.filter { it.folder == name },
                modifier =
                    Modifier.onGloballyPositioned {
                      tileBounds[FOLDER_KEY + name] = it.boundsInWindow()
                    },
                onClick = { openFolder = name },
            )
          }
          items(ungrouped, key = { it.component.packageName }) { app ->
            val pkg = app.component.packageName
            AppTile(
                app = app,
                editMode = editMode,
                dimmed = dragPkg == pkg,
                modifier =
                    Modifier.onGloballyPositioned {
                      tileBounds[APP_KEY + pkg] = it.boundsInWindow()
                    },
                onDelete = { onUninstall(pkg) },
                onClick = { onLaunch(app.component) },
            )
          }
          // Always-present Updates tile, parked at the end of the grid. Tapping
          // installs a ready update, or forces a fresh check when up to date.
          item {
            UpdatesTile(update = update, status = updateStatus) {
              val info = update
              if (info != null) {
                UpdateManager.installUpdate(context, info) { updateStatus = it }
              } else {
                updateStatus = "Checking…"
                UpdateManager.checkForUpdate(context) {
                  update = it
                  updateStatus = if (it == null) "Up to date" else null
                }
              }
            }
          }
        }
      }
    }

    // Floating ghost of the app being dragged.
    dragPkg?.let { pkg ->
      appsEff.firstOrNull { it.component.packageName == pkg }?.let { dragged ->
        val half = with(androidx.compose.ui.platform.LocalDensity.current) { 44.dp.toPx() }
        Image(
            bitmap = dragged.icon,
            contentDescription = null,
            modifier =
                Modifier.offset {
                      IntOffset(
                          (dragPos.x - half).roundToInt(),
                          (dragPos.y - half).roundToInt(),
                      )
                    }
                    .size(88.dp)
                    .clip(RoundedCornerShape(20.dp)),
        )
      }
    }

    // Manage / Done toggle lives in the bottom-right corner.
    EditButton(
        editMode = editMode,
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 36.dp, bottom = 32.dp),
        onClick = { editMode = !editMode },
    )

    openFolder?.let { name ->
      val folderApps = appsEff.filter { it.folder == name }
      if (folderApps.isEmpty()) {
        LaunchedEffect(name) { openFolder = null }
      } else {
        FolderOverlay(
            name = name,
            apps = folderApps,
            onLaunch = {
              onLaunch(it)
              openFolder = null
            },
            onRename = { renaming = name },
            onMoveOut = { moveOut(it) },
            onDismiss = { openFolder = null },
        )
      }
    }

    // Name a new folder (created by dropping one app on another).
    pendingPair?.let { (src, tgt) ->
      NameOverlay(
          title = "Name folder",
          initial = "Folder",
          confirmLabel = "Create",
          onConfirm = {
            createFolder(src, tgt, it)
            pendingPair = null
          },
          onCancel = { pendingPair = null },
      )
    }

    // Rename an existing folder.
    renaming?.let { old ->
      NameOverlay(
          title = "Rename folder",
          initial = old,
          confirmLabel = "Rename",
          onConfirm = {
            renameFolder(old, it)
            renaming = null
          },
          onCancel = { renaming = null },
      )
    }
  }
}

private const val APP_KEY = "app:"
private const val FOLDER_KEY = "folder:"
private const val UPDATE_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000 // 6 hours

@Composable
private fun HeaderBar(onScreensaver: () -> Unit) {
  var now by remember { mutableStateOf(Date()) }
  androidx.compose.runtime.LaunchedEffect(Unit) {
    while (true) {
      now = Date()
      delay(1000)
    }
  }
  val weather by produceState(initialValue = "") {
    value = withContext(Dispatchers.IO) { fetchWeather() }
  }
  val battery = batteryState()

  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    // Screensaver entry — top-left, with the stock launcher's stacked-photo icon
    // so the affordance reads the same as the Portal users already know.
    Surface(
        color = Color(0x33FFFFFF),
        shape = androidx.compose.foundation.shape.CircleShape,
        modifier = Modifier.size(56.dp).clickable { onScreensaver() },
    ) {
      Box(contentAlignment = Alignment.Center) { StackedPhotoIcon() }
    }
    Spacer(Modifier.size(18.dp))
    Column {
      Text(
          SimpleDateFormat("h:mm", Locale.getDefault()).format(now),
          color = Color.White,
          fontSize = 56.sp,
          fontWeight = FontWeight.Light,
      )
      Text(
          SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now),
          color = Color(0xFFDADADA),
          fontSize = 18.sp,
      )
    }
    Spacer(Modifier.weight(1f))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      // Charge level is OPTIONAL — only Portal Go has a battery; mains-powered
      // Portals report no battery present, so we render nothing for them.
      if (battery.present) {
        BatteryIndicator(percent = battery.percent, charging = battery.charging)
      }
      if (weather.isNotBlank()) {
        Text(weather, color = Color.White, fontSize = 30.sp)
      }
    }
  }
}

/** White line-art photo glyph (single frame), matching the stock screensaver. */
@Composable
private fun StackedPhotoIcon() {
  Canvas(modifier = Modifier.size(30.dp)) {
    val w = size.minDimension
    val s = w * 0.075f // stroke width
    val stroke =
        Stroke(
            width = s,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
    // Photo frame outline.
    drawRoundRect(
        color = Color.White,
        topLeft = Offset(w * 0.16f, w * 0.20f),
        size = Size(w * 0.68f, w * 0.60f),
        cornerRadius = CornerRadius(w * 0.14f, w * 0.14f),
        style = stroke,
    )
    // Sun.
    drawCircle(
        color = Color.White,
        radius = w * 0.075f,
        center = Offset(w * 0.36f, w * 0.40f),
        style = Stroke(width = s),
    )
    // Mountains.
    val path =
        androidx.compose.ui.graphics.Path().apply {
          moveTo(w * 0.20f, w * 0.72f)
          lineTo(w * 0.40f, w * 0.48f)
          lineTo(w * 0.52f, w * 0.60f)
          lineTo(w * 0.62f, w * 0.50f)
          lineTo(w * 0.80f, w * 0.72f)
        }
    drawPath(path, color = Color.White, style = stroke)
  }
}

/** Bottom-right Manage / Done toggle. */
@Composable
private fun EditButton(editMode: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Surface(
      color = if (editMode) Color(0xFFE53935) else Color(0xCC2B2B2B),
      shape = androidx.compose.foundation.shape.CircleShape,
      modifier = modifier.size(60.dp).clickable { onClick() },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(if (editMode) "✓" else "✎", color = Color.White, fontSize = 28.sp)
    }
  }
}

private data class BatteryReading(val present: Boolean, val percent: Int, val charging: Boolean)

/** Reads the device battery, updating live. Returns present=false on devices
 * without a battery (Portal+, Portal TV, Portal Mini), so callers can hide it. */
@Composable
private fun batteryState(): BatteryReading {
  val context = androidx.compose.ui.platform.LocalContext.current
  var reading by remember { mutableStateOf(BatteryReading(false, 0, false)) }
  DisposableEffect(Unit) {
    fun parse(i: Intent?): BatteryReading {
      if (i == null) return BatteryReading(false, 0, false)
      val present = i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)
      val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
      val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
      val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
      val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
      val charging =
          status == BatteryManager.BATTERY_STATUS_CHARGING ||
              status == BatteryManager.BATTERY_STATUS_FULL
      return BatteryReading(present && pct >= 0, pct.coerceIn(0, 100), charging)
    }
    val receiver =
        object : android.content.BroadcastReceiver() {
          override fun onReceive(c: Context, i: Intent) {
            reading = parse(i)
          }
        }
    // Registering for the sticky ACTION_BATTERY_CHANGED returns the current value.
    val sticky =
        context.registerReceiver(receiver, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    reading = parse(sticky)
    onDispose { runCatching { context.unregisterReceiver(receiver) } }
  }
  return reading
}

/** Minimal drawn battery glyph + percent, green while charging, red when low. */
@Composable
private fun BatteryIndicator(percent: Int, charging: Boolean) {
  Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Canvas(modifier = Modifier.size(width = 36.dp, height = 18.dp)) {
      val cap = 3.dp.toPx()
      val bodyW = size.width - cap
      val stroke = 2.dp.toPx()
      drawRoundRect(
          color = Color.White,
          topLeft = Offset(stroke / 2, stroke / 2),
          size = Size(bodyW - stroke, size.height - stroke),
          cornerRadius = CornerRadius(4f, 4f),
          style = Stroke(width = stroke),
      )
      drawRoundRect(
          color = Color.White,
          topLeft = Offset(bodyW, size.height * 0.3f),
          size = Size(cap, size.height * 0.4f),
          cornerRadius = CornerRadius(2f, 2f),
      )
      val inset = stroke + 2.dp.toPx()
      val fillColor =
          when {
            charging -> Color(0xFF4CAF50)
            percent <= 15 -> Color(0xFFE53935)
            else -> Color.White
          }
      drawRoundRect(
          color = fillColor,
          topLeft = Offset(inset, inset),
          size =
              Size(
                  ((bodyW - inset * 2) * (percent / 100f)).coerceAtLeast(0f),
                  size.height - inset * 2,
              ),
          cornerRadius = CornerRadius(2f, 2f),
      )
    }
    Text("$percent%", color = Color.White, fontSize = 22.sp)
    if (charging) Text("⚡", color = Color(0xFF4CAF50), fontSize = 16.sp)
  }
}

@Composable
private fun PortalHomeTile(onClick: () -> Unit) {
  // Bridge to Meta's stock launcher — the only context allowed to open the
  // trusted-caller apps (Contacts, Camera, Photos), so this is how the user
  // reaches calling. Tapping Portal's home button returns to Immortal.
  BuiltInTile(
      label = "Calls",
      background = Color(0xFF1FA463),
      glyph = ICON_CALL,
      onClick = onClick,
  )
}

// Material-style glyph paths (24x24 viewport), rendered crisply as vectors.
private const val ICON_CALL =
    "M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z"
private const val ICON_DOWNLOAD = "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"
private const val ICON_REFRESH =
    "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"

/** A built-in launcher tile: a rounded colour tile with a centered white vector
 * glyph, styled to sit naturally beside real app icons. */
@Composable
private fun BuiltInTile(
    label: String,
    background: Color,
    glyph: String,
    onClick: () -> Unit,
) {
  val path = remember(glyph) { PathParser().parsePathString(glyph).toPath() }
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.clickable { onClick() }.padding(4.dp),
  ) {
    Surface(
        color = background,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.size(88.dp),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(46.dp)) {
          val s = size.minDimension / 24f
          scale(s, s, pivot = Offset.Zero) { drawPath(path, Color.White) }
        }
      }
    }
    Spacer(Modifier.size(8.dp))
    Text(label, color = Color.White, fontSize = 15.sp, maxLines = 1, textAlign = TextAlign.Center)
  }
}

@Composable
private fun FolderTile(
    name: String,
    apps: List<AppEntry>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = modifier.clickable { onClick() }.padding(4.dp),
  ) {
    Surface(
        color = Color(0xFF3A3A3A),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.size(88.dp),
    ) {
      Column(
          modifier = Modifier.padding(13.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        apps.chunked(2).take(2).forEach { row ->
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.take(2).forEach { app ->
              Image(
                  bitmap = app.icon,
                  contentDescription = null,
                  modifier = Modifier.size(25.dp).clip(RoundedCornerShape(7.dp)),
              )
            }
          }
        }
      }
    }
    Spacer(Modifier.size(8.dp))
    Text(
        name,
        color = Color.White,
        fontSize = 15.sp,
        maxLines = 1,
        textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun FolderOverlay(
    name: String,
    apps: List<AppEntry>,
    onLaunch: (ComponentName) -> Unit,
    onRename: () -> Unit,
    onMoveOut: (String) -> Unit,
    onDismiss: () -> Unit,
) {
  // Rendered inside the launcher's own (immersive) window — NOT a Dialog, which
  // would spawn a separate window and momentarily reveal the system bars.
  val noRipple = remember { MutableInteractionSource() }
  val tileBounds = remember { mutableStateMapOf<String, Rect>() }
  var panel by remember { mutableStateOf(Rect.Zero) }
  var dragPkg by remember { mutableStateOf<String?>(null) }
  var dragPos by remember { mutableStateOf(Offset.Zero) }

  Box(
      contentAlignment = Alignment.Center,
      modifier =
          Modifier.fillMaxSize()
              .background(Color(0xCC000000))
              .clickable(interactionSource = noRipple, indication = null) { onDismiss() }
              // Drag an app out of the panel to remove it from the folder.
              .pointerInput(apps) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                      dragPkg =
                          tileBounds.entries.firstOrNull { it.value.contains(pos) }?.key
                      dragPos = pos
                    },
                    onDrag = { change, delta ->
                      change.consume()
                      dragPos += delta
                    },
                    onDragEnd = {
                      val pkg = dragPkg
                      if (pkg != null && !panel.contains(dragPos)) onMoveOut(pkg)
                      dragPkg = null
                    },
                    onDragCancel = { dragPkg = null },
                )
              },
  ) {
    Surface(
        color = Color(0xFF1C1C1E),
        shape = RoundedCornerShape(28.dp),
        modifier =
            Modifier.width(420.dp)
                .onGloballyPositioned { panel = it.boundsInWindow() }
                .clickable(interactionSource = noRipple, indication = null) {},
    ) {
      Column(modifier = Modifier.padding(28.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(name, color = Color.White, fontSize = 22.sp, modifier = Modifier.weight(1f))
          // Rename.
          Surface(
              color = Color(0x33FFFFFF),
              shape = androidx.compose.foundation.shape.CircleShape,
              modifier = Modifier.size(40.dp).clickable { onRename() },
          ) {
            Box(contentAlignment = Alignment.Center) {
              Text("✎", color = Color.White, fontSize = 18.sp)
            }
          }
        }
        Spacer(Modifier.size(20.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
          items(apps, key = { it.component.packageName }) { app ->
            val pkg = app.component.packageName
            AppTile(
                app = app,
                editMode = false,
                dimmed = dragPkg == pkg,
                modifier =
                    Modifier.onGloballyPositioned { tileBounds[pkg] = it.boundsInWindow() },
                onClick = { onLaunch(app.component) },
            )
          }
        }
        Spacer(Modifier.size(6.dp))
        Text(
            "Drag an app out to remove it",
            color = Color(0xFF8A8A8A),
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
      }
    }
  }

  // Floating ghost of the app being dragged out.
  dragPkg?.let { pkg ->
    apps.firstOrNull { it.component.packageName == pkg }?.let { dragged ->
      val half = with(androidx.compose.ui.platform.LocalDensity.current) { 44.dp.toPx() }
      Image(
          bitmap = dragged.icon,
          contentDescription = null,
          modifier =
              Modifier.offset {
                    IntOffset((dragPos.x - half).roundToInt(), (dragPos.y - half).roundToInt())
                  }
                  .size(88.dp)
                  .clip(RoundedCornerShape(20.dp)),
      )
    }
  }
}

/** Centered overlay with a text field for naming/renaming a folder. */
@Composable
private fun NameOverlay(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
  val noRipple = remember { MutableInteractionSource() }
  // Pre-select the whole name so the first keystroke replaces it (iOS-style).
  var field by remember {
    mutableStateOf(
        androidx.compose.ui.text.input.TextFieldValue(
            initial,
            selection = androidx.compose.ui.text.TextRange(0, initial.length),
        ))
  }
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
  Box(
      contentAlignment = Alignment.TopCenter,
      modifier =
          Modifier.fillMaxSize()
              .background(Color(0xCC000000))
              .clickable(interactionSource = noRipple, indication = null) { onCancel() },
  ) {
    Surface(
        color = Color(0xFF1C1C1E),
        shape = RoundedCornerShape(24.dp),
        modifier =
            Modifier.padding(top = 70.dp)
                .width(440.dp)
                .clickable(interactionSource = noRipple, indication = null) {},
    ) {
      Column(modifier = Modifier.padding(24.dp)) {
        Text(title, color = Color.White, fontSize = 20.sp)
        Spacer(Modifier.size(16.dp))
        BasicTextField(
            value = field,
            onValueChange = { field = it },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
            cursorBrush = SolidColor(Color.White),
            modifier =
                Modifier.fillMaxWidth()
                    .focusRequester(focus)
                    .background(Color(0xFF2B2B2B), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 14.dp),
        )
        Spacer(Modifier.size(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          Text(
              "Cancel",
              color = Color(0xFF8AB4F8),
              fontSize = 18.sp,
              modifier = Modifier.clickable { onCancel() }.padding(12.dp),
          )
          Spacer(Modifier.size(8.dp))
          Text(
              confirmLabel,
              color = Color(0xFF8AB4F8),
              fontSize = 18.sp,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.clickable { onConfirm(field.text) }.padding(12.dp),
          )
        }
      }
    }
  }
}

/** Always-present Updates tile. Neutral + refresh glyph when up to date; blue +
 * download glyph (with a badge) when an update is ready. Shows transient status
 * text during a check or install. */
@Composable
private fun UpdatesTile(update: UpdateInfo?, status: String?, onClick: () -> Unit) {
  val available = update != null
  val label = status ?: if (available) "Update ready" else "Up to date"
  val background = if (available) Color(0xFF2D6CDF) else Color(0xFF2B2B2B)
  val glyph = if (available) ICON_DOWNLOAD else ICON_REFRESH
  val path = remember(glyph) { PathParser().parsePathString(glyph).toPath() }
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.clickable { onClick() }.padding(4.dp),
  ) {
    Box {
      Surface(
          color = background,
          shape = RoundedCornerShape(20.dp),
          modifier = Modifier.size(88.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Canvas(Modifier.size(44.dp)) {
            val s = size.minDimension / 24f
            scale(s, s, pivot = Offset.Zero) { drawPath(path, Color.White) }
          }
        }
      }
      if (available) {
        Surface(
            color = Color(0xFFE53935),
            shape = androidx.compose.foundation.shape.CircleShape,
            modifier = Modifier.size(18.dp).align(Alignment.TopEnd),
        ) {}
      }
    }
    Spacer(Modifier.size(8.dp))
    Text(
        label,
        color = Color.White,
        fontSize = 15.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun StoreTile(onClick: () -> Unit) {
  BuiltInTile(
      label = "App Store",
      background = Color(0xFF2D6CDF),
      glyph = ICON_DOWNLOAD,
      onClick = onClick,
  )
}

@Composable
private fun AppTile(
    app: AppEntry,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    onDelete: () -> Unit = {},
    onClick: () -> Unit,
) {
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      // In Manage mode the body tap is inert (drag to fold, ✕ to remove); the
      // icon launches normally otherwise.
      modifier = modifier.clickable(enabled = !editMode) { onClick() }.padding(4.dp),
  ) {
    Box {
      Image(
          bitmap = app.icon,
          contentDescription = app.label,
          modifier =
              Modifier.size(88.dp)
                  .clip(RoundedCornerShape(20.dp))
                  .alpha(if (dimmed) 0.3f else 1f),
      )
      if (editMode) {
        Surface(
            color = Color(0xFFE53935),
            shape = androidx.compose.foundation.shape.CircleShape,
            modifier = Modifier.size(30.dp).align(Alignment.TopEnd).clickable { onDelete() },
        ) {
          Box(contentAlignment = Alignment.Center) {
            Text("✕", color = Color.White, fontSize = 18.sp)
          }
        }
      }
    }
    Spacer(Modifier.size(8.dp))
    Text(
        app.label,
        color = Color.White,
        fontSize = 15.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
  }
}

// --- data ---------------------------------------------------------------------

private fun loadApps(context: Context): List<AppEntry> {
  val pm = context.packageManager
  val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  return pm.queryIntentActivities(intent, 0)
      .filter {
        val pkg = it.activityInfo.packageName
        pkg != context.packageName && pkg !in Curation.hiddenPackages
      }
      .mapNotNull { ri ->
        runCatching {
              val ai = ri.activityInfo
              val rawLabel = ri.loadLabel(pm).toString()
              val bmp: Bitmap = ri.loadIcon(pm).toBitmap(144, 144)
              AppEntry(
                  label = Curation.displayLabel(ai.packageName, rawLabel),
                  component = ComponentName(ai.packageName, ai.name),
                  icon = bmp.asImageBitmap(),
                  folder = Curation.folderFor(ai.packageName),
              ) to rawLabel
            }
            .getOrNull()
      }
      // Label-based hide (catches gated apps that share a package).
      .filterNot { (_, raw) -> raw in Curation.hiddenLabels }
      .map { it.first }
      // One tile per package — some apps (e.g. Portal Settings) expose a second
      // debug/launcher activity that would otherwise show as a duplicate.
      .distinctBy { it.component.packageName }
      .sortedBy { it.label.lowercase(Locale.getDefault()) }
}

private fun fetchWeather(): String =
    runCatching {
          fun get(spec: String): String {
            val c = URL(spec).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "PortalLauncher/1.0")
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
          }
          val geo = JSONObject(get("https://ipapi.co/json/"))
          val lat = geo.getDouble("latitude")
          val lon = geo.getDouble("longitude")
          val w =
              JSONObject(
                  get(
                      "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                          "&current=temperature_2m,weather_code&temperature_unit=fahrenheit"))
          val cur = w.getJSONObject("current")
          val temp = cur.getDouble("temperature_2m").toInt()
          val code = cur.getInt("weather_code")
          val emoji =
              when (code) {
                0 -> "☀️"
                1, 2 -> "🌤️"
                3 -> "☁️"
                in 45..48 -> "🌫️"
                in 51..67 -> "🌦️"
                in 71..77 -> "🌨️"
                in 80..82 -> "🌧️"
                in 95..99 -> "⛈️"
                else -> "🌡️"
              }
          "$emoji $temp°"
        }
        .getOrDefault("")
