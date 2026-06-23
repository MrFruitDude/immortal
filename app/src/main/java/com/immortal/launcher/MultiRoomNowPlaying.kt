/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * Selects the effective multi-room now-playing state from the Snapcast relay and the
 * Music Assistant player poll.
 *
 * Snapcast wins when it carries real track metadata. Music Assistant fills in when
 * Snapcast only exposes an idle/bare stream, such as AirPlay bridged into a group.
 */
object MultiRoomNowPlaying {
  fun effective(
      snapcast: NowPlayingState?,
      musicAssistant: NowPlayingState?,
  ): NowPlayingState =
      when {
        hasTrack(snapcast) -> snapcast!!
        hasTrack(musicAssistant) -> musicAssistant!!
        else -> NowPlayingState(PlaybackState.IDLE)
      }

  internal fun hasTrack(state: NowPlayingState?): Boolean = state?.active == true
}
