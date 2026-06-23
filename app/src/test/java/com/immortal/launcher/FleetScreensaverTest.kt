package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/** Pure JSON-shaping + coercion for the fleet `/screensaver` endpoint (no Context). */
class FleetScreensaverTest {

  @Test
  fun toJson_mirrorsDefaults() {
    val json = FleetScreensaver.toJson(ScreensaverConfig.Settings())
    assertTrue(json.getBoolean("enabled"))
    assertEquals(ScreensaverConfig.SOURCE_DEFAULT, json.getString("source"))
    assertEquals("", json.getString("folderPath"))
    assertEquals("", json.getString("albumUrl"))
    assertEquals(ScreensaverConfig.FIT_FILL, json.getString("fit"))
    assertEquals(ScreensaverConfig.DEFAULT_INTERVAL, json.getInt("intervalSec"))
    assertFalse(json.getBoolean("shuffle"))
    assertTrue(json.getBoolean("includeVideo"))
    assertTrue(json.getBoolean("showNowPlaying"))
    assertEquals(FrameMode.ALWAYS_ON.name, json.getString("presenceMode"))
    assertEquals(0, json.getInt("idleSleepMin"))
    assertFalse(json.getBoolean("overnightEnabled"))
  }

  @Test
  fun toJson_reflectsCustomSettings() {
    val s =
        ScreensaverConfig.Settings(
            enabled = false,
            source = ScreensaverConfig.SOURCE_URL,
            albumUrl = "https://photos.app.goo.gl/abc",
            fit = ScreensaverConfig.FIT_FIT,
            intervalSec = 45,
            shuffle = true,
            includeVideo = false,
            presenceMode = FrameMode.PRESENCE,
            idleSleepMin = 30,
            overnightEnabled = true,
            overnightStartMin = 22 * 60,
            overnightEndMin = 7 * 60)
    val json = FleetScreensaver.toJson(s)
    assertFalse(json.getBoolean("enabled"))
    assertEquals(ScreensaverConfig.SOURCE_URL, json.getString("source"))
    assertEquals("https://photos.app.goo.gl/abc", json.getString("albumUrl"))
    assertEquals(ScreensaverConfig.FIT_FIT, json.getString("fit"))
    assertEquals(45, json.getInt("intervalSec"))
    assertTrue(json.getBoolean("shuffle"))
    assertFalse(json.getBoolean("includeVideo"))
    assertEquals(FrameMode.PRESENCE.name, json.getString("presenceMode"))
    assertEquals(30, json.getInt("idleSleepMin"))
    assertTrue(json.getBoolean("overnightEnabled"))
    assertEquals(1320, json.getInt("overnightStartMin"))
    assertEquals(420, json.getInt("overnightEndMin"))
  }

  @Test
  fun coerceFit_acceptsKnownElseNull() {
    assertEquals(ScreensaverConfig.FIT_FILL, FleetScreensaver.coerceFit("fill"))
    assertEquals(ScreensaverConfig.FIT_FIT, FleetScreensaver.coerceFit("fit"))
    assertNull(FleetScreensaver.coerceFit("stretch"))
    assertNull(FleetScreensaver.coerceFit(null))
  }

  @Test
  fun coercePresenceMode_parsesOrNull() {
    assertEquals(FrameMode.PRESENCE, FleetScreensaver.coercePresenceMode("PRESENCE"))
    assertEquals(FrameMode.PRESENCE, FleetScreensaver.coercePresenceMode("presence"))
    assertEquals(FrameMode.ALWAYS_ON, FleetScreensaver.coercePresenceMode("ALWAYS_ON"))
    // Unknown / null → null, so apply() skips it instead of flipping the mode.
    assertNull(FleetScreensaver.coercePresenceMode("garbage"))
    assertNull(FleetScreensaver.coercePresenceMode(null))
  }

  // --- sourcesJson: the photo-source setup the remote's Setup form pre-fills. Pure. ------------
  // Characterization of the current wire shape — pins the secret/source fields before they move
  // to a credential GroupSpec, since they had no coverage previously.

  @Test
  fun sourcesJson_defaultsBlank() {
    val json = FleetScreensaver.sourcesJson(ScreensaverConfig.Settings())
    assertEquals("default", json.getString("source"))
    for (k in
        listOf(
            "immichUrl",
            "immichKey",
            "smbHost",
            "smbShare",
            "smbPath",
            "smbUser",
            "smbPass",
            "davUrl",
            "davUser",
            "davPass",
            "webUrl",
            "albumUrl")) {
      assertEquals("blank field $k", "", json.getString(k))
    }
  }

  @Test
  fun sourcesJson_reportsImmichWithSecret() {
    val s =
        ScreensaverConfig.Settings(
            source = ScreensaverConfig.SOURCE_IMMICH,
            immichUrl = "https://immich.example",
            immichKey = "secret-key")
    val json = FleetScreensaver.sourcesJson(s)
    assertEquals("immich", json.getString("source"))
    assertEquals("https://immich.example", json.getString("immichUrl"))
    assertEquals("secret-key", json.getString("immichKey"))
  }

  @Test
  fun sourcesJson_reportsSmbWithPassword() {
    val s =
        ScreensaverConfig.Settings(
            source = ScreensaverConfig.SOURCE_SMB,
            smbHost = "nas.local",
            smbShare = "photos",
            smbPass = "hunter2")
    val json = FleetScreensaver.sourcesJson(s)
    assertEquals("smb", json.getString("source"))
    assertEquals("nas.local", json.getString("smbHost"))
    assertEquals("photos", json.getString("smbShare"))
    assertEquals("hunter2", json.getString("smbPass"))
  }

  @Test
  fun currentSource_mapsActiveSource() {
    fun src(s: ScreensaverConfig.Settings) = FleetScreensaver.currentSource(s)
    assertEquals("default", src(ScreensaverConfig.Settings()))
    assertEquals(
        "album",
        src(
            ScreensaverConfig.Settings(
                source = ScreensaverConfig.SOURCE_URL, albumUrl = "https://photos.app.goo.gl/x")))
    assertEquals(
        "web",
        src(
            ScreensaverConfig.Settings(
                source = ScreensaverConfig.SOURCE_WEBURL, webUrl = "https://kiosk.local")))
    assertEquals(
        "dav",
        src(
            ScreensaverConfig.Settings(
                source = ScreensaverConfig.SOURCE_DAV, davUrl = "https://dav.example/photos")))
  }

  @Test
  fun apply_writesOnlyPresentDisplayKeys() {
    val writer = RecordingWriter()
    val applied =
        FleetScreensaver.apply(
            writer,
            JSONObject()
                .put("enabled", false)
                .put("fit", ScreensaverConfig.FIT_FIT)
                .put("intervalSec", 45)
                .put("presenceMode", "presence"))

    assertEquals(listOf("enabled", "fit", "intervalSec", "presenceMode"), applied)
    assertEquals(
        listOf("enabled=false", "fit=fit", "interval=45", "presence=PRESENCE"),
        writer.calls,
    )
  }

  @Test
  fun apply_keepsCredentialSourcesAtomic() {
    val partialWriter = RecordingWriter()
    val partialApplied =
        FleetScreensaver.apply(partialWriter, JSONObject().put("immichUrl", "https://immich.local"))

    assertEquals(emptyList<String>(), partialApplied)
    assertEquals(emptyList<String>(), partialWriter.calls)

    val fullWriter = RecordingWriter()
    val fullApplied =
        FleetScreensaver.apply(
            fullWriter,
            JSONObject().put("immichUrl", "https://immich.local").put("immichKey", "secret"),
        )

    assertEquals(listOf("immich"), fullApplied)
    assertEquals(listOf("immich=https://immich.local|secret"), fullWriter.calls)
  }

  @Test
  fun apply_ignoresUnknownFitAndPresenceMode() {
    val writer = RecordingWriter()
    val applied =
        FleetScreensaver.apply(
            writer,
            JSONObject().put("fit", "stretch").put("presenceMode", "sometimes"),
        )

    assertEquals(emptyList<String>(), applied)
    assertEquals(emptyList<String>(), writer.calls)
  }

  @Test
  fun apply_sourceResetAndFolderUseWriterSeam() {
    val writer = RecordingWriter()
    val applied =
        FleetScreensaver.apply(
            writer,
            JSONObject()
                .put("source", ScreensaverConfig.SOURCE_DEFAULT)
                .put("folderPath", "/sdcard/Pictures"),
        )

    assertEquals(listOf("source", "folderPath"), applied)
    assertEquals(listOf("default", "folder=/sdcard/Pictures"), writer.calls)
  }

  private class RecordingWriter : FleetScreensaver.Writer {
    val calls = mutableListOf<String>()

    override fun setEnabled(value: Boolean) {
      calls += "enabled=$value"
    }

    override fun useDefault() {
      calls += "default"
    }

    override fun setFolder(path: String) {
      calls += "folder=$path"
    }

    override fun setAlbumUrl(url: String) {
      calls += "album=$url"
    }

    override fun setImmich(url: String, key: String) {
      calls += "immich=$url|$key"
    }

    override fun setSmb(host: String, share: String, path: String, user: String, pass: String) {
      calls += "smb=$host|$share|$path|$user|$pass"
    }

    override fun setDav(url: String, user: String, pass: String) {
      calls += "dav=$url|$user|$pass"
    }

    override fun setWebUrl(url: String) {
      calls += "web=$url"
    }

    override fun setAlbumRefreshMin(value: Int) {
      calls += "albumRefresh=$value"
    }

    override fun setFit(value: String) {
      calls += "fit=$value"
    }

    override fun setInterval(value: Int) {
      calls += "interval=$value"
    }

    override fun setShuffle(value: Boolean) {
      calls += "shuffle=$value"
    }

    override fun setIncludeVideo(value: Boolean) {
      calls += "includeVideo=$value"
    }

    override fun setBatterySaver(value: Boolean) {
      calls += "batterySaver=$value"
    }

    override fun setShowNowPlaying(value: Boolean) {
      calls += "showNowPlaying=$value"
    }

    override fun setPresenceMode(value: FrameMode) {
      calls += "presence=${value.name}"
    }

    override fun setIdleSleepMin(value: Int) {
      calls += "idleSleep=$value"
    }

    override fun setOvernightEnabled(value: Boolean) {
      calls += "overnightEnabled=$value"
    }

    override fun setOvernightStartMin(value: Int) {
      calls += "overnightStart=$value"
    }

    override fun setOvernightEndMin(value: Int) {
      calls += "overnightEnd=$value"
    }
  }
}
