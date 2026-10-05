/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.json.JSONArray
import org.json.JSONObject

/*
 * The home-network reach Muse gets through a Portal. The ESP32 Home Link tunnels raw IP for the
 * VM; an unrooted Portal can't, so instead it offers the three things Muse's community device
 * skills actually need, each confined to the private LAN:
 *   - mDNS/DNS-SD discovery ([MuseMdns]),
 *   - HTTP to RFC 1918 / link-local hosts ([MuseLanHttp]),
 *   - Google Cast receiver control ([MuseCast]) — Nest/Google Home speakers, displays and groups.
 */

/** Is [addr] on the home network (and not this device's loopback)? */
internal fun isPrivateLan(addr: InetAddress): Boolean =
    when (addr) {
      is Inet4Address -> {
        val b = addr.address.map { it.toInt() and 0xff }
        b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254)
      }
      is Inet6Address -> addr.isLinkLocalAddress || addr.isSiteLocalAddress || ((addr.address[0].toInt() and 0xfe) == 0xfc)
      else -> false
    }

/** Resolves [host] and returns its address only if it's on the private LAN. */
internal fun resolvePrivate(host: String): InetAddress {
  val addrs = InetAddress.getAllByName(host)
  return addrs.firstOrNull { isPrivateLan(it) }
      ?: throw IllegalArgumentException("$host is not on the home network (private addresses only)")
}

/** One-shot DNS-SD browser using legacy-unicast mDNS queries (no multicast lock needed). */
object MuseMdns {
  private const val TAG = "MuseMdns"
  private val GROUP = InetSocketAddress("224.0.0.251", 5353)

  /** The service types worth a look on a typical smart home LAN, browsed when none is given. */
  val COMMON_TYPES =
      listOf(
          "_googlecast._tcp", "_airplay._tcp", "_raop._tcp", "_spotify-connect._tcp", "_sonos._tcp",
          "_hue._tcp", "_hap._tcp", "_home-assistant._tcp", "_esphomelib._tcp", "_shelly._tcp",
          "_elg._tcp", "_ipp._tcp", "_printer._tcp", "_http._tcp", "_mqtt._tcp", "_androidtvremote2._tcp",
          "_immortal-remote._tcp", "_snapcast._tcp")

  data class Service(
      val instance: String,
      val type: String,
      var host: String = "",
      var port: Int = 0,
      val addresses: MutableSet<String> = linkedSetOf(),
      val txt: MutableMap<String, String> = linkedMapOf(),
  ) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("name", instance)
            .put("type", type)
            .put("host", host)
            .put("port", port)
            .put("addresses", JSONArray(addresses.toList()))
            .put("txt", JSONObject(txt as Map<*, *>))
  }

  fun browse(types: List<String>, timeoutMs: Long): List<Service> {
    val names = types.map { it.trim().trimEnd('.').let { t -> if (t.endsWith(".local")) t else "$t.local" } }
    val services = linkedMapOf<String, Service>()
    val hostAddrs = HashMap<String, MutableSet<String>>()
    DatagramSocket().use { sock ->
      sock.soTimeout = 250
      val query = buildQuery(names)
      val deadline = System.currentTimeMillis() + timeoutMs
      var nextSend = 0L
      var sends = 0
      val buf = ByteArray(9000)
      while (System.currentTimeMillis() < deadline) {
        if (sends < 3 && System.currentTimeMillis() >= nextSend) {
          runCatching { sock.send(DatagramPacket(query, query.size, GROUP)) }.onFailure { Log.w(TAG, "send: $it") }
          sends++
          nextSend = System.currentTimeMillis() + 700
        }
        val p = DatagramPacket(buf, buf.size)
        try {
          sock.receive(p)
        } catch (_: SocketTimeoutException) {
          continue
        }
        runCatching { parse(p.data.copyOf(p.length), names, services, hostAddrs) }
      }
    }
    services.values.forEach { s ->
      hostAddrs[s.host.lowercase()]?.let { s.addresses += it }
    }
    return services.values.toList()
  }

  private fun buildQuery(names: List<String>): ByteArray {
    val out = ByteArrayOutputStream()
    val d = DataOutputStream(out)
    d.writeShort(0) // id
    d.writeShort(0) // flags: standard query
    d.writeShort(names.size)
    d.writeShort(0)
    d.writeShort(0)
    d.writeShort(0)
    names.forEach { n ->
      n.split('.').filter { it.isNotEmpty() }.forEach { label ->
        val b = label.toByteArray(Charsets.UTF_8)
        d.writeByte(b.size)
        d.write(b)
      }
      d.writeByte(0)
      d.writeShort(12) // PTR
      d.writeShort(1) // IN
    }
    return out.toByteArray()
  }

  internal fun parse(
      msg: ByteArray,
      wanted: List<String>,
      services: MutableMap<String, Service>,
      hostAddrs: MutableMap<String, MutableSet<String>>,
  ) {
    val inp = DataInputStream(msg.inputStream())
    inp.skipBytes(4)
    val qd = inp.readUnsignedShort()
    val an = inp.readUnsignedShort()
    val ns = inp.readUnsignedShort()
    val ar = inp.readUnsignedShort()
    var off = 12
    repeat(qd) {
      off = readName(msg, off).second + 4
    }
    val wantedLc = wanted.map { it.lowercase() }
    val srv = HashMap<String, Pair<String, Int>>()
    val txt = HashMap<String, Map<String, String>>()
    repeat(an + ns + ar) {
      val (name, o1) = readName(msg, off)
      val type = u16(msg, o1)
      val rdlen = u16(msg, o1 + 8)
      val rd = o1 + 10
      when (type) {
        12 -> {
          val target = readName(msg, rd).first
          if (name.lowercase() in wantedLc) {
            services.getOrPut(target.lowercase()) {
              Service(target.removeSuffix(".$name").removeSuffix(name), name.removeSuffix(".local"))
            }
          }
        }
        33 -> srv[name.lowercase()] = readName(msg, rd + 6).first to u16(msg, rd + 4)
        16 -> {
          val m = linkedMapOf<String, String>()
          var p = rd
          while (p < rd + rdlen) {
            val l = msg[p].toInt() and 0xff
            val s = String(msg, p + 1, l, Charsets.UTF_8)
            if (s.isNotEmpty()) m[s.substringBefore('=')] = s.substringAfter('=', "")
            p += 1 + l
          }
          txt[name.lowercase()] = m
        }
        1 -> if (rdlen == 4) hostAddrs.getOrPut(name.lowercase()) { linkedSetOf() } += InetAddress.getByAddress(msg.copyOfRange(rd, rd + 4)).hostAddress!!
        28 -> if (rdlen == 16) hostAddrs.getOrPut(name.lowercase()) { linkedSetOf() } += InetAddress.getByAddress(msg.copyOfRange(rd, rd + 16)).hostAddress!!
      }
      off = rd + rdlen
    }
    services.forEach { (key, s) ->
      srv[key]?.let { (h, p) ->
        s.host = h
        s.port = p
      }
      txt[key]?.let { s.txt.putAll(it) }
    }
  }

  private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

  /** (dotted name, offset after the name in place). Follows compression pointers. */
  private fun readName(b: ByteArray, start: Int): Pair<String, Int> {
    val labels = ArrayList<String>()
    var off = start
    var end = -1
    var jumps = 0
    while (true) {
      val len = b[off].toInt() and 0xff
      if (len == 0) {
        off++
        break
      }
      if (len and 0xC0 == 0xC0) {
        if (end < 0) end = off + 2
        off = ((len and 0x3f) shl 8) or (b[off + 1].toInt() and 0xff)
        if (++jumps > 32) throw IOException("dns: pointer loop")
        continue
      }
      labels += String(b, off + 1, len, Charsets.UTF_8)
      off += 1 + len
    }
    return labels.joinToString(".") to (if (end >= 0) end else off)
  }
}

/** HTTP to devices on the private LAN only, for Muse's local-API device skills. */
object MuseLanHttp {
  private const val MAX_BODY = 96 * 1024

  fun request(params: JSONObject): JSONObject {
    val url = URL(params.getString("url"))
    require(url.protocol == "http" || url.protocol == "https") { "only http:// and https:// URLs" }
    // Pin the connection to the address vetted here: resolving again at connect time could land
    // on a public or loopback address (multi-record answers, DNS rebinding).
    val addr = resolvePrivate(url.host)
    val method = params.optString("method", "GET").uppercase()
    val https = url.protocol == "https"
    val target =
        if (https) url
        else URL("http", if (addr is Inet6Address) "[${addr.hostAddress}]" else addr.hostAddress, url.port, url.file)
    val conn = target.openConnection() as HttpURLConnection
    try {
      conn.instanceFollowRedirects = false // a redirect could leave the LAN
      conn.connectTimeout = 8_000
      conn.readTimeout = params.optInt("timeout_ms", 15_000).coerceIn(1_000, 60_000)
      conn.requestMethod = method
      if (!https) conn.setRequestProperty("Host", if (url.port > 0) "${url.host}:${url.port}" else url.host)
      if (conn is HttpsURLConnection) {
        val base = if (params.optBoolean("insecure_tls", false)) trustAllContext().socketFactory
            else HttpsURLConnection.getDefaultSSLSocketFactory()
        // HTTPS keeps the hostname (SNI, certificate check); instead the connected peer is
        // re-checked before TLS starts.
        conn.sslSocketFactory = PrivatePeerSocketFactory(base)
        if (params.optBoolean("insecure_tls", false)) conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
      }
      params.optJSONObject("headers")?.let { h -> h.keys().forEach { conn.setRequestProperty(it, h.optString(it)) } }
      val body =
          params.optString("body_b64").takeIf { it.isNotEmpty() }?.let { B64.decode(it) }
              ?: params.optString("body").takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
      if (body != null) {
        conn.doOutput = true
        conn.outputStream.use { it.write(body) }
      }
      val status = conn.responseCode
      val stream = if (status >= 400) conn.errorStream else conn.inputStream
      val bytes = stream?.use { readCapped(it) } ?: (ByteArray(0) to false)
      val headers = JSONObject()
      conn.headerFields.forEach { (k, v) -> if (k != null) headers.put(k, v.joinToString(", ")) }
      val type = conn.contentType.orEmpty()
      val textual = type.isEmpty() || type.startsWith("text/") || "json" in type || "xml" in type || "javascript" in type
      val out = JSONObject().put("status", status).put("headers", headers).put("truncated", bytes.second)
      if (textual) out.put("body", String(bytes.first, Charsets.UTF_8)) else out.put("body_b64", B64.encode(bytes.first))
      return out
    } finally {
      conn.disconnect()
    }
  }

  private fun readCapped(inp: java.io.InputStream): Pair<ByteArray, Boolean> {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
      val n = inp.read(buf)
      if (n < 0) return out.toByteArray() to false
      if (out.size() + n > MAX_BODY) {
        out.write(buf, 0, MAX_BODY - out.size())
        return out.toByteArray() to true
      }
      out.write(buf, 0, n)
    }
  }
}

/** Wraps TLS over an already-connected socket only if its peer is on the private LAN. */
private class PrivatePeerSocketFactory(private val base: SSLSocketFactory) : SSLSocketFactory() {
  override fun createSocket(s: java.net.Socket, host: String?, port: Int, autoClose: Boolean): java.net.Socket {
    val peer = s.inetAddress
    if (peer == null || !isPrivateLan(peer)) {
      runCatching { s.close() }
      throw IOException("refusing a connection that resolved off the home network ($peer)")
    }
    return base.createSocket(s, host, port, autoClose)
  }

  override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites

  override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites

  // Unconnected-socket variants: refuse, so nothing can skip the peer check above.
  override fun createSocket(host: String?, port: Int) = throw IOException("direct sockets not allowed")

  override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int) = throw IOException("direct sockets not allowed")

  override fun createSocket(host: InetAddress?, port: Int) = throw IOException("direct sockets not allowed")

  override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int) = throw IOException("direct sockets not allowed")
}

internal fun trustAllContext(): SSLContext =
    SSLContext.getInstance("TLS").apply {
      init(null, arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
      }), java.security.SecureRandom())
    }

/**
 * A Google Cast v2 *sender*: TLS to the receiver's port (8009 for single devices; groups advertise
 * their own), length-prefixed protobuf `CastMessage`s with JSON payloads. Receivers present
 * self-signed certs and senders are never authenticated, so this needs no Google services. It
 * cannot make the Portal a Cast *receiver* — that needs a Google-fused device certificate.
 */
class MuseCast(private val host: String, private val port: Int = 8009) : AutoCloseable {
  private var socket: SSLSocket? = null
  private var inp: DataInputStream? = null
  private var out: DataOutputStream? = null
  private var requestId = 1
  private val connected = HashSet<String>()

  fun connect(): MuseCast {
    val addr = resolvePrivate(host)
    val s = trustAllContext().socketFactory.createSocket() as SSLSocket
    s.connect(InetSocketAddress(addr, port), 8_000)
    s.soTimeout = 1_000
    s.startHandshake()
    socket = s
    inp = DataInputStream(s.inputStream.buffered())
    out = DataOutputStream(s.outputStream)
    open(RECEIVER)
    return this
  }

  override fun close() {
    runCatching { connected.forEach { send(it, NS_CONNECTION, JSONObject().put("type", "CLOSE")) } }
    runCatching { socket?.close() }
  }

  /** Receiver status (volume, running app) plus media status if a media app is running. */
  fun status(): JSONObject {
    val rs = receiverStatus()
    val out = JSONObject().put("receiver", rs)
    mediaApp(rs)?.let { app ->
      val transport = app.getString("transportId")
      open(transport)
      val ms = call(transport, NS_MEDIA, JSONObject().put("type", "GET_STATUS")) { it.optString("type") == "MEDIA_STATUS" }
      out.put("media", ms?.optJSONArray("status")?.optJSONObject(0) ?: JSONObject.NULL)
    }
    return out
  }

  /** Launches the Default Media Receiver and loads [url]; returns once it's PLAYING or failed. */
  fun play(url: String, contentType: String, title: String?, live: Boolean): JSONObject {
    var rs = receiverStatus()
    var app = findApp(rs, DEFAULT_MEDIA_RECEIVER)
    if (app == null) {
      rs = call(RECEIVER, NS_RECEIVER, JSONObject().put("type", "LAUNCH").put("appId", DEFAULT_MEDIA_RECEIVER), 20_000) {
        it.optString("type") == "RECEIVER_STATUS" && findApp(it.optJSONObject("status"), DEFAULT_MEDIA_RECEIVER) != null ||
            it.optString("type") == "LAUNCH_ERROR"
      }?.also { if (it.optString("type") == "LAUNCH_ERROR") throw IOException("launch failed: ${it.optString("reason")}") }
          ?.optJSONObject("status") ?: throw IOException("receiver didn't launch the media app")
      app = findApp(rs, DEFAULT_MEDIA_RECEIVER)!!
    }
    val transport = app.getString("transportId")
    open(transport)
    val media = JSONObject()
        .put("contentId", url)
        .put("contentType", contentType)
        .put("streamType", if (live) "LIVE" else "BUFFERED")
    if (!title.isNullOrBlank()) media.put("metadata", JSONObject().put("metadataType", 0).put("title", title))
    val load = JSONObject().put("type", "LOAD").put("media", media).put("autoplay", true)
    val first = call(transport, NS_MEDIA, load, 20_000) { it.optString("type") in setOf("MEDIA_STATUS", "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST") }
        ?: throw IOException("no answer to LOAD")
    if (first.optString("type") != "MEDIA_STATUS") throw IOException("${first.optString("type")}: ${first.optString("reason")}")
    // Wait for PLAYING (a LOAD ack alone, or BUFFERING, isn't success).
    var state = first.optJSONArray("status")?.optJSONObject(0)
    val deadline = System.currentTimeMillis() + 15_000
    while (state?.optString("playerState") != "PLAYING" && System.currentTimeMillis() < deadline) {
      val m = readUntil(transport, deadline) { it.optString("type") == "MEDIA_STATUS" } ?: break
      m.optJSONArray("status")?.optJSONObject(0)?.let { state = it }
      if (state?.optString("playerState") == "IDLE" && state?.optString("idleReason") == "ERROR") break
    }
    return JSONObject().put("media", state ?: JSONObject.NULL)
  }

  /** pause | play | stop on the running media session; stop falls back to quitting the app. */
  fun control(action: String): JSONObject {
    val rs = receiverStatus()
    val app = mediaApp(rs) ?: return JSONObject().put("note", "nothing is playing")
    val transport = app.getString("transportId")
    open(transport)
    val ms = call(transport, NS_MEDIA, JSONObject().put("type", "GET_STATUS")) { it.optString("type") == "MEDIA_STATUS" }
    val session = ms?.optJSONArray("status")?.optJSONObject(0)?.optInt("mediaSessionId", -1) ?: -1
    if (session < 0) {
      if (action == "stop") {
        call(RECEIVER, NS_RECEIVER, JSONObject().put("type", "STOP").put("sessionId", app.optString("sessionId"))) { it.optString("type") == "RECEIVER_STATUS" }
        return JSONObject().put("stopped_app", app.optString("displayName"))
      }
      return JSONObject().put("note", "no media session")
    }
    val type = when (action) { "pause" -> "PAUSE"; "play", "resume" -> "PLAY"; else -> "STOP" }
    val r = call(transport, NS_MEDIA, JSONObject().put("type", type).put("mediaSessionId", session)) { it.optString("type") == "MEDIA_STATUS" }
    return JSONObject().put("media", r?.optJSONArray("status")?.optJSONObject(0) ?: JSONObject.NULL)
  }

  /** Receiver-level volume (0.0–1.0) and/or mute; returns the read-back receiver volume. */
  fun volume(level: Double?, muted: Boolean?): JSONObject {
    val v = JSONObject()
    if (level != null) v.put("level", level.coerceIn(0.0, 1.0))
    if (muted != null) v.put("muted", muted)
    val r = call(RECEIVER, NS_RECEIVER, JSONObject().put("type", "SET_VOLUME").put("volume", v)) { it.optString("type") == "RECEIVER_STATUS" }
    return r?.optJSONObject("status")?.optJSONObject("volume") ?: JSONObject()
  }

  private fun receiverStatus(): JSONObject =
      call(RECEIVER, NS_RECEIVER, JSONObject().put("type", "GET_STATUS")) { it.optString("type") == "RECEIVER_STATUS" }
          ?.optJSONObject("status") ?: throw IOException("no receiver status")

  private fun mediaApp(rs: JSONObject): JSONObject? {
    val apps = rs.optJSONArray("applications") ?: return null
    for (i in 0 until apps.length()) {
      val a = apps.getJSONObject(i)
      val ns = a.optJSONArray("namespaces") ?: continue
      if ((0 until ns.length()).any { ns.optJSONObject(it)?.optString("name") == NS_MEDIA }) return a
    }
    return null
  }

  private fun findApp(rs: JSONObject?, appId: String): JSONObject? {
    val apps = rs?.optJSONArray("applications") ?: return null
    return (0 until apps.length()).map { apps.getJSONObject(it) }.firstOrNull { it.optString("appId") == appId }
  }

  private fun open(dest: String) {
    if (connected.add(dest)) send(dest, NS_CONNECTION, JSONObject().put("type", "CONNECT"))
  }

  private fun call(dest: String, ns: String, payload: JSONObject, timeoutMs: Long = 8_000, match: (JSONObject) -> Boolean): JSONObject? {
    val id = requestId++
    send(dest, ns, payload.put("requestId", id))
    val deadline = System.currentTimeMillis() + timeoutMs
    return readUntil(null, deadline) { (it.optInt("requestId", -1) == id || it.optInt("requestId", -1) == 0) && match(it) }
  }

  private fun readUntil(from: String?, deadline: Long, match: (JSONObject) -> Boolean): JSONObject? {
    while (System.currentTimeMillis() < deadline) {
      val (src, ns, payload) = read() ?: continue
      if (ns == NS_HEARTBEAT && payload.optString("type") == "PING") {
        send(src, NS_HEARTBEAT, JSONObject().put("type", "PONG"))
        continue
      }
      if (from != null && src != from) continue
      if (match(payload)) return payload
    }
    return null
  }

  private fun send(dest: String, ns: String, payload: JSONObject) {
    val msg = Proto.Writer()
        .varint(1, 0)
        .string(2, SENDER)
        .string(3, dest)
        .string(4, ns)
        .varint(5, 0)
        .string(6, payload.toString())
        .toByteArray()
    val o = out ?: throw IOException("not connected")
    synchronized(o) {
      o.writeInt(msg.size)
      o.write(msg)
      o.flush()
    }
  }

  private fun read(): Triple<String, String, JSONObject>? {
    val i = inp ?: throw IOException("not connected")
    val sock = socket ?: throw IOException("not connected")
    // Only the wait for a frame's first byte may time out quietly (the 1 s poll); once a frame
    // has started, read the rest under a longer deadline so a partial header is never dropped.
    val first = try {
      i.read()
    } catch (_: SocketTimeoutException) {
      return null
    }
    if (first < 0) throw IOException("cast: connection closed")
    val buf: ByteArray
    sock.soTimeout = 10_000
    try {
      val len = (first shl 24) or (i.readUnsignedByte() shl 16) or (i.readUnsignedByte() shl 8) or i.readUnsignedByte()
      if (len < 0 || len > 1 shl 20) throw IOException("cast: bad frame length $len")
      buf = ByteArray(len)
      i.readFully(buf)
    } finally {
      sock.soTimeout = 1_000
    }
    var src = ""
    var ns = ""
    var text = ""
    Proto.read(buf) { f, w, _, b ->
      if (w == Proto.DELIMITED) when (f) {
        2 -> src = String(b!!, Charsets.UTF_8)
        4 -> ns = String(b!!, Charsets.UTF_8)
        6 -> text = String(b!!, Charsets.UTF_8)
      }
    }
    val payload = runCatching { JSONObject(text) }.getOrNull() ?: return null
    return Triple(src, ns, payload)
  }

  companion object {
    const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"
    private const val SENDER = "sender-immortal"
    private const val RECEIVER = "receiver-0"
    private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
    private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
  }
}
