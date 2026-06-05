/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.SampleAppTheme

/**
 * The Portal App Store. Loads the JSON catalog, lists apps by category, and
 * installs them on-device via the PackageInstaller path. Custom Portal apps and
 * curated F-Droid apps appear side by side; the catalog is just a hosted file.
 */
class StoreActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme(darkTheme = true) { StoreScreen() } }
  }
}

@Composable
private fun StoreScreen() {
  val context = LocalContext.current
  var apps by remember { mutableStateOf<List<CatalogApp>>(emptyList()) }
  val status = remember { mutableStateMapOf<String, String>() }
  var pendingConfirm by remember { mutableStateOf<Intent?>(null) }

  val confirmLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

  LaunchedEffect(Unit) { StoreCatalog.loadCatalog(context) { apps = it } }

  LaunchedEffect(pendingConfirm) {
    pendingConfirm?.let {
      confirmLauncher.launch(it)
      pendingConfirm = null
    }
  }

  DisposableEffect(Unit) {
    val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(c: Context, intent: Intent) {
            val pkg = intent.getStringExtra(STORE_EXTRA_PKG) ?: return
            when (val s = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
              PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                pendingConfirm =
                    if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                status[pkg] = "Confirm to install…"
              }
              PackageInstaller.STATUS_SUCCESS -> status[pkg] = "Installed ✓"
              else -> status[pkg] = "Failed (status=$s)"
            }
          }
        }
    val filter = IntentFilter(STORE_INSTALL_ACTION)
    if (Build.VERSION.SDK_INT >= 33)
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
    onDispose { runCatching { context.unregisterReceiver(receiver) } }
  }

  val categories = remember(apps) { apps.groupBy { it.category } }

  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 72.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      item {
        Text("App Store", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text(
            "${apps.size} apps · tap Install to download and add to your Portal",
            style = MaterialTheme.typography.bodyMedium,
        )
      }
      categories.forEach { (cat, list) ->
        item {
          Text(
              cat,
              fontSize = 20.sp,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.padding(top = 12.dp),
          )
        }
        items(list) { app ->
          AppRow(
              app = app,
              installed = StoreCatalog.isInstalled(context, app.packageName),
              status = status[app.packageName],
              onInstall = { StoreCatalog.install(context, app) { p, m -> status[p] = m } },
          )
        }
      }
    }
  }
}

@Composable
private fun AppRow(
    app: CatalogApp,
    installed: Boolean,
    status: String?,
    onInstall: () -> Unit,
) {
  Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
        Text(app.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(app.description, style = MaterialTheme.typography.bodySmall)
        status?.let {
          Text(
              it,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(top = 4.dp),
          )
        }
      }
      if (installed && status == null) {
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = MaterialTheme.shapes.small,
        ) {
          Text(
              "Installed",
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              color = MaterialTheme.colorScheme.onTertiaryContainer,
          )
        }
      } else {
        Button(
            onClick = onInstall,
            modifier = Modifier.heightIn(min = 52.dp).width(120.dp),
        ) {
          Text("Install")
        }
      }
    }
  }
}
