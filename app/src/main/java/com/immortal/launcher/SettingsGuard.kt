/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Self-healing for our screensaver settings. The stock Aloha launcher rewrites
 * `screensaver_components` / `screensaver_default_component` back to its own
 * SuperFrame whenever it runs, so Immortal re-asserts them on boot and on every
 * resume. Requires `WRITE_SECURE_SETTINGS`, which provisioning grants via
 * `pm grant` — without it this is a silent no-op (settings stay as provisioned).
 *
 * Note: the *home* role can't be reasserted this way (it isn't a secure setting
 * and needs system privilege); provisioning hardens that separately by disabling
 * the stock launcher's home activity.
 */
object SettingsGuard {

  fun reaffirmScreensaver(context: Context) {
    runCatching {
      val resolver = context.contentResolver
      val ours = ComponentName(context, PhotoDreamService::class.java).flattenToShortString()
      if (Settings.Secure.getString(resolver, "screensaver_components") != ours) {
        Settings.Secure.putString(resolver, "screensaver_components", ours)
      }
      if (Settings.Secure.getString(resolver, "screensaver_default_component") != ours) {
        Settings.Secure.putString(resolver, "screensaver_default_component", ours)
      }
      Settings.Secure.putInt(resolver, "screensaver_enabled", 1)
    }
  }

  /** True if we hold WRITE_SECURE_SETTINGS (so self-healing is active). */
  fun canWriteSecureSettings(context: Context): Boolean =
      context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
          android.content.pm.PackageManager.PERMISSION_GRANTED
}
