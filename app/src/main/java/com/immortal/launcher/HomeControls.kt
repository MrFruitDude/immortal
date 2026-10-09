/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The dashboard's smart-home cards: thermostats and lights, read from the same Home Assistant
 * and Hue connections Muse uses (Settings › Muse). Thermostats are any Home Assistant `climate`
 * entity — Hydro-Québec Hilo thermostats show up there through the Hilo integration, as do
 * Ecobee, Nest and friends. Lights are the Hue bridge's rooms when Hue is paired, otherwise Home
 * Assistant's `light` entities.
 *
 * Every call here is blocking network I/O: call it off the main thread.
 */
object HomeControls {
  private const val TAG = "ImmortalHomeControls"
  private const val MAX_THERMOSTATS = 4
  private const val MAX_LIGHTS = 8
  private const val MAX_SCENES = 16

  data class Thermostat(
      val entityId: String,
      val name: String,
      val current: Double?,
      val target: Double?,
      /** heating / idle / off / cooling — as Home Assistant reports `hvac_action` (or the state). */
      val action: String,
      val unit: String,
  )

  data class Light(
      /** `hue:<room name>` or `ha:<entity_id>`. */
      val id: String,
      val name: String,
      val on: Boolean,
      /** 0–100, or null when unknown / not dimmable. */
      val brightness: Int?,
  )

  /** A Hue scene; [room] is the room/zone it belongs to (empty for an all-lights scene). */
  data class Scene(val id: String, val name: String, val roomId: String, val room: String)

  data class Snapshot(
      val homeAssistant: Boolean,
      val hue: Boolean,
      val thermostats: List<Thermostat> = emptyList(),
      val lights: List<Light> = emptyList(),
      val scenes: List<Scene> = emptyList(),
      val error: String? = null,
  ) {
    val configured: Boolean
      get() = homeAssistant || hue
  }

  fun load(c: Context): Snapshot {
    val ha = MuseHomeAssistant.configured(c)
    val hue = MuseHue.paired(c)
    var snap = Snapshot(homeAssistant = ha, hue = hue)
    if (!ha && !hue) return snap
    val errors = mutableListOf<String>()
    if (ha) {
      runCatching {
            val entities = MuseHomeAssistant.states(c, null, null, 2000).getJSONArray("entities")
            snap = snap.copy(thermostats = parseThermostats(entities))
            if (!hue) snap = snap.copy(lights = parseHaLights(entities), scenes = parseHaScenes(entities))
          }
          .onFailure { errors += "Home Assistant: ${it.message}" }
    }
    if (hue) {
      runCatching {
            val hue = MuseHue.lights(c)
            val rooms = hue.getJSONArray("rooms")
            snap = snap.copy(lights = parseHueRooms(rooms), scenes = parseHueScenes(hue.getJSONArray("scenes"), rooms))
          }
          .onFailure { errors += "Hue: ${it.message}" }
    }
    if (errors.isNotEmpty()) Log.w(TAG, errors.joinToString("; "))
    return snap.copy(error = errors.firstOrNull())
  }

  fun toggle(c: Context, light: Light) {
    when {
      light.id.startsWith("hue:") ->
          MuseHue.set(c, JSONObject().put("room", light.id.removePrefix("hue:")).put("on", !light.on))
      light.id.startsWith("ha:") ->
          MuseHomeAssistant.callService(
              c, "light", if (light.on) "turn_off" else "turn_on", JSONObject().put("entity_id", light.id.removePrefix("ha:")))
    }
  }

  /** Every light off: the whole Hue bridge and/or every Home Assistant light. */
  fun allOff(c: Context) {
    if (MuseHue.paired(c)) runCatching { MuseHue.set(c, JSONObject().put("on", false)) }
    if (MuseHomeAssistant.configured(c))
        runCatching { MuseHomeAssistant.callService(c, "light", "turn_off", JSONObject().put("entity_id", "all")) }
  }

  /** Recall a Hue scene on its room (or on every light for an all-lights scene). */
  fun activate(c: Context, scene: Scene) {
    if (scene.id.startsWith("ha:")) {
      MuseHomeAssistant.callService(c, "scene", "turn_on", JSONObject().put("entity_id", scene.id.removePrefix("ha:")))
      return
    }
    val p = JSONObject().put("scene", scene.id)
    if (scene.roomId.isNotEmpty()) p.put("room", scene.roomId)
    MuseHue.set(c, p)
  }

  fun setTarget(c: Context, t: Thermostat, target: Double) {
    MuseHomeAssistant.callService(
        c, "climate", "set_temperature", JSONObject().put("entity_id", t.entityId).put("temperature", target))
  }

  /** Thermostat step: half a degree in Celsius (Hilo's resolution), one degree in Fahrenheit. */
  fun step(t: Thermostat): Double = if (t.unit.contains("F")) 1.0 else 0.5

  // --- parsing (pure, for tests) -----------------------------------------------

  internal fun parseThermostats(entities: JSONArray): List<Thermostat> =
      (0 until entities.length())
          .map { entities.getJSONObject(it) }
          .filter { it.optString("entity_id").startsWith("climate.") && it.optString("state") != "unavailable" }
          .map {
            Thermostat(
                entityId = it.getString("entity_id"),
                name = it.optString("name").ifBlank { it.getString("entity_id").substringAfter('.') },
                current = it.optDoubleOrNull("current_temperature"),
                target = it.optDoubleOrNull("temperature"),
                action = it.optString("hvac_action").ifBlank { it.optString("state") },
                unit = it.optString("unit_of_measurement").ifBlank { "°" },
            )
          }
          .take(MAX_THERMOSTATS)

  internal fun parseHaLights(entities: JSONArray): List<Light> {
    val all =
        (0 until entities.length())
            .map { entities.getJSONObject(it) }
            .filter { it.optString("entity_id").startsWith("light.") && it.optString("state") in setOf("on", "off") }
    // Hue reaches Home Assistant as one light per bulb plus a group per room: show the rooms when
    // there are any (the whole-home group last), like the Hue app does.
    val rooms = all.filter { it.optBoolean("is_hue_group") }.sortedBy { it.optString("name").equals("Home", true) }
    return toLights(rooms.ifEmpty { all })
  }

  /** Home Assistant scenes (Hue's arrive as "Home Honolulu" with group "Home"; keep "Honolulu"). */
  internal fun parseHaScenes(entities: JSONArray): List<Scene> =
      (0 until entities.length())
          .map { entities.getJSONObject(it) }
          .filter { it.optString("entity_id").startsWith("scene.") && it.optString("state") != "unavailable" }
          .map {
            val room = it.optString("group_name")
            val full = it.optString("name").ifBlank { it.getString("entity_id").substringAfter('.') }
            val name = if (room.isNotBlank() && full.startsWith("$room ", true)) full.substring(room.length + 1) else full
            Scene(id = "ha:" + it.getString("entity_id"), name = name.replaceFirstChar { c -> c.uppercase() }, roomId = "", room = room)
          }
          .sortedBy { it.name.lowercase() }
          .take(MAX_SCENES)

  private fun toLights(list: List<JSONObject>): List<Light> =
      list
          .map {
            val bri = it.optDoubleOrNull("brightness")
            Light(
                id = "ha:" + it.getString("entity_id"),
                name = it.optString("name").ifBlank { it.getString("entity_id").substringAfter('.') },
                on = it.optString("state") == "on",
                brightness = bri?.let { b -> (b * 100 / 255).toInt().coerceIn(0, 100) },
            )
          }
          .take(MAX_LIGHTS)

  internal fun parseHueRooms(rooms: JSONArray): List<Light> =
      (0 until rooms.length())
          .map { rooms.getJSONObject(it) }
          .filter { it.optString("type") == "Room" || it.optString("type") == "Zone" }
          .sortedBy { it.optString("type") != "Room" } // rooms first, then zones like "Home"
          .map { Light(id = "hue:" + it.optString("name"), name = it.optString("name"), on = it.optBoolean("any_on"), brightness = null) }
          .take(MAX_LIGHTS)

  /**
   * Hue scenes, named after their room when the same name exists in several rooms ("Honolulu ·
   * Living room"). Sorted by name so the chips don't reshuffle between reads.
   */
  internal fun parseHueScenes(scenes: JSONArray, rooms: JSONArray): List<Scene> {
    val roomNames =
        (0 until rooms.length()).associate { rooms.getJSONObject(it).let { r -> r.optString("id") to r.optString("name") } }
    return (0 until scenes.length())
        .map { scenes.getJSONObject(it) }
        // Apps (Google Assistant, wake-up routines…) leave throwaway "recycle" scenes on the bridge.
        .filter { it.optString("name").isNotBlank() && !it.optBoolean("recycle") }
        .map {
          val g = it.optString("group")
          Scene(id = it.optString("id"), name = it.optString("name"), roomId = g, room = roomNames[g].orEmpty())
        }
        .distinctBy { it.name.lowercase() + "|" + it.roomId }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.room }))
        .take(MAX_SCENES)
  }

  private fun JSONObject.optDoubleOrNull(key: String): Double? =
      if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null
}
