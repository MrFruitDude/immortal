/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.max

/**
 * The liquid-glass dashboard's GL side: its own EGL context on a dedicated [HandlerThread],
 * rendering into a [android.view.TextureView]'s [SurfaceTexture] (whose buffer is sized at half the
 * view, so the view's own compositing does the upscale for free).
 *
 * Per frame: (1) the animated sky into a quarter-resolution FBO, (2)+(3) a downsampling separable
 * Gaussian blur into an eighth-resolution FBO, (4) the sky composited to the surface with stars and
 * dither, (5) one quad per glass panel sampling the blurred sky with refraction. Passes 1-3 are
 * skipped when only a panel moved, since the sky didn't change.
 *
 * Frames are scheduled only by [GlassFramePolicy]: ~30 fps for a while after a [poke], easing to a
 * stop, then nothing until the next poke or [requestRender]. Never draws while not [setVisible].
 *
 * Never throws to its callers: any GL / EGL failure tears the context down and reports through
 * [onFailed] (on the render thread) so the dashboard can fall back to the classic look.
 */
internal class GlassRenderer(
    /** Render-thread hooks around the risky part (EGL/driver init), for the native-crash guard. */
    private val guard: Guard,
    /** Called once, on the render thread, when GL can't be used; the renderer is dead after it. */
    private val onFailed: (String) -> Unit,
) {
  /** Called on the render thread, synchronously, so a marker can be persisted before the driver runs. */
  interface Guard {
    /** About to initialise EGL/GL on a new surface. */
    fun beginInit()

    /** Init is over: [ok] after the first frames reached the screen, false if it ended otherwise. */
    fun endInit(ok: Boolean)
  }

  /** True once frames have reached the screen (written on the render thread). */
  @Volatile
  var healthy = false
    private set

  private var initOpen = false

  private val thread = HandlerThread("ImmortalGlass", Process.THREAD_PRIORITY_DEFAULT).apply { start() }
  private val handler = Handler(thread.looper) { msg -> handle(msg); true }

  // --- written by the main thread, read on the render thread ------------------------------------
  @Volatile private var visible = false
  @Volatile private var lastPokeMs = 0L
  @Volatile private var statsOn = false
  @Volatile private var unitPx = 0.5f
  private val panels = AtomicReference<List<GlassGeometry.Panel>>(emptyList())
  private val palette = AtomicReference<GlassPalette?>(null)
  private val skyDirty = AtomicBoolean(true)

  // --- render-thread state ----------------------------------------------------------------------
  private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
  private var context: EGLContext = EGL14.EGL_NO_CONTEXT
  private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
  private var gles = 0
  private var bufW = 0
  private var bufH = 0
  private var progSky: Program? = null
  private var progBlur: Program? = null
  private var progComposite: Program? = null
  private var progPanel: Program? = null
  private var vbo = 0
  private var sky: Target? = null
  private var blurA: Target? = null
  private var blurB: Target? = null
  private var dead = false
  private var goodFrames = 0
  private var speed = 0f
  private var clockS = 0f
  private var lastFrameAt = 0L
  private var sizeChanged = false
  // Debug frame-time stats (only when statsOn).
  private var statFrames = 0
  private var statSumMs = 0.0
  private var statMaxMs = 0.0
  private var statSince = 0L

  // --- main-thread API --------------------------------------------------------------------------

  /** The view's SurfaceTexture is ready; [w]×[h] is the GL buffer size already set on it. */
  fun surfaceAvailable(st: SurfaceTexture, w: Int, h: Int) {
    handler.obtainMessage(MSG_SURFACE, w, h, st).sendToTarget()
  }

  fun surfaceResized(w: Int, h: Int) {
    handler.obtainMessage(MSG_RESIZE, w, h).sendToTarget()
  }

  /**
   * The SurfaceTexture is going away. Blocks (briefly) until the render thread has let go of the
   * EGL surface — some drivers crash if the window disappears under a live EGL surface.
   */
  fun surfaceDestroyed() {
    val latch = CountDownLatch(1)
    handler.removeMessages(MSG_FRAME)
    handler.obtainMessage(MSG_DESTROY, latch).sendToTarget()
    runCatching { latch.await(1500, TimeUnit.MILLISECONDS) }
  }

  /** Stop the thread and free everything. The renderer can't be used afterwards. */
  fun release() {
    handler.removeMessages(MSG_FRAME)
    handler.sendEmptyMessage(MSG_QUIT)
  }

  fun setVisible(on: Boolean) {
    visible = on
    if (on) requestRender() else handler.removeMessages(MSG_FRAME)
  }

  fun setStats(on: Boolean) {
    statsOn = on
  }

  /** Buffer pixels per dp, for rim widths and star sizes. */
  fun setUnit(px: Float) {
    if (px > 0f && px != unitPx) {
      unitPx = px
      skyDirty.set(true)
      requestRender()
    }
  }

  fun setPanels(list: List<GlassGeometry.Panel>) {
    if (panels.getAndSet(list) != list) requestRender()
  }

  fun setPalette(p: GlassPalette) {
    if (palette.getAndSet(p) != p) {
      skyDirty.set(true)
      requestRender()
    }
  }

  /** Something happened: animate for [GlassFramePolicy.ACTIVE_MS]. */
  fun poke() {
    lastPokeMs = SystemClock.uptimeMillis()
    requestRender()
  }

  /** Draw one frame soon (no animation window). */
  fun requestRender() {
    if (visible && !handler.hasMessages(MSG_FRAME)) handler.sendEmptyMessage(MSG_FRAME)
  }

  // --- render thread ----------------------------------------------------------------------------

  private fun handle(msg: Message) {
    if (msg.what == MSG_DESTROY) {
      runCatching { teardown() }
      closeInit(false)
      (msg.obj as CountDownLatch).countDown()
      return
    }
    if (msg.what == MSG_QUIT) {
      runCatching { teardown() }
      closeInit(false)
      thread.quitSafely()
      return
    }
    if (dead) return
    try {
      when (msg.what) {
        MSG_SURFACE -> {
          teardown()
          if (!initOpen) {
            initOpen = true
            guard.beginInit()
          }
          initEgl(msg.obj as SurfaceTexture)
          bufW = msg.arg1
          bufH = msg.arg2
          initGl()
          sizeChanged = true
          skyDirty.set(true)
          drawFrame()
        }
        MSG_RESIZE -> {
          bufW = msg.arg1
          bufH = msg.arg2
          sizeChanged = true
          skyDirty.set(true)
          drawFrame()
        }
        MSG_FRAME -> drawFrame()
      }
    } catch (t: Throwable) {
      fail("${t.javaClass.simpleName}: ${t.message}")
    }
  }

  private fun fail(reason: String) {
    if (dead) return
    dead = true
    Log.e(TAG, "liquid glass disabled: $reason")
    runCatching { teardown() }
    // A clean Java-side failure is not a native crash: close the marker, report it instead.
    closeInit(false)
    runCatching { onFailed(reason) }
  }

  private fun closeInit(ok: Boolean) {
    if (!initOpen) return
    initOpen = false
    runCatching { guard.endInit(ok) }
  }

  private fun initEgl(st: SurfaceTexture) {
    display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
    val ver = IntArray(2)
    check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize ${eglErr()}" }
    // GLES 3 first, then 2: the shaders are GLSL ES 1.00 and run on either.
    var cfg = chooseConfig(EGLExt.EGL_OPENGL_ES3_BIT_KHR)
    gles = 3
    if (cfg == null) {
      cfg = chooseConfig(EGL14.EGL_OPENGL_ES2_BIT)
      gles = 2
    }
    checkNotNull(cfg) { "no RGBA8888 window EGL config" }
    context =
        EGL14.eglCreateContext(
            display, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, gles, EGL14.EGL_NONE), 0)
    if (context == EGL14.EGL_NO_CONTEXT && gles == 3) {
      val cfg2 = checkNotNull(chooseConfig(EGL14.EGL_OPENGL_ES2_BIT)) { "no ES2 config" }
      gles = 2
      context =
          EGL14.eglCreateContext(
              display, cfg2, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
      cfg = cfg2
    }
    check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext ${eglErr()}" }
    surface = EGL14.eglCreateWindowSurface(display, cfg, st, intArrayOf(EGL14.EGL_NONE), 0)
    check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface ${eglErr()}" }
    check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent ${eglErr()}" }
    Log.i(TAG, "EGL up: GLES $gles, ${GLES20.glGetString(GLES20.GL_RENDERER)}")
  }

  private fun chooseConfig(renderable: Int): EGLConfig? {
    val attrs =
        intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_RENDERABLE_TYPE, renderable,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE)
    val configs = arrayOfNulls<EGLConfig>(1)
    val n = IntArray(1)
    if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) || n[0] < 1) return null
    return configs[0]
  }

  private fun initGl() {
    progSky = Program(GlassShaders.VS_FULL, GlassShaders.FS_BACKGROUND)
    progBlur = Program(GlassShaders.VS_FULL, GlassShaders.FS_BLUR)
    progComposite = Program(GlassShaders.VS_FULL, GlassShaders.FS_COMPOSITE)
    progPanel = Program(GlassShaders.VS_QUAD, GlassShaders.FS_PANEL)
    val strip = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    val buf = ByteBuffer.allocateDirect(strip.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(strip)
    buf.position(0)
    val ids = IntArray(1)
    GLES20.glGenBuffers(1, ids, 0)
    vbo = ids[0]
    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
    GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, strip.size * 4, buf, GLES20.GL_STATIC_DRAW)
    GLES20.glEnableVertexAttribArray(0)
    GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0)
    GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    GLES20.glDisable(GLES20.GL_DITHER)
    checkGl("init")
  }

  private fun ensureTargets() {
    if (!sizeChanged && sky != null) return
    sizeChanged = false
    sky?.delete()
    blurA?.delete()
    blurB?.delete()
    val sw = max(1, bufW / 2)
    val sh = max(1, bufH / 2)
    sky = Target(sw, sh)
    blurA = Target(max(1, sw / 2), max(1, sh / 2))
    blurB = Target(max(1, sw / 2), max(1, sh / 2))
    checkGl("targets")
  }

  private fun drawFrame() {
    handler.removeMessages(MSG_FRAME)
    if (surface == EGL14.EGL_NO_SURFACE || !visible || bufW <= 0 || bufH <= 0) {
      lastFrameAt = 0L
      return
    }
    val t0 = SystemClock.uptimeMillis()
    val t0ns = System.nanoTime()
    val pokeAt = lastPokeMs
    val dt = if (lastFrameAt == 0L) 0L else t0 - lastFrameAt
    speed = GlassFramePolicy.approach(speed, GlassFramePolicy.targetSpeed(t0, pokeAt), dt)
    clockS = GlassFramePolicy.advance(clockS, dt, speed)
    lastFrameAt = t0

    ensureTargets()
    val pal = palette.get()
    if (pal == null) return // nothing to draw until the dashboard hands over its palette
    val drawSky = skyDirty.getAndSet(false) || speed > 0f
    val phase = (clockS / GlassFramePolicy.PERIOD_S * 2.0 * PI).toFloat()
    if (drawSky) renderSky(pal, phase)
    renderComposite(pal, phase)
    renderPanels()

    if (statsOn) GLES20.glFinish()
    if (!EGL14.eglSwapBuffers(display, surface)) {
      val err = EGL14.eglGetError()
      // A lost context / dead window is not a GL bug: drop the surface and wait for a new one.
      if (err == EGL14.EGL_BAD_SURFACE || err == EGL14.EGL_BAD_NATIVE_WINDOW || err == EGL14.EGL_CONTEXT_LOST) {
        Log.w(TAG, "swap failed (0x${Integer.toHexString(err)}); waiting for a new surface")
        teardown()
        return
      }
      error("eglSwapBuffers 0x${Integer.toHexString(err)}")
    }
    if (goodFrames < HEALTHY_FRAMES) {
      goodFrames++
      if (goodFrames == HEALTHY_FRAMES) {
        healthy = true
        closeInit(true)
      }
    }
    val costMs = SystemClock.uptimeMillis() - t0
    if (statsOn) recordStat((System.nanoTime() - t0ns) / 1e6)

    val delay = GlassFramePolicy.nextDelayMs(SystemClock.uptimeMillis(), pokeAt, visible, speed, costMs)
    if (delay != null) handler.sendEmptyMessageDelayed(MSG_FRAME, delay) else lastFrameAt = 0L
  }

  private fun renderSky(pal: GlassPalette, phase: Float) {
    val s = sky!!
    val a = blurA!!
    val b = blurB!!
    GLES20.glDisable(GLES20.GL_BLEND)
    // (1) sky
    s.bind()
    val p = progSky!!
    p.use()
    p.f("uPhase", phase)
    p.f("uAspect", s.w.toFloat() / s.h)
    p.color("uTop", pal.top)
    p.color("uBottom", pal.bottom)
    p.color("uA", pal.blobA)
    p.color("uB", pal.blobB)
    p.color("uC", pal.blobC)
    p.color("uRibbon", pal.ribbon)
    p.f("uAurora", pal.aurora)
    // Palette glow y is screen-down; the shader's uv is y-up.
    p.f3("uGlow", pal.glowX, 1f - pal.glowY, pal.glow)
    p.color("uGlowColor", pal.glowColor)
    strip()
    // (2) horizontal blur, downsampling sky → blurA. Spread is in blur texels (2 sky texels each).
    val blur = progBlur!!
    a.bind()
    blur.use()
    blur.tex("uTex", s.tex, 0)
    blur.f2("uDir", BLUR_SPREAD * 2f / s.w, 0f)
    strip()
    // (3) vertical blur blurA → blurB.
    b.bind()
    blur.tex("uTex", a.tex, 0)
    blur.f2("uDir", 0f, BLUR_SPREAD / b.h)
    strip()
  }

  private fun renderComposite(pal: GlassPalette, phase: Float) {
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    GLES20.glViewport(0, 0, bufW, bufH)
    GLES20.glDisable(GLES20.GL_BLEND)
    val p = progComposite!!
    p.use()
    p.tex("uBg", sky!!.tex, 0)
    p.f2("uRes", bufW.toFloat(), bufH.toFloat())
    p.f("uUnit", unitPx)
    p.f("uStars", pal.stars)
    p.f("uPhase", phase)
    strip()
  }

  private fun renderPanels() {
    val list = panels.get()
    if (list.isEmpty()) return
    GLES20.glEnable(GLES20.GL_BLEND)
    GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    val p = progPanel!!
    p.use()
    p.tex("uBlur", blurB!!.tex, 0)
    p.f2("uRes", bufW.toFloat(), bufH.toFloat())
    p.f("uUnit", unitPx)
    val m = SHADOW_MARGIN_DP * unitPx
    for (q in list) {
      p.f4("uQuad", q.cx - q.hw - m, q.cy - q.hh - m, q.cx + q.hw + m, q.cy + q.hh + m)
      p.f4("uBox", q.cx, q.cy, q.hw, q.hh)
      p.f("uRadius", q.radius)
      strip()
    }
    GLES20.glDisable(GLES20.GL_BLEND)
  }

  private fun strip() = GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

  private fun recordStat(ms: Double) {
    val now = SystemClock.uptimeMillis()
    if (statSince == 0L) statSince = now
    statFrames++
    statSumMs += ms
    if (ms > statMaxMs) statMaxMs = ms
    if (now - statSince >= STATS_EVERY_MS) {
      Log.i(
          TAG,
          String.format(
              java.util.Locale.ROOT,
              "frames=%d avg=%.2fms max=%.2fms (CPU+GPU, incl. glFinish) buffer=%dx%d sky=%dx%d blur=%dx%d panels=%d gles=%d",
              statFrames,
              statSumMs / statFrames,
              statMaxMs,
              bufW,
              bufH,
              sky?.w ?: 0,
              sky?.h ?: 0,
              blurB?.w ?: 0,
              blurB?.h ?: 0,
              panels.get().size,
              gles))
      statFrames = 0
      statSumMs = 0.0
      statMaxMs = 0.0
      statSince = now
    }
  }

  /** Drop every GL/EGL object. Safe to call repeatedly; a new surface re-inits. */
  private fun teardown() {
    lastFrameAt = 0L
    goodFrames = 0
    if (display == EGL14.EGL_NO_DISPLAY) return
    if (context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE) {
      // Objects die with the context; deleting explicitly is just tidy where we can.
      if (EGL14.eglMakeCurrent(display, surface, surface, context)) {
        runCatching {
          sky?.delete()
          blurA?.delete()
          blurB?.delete()
          listOf(progSky, progBlur, progComposite, progPanel).forEach { it?.delete() }
          if (vbo != 0) GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)
        }
      }
    }
    sky = null
    blurA = null
    blurB = null
    progSky = null
    progBlur = null
    progComposite = null
    progPanel = null
    vbo = 0
    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
    EGL14.eglReleaseThread()
    EGL14.eglTerminate(display)
    surface = EGL14.EGL_NO_SURFACE
    context = EGL14.EGL_NO_CONTEXT
    display = EGL14.EGL_NO_DISPLAY
  }

  private fun eglErr() = "0x" + Integer.toHexString(EGL14.eglGetError())

  private fun checkGl(where: String) {
    val e = GLES20.glGetError()
    check(e == GLES20.GL_NO_ERROR) { "GL error 0x${Integer.toHexString(e)} in $where" }
  }

  /** An offscreen colour target: an RGBA8 texture with its FBO. */
  private class Target(val w: Int, val h: Int) {
    val tex: Int
    val fbo: Int

    init {
      val ids = IntArray(1)
      GLES20.glGenTextures(1, ids, 0)
      tex = ids[0]
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
      GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
      GLES20.glGenFramebuffers(1, ids, 0)
      fbo = ids[0]
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
      GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
      val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
      check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "FBO ${w}x$h incomplete 0x${Integer.toHexString(status)}" }
    }

    fun bind() {
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
      GLES20.glViewport(0, 0, w, h)
    }

    fun delete() {
      GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
      GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
    }
  }

  /** A linked program with cached uniform locations. Attribute 0 is always `aPos`. */
  private class Program(vs: String, fs: String) {
    val id: Int
    private val locs = HashMap<String, Int>()

    init {
      val v = compile(GLES20.GL_VERTEX_SHADER, vs)
      val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
      id = GLES20.glCreateProgram()
      GLES20.glAttachShader(id, v)
      GLES20.glAttachShader(id, f)
      GLES20.glBindAttribLocation(id, 0, "aPos")
      GLES20.glLinkProgram(id)
      val ok = IntArray(1)
      GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
      GLES20.glDeleteShader(v)
      GLES20.glDeleteShader(f)
      check(ok[0] == GLES20.GL_TRUE) { "link failed: ${GLES20.glGetProgramInfoLog(id)}" }
    }

    private fun compile(type: Int, src: String): Int {
      val s = GLES20.glCreateShader(type)
      GLES20.glShaderSource(s, src)
      GLES20.glCompileShader(s)
      val ok = IntArray(1)
      GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
      check(ok[0] == GLES20.GL_TRUE) { "compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
      return s
    }

    fun use() = GLES20.glUseProgram(id)

    private fun loc(name: String) = locs.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }

    fun f(name: String, v: Float) = GLES20.glUniform1f(loc(name), v)

    fun f2(name: String, a: Float, b: Float) = GLES20.glUniform2f(loc(name), a, b)

    fun f3(name: String, a: Float, b: Float, c: Float) = GLES20.glUniform3f(loc(name), a, b, c)

    fun f4(name: String, a: Float, b: Float, c: Float, d: Float) = GLES20.glUniform4f(loc(name), a, b, c, d)

    fun color(name: String, c: androidx.compose.ui.graphics.Color) = f3(name, c.red, c.green, c.blue)

    fun tex(name: String, tex: Int, unit: Int) {
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
      GLES20.glUniform1i(loc(name), unit)
    }

    fun delete() = GLES20.glDeleteProgram(id)
  }

  private companion object {
    const val TAG = "ImmortalGlass"
    const val MSG_SURFACE = 1
    const val MSG_RESIZE = 2
    const val MSG_DESTROY = 3
    const val MSG_FRAME = 4
    const val MSG_QUIT = 5
    /** Frames that must reach the screen before GL counts as working on this device. */
    const val HEALTHY_FRAMES = 3
    /** Blur radius multiplier, in eighth-resolution texels. */
    const val BLUR_SPREAD = 1.5f
    /** How far a panel's quad extends past the card for its shadow. */
    const val SHADOW_MARGIN_DP = 16f
    const val STATS_EVERY_MS = 10_000L
  }
}
