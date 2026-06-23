/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoFrameSourceTest {

  @Test
  fun from_defaultsWhenSourceIsUnsetOrIncomplete() {
    assertEquals(PhotoFrameSource.DefaultFeed, PhotoFrameSource.from(ScreensaverConfig.Settings()))
    assertEquals(
        PhotoFrameSource.DefaultFeed,
        PhotoFrameSource.from(ScreensaverConfig.Settings(source = ScreensaverConfig.SOURCE_FOLDER)))
    assertEquals(
        PhotoFrameSource.DefaultFeed,
        PhotoFrameSource.from(ScreensaverConfig.Settings(source = ScreensaverConfig.SOURCE_IMMICH, immichUrl = "http://x")))
  }

  @Test
  fun from_selectsConfiguredPhotoSources() {
    assertEquals(
        PhotoFrameSource.Folder("/photos", includeVideo = false, shuffle = true),
        PhotoFrameSource.from(
            ScreensaverConfig.Settings(
                source = ScreensaverConfig.SOURCE_FOLDER,
                folderPath = "/photos",
                includeVideo = false,
                shuffle = true,
            )))
    assertEquals(
        PhotoFrameSource.SharedAlbum("https://photos.example/album", shuffle = true),
        PhotoFrameSource.from(
            ScreensaverConfig.Settings(
                source = ScreensaverConfig.SOURCE_URL,
                albumUrl = "https://photos.example/album",
                shuffle = true,
            )))
    assertEquals(
        PhotoFrameSource.Immich("http://immich", "key", albumId = "album", shuffle = false),
        PhotoFrameSource.from(
            ScreensaverConfig.Settings(
                source = ScreensaverConfig.SOURCE_IMMICH,
                immichUrl = "http://immich",
                immichKey = "key",
                immichAlbumId = "album",
            )))
  }

  @Test
  fun from_webPageCanBeDisabledForFacePreview() {
    val settings =
        ScreensaverConfig.Settings(
            source = ScreensaverConfig.SOURCE_WEBURL,
            webUrl = "https://dashboard.example",
        )
    assertTrue(PhotoFrameSource.from(settings) is PhotoFrameSource.WebPage)
    assertEquals(PhotoFrameSource.DefaultFeed, PhotoFrameSource.from(settings, allowWebPage = false))
  }

  @Test
  fun setupKey_matchesRemoteSetupVocabulary() {
    assertEquals("default", PhotoFrameSource.DefaultFeed.setupKey)
    assertEquals("album", PhotoFrameSource.SharedAlbum("u", shuffle = false).setupKey)
    assertEquals("web", PhotoFrameSource.WebPage("u").setupKey)
  }
}
