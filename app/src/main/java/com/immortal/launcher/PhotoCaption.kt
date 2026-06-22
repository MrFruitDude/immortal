/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.exifinterface.media.ExifInterface
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Photo metadata for the screensaver caption — the "taken at <place> · <date>" line,
 * tvOS-style. Read from EXIF, so it only exists for the user's *own* photos: the local
 * folder and SMB (NAS) sources, which decode real image files. The web/CDN sources
 * (Picsum/Unsplash, iCloud/Google shared albums, Immich) serve re-encoded images with
 * EXIF stripped, so there's nothing to show there and the caption is simply hidden.
 *
 * Everything is best-effort: a missing date, absent GPS, or a failed lookup just means
 * less (or no) caption — never a crash and never a blocked slideshow.
 */
object PhotoCaption {

  /** Capture date (epoch millis) and GPS coordinates pulled from a photo's EXIF block. */
  data class Meta(val dateMillis: Long?, val lat: Double?, val lng: Double?) {
    val hasLocation: Boolean
      get() = lat != null && lng != null

    val isEmpty: Boolean
      get() = dateMillis == null && !hasLocation
  }

  /** Pull capture date + GPS from an already-opened [ExifInterface]. Best-effort. */
  fun read(exif: ExifInterface): Meta {
    val dateMillis =
        runCatching {
              val raw =
                  exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                      ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
              raw?.let { synchronized(EXIF_DATE) { EXIF_DATE.parse(it) }?.time }
            }
            .getOrNull()
    val latLng = runCatching { exif.getLatLong() }.getOrNull()
    return Meta(dateMillis, latLng?.get(0), latLng?.get(1))
  }

  // EXIF stores capture time as "yyyy:MM:dd HH:mm:ss". SimpleDateFormat isn't thread-safe,
  // so callers (which run on a background thread) synchronize on it.
  private val EXIF_DATE = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

  /** Friendly capture date, e.g. "June 22, 2026" in the device locale. Null when absent. */
  fun formatDate(millis: Long?): String? =
      millis?.let { SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(it)) }

  // --- reverse geocoding (keyless) -------------------------------------------
  // BigDataCloud's reverse-geocode-client endpoint needs no key — the same keyless-web-service
  // approach Immortal already uses for weather (Open-Meteo) and IP geolocation. Results are
  // cached by a coarse (~1km) lat/lng key, and misses are cached too, so a place is looked up
  // at most once and the slideshow never re-hits the network for the same spot.
  private val cache = ConcurrentHashMap<String, String>()

  /**
   * Reverse-geocode coordinates to a "City, Country" label. Network call, best-effort:
   * returns null on any failure. MUST be called off the main thread.
   */
  fun placeName(lat: Double, lng: Double): String? {
    val key = "%.2f,%.2f".format(Locale.US, lat, lng)
    cache[key]?.let { return it.ifEmpty { null } }
    val place = runCatching { fetchPlace(lat, lng) }.getOrNull()
    cache[key] = place ?: "" // cache misses too, so a dead/edge lookup isn't retried each photo
    return place
  }

  private fun fetchPlace(lat: Double, lng: Double): String? {
    val spec =
        "https://api.bigdatacloud.net/data/reverse-geocode-client" +
            "?latitude=$lat&longitude=$lng&localityLanguage=en"
    val c = URL(spec).openConnection() as HttpURLConnection
    c.connectTimeout = 8000
    c.readTimeout = 8000
    c.setRequestProperty("User-Agent", "PortalPhotoFrame/1.0")
    val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    val o = JSONObject(body)
    val city = o.optString("city", "").ifBlank { o.optString("locality", "") }
    val region = o.optString("principalSubdivision", "")
    val country = o.optString("countryName", "")
    val primary = city.ifBlank { region }
    return when {
      primary.isNotBlank() && country.isNotBlank() -> "$primary, $country"
      primary.isNotBlank() -> primary
      country.isNotBlank() -> country
      else -> null
    }
  }
}
