/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import com.immortal.launcher.AlfredSessionMachine.EndReason
import com.immortal.launcher.AlfredSessionMachine.Phase
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlfredSessionMachineTest {
  private val cfg = AlfredSessionMachine.Config(followUpMs = 8_000, firstTurnMs = 6_000, sessionCapMs = 180_000, capGraceMs = 60_000)

  private fun started(c: AlfredSessionMachine.Config = cfg) = AlfredSessionMachine(c).apply { assertTrue(start(0)) }

  /** One full exchange from LISTENING: speech, sent, spoken, done at [doneAt]. */
  private fun AlfredSessionMachine.exchange(doneAt: Long) {
    speechStarted(doneAt - 3_000)
    turnSent()
    assertEquals(Phase.THINKING, phase)
    speaking()
    assertEquals(Phase.SPEAKING, phase)
    turnDone(doneAt)
  }

  @Test
  fun aConversation_followUpsNeedNoWakeWordAndSilenceEndsIt() {
    val m = started()
    assertEquals(Phase.LISTENING, m.phase)
    assertTrue(m.micLive)
    m.exchange(doneAt = 10_000)
    assertEquals(Phase.FOLLOW_UP, m.phase)
    assertTrue(m.micLive)
    m.tick(15_000)
    assertEquals(Phase.FOLLOW_UP, m.phase)
    m.speechStarted(16_000) // the follow-up: just talking starts the next turn
    assertEquals(Phase.LISTENING, m.phase)
    m.turnSent()
    assertEquals(Phase.THINKING, m.phase)
    assertFalse(m.micLive)
    m.turnDone(30_000) // no reply spoken (captions only): straight to follow-up
    assertEquals(Phase.FOLLOW_UP, m.phase)
    m.tick(37_999)
    assertEquals(Phase.FOLLOW_UP, m.phase)
    m.tick(38_000)
    assertEquals(Phase.IDLE, m.phase)
    assertEquals(EndReason.FOLLOW_UP_SILENCE, m.endReason)
    assertEquals(2, m.turns)
  }

  @Test
  fun nobodySpeaksAfterTheWakeWord_endsAfterTheFirstTurnWindow() {
    val m = started()
    m.tick(5_999)
    assertEquals(Phase.LISTENING, m.phase)
    m.tick(6_000)
    assertEquals(Phase.IDLE, m.phase)
    assertEquals(EndReason.NO_SPEECH, m.endReason)
  }

  @Test
  fun aLongRequestIsNotCutByTheFirstTurnWindow() {
    val m = started()
    m.speechStarted(2_000)
    m.tick(12_000)
    assertEquals(Phase.LISTENING, m.phase)
  }

  @Test
  fun alfredEndsTheCall_afterHisReplyIsSpoken() {
    val m = started()
    m.speechStarted(1_000)
    m.turnSent()
    assertTrue(m.requestEnd())
    assertEquals(Phase.THINKING, m.phase) // not cut off
    assertEquals(EndReason.ALFRED_ENDED, m.pendingEnd)
    m.speaking()
    assertEquals(Phase.SPEAKING, m.phase)
    m.turnDone(9_000)
    assertEquals(Phase.IDLE, m.phase) // no follow-up window
    assertEquals(EndReason.ALFRED_ENDED, m.endReason)
  }

  @Test
  fun alfredEndsTheCall_duringTheFollowUpWindowEndsNow() {
    val m = started()
    m.exchange(doneAt = 5_000)
    assertTrue(m.requestEnd())
    assertEquals(Phase.IDLE, m.phase)
    assertEquals(EndReason.ALFRED_ENDED, m.endReason)
  }

  @Test
  fun anEndRequestWhileTheUserIsTalkingWaitsForTheReply() {
    val m = started()
    m.speechStarted(1_000)
    m.requestEnd()
    assertEquals(Phase.LISTENING, m.phase)
    m.turnSent()
    m.turnDone(5_000)
    assertEquals(EndReason.ALFRED_ENDED, m.endReason)
  }

  @Test
  fun anEndRequestWithNoSession_isHarmless() {
    val m = AlfredSessionMachine(cfg)
    assertFalse(m.requestEnd())
    assertEquals(Phase.IDLE, m.phase)
    assertNull(m.endReason)
  }

  @Test
  fun closeEndsFromEveryPhase() {
    val setups: List<(AlfredSessionMachine) -> Unit> =
        listOf(
            {},
            { it.speechStarted(100) },
            { it.speechStarted(100); it.turnSent() },
            { it.speechStarted(100); it.turnSent(); it.speaking() },
            { it.exchange(5_000) },
        )
    setups.forEach { setup ->
      val m = started()
      setup(m)
      m.close()
      assertEquals(Phase.IDLE, m.phase)
      assertEquals(EndReason.USER_CLOSED, m.endReason)
      assertFalse(m.micLive)
    }
  }

  @Test
  fun followUpOff_oneExchangePerSession() {
    val m = started(cfg.copy(followUp = false))
    m.exchange(doneAt = 5_000)
    assertEquals(Phase.IDLE, m.phase)
    assertEquals(EndReason.COMPLETE, m.endReason)
  }

  @Test
  fun aFailedTurnEndsTheSession() {
    val m = started()
    m.speechStarted(100)
    m.turnFailed()
    assertEquals(Phase.IDLE, m.phase)
    assertEquals(EndReason.FAILED, m.endReason)
  }

  @Test
  fun stopPhraseAndMicLossEndIt() {
    started().also {
      it.speechStarted(100)
      it.stopPhrase()
      assertEquals(EndReason.STOP_PHRASE, it.endReason)
    }
    started().also {
      it.exchange(5_000)
      it.micLost()
      assertEquals(EndReason.MIC_LOST, it.endReason)
    }
  }

  @Test
  fun presenceLeaving_endsOnlyAWaitingSession() {
    val m = started()
    m.speechStarted(100)
    m.presenceLeft() // someone is talking: presence sensing is wrong, keep going
    assertEquals(Phase.LISTENING, m.phase)
    m.turnSent()
    m.turnDone(5_000)
    m.presenceLeft()
    assertEquals(Phase.IDLE, m.phase)
    assertEquals(EndReason.PRESENCE, m.endReason)
  }

  @Test
  fun theCap_waitsForAReplyInProgressThenEnds() {
    val m = started()
    m.exchange(doneAt = 170_000)
    m.speechStarted(171_000)
    m.turnSent()
    m.speaking()
    m.tick(180_000)
    assertEquals(Phase.SPEAKING, m.phase)
    assertEquals(EndReason.SESSION_CAP, m.pendingEnd)
    m.turnDone(185_000)
    assertEquals(Phase.IDLE, m.phase)
    assertEquals(EndReason.SESSION_CAP, m.endReason)
  }

  @Test
  fun theCap_endsAWaitingSessionAtOnce() {
    val m = started()
    m.exchange(doneAt = 179_000)
    m.tick(180_000)
    assertEquals(Phase.IDLE, m.phase)
    assertEquals(EndReason.SESSION_CAP, m.endReason)
  }

  @Test
  fun theCap_hasAHardBackstop() {
    val m = started()
    m.exchange(doneAt = 175_000)
    m.speechStarted(176_000)
    m.turnSent()
    m.tick(239_999)
    assertEquals(Phase.THINKING, m.phase)
    m.tick(240_000)
    assertEquals(Phase.IDLE, m.phase)
    assertEquals(EndReason.SESSION_CAP, m.endReason)
  }

  @Test
  fun eventsOutOfOrderAreIgnored() {
    val m = started()
    m.turnSent() // nothing said yet
    assertEquals(Phase.LISTENING, m.phase)
    m.speaking()
    m.turnDone(100)
    assertEquals(Phase.LISTENING, m.phase)
    assertEquals(0, m.turns)
    assertFalse(m.start(200)) // already running
  }

  @Test
  fun aSecondSessionStartsClean() {
    val m = started()
    m.speechStarted(100)
    m.turnSent()
    m.requestEnd()
    m.close()
    assertTrue(m.start(1_000))
    assertEquals(Phase.LISTENING, m.phase)
    assertNull(m.endReason)
    assertNull(m.pendingEnd)
    assertEquals(0, m.turns)
  }

  @Test
  fun stopPhrases_wholeUtteranceOnly() {
    listOf("thanks alfred", "Thank you Alfred", "  stop ", "that's all", "that’s all", "goodbye", "never mind")
        .forEach { assertTrue(it, AlfredStopPhrases.matches(it)) }
    listOf("", "[unk]", "stop [unk]", "stop the music", "thanks alfred [unk]", "alfred", "hey alfred")
        .forEach { assertFalse(it, AlfredStopPhrases.matches(it)) }
  }

  @Test
  fun stopPhrases_grammarIsAValidVoskList() {
    val g = JSONArray(AlfredStopPhrases.GRAMMAR)
    val items = List(g.length()) { g.getString(it) }
    assertTrue("[unk]" in items)
    assertTrue(AlfredStopPhrases.PHRASES.all { it in items })
  }
}
