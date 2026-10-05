/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/**
 * "Hey Alfred", entirely on the Portal.
 *
 * Privacy is the design constraint, not an afterthought:
 *  - Keyword spotting runs locally (Kaldi via Vosk) on a ring of in-memory audio. Nothing is
 *    written to disk and nothing leaves the device while it waits.
 *  - Only after "Hey Alfred" does one voice note go to Muse: from just before the wake word to
 *    when you stop talking (silence), at most 15 s. Then it goes back to waiting locally.
 *  - It listens only while Muse is connected, can be limited to when someone is in the room,
 *    pauses while Alfred is talking (so it can't wake itself), and hands the microphone to the
 *    intercom, the camera or a voice note the moment they want it.
 */
object AlfredWake {
  private const val TAG = "AlfredWake"
  private const val OWNER = "alfred-wake"
  private const val RATE = 16_000
  private const val CHUNK = RATE / 10 * 2 // 100 ms of 16-bit mono
  private const val PREROLL_CHUNKS = 12 // 1.2 s kept from before the wake word
  private const val END_SILENCE_MS = 1_200L
  private const val NO_SPEECH_MS = 4_000L
  private const val MAX_COMMAND_MS = 15_000L
  private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
  const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
  /** Pinned: a tampered or truncated download is rejected. */
  const val MODEL_SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
  /** "hey" and "alfred" alone are decoys: only the full phrase wakes. */
  private const val GRAMMAR = "[\"hey alfred\", \"hey\", \"alfred\", \"[unk]\"]"

  enum class Status { OFF, DOWNLOADING, LISTENING, PAUSED, ERROR }

  @Volatile var status = Status.OFF
    private set
  @Volatile var detail = ""
    private set
  @Volatile private var thread: Thread? = null
  /** True while the wake listener has an AudioRecord open. */
  @Volatile private var micOpen = false

  /** Blocks up to [ms] until the wake listener has closed its microphone. */
  fun awaitMicReleased(ms: Long) {
    val until = System.currentTimeMillis() + ms
    while (micOpen && System.currentTimeMillis() < until) Thread.sleep(20)
  }
  @Volatile private var running = false
  private var model: Model? = null

  fun start(context: Context) {
    synchronized(this) {
      if (running) return
      running = true
      thread = Thread({ run(context.applicationContext) }, "alfred-wake").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY - 1
        start()
      }
    }
  }

  fun stop() {
    synchronized(this) {
      running = false
      thread?.interrupt()
      thread = null
    }
    set(Status.OFF, "")
  }

  /** Re-evaluates whether to listen (settings, pairing, connection changed). */
  fun sync(context: Context) {
    if (MuseConfig.isEnabled(context) && MuseConfig.wakeWord(context) && MuseConfig.isPaired(context)) start(context) else stop()
  }

  private fun set(s: Status, d: String) {
    status = s
    detail = d
  }

  // --- the listening loop ----------------------------------------------------------

  private fun run(c: Context) {
    try {
      val m = ensureModel(c) ?: return
      LibVosk.setLogLevel(LogLevel.WARNINGS)
      while (running) {
        val reason = pauseReason(c)
        if (reason != null) {
          set(Status.PAUSED, reason)
          Thread.sleep(1_000)
          continue
        }
        listen(c, m)
        if (running) Thread.sleep(500)
      }
    } catch (_: InterruptedException) {
    } catch (e: Throwable) {
      Log.e(TAG, "wake word stopped", e)
      set(Status.ERROR, e.message ?: e.javaClass.simpleName)
    } finally {
      MicOwner.release(OWNER)
    }
  }

  /** Why not to hold the mic right now, or null to listen. */
  private fun pauseReason(c: Context): String? =
      when {
        MuseRuntime.currentLink() == null -> "Waiting for Muse"
        MuseConfig.wakeOnlyWhenPresent(c) && PresenceHub.current.presence == Presence.ABSENT -> "Nobody's here"
        Alfred.busy -> "Talking"
        MicOwner.holder.let { it != null && it != OWNER } -> "Microphone in use (${MicOwner.holder})"
        else -> null
      }

  @SuppressLint("MissingPermission") // RECORD_AUDIO: granted by provisioning or the Muse screen
  private fun listen(c: Context, m: Model) {
    if (!MicOwner.acquire(OWNER, MicOwner.PRIORITY_WAKE)) return
    val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = runCatching {
      AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK * 4))
    }.getOrNull()
    if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
      rec?.release()
      MicOwner.release(OWNER)
      set(Status.ERROR, "Couldn't open the microphone")
      Thread.sleep(5_000)
      return
    }
    val recognizer = Recognizer(m, RATE.toFloat(), GRAMMAR)
    val ring = ArrayDeque<ByteArray>()
    val buf = ByteArray(CHUNK)
    var floor = 300.0
    var silentReads = 0
    // Energy gate: the recogniser only runs while there's sound in the room (plus a short tail),
    // so a quiet room costs next to nothing and nothing is decoded from silence.
    var active = false
    var quiet = 0
    try {
      rec.startRecording()
      micOpen = true
      set(Status.LISTENING, "Listening for “Hey ${Alfred.NAME}” on this Portal")
      Log.i(TAG, "listening for the wake word (on-device)")
      while (running) {
        val n = readFully(rec, buf)
        if (n <= 0) {
          if (++silentReads > 50) return // the HAL stopped handing us audio: reopen
          continue
        }
        silentReads = 0
        // Yield at once if someone else wants the mic, Alfred started talking, or presence left.
        if (MicOwner.holder != OWNER || pauseReason(c) != null) return
        val chunk = buf.copyOf(n)
        ring.addLast(chunk)
        while (ring.size > PREROLL_CHUNKS) ring.removeFirst()
        val rms = rms(chunk)
        floor = if (rms < floor) floor * 0.9 + rms * 0.1 else floor * 0.995 + rms * 0.005
        val loud = rms > maxOf(floor * 2.2, 350.0)
        if (!active) {
          if (!loud) continue
          active = true
          quiet = 0
          // Catch the onset: feed the last few chunks before this one first.
          val pre = ring.toList().takeLast(4).dropLast(1)
          if (pre.any { heard(recognizer, it) }) {
            onWake(c, rec, ring, floor, recognizer)
            active = false
            continue
          }
        }
        quiet = if (loud) 0 else quiet + 1
        if (quiet > 15) { // 1.5 s of quiet: go idle again
          active = false
          recognizer.reset()
          continue
        }
        if (heard(recognizer, chunk)) {
          onWake(c, rec, ring, floor, recognizer)
          active = false
        }
      }
    } finally {
      runCatching { rec.stop() }
      rec.release()
      micOpen = false
      recognizer.close()
      MicOwner.release(OWNER)
    }
  }

  private fun onWake(c: Context, rec: AudioRecord, ring: ArrayDeque<ByteArray>, floor: Double, recognizer: Recognizer) {
    Log.w(TAG, "wake word heard") // warn level: survives logcat rate-limiting
    recognizer.reset()
    capture(c, rec, ring, maxOf(floor, 150.0))
    ring.clear()
    recognizer.reset()
    set(Status.LISTENING, "Listening for “Hey ${Alfred.NAME}” on this Portal")
  }

  private fun heard(r: Recognizer, chunk: ByteArray): Boolean {
    val final = r.acceptWaveForm(chunk, chunk.size)
    val text =
        if (final) JSONObject(r.result).optString("text")
        else JSONObject(r.partialResult).optString("partial")
    return isWakePhrase(text)
  }

  /**
   * After the wake word: stream this utterance to Muse. The same AudioRecord keeps running so
   * nothing is lost between "Hey Alfred" and the request; it ends on silence.
   */
  private fun capture(c: Context, rec: AudioRecord, preroll: ArrayDeque<ByteArray>, floor: Double) {
    chime(rising = true)
    val pre = java.io.ByteArrayOutputStream().apply { preroll.forEach { write(it) } }.toByteArray()
    val turn = Alfred.startFromWake(c, pre) ?: return
    val buf = ByteArray(CHUNK)
    val started = System.currentTimeMillis()
    var lastSpeech = 0L
    val threshold = maxOf(floor * 2.8, 450.0)
    var dead = 0
    try {
      while (running) {
        val n = readFully(rec, buf)
        val now = System.currentTimeMillis()
        if (n <= 0) {
          // The microphone stopped delivering (another app took it, or the HAL died). Send what
          // we have rather than wait forever; listen() reopens the mic afterwards.
          if (++dead >= 20 || now - started > MAX_COMMAND_MS) break
          Thread.sleep(50)
          continue
        }
        dead = 0
        if (!turn.feed(buf, 0, n)) return // the turn failed (Muse unreachable): it reported why
        if (rms(buf, n) > threshold) lastSpeech = now
        val done =
            (lastSpeech > 0 && now - lastSpeech > END_SILENCE_MS) ||
                (lastSpeech == 0L && now - started > NO_SPEECH_MS) ||
                now - started > MAX_COMMAND_MS
        if (done) break
      }
      turn.finishExternal()
      chime(rising = false)
    } catch (e: Exception) {
      turn.cancel()
      throw e
    }
  }

  private fun readFully(rec: AudioRecord, buf: ByteArray): Int {
    var got = 0
    while (got < buf.size) {
      val n = rec.read(buf, got, buf.size - got)
      if (n <= 0) return got
      got += n
    }
    return got
  }

  internal fun isWakePhrase(text: String): Boolean = Regex("\\bhey alfred\\b").containsMatchIn(text.lowercase())

  private fun rms(b: ByteArray, len: Int = b.size): Double {
    var sum = 0.0
    var i = 0
    while (i + 1 < len) {
      val v = (b[i].toInt() and 0xff) or (b[i + 1].toInt() shl 8)
      sum += v.toDouble() * v
      i += 2
    }
    return Math.sqrt(sum / maxOf(1, len / 2))
  }

  /** A soft two-note cue: rising when Alfred starts listening, falling when it stops. */
  private fun chime(rising: Boolean) {
    runCatching {
      val rate = 22_050
      val notes = if (rising) doubleArrayOf(880.0, 1318.5) else doubleArrayOf(1318.5, 880.0)
      val each = rate * 9 / 100
      val pcm = ShortArray(each * notes.size)
      notes.forEachIndexed { k, f ->
        for (i in 0 until each) {
          val env = Math.sin(Math.PI * i / each)
          pcm[k * each + i] = (Math.sin(2 * Math.PI * f * i / rate) * env * 5000).toInt().toShort()
        }
      }
      val track = AudioTrack.Builder()
          .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
              .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
          .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
          .setBufferSizeInBytes(pcm.size * 2)
          .setTransferMode(AudioTrack.MODE_STATIC)
          .build()
      track.write(pcm, 0, pcm.size)
      track.play()
      Thread({
        Thread.sleep(400)
        track.release()
      }, "alfred-chime").start()
    }
  }

  // --- the acoustic model ------------------------------------------------------------

  fun modelReady(c: Context) = File(File(c.filesDir, MODEL_NAME), "am/final.mdl").exists()

  /** Loads the model, downloading and verifying it first if needed. Null (status set) on failure. */
  private fun ensureModel(c: Context): Model? {
    model?.let { return it }
    val dir = File(c.filesDir, MODEL_NAME)
    if (!modelReady(c)) {
      set(Status.DOWNLOADING, "Downloading the on-device voice model (41 MB)…")
      if (!download(c, dir)) return null
    }
    return Model(dir.absolutePath).also { model = it }
  }

  private fun download(c: Context, dir: File): Boolean {
    val zip = File(c.cacheDir, "$MODEL_NAME.zip")
    repeat(3) { attempt ->
      try {
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        val md = MessageDigest.getInstance("SHA-256")
        conn.inputStream.use { inp ->
          zip.outputStream().use { out ->
            val b = ByteArray(64 * 1024)
            while (true) {
              val n = inp.read(b)
              if (n < 0) break
              md.update(b, 0, n)
              out.write(b, 0, n)
            }
          }
        }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (got != MODEL_SHA256) {
          zip.delete()
          set(Status.ERROR, "The voice model download didn't verify")
          Log.e(TAG, "model checksum mismatch: $got")
          return false
        }
        unzip(zip, c.filesDir, MODEL_NAME)
        zip.delete()
        Log.i(TAG, "voice model installed")
        return true
      } catch (e: Exception) {
        Log.w(TAG, "model download attempt ${attempt + 1} failed: $e")
        Thread.sleep(5_000L * (attempt + 1))
      }
    }
    set(Status.ERROR, "Couldn't download the voice model")
    return false
  }

  /** Extracts only entries under [root]/, refusing any path that escapes it. */
  private fun unzip(zip: File, into: File, root: String) {
    val base = into.canonicalFile
    val tmp = File(into, "$root.partial").apply { deleteRecursively() }
    ZipInputStream(zip.inputStream().buffered()).use { z ->
      while (true) {
        val e = z.nextEntry ?: break
        if (!e.name.startsWith("$root/")) continue
        val out = File(tmp, e.name.removePrefix("$root/")).canonicalFile
        require(out.path.startsWith(tmp.canonicalPath)) { "bad zip entry ${e.name}" }
        if (e.isDirectory) out.mkdirs()
        else {
          out.parentFile?.mkdirs()
          out.outputStream().use { z.copyTo(it) }
        }
      }
    }
    val dest = File(base, root)
    dest.deleteRecursively()
    check(tmp.renameTo(dest)) { "couldn't install the voice model" }
  }
}
