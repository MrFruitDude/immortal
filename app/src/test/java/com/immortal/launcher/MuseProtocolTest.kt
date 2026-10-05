/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The Muse gadget wire protocol, checked against fixed vectors: RFC 7748 for X25519 and the
 * public synthetic pairing vector `community_app_v5` from the Muse Gadget SDK
 * (linux/tests/vectors/link_pairing_v5.json, Apache-2.0) — P-256 scalars 1 (mobile) and 2 (device),
 * counter-pattern nonces, no real device or account data.
 */
class MuseProtocolTest {

  private fun hex(s: String) = X25519.hex(s)

  // --- X25519 (RFC 7748 §5.2 and §6.1) -------------------------------------------

  @Test
  fun x25519_rfc7748_scalarMultVector() {
    val k = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
    val u = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
    assertArrayEquals(hex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"), X25519.scalarMult(k, u))
  }

  @Test
  fun x25519_rfc7748_diffieHellman() {
    val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    val bob = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    assertArrayEquals(hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"), X25519.publicKey(alice))
    assertArrayEquals(hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"), X25519.publicKey(bob))
    val shared = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
    assertArrayEquals(shared, X25519.dh(alice, X25519.publicKey(bob)))
    assertArrayEquals(shared, X25519.dh(bob, X25519.publicKey(alice)))
  }

  @Test
  fun x25519_rejectsLowOrderPoints() {
    try {
      X25519.dh(X25519.generatePrivate(), ByteArray(32))
      fail("accepted the all-zero point")
    } catch (e: MuseProtocolException) {}
  }

  // --- Noise XX + HTTP-over-Noise envelopes --------------------------------------

  private class Pair2(val device: MuseNoiseTransport, val serverSend: NoiseCipherState, val serverRecv: NoiseCipherState)

  private fun handshake(): Pair2 {
    val init = NoiseXXInitiator()
    val resp = NoiseXXResponder()
    val m2 = resp.readMessage1WriteMessage2(init.writeMessage1())
    init.readMessage2(m2)
    resp.readMessage3(init.writeMessage3())
    val (dSend, dRecv) = init.split()
    val (sSend, sRecv) = resp.split()
    return Pair2(MuseNoiseTransport(dSend, dRecv), sSend, sRecv)
  }

  /** What the server sees: decrypt each frame, reassemble chunks, unwrap the ServiceRequest. */
  private fun serverRead(frames: List<ByteArray>, recv: NoiseCipherState): ByteArray {
    val chunks = sortedMapOf<Int, ByteArray>()
    var total = 1
    frames.forEach { f ->
      val plain = recv.decryptWithAd(ByteArray(0), f)
      var idx = 0
      var payload = ByteArray(0)
      Proto.read(plain) { field, _, v, b ->
        when (field) {
          2 -> idx = v.toInt()
          3 -> total = v.toInt()
          4 -> payload = b!!
        }
      }
      chunks[idx] = payload
    }
    assertEquals(total, chunks.size)
    val all = ByteArrayOutputStream().apply { chunks.values.forEach { write(it) } }.toByteArray()
    var serviceFrame = ByteArray(0)
    Proto.read(all) { field, _, _, b -> if (field == 2) serviceFrame = b!! }
    return serviceFrame
  }

  @Test
  fun noise_requestReachesTheServerIntact() {
    val p = handshake()
    val (id, frames) = p.device.request("POST", "/link-control", listOf("x-app-id" to "musegadget"), endBody = false)
    assertEquals(1L, id)
    val frame = serverRead(frames, p.serverRecv)
    var streamId = 0L
    var request = ByteArray(0)
    Proto.read(frame) { f, _, v, b ->
      if (f == 1) streamId = v
      if (f == 2) request = b!!
    }
    assertEquals(1L, streamId)
    val fields = HashMap<Int, Any>()
    Proto.read(request) { f, _, v, b -> fields[f] = b?.let { String(it) } ?: v }
    assertEquals("POST", fields[1])
    assertEquals("/link-control", fields[2])
    assertFalse("a streaming request must not set end_body", fields.containsKey(5))
  }

  @Test
  fun noise_largeBodiesAreChunkedAndReassembled() {
    val big = ByteArray(200_000) { (it % 251).toByte() }
    val pp = handshake()
    pp.device.bodyChunk(1, big, true).let { fr ->
      assertTrue("expected several noise frames", fr.size > 1)
      val frame = serverRead(fr, pp.serverRecv)
      var chunk = ByteArray(0)
      Proto.read(frame) { f, _, _, b -> if (f == 4) chunk = b!! }
      var data = ByteArray(0)
      var end = false
      Proto.read(chunk) { f, _, v, b ->
        if (f == 1) data = b!!
        if (f == 2) end = v != 0L
      }
      assertArrayEquals(big, data)
      assertTrue(end)
    }
  }

  @Test
  fun noise_serverFramesDecode() {
    val p = handshake()
    fun send(kind: Int, inner: ByteArray, stream: Long = 7): MuseFrame? =
        p.device.decrypt(p.serverSend.encryptWithAd(ByteArray(0),
            Proto.Writer().varint(3, 1).bytes(4, MuseNoiseTransport.encodeServerFrame(stream, kind, inner)).toByteArray()))
    val resp = send(3, Proto.Writer().varint(1, 200).bytes(3, "hi".toByteArray()).bool(4, true).toByteArray()) as MuseFrame.Response
    assertEquals(7L, resp.streamId)
    assertEquals(200, resp.status)
    assertEquals("hi", String(resp.body))
    assertTrue(resp.endBody)
    val body = send(4, Proto.Writer().bytes(1, byteArrayOf(1, 2, 3)).toByteArray()) as MuseFrame.Body
    assertArrayEquals(byteArrayOf(1, 2, 3), body.data)
    assertFalse(body.endBody)
    val reset = send(5, Proto.Writer().varint(1, 1).string(2, "bye").toByteArray()) as MuseFrame.Reset
    assertEquals("bye", reset.reason)
  }

  @Test
  fun noise_tamperedFrameIsRejected() {
    val p = handshake()
    val ct = p.serverSend.encryptWithAd(ByteArray(0), byteArrayOf(1, 2, 3))
    ct[0] = (ct[0].toInt() xor 1).toByte()
    try {
      p.device.decrypt(ct)
      fail("accepted a tampered frame")
    } catch (e: MuseProtocolException) {}
  }

  @Test
  fun controlStream_lengthPrefixedMessagesSplitAcrossChunks() {
    val buf = ByteArrayOutputStream()
    fun msg(o: JSONObject): ByteArray {
      val d = o.toString().toByteArray()
      return byteArrayOf(d.size.toByte(), (d.size shr 8).toByte(), 0, 0) + d
    }
    val a = msg(JSONObject().put("id", "1"))
    val b = msg(JSONObject().put("method", "link.invoke"))
    val keepalive = byteArrayOf(0, 0, 0, 0)
    val all = a + keepalive + b
    buf.write(all, 0, a.size + 6)
    assertEquals(listOf("1"), MuseLink.drainMessages(buf).map { it.optString("id") })
    buf.write(all, a.size + 6, all.size - a.size - 6)
    assertEquals(listOf("link.invoke"), MuseLink.drainMessages(buf).map { it.optString("method") })
    assertEquals(0, buf.size())
  }

  @Test
  fun encodeUriComponent_matchesJavaScript() {
    assertEquals("vm%20one!'()*~-_.", MuseLink.encodeUriComponent("vm one!'()*~-_.").replace("%2A", "*"))
  }

  // --- pairing v5 (community, confirm_app) ------------------------------------

  private object V {
    const val deviceId = "hatch-link:02:00:00:00:00:01"
    const val nodeId = "homelink-000001"
    const val mac = "02:00:00:00:00:01"
    const val mobilePub = "BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU"
    const val devicePub = "BHzyexiNA09-ilI4AwS1GsPAiWnid_IbNaYLSPxHZpl4B3dVENuO0EApPZrGn3Qw27p9reY86YIpngS3nSJ4c9E"
    const val mobileNonce = "AAECAwQFBgcICQoLDA0ODw"
    const val deviceNonce = "EBESExQVFhcYGRobHB0eHw"
    const val transcriptHash = "18Rs196tzddPGuj18pZ8rWObWYpwoDUIwocjnUASj_0"
    const val ecdh = "7cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978"
    const val mobileTx = "ee25e7f1eb3c05cc8465634e5f634deed858862b1c4c35fa434dc926f7facaeb"
    const val mobileRx = "9abb2954b05e2f5f7112e9bfd41fd970602e27ed07453c1cff51f575de5d0b4e"
    const val sessionId = "z0eNLGw5mczvD4a1F2ubSQ"
    const val finishedCt = "uwNX3KEkix5T6nWce3s2SKPPh-5E31x-UIHFSK6MpMUg562B"
    const val finishedTag = "zT4kU6XK-rM7rvt3VEITLg"
  }

  private var now = 0L

  private fun device(sdkToken: String? = null) =
      MusePairingSession(
          V.nodeId, V.deviceId, V.mac, "1.0.0", sdkToken,
          clock = { now },
          keyGen = { MusePairingSession.p256Private(BigInteger.valueOf(2)) to B64Url.decode(V.devicePub) },
          random = { B64Url.decode(V.deviceNonce) })

  private fun hello() =
      JSONObject()
          .put("action", "pairing_client_hello")
          .put("version", 5)
          .put("pairing_auth", "none")
          .put("pairing_policy", "confirm_app")
          .put("mobile_pub", V.mobilePub)
          .put("mobile_nonce", V.mobileNonce)

  private fun finishedRecord(counter: String = "0", ct: String = V.finishedCt) =
      JSONObject().put("type", "pairing_encrypted").put("session_id", V.sessionId).put("counter", counter)
          .put("ciphertext", ct).put("tag", V.finishedTag)

  @Test
  fun pairing_keyScheduleMatchesVector() {
    val keys = MusePairingSession.deriveSessionKeys(hex(V.ecdh), B64Url.decode(V.mobileNonce), B64Url.decode(V.deviceNonce),
        B64Url.decode(V.transcriptHash))
    assertArrayEquals(hex(V.mobileTx), keys.mobileTx)
    assertArrayEquals(hex(V.mobileRx), keys.mobileRx)
    assertEquals(V.sessionId, B64Url.encode(keys.sessionId))
  }

  @Test
  fun pairing_helloProducesTheVectorPairingReady() {
    val ready = device().handleHello(hello())
    assertEquals("pairing_ready", ready.getString("type"))
    assertEquals(V.devicePub, ready.getString("device_pub"))
    assertEquals(V.deviceNonce, ready.getString("device_nonce"))
    assertEquals(V.transcriptHash, ready.getString("transcript_hash"))
    assertEquals(V.sessionId, ready.getString("session_id"))
    assertEquals("confirm_app", ready.getString("pairing_policy"))
    assertEquals("hatch_link", ready.getString("model"))
  }

  @Test
  fun pairing_fullAppConfirmedHandshake() {
    val d = device(sdkToken = "mgst_" + "A".repeat(43))
    d.handleHello(hello())
    val plain = d.decrypt(finishedRecord())
    assertEquals("{\"action\":\"pairing_client_finished\"}", plain)
    val gen = d.handleClientFinished(JSONObject(plain))
    assertTrue(gen != 0)
    assertTrue(d.confirmed)
    // The app opens the confirmation with mobile_rx; it carries the SDK token.
    val env = d.encryptStatus("pairing_confirmed", gen)!!
    assertEquals("0", env.getString("counter"))
    val sealed = B64Url.decode(env.getString("ciphertext")) + B64Url.decode(env.getString("tag"))
    val opened = AesGcm.open(hex(V.mobileRx), MusePairingSession.recordNonce(1, 0), sealed,
        "hatch-link ble setup v1|${V.sessionId}|d2m|0".toByteArray())
    val status = JSONObject(String(opened))
    assertEquals("pairing_confirmed", status.getString("status"))
    assertEquals("mgst_" + "A".repeat(43), status.getString("sdk_token"))
    // A mobile record sealed with mobile_tx at counter 1 is accepted next.
    val scan = AesGcm.seal(hex(V.mobileTx), MusePairingSession.recordNonce(0, 1), "{\"action\":\"wifi_scan\"}".toByteArray(),
        "hatch-link ble setup v1|${V.sessionId}|m2d|1".toByteArray())
    val rec = JSONObject().put("session_id", V.sessionId).put("counter", "1")
        .put("ciphertext", B64Url.encode(scan.copyOf(scan.size - 16))).put("tag", B64Url.encode(scan.copyOfRange(scan.size - 16, scan.size)))
    assertEquals("{\"action\":\"wifi_scan\"}", d.decrypt(rec))
  }

  @Test
  fun pairing_replayedOrTamperedRecordsClearTheSession() {
    val d = device()
    d.handleHello(hello())
    d.decrypt(finishedRecord())
    try {
      d.decrypt(finishedRecord()) // replayed counter 0
      fail("accepted a replay")
    } catch (e: MusePairingException) {
      assertEquals("error_pairing_decrypt", e.status)
    }
    assertEquals(MusePairingSession.State.IDLE, d.currentState)
    val d2 = device()
    d2.handleHello(hello())
    try {
      d2.decrypt(finishedRecord(ct = V.finishedCt.replaceRange(0, 1, "v")))
      fail("accepted tampered ciphertext")
    } catch (e: MusePairingException) {}
  }

  @Test
  fun pairing_clientFinishedMustBeExactAndFirst() {
    val d = device()
    d.handleHello(hello())
    assertEquals(0, d.handleClientFinished(JSONObject().put("action", "pairing_client_finished")))
    val d2 = device()
    d2.handleHello(hello())
    d2.decrypt(finishedRecord())
    assertEquals(0, d2.handleClientFinished(JSONObject().put("action", "pairing_client_finished").put("x", 1)))
  }

  @Test
  fun pairing_timesOut() {
    val d = device()
    d.handleHello(hello())
    now += MusePairingSession.CLIENT_FINISHED_TIMEOUT_MS + 1
    try {
      d.decrypt(finishedRecord())
      fail("accepted an expired session")
    } catch (e: MusePairingException) {}
  }

  @Test
  fun pairing_rejectsOtherPoliciesAndBadKeys() {
    for (bad in listOf(hello().put("pairing_policy", "confirm_press"), hello().put("version", 4), hello().put("mobile_pub", "AAAA"))) {
      try {
        device().handleHello(bad)
        fail("accepted $bad")
      } catch (e: MusePairingException) {
        assertEquals("error_pairing_invalid_hello", e.status)
      }
    }
  }

  @Test
  fun pairing_staleGenerationCannotEncrypt() {
    val d = device()
    d.handleHello(hello())
    d.decrypt(finishedRecord())
    val gen = d.handleClientFinished(JSONObject().put("action", "pairing_client_finished"))
    assertNotNull(d.encryptStatus("x", gen))
    assertNull(d.encryptStatus("x", gen + 1))
  }

  @Test
  fun b64url_roundTripsUnpaddedAndRejectsJunk() {
    for (n in 1..40) {
      val b = ByteArray(n) { (it * 37).toByte() }
      assertArrayEquals(b, B64Url.decode(B64Url.encode(b)))
      assertArrayEquals(b, B64.decode(B64.encode(b)))
    }
    assertFalse(B64Url.encode(ByteArray(4)).contains("="))
    for (bad in listOf("", "A", "ab+c", "ab=c")) {
      try {
        B64Url.decode(bad)
        fail("accepted '$bad'")
      } catch (e: IllegalArgumentException) {}
    }
    assertEquals("AQID", B64.encode(byteArrayOf(1, 2, 3)))
    assertEquals("AQ==", B64.encode(byteArrayOf(1)))
  }

  // --- BLE framing --------------------------------------------------------------

  @Test
  fun bleFraming_roundTripsAtSmallMtu() {
    val msg = ByteArray(1000) { it.toByte() }
    val packets = MuseBleFraming.encode(msg, mtu = 23)
    assertTrue(packets.all { it.size <= 20 })
    val a = MuseBleFraming.Assembler()
    val out = packets.mapNotNull { a.feed(it) }
    assertEquals(1, out.size)
    assertArrayEquals(msg, out[0])
  }

  @Test
  fun bleFraming_outOfOrderDiscardsAndPlainWritesPassThrough() {
    val packets = MuseBleFraming.encode(ByteArray(300), mtu = 100)
    val a = MuseBleFraming.Assembler()
    assertNull(a.feed(packets[0]))
    assertNull(a.feed(packets[2])) // skipped one
    assertNull(a.feed(packets[1]))
    assertArrayEquals("{}".toByteArray(), a.feed("{}".toByteArray()))
  }

  @Test
  fun setup_emptyWritesDontStopTheWorker() {
    val sent = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    val transport = object : MuseSetupController.Transport {
      override fun sendPackets(packets: List<ByteArray>) = packets.forEach { sent.put(it) }
      override fun mtu() = 185
      override fun disconnect(delayMs: Long) = Unit
    }
    val net = object : MuseSetupController.Network {
      override fun isOnline() = true
      override fun currentConnectionEntry() = JSONObject()
    }
    val c = MuseSetupController(device(), MuseIdentity(V.mac), "1.0.0", transport, net, { _, _ -> })
    c.start()
    c.onWrite(ByteArray(0)) // a stray 0-byte write
    c.onWrite(byteArrayOf(0xFE.toByte(), 0, 1)) // a framed empty message
    c.onWrite("{\"action\":\"get_device_info\"}".toByteArray())
    val reply = sent.poll(5, java.util.concurrent.TimeUnit.SECONDS)
    assertNotNull("the worker must still answer after empty writes", reply)
    c.stop()
  }

  // --- identity -----------------------------------------------------------------

  @Test
  fun identity_namesShareTheSuffix() {
    val id = MuseIdentity.generate { ByteArray(it) { i -> (0xA0 + i).toByte() } }
    assertEquals("a2:a1:a2:a3:a4:a5", id.mac) // first octet forced locally-administered unicast
    assertEquals("homelink-a3a4a5", id.nodeId)
    assertEquals("MuseGadgetA3A4A5", id.bleName)
    assertEquals("hatch-link:a2:a1:a2:a3:a4:a5", id.deviceId)
  }

  @Test
  fun sdkToken_shapeIsChecked() {
    assertTrue(MuseConfig.isValidSdkToken("mgst_" + "A".repeat(43)))
    assertFalse(MuseConfig.isValidSdkToken("mgst_" + "A".repeat(42) + "B")) // non-canonical last char
    assertFalse(MuseConfig.isValidSdkToken("mgst_short"))
  }
}
