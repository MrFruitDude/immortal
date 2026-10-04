/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The GATT peripheral the Muse app pairs with: one setup service with an RX characteristic (the
 * phone writes commands) and a notifying TX characteristic (the Portal answers), advertised as
 * `MuseGadgetXXXXXX`. Only up while the pairing window is open. Implements the setup
 * [MuseSetupController.Transport].
 *
 * The adapter's own name is switched to the gadget name for the window (the app reads the GAP
 * name as well as the advertised one) and restored afterwards, as is a Bluetooth radio we had to
 * turn on.
 */
@SuppressLint("MissingPermission") // BLUETOOTH/BLUETOOTH_ADMIN are install-time on API 28/29 Portals
class MuseBle(
    private val context: Context,
    private val localName: String,
    private val onWrite: (ByteArray) -> Unit,
    private val onDisconnect: () -> Unit,
) : MuseSetupController.Transport {

  private val manager = context.getSystemService(BluetoothManager::class.java)
  private val adapter: BluetoothAdapter? = manager?.adapter
  private val main = Handler(Looper.getMainLooper())
  private var server: BluetoothGattServer? = null
  private var tx: BluetoothGattCharacteristic? = null
  @Volatile private var device: BluetoothDevice? = null
  @Volatile private var mtu = MuseBleFraming.MAX_PACKET + 3
  @Volatile private var subscribed = false
  private val notifyGate = Semaphore(1)
  private val prepared = ByteArrayOutputStream()
  private var savedName: String? = null
  private var enabledByUs = false

  private val advertiseCallback = object : AdvertiseCallback() {
    override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
      Log.i(TAG, "advertising as $localName")
    }

    override fun onStartFailure(errorCode: Int) {
      Log.e(TAG, "advertising failed: $errorCode")
    }
  }

  /** Why this Portal can't host BLE setup, or null if it can. */
  fun unsupportedReason(): String? =
      when {
        adapter == null -> "this Portal has no Bluetooth adapter"
        !context.packageManager.hasSystemFeature("android.hardware.bluetooth_le") -> "no Bluetooth LE"
        adapter.isEnabled && !adapter.isMultipleAdvertisementSupported -> "Bluetooth LE advertising isn't supported"
        else -> null
      }

  /** Brings the radio up if needed, opens the GATT server and starts advertising. */
  fun start(): Boolean {
    val a = adapter ?: return false
    if (!a.isEnabled) {
      @Suppress("DEPRECATION")
      if (!a.enable()) return false.also { Log.e(TAG, "couldn't turn Bluetooth on") }
      enabledByUs = true
      val until = System.currentTimeMillis() + 8_000
      while (!a.isEnabled && System.currentTimeMillis() < until) Thread.sleep(100)
      if (!a.isEnabled) return false
    }
    savedName = a.name
    runCatching { a.name = localName }
    val s = manager!!.openGattServer(context, callback) ?: return false.also { Log.e(TAG, "openGattServer failed") }
    server = s
    val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
    service.addCharacteristic(
        BluetoothGattCharacteristic(
            RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE))
    val t = BluetoothGattCharacteristic(
        TX_UUID,
        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ)
    t.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
    service.addCharacteristic(t)
    tx = t
    s.addService(service)
    val advertiser = a.bluetoothLeAdvertiser ?: return false.also { Log.e(TAG, "no LE advertiser") }
    val settings = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        .setConnectable(true)
        .build()
    // 31-byte budget: flags + the 128-bit service UUID + the "paired" manufacturer byte here, the
    // name in the scan response (which is where phones read it from anyway).
    val data = AdvertiseData.Builder()
        .addServiceUuid(ParcelUuid(SERVICE_UUID))
        .addManufacturerData(PAIRED_FLAG_COMPANY_ID, byteArrayOf(0))
        .setIncludeDeviceName(false)
        .build()
    val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
    advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    return true
  }

  fun stop() {
    val a = adapter ?: return
    runCatching { a.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
    device?.let { d -> runCatching { server?.cancelConnection(d) } }
    runCatching { server?.close() }
    server = null
    savedName?.let { n -> runCatching { a.name = n } }
    @Suppress("DEPRECATION")
    if (enabledByUs) runCatching { a.disable() }
    enabledByUs = false
  }

  // --- Transport ----------------------------------------------------------------

  override fun mtu(): Int = mtu

  override fun sendPackets(packets: List<ByteArray>) {
    packets.forEachIndexed { i, packet ->
      if (i > 0) Thread.sleep(CHUNK_STAGGER_MS)
      val d = device ?: return
      val t = tx ?: return
      // One notification in flight at a time: Android drops a notify issued before the previous
      // one's onNotificationSent.
      notifyGate.tryAcquire(1, TimeUnit.SECONDS)
      t.value = packet
      if (server?.notifyCharacteristicChanged(d, t, false) != true) {
        notifyGate.release()
        Log.w(TAG, "notify failed")
      }
    }
  }

  override fun disconnect(delayMs: Long) {
    main.postDelayed({ device?.let { d -> runCatching { server?.cancelConnection(d) } } }, delayMs)
  }

  private val callback = object : BluetoothGattServerCallback() {
    override fun onConnectionStateChange(d: BluetoothDevice, status: Int, newState: Int) {
      if (newState == BluetoothProfile.STATE_CONNECTED) {
        Log.i(TAG, "phone connected")
        device = d
      } else if (newState == BluetoothProfile.STATE_DISCONNECTED && (device == null || device == d)) {
        Log.i(TAG, "phone disconnected")
        device = null
        subscribed = false
        mtu = MuseBleFraming.MAX_PACKET + 3
        notifyGate.drainPermits()
        notifyGate.release()
        onDisconnect()
      }
    }

    override fun onMtuChanged(d: BluetoothDevice, newMtu: Int) {
      Log.i(TAG, "ATT MTU $newMtu")
      mtu = newMtu
    }

    override fun onNotificationSent(d: BluetoothDevice, status: Int) {
      notifyGate.release()
    }

    override fun onCharacteristicReadRequest(d: BluetoothDevice, requestId: Int, offset: Int, c: BluetoothGattCharacteristic) {
      val v = c.value ?: ByteArray(0)
      server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, offset, if (offset < v.size) v.copyOfRange(offset, v.size) else ByteArray(0))
    }

    override fun onCharacteristicWriteRequest(
        d: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic, preparedWrite: Boolean,
        responseNeeded: Boolean, offset: Int, value: ByteArray,
    ) {
      device = d
      if (responseNeeded) server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
      if (c.uuid != RX_UUID) return
      if (preparedWrite) synchronized(prepared) { prepared.write(value) } else onWrite(value)
    }

    override fun onExecuteWrite(d: BluetoothDevice, requestId: Int, execute: Boolean) {
      val data = synchronized(prepared) { prepared.toByteArray().also { prepared.reset() } }
      server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
      if (execute && data.isNotEmpty()) onWrite(data)
    }

    override fun onDescriptorReadRequest(d: BluetoothDevice, requestId: Int, offset: Int, desc: BluetoothGattDescriptor) {
      val v = if (subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
      server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
    }

    override fun onDescriptorWriteRequest(
        d: BluetoothDevice, requestId: Int, desc: BluetoothGattDescriptor, preparedWrite: Boolean,
        responseNeeded: Boolean, offset: Int, value: ByteArray,
    ) {
      if (desc.uuid == CCCD_UUID) {
        subscribed = value.isNotEmpty() && value[0].toInt() != 0
        Log.i(TAG, "phone ${if (subscribed) "subscribed" else "unsubscribed"}")
      }
      if (responseNeeded) server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
    }
  }

  companion object {
    private const val TAG = "MuseBle"
    val SERVICE_UUID: UUID = UUID.fromString("7fdd3d1c-38ea-46cf-8b46-314ecf5f240c")
    val RX_UUID: UUID = UUID.fromString("4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01")
    val TX_UUID: UUID = UUID.fromString("d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    /** Manufacturer data the apps read as a "paired" flag; 0xFFFF is the unassigned company id. */
    const val PAIRED_FLAG_COMPANY_ID = 0xFFFF
    private const val CHUNK_STAGGER_MS = 50L
  }
}
