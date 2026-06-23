/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

/** Android PackageInstaller session Adapter shared by store installs and self-updates. */
object PackageInstallSessions {
  fun commit(
      context: Context,
      apk: File,
      resultAction: String,
      configureResultIntent: Intent.() -> Unit = {},
  ) {
    val pi = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    val sessionId = pi.createSession(params)
    pi.openSession(sessionId).use { session ->
      session.openWrite("base.apk", 0, apk.length()).use { out ->
        apk.inputStream().use { it.copyTo(out) }
        session.fsync(out)
      }
      val flags =
          if (Build.VERSION.SDK_INT >= 31)
              PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
          else PendingIntent.FLAG_UPDATE_CURRENT
      val resultIntent =
          Intent(resultAction)
              .setPackage(context.packageName)
              .apply(configureResultIntent)
      val pending = PendingIntent.getBroadcast(context, sessionId, resultIntent, flags)
      session.commit(pending.intentSender)
    }
  }
}
