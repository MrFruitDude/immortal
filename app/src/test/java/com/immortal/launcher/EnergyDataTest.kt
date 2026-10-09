/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyDataTest {
  private val montreal = TimeZone.getTimeZone("America/Toronto")
  private val en = Locale.US

  private fun t(iso: String) = EnergyData.parseIso(iso)!!

  // --- Home Assistant -------------------------------------------------------------

  // The compact shape MuseHomeAssistant.states returns, with the user's real Hilo entities.
  private val entities =
      JSONArray(
          """[
            {"entity_id":"sensor.meter00_power","state":"1315","name":"Meter00 Power","device_class":"power","unit_of_measurement":"W"},
            {"entity_id":"sensor.kitchen_power","state":"435","name":"Kitchen Power","device_class":"power","unit_of_measurement":"W"},
            {"entity_id":"sensor.living_room_power","state":"596","name":"","device_class":"power","unit_of_measurement":"W"},
            {"entity_id":"sensor.bedroom_power","state":"0","name":"Bedroom Power","device_class":"power","unit_of_measurement":"W"},
            {"entity_id":"sensor.garage_power","state":"unavailable","name":"Garage Power","device_class":"power","unit_of_measurement":"W"},
            {"entity_id":"sensor.kitchen_temperature","state":"21.5","name":"Kitchen Temperature","device_class":"temperature"},
            {"entity_id":"sensor.defi_hilo","state":"scheduled","name":"Défi Hilo","next_events":[
              {"event_id":101,"state":"completed","period":"am",
               "phases":{"preheat_start":"2026-01-25T04:00:00-05:00","reduction_start":"2026-01-25T06:00:00-05:00","reduction_end":"2026-01-25T09:00:00-05:00"}},
              {"event_id":102,"state":"scheduled","period":"am",
               "phases":{"preheat_start":"2026-01-27T04:00:00-05:00","reduction_start":"2026-01-27T06:00:00-05:00","reduction_end":"2026-01-27T09:00:00-05:00","recovery_end":"2026-01-27T09:30:00-05:00"}}
            ]},
            {"entity_id":"sensor.recompenses_hilo","state":"12.5","name":"Récompenses Hilo","device_class":"monetary","unit_of_measurement":"CAD"}
          ]""")

  @Test
  fun discovery_findsMeterRoomsDefiAndRewards() {
    val now = t("2026-01-26T15:00:00-05:00")
    val home = EnergyData.parseHome(entities, now)!!
    assertEquals(1315.0, home.watts!!, 0.0)
    assertEquals(listOf("Bedroom", "Kitchen", "Living room"), home.rooms.map { it.name })
    assertEquals(listOf(0.0, 435.0, 596.0), home.rooms.map { it.watts })
    assertEquals(12.5, home.rewards!!, 0.0)
    val d = home.defi!!
    assertEquals("scheduled", d.state)
    assertEquals(t("2026-01-27T06:00:00-05:00"), d.start)
    assertEquals(t("2026-01-27T09:00:00-05:00"), d.end)
    assertEquals("Défi Hilo tomorrow 6–9 AM", EnergyData.defiLabel(d, now, montreal, en, use24h = false))
    assertEquals("Défi Hilo tomorrow 06:00–09:00", EnergyData.defiLabel(d, now, montreal, en, use24h = true))
    // During the reduction window it's in progress.
    assertEquals("Défi in progress", EnergyData.defiLabel(d, t("2026-01-27T07:00:00-05:00"), montreal, en, false))
  }

  @Test
  fun discovery_prefersHiloMeter_andConvertsKilowatts() {
    val e =
        JSONArray(
            """[
              {"entity_id":"sensor.ups_meter_power","state":"120","name":"UPS meter","device_class":"power","unit_of_measurement":"W"},
              {"entity_id":"sensor.hilo_meter_power","state":"2.4","name":"Hilo meter","device_class":"power","unit_of_measurement":"kW"},
              {"entity_id":"sensor.office_power","state":"0.25","name":"Office","device_class":"power","unit_of_measurement":"kW"}
            ]""")
    assertEquals("sensor.hilo_meter_power", EnergyData.findMeter(e)!!.getString("entity_id"))
    val home = EnergyData.parseHome(e, 0L)!!
    assertEquals(2400.0, home.watts!!, 0.001)
    // Other "meter" sensors are never rooms.
    assertEquals(listOf(EnergyData.Room("Office", 250.0)), home.rooms)
    assertNull(home.defi)
  }

  @Test
  fun discovery_nothingEnergyRelated_isNull() {
    val e = JSONArray("""[{"entity_id":"sensor.kitchen_temperature","state":"21","device_class":"temperature"}]""")
    assertNull(EnergyData.parseHome(e, 0L))
  }

  @Test
  fun defi_acceptsTopLevelTimes_stringList_andStateOnly() {
    val now = t("2026-01-26T12:00:00Z")
    // Times at the top level of each event, list serialized as a string.
    val flat =
        JSONObject()
            .put("entity_id", "sensor.defi_hilo")
            .put("state", "scheduled")
            .put("next_events", """[{"start":"2026-01-26T21:00:00+00:00","end":"2026-01-27T01:00:00+00:00"}]""")
    val d = EnergyData.parseDefi(flat, now)!!
    assertEquals(t("2026-01-26T21:00:00Z"), d.start)
    assertEquals("Défi Hilo today 4–8 PM", EnergyData.defiLabel(d, now, montreal, en, false))
    // No events but the sensor says it's reducing: in progress.
    val reducing = JSONObject().put("entity_id", "sensor.defi_hilo").put("state", "reduction")
    assertEquals("Défi in progress", EnergyData.defiLabel(EnergyData.parseDefi(reducing, now)!!, now, montreal, en, false))
    // Off with nothing scheduled: no chip.
    assertNull(EnergyData.parseDefi(JSONObject().put("state", "off").put("next_events", JSONArray()), now))
    // Unknown event shapes are skipped, not fatal.
    val odd = JSONObject().put("state", "off").put("next_events", JSONArray("""[{"foo":1},"x",{"phases":{"reduction_start":"soon"}}]"""))
    assertNull(EnergyData.parseDefi(odd, now))
  }

  @Test
  fun roomName_stripsPowerSuffix() {
    assertEquals("Kitchen", EnergyData.roomName("Kitchen Power", "sensor.kitchen_power"))
    assertEquals("Living room", EnergyData.roomName("", "sensor.living_room_power"))
    assertEquals("Salon", EnergyData.roomName("Salon puissance", "sensor.salon_puissance"))
    assertEquals("Power", EnergyData.roomName("Power", "sensor.power"))
  }

  // History: HA's minimal_response + no_attributes (the first item carries the entity id).
  private val history =
      """[[
        {"entity_id":"sensor.meter00_power","state":"1000","last_changed":"2026-10-08T12:00:00+00:00"},
        {"state":"3000","last_changed":"2026-10-08T12:15:00.123456+00:00"},
        {"state":"unavailable","last_changed":"2026-10-08T12:20:00+00:00"},
        {"state":"2000","last_changed":"2026-10-08T13:00:00+00:00"}
      ]]"""

  @Test
  fun history_parsesNumericStatesWithMicroseconds() {
    val p = EnergyData.parseHistory(history)
    assertEquals(listOf(1000.0, 3000.0, 2000.0), p.map { it.second })
    assertEquals(t("2026-10-08T12:15:00Z") + 123, p[1].first)
  }

  @Test
  fun history_downsamplesTimeWeighted() {
    val p = EnergyData.parseHistory(history)
    val start = t("2026-10-08T12:00:00Z")
    // Two half-hour buckets: 15 min at 1000 W + 15 min at 3000 W, then 30 min at 3000 W.
    val two = EnergyData.downsample(p, start, start + 60 * 60_000L, 2)
    assertEquals(2, two.size)
    assertEquals(2000.0, two[0], 1.0)
    assertEquals(3000.0, two[1], 0.0)
    // The full day becomes 48 points; after 13:00 it holds 2000 W.
    val day = EnergyData.downsample(p, start, start + 24 * 60 * 60_000L, EnergyData.SPARK_POINTS)
    assertEquals(48, day.size)
    assertEquals(2000.0, day.last(), 0.0)
    // Before the first sample the first value is assumed.
    assertEquals(listOf(1000.0), EnergyData.downsample(p, start - 30 * 60_000L, start, 1))
    assertTrue(EnergyData.downsample(emptyList(), start, start + 1, 4).isEmpty())
  }

  @Test
  fun parseIso_variants() {
    assertEquals(t("2026-10-09T22:15:00+00:00"), t("2026-10-09T18:15:00-04:00"))
    assertEquals(t("2026-10-09T22:15:00Z"), t("2026-10-09 22:15:00+0000"))
    assertEquals("2026-10-09T22:15:00Z", EnergyData.utcIso(t("2026-10-09T22:15:00Z")))
    assertNull(EnergyData.parseIso("soon"))
    assertNull(EnergyData.parseIso(""))
  }

  // --- Hydro-Québec open data (real responses, trimmed) -----------------------------

  @Test
  fun demand_oldestFirst_skipsNulls() {
    val json =
        """{"total_count": 171, "results": [
          {"date": "2026-10-10T03:45:00+00:00", "valeurs_demandetotal": null},
          {"date": "2026-10-09T22:15:00+00:00", "valeurs_demandetotal": 19878.0},
          {"date": "2026-10-09T22:00:00+00:00", "valeurs_demandetotal": 19907.0},
          {"date": "2026-10-09T21:45:00+00:00", "valeurs_demandetotal": 19691.0},
          {"date": "2026-10-09T21:30:00+00:00", "valeurs_demandetotal": 19551.0}]}"""
    val d = EnergyData.parseDemand(json)
    assertEquals(listOf(19551.0, 19691.0, 19907.0, 19878.0), d)
    assertEquals("19.9 GW", EnergyData.formatMw(d.last(), en))
    assertEquals("850 MW", EnergyData.formatMw(850.0, en))
  }

  @Test
  fun production_mixFractions() {
    val json =
        """{"total_count": 40, "results": [{"date": "2026-10-09T19:30:00+00:00", "valeurs_total": 17472.0,
          "valeurs_hydraulique": 14090.0, "valeurs_eolien": 2745.0, "valeurs_autres": 635.0, "valeurs_solaire": 2.0,
          "valeurs_thermique": null}]}"""
    val mix = EnergyData.parseProduction(json)
    assertEquals(EnergyData.Source.values().toList(), mix.map { it.source })
    assertEquals(0.806, mix[0].fraction, 0.001)
    assertEquals(0.157, mix[1].fraction, 0.001)
    assertEquals(635.0, mix[3].mw, 0.0)
    assertEquals(1.0, mix.sumOf { it.fraction }, 1e-9)
    assertTrue(EnergyData.parseProduction("""{"results": []}""").isEmpty())
  }

  private val peaks =
      """{"total_count": 6, "results": [
        {"offre": "TPC-DPC", "datedebut": "2026-02-10T11:00:00+00:00", "datefin": "2026-02-10T15:00:00+00:00", "plagehoraire": "AM", "duree": "PT04H00MS", "secteurclient": "Residentiel"},
        {"offre": "OEA", "datedebut": "2026-03-18T08:00:00+00:00", "datefin": "2026-03-18T15:00:00+00:00", "plagehoraire": "AM", "duree": "PT07H00MS", "secteurclient": "Affaires"},
        {"offre": "TPC-DPC", "datedebut": "2026-03-18T10:00:00+00:00", "datefin": "2026-03-18T14:00:00+00:00", "plagehoraire": "AM", "duree": "PT04H00MS", "secteurclient": "Residentiel"},
        {"offre": "TPC-GPC", "datedebut": "2026-03-18T10:00:00+00:00", "datefin": "2026-03-18T14:00:00+00:00", "plagehoraire": "AM", "duree": "PT04H00MS", "secteurclient": "Affaires"},
        {"offre": "CPC-D", "datedebut": "2026-03-18T10:00:00+00:00", "datefin": "2026-03-18T14:00:00+00:00", "plagehoraire": "AM", "duree": "PT04H00MS", "secteurclient": "Residentiel"},
        {"offre": "CPC-G", "datedebut": "2026-03-18T10:00:00+00:00", "datefin": "2026-03-18T14:00:00+00:00", "plagehoraire": "AM", "duree": "PT04H00MS", "secteurclient": "Affaires"}]}"""

  @Test
  fun peaks_residentialOnly_mergedWindows() {
    // Feb 9: the next residential event is the Feb 10 TPC-DPC-only one (6–10 AM EST).
    val feb9 = t("2026-02-09T20:00:00-05:00")
    val p1 = EnergyData.parsePeaks(peaks, feb9)!!
    assertEquals(listOf("TPC-DPC"), p1.offers)
    assertEquals("Peak event tomorrow 6–10 AM", EnergyData.peakLabel(p1, feb9, montreal, en, false))
    // Mar 17 evening, after the DST switch: CPC-D and TPC-DPC merge into one 6–10 AM EDT window;
    // the business-only OEA (which starts earlier) is ignored.
    val mar17 = t("2026-03-17T19:00:00-04:00")
    val p2 = EnergyData.parsePeaks(peaks, mar17)!!
    assertEquals(t("2026-03-18T10:00:00Z"), p2.start)
    assertEquals(listOf("CPC-D", "TPC-DPC"), p2.offers)
    assertEquals("Peak event tomorrow 6–10 AM", EnergyData.peakLabel(p2, mar17, montreal, en, false))
    assertEquals("Peak event until 10 AM", EnergyData.peakLabel(p2, t("2026-03-18T08:00:00-04:00"), montreal, en, false))
    // After it ends: nothing.
    assertNull(EnergyData.parsePeaks(peaks, t("2026-03-18T15:00:00Z")))
  }

  @Test
  fun residentialMatch_isLoose() {
    assertTrue(EnergyData.isResidential("Résidentiel", ""))
    assertTrue(EnergyData.isResidential("RESIDENTIEL", "X"))
    assertTrue(EnergyData.isResidential("", "cpc-d"))
    assertTrue(!EnergyData.isResidential("Affaires", "CPC-G"))
  }

  @Test
  fun formats() {
    assertEquals("435 W", EnergyData.formatWatts(435.2, en))
    assertEquals("1.3 kW", EnergyData.formatWatts(1315.0, en))
    assertEquals("1,3 kW", EnergyData.formatWatts(1315.0, Locale.CANADA_FRENCH))
    val now = t("2026-10-09T12:00:00-04:00")
    assertEquals("Mon 11 AM–2 PM", EnergyData.windowLabel(t("2026-10-12T11:00:00-04:00"), t("2026-10-12T14:00:00-04:00"), now, montreal, en, false))
    assertEquals("Oct 20 6:30–9 AM", EnergyData.windowLabel(t("2026-10-20T06:30:00-04:00"), t("2026-10-20T09:00:00-04:00"), now, montreal, en, false))
    assertNotNull(EnergyData.windowLabel(now, null, now, montreal, en, true))
  }
}
