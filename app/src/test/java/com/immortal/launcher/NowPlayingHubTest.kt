/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingHubTest {
  private val song = NowPlayingState(PlaybackState.PLAYING, title = "Burn Me Blue", artist = "Remi Wolf", positionMs = 1_000L)

  @Test
  fun positionTick_isTheSameTrack() {
    assertTrue(NowPlayingHub.sameTrack(song, song.copy(positionMs = 4_000L)))
  }

  @Test
  fun pauseNewTrackOrStop_areChanges() {
    assertFalse(NowPlayingHub.sameTrack(song, song.copy(state = PlaybackState.PAUSED)))
    assertFalse(NowPlayingHub.sameTrack(song, song.copy(title = "Liquor Store")))
    assertFalse(NowPlayingHub.sameTrack(song, null))
    assertTrue(NowPlayingHub.sameTrack(null, null))
  }
}
