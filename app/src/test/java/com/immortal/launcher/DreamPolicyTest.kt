/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dream-bounce policy (pure decisions). Background: Meta's power manager
 * force-wakes any dream ~2 min after idle; these rules decide whether we put the
 * frame back up (permanent frame) or let the device go quiet (battery saver).
 */
class DreamPolicyTest {

  @Test
  fun mainsPoweredPortals_alwaysDreamAndAlwaysRelaunch() {
    // No battery (Portal+, Mini, gen-2, TV): saver setting is irrelevant.
    assertTrue(DreamPolicy.dreamShouldBeEnabled(hasBattery = false, batterySaver = true, powered = false))
    assertTrue(
        DreamPolicy.shouldRelaunch(
            userExitAgoMs = 60_000, interactive = true,
            hasBattery = false, batterySaver = true, powered = false))
  }

  @Test
  fun batterySaver_pausesDreamOnlyWhileUnplugged() {
    assertFalse(DreamPolicy.dreamShouldBeEnabled(hasBattery = true, batterySaver = true, powered = false))
    assertTrue(DreamPolicy.dreamShouldBeEnabled(hasBattery = true, batterySaver = true, powered = true))
    // Saver off: frame runs on battery too.
    assertTrue(DreamPolicy.dreamShouldBeEnabled(hasBattery = true, batterySaver = false, powered = false))
  }

  @Test
  fun userTapExit_neverRelaunches() {
    assertFalse(
        DreamPolicy.shouldRelaunch(
            userExitAgoMs = 500, interactive = true,
            hasBattery = false, batterySaver = true, powered = true))
  }

  @Test
  fun powerButtonSleep_neverRelaunches() {
    // Screen is off (not interactive): the user or system put the device to sleep.
    assertFalse(
        DreamPolicy.shouldRelaunch(
            userExitAgoMs = 60_000, interactive = false,
            hasBattery = false, batterySaver = true, powered = true))
  }

  @Test
  fun systemBounce_relaunchesUnlessSavingBattery() {
    // The classic bounce: no recent tap, screen on.
    assertTrue(
        DreamPolicy.shouldRelaunch(
            userExitAgoMs = 125_000, interactive = true,
            hasBattery = true, batterySaver = true, powered = true))
    assertFalse(
        DreamPolicy.shouldRelaunch(
            userExitAgoMs = 125_000, interactive = true,
            hasBattery = true, batterySaver = true, powered = false))
  }
}
