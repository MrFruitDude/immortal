/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallLifecycleTest {

  @Test
  fun plan_prefersSilentWhenDaemonIsAvailable() {
    val plan = InstallLifecycle.plan(legacy = true, daemonAvailable = true, dialogFixed = false)
    assertEquals(InstallLifecycle.MODE_SILENT, plan.mode)
    assertTrue(plan.daemonAvailable)
    assertFalse(plan.paused)
  }

  @Test
  fun plan_pausesOnlyUnfixedLegacyWithoutDaemon() {
    val paused = InstallLifecycle.plan(legacy = true, daemonAvailable = false, dialogFixed = false)
    assertEquals(InstallLifecycle.MODE_PAUSED, paused.mode)
    assertTrue(paused.paused)

    val fixed = InstallLifecycle.plan(legacy = true, daemonAvailable = false, dialogFixed = true)
    assertEquals(InstallLifecycle.MODE_DIALOG, fixed.mode)
    assertFalse(fixed.paused)

    val modern = InstallLifecycle.plan(legacy = false, daemonAvailable = false, dialogFixed = false)
    assertEquals(InstallLifecycle.MODE_DIALOG, modern.mode)
    assertFalse(modern.paused)
  }

  @Test
  fun terminalResult_classifiesStoreMessages() {
    assertEquals(true, InstallLifecycle.terminalResult("Installed ✓"))
    assertEquals(false, InstallLifecycle.terminalResult("Install failed"))
    assertEquals(false, InstallLifecycle.terminalResult("Error: no network"))
    assertEquals(false, InstallLifecycle.terminalResult("Paused — connect to your computer"))
    assertNull(InstallLifecycle.terminalResult("Downloading…"))
  }
}
