/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import com.immortal.launcher.AlfredEndpointer.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlfredEndpointerTest {
  private val quiet = 150.0
  private val speech = 2_000.0

  /** Feeds [n] chunks of [rms]; returns the events in order. */
  private fun AlfredEndpointer.run(rms: Double, n: Int): List<Event> = List(n) { feed(rms) }

  /** A room that has settled to [quiet]. */
  private fun settled(cfg: AlfredEndpointer.Config = AlfredEndpointer.Config()) =
      AlfredEndpointer(cfg).apply { run(quiet, 60) }

  @Test
  fun quietRoom_neverStartsAndFloorFallsToTheRoom() {
    val ep = AlfredEndpointer(initialFloor = 300.0)
    assertTrue(ep.run(quiet, 60).all { it == Event.NONE })
    assertFalse(ep.inSpeech)
    assertTrue("floor ${ep.floor} should approach the room", ep.floor < 170)
  }

  @Test
  fun aClickIsNoiseNotSpeech() {
    val ep = settled()
    assertEquals(Event.NONE, ep.feed(speech)) // one loud chunk: a candidate onset only
    val after = ep.run(quiet, 3)
    assertEquals(listOf(Event.NONE, Event.NONE, Event.NOISE), after)
    assertFalse(ep.inSpeech)
    assertEquals(0, ep.onsetChunks)
  }

  @Test
  fun speechStartsAfterTheMinimumVoicedLength() {
    val ep = settled()
    assertEquals(listOf(Event.NONE, Event.NONE, Event.SPEECH_START), ep.run(speech, 3))
    assertTrue(ep.inSpeech)
    assertEquals(3, ep.onsetChunks)
  }

  @Test
  fun aShortGapInsideTheOnsetIsBridged() {
    val ep = settled()
    ep.feed(speech)
    ep.feed(quiet) // 100 ms dip, under the 300 ms onset gap
    ep.feed(speech)
    assertEquals(Event.SPEECH_START, ep.feed(speech))
    assertEquals(4, ep.onsetChunks)
  }

  @Test
  fun aPauseShorterThanEndOfTurnDoesNotEndIt() {
    val ep = settled()
    ep.run(speech, 5)
    assertTrue(ep.run(quiet, 6).all { it == Event.NONE }) // 600 ms: thinking mid-sentence
    assertTrue(ep.inSpeech)
    ep.run(speech, 5)
    assertTrue(ep.inSpeech)
  }

  @Test
  fun eightHundredMsOfSilenceEndsTheTurn() {
    val ep = settled()
    ep.run(speech, 10)
    val events = ep.run(quiet, 8)
    assertEquals(Event.END_OF_TURN, events.last())
    assertTrue(events.dropLast(1).all { it == Event.NONE })
    assertFalse(ep.inSpeech)
  }

  @Test
  fun endOfTurnIsConfigurable() {
    val ep = settled(AlfredEndpointer.Config(endOfTurnMs = 1_200))
    ep.run(speech, 5)
    assertTrue(ep.run(quiet, 11).all { it == Event.NONE })
    assertEquals(Event.END_OF_TURN, ep.feed(quiet))
  }

  @Test
  fun aLongTurnIsCutAtFifteenSeconds() {
    val ep = settled()
    val events = ep.run(speech, 150)
    assertEquals(Event.SPEECH_START, events[2])
    assertEquals(Event.MAX_LENGTH, events.last())
    assertEquals(1, events.count { it == Event.MAX_LENGTH })
  }

  @Test
  fun theFloorIsFrozenWhileSpeaking() {
    val ep = settled()
    val before = ep.floor
    ep.run(speech, 40)
    ep.run(quiet, 8)
    assertEquals(before, ep.floor, 0.0001)
  }

  @Test
  fun hysteresis_quieterSpeechHoldsTheTurnButCannotStartOne() {
    val ep = settled()
    val between = (ep.holdThreshold + ep.startThreshold) / 2
    // Too quiet to start a turn…
    assertTrue(ep.run(between, 3).all { it == Event.NONE })
    assertFalse(ep.inSpeech)
    // …but once speaking, it counts as still talking.
    val ep2 = settled()
    ep2.run(speech, 3)
    assertTrue(ep2.run(between, 20).all { it == Event.NONE })
    assertTrue(ep2.inSpeech)
  }

  @Test
  fun aNoisyRoomRaisesTheBar() {
    val ep = AlfredEndpointer(initialFloor = 300.0)
    ep.run(400.0, 300) // a fan
    assertTrue("start threshold ${ep.startThreshold}", ep.startThreshold > 1_000)
    assertTrue(ep.run(800.0, 5).all { it == Event.NONE }) // the fan getting louder isn't speech
  }

  @Test
  fun resetKeepsTheFloorForTheNextTurn() {
    val ep = settled()
    val floor = ep.floor
    ep.run(speech, 5)
    ep.reset()
    assertFalse(ep.inSpeech)
    assertEquals(0, ep.onsetChunks)
    assertEquals(floor, ep.floor, 0.0001)
    assertEquals(Event.SPEECH_START, ep.run(speech, 3).last())
  }

  @Test
  fun afterTheWakeWord_waitsForTheRequestInsteadOfAssumingSpeech() {
    // "Hey Alfred" … (a pause) … "what's the weather": the endpointer starts out waiting, so the
    // pause isn't an end of turn and the request is caught when it comes.
    val ep = AlfredEndpointer(initialFloor = quiet)
    assertTrue(ep.run(quiet, 20).all { it == Event.NONE })
    assertEquals(Event.SPEECH_START, ep.run(speech, 3).last())
  }

  @Test
  fun rms_ofASquareWaveIsItsAmplitude() {
    val pcm = ByteArray(3200)
    for (i in 0 until 1600) {
      val v = if (i % 2 == 0) 1000 else -1000
      pcm[2 * i] = (v and 0xff).toByte()
      pcm[2 * i + 1] = (v shr 8).toByte()
    }
    assertEquals(1000.0, AlfredEndpointer.rms(pcm), 0.001)
    assertEquals(0.0, AlfredEndpointer.rms(ByteArray(3200)), 0.0)
  }
}
