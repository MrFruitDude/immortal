/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Foreground service that keeps this Portal connected to its Muse as a gadget. Mirrors
 * [MqttService]: off until enabled, [sync] starts/stops it, START_STICKY across kills, started
 * from [ImmortalApp] and [BootReceiver]. All the work lives in [MuseRuntime].
 */
class MuseService : Service() {
  override fun onCreate() {
    super.onCreate()
    createChannel()
    startForeground(NOTIF_ID, notification())
    MuseRuntime.start(applicationContext)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_PAIR -> Thread({ MuseRuntime.openPairing(applicationContext) }, "muse-pair-open").start()
      ACTION_STOP_PAIR -> MuseRuntime.closePairing()
      ACTION_RECONNECT -> MuseRuntime.reconnect()
    }
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    MuseRuntime.stop()
    super.onDestroy()
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
      val ch = NotificationChannel(CHANNEL, "Muse", NotificationManager.IMPORTANCE_MIN).apply {
        description = "Keeps this Portal connected to Muse"
      }
      getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }
  }

  private fun notification(): Notification {
    val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
    return b.setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Immortal · Muse")
        .setContentText("Connected to Muse as a gadget")
        .setOngoing(true)
        .build()
  }

  companion object {
    private const val CHANNEL = "muse_gadget"
    private const val NOTIF_ID = 4713
    private const val ACTION_PAIR = "com.immortal.launcher.MUSE_PAIR"
    private const val ACTION_STOP_PAIR = "com.immortal.launcher.MUSE_STOP_PAIR"
    private const val ACTION_RECONNECT = "com.immortal.launcher.MUSE_RECONNECT"

    /** Run when enabled, stop otherwise. Safe to call repeatedly. */
    fun sync(context: Context) {
      val intent = Intent(context, MuseService::class.java)
      if (MuseConfig.isEnabled(context)) start(context, intent) else context.stopService(intent)
    }

    /** Opens the 10-minute Bluetooth pairing window (enabling Muse if it was off). */
    fun pair(context: Context) {
      if (!MuseConfig.isEnabled(context)) MuseConfig.setEnabled(context, true)
      start(context, Intent(context, MuseService::class.java).setAction(ACTION_PAIR))
    }

    fun stopPairing(context: Context) {
      if (MuseConfig.isEnabled(context)) start(context, Intent(context, MuseService::class.java).setAction(ACTION_STOP_PAIR))
    }

    fun reconnect(context: Context) {
      if (MuseConfig.isEnabled(context)) start(context, Intent(context, MuseService::class.java).setAction(ACTION_RECONNECT))
    }

    private fun start(context: Context, intent: Intent) {
      if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }
  }
}

/** Connection state the UI, the fleet route and the voice button read. */
data class MuseStatus(
    val state: State,
    val detail: String = "",
    val vmName: String = "",
    val pairingUntil: Long = 0L,
) {
  enum class State { DISABLED, UNPAIRED, PAIRING, CONNECTING, CONNECTED, OFFLINE }

  fun toJson(): JSONObject =
      JSONObject().put("state", state.name.lowercase()).put("detail", detail).put("muse", vmName)
          .put("pairingOpenUntil", pairingUntil)
}

/**
 * The gadget runtime: keeps one [MuseLink] up (fetch leased VMs with the device token, connect to
 * the default VM, serve commands, reconnect with backoff, rotate tokens), and hosts the BLE
 * pairing window. A port of the SDK's `service.py`, threads instead of asyncio.
 */
object MuseRuntime {
  private const val TAG = "MuseRuntime"
  private const val BACKOFF_BASE_MS = 2_000L
  private const val BACKOFF_MAX_MS = 60_000L
  private const val AUTH_BACKOFF_MIN_MS = 15_000L
  private const val HEALTHY_SESSION_MS = 30_000L
  private const val UNPAIRED_POLL_MS = 30_000L
  /** Device access tokens live about 4 hours; rotate at 3. */
  private const val TOKEN_REFRESH_AGE_S = 3 * 3600L
  private const val TOKEN_RETRY_MS = 300_000L

  @Volatile var status = MuseStatus(MuseStatus.State.DISABLED)
    private set
  private val listeners = CopyOnWriteArrayList<(MuseStatus) -> Unit>()
  @Volatile private var app: Context? = null
  @Volatile private var loop: Thread? = null
  @Volatile private var running = false
  @Volatile private var link: MuseLink? = null
  private val wake = Object()
  private var lastRefreshAttempt = 0L
  private var sdkTokenReported = false

  // Pairing window
  private var ble: MuseBle? = null
  private var setup: MuseSetupController? = null
  private var pairingCloser: Thread? = null

  fun addListener(l: (MuseStatus) -> Unit) = listeners.add(l)

  fun removeListener(l: (MuseStatus) -> Unit) = listeners.remove(l)

  /** The live, registered session, for voice turns and chat. */
  fun currentLink(): MuseLink? = link?.takeIf { it.isRegistered }

  @Synchronized
  fun start(context: Context) {
    app = context.applicationContext
    BuildConfigCompat.versionName =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "dev"
    if (running) return
    running = true
    sdkTokenReported = false
    loop = Thread({ runLoop() }, "muse-runtime").apply {
      isDaemon = true
      start()
    }
  }

  @Synchronized
  fun stop() {
    running = false
    closePairing()
    link?.stop()
    loop?.interrupt()
    loop = null
    publish(MuseStatus(MuseStatus.State.DISABLED))
  }

  /** Drops the current session so the loop reconnects (e.g. after a settings change). */
  fun reconnect() {
    link?.stop()
    synchronized(wake) { wake.notifyAll() }
  }

  // --- the connection loop -------------------------------------------------------

  private fun runLoop() {
    val c = app ?: return
    var failures = 0
    var floor = 0L
    fun nextDelay(): Long = maxOf(minOf(BACKOFF_BASE_MS shl minOf(failures++, 10), BACKOFF_MAX_MS), floor)
    while (running) {
      var pairing = MuseConfig.pairing(c)
      if (pairing == null) {
        if (status.state != MuseStatus.State.PAIRING) publish(MuseStatus(MuseStatus.State.UNPAIRED, "Pair it from the Muse app"))
        sleep(UNPAIRED_POLL_MS)
        continue
      }
      pairing = maybeRefresh(c, pairing) ?: run {
        sleep(TOKEN_RETRY_MS)
        null
      } ?: continue
      publish(status.copy(state = MuseStatus.State.CONNECTING, detail = "Finding your Muse"))
      val root = MuseApi.apiRoot(pairing.apiUrlV2)
      val (vms, httpStatus) = MuseApi.fetchVms(pairing.accessToken, root)
      if (httpStatus == 401) {
        Log.w(TAG, "device token rejected; refreshing")
        if (maybeRefresh(c, pairing, force = true) == null) sleep(TOKEN_RETRY_MS)
        continue
      }
      val vm = vms.firstOrNull { it.isDefault } ?: vms.firstOrNull()
      if (vm == null) {
        publish(MuseStatus(MuseStatus.State.OFFLINE, if (httpStatus == null) "Can't reach Muse" else "No Muse found for this account"))
        sleep(nextDelay())
        continue
      }
      val session = MuseLink(
          noiseHost = pairing.noiseHost.ifEmpty { MuseApi.DEFAULT_NOISE_HOST },
          vmId = vm.id.ifEmpty { vm.name },
          vmToken = vm.token,
          register = registerParams(c),
          runCommand = { cmd, params, timeout -> MuseCommands(c).run(cmd, params, timeout) })
      link = session
      publish(MuseStatus(MuseStatus.State.CONNECTING, "Connecting to ${vm.name.ifEmpty { "Muse" }}", vm.name))
      val watcher = Thread({
        // Flip to CONNECTED once the register reply lands.
        while (running && link === session) {
          if (session.isRegistered) {
            publish(MuseStatus(MuseStatus.State.CONNECTED, "Connected", vm.name, status.pairingUntil))
            return@Thread
          }
          try { Thread.sleep(250) } catch (_: InterruptedException) { return@Thread }
        }
      }, "muse-register-watch").apply { isDaemon = true; start() }
      val outcome = runCatching { session.run() }.getOrElse {
        Log.w(TAG, "session failed: $it")
        MuseLink.Outcome.CLOSED
      }
      watcher.interrupt()
      link = null
      val lasted = if (session.registeredAt > 0) System.currentTimeMillis() - session.registeredAt else 0
      Log.i(TAG, "session ended: $outcome after ${lasted / 1000}s")
      if (!running) return
      when (outcome) {
        MuseLink.Outcome.STOPPED -> continue // reconnect() or a settings change
        MuseLink.Outcome.UNPAIRED -> {
          MuseConfig.clearPairing(c)
          publish(MuseStatus(MuseStatus.State.UNPAIRED, "Muse removed this Portal; pair it again"))
          continue
        }
        else -> Unit
      }
      if (lasted >= HEALTHY_SESSION_MS) {
        failures = 0
        floor = 0
      }
      if (outcome == MuseLink.Outcome.AUTH_REJECTED || outcome == MuseLink.Outcome.FORBIDDEN) floor = AUTH_BACKOFF_MIN_MS
      val delay = nextDelay()
      publish(MuseStatus(MuseStatus.State.OFFLINE, "Reconnecting in ${delay / 1000}s", vm.name, status.pairingUntil))
      sleep(delay)
    }
  }

  private fun registerParams(c: Context): JSONObject {
    val identity = MuseConfig.identity(c)
    // Registered like the Linux SDK's gadget (platform linux, family homehub — Android *is*
    // Linux). Never family "link": the server pushes ESP32 firmware to every link device.
    return JSONObject()
        .put("node_id", identity.nodeId)
        .put("display_name", FleetConfig.name(c))
        .put("platform", "linux")
        .put("version", BuildConfigCompat.versionName)
        .put("device_family", "homehub")
        .put("model_id", "linux")
        .put("is_wakeup_supported", false)
        .put("commands_v2", MuseCommands(c).specs())
  }

  /** Current pairing, rotating tokens first when due; null = don't use the old token yet. */
  private fun maybeRefresh(c: Context, pairing: MusePairingRecord, force: Boolean = false): MusePairingRecord? {
    val sdkToken = MuseConfig.sdkToken(c)
    val age = System.currentTimeMillis() / 1000 - pairing.savedAtSec
    val reportDue = sdkToken != null && !sdkTokenReported
    val due = force || age >= TOKEN_REFRESH_AGE_S
    if (!due && !reportDue) return pairing
    if (!force && System.currentTimeMillis() - lastRefreshAttempt < TOKEN_RETRY_MS) return pairing
    lastRefreshAttempt = System.currentTimeMillis()
    if (reportDue) sdkTokenReported = true
    val identity = MuseConfig.identity(c)
    val (tokens, httpStatus) = MuseApi.refreshDeviceToken(pairing.refreshToken, identity.nodeId, MuseApi.apiRoot(pairing.apiUrlV2), sdkToken)
    if (tokens != null) {
      val next = pairing.copy(accessToken = tokens.first, refreshToken = tokens.second, savedAtSec = System.currentTimeMillis() / 1000)
      MuseConfig.savePairing(c, next)
      Log.i(TAG, "device token rotated")
      return next
    }
    if (!due) return pairing // only reporting the SDK token: never unpair over it
    if (httpStatus == 401) {
      MuseConfig.clearPairing(c)
      publish(MuseStatus(MuseStatus.State.UNPAIRED, "Muse revoked the pairing; pair it again"))
      return null
    }
    return if (force) null else pairing
  }

  // --- pairing window ------------------------------------------------------------

  @Synchronized
  fun openPairing(context: Context): String? {
    val c = context.applicationContext
    app = c
    if (ble != null) return null
    val identity = MuseConfig.identity(c)
    lateinit var controller: MuseSetupController
    val transport = MuseBle(c, identity.bleName, onWrite = { controller.onWrite(it) }, onDisconnect = { controller.onDisconnect() })
    transport.unsupportedReason()?.let {
      publish(status.copy(detail = "Can't pair: $it"))
      return it
    }
    val session = MusePairingSession(identity.nodeId, identity.deviceId, identity.mac, BuildConfigCompat.versionName, MuseConfig.sdkToken(c))
    controller = MuseSetupController(
        pairing = session,
        identity = identity,
        version = BuildConfigCompat.versionName,
        transport = transport,
        network = SetupNetwork(c),
        provision = { creds, commit -> verifyAndSave(c, creds, commit) },
        onComplete = {
          Thread({
            Thread.sleep(1_500)
            closePairing()
            reconnect()
          }, "muse-pair-done").start()
        })
    if (!transport.start()) {
      publish(status.copy(detail = "Couldn't start Bluetooth for pairing"))
      return "couldn't start Bluetooth"
    }
    controller.start()
    ble = transport
    setup = controller
    val until = System.currentTimeMillis() + MuseConfig.PAIRING_WINDOW_MS
    MuseConfig.setPairingWindowUntil(c, until)
    publish(MuseStatus(MuseStatus.State.PAIRING, "In the Muse app: Settings › Devices › Add Device › ${identity.bleName}", status.vmName, until))
    pairingCloser = Thread({
      try {
        Thread.sleep(MuseConfig.PAIRING_WINDOW_MS)
        closePairing()
      } catch (_: InterruptedException) {}
    }, "muse-pair-window").apply { isDaemon = true; start() }
    Log.i(TAG, "pairing open as ${identity.bleName}")
    return null
  }

  @Synchronized
  fun closePairing() {
    val b = ble ?: return
    ble = null
    setup?.stop()
    setup = null
    pairingCloser?.interrupt()
    pairingCloser = null
    runCatching { b.stop() }
    app?.let { MuseConfig.setPairingWindowUntil(it, 0) }
    Log.i(TAG, "pairing closed")
    val c = app
    publish(
        when {
          c == null -> status.copy(pairingUntil = 0)
          link?.isRegistered == true -> status.copy(state = MuseStatus.State.CONNECTED, detail = "Connected", pairingUntil = 0)
          MuseConfig.isPaired(c) -> status.copy(state = MuseStatus.State.CONNECTING, detail = "Connecting", pairingUntil = 0)
          else -> MuseStatus(MuseStatus.State.UNPAIRED, "Pair it from the Muse app")
        })
  }

  private fun verifyAndSave(c: Context, creds: MuseSetupController.Credentials, commit: (() -> Boolean) -> Boolean) {
    val apiV2 = creds.apiUrlV2.takeIf { it.startsWith("https://") } ?: ""
    val (vms, httpStatus) = MuseApi.fetchVms(creds.accessToken, MuseApi.apiRoot(apiV2))
    if (vms.isEmpty()) {
      Log.w(TAG, "device token check failed (HTTP $httpStatus)")
      throw MuseSetupController.ProvisionFailed("auth_failed")
    }
    val record = MusePairingRecord(creds.accessToken, creds.refreshToken, creds.username, apiV2, creds.noiseHost, System.currentTimeMillis() / 1000)
    if (!commit { MuseConfig.savePairing(c, record) }) throw MuseSetupController.ProvisionFailed("error_storage")
  }

  private class SetupNetwork(private val c: Context) : MuseSetupController.Network {
    override fun isOnline(): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress("api.muse.ai", 443), 5_000) }; true }.getOrDefault(false)

    @Suppress("DEPRECATION")
    override fun currentConnectionEntry(): JSONObject {
      val ssid = runCatching { c.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid }.getOrNull()
          ?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
      // Marked open so the app skips the password field; whatever comes back is ignored.
      return JSONObject().put("ssid", ssid ?: "Use current connection").put("rssi", -40).put("secure", false)
    }
  }

  // --- talking to Muse from the Portal -------------------------------------------

  /** Posts a text message to the Muse as coming from this Portal; returns the ack or an error. */
  fun sendChat(text: String, sessionId: String? = null): JSONObject {
    val l = currentLink() ?: return JSONObject().put("ok", false).put("error", "not connected to Muse")
    val c = app ?: return JSONObject().put("ok", false).put("error", "not running")
    val body = JSONObject().put("message", text).put("output_modality", "text").put("device_id", MuseConfig.identity(c).nodeId)
    if (!sessionId.isNullOrEmpty()) body.put("session_id", sessionId)
    val done = CountDownLatch(1)
    val out = ByteArrayOutputStream()
    var httpStatus = 0
    val id = l.openRequest("POST", "/chat/stream",
        listOf("Content-Type" to "application/json", "x-request-id" to UUID.randomUUID().toString(), "x-app-id" to "musegadget"),
        body.toString().toByteArray(), true) { f ->
      when (f) {
        is MuseFrame.Response -> {
          httpStatus = f.status
          out.write(f.body)
          if (f.endBody) done.countDown()
        }
        is MuseFrame.Body -> {
          out.write(f.data)
          if (f.endBody) done.countDown()
        }
        is MuseFrame.Reset -> done.countDown()
      }
    }
    if (id == 0L) return JSONObject().put("ok", false).put("error", "not connected to Muse")
    if (!done.await(60, TimeUnit.SECONDS)) {
      l.cancel(id)
      return JSONObject().put("ok", false).put("error", "timed out")
    }
    val text2 = String(out.toByteArray(), Charsets.UTF_8)
    return JSONObject().put("ok", httpStatus in 200..299).put("status", httpStatus)
        .put("response", runCatching { JSONObject(text2) }.getOrElse { text2.take(2000) })
  }

  private fun publish(s: MuseStatus) {
    status = s
    listeners.forEach { runCatching { it(s) } }
  }

  private fun sleep(ms: Long) {
    try {
      synchronized(wake) { wake.wait(ms) }
    } catch (_: InterruptedException) {}
  }
}
