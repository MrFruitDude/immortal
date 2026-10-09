/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import org.vosk.Recognizer

/**
 * A hands-free conversation with Alfred: started by "Hey Alfred" or the Alfred button, no
 * push-to-talk. The shape lives in [AlfredSessionMachine] and turn detection in
 * [AlfredEndpointer] (both pure and unit-tested); this is the runtime that drives them from one
 * microphone loop and shows the conversation in the [AlfredOverlay] popover.
 *
 * Privacy, as for the wake word:
 *  - Audio stays in memory. While waiting for you to talk (the first turn and every follow-up
 *    window) nothing leaves the Portal: the voice note to Muse only opens once the endpointer
 *    hears speech, with ~0.5 s from just before it.
 *  - While Alfred thinks and talks the microphone is read and thrown away, so he can't hear
 *    himself, and a short echo guard follows his last word.
 *  - The intercom (or anything with a higher [MicOwner] priority) can take the microphone at any
 *    time; the conversation ends when it does. A follow-up window also ends when presence says the
 *    room emptied (if the wake word is limited to when someone's here).
 */
object AlfredSession {
  private const val TAG = "AlfredSession"
  private const val OWNER = "alfred"
  private const val RATE = 16_000
  private const val CHUNK = RATE / 10 * 2 // 100 ms of 16-bit mono
  private const val PREROLL_CHUNKS = 5 // sent from before the speech onset
  private const val RING_CHUNKS = 30
  private const val ECHO_GUARD_MS = 400L
  /**
   * At the start the endpointer isn't fed for this long: the chime plays, and after "Hey Alfred"
   * the tail of "…fred" mustn't open a turn on its own. Audio still goes to the preroll ring, so a
   * request spoken straight through ("Hey Alfred, what's the time") loses nothing.
   */
  private const val START_GUARD_MS = 400L
  /** A stop phrase is short: longer turns always go to Muse. */
  private const val STOP_PHRASE_MAX_VOICED_MS = 2_000L

  private val lock = Any()
  @Volatile private var machine: AlfredSessionMachine? = null
  @Volatile private var starting = false
  private val listeners = CopyOnWriteArrayList<(AlfredSessionMachine.Phase) -> Unit>()

  /** The current phase (IDLE when no conversation runs). */
  @Volatile var phase = AlfredSessionMachine.Phase.IDLE
    private set

  val active: Boolean
    get() = starting || phase != AlfredSessionMachine.Phase.IDLE

  fun addListener(l: (AlfredSessionMachine.Phase) -> Unit) = listeners.add(l)

  fun removeListener(l: (AlfredSessionMachine.Phase) -> Unit) = listeners.remove(l)

  /**
   * The Alfred button: starts a conversation on its own microphone thread. False (nothing
   * started) if Muse isn't connected or the app can't record; the caller then opens the full
   * Muse screen, which explains why.
   */
  fun start(context: Context): Boolean {
    val c = context.applicationContext
    if (MuseRuntime.currentLink() == null) return false
    if (c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
    synchronized(lock) {
      if (active) return true
      starting = true
    }
    Thread({ runOwnMic(c) }, "alfred-session").start()
    return true
  }

  /**
   * "Hey Alfred": the conversation runs on the wake listener's thread and its already-open
   * [rec], so nothing is lost after the wake word. Blocks until the conversation is over.
   */
  fun runFromWake(context: Context, rec: AudioRecord, owner: String, preroll: List<ByteArray>, floor: Double) {
    synchronized(lock) {
      if (active) return
      starting = true
    }
    // Hold the mic like a voice note for the conversation (only the intercom outranks it), then
    // drop back to the wake listener's lowest priority.
    MicOwner.acquire(owner, MicOwner.PRIORITY_NOTE)
    try {
      converse(context.applicationContext, rec, owner, preroll, floor)
    } finally {
      if (MicOwner.holds(owner)) MicOwner.acquire(owner, MicOwner.PRIORITY_WAKE)
    }
  }

  /** Muse's `conversation.end`: ends once the current reply has been spoken. */
  fun requestEnd(): Boolean = synchronized(lock) { machine?.requestEnd(AlfredSessionMachine.EndReason.ALFRED_ENDED) ?: false }

  /** The popover's close button (or tapping the Alfred button again): ends now, mid-reply too. */
  fun close() {
    val was = synchronized(lock) { machine?.also { it.close() } }
    if (was != null && (Alfred.turn != null || MuseSpeech.isSpeaking)) Alfred.cancel()
  }

  // --- the microphone loop -------------------------------------------------------------

  @SuppressLint("MissingPermission") // checked in start()
  private fun runOwnMic(c: Context) {
    if (!MicOwner.acquire(OWNER, MicOwner.PRIORITY_NOTE)) return failStart(c, "The microphone is busy right now.")
    // The wake listener yields within one chunk once it sees it lost the mic.
    AlfredWake.awaitMicReleased(600)
    val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    // Same source as the wake listener, so the endpointer's thresholds mean the same thing.
    val rec = runCatching {
      AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE * 2 * 2))
    }.getOrNull()
    if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
      rec?.release()
      MicOwner.release(OWNER)
      return failStart(c, "I couldn't open the microphone.")
    }
    try {
      rec.startRecording()
      converse(c, rec, OWNER, emptyList(), AlfredEndpointer.DEFAULT_FLOOR)
    } catch (e: Throwable) {
      Log.e(TAG, "conversation stopped", e)
    } finally {
      runCatching { rec.stop() }
      rec.release()
      MicOwner.release(OWNER)
    }
  }

  private fun failStart(c: Context, message: String) {
    Log.w(TAG, "couldn't start: $message")
    synchronized(lock) { starting = false }
    AlfredOverlay.show(c, AlfredOverlay.Model(Alfred.Mode.ERROR, "Sorry", message))
    AlfredOverlay.hide(3_500)
    notifyListeners()
  }

  private fun converse(c: Context, rec: AudioRecord, owner: String, preroll: List<ByteArray>, floor: Double) {
    val m = AlfredSessionMachine(
        AlfredSessionMachine.Config(
            followUp = MuseConfig.followUp(c),
            followUpMs = MuseConfig.followUpSeconds(c).coerceIn(3, 20) * 1000L))
    synchronized(lock) {
      m.start(now())
      machine = m
      starting = false
      phase = m.phase
    }
    Log.i(TAG, "conversation started")
    ScreenControl.wake(c)
    val onAlfred: (Alfred.State) -> Unit = { publish(m) }
    Alfred.addListener(onAlfred)
    AlfredOverlay.show(c, model(m.phase, false, Alfred.state))
    AlfredWake.chime(rising = true)

    val ep = AlfredEndpointer(AlfredEndpointer.Config(endOfTurnMs = MuseConfig.endOfTurnMs(c).coerceIn(400, 2000).toLong()), floor)
    val ring = ArrayDeque(preroll.takeLast(RING_CHUNKS))
    val stop = StopListener.create()
    val observer = TurnObserver(m)
    val buf = ByteArray(CHUNK)
    var turn: MuseVoiceTurn? = null
    var shown = m.phase
    var guardUntil = now() + START_GUARD_MS
    var dead = 0
    try {
      while (true) {
        val ph = synchronized(lock) {
          m.tick(now())
          m.phase
        }
        if (ph != shown) {
          if (ph == AlfredSessionMachine.Phase.FOLLOW_UP) {
            // Alfred just stopped talking: start fresh and ignore the room's echo of him.
            ep.reset()
            ring.clear()
            guardUntil = now() + ECHO_GUARD_MS
          }
          shown = ph
          phase = ph
          publish(m)
          notifyListeners()
        }
        if (ph == AlfredSessionMachine.Phase.IDLE) break
        val n = AlfredWake.readFully(rec, buf)
        if (n <= 0) {
          // The HAL stopped handing us audio (another app took it, or it died).
          if (++dead >= 20) synchronized(lock) { m.micLost() } else Thread.sleep(50)
          continue
        }
        dead = 0
        if (!MicOwner.holds(owner)) {
          Log.i(TAG, "microphone handed to ${MicOwner.holder}")
          synchronized(lock) { m.micLost() }
          continue
        }
        // Thinking or talking: read and discard, so he can't hear himself.
        if (ph != AlfredSessionMachine.Phase.LISTENING && ph != AlfredSessionMachine.Phase.FOLLOW_UP) continue
        if (ph == AlfredSessionMachine.Phase.FOLLOW_UP && MuseConfig.wakeOnlyWhenPresent(c) &&
            PresenceHub.current.presence == Presence.ABSENT) {
          synchronized(lock) { m.presenceLeft() }
          continue
        }
        val chunk = buf.copyOf(n)
        val open = turn
        if (open == null) {
          ring.addLast(chunk)
          while (ring.size > RING_CHUNKS) ring.removeFirst()
        }
        if (now() < guardUntil) continue
        val event = ep.feed(AlfredEndpointer.rms(chunk))
        if (open == null) {
          Alfred.level = MuseVoiceTurn.level(chunk, 0, chunk.size)
          if (event != AlfredEndpointer.Event.SPEECH_START) continue
          // Speech: only now does a voice note open, starting just before the onset.
          val pre = ring.toList().takeLast(ep.onsetChunks + PREROLL_CHUNKS)
          ring.clear()
          synchronized(lock) { m.speechStarted(now()) }
          stop?.begin(pre)
          turn = Alfred.startExternalTurn(c, concat(pre), observer)
          if (turn == null) synchronized(lock) { m.turnFailed() }
          continue
        }
        if (!open.feed(chunk, 0, chunk.size)) {
          turn = null // it reported why (Muse unreachable / refused); the observer ends the session
          synchronized(lock) { m.turnFailed() }
          continue
        }
        if (ep.voicedMs <= STOP_PHRASE_MAX_VOICED_MS) stop?.feed(chunk) else stop?.giveUp()
        if (event == AlfredEndpointer.Event.END_OF_TURN || event == AlfredEndpointer.Event.MAX_LENGTH) {
          turn = null
          val stopped = stop?.heardStopPhrase() == true
          ep.reset()
          if (stopped) {
            Log.i(TAG, "stop phrase: ending without sending")
            open.cancel()
            synchronized(lock) { m.stopPhrase() }
          } else {
            synchronized(lock) { m.turnSent() }
            open.finishExternal()
            AlfredWake.chime(rising = false)
          }
        }
      }
    } catch (_: InterruptedException) {
    } finally {
      Alfred.removeListener(onAlfred)
      turn?.cancel()
      stop?.close()
      val reason = synchronized(lock) {
        if (m.active) m.close()
        machine = null
        phase = AlfredSessionMachine.Phase.IDLE
        m.endReason
      }
      // Ended mid-reply (close, the mic taken, the hard cap): stop him too.
      if (Alfred.turn != null || MuseSpeech.isSpeaking) Alfred.cancel()
      Alfred.level = 0f
      Log.i(TAG, "conversation over: $reason after ${m.turns} turns")
      endUi(c, reason)
      notifyListeners()
    }
  }

  private class TurnObserver(private val m: AlfredSessionMachine) : MuseVoiceTurn.Listener {
    override fun onState(state: MuseVoiceTurn.State, message: String) {
      synchronized(lock) {
        if (machine !== m) return
        when (state) {
          MuseVoiceTurn.State.SPEAKING -> m.speaking()
          MuseVoiceTurn.State.DONE -> m.turnDone(now())
          MuseVoiceTurn.State.FAILED -> m.turnFailed()
          else -> {}
        }
      }
    }

    override fun onReply(text: String) {}
  }

  /**
   * Stop phrases on the device, only if the wake listener already loaded its model (the
   * conversation never loads or downloads it). Fed the start of each turn; a match is only
   * trusted when the whole short utterance is one phrase.
   */
  private class StopListener(private val r: Recognizer) {
    private val heard = StringBuilder()
    private var eligible = false

    fun begin(pre: List<ByteArray>) {
      r.reset()
      heard.setLength(0)
      eligible = true
      pre.forEach { feed(it) }
    }

    fun feed(chunk: ByteArray) {
      if (!eligible) return
      if (r.acceptWaveForm(chunk, chunk.size)) heard.append(' ').append(text(r.result))
    }

    fun giveUp() {
      eligible = false
    }

    fun heardStopPhrase(): Boolean {
      if (!eligible) return false
      eligible = false
      heard.append(' ').append(text(r.finalResult))
      return AlfredStopPhrases.matches(heard.toString())
    }

    fun close() = runCatching { r.close() }

    private fun text(json: String) = runCatching { JSONObject(json).optString("text") }.getOrDefault("")

    companion object {
      fun create(): StopListener? =
          AlfredWake.modelIfLoaded()?.let { model ->
            runCatching { StopListener(Recognizer(model, RATE.toFloat(), AlfredStopPhrases.GRAMMAR)) }
                .onFailure { Log.w(TAG, "stop phrases unavailable", it) }
                .getOrNull()
          }
    }
  }

  // --- what the popover shows ------------------------------------------------------------

  private fun publish(m: AlfredSessionMachine) {
    val (ph, heard) = synchronized(lock) { m.phase to m.heardSpeech }
    if (ph == AlfredSessionMachine.Phase.IDLE) return
    AlfredOverlay.update(model(ph, heard, Alfred.state))
  }

  /** The popover for a running conversation. */
  internal fun model(ph: AlfredSessionMachine.Phase, heard: Boolean, s: Alfred.State): AlfredOverlay.Model {
    val reply = s.transcript.lastOrNull()?.takeIf { it.fromAlfred }?.text.orEmpty()
    return when (ph) {
      AlfredSessionMachine.Phase.LISTENING ->
          AlfredOverlay.Model(Alfred.Mode.LISTENING, "Listening…", if (heard) "" else "Go ahead, I'm listening.")
      AlfredSessionMachine.Phase.FOLLOW_UP -> AlfredOverlay.Model(Alfred.Mode.LISTENING, "Anything else?", reply)
      AlfredSessionMachine.Phase.THINKING -> AlfredOverlay.Model(Alfred.Mode.THINKING, "Thinking…", "")
      AlfredSessionMachine.Phase.SPEAKING -> AlfredOverlay.Model(Alfred.Mode.SPEAKING, Alfred.NAME, reply)
      AlfredSessionMachine.Phase.IDLE -> AlfredOverlay.Model(Alfred.Mode.IDLE, Alfred.NAME, reply)
    }
  }

  private fun endUi(c: Context, reason: AlfredSessionMachine.EndReason?) {
    when (reason) {
      AlfredSessionMachine.EndReason.FAILED -> {
        AlfredOverlay.show(c, AlfredOverlay.Model(Alfred.Mode.ERROR, "Sorry",
            Alfred.state.caption.ifEmpty { "Something went wrong." }))
        AlfredOverlay.hide(3_500)
      }
      AlfredSessionMachine.EndReason.STOP_PHRASE -> {
        AlfredOverlay.show(c, AlfredOverlay.Model(Alfred.Mode.IDLE, "Bye!", ""))
        AlfredOverlay.hide(1_200)
      }
      AlfredSessionMachine.EndReason.MIC_LOST -> {
        AlfredOverlay.show(c, AlfredOverlay.Model(Alfred.Mode.IDLE, Alfred.NAME, "Handing the microphone over."))
        AlfredOverlay.hide(1_500)
      }
      else -> AlfredOverlay.hide(0)
    }
  }

  private fun notifyListeners() {
    val ph = phase
    listeners.forEach { runCatching { it(ph) } }
  }

  private fun concat(chunks: List<ByteArray>): ByteArray =
      ByteArrayOutputStream().apply { chunks.forEach { write(it) } }.toByteArray()

  private fun now() = System.currentTimeMillis()
}
