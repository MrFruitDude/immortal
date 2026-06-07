/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Client for the optional shell-privileged install daemon set up by the
 * provisioning kit (`installd.sh`, started via ADB as the shell user).
 *
 * When the daemon is running, Immortal can install/update apps **silently** by
 * dropping the APK into a watched queue — no system installer dialog at all.
 * This is what makes the App Store and self-update work on the Gen-1 Portal+,
 * whose built-in installer UI is broken, and it's a one-tap upgrade on every
 * other model too. When the daemon isn't running (e.g. after a reboot, since
 * non-root helpers don't survive one), callers fall back to the normal
 * PackageInstaller flow.
 *
 * The daemon renames `<name>.apk` → `<name>.apk.done` / `.failed` to report
 * results; we write APKs atomically (`.part` → rename) so it never sees a
 * partial file.
 */
object InstallDaemon {

  private fun queueDir(context: Context) = File(context.getExternalFilesDir(null), "installq")

  /** Heartbeat freshness window (extracted as a pure function for testing). */
  internal fun heartbeatFresh(tsSeconds: Long, nowSeconds: Long): Boolean =
      (nowSeconds - tsSeconds) in 0..20

  /** True if the daemon is alive (it writes a unix-time heartbeat every ~2s). */
  fun isAvailable(context: Context): Boolean {
    val ts =
        runCatching { File(queueDir(context), ".heartbeat").readText().trim().toLong() }
            .getOrDefault(0L)
    return heartbeatFresh(ts, System.currentTimeMillis() / 1000)
  }

  /**
   * Whether this device's built-in installer dialog is broken and it therefore
   * RELIES on the daemon to install anything. True on the Gen-1 Portal/Portal+
   * (Android 9 / API 28); the Android-10 models (Go, Mini, gen-2) have a working
   * system installer and don't need the daemon. Used to tell the two apart so a
   * paused daemon on a Gen-1 reads as "re-run setup", not a bug.
   */
  /** Pure SDK check (extracted for testing): API < 29 == broken-installer Gen-1. */
  internal fun isLegacy(sdkInt: Int): Boolean = sdkInt < 29

  fun legacyInstaller(): Boolean = isLegacy(Build.VERSION.SDK_INT)

  /**
   * Whether the provisioning kit has fixed the Gen-1 installer dialog by disabling
   * Meta's white-on-white RRO (it sets the global flag `immortal_overlay_fix=1`).
   * When true, the *stock* PackageInstaller dialog is usable again, so a Gen-1 with
   * the daemon down can still install via the system dialog instead of being paused.
   * Unlike the daemon, the overlay fix persists across reboots, so this can be true
   * even when the daemon isn't running. Reading the marker avoids needing the hidden
   * IOverlayManager API to query overlay state directly.
   */
  fun installerDialogFixed(context: Context): Boolean =
      runCatching { Settings.Global.getInt(context.contentResolver, OVERLAY_FIX_FLAG, 0) }
          .getOrDefault(0) == 1

  internal const val OVERLAY_FIX_FLAG = "immortal_overlay_fix"

  /** Pure paused decision (extracted for testing): paused only when there is no
   *  silent path (daemon) AND no working dialog (overlay fix) on a Gen-1. */
  internal fun isPaused(legacy: Boolean, daemonAvailable: Boolean, dialogFixed: Boolean): Boolean =
      legacy && !daemonAvailable && !dialogFixed

  /**
   * Gen-1 with the daemon down AND no working dialog: on-device installs are truly
   * paused until the kit is re-run. If the overlay fix is in place the stock dialog
   * works, so callers fall through to PackageInstaller rather than refusing.
   */
  fun installPaused(context: Context): Boolean =
      isPaused(legacyInstaller(), isAvailable(context), installerDialogFixed(context))

  /**
   * Android-10 model with the daemon down. Installs aren't *blocked* here — the
   * system installer dialog still works for most apps — but they're no longer
   * SILENT, and that dialog parse-fails ("There was a problem parsing the
   * package") on some apps (Play-store split APKs, a few others) on Portal's
   * Android 10. The daemon avoids both problems, but it doesn't survive a reboot.
   * We surface this so the drop to the system dialog isn't a silent mystery, and
   * point the user at the one-tap fix (reconnect + re-run the installer).
   */
  fun silentInstallOffline(context: Context): Boolean =
      !legacyInstaller() && !isAvailable(context)

  /**
   * Queue [apk] for the daemon and block (on a background thread) until it
   * reports a result or [timeoutMs] elapses. Returns true on success.
   */
  fun install(context: Context, apk: File, name: String, timeoutMs: Long = 180_000): Boolean =
      install(queueDir(context), apk, name, timeoutMs)

  /**
   * The queue protocol against an explicit directory — extracted so the
   * atomic-rename + poll-for-result behaviour is testable without a Context.
   */
  internal fun install(queueDir: File, apk: File, name: String, timeoutMs: Long = 180_000): Boolean {
    val d = queueDir.apply { mkdirs() }
    val target = File(d, "$name.apk")
    val done = File(d, "$name.apk.done")
    val failed = File(d, "$name.apk.failed")
    val log = File(d, "$name.apk.log")
    listOf(target, done, failed, log).forEach { runCatching { it.delete() } }

    // Write to a temp name, then atomically rename in — the daemon only ever
    // sees a complete APK.
    val part = File(d, "$name.part")
    runCatching { apk.copyTo(part, overwrite = true) }.getOrElse {
      part.delete()
      return false
    }
    if (!part.renameTo(target)) {
      part.delete()
      return false
    }

    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      if (done.exists()) {
        listOf(done, log).forEach { runCatching { it.delete() } }
        return true
      }
      if (failed.exists()) {
        listOf(failed, log).forEach { runCatching { it.delete() } }
        return false
      }
      Thread.sleep(800)
    }
    return false
  }
}
