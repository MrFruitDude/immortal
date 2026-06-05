/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.service.dreams.DreamService
import android.view.MotionEvent

/**
 * Native photo-frame screensaver. Reproduces the stock Portal idle screen
 * (clock / battery / date / weather over a full-screen photo feed) without
 * touching Meta's APK. Set as the screensaver via
 * `settings put secure screensaver_components <pkg>/.PhotoDreamService`.
 *
 * All UI/logic lives in [PhotoFrameController], shared with
 * [PhotoFramePreviewActivity].
 */
class PhotoDreamService : DreamService() {
  private lateinit var frame: PhotoFrameController

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    // Interactive so we receive touch and can exit on tap (verified on device).
    // Fullscreen for an immersive frame — tap-to-exit is the way out.
    isInteractive = true
    isFullscreen = true
    isScreenBright = true
    frame = PhotoFrameController(this)
    frame.onExit = { finish() }
    val root = frame.view
    // Tap dismisses, horizontal swipe changes photo (handled by the controller).
    root.setOnTouchListener { _, ev ->
      frame.onTouch(ev)
      true
    }
    setContentView(root)
    frame.start()
  }

  override fun onDetachedFromWindow() {
    if (this::frame.isInitialized) frame.stop()
    super.onDetachedFromWindow()
  }
}
