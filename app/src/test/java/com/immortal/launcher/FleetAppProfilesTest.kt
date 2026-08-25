/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** Pure validation rules; persistence and install reconciliation need a device. */
class FleetAppProfilesTest {

  @Test
  fun normalizeAction_acceptsExactAndCasedActions() {
    assertEquals(FleetAppProfiles.ACTION_INSTALL, FleetAppProfiles.normalizeAction("install"))
    assertEquals(FleetAppProfiles.ACTION_INSTALL, FleetAppProfiles.normalizeAction(" Install "))
    assertEquals(FleetAppProfiles.ACTION_REMOVE, FleetAppProfiles.normalizeAction("REMOVE"))
    assertNull(FleetAppProfiles.normalizeAction(""))
    assertNull(FleetAppProfiles.normalizeAction("upgrade"))
  }

  @Test
  fun validatePackageName_acceptsAndroidPackageCharacters() {
    assertEquals(
        "com.example.app",
        FleetAppProfiles.validatePackageName(" com.example.app "))
    assertEquals("APP_2", FleetAppProfiles.validatePackageName("APP_2"))
  }

  @Test
  fun validatePackageName_rejectsBlankInvalidAndOversizedNames() {
    assertRejected("", "packageName_required")
    assertRejected("   ", "packageName_required")
    assertRejected("com/example", "invalid_package_name")
    assertRejected("com.example-", "invalid_package_name")
    assertRejected("com.\u00e9xample", "invalid_package_name")
    assertRejected("a".repeat(256), "packageName_required")
  }

  private fun assertRejected(packageName: String, expectedMessage: String) {
    try {
      FleetAppProfiles.validatePackageName(packageName)
    } catch (e: IllegalArgumentException) {
      assertEquals(expectedMessage, e.message)
      return
    }
    fail("Expected $packageName to be rejected")
  }
}
