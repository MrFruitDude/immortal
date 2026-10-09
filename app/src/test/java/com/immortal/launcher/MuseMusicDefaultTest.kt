/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MuseMusicDefaultTest {
  private fun player(id: String, name: String, members: List<String> = emptyList(), type: String = "player") =
      JSONObject().put("id", id).put("name", name).put("type", type).put("group_members", JSONArray(members))

  @Test
  fun wholeHome_isTheBiggestGroup() {
    val players =
        listOf(
            player("kitchen", "Kitchen"),
            player("up", "Upstairs", listOf("bedroom", "office")),
            player("group", "Group", listOf("living", "bedroom", "kitchen"), type = "group"),
        )
    assertEquals("group" to "Group", MuseMusic.defaultPlayer(players))
  }

  @Test
  fun noGroups_meansNoDefault() {
    assertNull(MuseMusic.defaultPlayer(listOf(player("kitchen", "Kitchen"))))
  }
}
