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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONObject

/** One installable catalog entry. */
data class CatalogApp(
    val name: String,
    val packageName: String,
    val source: String, // "fdroid" | "url"
    val fdroidId: String?,
    val apkUrl: String?,
    val versionCode: Long?, // optional pin (e.g. the arm64 build of a multi-ABI app)
    val description: String,
    val category: String,
)

/** Broadcast action the PackageInstaller session reports status to. */
const val STORE_INSTALL_ACTION = "com.immortal.launcher.STORE_INSTALL_STATUS"
const val STORE_EXTRA_PKG = "pkg"

/**
 * Catalog loader + installer. Reads the JSON catalog (from a hosted URL, with a
 * bundled asset fallback), resolves each entry to a concrete APK URL —
 * F-Droid entries are resolved live via the F-Droid API so they never go
 * stale — downloads it, and installs via [PackageInstaller]. Installs still
 * require the user-confirm dialog unless the device's verifier is disabled
 * (see the provisioning kit).
 */
object StoreCatalog {
  // Point this at your hosted catalog (e.g. a GitHub raw URL). Falls back to the
  // bundled assets/catalog.json if the network copy can't be reached.
  private const val CATALOG_URL =
      "https://raw.githubusercontent.com/starbrightlab/immortal/main/catalog.json"

  private val io = Executors.newSingleThreadExecutor()
  private val main = Handler(Looper.getMainLooper())

  fun loadCatalog(context: Context, onResult: (List<CatalogApp>) -> Unit) {
    io.execute {
      val json =
          runCatching { httpGet(CATALOG_URL) }
              .getOrNull()
              ?: context.assets.open("catalog.json").use { it.readBytes().toString(Charsets.UTF_8) }
      val apps = runCatching { parse(json) }.getOrDefault(emptyList())
      main.post { onResult(apps) }
    }
  }

  private fun parse(json: String): List<CatalogApp> {
    val out = mutableListOf<CatalogApp>()
    val cats = JSONObject(json).getJSONArray("categories")
    for (i in 0 until cats.length()) {
      val cat = cats.getJSONObject(i)
      val catName = cat.getString("name")
      val apps = cat.getJSONArray("apps")
      for (j in 0 until apps.length()) {
        val a = apps.getJSONObject(j)
        out +=
            CatalogApp(
                name = a.getString("name"),
                packageName = a.getString("packageName"),
                source = a.optString("source", "fdroid"),
                fdroidId = a.optString("fdroidId").ifBlank { null },
                apkUrl = a.optString("apkUrl").ifBlank { null },
                versionCode = if (a.has("versionCode")) a.getLong("versionCode") else null,
                description = a.optString("description", ""),
                category = catName,
            )
      }
    }
    return out
  }

  fun isInstalled(context: Context, pkg: String): Boolean =
      runCatching { context.packageManager.getPackageInfo(pkg, 0); true }
          .getOrDefault(false)

  /**
   * Resolve -> download -> commit. [status] is called on the main thread with
   * progress text; terminal SUCCESS/FAILURE arrives via the STORE_INSTALL_ACTION
   * broadcast handled by the host activity.
   */
  fun install(context: Context, app: CatalogApp, status: (String, String) -> Unit) {
    status(app.packageName, "Resolving…")
    io.execute {
      try {
        val url = resolveApkUrl(app)
        main.post { status(app.packageName, "Downloading…") }
        val apk = File(context.cacheDir, "${app.packageName}.apk")
        download(url, apk)
        main.post { status(app.packageName, "Installing…") }
        commit(context, app.packageName, apk)
      } catch (t: Throwable) {
        main.post { status(app.packageName, "Error: ${t.message ?: t.javaClass.simpleName}") }
      }
    }
  }

  private fun resolveApkUrl(app: CatalogApp): String {
    if (app.source == "url" && !app.apkUrl.isNullOrBlank()) return app.apkUrl!!
    val id = app.fdroidId ?: app.packageName
    // A pinned versionCode (e.g. the arm64 build of a multi-ABI app like VLC)
    // wins; otherwise resolve the current suggested build from the F-Droid API.
    val vc =
        app.versionCode
            ?: JSONObject(httpGet("https://f-droid.org/api/v1/packages/$id"))
                .getLong("suggestedVersionCode")
    return "https://f-droid.org/repo/${id}_$vc.apk"
  }

  private fun commit(context: Context, pkg: String, apk: File) {
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
      val intent =
          Intent(STORE_INSTALL_ACTION).setPackage(context.packageName).putExtra(STORE_EXTRA_PKG, pkg)
      val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
      session.commit(pending.intentSender)
    }
  }

  // --- net helpers ------------------------------------------------------------
  private fun httpGet(spec: String): String {
    val c = open(spec)
    return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
  }

  private fun download(spec: String, dest: File) {
    val c = open(spec)
    c.inputStream.use { input -> dest.outputStream.use { input.copyTo(it) } }
  }

  private val File.outputStream
    get() = java.io.FileOutputStream(this)

  private fun open(spec: String): HttpURLConnection {
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 10000
    c.readTimeout = 30000
    c.instanceFollowRedirects = true
    c.setRequestProperty("User-Agent", "PortalStore/1.0")
    return c
  }
}
