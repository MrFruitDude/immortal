/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.immortal.launcher.ui.theme.SampleAppTheme
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class LocalApk(
    val file: File,
    val label: String,
    val version: String,
    val pkg: String,
    val icon: ImageBitmap?,
)

/**
 * Lists APK files the user has downloaded (Downloads, etc.) and installs them via
 * the silent daemon — so on a Gen-1 Portal you can sideload an APK from Chrome,
 * Aurora's downloads, or anywhere, even though the stock installer is broken.
 */
class ApkBrowserActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { ApkBrowserScreen() } }
  }
}

@Composable
private fun ApkBrowserScreen() {
  val context = LocalContext.current
  var granted by remember {
    mutableStateOf(
        context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
  }
  val permLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
      }
  LaunchedEffect(Unit) {
    if (!granted) permLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
  }

  val apks by
      androidx.compose.runtime.produceState(initialValue = emptyList<LocalApk>(), granted) {
        value = if (granted) withContext(Dispatchers.IO) { scanApks(context) } else emptyList()
      }
  val status = remember { mutableStateMapOf<String, String>() }

  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 72.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      item {
        Text("Install an APK", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text(
            if (!granted) "Allow file access to see your downloaded APKs."
            else "${apks.size} APK file(s) found in your downloads · tap Install.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (InstallDaemon.installPaused(context)) PausedNote()
      }
      items(apks, key = { it.file.absolutePath }) { a ->
        ApkRow(a, status[a.file.absolutePath]) {
          val key = a.file.absolutePath
          when {
            InstallDaemon.installPaused(context) ->
                status[key] = "Paused — connect to your computer to add apps"
            else -> {
              // Install via the system PackageInstaller — it surfaces the confirm dialog
              // (readable once the Gen-1 overlay fix is applied; InstallConfirmService
              // auto-taps it where enabled).
              status[key] = "Installing…"
              thread {
                val ok = HeadlessInstaller.install(context, a.file, a.pkg)
                status[key] = if (ok) "Installed ✓" else "Install failed"
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun PausedNote() {
  Card(
      modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
      colors = CardDefaults.cardColors(containerColor = Color(0x33FFB300)),
  ) {
    Text(
        "Installing apps needs the installer-dialog fix that Immortal's setup applies on " +
            "first-gen Portals. Connect to your computer and run the Immortal installer. " +
            "Everything else keeps working.",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFFFFD180),
        modifier = Modifier.padding(16.dp),
    )
  }
}

@Composable
private fun ApkRow(a: LocalApk, status: String?, onInstall: () -> Unit) {
  Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      a.icon?.let {
        Image(bitmap = it, contentDescription = null, modifier = Modifier.size(44.dp).clip(
            androidx.compose.foundation.shape.RoundedCornerShape(10.dp)))
      }
      Column(modifier = Modifier.weight(1f).padding(start = 16.dp, end = 16.dp)) {
        Text(a.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "v${a.version} · ${a.file.name}",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFAAAAAA),
        )
        status?.let {
          Text(
              it,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(top = 4.dp),
          )
        }
      }
      Button(onClick = onInstall, modifier = Modifier.size(width = 120.dp, height = 52.dp)) {
        Text("Install")
      }
    }
  }
}

private fun scanApks(context: android.content.Context): List<LocalApk> {
  val dirs =
      listOfNotNull(
          Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
          @Suppress("DEPRECATION") Environment.getExternalStorageDirectory(),
          context.getExternalFilesDir(null),
      )
  val pm = context.packageManager
  return dirs
      .flatMap { d -> runCatching { d.listFiles { f -> f.extension.equals("apk", true) }?.toList() }.getOrNull() ?: emptyList() }
      .distinctBy { it.absolutePath }
      .mapNotNull { f ->
        runCatching {
              val info = pm.getPackageArchiveInfo(f.absolutePath, 0)
              val ai = info?.applicationInfo
              ai?.sourceDir = f.absolutePath
              ai?.publicSourceDir = f.absolutePath
              LocalApk(
                  file = f,
                  label = ai?.loadLabel(pm)?.toString() ?: f.name,
                  version = info?.versionName ?: "?",
                  pkg = info?.packageName ?: f.name,
                  icon = ai?.loadIcon(pm)?.toBitmap(120, 120)?.asImageBitmap(),
              )
            }
            .getOrNull()
      }
      .sortedByDescending { it.file.lastModified() }
}
