/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * The transport a Muse gadget speaks to its Muse VM, ported from the wire protocol of Meta's
 * open-source Muse Gadget SDK (github.com/facebookincubator/muse-gadget-sdk, Apache-2.0):
 *
 *   WebSocket /v1/noise  ->  Noise_XX_25519_AESGCM_SHA256 handshake  ->  every WebSocket binary
 *   frame is one AES-GCM record holding a NoiseTransportFrame (chunking), which reassembles to a
 *   ServiceRequest/ServiceResponse wrapping a ServiceFrame: HTTP-shaped request/response/body
 *   streams multiplexed by stream id.
 *
 * Dependency-free like the rest of the app's networking: X25519 is implemented here (Android 9/10
 * have no XDH provider), everything else is plain JCA (AES/GCM, HmacSHA256, SHA-256).
 */

class MuseProtocolException(message: String) : RuntimeException(message)

/** RFC 7748 X25519. BigInteger ladder: only ever runs a handful of times per connection. */
object X25519 {
  private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
  private val A24 = BigInteger.valueOf(121665)
  private val TWO = BigInteger.valueOf(2)
  private val BASE = ByteArray(32).also { it[0] = 9 }
  private val rnd = SecureRandom()

  // Public keys that force a predictable shared secret; the SDK rejects them up front.
  private val LOW_ORDER: List<ByteArray> =
      listOf(
          ByteArray(32),
          ByteArray(32).also { it[0] = 1 },
          hex("e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800"),
          hex("5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157"),
          hex("ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
          hex("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
          hex("eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
      )

  fun generatePrivate(): ByteArray = ByteArray(32).also { rnd.nextBytes(it) }

  fun publicKey(privateKey: ByteArray): ByteArray = scalarMult(privateKey, BASE)

  /** Diffie-Hellman with the same checks as the SDK: no low-order points, no all-zero output. */
  fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
    if (publicKey.size != 32) throw MuseProtocolException("x25519: invalid public key length")
    if (LOW_ORDER.any { MessageDigest.isEqual(it, publicKey) })
        throw MuseProtocolException("x25519: rejected low-order public key")
    val shared = scalarMult(privateKey, publicKey)
    if (shared.all { it.toInt() == 0 }) throw MuseProtocolException("x25519: all-zero output")
    return shared
  }

  fun scalarMult(scalar: ByteArray, u: ByteArray): ByteArray {
    val k = scalar.copyOf(32)
    k[0] = (k[0].toInt() and 248).toByte()
    k[31] = ((k[31].toInt() and 127) or 64).toByte()
    val uc = u.copyOf(32)
    uc[31] = (uc[31].toInt() and 127).toByte()
    val x1 = leToInt(uc).mod(P)
    var x2 = BigInteger.ONE
    var z2 = BigInteger.ZERO
    var x3 = x1
    var z3 = BigInteger.ONE
    var swap = 0
    for (t in 254 downTo 0) {
      val kt = (k[t ushr 3].toInt() ushr (t and 7)) and 1
      if ((swap xor kt) == 1) {
        x2 = x3.also { x3 = x2 }
        z2 = z3.also { z3 = z2 }
      }
      swap = kt
      val a = x2.add(z2).mod(P)
      val aa = a.multiply(a).mod(P)
      val b = x2.subtract(z2).mod(P)
      val bb = b.multiply(b).mod(P)
      val e = aa.subtract(bb).mod(P)
      val c = x3.add(z3).mod(P)
      val d = x3.subtract(z3).mod(P)
      val da = d.multiply(a).mod(P)
      val cb = c.multiply(b).mod(P)
      val sum = da.add(cb).mod(P)
      x3 = sum.multiply(sum).mod(P)
      val diff = da.subtract(cb).mod(P)
      z3 = x1.multiply(diff.multiply(diff)).mod(P)
      x2 = aa.multiply(bb).mod(P)
      z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
    }
    if (swap == 1) {
      x2 = x3
      z2 = z3
    }
    return intToLe(x2.multiply(z2.modPow(P.subtract(TWO), P)).mod(P))
  }

  private fun leToInt(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

  private fun intToLe(v: BigInteger): ByteArray {
    val be = v.toByteArray()
    val out = ByteArray(32)
    for (i in be.indices) {
      val pos = be.size - 1 - i
      if (pos < 32) out[pos] = be[i]
    }
    return out
  }

  internal fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
      init(SecretKeySpec(key, "HmacSHA256"))
      doFinal(data)
    }

internal fun sha256(vararg parts: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").run {
      parts.forEach { update(it) }
      digest()
    }

/** AES-256-GCM with an explicit 12-byte nonce and associated data; tag appended (JCA layout). */
internal object AesGcm {
  fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
      Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad.isNotEmpty()) updateAAD(aad)
        doFinal(plaintext)
      }

  fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray =
      Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad.isNotEmpty()) updateAAD(aad)
        doFinal(sealed)
      }
}

/** One direction of a Noise session. Thread-safe; any failure poisons it for good. */
class NoiseCipherState {
  private var key: ByteArray? = null
  private var nonce = 0L
  private var poisoned = false

  fun initializeKey(k: ByteArray) {
    if (k.size != 32) throw MuseProtocolException("CipherState: key must be 32 bytes")
    key = k.copyOf()
    nonce = 0
  }

  fun hasKey(): Boolean = key != null

  @Synchronized
  fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
    val k = key ?: return plaintext
    return guard { AesGcm.seal(k, iv(next()), plaintext, ad) }
  }

  @Synchronized
  fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
    val k = key ?: return ciphertext
    return guard {
      try {
        AesGcm.open(k, iv(next()), ciphertext, ad)
      } catch (e: javax.crypto.AEADBadTagException) {
        throw MuseProtocolException("CipherState: decrypt failed")
      }
    }
  }

  private inline fun <T> guard(block: () -> T): T {
    if (poisoned) throw MuseProtocolException("CipherState: poisoned after prior failure")
    try {
      return block()
    } catch (e: Exception) {
      poisoned = true
      throw e
    }
  }

  private fun next(): Long {
    if (nonce >= MAX_SAFE_NONCE) throw MuseProtocolException("CipherState: nonce exhausted")
    return nonce++
  }

  private companion object {
    const val MAX_SAFE_NONCE = (1L shl 53) - 1

    fun iv(n: Long): ByteArray =
        ByteArray(12).also { for (i in 0 until 8) it[4 + i] = (n ushr (8 * (7 - i))).toByte() }
  }
}

private class NoiseSymmetricState {
  var ck = ByteArray(32)
  var h = ByteArray(32)
  var cipher = NoiseCipherState()

  fun initialize() {
    val name = PROTOCOL_NAME.toByteArray(Charsets.US_ASCII)
    h = ByteArray(32).also { System.arraycopy(name, 0, it, 0, name.size) }
    ck = h.copyOf()
    mixHash(ByteArray(0))
  }

  fun mixHash(data: ByteArray) {
    h = sha256(h, data)
  }

  fun mixKey(ikm: ByteArray) {
    val (newCk, tempK) = hkdf2(ck, ikm)
    ck = newCk
    cipher = NoiseCipherState().also { it.initializeKey(tempK) }
  }

  fun encryptAndHash(plaintext: ByteArray): ByteArray =
      cipher.encryptWithAd(h, plaintext).also { mixHash(it) }

  fun decryptAndHash(ciphertext: ByteArray): ByteArray =
      cipher.decryptWithAd(h, ciphertext).also { mixHash(ciphertext) }

  fun split(): Pair<NoiseCipherState, NoiseCipherState> {
    val (k1, k2) = hkdf2(ck, ByteArray(0))
    ck = ByteArray(32)
    h = ByteArray(32)
    return NoiseCipherState().also { it.initializeKey(k1) } to
        NoiseCipherState().also { it.initializeKey(k2) }
  }

  companion object {
    const val PROTOCOL_NAME = "Noise_XX_25519_AESGCM_SHA256"

    fun hkdf2(chainingKey: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
      val temp = hmacSha256(chainingKey, ikm)
      val o1 = hmacSha256(temp, byteArrayOf(1))
      val o2 = hmacSha256(temp, o1 + byteArrayOf(2))
      return o1 to o2
    }
  }
}

/**
 * The initiator (device) side of Noise XX. The server's static key isn't pinned (the SDK doesn't
 * either: the per-VM bearer on the WebSocket upgrade is what authenticates both ends), and our
 * static key is fresh per connection.
 */
class NoiseXXInitiator(private val keyGen: () -> ByteArray = X25519::generatePrivate) {
  private val ss = NoiseSymmetricState().also { it.initialize() }
  private var e: ByteArray? = null
  private var re: ByteArray? = null
  private var phase = 0

  fun writeMessage1(): ByteArray {
    check(phase == 0) { "NoiseXX: writeMessage1 out of order" }
    val priv = keyGen()
    e = priv
    val pub = X25519.publicKey(priv)
    ss.mixHash(pub)
    ss.encryptAndHash(ByteArray(0))
    phase = 1
    return pub
  }

  fun readMessage2(msg: ByteArray): ByteArray {
    check(phase == 1) { "NoiseXX: readMessage2 out of order" }
    if (msg.size < 32 + 48 + 16) throw MuseProtocolException("NoiseXX: message 2 too short (${msg.size})")
    val remoteE = msg.copyOfRange(0, 32)
    re = remoteE
    ss.mixHash(remoteE)
    ss.mixKey(X25519.dh(e!!, remoteE))
    val rs = ss.decryptAndHash(msg.copyOfRange(32, 80))
    ss.mixKey(X25519.dh(e!!, rs))
    val payload = ss.decryptAndHash(msg.copyOfRange(80, msg.size))
    phase = 2
    return payload
  }

  fun writeMessage3(): ByteArray {
    check(phase == 2) { "NoiseXX: writeMessage3 out of order" }
    val s = keyGen()
    val encS = ss.encryptAndHash(X25519.publicKey(s))
    ss.mixKey(X25519.dh(s, re!!))
    val encPayload = ss.encryptAndHash(ByteArray(0))
    phase = 3
    return encS + encPayload
  }

  /** (send, receive) cipher states. */
  fun split(): Pair<NoiseCipherState, NoiseCipherState> {
    check(phase == 3) { "NoiseXX: split out of order" }
    phase = 4
    e = null
    re = null
    return ss.split()
  }
}

/** Test/loopback responder, kept next to the initiator so the two can't drift. */
internal class NoiseXXResponder(private val payload: ByteArray = ByteArray(0)) {
  private val ss = NoiseSymmetricState().also { it.initialize() }
  private var e: ByteArray? = null

  fun readMessage1WriteMessage2(msg1: ByteArray): ByteArray {
    val re = msg1.copyOfRange(0, 32)
    ss.mixHash(re)
    ss.decryptAndHash(msg1.copyOfRange(32, msg1.size))
    val ePriv = X25519.generatePrivate()
    e = ePriv
    val ePub = X25519.publicKey(ePriv)
    ss.mixHash(ePub)
    ss.mixKey(X25519.dh(ePriv, re))
    val sPriv = X25519.generatePrivate()
    val encS = ss.encryptAndHash(X25519.publicKey(sPriv))
    ss.mixKey(X25519.dh(sPriv, re))
    return ePub + encS + ss.encryptAndHash(payload)
  }

  fun readMessage3(msg3: ByteArray) {
    val rs = ss.decryptAndHash(msg3.copyOfRange(0, 48))
    ss.mixKey(X25519.dh(e!!, rs))
    ss.decryptAndHash(msg3.copyOfRange(48, msg3.size))
  }

  /** (send, receive) from the responder's side. */
  fun split(): Pair<NoiseCipherState, NoiseCipherState> = ss.split().let { (c1, c2) -> c2 to c1 }
}

/** The handful of protobuf wire-format helpers the envelopes need. */
internal object Proto {
  const val VARINT = 0
  const val FIXED64 = 1
  const val DELIMITED = 2
  const val FIXED32 = 5

  class Writer {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long) = apply {
      key(field, VARINT)
      rawVarint(value)
    }

    fun bytes(field: Int, value: ByteArray) = apply {
      key(field, DELIMITED)
      rawVarint(value.size.toLong())
      out.write(value)
    }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun bool(field: Int, value: Boolean) = varint(field, if (value) 1 else 0)

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun key(field: Int, wire: Int) = rawVarint(((field shl 3) or wire).toLong())

    private fun rawVarint(v: Long) {
      var value = v
      while (true) {
        if (value and 0x7fL.inv() == 0L) {
          out.write(value.toInt())
          return
        }
        out.write(((value and 0x7f) or 0x80).toInt())
        value = value ushr 7
      }
    }
  }

  /** Visits each field; [onField] gets (field, wireType, varintValue, delimitedBytes). */
  fun read(data: ByteArray, onField: (Int, Int, Long, ByteArray?) -> Unit) {
    var off = 0
    fun varint(): Long {
      var result = 0L
      var shift = 0
      for (i in 0 until 10) {
        if (off >= data.size) throw MuseProtocolException("proto: truncated varint")
        val b = data[off++].toInt() and 0xff
        result = result or ((b and 0x7f).toLong() shl shift)
        if (b and 0x80 == 0) return result
        shift += 7
      }
      throw MuseProtocolException("proto: malformed varint")
    }
    while (off < data.size) {
      val key = varint()
      val field = (key ushr 3).toInt()
      val wire = (key and 7).toInt()
      if (field == 0) throw MuseProtocolException("proto: invalid field number")
      when (wire) {
        VARINT -> onField(field, wire, varint(), null)
        DELIMITED -> {
          val len = varint().toInt()
          if (len < 0 || off + len > data.size) throw MuseProtocolException("proto: truncated field")
          val bytes = data.copyOfRange(off, off + len)
          off += len
          onField(field, wire, 0, bytes)
        }
        FIXED64 -> {
          if (off + 8 > data.size) throw MuseProtocolException("proto: truncated fixed64")
          off += 8
        }
        FIXED32 -> {
          if (off + 4 > data.size) throw MuseProtocolException("proto: truncated fixed32")
          off += 4
        }
        else -> throw MuseProtocolException("proto: invalid wire type $wire")
      }
    }
  }
}

/** A decoded server-to-device frame on one stream. */
sealed class MuseFrame(val streamId: Long) {
  class Response(
      streamId: Long,
      val status: Int,
      val headers: List<Pair<String, String>>,
      val body: ByteArray,
      val endBody: Boolean,
  ) : MuseFrame(streamId)

  class Body(streamId: Long, val data: ByteArray, val endBody: Boolean) : MuseFrame(streamId)

  class Reset(streamId: Long, val code: Int, val reason: String) : MuseFrame(streamId)
}

/**
 * HTTP-over-Noise on an established session: encrypts device requests into WebSocket frames and
 * decrypts server frames back into [MuseFrame]s. Stream ids are allocated here. Not
 * thread-safe for encryption order: callers serialize sends (see [MuseLink]).
 */
class MuseNoiseTransport(private val send: NoiseCipherState, private val recv: NoiseCipherState) {
  private var nextStreamId = 1L
  private val pending = HashMap<Long, Assembly>()
  private val rnd = SecureRandom()

  private class Assembly(val total: Int, val created: Long) {
    val chunks = HashMap<Int, ByteArray>()
    var bytes = 0
  }

  /** Opens a request stream; returns its id and the frames to send. */
  @Synchronized
  fun request(
      verb: String,
      path: String,
      headers: List<Pair<String, String>> = emptyList(),
      body: ByteArray = ByteArray(0),
      endBody: Boolean = true,
  ): Pair<Long, List<ByteArray>> {
    val id = nextStreamId++
    val req = Proto.Writer().string(1, verb).string(2, path)
    headers.forEach { (k, v) -> req.bytes(3, header(k, v)) }
    if (body.isNotEmpty()) req.bytes(4, body)
    if (endBody) req.bool(5, true)
    val frame = Proto.Writer().varint(1, id).bytes(2, req.toByteArray()).toByteArray()
    return id to seal(frame)
  }

  @Synchronized
  fun bodyChunk(streamId: Long, data: ByteArray, endBody: Boolean = false): List<ByteArray> {
    val chunk = Proto.Writer()
    if (data.isNotEmpty()) chunk.bytes(1, data)
    if (endBody) chunk.bool(2, true)
    return seal(Proto.Writer().varint(1, streamId).bytes(4, chunk.toByteArray()).toByteArray())
  }

  @Synchronized
  fun reset(streamId: Long, reason: String = ""): List<ByteArray> {
    val r = Proto.Writer().varint(1, 1) // CANCELLED
    if (reason.isNotEmpty()) r.string(2, reason)
    return seal(Proto.Writer().varint(1, streamId).bytes(5, r.toByteArray()).toByteArray())
  }

  /** Decrypts one WebSocket binary frame; null while a chunked message is still assembling. */
  @Synchronized
  fun decrypt(wsFrame: ByteArray): MuseFrame? {
    val plain = recv.decryptWithAd(ByteArray(0), wsFrame)
    val message = reassemble(plain) ?: return null
    var payload: ByteArray? = null
    Proto.read(message) { f, w, _, b -> if (f == 1 && w == Proto.DELIMITED) payload = b }
    val frame = payload ?: throw MuseProtocolException("empty ServiceResponse payload")
    return decodeServiceFrame(frame)
  }

  private fun seal(serviceFrame: ByteArray): List<ByteArray> {
    // ServiceRequest{service=DAEMON (default, omitted), payload}
    val request = Proto.Writer().bytes(2, serviceFrame).toByteArray()
    return chunkFrames(request).map { send.encryptWithAd(ByteArray(0), it) }
  }

  private fun chunkFrames(data: ByteArray): List<ByteArray> {
    val chunkId = rnd.nextLong()
    val total = maxOf(1, (data.size + MAX_CHUNK - 1) / MAX_CHUNK)
    if (total > MAX_CHUNKS) throw MuseProtocolException("payload too large for noise framing")
    return (0 until total).map { i ->
      val part = data.copyOfRange(i * MAX_CHUNK, minOf(data.size, (i + 1) * MAX_CHUNK))
      val w = Proto.Writer()
      if (chunkId != 0L) w.varint(1, chunkId)
      if (i != 0) w.varint(2, i.toLong())
      w.varint(3, total.toLong())
      if (part.isNotEmpty()) w.bytes(4, part)
      w.toByteArray()
    }
  }

  private fun reassemble(frame: ByteArray): ByteArray? {
    var chunkId = 0L
    var index = 0
    var total = 1
    var payload = ByteArray(0)
    Proto.read(frame) { f, w, v, b ->
      when {
        f == 1 && w == Proto.VARINT -> chunkId = v
        f == 2 && w == Proto.VARINT -> index = v.toInt()
        f == 3 && w == Proto.VARINT -> total = v.toInt()
        f == 4 && w == Proto.DELIMITED -> payload = b!!
      }
    }
    if (total < 1 || total > MAX_CHUNKS || index < 0 || index >= total)
        throw MuseProtocolException("noise frame: bad chunk $index/$total")
    if (total == 1) return payload
    val now = System.currentTimeMillis()
    pending.entries.removeAll { now - it.value.created > ASSEMBLY_TTL_MS }
    val a =
        pending.getOrPut(chunkId) {
          if (pending.size >= MAX_PENDING) throw MuseProtocolException("too many pending assemblies")
          Assembly(total, now)
        }
    if (a.total != total || a.chunks.containsKey(index))
        throw MuseProtocolException("noise frame: inconsistent chunking")
    a.chunks[index] = payload
    a.bytes += payload.size
    if (a.bytes > MAX_ASSEMBLY) throw MuseProtocolException("noise frame: assembly too large")
    if (a.chunks.size < total) return null
    pending.remove(chunkId)
    val out = ByteArrayOutputStream(a.bytes)
    for (i in 0 until total) out.write(a.chunks[i]!!)
    return out.toByteArray()
  }

  private fun decodeServiceFrame(data: ByteArray): MuseFrame? {
    var streamId = 0L
    var result: ((Long) -> MuseFrame)? = null
    Proto.read(data) { f, w, v, b ->
      when {
        f == 1 && w == Proto.VARINT -> streamId = v
        f == 2 && w == Proto.DELIMITED ->
            throw MuseProtocolException("unexpected request frame from server")
        f == 3 && w == Proto.DELIMITED -> result = decodeResponse(b!!)
        f == 4 && w == Proto.DELIMITED -> result = decodeBody(b!!)
        f == 5 && w == Proto.DELIMITED -> result = decodeReset(b!!)
      }
    }
    return result?.invoke(streamId)
  }

  private fun decodeResponse(b: ByteArray): (Long) -> MuseFrame {
    var status = 0
    val headers = ArrayList<Pair<String, String>>()
    var body = ByteArray(0)
    var end = false
    Proto.read(b) { f, w, v, d ->
      when {
        f == 1 && w == Proto.VARINT -> status = v.toInt()
        f == 2 && w == Proto.DELIMITED -> headers += decodeHeader(d!!)
        f == 3 && w == Proto.DELIMITED -> body = d!!
        f == 4 && w == Proto.VARINT -> end = v != 0L
      }
    }
    return { MuseFrame.Response(it, status, headers, body, end) }
  }

  private fun decodeBody(b: ByteArray): (Long) -> MuseFrame {
    var data = ByteArray(0)
    var end = false
    Proto.read(b) { f, w, v, d ->
      when {
        f == 1 && w == Proto.DELIMITED -> data = d!!
        f == 2 && w == Proto.VARINT -> end = v != 0L
      }
    }
    return { MuseFrame.Body(it, data, end) }
  }

  private fun decodeReset(b: ByteArray): (Long) -> MuseFrame {
    var code = 0
    var reason = ""
    Proto.read(b) { f, w, v, d ->
      when {
        f == 1 && w == Proto.VARINT -> code = v.toInt()
        f == 2 && w == Proto.DELIMITED -> reason = String(d!!, Charsets.UTF_8)
      }
    }
    return { MuseFrame.Reset(it, code, reason) }
  }

  private fun decodeHeader(b: ByteArray): Pair<String, String> {
    var k = ""
    var v = ""
    Proto.read(b) { f, w, _, d ->
      if (w == Proto.DELIMITED && f == 1) k = String(d!!, Charsets.UTF_8)
      if (w == Proto.DELIMITED && f == 2) v = String(d!!, Charsets.UTF_8)
    }
    return k to v
  }

  private fun header(k: String, v: String): ByteArray {
    val w = Proto.Writer()
    if (k.isNotEmpty()) w.string(1, k)
    if (v.isNotEmpty()) w.string(2, v)
    return w.toByteArray()
  }

  internal companion object {
    const val MAX_CHUNK = 65489
    const val MAX_CHUNKS = 256
    const val MAX_PENDING = 16
    const val MAX_ASSEMBLY = 16 * 1024 * 1024
    const val ASSEMBLY_TTL_MS = 60_000L

    /** Server side of the codec, for the loopback tests: decrypted request bytes → ServiceFrame. */
    internal fun encodeServerFrame(streamId: Long, kind: Int, inner: ByteArray): ByteArray =
        Proto.Writer().bytes(1, Proto.Writer().varint(1, streamId).bytes(kind, inner).toByteArray()).toByteArray()
  }
}
