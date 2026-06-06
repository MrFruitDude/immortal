/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Process-wide hooks. As the home app our process is effectively persistent, so
 * runtime-registered receivers here behave like manifest ones without the
 * background-broadcast restrictions:
 *  - DREAMING_STOPPED → [DreamPolicy.onDreamingStopped] (keep the photo frame up
 *    when the system force-wakes the screensaver; see DreamPolicy for why).
 *  - Charger plug/unplug → [DreamPolicy.applyDreamGate] (battery models pause the
 *    screensaver on battery so the device can truly sleep).
 */
class ImmortalApp : Application() {
  override fun onCreate() {
    super.onCreate()
    val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
              Intent.ACTION_DREAMING_STOPPED -> DreamPolicy.onDreamingStopped(c)
              Intent.ACTION_POWER_CONNECTED,
              Intent.ACTION_POWER_DISCONNECTED -> DreamPolicy.applyDreamGate(c)
            }
          }
        }
    registerReceiver(
        receiver,
        IntentFilter().apply {
          addAction(Intent.ACTION_DREAMING_STOPPED)
          addAction(Intent.ACTION_POWER_CONNECTED)
          addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
    DreamPolicy.applyDreamGate(this)
  }
}
