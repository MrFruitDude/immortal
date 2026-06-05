/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * Which stock apps to hide, stash in folders, or relabel in the launcher grid.
 * Editable here (and a natural candidate for remote config later, like the
 * store catalog).
 *
 * The hidden set is mostly Meta's trusted-caller-gated apps (Contacts, Camera,
 * Photos, Help, …) — they reject our untrusted launch and can only be opened
 * from the stock launcher, so we hide them from the grid and route users to the
 * Portal Home bridge instead. Plus dead/internal entries.
 */
object Curation {

  /** Hidden by package id. */
  val hiddenPackages =
      setOf(
          "com.facebook.alohaapps.contacts", // gated → Portal Home bridge
          "com.facebook.alohaapps.launcher", // stock launcher — we have a clean bridge tile
          "com.facebook.alohaapps.superframe", // old slideshow
          "com.facebook.alohaservices.abilitymanager", // internal, no icon
          "com.facebook.aloha.app.cameraeditor", // avatar/debug camera, dead/gated
          "com.android.camera2", // stock camera crashes from our launch → bridge
          "com.facebook.alohasdk.ctsintentabsorber", // Android CTS test app, not user-facing
      )

  /** Hidden by visible label (catches gated apps that share a package). */
  val hiddenLabels = setOf("Help", "Photos", "Debug Camera", "Avatar Editor")

  /** Package → folder name. Anything not listed shows on the main grid. */
  val folders =
      mapOf(
          "com.android.settings" to "Settings",
          "com.facebook.alohaapps.settings" to "Settings",
          "com.facebook.aloha.chargecontrol" to "Settings",
          "com.android.inputmethod.latin" to "Settings",
          "com.facebook.portal.aiservice" to "Settings",
          "com.android.quicksearchbox" to "Settings",
      )

  /** Friendlier display names for a few stock apps. */
  val labelOverrides =
      mapOf(
          "com.android.settings" to "System Settings",
          "com.facebook.alohaapps.settings" to "Portal Settings",
          "com.facebook.portal.aiservice" to "AI Service",
          "com.android.quicksearchbox" to "Google Search",
      )

  fun isHidden(packageName: String, label: String): Boolean =
      packageName in hiddenPackages || label in hiddenLabels

  fun folderFor(packageName: String): String? = folders[packageName]

  fun displayLabel(packageName: String, label: String): String =
      labelOverrides[packageName] ?: label
}
