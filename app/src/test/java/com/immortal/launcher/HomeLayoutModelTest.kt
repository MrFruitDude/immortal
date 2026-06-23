/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeLayoutModelTest {

  @Test
  fun effectiveApps_userAssignmentsOverrideCuratedFolders() {
    val effective =
        HomeLayoutModel.effectiveApps(
            listOf(app("a", "Media"), app("b", "Games"), app("c", null)),
            mapOf("a" to "", "c" to "Pinned"),
        )
    assertNull(effective[0].folder)
    assertEquals("Games", effective[1].folder)
    assertEquals("Pinned", effective[2].folder)
  }

  @Test
  fun folderNamesAreDistinctAndSorted() {
    assertEquals(
        listOf("Games", "Media"),
        HomeLayoutModel.folderNames(listOf(app("a", "Media"), app("b", "Games"), app("c", "Media"))),
    )
  }

  @Test
  fun createAndRenameFolderReturnUpdatedAssignments() {
    val created = HomeLayoutModel.createFolder(emptyMap(), "a", "b", "  Photos  ")
    assertEquals("Photos", created.folderName)
    assertEquals(mapOf("a" to "Photos", "b" to "Photos"), created.assignments)

    val renamed =
        HomeLayoutModel.renameFolder(
            effectiveApps = listOf(app("a", "Photos"), app("b", "Photos"), app("c", null)),
            assignments = created.assignments,
            oldName = "Photos",
            rawName = "Family",
        )
    assertEquals("Family", renamed?.folderName)
    assertEquals(mapOf("a" to "Family", "b" to "Family"), renamed?.assignments)
  }

  @Test
  fun moveOutCollapsesSingleAppFolders() {
    val result =
        HomeLayoutModel.moveOut(
            effectiveApps = listOf(app("a", "Folder"), app("b", "Folder"), app("c", null)),
            assignments = mapOf("a" to "Folder", "b" to "Folder"),
            packageName = "a",
        )
    assertEquals(mapOf("a" to "", "b" to ""), result?.assignments)
    assertEquals(true, result?.closeFolder)
  }

  private fun app(packageName: String, folder: String?) = HomeLayoutModel.AppRef(packageName, folder)
}
