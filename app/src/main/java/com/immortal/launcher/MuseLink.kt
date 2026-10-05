/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.Build
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.json.JSONArray
import org.json.JSONObject

/** A minimal RFC 6455 client for the Noise channel: TLS, a bearer on the upgrade, binary frames. */
class MuseWebSocket private constructor(private val socket: Socket) {
  private val inp: InputStream = BufferedInputStream(socket.getInputStream(), 64 * 1024)
  private val out: OutputStream = socket.getOutputStream()
  private val writeLock = Any()
  private val rnd = SecureRandom()
  @Volatile var lastRxAt = System.currentTimeMillis()
    private set

  /** Non-101 answer to the upgrade; 401/403 mean "fetch fresh VM credentials". */
  class UpgradeRejected(val status: Int) : IOException("upgrade rejected: HTTP $status")

  fun sendBinary(payload: ByteArray) = sendFrame(0x82, payload)

  fun ping() = sendFrame(0x89, ByteArray(0))

  /** The next binary message, or null when the connection closed. Answers pings itself. */
  fun readBinary(): ByteArray? {
    val msg = ByteArrayOutputStream()
    while (true) {
      val b0 = inp.read()
      if (b0 < 0) return null
      val b1 = inp.read()
      if (b1 < 0) return null
      lastRxAt = System.currentTimeMillis()
      val fin = b0 and 0x80 != 0
      val opcode = b0 and 0x0f
      var len = (b1 and 0x7f).toLong()
      if (len == 126L) len = ((readByte() shl 8) or readByte()).toLong()
      else if (len == 127L) {
        len = 0
        repeat(8) { len = (len shl 8) or readByte().toLong() }
      }
      if (len > MAX_MESSAGE) throw IOException("frame too large: $len")
      val mask = if (b1 and 0x80 != 0) ByteArray(4) { readByte().toByte() } else null
      val payload = ByteArray(len.toInt())
      var read = 0
      while (read < payload.size) {
        val r = inp.read(payload, read, payload.size - read)
        if (r < 0) return null
        read += r
      }
      mask?.let { m -> for (i in payload.indices) payload[i] = (payload[i].toInt() xor m[i % 4].toInt()).toByte() }
      when (opcode) {
        0x1 -> msg.reset() // text frames carry nothing on the Noise channel; the SDK ignores them
        0x0, 0x2 -> {
          msg.write(payload)
          if (msg.size() > MAX_MESSAGE) throw IOException("message too large")
          if (fin) return msg.toByteArray()
        }
        0x9 -> sendFrame(0x8A, payload)
        0x8 -> return null
      }
    }
  }

  /**
   * Closes the TCP connection without touching the write lock, so it never waits behind a send
   * stalled on a full socket buffer (and is safe from the main thread). No close frame: the
   * server treats a dropped connection the same.
   */
  fun close() {
    runCatching { socket.close() }
  }

  private fun readByte(): Int {
    val b = inp.read()
    if (b < 0) throw IOException("eof")
    return b
  }

  private fun sendFrame(first: Int, payload: ByteArray) {
    val len = payload.size
    val header = ByteArrayOutputStream(14)
    header.write(first)
    when {
      len < 126 -> header.write(0x80 or len)
      len < 65536 -> {
        header.write(0x80 or 126)
        header.write(len ushr 8)
        header.write(len and 0xff)
      }
      else -> {
        header.write(0x80 or 127)
        for (s in 7 downTo 0) header.write(((len.toLong() ushr (8 * s)) and 0xff).toInt())
      }
    }
    val mask = ByteArray(4).also { rnd.nextBytes(it) }
    header.write(mask)
    val masked = ByteArray(len) { (payload[it].toInt() xor mask[it and 3].toInt()).toByte() }
    synchronized(writeLock) {
      out.write(header.toByteArray())
      out.write(masked)
      out.flush()
    }
  }

  companion object {
    const val MAX_MESSAGE = 8L * 1024 * 1024

    fun connect(url: String, headers: Map<String, String>, timeoutMs: Int = 20_000): MuseWebSocket {
      val u = URL(url.replaceFirst("wss://", "https://"))
      val host = u.host
      val port = if (u.port > 0) u.port else 443
      val raw = Socket()
      try {
        return upgrade(raw, u, host, port, headers, timeoutMs)
      } catch (e: Exception) {
        runCatching { raw.close() }
        throw e
      }
    }

    private fun upgrade(raw: Socket, u: URL, host: String, port: Int, headers: Map<String, String>, timeoutMs: Int): MuseWebSocket {
      raw.connect(InetSocketAddress(host, port), timeoutMs)
      val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
      ssl.soTimeout = timeoutMs
      ssl.startHandshake()
      if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
        ssl.close()
        throw IOException("TLS hostname mismatch for $host")
      }
      val key = B64.encode(ByteArray(16).also { SecureRandom().nextBytes(it) })
      val req = StringBuilder()
          .append("GET ${u.file} HTTP/1.1\r\n")
          .append("Host: $host\r\n")
          .append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
          .append("Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n")
      headers.forEach { (k, v) -> req.append("$k: $v\r\n") }
      req.append("\r\n")
      ssl.outputStream.write(req.toString().toByteArray(Charsets.ISO_8859_1))
      ssl.outputStream.flush()
      val ws = MuseWebSocket(ssl)
      val status = ws.readHttpStatus()
      if (status != 101) {
        ssl.close()
        throw UpgradeRejected(status)
      }
      ssl.soTimeout = 0 // the session idles for long stretches; liveness is the ping timer's job
      return ws
    }
  }

  /** Reads the upgrade response's headers; returns its status code (0 if unreadable). */
  private fun readHttpStatus(): Int {
    var status = 0
    val line = StringBuilder()
    while (true) {
      val b = inp.read()
      if (b < 0) return status
      if (b != '\n'.code) {
        line.append(b.toChar())
        continue
      }
      val l = line.toString().trimEnd('\r')
      line.setLength(0)
      if (l.isEmpty()) return status // end of headers
      if (status == 0) status = l.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
    }
  }
}

/** The Muse device API: leased VM lookup and device-token rotation. */
object MuseApi {
  private const val TAG = "MuseApi"
  const val API_BASE = "https://api.muse.ai"
  const val DEFAULT_NOISE_HOST = "hatch.metaaivm.com"

  data class Vm(val url: String, val token: String, val name: String, val id: String, val isDefault: Boolean)

  fun apiRoot(apiUrlV2: String) = apiUrlV2.takeIf { it.startsWith("https://") }?.trimEnd('/') ?: API_BASE

  fun userAgent(): String =
      "immortal-muse/${BuildConfigCompat.versionName} (Meta Portal ${Build.MODEL}; Android ${Build.VERSION.RELEASE})"

  /** (vms, httpStatus) — status null = transport failure worth retrying; 401 = token rejected. */
  fun fetchVms(accessToken: String, root: String): Pair<List<Vm>, Int?> {
    val conn = (URL("$root/fetch_vms").openConnection() as HttpURLConnection)
    return try {
      conn.connectTimeout = 15_000
      conn.readTimeout = 15_000
      conn.setRequestProperty("Authorization", "Bearer $accessToken")
      conn.setRequestProperty("X-API-Version", "1.0.0")
      conn.setRequestProperty("User-Agent", userAgent())
      val status = conn.responseCode
      if (status !in 200..299) {
        Log.w(TAG, "VM fetch failed: HTTP $status")
        return emptyList<Vm>() to status
      }
      val data = JSONObject(conn.inputStream.bufferedReader().readText())
      if (data.optString("error_title").isNotEmpty() || data.has("backend_error_code")) {
        Log.w(TAG, "VM fetch error: ${data.optString("error_title")}")
        return emptyList<Vm>() to status
      }
      val list = data.optJSONArray("vm_list") ?: JSONArray()
      val vms = (0 until list.length()).mapNotNull { i ->
        val e = list.optJSONObject(i) ?: return@mapNotNull null
        val url = e.optString("vm_ws_url").ifEmpty { e.optString("vm_url") }
        val token = e.optString("vm_auth_token")
        if (url.isEmpty() || token.isEmpty()) null
        else Vm(url, token, e.optString("vm_name"), e.optString("vm_id"), e.optBoolean("default", false))
      }
      Log.i(TAG, "VM fetch: ${vms.size} VMs")
      vms to status
    } catch (e: Exception) {
      Log.w(TAG, "VM fetch failed: $e")
      emptyList<Vm>() to null
    } finally {
      conn.disconnect()
    }
  }

  /**
   * Rotates the device token pair with the refresh token. Like the SDK, the access token is never
   * presented here (the server can answer 200 with tokens every endpoint then rejects).
   */
  fun refreshDeviceToken(refreshToken: String, deviceId: String, root: String, sdkToken: String?): Pair<Pair<String, String>?, Int?> {
    val raw = refreshToken.substringAfterLast(':')
    val body = JSONObject().put("device_id", deviceId)
    if (sdkToken != null) body.put("sdk_token", sdkToken)
    val conn = URL("$root/device_token/refresh").openConnection() as HttpURLConnection
    return try {
      conn.connectTimeout = 15_000
      conn.readTimeout = 15_000
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.setRequestProperty("Authorization", "Bearer hatch_refresh:$raw")
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty("User-Agent", userAgent())
      conn.outputStream.use { it.write(body.toString().toByteArray()) }
      val status = conn.responseCode
      if (status !in 200..299) return null to status
      var data = JSONObject(conn.inputStream.bufferedReader().readText())
      data.optJSONObject("payload")?.let { data = it }
      val a = data.optString("access_token")
      val r = data.optString("refresh_token")
      if (a.isEmpty() || r.isEmpty()) null to status else (a to r) to status
    } catch (e: Exception) {
      Log.w(TAG, "token refresh failed: $e")
      null to null
    } finally {
      conn.disconnect()
    }
  }
}

/** Version string without depending on the generated BuildConfig (not enabled in this module). */
internal object BuildConfigCompat {
  @Volatile var versionName: String = "dev"
}

/**
 * One control session with a Muse VM: WebSocket `/v1/noise` → Noise XX → a long-lived
 * `POST /link-control` stream carrying length-prefixed JSON both ways (`link.register`,
 * `link.invoke` → `link.result`, `link.unpaired`). Other requests (chat, voice notes, the reply
 * subscription) multiplex onto the same session as their own streams via [openRequest].
 */
class MuseLink(
    private val noiseHost: String,
    private val vmId: String,
    private val vmToken: String,
    private val register: JSONObject,
    private val runCommand: (String, JSONObject, Long?) -> JSONObject,
) {
  enum class Outcome { CLOSED, AUTH_REJECTED, FORBIDDEN, UNPAIRED, STOPPED }

  /** Receives the frames of one device-opened request stream, on the session's reader thread. */
  fun interface StreamHandler {
    fun onFrame(frame: MuseFrame)
  }

  private val stopped = AtomicBoolean(false)
  @Volatile private var ws: MuseWebSocket? = null
  @Volatile private var transport: MuseNoiseTransport? = null
  private var controlStream = 0L
  private var registerId = ""
  private val handlers = ConcurrentHashMap<Long, StreamHandler>()
  private val invokes = Executors.newFixedThreadPool(MAX_CONCURRENT_INVOKES)
  private val sendLock = Any()
  @Volatile var registeredAt = 0L
    private set

  val isRegistered: Boolean
    get() = registeredAt != 0L && !stopped.get()

  fun stop() {
    stopped.set(true)
    ws?.close()
  }

  /** Blocks until the session ends. */
  fun run(): Outcome {
    val url = "wss://$noiseHost/v1/noise?vm_id=${encodeUriComponent(vmId)}"
    val socket =
        try {
          MuseWebSocket.connect(url, mapOf("Authorization" to "Bearer $vmToken", "User-Agent" to MuseApi.userAgent()))
        } catch (e: MuseWebSocket.UpgradeRejected) {
          Log.w(TAG, "VM refused connection: HTTP ${e.status}")
          return if (e.status == 401) Outcome.AUTH_REJECTED else if (e.status == 403) Outcome.FORBIDDEN else Outcome.CLOSED
        }
    ws = socket
    val pinger = Thread({ pingLoop(socket) }, "muse-ping").apply { isDaemon = true }
    try {
      if (stopped.get()) return Outcome.STOPPED
      transport = handshake(socket)
      Log.i(TAG, "Noise session established")
      pinger.start()
      openControl()
      return readLoop(socket)
    } finally {
      stopped.set(true)
      pinger.interrupt()
      invokes.shutdownNow()
      handlers.values.forEach { h -> runCatching { h.onFrame(MuseFrame.Reset(0, 0, "session ended")) } }
      handlers.clear()
      socket.close()
    }
  }

  private fun handshake(socket: MuseWebSocket): MuseNoiseTransport {
    val init = NoiseXXInitiator()
    socket.sendBinary(init.writeMessage1())
    val msg2 = socket.readBinary() ?: throw IOException("closed during Noise handshake")
    init.readMessage2(msg2)
    // The bearer authenticated us at the upgrade; message 3 carries an empty payload.
    socket.sendBinary(init.writeMessage3())
    val (send, recv) = init.split()
    return MuseNoiseTransport(send, recv)
  }

  private fun openControl() {
    val t = transport!!
    synchronized(sendLock) {
      val (id, frames) = t.request("POST", "/link-control", endBody = false)
      controlStream = id
      frames.forEach { ws!!.sendBinary(it) }
    }
    registerId = UUID.randomUUID().toString()
    sendControl(JSONObject().put("type", "req").put("id", registerId).put("method", "link.register").put("params", register))
    Log.i(TAG, "sent link.register as ${register.optString("node_id")}")
  }

  /** Sends one JSON message on the control stream. */
  fun sendControl(message: JSONObject) {
    val data = message.toString().toByteArray(Charsets.UTF_8)
    val framed = ByteArray(4 + data.size)
    framed[0] = data.size.toByte()
    framed[1] = (data.size ushr 8).toByte()
    framed[2] = (data.size ushr 16).toByte()
    framed[3] = (data.size ushr 24).toByte()
    System.arraycopy(data, 0, framed, 4, data.size)
    synchronized(sendLock) { transport!!.bodyChunk(controlStream, framed).forEach { ws!!.sendBinary(it) } }
  }

  /** Opens a request stream on this session; returns its id (0 if the session is down). */
  fun openRequest(
      verb: String,
      path: String,
      headers: List<Pair<String, String>>,
      body: ByteArray,
      endBody: Boolean,
      handler: StreamHandler,
  ): Long {
    val t = transport ?: return 0
    val socket = ws ?: return 0
    if (stopped.get()) return 0
    return try {
      synchronized(sendLock) {
        val (id, frames) = t.request(verb, path, headers, body, endBody)
        handlers[id] = handler
        frames.forEach { socket.sendBinary(it) }
        id
      }
    } catch (e: IOException) {
      Log.w(TAG, "open $path failed: $e")
      0
    }
  }

  fun sendBody(streamId: Long, data: ByteArray, endBody: Boolean): Boolean {
    val t = transport ?: return false
    val socket = ws ?: return false
    return try {
      synchronized(sendLock) { t.bodyChunk(streamId, data, endBody).forEach { socket.sendBinary(it) } }
      true
    } catch (e: IOException) {
      false
    }
  }

  fun cancel(streamId: Long) {
    handlers.remove(streamId) ?: return
    val t = transport ?: return
    runCatching { synchronized(sendLock) { t.reset(streamId).forEach { ws?.sendBinary(it) } } }
  }

  fun closeStream(streamId: Long) {
    handlers.remove(streamId)
  }

  private fun readLoop(socket: MuseWebSocket): Outcome {
    val buf = ByteArrayOutputStream()
    while (!stopped.get()) {
      val raw =
          try {
            socket.readBinary()
          } catch (e: IOException) {
            null
          } ?: return if (stopped.get()) Outcome.STOPPED else Outcome.CLOSED.also { Log.i(TAG, "control connection closed") }
      val frame = transport!!.decrypt(raw) ?: continue
      if (frame.streamId != controlStream) {
        handlers[frame.streamId]?.let { h -> runCatching { h.onFrame(frame) }.onFailure { Log.w(TAG, "stream handler", it) } }
        val ended = (frame is MuseFrame.Reset) || (frame is MuseFrame.Response && frame.endBody) || (frame is MuseFrame.Body && frame.endBody)
        if (ended) handlers.remove(frame.streamId)
        continue
      }
      val (data, ended) =
          when (frame) {
            is MuseFrame.Reset -> {
              Log.w(TAG, "control stream reset: ${frame.reason}")
              return Outcome.CLOSED
            }
            is MuseFrame.Response -> {
              if (frame.status >= 400) {
                Log.w(TAG, "/link-control refused: HTTP ${frame.status}")
                return if (frame.status == 403) Outcome.FORBIDDEN else Outcome.CLOSED
              }
              frame.body to frame.endBody
            }
            is MuseFrame.Body -> frame.data to frame.endBody
          }
      buf.write(data)
      for (message in drainMessages(buf)) handle(message)?.let { return it }
      if (ended) {
        Log.i(TAG, "control stream ended by VM")
        return Outcome.CLOSED
      }
    }
    return Outcome.STOPPED
  }

  private fun handle(message: JSONObject): Outcome? {
    if (message.optString("id") == registerId && !message.has("method")) {
      if (message.has("error")) Log.e(TAG, "link.register rejected: ${message.opt("error")}")
      else {
        registeredAt = System.currentTimeMillis()
        Log.i(TAG, "registered with the Muse")
      }
      return null
    }
    val event = message.optString("event")
    if (event == "link.unpaired" || event == "node.unpaired") {
      Log.w(TAG, "the Muse removed this device")
      return Outcome.UNPAIRED
    }
    if (message.optString("method") == "link.invoke") {
      val id = message.optString("id")
      if (id.isEmpty()) return null
      val command = message.optString("command")
      val params = message.optJSONObject("params") ?: JSONObject()
      val timeout = message.optLong("timeout_ms", 0L).takeIf { it > 0 }
      Log.i(TAG, "invoke $command")
      runCatching {
        // A fixed pool of MAX_CONCURRENT_INVOKES: further invokes queue, as in the SDK.
        invokes.execute {
          val result =
              runCatching { runCommand(command, params, timeout) }
                  .getOrElse { JSONObject().put("ok", false).put("error", "${it.javaClass.simpleName}: ${it.message}") }
          val reply = JSONObject().put("method", "link.result").put("id", id)
          result.keys().forEach { reply.put(it, result.get(it)) }
          if (!stopped.get()) runCatching { sendControl(reply) }
        }
      }
    }
    return null
  }

  private fun pingLoop(socket: MuseWebSocket) {
    try {
      while (!stopped.get()) {
        Thread.sleep(PING_INTERVAL_MS)
        if (System.currentTimeMillis() - socket.lastRxAt > DEAD_AFTER_MS) {
          Log.w(TAG, "no traffic from the VM; dropping the session")
          socket.close()
          return
        }
        socket.ping()
      }
    } catch (_: InterruptedException) {
    } catch (e: IOException) {
      socket.close()
    }
  }

  companion object {
    private const val TAG = "MuseLink"
    const val MAX_CONCURRENT_INVOKES = 4
    const val MAX_INBOUND_MESSAGE = 4 * 1024 * 1024
    const val PING_INTERVAL_MS = 20_000L
    const val DEAD_AFTER_MS = 60_000L

    /** Splits the control stream into little-endian-u32-length-prefixed JSON messages. */
    internal fun drainMessages(buf: ByteArrayOutputStream): List<JSONObject> {
      val bytes = buf.toByteArray()
      val out = ArrayList<JSONObject>()
      var off = 0
      while (bytes.size - off >= 4) {
        val len = (bytes[off].toInt() and 0xff) or ((bytes[off + 1].toInt() and 0xff) shl 8) or
            ((bytes[off + 2].toInt() and 0xff) shl 16) or ((bytes[off + 3].toInt() and 0xff) shl 24)
        if (len < 0 || len > MAX_INBOUND_MESSAGE) throw MuseProtocolException("inbound message too large: $len")
        if (bytes.size - off - 4 < len) break
        val raw = String(bytes, off + 4, len, Charsets.UTF_8)
        off += 4 + len
        if (raw.isEmpty()) continue // keepalive
        runCatching { JSONObject(raw) }.getOrNull()?.let { out += it }
      }
      buf.reset()
      buf.write(bytes, off, bytes.size - off)
      return out
    }

    /** JavaScript's encodeURIComponent, as the firmware does. */
    fun encodeUriComponent(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%21", "!").replace("%27", "'")
            .replace("%28", "(").replace("%29", ")").replace("%7E", "~")
  }
}
