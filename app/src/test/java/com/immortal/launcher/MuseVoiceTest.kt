/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MuseVoiceTest {

  private fun event(seq: Int, event: String, payload: JSONObject) =
      JSONObject().put("type", "event").put("seq", seq).put("event", event).put("payload", payload)

  @Test
  fun replies_bindToOurNoteAndIgnoreOtherChats() {
    val t = MuseReplyTracker()
    t.onAck("""{"result":{"message_id":"u1","reply_to_message_id":"p0"}}""")
    // Someone else's chat on the same subscription: parented to a message that isn't ours.
    assertNull(t.onLine(event(1, "delta.message_start", JSONObject().put("message_id", "x").put("reply_to_message_id", "other"))))
    assertTrue(t.messages.isEmpty())
    t.onLine(event(2, "delta.message_start", JSONObject().put("message_id", "a1").put("reply_to_message_id", "u1")))
    t.onLine(event(3, "delta.text_append", JSONObject().put("message_id", "a1").put("text", "Hello ")))
    t.onLine(event(4, "delta.text_append", JSONObject().put("message_id", "a1").put("text", "there")))
    assertFalse(t.allDone())
    val done = t.onLine(event(5, "delta.message_done", JSONObject().put("message_id", "a1")))
    assertNotNull(done)
    assertEquals("Hello there", done!!.text.toString())
    assertTrue(t.allDone())
  }

  @Test
  fun replies_dropReplayedSequenceNumbers() {
    val t = MuseReplyTracker()
    t.onLine(event(5, "delta.text_append", JSONObject().put("message_id", "a").put("text", "x")))
    t.onLine(event(5, "delta.text_append", JSONObject().put("message_id", "a").put("text", "x")))
    t.onLine(event(4, "delta.text_append", JSONObject().put("message_id", "a").put("text", "x")))
    assertEquals("x", t.messages["a"]!!.text.toString())
  }

  @Test
  fun replies_fullMessageWaitsForDisplayTextAndBusyIsTracked() {
    val t = MuseReplyTracker()
    t.onLine(event(1, "agent.status", JSONObject().put("activity_code", "thinking")))
    assertTrue(t.agentBusy)
    assertNull(t.onLine(event(2, "message.assistant", JSONObject().put("id", "m").put("display_text_ready", false))))
    val m = t.onLine(event(3, "message.assistant", JSONObject().put("id", "m").put("display_text", "Done.")))
    assertEquals("Done.", m!!.text.toString())
    t.onLine(event(4, "agent.status", JSONObject().put("activity_code", "idle")))
    assertFalse(t.agentBusy)
  }

  @Test
  fun voiceNote_isOneValidJsonBodyWithAStreamingWav() {
    val pcm = ByteArray(16_001) { (it * 7).toByte() } // odd length: exercises the 3-byte staging
    val head = MuseVoiceTurn.noteHead("homelink-abcdef")
    // Mirror the turn's chunking: whole 3-byte groups mid-stream, the remainder (padded) last.
    val all = MuseVoiceTurn.wavHeader(16_000) + pcm
    val sb = StringBuilder(head)
    var off = 0
    while (all.size - off > 6144) {
      val take = 6144 - 6144 % 3
      sb.append(B64.encode(all.copyOfRange(off, off + take)))
      off += take
    }
    sb.append(B64.encode(all.copyOfRange(off, all.size))).append(MuseVoiceTurn.NOTE_TAIL)
    val body = JSONObject(sb.toString())
    assertEquals("homelink-abcdef", body.getString("device_id"))
    val item = body.getJSONArray("items").getJSONObject(0)
    assertEquals("audio/wav", item.getString("mime_type"))
    val wav = B64.decode(item.getString("data_base64"))
    assertEquals("RIFF", String(wav, 0, 4))
    assertEquals(44 + pcm.size, wav.size)
    assertEquals(16_000, (wav[24].toInt() and 0xff) or ((wav[25].toInt() and 0xff) shl 8))
  }

  @Test
  fun speakable_dropsMarkdownAndLinks() {
    assertEquals("See the docs for more", MuseVoiceTurn.speakable("See **the [docs](https://x.y/z)** for more"))
    assertEquals("Run it", MuseVoiceTurn.speakable("Run it\n```\nrm -rf /\n```"))
  }

  // --- mDNS ----------------------------------------------------------------------

  private fun name(d: DataOutputStream, n: String) {
    n.split('.').forEach { l ->
      d.writeByte(l.length)
      d.write(l.toByteArray())
    }
    d.writeByte(0)
  }

  @Test
  fun mdns_parsesPtrSrvTxtAndAddresses() {
    val out = ByteArrayOutputStream()
    val d = DataOutputStream(out)
    d.writeShort(0); d.writeShort(0x8400); d.writeShort(0); d.writeShort(1); d.writeShort(0); d.writeShort(3)
    fun rr(owner: String, type: Int, rdata: ByteArray) {
      name(d, owner); d.writeShort(type); d.writeShort(1); d.writeInt(120); d.writeShort(rdata.size); d.write(rdata)
    }
    val inst = "Kitchen._googlecast._tcp.local"
    rr("_googlecast._tcp.local", 12, ByteArrayOutputStream().also { name(DataOutputStream(it), inst) }.toByteArray())
    rr(inst, 33, ByteArrayOutputStream().also {
      val s = DataOutputStream(it); s.writeShort(0); s.writeShort(0); s.writeShort(8009); name(s, "kitchen.local")
    }.toByteArray())
    rr(inst, 16, byteArrayOf(7) + "fn=Kitch".toByteArray().copyOf(7) + byteArrayOf(5) + "md=GH".toByteArray())
    rr("kitchen.local", 1, InetAddress.getByName("10.0.0.84").address)
    val services = LinkedHashMap<String, MuseMdns.Service>()
    val hosts = HashMap<String, MutableSet<String>>()
    MuseMdns.parse(out.toByteArray(), listOf("_googlecast._tcp.local"), services, hosts)
    val s = services.values.single()
    assertEquals("Kitchen", s.instance)
    assertEquals("_googlecast._tcp", s.type)
    assertEquals(8009, s.port)
    assertEquals("kitchen.local", s.host)
    assertEquals("Kitc", s.txt["fn"])
    assertEquals("GH", s.txt["md"])
    assertEquals(setOf("10.0.0.84"), hosts["kitchen.local"])
  }

  @Test
  fun lan_onlyPrivateAddressesCount() {
    assertTrue(isPrivateLan(InetAddress.getByName("10.0.0.84")))
    assertTrue(isPrivateLan(InetAddress.getByName("192.168.1.2")))
    assertTrue(isPrivateLan(InetAddress.getByName("172.20.0.1")))
    assertFalse(isPrivateLan(InetAddress.getByName("172.32.0.1")))
    assertFalse(isPrivateLan(InetAddress.getByName("8.8.8.8")))
    assertFalse(isPrivateLan(InetAddress.getByName("127.0.0.1")))
  }
}
