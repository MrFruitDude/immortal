/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/** Pure Launcher layout rules that sit above the free-placement [HomeGrid] slot model. */
object HomeLayoutModel {
  data class AppRef(val packageName: String, val folder: String?)

  data class CreateFolderResult(val assignments: Map<String, String>, val folderName: String)

  data class RenameFolderResult(val assignments: Map<String, String>, val folderName: String)

  data class MoveOutResult(val assignments: Map<String, String>, val closeFolder: Boolean)

  fun effectiveFolder(
      packageName: String,
      defaultFolder: String?,
      assignments: Map<String, String>,
  ): String? =
      if (assignments.containsKey(packageName)) assignments.getValue(packageName).ifEmpty { null }
      else defaultFolder

  fun effectiveApps(apps: List<AppRef>, assignments: Map<String, String>): List<AppRef> =
      apps.map { it.copy(folder = effectiveFolder(it.packageName, it.folder, assignments)) }

  fun folderNames(apps: List<AppRef>): List<String> = apps.mapNotNull { it.folder }.distinct().sorted()

  fun createFolder(
      assignments: Map<String, String>,
      firstPackage: String,
      secondPackage: String,
      rawName: String,
  ): CreateFolderResult {
    val name = rawName.trim().ifEmpty { "Folder" }
    val next = LinkedHashMap(assignments)
    next[firstPackage] = name
    next[secondPackage] = name
    return CreateFolderResult(next, name)
  }

  fun renameFolder(
      effectiveApps: List<AppRef>,
      assignments: Map<String, String>,
      oldName: String,
      rawName: String,
  ): RenameFolderResult? {
    val newName = rawName.trim()
    if (newName.isEmpty() || newName == oldName) return null
    val next = LinkedHashMap(assignments)
    effectiveApps.filter { it.folder == oldName }.forEach { next[it.packageName] = newName }
    return RenameFolderResult(next, newName)
  }

  fun moveOut(
      effectiveApps: List<AppRef>,
      assignments: Map<String, String>,
      packageName: String,
  ): MoveOutResult? {
    val folder = effectiveApps.firstOrNull { it.packageName == packageName }?.folder ?: return null
    val next = LinkedHashMap(assignments)
    next[packageName] = "" // explicit ungroup override
    val remaining = effectiveApps.filter { it.folder == folder && it.packageName != packageName }
    if (remaining.size == 1) next[remaining[0].packageName] = ""
    return MoveOutResult(next, closeFolder = remaining.size <= 1)
  }
}
