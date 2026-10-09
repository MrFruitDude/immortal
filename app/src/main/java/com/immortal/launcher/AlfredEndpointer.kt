/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * Turn detection for Alfred's hands-free conversations: decides, one 100 ms chunk at a time, when
 * someone has started talking and when they've finished. Pure Kotlin (energy only, no Android),
 * so the timing rules are unit-tested on the JVM.
 *
 *  - **Adaptive noise floor.** Tracks the room's quiet level (falls fast, rises slowly) while
 *    nobody is talking, and is frozen while speech is active so a long sentence can't drag the
 *    floor up and cut the speaker off.
 *  - **Start threshold with hysteresis.** Speech starts on a chunk clearly above the floor
 *    ([Config.startFactor]) and is held by a lower "still speaking" threshold.
 *  - **Minimum speech length.** A candidate onset only becomes speech after [Config.minSpeechMs]
 *    of voiced audio; a click, a cough or a door is reported as [Event.NOISE] and discarded.
 *  - **End of turn** after [Config.endOfTurnMs] of trailing quiet, or [Event.MAX_LENGTH] at
 *    [Config.maxTurnMs] (Muse stops listening at 15 s).
 *
 * How long to wait for speech to start at all is the session's business
 * ([AlfredSessionMachine]), not the endpointer's.
 */
class AlfredEndpointer(private val cfg: Config = Config(), initialFloor: Double = DEFAULT_FLOOR) {
  data class Config(
      val chunkMs: Long = 100,
      /** Trailing quiet that ends a turn. */
      val endOfTurnMs: Long = 800,
      /** Voiced audio needed before an onset counts as speech. */
      val minSpeechMs: Long = 300,
      /** A turn is cut here regardless (Muse's voice-note limit). */
      val maxTurnMs: Long = 15_000,
      /** A gap this long inside an unconfirmed onset discards it as noise. */
      val onsetGapMs: Long = 300,
      val startFactor: Double = 2.8,
      val startMin: Double = 450.0,
      val holdFactor: Double = 2.0,
      val holdMin: Double = 320.0,
      val minFloor: Double = 60.0,
  )

  enum class Event { NONE, SPEECH_START, NOISE, END_OF_TURN, MAX_LENGTH }

  /** The room's current quiet level (RMS of 16-bit PCM). */
  var floor = initialFloor.coerceAtLeast(cfg.minFloor)
    private set

  /** True between [Event.SPEECH_START] and the end of the turn. */
  var inSpeech = false
    private set

  /** Chunks since the first voiced chunk of the current onset / turn: how much audio to send. */
  var onsetChunks = 0
    private set

  /** Voiced milliseconds in the current onset / turn. */
  var voicedMs = 0L
    private set

  /** Length of the current turn so far, from its onset. */
  var turnMs = 0L
    private set

  private var quietMs = 0L

  val startThreshold: Double
    get() = maxOf(floor * cfg.startFactor, cfg.startMin)

  val holdThreshold: Double
    get() = maxOf(floor * cfg.holdFactor, cfg.holdMin)

  /** Feeds one chunk's RMS; returns what it means. */
  fun feed(rms: Double): Event {
    if (!inSpeech) {
      if (onsetChunks == 0) {
        if (rms <= startThreshold) {
          adapt(rms)
          return Event.NONE
        }
        onsetChunks = 1
        voicedMs = cfg.chunkMs
        quietMs = 0
      } else {
        onsetChunks++
        if (rms > holdThreshold) {
          voicedMs += cfg.chunkMs
          quietMs = 0
        } else {
          quietMs += cfg.chunkMs
          if (quietMs >= cfg.onsetGapMs) {
            clearTurn()
            adapt(rms)
            return Event.NOISE
          }
        }
      }
      if (voicedMs < cfg.minSpeechMs) return Event.NONE
      inSpeech = true
      turnMs = onsetChunks * cfg.chunkMs
      return Event.SPEECH_START
    }
    onsetChunks++
    turnMs += cfg.chunkMs
    if (rms > holdThreshold) {
      voicedMs += cfg.chunkMs
      quietMs = 0
    } else {
      quietMs += cfg.chunkMs
    }
    if (quietMs >= cfg.endOfTurnMs) {
      inSpeech = false
      return Event.END_OF_TURN
    }
    if (turnMs >= cfg.maxTurnMs) {
      inSpeech = false
      return Event.MAX_LENGTH
    }
    return Event.NONE
  }

  /** Ready for the next turn; the learned noise floor is kept. */
  fun reset() = clearTurn()

  private fun clearTurn() {
    inSpeech = false
    onsetChunks = 0
    voicedMs = 0
    turnMs = 0
    quietMs = 0
  }

  /** Same tracking as the wake listener: falls quickly to quiet, creeps up slowly with noise. */
  private fun adapt(rms: Double) {
    floor = (if (rms < floor) floor * 0.9 + rms * 0.1 else floor * 0.995 + rms * 0.005).coerceAtLeast(cfg.minFloor)
  }

  companion object {
    const val DEFAULT_FLOOR = 300.0

    /** RMS of 16-bit little-endian mono PCM. */
    fun rms(b: ByteArray, len: Int = b.size): Double {
      var sum = 0.0
      var i = 0
      while (i + 1 < len) {
        val v = (b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)
        sum += v.toDouble() * v
        i += 2
      }
      return Math.sqrt(sum / maxOf(1, len / 2))
    }
  }
}
