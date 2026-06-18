/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Apps to (re)launch after a reboot so their background work resumes without a human.
 * The motivating case is the Music Assistant / Sendspin player: it has no boot receiver
 * of its own, so after a reboot its player stays dead until someone opens the app — but
 * we own the launcher, which DOES run on boot, so we start it for them.
 *
 * The list is one package per line in the shell-writable external file [FILE]
 * (provisioning writes it; blank lines and `#` comments ignored), so it's configurable
 * per device without an app release.
 */
object BootLaunch {
  private const val FILE = "boot_apps.txt"

  fun packages(context: Context): List<String> =
      runCatching {
            File(context.getExternalFilesDir(null), FILE)
                .takeIf { it.exists() }
                ?.readLines()
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() && !it.startsWith("#") }
                ?: emptyList()
          }
          .getOrDefault(emptyList())

  /**
   * Launch each configured, installed app. Starting an activity from the background
   * needs an exemption on Android 10 (SYSTEM_ALERT_WINDOW, which provisioning grants);
   * Android 9 has no background-activity-start restriction, so it's unrestricted there.
   */
  fun launchAll(context: Context) {
    val pm = context.packageManager
    packages(context).forEach { pkg ->
      runCatching {
        pm.getLaunchIntentForPackage(pkg)?.let { intent ->
          intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          context.startActivity(intent)
        }
      }
    }
  }
}
