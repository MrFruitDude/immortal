/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Full-screen photo frame as a normal activity. Two jobs:
 *  - on-demand preview (Screensaver tile), and
 *  - the PERMANENT frame: when the system force-wakes the real screensaver
 *    (see [DreamPolicy]), this activity takes over and keeps the screen on, so
 *    the frame runs indefinitely like a stock Portal.
 *
 * Keep-screen-on policy: held whenever the device has no battery (mains-powered
 * Portals) or is charging or the user turned the battery saver off; on a
 * battery model that gets UNPLUGGED mid-frame with saver on, the frame exits so
 * the device can reach real sleep.
 */
class PhotoFramePreviewActivity : ComponentActivity() {
  private lateinit var frame: PhotoFrameController
  private var unplugReceiver: BroadcastReceiver? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Immersive fullscreen.
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    applyKeepScreenOn()
    frame = PhotoFrameController(this)
    frame.onExit = { finish() }
    setContentView(frame.view)
    frame.start()

    // Battery models, saver on: bow out when unplugged so the device can sleep.
    if (DreamPolicy.hasBattery(this)) {
      unplugReceiver =
          object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
              if (intent.action == Intent.ACTION_POWER_DISCONNECTED &&
                  ScreensaverConfig.load(c).batterySaver) {
                finish()
              } else {
                applyKeepScreenOn()
              }
            }
          }
      registerReceiver(
          unplugReceiver,
          IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
          })
    }
  }

  private fun applyKeepScreenOn() {
    val keep =
        DreamPolicy.dreamShouldBeEnabled(
            hasBattery = DreamPolicy.hasBattery(this),
            batterySaver = ScreensaverConfig.load(this).batterySaver,
            powered = DreamPolicy.isPowered(this),
        )
    if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  // Feed all touches to the controller's gesture detector (tap = exit,
  // horizontal swipe = prev/next) at the window level for reliability.
  override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    frame.onTouch(ev)
    return true
  }

  override fun onDestroy() {
    unplugReceiver?.let { runCatching { unregisterReceiver(it) } }
    if (this::frame.isInitialized) frame.stop()
    super.onDestroy()
  }
}
