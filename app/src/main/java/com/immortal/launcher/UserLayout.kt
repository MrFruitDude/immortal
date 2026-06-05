/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import org.json.JSONObject

/**
 * User-created folder assignments, persisted across restarts.
 *
 * Maps a package id to the folder the user dropped it into (by dragging one app
 * onto another in Manage mode). This overlays the static [Curation.folders]
 * defaults: an entry here wins, so users can both create new folders and move
 * apps out of the curated Settings folder.
 */
object UserLayout {

  private const val PREFS = "immortal_layout"
  private const val KEY = "assignments"

  /** package id -> folder name (user override). */
  fun load(context: Context): Map<String, String> {
    val raw =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyMap()
    return runCatching {
          val obj = JSONObject(raw)
          buildMap {
            obj.keys().forEach { k -> put(k, obj.getString(k)) }
          }
        }
        .getOrDefault(emptyMap())
  }

  fun save(context: Context, assignments: Map<String, String>) {
    val obj = JSONObject()
    assignments.forEach { (k, v) -> obj.put(k, v) }
    context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY, obj.toString())
        .apply()
  }

  /** A folder name not already used by either the user map or the curated set. */
  fun nextFolderName(existing: Set<String>): String {
    if ("Folder" !in existing) return "Folder"
    var i = 2
    while ("Folder $i" in existing) i++
    return "Folder $i"
  }
}
