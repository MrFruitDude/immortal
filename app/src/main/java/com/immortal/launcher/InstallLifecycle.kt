/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * The install lifecycle decision model shared by the App Store, Fleet Agent, APK browser,
 * and self-updater.
 *
 * The Android-specific Adapters still live in [InstallDaemon], [HeadlessInstaller], and
 * [PackageInstallSessions]. This Module owns the policy: whether an install should use the
 * silent daemon, the system dialog, or be reported as paused on an unfixed Gen-1 Portal.
 */
object InstallLifecycle {
  const val MODE_SILENT = "silent"
  const val MODE_DIALOG = "dialog"
  const val MODE_PAUSED = "paused"

  data class Plan(
      val mode: String,
      val daemonAvailable: Boolean,
      val legacy: Boolean,
      val dialogFixed: Boolean,
      val paused: Boolean,
  )

  fun plan(legacy: Boolean, daemonAvailable: Boolean, dialogFixed: Boolean): Plan {
    val paused = isPaused(legacy, daemonAvailable, dialogFixed)
    return Plan(
        mode = modeFor(daemonAvailable, paused),
        daemonAvailable = daemonAvailable,
        legacy = legacy,
        dialogFixed = dialogFixed,
        paused = paused,
    )
  }

  fun modeFor(daemonAvailable: Boolean, paused: Boolean): String =
      when {
        daemonAvailable -> MODE_SILENT
        paused -> MODE_PAUSED
        else -> MODE_DIALOG
      }

  fun isPaused(legacy: Boolean, daemonAvailable: Boolean, dialogFixed: Boolean): Boolean =
      legacy && !daemonAvailable && !dialogFixed

  /** Map a store status string to a terminal result, or null for progress updates. */
  fun terminalResult(msg: String): Boolean? =
      when {
        msg.contains("Installed ✓") -> true
        msg.startsWith("Install failed") || msg.startsWith("Error") || msg.startsWith("Paused") -> false
        else -> null
      }
}
