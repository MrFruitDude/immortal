/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A minimal RFC 6455 WebSocket text client over a raw socket — just enough to talk to
 * Music Assistant's JSON WS API (auth + transport commands) without adding a WebSocket
 * dependency, consistent with the launcher's raw-socket networking elsewhere
 * ([SnapcastControlClient], [MultiRoomArt]). Text frames only; replies to pings.
 * Blocking — call off the main thread.
 */
class MaWebSocket(
    private val host: String,
    private val port: Int,
    private val path: String = "/ws",
    /** wss:// — TLS over the same socket (Home Assistant behind https). Off for Music Assistant. */
    private val secure: Boolean = false,
) {
  private var socket: Socket? = null
  private var inp: InputStream? = null
  private var out: OutputStream? = null
  private val rnd = java.security.SecureRandom()

  fun connect(timeoutMs: Int = 6000): Boolean {
    val plain = Socket()
    plain.connect(InetSocketAddress(host, port), timeoutMs)
    val s =
        if (secure) {
          val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
          tls.startHandshake()
          // A raw SSLSocket checks the chain but not the name; credentials go over this socket.
          if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session)) {
            tls.close()
            throw SSLPeerUnverifiedException("certificate doesn't match $host")
          }
          tls
        } else plain
    s.soTimeout = timeoutMs
    socket = s
    inp = s.getInputStream()
    out = s.getOutputStream()
    val key = Base64.encodeToString(ByteArray(16).also { rnd.nextBytes(it) }, Base64.NO_WRAP)
    val req =
        "GET $path HTTP/1.1\r\n" +
            "Host: $host:$port\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n"
    out!!.write(req.toByteArray(Charsets.ISO_8859_1))
    out!!.flush()
    return (readHandshakeStatus() ?: "").contains(" 101 ")
  }

  private fun readHandshakeStatus(): String? {
    val i = inp ?: return null
    val buf = ByteArrayOutputStream()
    while (true) {
      val b = i.read()
      if (b < 0) break
      buf.write(b)
      val a = buf.toByteArray()
      if (a.size >= 4 &&
          a[a.size - 4] == CR && a[a.size - 3] == LF && a[a.size - 2] == CR && a[a.size - 1] == LF)
          break
    }
    return String(buf.toByteArray(), Charsets.ISO_8859_1).lineSequence().firstOrNull()
  }

  /** Send a masked text frame (client→server frames MUST be masked). */
  fun sendText(text: String) {
    val o = out ?: return
    val payload = text.toByteArray(Charsets.UTF_8)
    val f = ByteArrayOutputStream()
    f.write(0x81) // FIN + opcode 0x1 (text)
    val len = payload.size
    when {
      len < 126 -> f.write(0x80 or len)
      len < 65536 -> {
        f.write(0x80 or 126)
        f.write((len ushr 8) and 0xff)
        f.write(len and 0xff)
      }
      else -> {
        f.write(0x80 or 127)
        for (shift in 7 downTo 0) f.write(((len.toLong() ushr (8 * shift)) and 0xff).toInt())
      }
    }
    val mask = ByteArray(4).also { rnd.nextBytes(it) }
    f.write(mask)
    val masked = ByteArray(len) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
    f.write(masked)
    o.write(f.toByteArray())
    o.flush()
  }

  /** Read one complete text message, answering pings and stopping on close. */
  fun readText(): String? {
    val i = inp ?: return null
    val msg = ByteArrayOutputStream()
    while (true) {
      val b0 = i.read()
      if (b0 < 0) return null
      val fin = (b0 and 0x80) != 0
      val opcode = b0 and 0x0f
      val b1 = i.read()
      if (b1 < 0) return null
      val masked = (b1 and 0x80) != 0
      var len = (b1 and 0x7f).toLong()
      if (len == 126L) len = ((i.read() and 0xff).toLong() shl 8) or (i.read() and 0xff).toLong()
      else if (len == 127L) {
        len = 0
        repeat(8) { len = (len shl 8) or (i.read() and 0xff).toLong() }
      }
      val maskKey = if (masked) ByteArray(4) { i.read().toByte() } else null
      val payload = ByteArray(len.toInt())
      var read = 0
      while (read < payload.size) {
        val r = i.read(payload, read, payload.size - read)
        if (r < 0) return null
        read += r
      }
      maskKey?.let { mk -> for (k in payload.indices) payload[k] = (payload[k].toInt() xor mk[k % 4].toInt()).toByte() }
      when (opcode) {
        0x1, 0x0 -> {
          msg.write(payload)
          if (fin) return String(msg.toByteArray(), Charsets.UTF_8)
        }
        0x9 -> sendControl(0x8A, payload) // ping → pong
        0x8 -> return null // close
      // 0xA pong: ignore
      }
    }
  }

  private fun sendControl(firstByte: Int, payload: ByteArray) {
    val o = out ?: return
    val f = ByteArrayOutputStream()
    f.write(firstByte)
    f.write(0x80 or (payload.size and 0x7f)) // control payloads are < 126
    val mask = ByteArray(4).also { rnd.nextBytes(it) }
    f.write(mask)
    val masked = ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
    f.write(masked)
    o.write(f.toByteArray())
    o.flush()
  }

  fun close() {
    runCatching { socket?.close() }
  }

  private companion object {
    const val CR = '\r'.code.toByte()
    const val LF = '\n'.code.toByte()
  }
}
