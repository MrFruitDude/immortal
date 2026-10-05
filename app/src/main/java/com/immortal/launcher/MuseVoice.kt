/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Tracks one turn's assistant replies on the `/chat/subscribe` NDJSON stream. Pure (no Android),
 * so the binding rules are unit-tested: once the chat ack names our message, only replies to it
 * (or unparented live replies) count — the subscription also carries other chats' traffic.
 */
class MuseReplyTracker {
  class Message(val id: String) {
    val text = StringBuilder()
    var done = false
  }

  var noteId = ""
    private set
  var parentId = ""
    private set
  private var lastSeq = 0L
  val messages = LinkedHashMap<String, Message>()
  var agentBusy = false
    private set
  var lastEventAt = 0L
    private set

  /** The `/chat/stream` response body: `{message_id, reply_to_message_id}` (maybe under `result`). */
  fun onAck(body: String) {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return
    val o = root.optJSONObject("result") ?: root
    noteId = o.optString("message_id")
    parentId = o.optString("reply_to_message_id")
  }

  /** Feeds one subscription line; returns the message that just finished, if any. */
  fun onLine(line: JSONObject, now: Long = System.currentTimeMillis()): Message? {
    if (line.optString("type") != "event") return null // the subscription ack
    val seq = line.optLong("seq", 0)
    if (seq > 0) {
      if (seq <= lastSeq) return null
      lastSeq = seq
    }
    val event = line.optString("event")
    val payload = line.optJSONObject("payload") ?: JSONObject()
    if (event == "agent.status" || event == "task.status") {
      val code = payload.optString("activity_code")
      val status = payload.optString("status")
      agentBusy =
          if (code.isNotEmpty()) code != "online" && code != "idle"
          else status.isNotEmpty() && status != "completed" && status != "failed"
      lastEventAt = now
      return null
    }
    val start = event == "delta.message_start"
    val append = event == "delta.text_append"
    val doneEvt = event == "delta.message_done"
    val full = event == "message.assistant"
    if (!start && !append && !doneEvt && !full) return null
    val id = payload.optString("message_id").ifEmpty { line.optString("message_id") }.ifEmpty { payload.optString("id") }
    if (id.isEmpty()) return null
    val m = messages[id] ?: run {
      val parent = payload.optString("reply_to_message_id").ifEmpty { payload.optString("parent_message_id") }
      if (parent.isNotEmpty() && noteId.isNotEmpty() && parent != noteId && parent != parentId && !messages.containsKey(parent)) return null
      if (id == noteId || messages.size >= 8) return null
      Message(id).also { messages[id] = it }
    }
    lastEventAt = now
    if (append) {
      m.text.append(payload.optString("text"))
      return null
    }
    if ((doneEvt || full) && !m.done) {
      if (full && payload.has("display_text_ready") && !payload.optBoolean("display_text_ready")) return null
      val final = payload.optString("display_text").ifEmpty { payload.optString("content") }
      if (m.text.isEmpty() && final.isNotEmpty()) m.text.append(final)
      m.done = true
      return m.takeIf { it.text.isNotEmpty() }
    }
    return null
  }

  fun allDone() = messages.isNotEmpty() && messages.values.all { it.done }
}

/**
 * One push-to-talk turn on a Portal: hold to talk, release to send. The mic (16 kHz PCM, up to
 * 15 s) streams up as a voice note on `POST /chat/stream` while you speak — Muse transcribes it
 * server-side, as it does the phone app's voice notes — and replies arrive on `POST
 * /chat/subscribe`. Muse doesn't voice gadget replies, so the Portal speaks them with its own TTS.
 */
class MuseVoiceTurn(
    private val context: Context,
    private val link: MuseLink,
    private val nodeId: String,
    private val listener: Listener,
) {
  interface Listener {
    fun onState(state: State, message: String = "")

    fun onReply(text: String)

    /** Live input level 0..1 while listening (drives the avatar). */
    fun onLevel(level: Float) {}

    /** The voice note is on its way, [seconds] long. */
    fun onNoteSent(seconds: Double) {}
  }

  enum class State { LISTENING, SENDING, THINKING, SPEAKING, DONE, FAILED }

  private val recording = AtomicBoolean(false)
  private val cancelled = AtomicBoolean(false)
  private val tracker = MuseReplyTracker()
  private var chatStream = 0L
  private var subStream = 0L
  private val ackBuf = ByteArrayOutputStream()
  private val subBuf = ByteArrayOutputStream()
  @Volatile private var acked = false
  @Volatile private var failure: String? = null
  private val lock = Object()
  private val spoken = HashSet<String>()
  // The note being streamed: PCM staged here and flushed in whole base64 groups.
  private val stage = ByteArrayOutputStream()
  private var pcmBytes = 0
  private var startedAt = 0L

  /** Push-to-talk: opens the mic and streams until [end]; false (after reporting why) if it can't. */
  @SuppressLint("MissingPermission") // RECORD_AUDIO is granted at install on API 28/29 Portals
  fun begin(): Boolean {
    if (!MicOwner.acquire(MIC_OWNER, MicOwner.PRIORITY_NOTE)) return fail("the microphone is busy (${MicOwner.holder})")
    // The wake listener yields within one 100 ms chunk once it sees it lost the mic; give it
    // that long, so two captures never open the same input at once.
    AlfredWake.awaitMicReleased(600)
    val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = runCatching {
      AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
    }.getOrNull()
    if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
      rec?.release()
      MicOwner.release(MIC_OWNER)
      return fail("couldn't open the microphone")
    }
    if (!openNote()) {
      rec.release()
      MicOwner.release(MIC_OWNER)
      return false
    }
    Thread({ record(rec) }, "muse-voice-mic").start()
    return true
  }

  /**
   * Wake word: the caller owns the microphone and pushes audio with [feed] (starting with the
   * [preroll] it kept), then [finish]es when the speaker stops. Non-blocking.
   */
  fun beginExternal(preroll: ByteArray): Boolean {
    if (!openNote()) return false
    return feed(preroll, 0, preroll.size)
  }

  /** Adds captured PCM to the note; false if the upload can't keep up (the turn has failed). */
  fun feed(pcm: ByteArray, off: Int, len: Int): Boolean {
    if (!recording.get() || cancelled.get()) return false
    // Muse already refused the note (e.g. HTTP 502): stop recording and say so now.
    failure?.let {
      recording.set(false)
      finish(it)
      return false
    }
    val n = minOf(len, MAX_NOTE_BYTES - pcmBytes)
    if (n <= 0) return true
    stage.write(pcm, off, n)
    pcmBytes += n
    listener.onLevel(level(pcm, off, n))
    if (stage.size() >= PART_BYTES && !flush(stage, last = false)) {
      recording.set(false)
      finish("can't keep up with Muse")
      return false
    }
    return true
  }

  /** Wake word: the speaker stopped; send the rest and await the reply on a worker thread. */
  fun finishExternal() {
    if (!recording.getAndSet(false)) return
    Thread({ sendNote() }, "muse-voice-send").start()
  }

  /** Release: stop recording; the rest is sent and the reply awaited on a worker thread. */
  fun end() {
    recording.set(false)
  }

  fun cancel() {
    cancelled.set(true)
    recording.set(false)
    MuseSpeech.stop()
    val streams = listOf(chatStream, subStream).filter { it != 0L }
    if (streams.isNotEmpty()) Thread({ streams.forEach { link.cancel(it) } }, "muse-voice-cancel").start()
    synchronized(lock) { lock.notifyAll() }
  }

  private fun openNote(): Boolean {
    chatStream = link.openRequest("POST", "/chat/stream", headers("application/json", "application/json"),
        noteHead(nodeId).toByteArray(), false) { onChatFrame(it) }
    if (chatStream == 0L) return fail("can't reach Muse")
    stage.write(wavHeader(RATE))
    startedAt = System.currentTimeMillis()
    recording.set(true)
    listener.onState(State.LISTENING)
    return true
  }

  private fun record(rec: AudioRecord) {
    val buf = ByteArray(RATE / 10 * 2) // 100 ms
    try {
      rec.startRecording()
      var dead = 0
      while (recording.get() && pcmBytes < MAX_NOTE_BYTES) {
        val n = rec.read(buf, 0, buf.size)
        if (n <= 0) {
          if (++dead >= 20) break // the mic stopped delivering: send what we have
          Thread.sleep(50)
          continue
        }
        dead = 0
        if (!feed(buf, 0, n)) return
      }
    } finally {
      runCatching { rec.stop() }
      rec.release()
      MicOwner.release(MIC_OWNER)
      recording.set(false)
    }
    sendNote()
  }

  private fun sendNote() {
    if (cancelled.get()) return
    if (pcmBytes < RATE * 2 * 3 / 10) {
      link.cancel(chatStream)
      return finish("didn't catch that")
    }
    listener.onState(State.SENDING)
    // Subscribe before the note's last chunk so no reply event can slip past.
    subStream = link.openRequest("POST", "/chat/subscribe", headers("application/json", "application/x-ndjson"),
        "{}".toByteArray(), true) { onSubFrame(it) }
    if (subStream == 0L || !flush(stage, last = true)) return finish("can't reach Muse")
    Log.i(TAG, "voice note sent: ${pcmBytes / (RATE * 2.0)}s in ${System.currentTimeMillis() - startedAt}ms")
    listener.onNoteSent(pcmBytes / (RATE * 2.0))
    listener.onState(State.THINKING)
    awaitReplies()
  }

  private fun flush(stage: ByteArrayOutputStream, last: Boolean): Boolean {
    val all = stage.toByteArray()
    val take = if (last) all.size else all.size - all.size % 3
    stage.reset()
    stage.write(all, take, all.size - take)
    val text = B64.encode(all.copyOf(take)) + if (last) NOTE_TAIL else ""
    return link.sendBody(chatStream, text.toByteArray(), last)
  }

  private fun awaitReplies() {
    val sentAt = System.currentTimeMillis()
    synchronized(lock) {
      while (!cancelled.get() && failure == null) {
        val now = System.currentTimeMillis()
        val quietFor = now - maxOf(tracker.lastEventAt, sentAt)
        if (tracker.allDone() && quietFor > SETTLE_MS && !tracker.agentBusy) break
        if (tracker.messages.isEmpty() && now - sentAt > if (tracker.agentBusy) BUSY_REPLY_TIMEOUT_MS else REPLY_TIMEOUT_MS) {
          failure = "Muse didn't answer"
          break
        }
        if (now - sentAt > TURN_CAP_MS) break
        lock.wait(250)
      }
    }
    if (subStream != 0L) link.cancel(subStream)
    if (cancelled.get()) return
    Log.i(TAG, "turn settled: ${tracker.messages.size} replies, failure=$failure")
    failure?.let { return finish(it) }
    // Let the last spoken reply finish before reporting done.
    while (speaking.get() && !cancelled.get()) Thread.sleep(100)
    listener.onState(State.DONE)
  }

  private val speaking = AtomicBoolean(false)

  private fun onReplyDone(m: MuseReplyTracker.Message) {
    if (!spoken.add(m.id)) return
    val text = m.text.toString().trim()
    listener.onReply(text)
    if (MuseConfig.speakReplies(context)) {
      speaking.set(true)
      listener.onState(State.SPEAKING)
      Thread({
        MuseSpeech.speakAndWait(context, speakable(text), queue = true)
        speaking.set(false)
        synchronized(lock) { lock.notifyAll() }
      }, "muse-voice-tts").start()
    }
  }

  private fun onChatFrame(f: MuseFrame) {
    when (f) {
      is MuseFrame.Response -> {
        Log.i(TAG, "chat/stream: HTTP ${f.status}")
        if (f.status >= 400) {
          Log.w(TAG, "chat/stream refused: ${String(f.body, Charsets.UTF_8).take(300)}")
          setFailure("Muse refused the voice note (HTTP ${f.status})")
        }
        ackBuf.write(f.body)
        if (f.endBody) ack()
      }
      is MuseFrame.Body -> {
        ackBuf.write(f.data)
        if (f.endBody) ack()
      }
      is MuseFrame.Reset -> {
        Log.w(TAG, "chat/stream reset: ${f.reason}")
        if (!acked) setFailure("voice note dropped: ${f.reason}")
      }
    }
  }

  private fun ack() {
    tracker.onAck(String(ackBuf.toByteArray(), Charsets.UTF_8))
    acked = true
    Log.i(TAG, "chat ack: note=${tracker.noteId.ifEmpty { "?" }} parent=${tracker.parentId.ifEmpty { "-" }} (${ackBuf.size()} bytes)")
  }

  private fun onSubFrame(f: MuseFrame) {
    val data = when (f) {
      is MuseFrame.Response -> {
        Log.i(TAG, "chat/subscribe: HTTP ${f.status}")
        if (f.status >= 400) return setFailure("Muse refused the reply stream (HTTP ${f.status})")
        f.body
      }
      is MuseFrame.Body -> f.data
      is MuseFrame.Reset -> {
        Log.w(TAG, "chat/subscribe reset: ${f.reason}")
        return
      }
    }
    subBuf.write(data)
    val bytes = subBuf.toByteArray()
    var start = 0
    for (i in bytes.indices) {
      if (bytes[i] != '\n'.code.toByte()) continue
      val line = String(bytes, start, i - start, Charsets.UTF_8).trim()
      start = i + 1
      if (line.isEmpty()) continue
      val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
      // Event names and ids only — never message text.
      val pl = obj.optJSONObject("payload")
      Log.d(TAG, "sub: type=${obj.optString("type")} event=${obj.optString("event")} seq=${obj.opt("seq")} " +
          "msg=${pl?.optString("message_id")?.ifEmpty { pl.optString("id") }} reply_to=${pl?.optString("reply_to_message_id")}" +
          " parent=${pl?.optString("parent_message_id")} keys=${pl?.keys()?.asSequence()?.toList()}")
      synchronized(lock) {
        tracker.onLine(obj)?.let { onReplyDone(it) }
        lock.notifyAll()
      }
    }
    subBuf.reset()
    if (start < bytes.size) {
      if (bytes.size - start > MAX_LINE) return setFailure("reply too large")
      subBuf.write(bytes, start, bytes.size - start)
    }
  }

  private fun setFailure(msg: String) {
    synchronized(lock) {
      if (failure == null) failure = msg
      lock.notifyAll()
    }
  }

  private fun fail(msg: String): Boolean {
    listener.onState(State.FAILED, msg)
    return false
  }

  private fun finish(msg: String) {
    Log.w(TAG, "turn failed: $msg")
    if (!cancelled.get()) listener.onState(State.FAILED, msg)
  }

  private fun headers(type: String, accept: String) =
      listOf("x-request-id" to UUID.randomUUID().toString(), "x-app-id" to "hatch-web", "Content-Type" to type, "accept" to accept)

  companion object {
    private const val TAG = "MuseVoice"
    private const val MIC_OWNER = "muse"
    const val RATE = 16_000
    const val MAX_NOTE_BYTES = RATE * 2 * 15 // Muse stops listening at 15 s
    const val PART_BYTES = 6144 // 192 ms of PCM per body chunk
    const val SETTLE_MS = 3_000L
    const val REPLY_TIMEOUT_MS = 60_000L
    const val BUSY_REPLY_TIMEOUT_MS = 180_000L
    const val TURN_CAP_MS = 5 * 60_000L
    const val MAX_LINE = 256 * 1024
    const val NOTE_TAIL = "\"}]}"

    /** RMS of 16-bit PCM, mapped to 0..1 on a soft log-ish curve for display. */
    fun level(pcm: ByteArray, off: Int, len: Int): Float {
      var sum = 0.0
      var n = 0
      var i = off
      while (i + 1 < off + len) {
        val v = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
        sum += v.toDouble() * v
        n++
        i += 2
      }
      if (n == 0) return 0f
      val rms = Math.sqrt(sum / n)
      return (rms / 6000.0).coerceIn(0.0, 1.0).let { Math.sqrt(it) }.toFloat()
    }

    fun noteHead(nodeId: String) =
        "{\"message\":\"\",\"output_modality\":\"text\",\"device_id\":\"$nodeId\",\"items\":[{\"type\":\"file\"," +
            "\"mime_type\":\"audio/wav\",\"filename\":\"voice_note.wav\",\"data_base64\":\""

    /** A streaming WAV header: sizes "unknown" (0xFFFFFFFF); the server reads to the end. */
    fun wavHeader(rate: Int): ByteArray {
      val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      h.put("RIFF".toByteArray()).putInt(-1).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
          .putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(-1)
      return h.array()
    }

    /** Strips Markdown that reads badly aloud (links keep their text, code fences go). */
    fun speakable(text: String): String =
        text.replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("[*_`#>]+"), "")
            .replace(Regex("https?://\\S+"), "")
            .trim()
  }
}
