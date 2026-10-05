/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Alfred: the character and the conversation on this Portal. One owner for the current voice
 * turn whichever way it started (push-to-talk on the Muse screen, or "Hey Alfred"), the avatar's
 * mode and live level, and a short transcript the UI shows. The UI only observes; the wake-word
 * engine and the screen both drive turns through here.
 */
object Alfred {
  private const val TAG = "Alfred"
  const val NAME = "Alfred"

  /** The avatar's modes, mirroring the Muse gadgets' (boot, idle, listening, …). */
  enum class Mode { BOOT, IDLE, LISTENING, THINKING, SPEAKING, ERROR, OFF }

  data class Line(val fromAlfred: Boolean, val text: String, val at: Long = System.currentTimeMillis())

  data class State(
      val mode: Mode = Mode.IDLE,
      val caption: String = "",
      val transcript: List<Line> = emptyList(),
      /** The turn started from the wake word: the screen closes itself when it's over. */
      val fromWake: Boolean = false,
      val turnActive: Boolean = false,
  )

  @Volatile var state = State()
    private set

  /** Live 0..1 audio level: the mic while listening, a speech envelope while speaking. */
  @Volatile var level = 0f
    internal set

  /** Wall-clock time of the last pet; the avatar eases a happy reaction out from here. */
  @Volatile var pettedAt = 0L
    private set

  private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
  @Volatile internal var turn: MuseVoiceTurn? = null
  internal val lock = Any()

  fun addListener(l: (State) -> Unit) = listeners.add(l)

  fun removeListener(l: (State) -> Unit) = listeners.remove(l)

  val busy: Boolean
    get() = turn != null || MuseSpeech.isSpeaking

  fun pet() {
    pettedAt = System.currentTimeMillis()
  }

  /** Push-to-talk from the screen. Blocking (opens the mic and a stream): call off main. */
  fun startPushToTalk(context: Context): Boolean {
    val t = newTurn(context, fromWake = false) ?: return false
    if (!t.begin()) {
      clearTurn(t)
      return false
    }
    return true
  }

  fun endPushToTalk() {
    turn?.end()
  }

  /**
   * The wake word fired: brings the Muse screen forward and returns a turn the wake engine feeds
   * with the audio it captures (null if Muse isn't connected). Blocking: call off main.
   */
  fun startFromWake(context: Context, preroll: ByteArray): MuseVoiceTurn? {
    val t = newTurn(context, fromWake = true) ?: return null
    showScreen(context, fromWake = true)
    if (!t.beginExternal(preroll)) {
      clearTurn(t)
      return null
    }
    return t
  }

  fun cancel() {
    turn?.cancel()
    turn = null
    MuseSpeech.stop()
    update { it.copy(mode = Mode.IDLE, caption = "", turnActive = false) }
  }

  /** Something on the Portal (or Muse via a command) wants Alfred to say a line out loud. */
  fun announce(line: String) {
    addLine(Line(true, line))
  }

  fun showScreen(context: Context, fromWake: Boolean) {
    DreamPolicy.userExitAt = System.currentTimeMillis()
    ScreenControl.wake(context)
    runCatching {
      context.startActivity(
          Intent(context, MuseActivity::class.java)
              .putExtra(MuseActivity.EXTRA_FROM_WAKE, fromWake)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }.onFailure { Log.w(TAG, "couldn't bring the Muse screen forward", it) }
  }

  private fun newTurn(context: Context, fromWake: Boolean): MuseVoiceTurn? {
    val link = MuseRuntime.currentLink()
    if (link == null) {
      update { it.copy(mode = Mode.ERROR, caption = "I can't reach Muse right now") }
      return null
    }
    synchronized(lock) {
      turn?.cancel()
      val t = MuseVoiceTurn(context.applicationContext, link, MuseConfig.identity(context).nodeId, Listener())
      turn = t
      update { it.copy(fromWake = fromWake, turnActive = true) }
      return t
    }
  }

  private fun clearTurn(t: MuseVoiceTurn) {
    synchronized(lock) { if (turn === t) turn = null }
  }

  private class Listener : MuseVoiceTurn.Listener {
    override fun onState(state: MuseVoiceTurn.State, message: String) {
      when (state) {
        MuseVoiceTurn.State.LISTENING -> update { it.copy(mode = Mode.LISTENING, caption = "I'm listening…") }
        MuseVoiceTurn.State.SENDING -> {
          level = 0f
          update { it.copy(mode = Mode.THINKING, caption = "") }
        }
        MuseVoiceTurn.State.THINKING -> update { it.copy(mode = Mode.THINKING, caption = "Let me think…") }
        MuseVoiceTurn.State.SPEAKING -> update { it.copy(mode = Mode.SPEAKING) }
        MuseVoiceTurn.State.DONE -> {
          turn = null
          update { it.copy(mode = Mode.IDLE, caption = "", turnActive = false) }
        }
        MuseVoiceTurn.State.FAILED -> {
          turn = null
          level = 0f
          update { it.copy(mode = Mode.ERROR, caption = message.replaceFirstChar { c -> c.uppercase() }, turnActive = false) }
        }
      }
    }

    override fun onReply(text: String) {
      addLine(Line(true, text))
    }

    override fun onLevel(level: Float) {
      Alfred.level = level
    }

    override fun onNoteSent(seconds: Double) = noteUserTurn(seconds)
  }

  internal fun addLine(line: Line) = update { it.copy(transcript = (it.transcript + line).takeLast(30), caption = "") }

  /** The voice note itself has no transcript we can show; mark that you spoke. */
  internal fun noteUserTurn(seconds: Double) = update {
    it.copy(transcript = (it.transcript + Line(false, "🎙 ${"%.1f".format(seconds)} s")).takeLast(30))
  }

  internal fun update(f: (State) -> State) {
    val s = synchronized(lock) { f(state).also { state = it } }
    listeners.forEach { runCatching { it(s) } }
  }
}
