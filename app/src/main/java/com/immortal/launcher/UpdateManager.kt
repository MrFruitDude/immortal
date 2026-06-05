/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONObject

/** A newer build advertised by the remote manifest. */
data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
)

const val UPDATE_INSTALL_ACTION = "com.immortal.launcher.UPDATE_INSTALL_STATUS"

/**
 * Over-the-air self-update. Immortal fetches a hosted `version.json`; if it
 * advertises a higher `versionCode` than what's installed, it downloads the APK
 * and installs it over itself via [PackageInstaller] — no laptop, no ADB. Works
 * because the provisioning step disables Meta's verifier, and an in-place update
 * is signature-checked against the running app (so ship every build with the
 * same signing key).
 */
object UpdateManager {
  // Point this at your hosted manifest (e.g. a GitHub raw URL). Overridable so
  // the same field can target a test server on the LAN.
  @Volatile
  var updateUrl: String =
      "https://raw.githubusercontent.com/starbrightlab/immortal/main/version.json"

  private val io = Executors.newSingleThreadExecutor()
  private val main = Handler(Looper.getMainLooper())

  /** Returns an [UpdateInfo] on the main thread only if a newer build exists. */
  fun checkForUpdate(context: Context, onResult: (UpdateInfo?) -> Unit) {
    io.execute {
      val available =
          runCatching {
                val j = JSONObject(httpGet(resolveUrl(context)))
                val info =
                    UpdateInfo(
                        versionCode = j.getLong("versionCode"),
                        versionName = j.optString("versionName"),
                        apkUrl = j.getString("apkUrl"),
                        notes = j.optString("notes"),
                    )
                if (info.versionCode > installedVersionCode(context)) info else null
              }
              .getOrNull()
      main.post { onResult(available) }
    }
  }

  /** Downloads and commits the update; status text is posted on the main thread. */
  fun installUpdate(context: Context, info: UpdateInfo, status: (String) -> Unit) {
    status("Downloading update…")
    io.execute {
      try {
        val apk = File(context.cacheDir, "immortal-update.apk")
        download(info.apkUrl, apk)
        main.post { status("Installing…") }
        commit(context, apk)
      } catch (t: Throwable) {
        main.post { status("Update failed: ${t.message ?: t.javaClass.simpleName}") }
      }
    }
  }

  // Lets a pushed file override the manifest URL (handy for testing against a
  // LAN server without rebuilding). Falls back to [updateUrl].
  private fun resolveUrl(context: Context): String {
    val f = File(context.getExternalFilesDir(null), "update_url.txt")
    return if (f.exists()) runCatching { f.readText().trim() }.getOrDefault(updateUrl) else updateUrl
  }

  fun installedVersionCode(context: Context): Long =
      runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
          }
          .getOrDefault(0L)

  private fun commit(context: Context, apk: File) {
    val pi = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    val sessionId = pi.createSession(params)
    pi.openSession(sessionId).use { session ->
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
              context,
              sessionId,
              Intent(UPDATE_INSTALL_ACTION).setPackage(context.packageName),
              flags,
          )
      session.commit(pending.intentSender)
    }
  }

  private fun httpGet(spec: String): String = open(spec).inputStream.use {
    it.readBytes().toString(Charsets.UTF_8)
  }

  private fun download(spec: String, dest: File) {
    open(spec).inputStream.use { input ->
      java.io.FileOutputStream(dest).use { input.copyTo(it) }
    }
  }

  private fun open(spec: String): HttpURLConnection {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 10000
    c.readTimeout = 30000
    c.instanceFollowRedirects = true
    c.setRequestProperty("User-Agent", "Immortal/1.0")
    return c
  }
}
