/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiRoomNowPlayingTest {

  @Test
  fun effective_prefersSnapcastWhenItHasTrackMetadata() {
    val snapcast = track("Snapcast")
    val musicAssistant = track("Music Assistant")

    assertEquals(snapcast, MultiRoomNowPlaying.effective(snapcast, musicAssistant))
  }

  @Test
  fun effective_fallsBackToMusicAssistantWhenSnapcastIsBare() {
    val snapcast = NowPlayingState(PlaybackState.PLAYING)
    val musicAssistant = track("AirPlay")

    assertEquals(musicAssistant, MultiRoomNowPlaying.effective(snapcast, musicAssistant))
  }

  @Test
  fun effective_treatsPausedTrackAsActive() {
    val paused = track("Paused", PlaybackState.PAUSED)

    assertEquals(paused, MultiRoomNowPlaying.effective(null, paused))
    assertTrue(MultiRoomNowPlaying.hasTrack(paused))
  }

  @Test
  fun effective_returnsIdleWhenNeitherSourceHasATrack() {
    assertFalse(MultiRoomNowPlaying.hasTrack(NowPlayingState(PlaybackState.STOPPED, title = "Old")))

    assertEquals(
        NowPlayingState(PlaybackState.IDLE),
        MultiRoomNowPlaying.effective(
            NowPlayingState(PlaybackState.PLAYING),
            NowPlayingState(PlaybackState.STOPPED, title = "Old"),
        ),
    )
  }

  private fun track(title: String, state: PlaybackState = PlaybackState.PLAYING) =
      NowPlayingState(state = state, title = title, artist = "Artist")
}
