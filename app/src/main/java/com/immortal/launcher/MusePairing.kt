/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.util.Log
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.concurrent.LinkedBlockingQueue
import javax.crypto.KeyAgreement
import org.json.JSONObject

/*
 * Device side of Muse Gadget BLE setup: community pairing v5 (P-256 ECDH, HKDF-SHA256,
 * AES-256-GCM records, policy `confirm_app`) and the setup command state machine behind the two
 * GATT characteristics. Ported from the wire protocol of Meta's open-source Muse Gadget SDK
 * (Apache-2.0) so the stock Muse app pairs a Portal exactly like a Raspberry Pi gadget.
 *
 * Community pairing protects the setup secrets from passive listeners; like the SDK it cannot
 * stop an active man-in-the-middle, so pairing only opens on demand and for ten minutes.
 */

/** Base64 without android.util (stubbed in JVM tests) or java.util.Base64 (API 26). */
internal object B64 {
  private const val STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
  private const val URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

  fun encode(b: ByteArray, url: Boolean = false, pad: Boolean = !url): String {
    val alphabet = if (url) URL else STD
    val sb = StringBuilder((b.size + 2) / 3 * 4)
    var i = 0
    while (i < b.size) {
      val n = minOf(3, b.size - i)
      val v = ((b[i].toInt() and 0xff) shl 16) or
          ((if (n > 1) b[i + 1].toInt() and 0xff else 0) shl 8) or
          (if (n > 2) b[i + 2].toInt() and 0xff else 0)
      for (j in 0..n) sb.append(alphabet[(v ushr (18 - 6 * j)) and 63])
      if (pad) repeat(3 - n) { sb.append('=') }
      i += 3
    }
    return sb.toString()
  }

  /** Decodes either alphabet; padding optional. Throws on anything else. */
  fun decode(text: String): ByteArray {
    val clean = text.trimEnd('=')
    val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
    var acc = 0
    var bits = 0
    for (ch in clean) {
      val v = when (ch) {
        in 'A'..'Z' -> ch - 'A'
        in 'a'..'z' -> ch - 'a' + 26
        in '0'..'9' -> ch - '0' + 52
        '+', '-' -> 62
        '/', '_' -> 63
        else -> throw IllegalArgumentException("base64")
      }
      acc = (acc shl 6) or v
      bits += 6
      if (bits >= 8) {
        bits -= 8
        out.write((acc ushr bits) and 0xff)
      }
    }
    return out.toByteArray()
  }
}

internal object B64Url {
  private val RE = Regex("[A-Za-z0-9_-]+")

  fun encode(b: ByteArray): String = B64.encode(b, url = true)

  /** Unpadded base64url, rejecting what the firmware rejects. */
  fun decode(text: Any?, maxChars: Int = 4096): ByteArray {
    if (text !is String || text.isEmpty() || text.length > maxChars) throw IllegalArgumentException("b64 length")
    if (text.length % 4 == 1 || !RE.matches(text)) throw IllegalArgumentException("b64")
    return B64.decode(text)
  }
}

class MusePairingException(val status: String) : Exception(status)

/** One device's pairing state; every public method is safe from any thread. */
class MusePairingSession(
    private val nodeId: String,
    private val deviceId: String,
    private val mac: String,
    firmwareVersion: String,
    private val sdkToken: String?,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val keyGen: () -> Pair<PrivateKey, ByteArray> = ::generateP256,
    private val random: (Int) -> ByteArray = { n -> ByteArray(n).also { SecureRandom().nextBytes(it) } },
) {
  enum class State { IDLE, WAIT_CLIENT_FINISHED, READY, PROVISIONING }

  private val firmwareVersion = firmwareVersion.ifEmpty { "unknown" }
  private val lock = Any()
  private var generation = 0
  private var state = State.IDLE
  private var deadline = 0L
  private var rxKey: ByteArray? = null
  private var txKey: ByteArray? = null
  private var sessionIdB64 = ""
  private var rxCounter = 0L
  private var txCounter = 0L

  fun deviceInfo(): JSONObject =
      JSONObject()
          .put("device_id", deviceId)
          .put("mac", mac)
          .put("model", MODEL)
          .put("pairing_protocol", VERSION)
          .put("pairing_auth", AUTH_COMMUNITY)
          .put("pairing_auth_epoch", 0)
          .put("pairing_policy", POLICY_APP)

  val currentState: State
    get() = synchronized(lock) { expire(); state }

  val confirmed: Boolean
    get() = currentState.let { it == State.READY || it == State.PROVISIONING }

  fun reset() = synchronized(lock) { resetLocked() }

  /** `pairing_client_hello` → `pairing_ready`. */
  fun handleHello(msg: JSONObject): JSONObject {
    val version = msg.opt("version")
    if (version !is Number || version is Boolean || version.toDouble() != VERSION.toDouble() ||
        msg.optString("pairing_auth") != AUTH_COMMUNITY ||
        msg.optString("pairing_policy") != POLICY_APP)
        throw MusePairingException(ERROR_INVALID_HELLO)
    synchronized(lock) {
      resetLocked()
      val mobilePub: ByteArray
      val mobileNonce: ByteArray
      val peer: java.security.PublicKey
      try {
        mobilePub = B64Url.decode(msg.opt("mobile_pub"))
        mobileNonce = B64Url.decode(msg.opt("mobile_nonce"))
        require(mobilePub.size == 65 && mobilePub[0].toInt() == 4 && mobileNonce.size == 16)
        peer = p256Public(mobilePub)
      } catch (e: Exception) {
        resetLocked()
        throw MusePairingException(ERROR_INVALID_HELLO)
      }
      val (devicePriv, devicePub) = keyGen()
      val deviceNonce = random(16)
      val transcript =
          buildTranscript(
              deviceId, nodeId, mac, firmwareVersion, B64Url.encode(mobilePub), B64Url.encode(devicePub),
              B64Url.encode(mobileNonce), B64Url.encode(deviceNonce))
      val transcriptHash = sha256(transcript.toByteArray(Charsets.UTF_8))
      val ecdh =
          KeyAgreement.getInstance("ECDH").run {
            init(devicePriv)
            doPhase(peer, true)
            generateSecret()
          }
      val keys = deriveSessionKeys(ecdh, mobileNonce, deviceNonce, transcriptHash)
      rxKey = keys.mobileTx
      txKey = keys.mobileRx
      sessionIdB64 = B64Url.encode(keys.sessionId)
      rxCounter = 0
      txCounter = 0
      state = State.WAIT_CLIENT_FINISHED
      deadline = clock() + CLIENT_FINISHED_TIMEOUT_MS
      return JSONObject()
          .put("type", "pairing_ready")
          .put("version", VERSION)
          .put("device_id", deviceId)
          .put("node_id", nodeId)
          .put("mac", mac)
          .put("model", MODEL)
          .put("firmware_version", firmwareVersion)
          .put("pairing_auth", AUTH_COMMUNITY)
          .put("pairing_auth_epoch", 0)
          .put("pairing_policy", POLICY_APP)
          .put("device_pub", B64Url.encode(devicePub))
          .put("device_nonce", B64Url.encode(deviceNonce))
          .put("transcript_hash", B64Url.encode(transcriptHash))
          .put("session_id", sessionIdB64)
    }
  }

  /** Opens one mobile→device `pairing_encrypted` record. Any failure clears the session. */
  fun decrypt(envelope: JSONObject): String =
      synchronized(lock) {
        val key = rxKey
        if (expire() || state == State.IDLE || key == null) {
          resetLocked()
          throw MusePairingException(ERROR_DECRYPT)
        }
        try {
          require(envelope.opt("session_id") == sessionIdB64)
          val counter = parseCounter(envelope.opt("counter"))
          require(counter == rxCounter)
          val ct = B64Url.decode(envelope.opt("ciphertext"), 16384)
          val tag = B64Url.decode(envelope.opt("tag"))
          require(tag.size == 16)
          val plain = AesGcm.open(key, recordNonce(TO_DEVICE, counter), ct + tag, recordAad(TO_DEVICE, counter))
          rxCounter++
          String(plain, Charsets.UTF_8)
        } catch (e: Exception) {
          resetLocked()
          throw MusePairingException(ERROR_DECRYPT)
        }
      }

  /** Confirms the session on an exact first `pairing_client_finished` record; 0 if invalid. */
  fun handleClientFinished(command: JSONObject): Int =
      synchronized(lock) {
        val ok =
            command.length() == 1 &&
                command.opt("action") == "pairing_client_finished" &&
                !expire() &&
                state == State.WAIT_CLIENT_FINISHED &&
                rxCounter == 1L
        if (!ok) {
          resetLocked()
          return 0
        }
        generation++
        state = State.READY
        deadline = clock() + CONFIRMED_TIMEOUT_MS
        generation
      }

  fun markProvisioning(): Int =
      synchronized(lock) {
        if (!expire() && state == State.READY) {
          generation++
          state = State.PROVISIONING
          deadline = clock() + PROVISIONING_TIMEOUT_MS
        }
        if (state == State.PROVISIONING) generation else 0
      }

  fun extendProvisioning(gen: Int): Boolean =
      synchronized(lock) { provisioningLocked(gen).also { if (it) deadline = clock() + PROVISIONING_TIMEOUT_MS } }

  /** Runs [commit] (local persistence only) under the lock if [gen] is still the live attempt. */
  fun commitProvisioning(gen: Int, commit: () -> Boolean): Boolean =
      synchronized(lock) { provisioningLocked(gen) && commit() }

  /** Seals a device→mobile record, or null with no session / a stale [gen]. */
  fun encryptJson(plaintext: String, gen: Int = 0): JSONObject? =
      synchronized(lock) {
        val key = txKey
        if ((gen != 0 && gen != generation) || expire() || state == State.IDLE || key == null) return null
        val counter = txCounter
        val sealed =
            AesGcm.seal(key, recordNonce(FROM_DEVICE, counter), plaintext.toByteArray(Charsets.UTF_8), recordAad(FROM_DEVICE, counter))
        txCounter++
        JSONObject()
            .put("type", "pairing_encrypted")
            .put("session_id", sessionIdB64)
            .put("counter", counter.toString())
            .put("ciphertext", B64Url.encode(sealed.copyOfRange(0, sealed.size - 16)))
            .put("tag", B64Url.encode(sealed.copyOfRange(sealed.size - 16, sealed.size)))
      }

  fun encryptStatus(status: String, gen: Int = 0): JSONObject? {
    val msg = JSONObject().put("type", "status").put("status", status)
    // Apps read only type and status, so older ones ignore the token.
    if (sdkToken != null && status == "pairing_confirmed") msg.put("sdk_token", sdkToken)
    return encryptJson(msg.toString(), gen)
  }

  private fun provisioningLocked(gen: Int) =
      gen != 0 && gen == generation && !expire() && state == State.PROVISIONING

  private fun resetLocked() {
    generation++
    clear()
  }

  private fun clear() {
    state = State.IDLE
    deadline = 0
    rxKey = null
    txKey = null
    sessionIdB64 = ""
    rxCounter = 0
    txCounter = 0
  }

  /** Expired sessions drop their keys but keep the generation, like the SDK. */
  private fun expire(): Boolean {
    if (state == State.IDLE || clock() <= deadline) return false
    clear()
    return true
  }

  private fun recordAad(direction: Int, counter: Long): ByteArray =
      "$RECORD_LABEL|$sessionIdB64|${if (direction == TO_DEVICE) "m2d" else "d2m"}|$counter".toByteArray()

  class SessionKeys(val mobileTx: ByteArray, val mobileRx: ByteArray, val sessionId: ByteArray)

  companion object {
    const val VERSION = 5
    const val MODEL = "hatch_link"
    const val SUITE = "p256-hkdf-sha256-aes-gcm-v1"
    const val POLICY_APP = "confirm_app"
    const val AUTH_COMMUNITY = "none"
    const val RECORD_LABEL = "hatch-link ble setup v1"
    const val ERROR_INVALID_HELLO = "error_pairing_invalid_hello"
    const val ERROR_DECRYPT = "error_pairing_decrypt"
    const val CLIENT_FINISHED_TIMEOUT_MS = 60_000L
    const val CONFIRMED_TIMEOUT_MS = 120_000L
    const val PROVISIONING_TIMEOUT_MS = 120_000L
    private const val TO_DEVICE = 0
    private const val FROM_DEVICE = 1
    private val DECIMAL = Regex("[0-9]+")

    private val p256Params: ECParameterSpec by lazy {
      (KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
          }
          .public as ECPublicKey)
          .params
    }

    fun generateP256(): Pair<PrivateKey, ByteArray> {
      val kp = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
      }
      val w = (kp.public as ECPublicKey).w
      return kp.private to (byteArrayOf(4) + fixed32(w.affineX) + fixed32(w.affineY))
    }

    fun p256Public(uncompressed: ByteArray): java.security.PublicKey {
      val x = BigInteger(1, uncompressed.copyOfRange(1, 33))
      val y = BigInteger(1, uncompressed.copyOfRange(33, 65))
      return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), p256Params))
    }

    fun p256Private(scalar: BigInteger): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(java.security.spec.ECPrivateKeySpec(scalar, p256Params))

    private fun fixed32(v: BigInteger): ByteArray {
      val b = v.toByteArray()
      return when {
        b.size == 32 -> b
        b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
        else -> ByteArray(32 - b.size) + b
      }
    }

    fun buildTranscript(
        deviceId: String,
        nodeId: String,
        mac: String,
        firmwareVersion: String,
        mobilePub: String,
        devicePub: String,
        mobileNonce: String,
        deviceNonce: String,
        policy: String = POLICY_APP,
    ): String =
        listOf(
                "hatch-link-pairing-v$VERSION",
                "version=$VERSION",
                "initiator_role=mobile",
                "responder_role=link",
                "device_id=$deviceId",
                "node_id=$nodeId",
                "mac=$mac",
                "model=$MODEL",
                "firmware_version=$firmwareVersion",
                "selected_cipher_suite=$SUITE",
                "pairing_auth=$AUTH_COMMUNITY",
                "pairing_auth_epoch=0",
                "pairing_policy=$policy",
                "confirm_timeout_seconds=${if (policy == "confirm_press") 60 else 0}",
                "mobile_pub=$mobilePub",
                "device_pub=$devicePub",
                "mobile_nonce=$mobileNonce",
                "device_nonce=$deviceNonce",
            )
            .joinToString("\n")

    fun deriveSessionKeys(ecdh: ByteArray, mobileNonce: ByteArray, deviceNonce: ByteArray, transcriptHash: ByteArray): SessionKeys {
      val salt = sha256(mobileNonce, deviceNonce, transcriptHash)
      val prk = hmacSha256(salt, ecdh)
      val sessionSecret = hmacSha256(prk, RECORD_LABEL.toByteArray() + byteArrayOf(1))
      val mobileTx = hmacSha256(sessionSecret, "mobile->device".toByteArray() + byteArrayOf(1))
      val mobileRx = hmacSha256(sessionSecret, "device->mobile".toByteArray() + byteArrayOf(1))
      val sessionId = sha256("hatch-link session id v1".toByteArray(), transcriptHash, ecdh).copyOf(16)
      return SessionKeys(mobileTx, mobileRx, sessionId)
    }

    fun recordNonce(direction: Int, counter: Long): ByteArray =
        ByteArray(12).also {
          it[0] = direction.toByte()
          for (i in 0 until 8) it[4 + i] = (counter ushr (8 * (7 - i))).toByte()
        }

    private fun parseCounter(text: Any?): Long {
      require(text is String && DECIMAL.matches(text) && text.length <= 19)
      return text.toLong()
    }
  }
}

/** Chunked BLE framing: `0xFE, index, total, payload`, strictly in order. */
internal object MuseBleFraming {
  const val MAGIC = 0xFE
  const val MAX_PACKET = 160
  const val MAX_MESSAGE = 8192

  fun encode(data: ByteArray, mtu: Int): List<ByteArray> {
    val notifyMax = minOf(if (mtu > 3) mtu - 3 else 20, MAX_PACKET)
    val usable = notifyMax - 3
    val total = maxOf(1, (data.size + usable - 1) / usable)
    require(total <= 255) { "message needs $total chunks" }
    return (0 until total).map { i ->
      byteArrayOf(MAGIC.toByte(), i.toByte(), total.toByte()) +
          data.copyOfRange(i * usable, minOf(data.size, (i + 1) * usable))
    }
  }

  class Assembler {
    private val buf = java.io.ByteArrayOutputStream()
    private var total = 0
    private var next = 0

    fun reset() {
      buf.reset()
      total = 0
      next = 0
    }

    /** Adds one write; returns a whole message once available. Unframed writes pass through. */
    fun feed(packet: ByteArray): ByteArray? {
      if (packet.size < 3 || (packet[0].toInt() and 0xff) != MAGIC) return packet
      val index = packet[1].toInt() and 0xff
      val t = packet[2].toInt() and 0xff
      if (t == 0) {
        reset()
        return null
      }
      if (index == 0 || t != total) {
        reset()
        total = t
      }
      if (index != next || index >= total || buf.size() + packet.size - 3 > MAX_MESSAGE) {
        reset()
        return null
      }
      buf.write(packet, 3, packet.size - 3)
      next = index + 1
      if (next < total) return null
      return buf.toByteArray().also { reset() }
    }
  }
}

/**
 * The setup commands behind the GATT characteristics. Independent of Android's Bluetooth stack: a
 * [Transport] delivers writes and sends framed notifications. Messages run one at a time on a
 * worker thread, in arrival order, and every send happens under one lock so encrypted record
 * counters reach the phone in order.
 */
class MuseSetupController(
    private val pairing: MusePairingSession,
    private val identity: MuseIdentity,
    private val version: String,
    private val transport: Transport,
    private val network: Network,
    private val provision: (Credentials, commit: (() -> Boolean) -> Boolean) -> Unit,
    private val onComplete: () -> Unit = {},
) {
  interface Transport {
    fun sendPackets(packets: List<ByteArray>)

    fun mtu(): Int

    fun disconnect(delayMs: Long)
  }

  interface Network {
    fun isOnline(): Boolean

    fun currentConnectionEntry(): JSONObject
  }

  data class Credentials(
      val accessToken: String,
      val refreshToken: String,
      val username: String,
      val apiUrl: String,
      val apiUrlV2: String,
      val noiseHost: String,
  )

  class ProvisionFailed(val status: String) : Exception(status)

  private val assembler = MuseBleFraming.Assembler()
  private val inbox = LinkedBlockingQueue<ByteArray>()
  /** Stop marker, compared by identity: no message from the phone can ever be it. */
  private val stopMarker = ByteArray(0)
  private val txLock = Any()
  private val stateLock = Any()
  private var plaintextBlocked = false
  private var provisioning = false
  private var worker: Thread? = null

  fun onWrite(packet: ByteArray) {
    synchronized(assembler) { assembler.feed(packet) }?.takeIf { it.isNotEmpty() }?.let { inbox.put(it) }
  }

  fun onDisconnect() {
    Log.i(TAG, "BLE client disconnected; clearing pairing session")
    synchronized(assembler) { assembler.reset() }
    synchronized(stateLock) { plaintextBlocked = false }
    pairing.reset()
  }

  fun start() {
    worker =
        Thread({
              try {
                while (true) {
                  val m = inbox.take()
                  if (m === stopMarker) break
                  runCatching { handleMessage(m) }.onFailure { Log.w(TAG, "setup command failed", it) }
                }
              } catch (_: InterruptedException) {}
            }, "muse-ble-setup")
            .apply {
              isDaemon = true
              start()
            }
  }

  fun stop() {
    inbox.put(stopMarker)
  }

  fun handleMessage(raw: ByteArray, decrypted: Boolean = false) {
    val command =
        try {
          JSONObject(String(raw, Charsets.UTF_8))
        } catch (e: Exception) {
          sendStatus("error_invalid_command")
          return
        }
    val action = command.opt("action") as? String ?: ""
    Log.i(TAG, "RX action: ${action.ifEmpty { "?" }}${if (decrypted) " (encrypted)" else ""}")
    val blocked = synchronized(stateLock) { plaintextBlocked }
    when {
      !decrypted && action == "pairing_client_hello" -> handleHello(command)
      !decrypted && action == "pairing_encrypted" -> handleRecord(command)
      action == "get_device_info" -> sendJson(deviceInfo())
      !decrypted && blocked -> Log.w(TAG, "plaintext command ignored after pairing started: $action")
      !decrypted && action in SENSITIVE -> sendStatus("error_encryption_required")
      decrypted && action == "pairing_client_finished" -> handleClientFinished(command)
      decrypted && action in SENSITIVE && !pairing.confirmed -> sendStatus("error_pairing_confirm_required")
      decrypted && action == "wifi_scan" -> handleWifiScan()
      decrypted && action == "provision_v2" -> handleProvision(command)
      else -> sendStatus("error_unknown_action")
    }
  }

  fun deviceInfo(): JSONObject {
    val info =
        JSONObject().put("type", "device_info").put("node_id", identity.nodeId).put("version", version)
    val p = pairing.deviceInfo()
    p.keys().forEach { info.put(it, p.get(it)) }
    return info.put("build_sha", "").put("network_ready", network.isOnline())
  }

  private fun handleHello(command: JSONObject) {
    val ready =
        try {
          pairing.handleHello(command)
        } catch (e: MusePairingException) {
          sendStatus(e.status)
          return
        }
    synchronized(stateLock) { plaintextBlocked = true }
    sendJson(ready)
  }

  private fun handleRecord(envelope: JSONObject) {
    val plaintext =
        try {
          pairing.decrypt(envelope)
        } catch (e: MusePairingException) {
          sendStatus(e.status)
          transport.disconnect(DISCONNECT_AFTER_ERROR_MS)
          return
        }
    handleMessage(plaintext.toByteArray(Charsets.UTF_8), decrypted = true)
  }

  private fun handleClientFinished(command: JSONObject) {
    val gen = pairing.handleClientFinished(command)
    if (gen == 0) {
      sendStatus(MusePairingSession.ERROR_DECRYPT)
      transport.disconnect(DISCONNECT_AFTER_ERROR_MS)
      return
    }
    Log.i(TAG, "pairing confirmed (app consent)")
    sendStatus("pairing_confirmed", gen)
  }

  private fun handleWifiScan() {
    val networks = org.json.JSONArray()
    if (network.isOnline()) networks.put(network.currentConnectionEntry())
    sendEncryptedJson(JSONObject().put("type", "wifi_scan_result").put("networks", networks))
  }

  private fun handleProvision(c: JSONObject) {
    fun text(k: String) = c.opt(k) as? String ?: ""
    if (c.opt("ssid") !is String || c.opt("password") !is String || text("access_token").isEmpty() ||
        text("refresh_token").isEmpty() || text("token_type") != "device") {
      sendStatus("error_missing_credentials")
      return
    }
    val gen: Int
    synchronized(stateLock) {
      if (provisioning) {
        sendStatus("error_operation_in_progress")
        return
      }
      gen = pairing.markProvisioning()
      if (gen == 0) {
        sendStatus("error_pairing_confirm_required")
        return
      }
      provisioning = true
    }
    // The Wi-Fi fields are deliberately dropped: the Portal only sets up while already online.
    val creds =
        Credentials(text("access_token"), text("refresh_token"), text("username"), text("api_url"),
            text("api_url_v2"), text("noise_host"))
    Thread({ runProvision(creds, gen) }, "muse-provision").apply { isDaemon = true }.start()
  }

  private fun runProvision(creds: Credentials, gen: Int) {
    try {
      sendStatus("wifi_connecting", gen)
      if (!network.isOnline()) {
        pairing.extendProvisioning(gen)
        sendStatus("wifi_failed", gen)
        return
      }
      sendStatus("wifi_connected", gen)
      try {
        provision(creds) { save -> pairing.commitProvisioning(gen, save) }
      } catch (e: ProvisionFailed) {
        Log.w(TAG, "provisioning failed: ${e.status}")
        sendStatus(e.status, gen)
        transport.disconnect(500)
        return
      }
      sendStatus("auth_ok", gen)
      Log.i(TAG, "setup complete")
      onComplete()
    } finally {
      synchronized(stateLock) { provisioning = false }
    }
  }

  fun sendStatus(status: String, gen: Int = 0) {
    synchronized(txLock) {
      val envelope = pairing.encryptStatus(status, gen)
      if (envelope != null) {
        sendLocked(envelope.toString())
        return
      }
      val blocked = synchronized(stateLock) { plaintextBlocked }
      if (gen != 0 || blocked || status !in PLAINTEXT_STATUSES) return
      transport.sendPackets(listOf(status.toByteArray()))
    }
  }

  private fun sendJson(obj: JSONObject) = synchronized(txLock) { sendLocked(obj.toString()) }

  private fun sendEncryptedJson(obj: JSONObject, gen: Int = 0) =
      synchronized(txLock) { pairing.encryptJson(obj.toString(), gen)?.let { sendLocked(it.toString()) } }

  private fun sendLocked(text: String) {
    transport.sendPackets(MuseBleFraming.encode(text.toByteArray(Charsets.UTF_8), transport.mtu()))
  }

  private companion object {
    const val TAG = "MuseSetup"
    const val DISCONNECT_AFTER_ERROR_MS = 300L
    val SENSITIVE = setOf("provision", "provision_v2", "wifi_scan", "ota", "device.ota", "unpair", "set_wifi", "set_auth")
    val PLAINTEXT_STATUSES =
        setOf("error_encryption_required", "error_pairing_invalid_hello", "error_pairing_unavailable", "error_pairing_decrypt")
  }
}
