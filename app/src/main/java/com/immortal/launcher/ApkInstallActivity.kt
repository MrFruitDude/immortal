/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.immortal.launcher.ui.theme.SampleAppTheme
import java.io.File
import kotlin.concurrent.thread

/**
 * Handles "open this APK" (ACTION_VIEW of an .apk) so a user can install any APK
 * they downloaded — from Chrome, a file manager, anywhere — through Immortal.
 *
 * On the Gen-1 Portal the stock installer dialog is broken, so this becomes the
 * working installer for the whole device: it routes the APK through the silent
 * install daemon. On newer models it falls back to the system installer.
 */
class ApkInstallActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val src: Uri? = intent?.data
    if (src == null) {
      finish()
      return
    }

    // Copy the APK somewhere we control (the daemon needs a real file path, and
    // we parse it for the app's name/icon).
    val apk = File(cacheDir, "incoming.apk")
    val copied =
        runCatching {
              contentResolver.openInputStream(src).use { input ->
                requireNotNull(input)
                apk.outputStream().use { input.copyTo(it) }
              }
            }
            .isSuccess
    if (!copied) {
      finish()
      return
    }

    val info = packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.applicationInfo
    info?.sourceDir = apk.absolutePath
    info?.publicSourceDir = apk.absolutePath
    val label = info?.loadLabel(packageManager)?.toString() ?: "this app"
    val icon: ImageBitmap? =
        runCatching { info?.loadIcon(packageManager)?.toBitmap(144, 144)?.asImageBitmap() }
            .getOrNull()
    val pkg = info?.packageName ?: "incoming"

    setContent {
      SampleAppTheme(darkTheme = true) {
        InstallPrompt(
            label = label,
            icon = icon,
            onCancel = { finish() },
            onInstall = { setStatus -> doInstall(apk, pkg, src, setStatus) },
        )
      }
    }
  }

  private fun doInstall(apk: File, pkg: String, originalUri: Uri, setStatus: (String) -> Unit) {
    when {
      InstallDaemon.installPaused(this) ->
          setStatus("Paused — connect to your computer and run the Immortal installer again")
      InstallDaemon.isAvailable(this) -> {
        setStatus("Installing…")
        thread {
          val ok = InstallDaemon.install(this, apk, pkg)
          runOnUiThread { setStatus(if (ok) "Installed ✓" else "Install failed") }
        }
      }
      else -> {
        // Newer models — and Gen-1 once the overlay fix has made the stock dialog
        // visible — hand off to the working system installer.
        val ok =
            runCatching {
                  startActivity(
                      Intent(Intent.ACTION_VIEW)
                          .setDataAndType(originalUri, "application/vnd.android.package-archive")
                          .setPackage("com.android.packageinstaller")
                          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                  finish()
                }
                .isSuccess
        if (!ok) setStatus("Couldn't install on this device")
      }
    }
  }
}

@Composable
private fun InstallPrompt(
    label: String,
    icon: ImageBitmap?,
    onCancel: () -> Unit,
    onInstall: ((String) -> Unit) -> Unit,
) {
  val noRipple = remember { MutableInteractionSource() }
  var status by remember { mutableStateOf<String?>(null) }
  Box(
      contentAlignment = Alignment.Center,
      modifier =
          Modifier.fillMaxSize()
              .background(Color(0xCC000000))
              .clickable(interactionSource = noRipple, indication = null) { onCancel() },
  ) {
    Surface(
        color = Color(0xFF1C1C1E),
        shape = RoundedCornerShape(28.dp),
        modifier =
            Modifier.padding(24.dp)
                .width(420.dp)
                .clickable(interactionSource = noRipple, indication = null) {},
    ) {
      Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.padding(28.dp).fillMaxWidth(),
      ) {
        if (icon != null) {
          Image(
              bitmap = icon,
              contentDescription = null,
              modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)),
          )
          Spacer(Modifier.size(14.dp))
        }
        Text("Install $label?", color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center)
        Text(
            "via Immortal",
            color = Color(0xFF8A8A8A),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        status?.let {
          Spacer(Modifier.size(12.dp))
          Text(it, color = Color(0xFF8AB4F8), fontSize = 15.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.size(22.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          Text(
              if (status?.contains("✓") == true || status?.startsWith("Paused") == true)
                  "Close"
              else "Cancel",
              color = Color(0xFF8AB4F8),
              fontSize = 18.sp,
              modifier = Modifier.clickable { onCancel() }.padding(12.dp),
          )
          if (status == null) {
            Spacer(Modifier.size(8.dp))
            Text(
                "Install",
                color = Color(0xFF8AB4F8),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onInstall { s -> status = s } }.padding(12.dp),
            )
          }
        }
      }
    }
  }
}
