/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeControlsTest {
  // The compact shape MuseHomeAssistant.states returns.
  private val entities =
      JSONArray(
          """[
            {"entity_id":"climate.salon","state":"heat","name":"Salon","current_temperature":20.5,"temperature":21,"hvac_action":"heating"},
            {"entity_id":"climate.chambre","state":"heat","name":"Chambre","current_temperature":18,"temperature":17.5,"hvac_action":"idle"},
            {"entity_id":"climate.garage","state":"unavailable","name":"Garage"},
            {"entity_id":"light.kitchen","state":"on","name":"Kitchen","brightness":128},
            {"entity_id":"light.hall","state":"off","name":"Hall","brightness":null},
            {"entity_id":"light.ghost","state":"unavailable","name":"Ghost"},
            {"entity_id":"sensor.x","state":"3","name":"X"}
          ]""")

  @Test
  fun thermostats_skipUnavailable_keepTargets() {
    val t = HomeControls.parseThermostats(entities)
    assertEquals(listOf("Salon", "Chambre"), t.map { it.name })
    assertEquals(20.5, t[0].current!!, 0.0)
    assertEquals(21.0, t[0].target!!, 0.0)
    assertEquals("heating", t[0].action)
    assertEquals(0.5, HomeControls.step(t[0]), 0.0)
  }

  @Test
  fun haLights_mapBrightnessToPercent() {
    val l = HomeControls.parseHaLights(entities)
    assertEquals(listOf("ha:light.kitchen", "ha:light.hall"), l.map { it.id })
    assertEquals(50, l[0].brightness)
    assertNull(l[1].brightness)
  }

  @Test
  fun hueRooms_onlyRoomsAndZones() {
    val rooms =
        JSONArray(
            """[{"id":"1","name":"Living","type":"Room","any_on":true},
               {"id":"2","name":"TV sync","type":"Entertainment","any_on":false},
               {"id":"3","name":"Upstairs","type":"Zone","any_on":false}]""")
    val l = HomeControls.parseHueRooms(rooms)
    assertEquals(listOf("hue:Living", "hue:Upstairs"), l.map { it.id })
    assertEquals(true, l[0].on)
  }

  @Test
  fun hueScenes_keepRoomAndSortByName() {
    val rooms = JSONArray("""[{"id":"1","name":"Living room","type":"Room"},{"id":"2","name":"Bedroom","type":"Room"}]""")
    val scenes =
        JSONArray(
            """[{"id":"a","name":"Pumpkin Spice","group":"1"},{"id":"b","name":"Honolulu","group":"1"},
               {"id":"c","name":"Honolulu","group":"2"},{"id":"d","name":"Honolulu","group":"1"},
               {"id":"e","name":"","group":"1"}]""")
    val s = HomeControls.parseHueScenes(scenes, rooms)
    assertEquals(listOf("Honolulu|Bedroom", "Honolulu|Living room", "Pumpkin Spice|Living room"), s.map { it.name + "|" + it.room })
  }
}
