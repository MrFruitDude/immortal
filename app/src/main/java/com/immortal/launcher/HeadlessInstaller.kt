/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Installs an APK via [PackageInstaller] with NO foreground UI of our own: when the
 * system asks for user confirmation we launch its dialog and let
 * [InstallConfirmService] tap the button. This is the fleet agent's install path
 * on devices where the silent shell daemon isn't running (e.g. after a reboot).
 *
 * Blocks the calling (worker) thread until the install reaches a terminal state or
 * [timeoutMs] elapses. Launching the confirm dialog from the background relies on
 * Immortal holding `SYSTEM_ALERT_WINDOW` (the background-activity-start exemption);
 * without it the dialog won't appear and the install times out — which the agent
 * surfaces as a failure rather than a silent hang.
 *
 * The dialog is a normal activity, so it opens *behind* the photo-frame dream (and on
 * a blank screen nothing is clickable at all). We wake the screen first, which ends the
 * dream (held off from relaunching the frame on top); otherwise installs started while
 * the Portal is idle -- the fleet's and the self-updater's -- time out with the dialog
 * hidden.
 */
object HeadlessInstaller {
  private const val TAG = "ImmortalFleet"
  private const val ACTION = "com.immortal.launcher.FLEET_INSTALL_RESULT"

  fun install(context: Context, apk: File, pkg: String, timeoutMs: Long = 120_000): Boolean {
    val app = context.applicationContext
    val latch = CountDownLatch(1)
    val ok = AtomicBoolean(false)
    // Every install's result arrives on the same action; only act on our own session's.
    val sessionId = AtomicInteger(-1)
    val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(c: Context, intent: Intent) {
            if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2) != sessionId.get()) return
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
              PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm =
                    if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (PresenceHub.current.screen != ScreenState.INTERACTIVE) {
                  DreamPolicy.holdForSystemDialog()
                  ScreenControl.wake(app)
                }
                // Surface the system dialog; InstallConfirmService taps "Install".
                runCatching { app.startActivity(confirm) }
                    .onFailure { Log.w(TAG, "couldn't launch installer dialog for $pkg", it) }
              }
              PackageInstaller.STATUS_SUCCESS -> {
                ok.set(true)
                latch.countDown()
              }
              else -> latch.countDown() // any failure/abort is terminal
            }
          }
        }

    val filter = IntentFilter(ACTION)
    if (Build.VERSION.SDK_INT >= 33)
        app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(receiver, filter)

    return try {
      val pi = app.packageManager.packageInstaller
      val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
      val id = pi.createSession(params)
      sessionId.set(id)
      pi.openSession(id).use { session ->
        session.openWrite("base.apk", 0, apk.length()).use { out ->
          apk.inputStream().use { it.copyTo(out) }
          session.fsync(out)
        }
        val flags =
            if (Build.VERSION.SDK_INT >= 31)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        val pending =
            PendingIntent.getBroadcast(
                app, id, Intent(ACTION).setPackage(app.packageName), flags)
        session.commit(pending.intentSender)
      }
      if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
        // Don't leave the dialog pending: a late confirm would install after we've reported failure.
        Log.w(TAG, "install of $pkg timed out; abandoning session $id")
        runCatching { pi.abandonSession(id) }
      }
      ok.get()
    } catch (t: Throwable) {
      Log.w(TAG, "headless install of $pkg failed", t)
      false
    } finally {
      runCatching { app.unregisterReceiver(receiver) }
    }
  }
}
