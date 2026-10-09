/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.net.URI
import java.security.SecureRandom
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartHomeConnectTest {

  // --- HA login: client id / redirect ----------------------------------------------------------

  @Test
  fun clientIdAndRedirect_shareSchemeAndHost_soHaNeedNotFetchTheClientId() {
    // indieauth.verify_redirect_uri accepts without fetching when scheme and netloc match exactly.
    val c = URI(HaAuth.CLIENT_ID)
    val r = URI(HaAuth.REDIRECT_URI)
    assertEquals(c.scheme, r.scheme)
    assertEquals(c.rawAuthority, r.rawAuthority)
    // _parse_client_id: http(s), has a path, no fragment / userinfo / dot segments, a domain name.
    assertTrue(c.scheme == "http" || c.scheme == "https")
    assertEquals("/", c.path)
    assertNull(c.fragment)
    assertNull(c.userInfo)
    assertFalse(c.path.split('/').any { it == "." || it == ".." })
    assertFalse(Regex("""\d+(\.\d+){3}""").matches(c.host))
  }

  @Test
  fun authorizeUrl_encodesEveryParameter() {
    val url = HaAuth.authorizeUrl("http://192.168.1.20:8123/", "abc123")
    assertTrue(url.startsWith("http://192.168.1.20:8123/auth/authorize?"))
    val q = HaAuth.query(URI(url).rawQuery)
    assertEquals("code", q["response_type"])
    assertEquals(HaAuth.CLIENT_ID, q["client_id"])
    assertEquals(HaAuth.REDIRECT_URI, q["redirect_uri"])
    assertEquals("abc123", q["state"])
    assertTrue("client_id is percent-encoded", url.contains("client_id=http%3A%2F%2Fimmortal.portal%2F"))
  }

  @Test
  fun newState_isRandomHex() {
    val a = HaAuth.newState(SecureRandom())
    val b = HaAuth.newState(SecureRandom())
    assertEquals(32, a.length)
    assertTrue(Regex("[0-9a-f]{32}").matches(a))
    assertNotEquals(a, b)
  }

  @Test
  fun normalizeBaseUrl_handlesWhatPeopleType() {
    assertEquals("http://192.168.1.20:8123", HaAuth.normalizeBaseUrl("192.168.1.20"))
    assertEquals("http://192.168.1.20:8123", HaAuth.normalizeBaseUrl(" 192.168.1.20:8123/ "))
    assertEquals("http://192.168.1.20:8123", HaAuth.normalizeBaseUrl("http://192.168.1.20:8123/lovelace/0"))
    assertEquals("https://ha.example.com", HaAuth.normalizeBaseUrl("https://ha.example.com"))
    assertEquals("http://homeassistant:8123", HaAuth.normalizeBaseUrl("homeassistant"))
    assertNull(HaAuth.normalizeBaseUrl(""))
    assertNull(HaAuth.normalizeBaseUrl("ftp://192.168.1.20"))
    assertNull(HaAuth.normalizeBaseUrl("http://user:pw@192.168.1.20:8123"))
  }

  // --- HA login: redirect parsing --------------------------------------------------------------

  @Test
  fun parseCallback_acceptsCodeWithMatchingState() {
    val cb = HaAuth.parseCallback("http://immortal.portal/auth_callback?code=c0de%2Fx&state=s1&storeToken=true", "s1")
    assertEquals(HaAuth.Callback.Code("c0de/x"), cb)
  }

  @Test
  fun parseCallback_rejectsWrongOrMissingState() {
    assertTrue(HaAuth.parseCallback("http://immortal.portal/auth_callback?code=x&state=evil", "s1") is HaAuth.Callback.Failed)
    assertTrue(HaAuth.parseCallback("http://immortal.portal/auth_callback?code=x", "s1") is HaAuth.Callback.Failed)
  }

  @Test
  fun parseCallback_reportsErrorsAndMissingCode() {
    val e = HaAuth.parseCallback("http://immortal.portal/auth_callback?error=access_denied&state=s1", "s1")
    assertTrue(e is HaAuth.Callback.Failed && e.reason.contains("access_denied"))
    assertTrue(HaAuth.parseCallback("http://immortal.portal/auth_callback?state=s1", "s1") is HaAuth.Callback.Failed)
  }

  @Test
  fun parseCallback_ignoresOtherPages() {
    assertEquals(HaAuth.Callback.NotOurs, HaAuth.parseCallback("http://192.168.1.20:8123/auth/authorize?state=s1", "s1"))
    assertEquals(HaAuth.Callback.NotOurs, HaAuth.parseCallback("http://immortal.portal.evil.com/auth_callback?code=x&state=s1", "s1"))
    assertEquals(HaAuth.Callback.NotOurs, HaAuth.parseCallback("http://immortal.portal/other?code=x&state=s1", "s1"))
    assertFalse(HaAuth.isCallback(null))
    assertTrue(HaAuth.isCallback("HTTP://Immortal.Portal/auth_callback"))
  }

  @Test
  fun tokenExchange_isFormEncodedWithTheSameClientId() {
    val body = HaAuth.tokenRequestBody("a+b/c")
    val q = HaAuth.query(body)
    assertEquals("authorization_code", q["grant_type"])
    assertEquals("a+b/c", q["code"])
    assertEquals(HaAuth.CLIENT_ID, q["client_id"])
  }

  @Test
  fun parseTokenResponse() {
    val t = HaAuth.parseTokenResponse("""{"access_token":"AT","token_type":"Bearer","refresh_token":"RT","expires_in":1800}""")
    assertEquals(HaAuth.Tokens("AT", "RT"), t)
    assertNull(HaAuth.parseTokenResponse("""{"error":"invalid_request"}"""))
    assertNull(HaAuth.parseTokenResponse("<html>"))
  }

  @Test
  fun tokenName_namesThePortal() {
    assertEquals("Immortal – Kitchen", HaAuth.tokenName("Kitchen"))
    assertEquals("Immortal – Portal", HaAuth.tokenName("  "))
    assertEquals("Immortal – Kitchen (2026-10-09 12:00)", HaAuth.tokenName("Kitchen", "2026-10-09 12:00"))
  }

  // --- HA discovery (mDNS TXT) -----------------------------------------------------------------

  private fun ha(txt: Map<String, String>, addrs: List<String> = listOf("192.168.1.20"), host: String = "homeassistant.local", port: Int = 8123) =
      MuseMdns.Service("Home", "_home-assistant._tcp", host, port, addrs.toMutableSet(), txt.toMutableMap())

  @Test
  fun haDiscovery_prefersInternalUrl_andSwapsDotLocalForTheIp() {
    val s = ha(mapOf("internal_url" to "http://homeassistant.local:8123", "base_url" to "http://x.local:9", "location_name" to "Maison",
        "version" to "2026.10.1", "uuid" to "u1"))
    assertEquals(listOf(HaInstance("Maison", "http://192.168.1.20:8123", "2026.10.1", "u1")), HaDiscovery.fromMdns(listOf(s)))
  }

  @Test
  fun haDiscovery_keepsIpAndRealDnsUrls() {
    assertEquals("http://192.168.1.21:8123", HaDiscovery.baseUrlFor(ha(mapOf("internal_url" to "http://192.168.1.21:8123"))))
    assertEquals("https://ha.example.com", HaDiscovery.baseUrlFor(ha(mapOf("base_url" to "https://ha.example.com"))))
  }

  @Test
  fun haDiscovery_fallsBackToSrvPortAndIpv4() {
    assertEquals("http://192.168.1.20:8123", HaDiscovery.baseUrlFor(ha(mapOf("internal_url" to ""), addrs = listOf("fe80::1", "192.168.1.20"))))
    assertNull("a .local name alone is unreachable on Android 9/10", HaDiscovery.baseUrlFor(ha(emptyMap(), addrs = emptyList())))
  }

  @Test
  fun haDiscovery_dedupesByUuid() {
    val a = ha(mapOf("uuid" to "u1"))
    val b = ha(mapOf("uuid" to "u1"), addrs = listOf("10.0.0.5"))
    assertEquals(1, HaDiscovery.fromMdns(listOf(a, b)).size)
    assertEquals("Home", HaDiscovery.fromMdns(listOf(a)).single().name)
  }

  // --- Hilo detection --------------------------------------------------------------------------

  private val states =
      JSONArray(
          """[
            {"entity_id":"climate.salon","state":"heat","attributes":{"friendly_name":"Salon","current_temperature":20.5}},
            {"entity_id":"climate.chambre","state":"heat","attributes":{"friendly_name":"Chambre"}},
            {"entity_id":"light.cuisine","state":"on","attributes":{"friendly_name":"Cuisine"}},
            {"entity_id":"sensor.hilo_gateway","state":"online","attributes":{}},
            {"entity_id":"sensor.defi","state":"scheduled","attributes":{"attribution":"Data provided by Hilo"}},
            {"entity_id":"sensor.other","state":"1","attributes":{"friendly_name":"Other"}}
          ]""")

  @Test
  fun summary_countsThermostatsLightsAndHiloHints() {
    val s = HaSummary.of(states)
    assertEquals(2, s.thermostats)
    assertEquals(1, s.lights)
    assertEquals(2, s.hiloEntities)
    assertTrue(s.hasHilo)
    assertNull("unknown without the registry", s.hiloThermostats)
  }

  @Test
  fun summary_usesTheRegistryForRoomNamedHiloThermostats() {
    val registry = JSONArray("""[{"entity_id":"climate.salon","platform":"hilo"},{"entity_id":"light.cuisine","platform":"hue"}]""")
    val s = HaSummary.of(states, registry)
    assertEquals(1, s.hiloThermostats)
    assertEquals(3, s.hiloEntities)
  }

  @Test
  fun summary_noHilo() {
    val s = HaSummary.of(JSONArray("""[{"entity_id":"climate.ecobee","state":"heat","attributes":{"friendly_name":"Ecobee"}}]"""), JSONArray())
    assertFalse(s.hasHilo)
    assertEquals(0, s.hiloThermostats)
    assertEquals(1, s.thermostats)
  }

  // --- Hue discovery ---------------------------------------------------------------------------

  @Test
  fun hueCloud_keepsPrivateIpv4Only() {
    val list = HueDiscovery.parseCloud(
        """[{"id":"001788fffe1a2b3c","internalipaddress":"192.168.1.30","port":443},
            {"id":"x","internalipaddress":"8.8.8.8"},{"id":"y","internalipaddress":"not-an-ip"}]""")
    assertEquals(listOf(HueBridge("Hue Bridge 1A2B3C", "192.168.1.30", "001788fffe1a2b3c")), list)
    assertTrue(HueDiscovery.parseCloud("garbage").isEmpty())
  }

  @Test
  fun hueMdns_usesIpv4AndBridgeId() {
    val s = MuseMdns.Service("Hue Bridge - 1A2B3C", "_hue._tcp", "ecb5fa1a2b3c.local", 443,
        linkedSetOf("fe80::1", "192.168.1.30"), linkedMapOf("bridgeid" to "ecb5fafffe1a2b3c"))
    assertEquals(listOf(HueBridge("Hue Bridge - 1A2B3C", "192.168.1.30", "ecb5fafffe1a2b3c")), HueDiscovery.fromMdns(listOf(s)))
  }
}
