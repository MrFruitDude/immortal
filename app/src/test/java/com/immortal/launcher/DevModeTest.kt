package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure status-shaping for the fleet `/dev` endpoint (no Context needed). */
class DevModeTest {

  @Test
  fun statusJson_reportsEnabledAndVersion() {
    val on = DevMode.statusJson(true, 4207, "1.4.2-dev")
    assertTrue(on.getBoolean("enabled"))
    assertEquals(4207L, on.getLong("versionCode"))
    assertEquals("1.4.2-dev", on.getString("versionName"))

    val off = DevMode.statusJson(false, 100, "1.0.0")
    assertFalse(off.getBoolean("enabled"))
    assertEquals(100L, off.getLong("versionCode"))
  }
}
