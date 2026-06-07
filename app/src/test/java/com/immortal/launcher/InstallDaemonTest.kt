/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.File
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests for the silent-install daemon client (no device needed). */
class InstallDaemonTest {

  private val tmp = File(System.getProperty("java.io.tmpdir"), "immortal-test-${System.nanoTime()}")

  @After
  fun cleanup() {
    tmp.deleteRecursively()
  }

  // --- Gen-1 detection --------------------------------------------------------

  @Test
  fun isLegacy_isTrueBelowApi29() {
    assertTrue(InstallDaemon.isLegacy(24))
    assertTrue(InstallDaemon.isLegacy(28)) // Gen-1 Portal+ (Android 9)
  }

  @Test
  fun isLegacy_isFalseAtOrAboveApi29() {
    assertFalse(InstallDaemon.isLegacy(29)) // Portal Go (Android 10)
    assertFalse(InstallDaemon.isLegacy(34))
  }

  // --- paused decision (daemon vs. overlay-fixed dialog) ----------------------

  @Test
  fun isPaused_gen1_noDaemon_noDialogFix_isPaused() {
    // Gen-1, daemon down, overlay not fixed: nothing can install on-device.
    assertTrue(InstallDaemon.isPaused(legacy = true, daemonAvailable = false, dialogFixed = false))
  }

  @Test
  fun isPaused_gen1_overlayFixed_fallsThroughToDialog() {
    // Gen-1, daemon down, but the overlay fix made the stock dialog usable —
    // not paused; callers use PackageInstaller. This survives reboots.
    assertFalse(InstallDaemon.isPaused(legacy = true, daemonAvailable = false, dialogFixed = true))
  }

  @Test
  fun isPaused_gen1_daemonUp_neverPaused() {
    assertFalse(InstallDaemon.isPaused(legacy = true, daemonAvailable = true, dialogFixed = false))
  }

  @Test
  fun isPaused_newerModels_neverPaused() {
    assertFalse(InstallDaemon.isPaused(legacy = false, daemonAvailable = false, dialogFixed = false))
  }

  // --- heartbeat freshness ----------------------------------------------------

  @Test
  fun heartbeatFresh_withinTwentySeconds() {
    assertTrue(InstallDaemon.heartbeatFresh(tsSeconds = 1000, nowSeconds = 1000))
    assertTrue(InstallDaemon.heartbeatFresh(tsSeconds = 1000, nowSeconds = 1020))
  }

  @Test
  fun heartbeatFresh_staleOrClockSkew() {
    assertFalse(InstallDaemon.heartbeatFresh(tsSeconds = 1000, nowSeconds = 1021)) // too old
    assertFalse(InstallDaemon.heartbeatFresh(tsSeconds = 1000, nowSeconds = 999)) // skew
  }

  // --- queue protocol (atomic write + poll for result) ------------------------

  private fun sampleApk(): File =
      File(tmp, "src.apk").apply {
        parentFile?.mkdirs()
        writeText("PK fake apk bytes")
      }

  @Test
  fun install_succeeds_deliversApk_andCleansUpMarkers() {
    val q = File(tmp, "q")
    val apk = sampleApk()
    val delivered = arrayOfNulls<String>(1)
    // Stand in for installd.sh: wait for the queued .apk, capture it, report done.
    val daemon =
        thread {
          val target = File(q, "app.apk")
          while (!target.exists()) Thread.sleep(20)
          delivered[0] = target.readText()
          File(q, "app.apk.done").writeText("Success")
        }
    val ok = InstallDaemon.install(q, apk, "app", timeoutMs = 5000)
    daemon.join()

    assertTrue(ok)
    assertEquals("the daemon must see the complete source APK", apk.readText(), delivered[0])
    assertFalse("result marker cleaned up", File(q, "app.apk.done").exists())
    assertFalse("log cleaned up", File(q, "app.apk.log").exists())
  }

  @Test
  fun install_returnsFalse_whenDaemonReportsFailure() {
    val q = File(tmp, "q")
    val apk = sampleApk()
    val daemon =
        thread {
          val target = File(q, "app.apk")
          while (!target.exists()) Thread.sleep(20)
          File(q, "app.apk.failed").writeText("Failure")
        }
    val ok = InstallDaemon.install(q, apk, "app", timeoutMs = 5000)
    daemon.join()

    assertFalse(ok)
    assertFalse(File(q, "app.apk.failed").exists())
  }

  @Test
  fun install_returnsFalse_onTimeout_whenNoDaemon() {
    val q = File(tmp, "q")
    val ok = InstallDaemon.install(q, sampleApk(), "app", timeoutMs = 100)
    assertFalse(ok)
  }
}
