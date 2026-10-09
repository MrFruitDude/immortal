/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * The shape of one hands-free conversation with Alfred, as a small pure state machine (no
 * Android, clock injected) so every path is unit-tested:
 *
 * ```
 * IDLE ─start─▶ LISTENING ─end of turn─▶ THINKING ─reply─▶ SPEAKING ─done─▶ FOLLOW_UP
 *                  ▲                                                          │
 *                  └──────────────── speech (no wake word needed) ◀───────────┘
 *                                    silence ▶ IDLE
 * ```
 *
 * The session ends on: follow-up silence, no speech after it opened, Alfred asking to end
 * (`conversation.end`, honoured once the current reply has been spoken), a stop phrase, the
 * close button, losing the microphone, presence leaving, a failed turn, or the overall cap
 * (also deferred until Alfred finishes, with a hard backstop).
 */
class AlfredSessionMachine(private val cfg: Config = Config()) {
  data class Config(
      /** Re-open the mic after a reply without the wake word. Off = one exchange per session. */
      val followUp: Boolean = true,
      /** How long the follow-up window waits for speech to start. */
      val followUpMs: Long = 8_000,
      /** How long the first turn waits for speech to start (after the wake word or the button). */
      val firstTurnMs: Long = 6_000,
      /** Overall session length; a reply in progress is allowed to finish. */
      val sessionCapMs: Long = 3 * 60_000L,
      /** Hard stop after the cap even if a reply is still going. */
      val capGraceMs: Long = 60_000,
  )

  enum class Phase { IDLE, LISTENING, THINKING, SPEAKING, FOLLOW_UP }

  enum class EndReason {
    NO_SPEECH,
    FOLLOW_UP_SILENCE,
    /** The exchange finished and follow-up listening is off. */
    COMPLETE,
    ALFRED_ENDED,
    STOP_PHRASE,
    USER_CLOSED,
    SESSION_CAP,
    MIC_LOST,
    PRESENCE,
    FAILED,
  }

  var phase = Phase.IDLE
    private set

  /** Why the last session ended (null while one runs or before the first). */
  var endReason: EndReason? = null
    private set

  /** Set when an end was asked for mid-reply: applied when the reply finishes. */
  var pendingEnd: EndReason? = null
    private set

  /** True once speech has started in the current LISTENING phase (a turn is open). */
  var heardSpeech = false
    private set

  /** Completed user turns in this session. */
  var turns = 0
    private set

  private var startedAt = 0L
  private var waitingSince = 0L

  val active: Boolean
    get() = phase != Phase.IDLE

  /** The microphone is live for the user (as opposed to discarded while Alfred thinks/talks). */
  val micLive: Boolean
    get() = phase == Phase.LISTENING || phase == Phase.FOLLOW_UP

  /** Starts a session; false if one is already running. */
  fun start(now: Long): Boolean {
    if (phase != Phase.IDLE) return false
    phase = Phase.LISTENING
    startedAt = now
    waitingSince = now
    heardSpeech = false
    pendingEnd = null
    endReason = null
    turns = 0
    return true
  }

  /** The endpointer confirmed speech: a turn opens (a follow-up becomes a new turn). */
  fun speechStarted(now: Long) {
    if (phase != Phase.LISTENING && phase != Phase.FOLLOW_UP) return
    phase = Phase.LISTENING
    heardSpeech = true
    waitingSince = now
  }

  /** The user finished talking and the voice note went to Muse. */
  fun turnSent() {
    if (phase != Phase.LISTENING || !heardSpeech) return
    phase = Phase.THINKING
    heardSpeech = false
    turns++
  }

  /** A reply started playing. */
  fun speaking() {
    if (phase == Phase.THINKING) phase = Phase.SPEAKING
  }

  /** The turn settled and its replies were spoken. */
  fun turnDone(now: Long) {
    if (phase != Phase.THINKING && phase != Phase.SPEAKING) return
    val pending = pendingEnd
    when {
      pending != null -> end(pending)
      !cfg.followUp -> end(EndReason.COMPLETE)
      else -> {
        phase = Phase.FOLLOW_UP
        waitingSince = now
      }
    }
  }

  fun turnFailed() = end(EndReason.FAILED)

  /**
   * Alfred (Muse's `conversation.end`) or the session cap asks to end. While nobody is talking
   * it ends now; otherwise once the current reply has been spoken. False if no session runs.
   */
  fun requestEnd(reason: EndReason = EndReason.ALFRED_ENDED): Boolean {
    when (phase) {
      Phase.IDLE -> return false
      Phase.FOLLOW_UP -> end(reason)
      Phase.LISTENING -> if (heardSpeech) pendingEnd = pendingEnd ?: reason else end(reason)
      Phase.THINKING, Phase.SPEAKING -> pendingEnd = pendingEnd ?: reason
    }
    return true
  }

  fun close() = end(EndReason.USER_CLOSED)

  fun stopPhrase() = end(EndReason.STOP_PHRASE)

  fun micLost() = end(EndReason.MIC_LOST)

  /** The room emptied: ends a session that's only waiting for speech. */
  fun presenceLeft() {
    if (phase == Phase.FOLLOW_UP || (phase == Phase.LISTENING && !heardSpeech)) end(EndReason.PRESENCE)
  }

  /** Time passes: listening deadlines and the session cap. */
  fun tick(now: Long) {
    if (phase == Phase.IDLE) return
    if (now - startedAt >= cfg.sessionCapMs + cfg.capGraceMs) return end(EndReason.SESSION_CAP)
    if (now - startedAt >= cfg.sessionCapMs) {
      requestEnd(EndReason.SESSION_CAP)
      if (phase == Phase.IDLE) return
    }
    when (phase) {
      Phase.LISTENING -> if (!heardSpeech && now - waitingSince >= cfg.firstTurnMs) end(EndReason.NO_SPEECH)
      Phase.FOLLOW_UP -> if (now - waitingSince >= cfg.followUpMs) end(EndReason.FOLLOW_UP_SILENCE)
      else -> {}
    }
  }

  private fun end(reason: EndReason) {
    if (phase == Phase.IDLE) return
    phase = Phase.IDLE
    endReason = reason
    heardSpeech = false
    pendingEnd = null
  }
}

/**
 * Phrases that end a conversation without sending anything to Muse. Matched against the
 * on-device recogniser's whole result for a short turn, so "stop" ends the call but "stop the
 * music" (heard as "stop [unk]") still goes to Muse.
 */
object AlfredStopPhrases {
  val PHRASES =
      listOf(
          "thanks alfred", "thank you alfred", "thanks", "thank you", "that's all", "that is all",
          "that's it", "stop", "goodbye", "goodbye alfred", "bye alfred", "never mind")

  /** Vosk grammar: the phrases plus [unk] so anything else is absorbed rather than forced to fit. */
  val GRAMMAR: String = (PHRASES + "[unk]").joinToString(", ", "[", "]") { "\"$it\"" }

  /** True when [text] is exactly one stop phrase (ignoring case and spacing). */
  fun matches(text: String): Boolean {
    val t = text.lowercase().replace('’', '\'').trim().replace(Regex("\\s+"), " ")
    if (t.isEmpty() || t.contains("[unk]")) return false
    return t in PHRASES
  }
}
