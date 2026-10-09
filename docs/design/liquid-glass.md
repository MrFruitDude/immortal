# Liquid glass dashboard — design

A "liquid glass" look for the widget dashboard: an animated sky behind the cards, with each card
drawn as a pane of frosted glass that refracts the sky behind it. The look takes its cues from the
[shaders.com](https://shaders.com) component library (MeshGradient, Aurora, Glass). That library
needs WebGPU, which no Portal has, so this is a native OpenGL ES re-creation with its own shaders.

**Status:** prototype. It's behind a setting (**Settings › Immortal › Home screen › Dashboard
style: Liquid glass**, shown only when Home is set to the Dashboard). The default is Classic, so
nothing changes until someone picks it. The shaders compile and render correctly in WebGL 1, which
uses the same GLSL ES 1.00 dialect. Frame time, heat and the final look still have to be checked on
real Portals (see [hardware checks](#what-to-check-on-hardware)).

## Constraints

- **Android 9 and 10 (API 28 and 29).** No AGSL `RuntimeShader`, no `RenderEffect` or
  `Modifier.blur`, and no WebGPU. Plain EGL plus OpenGL ES is all there is.
- **A mid-range Adreno GPU from about 2018, with 2.8 GB of RAM.** The panels are 1280×800 (the
  Mini, in portrait 800×1280) and 1920×1080 (the Portal+).
- **The Portal is on all day.** Anything that keeps animating keeps the GPU awake and warms the
  device. The default state has to be a static frame.
- **This is the home app.** A crash drops the HOME role, so every failure has to fall back to the
  classic look.

## Architecture

| Piece | File | Role |
|---|---|---|
| `GlassPalette`, `GlassGeometry`, `GlassFramePolicy` | `GlassModel.kt` | The pure, unit-tested parts: colours from time and weather, mapping window rects to GL rects, and when to draw a frame |
| `GlassShaders` | `GlassShaders.kt` | GLSL ES 1.00 sources (the same source works on ES 3 and ES 2) |
| `GlassRenderer` | `GlassRenderer.kt` | EGL context and frame loop on its own `HandlerThread` |
| `GlassStage` | `GlassStage.kt` | `LiquidGlassBackground()`, `Modifier.glassPanel(id, radius)`, `Modifier.dashboardCardSurface(...)`, the fallback guard, and screenshot compositing |

- **The surface is a `TextureView`.** Its `SurfaceTexture` buffer is set to **half** the view's
  size, and the view's own compositing upscales it, so the upscale costs nothing extra. It sits
  full-screen behind the Compose UI as an `AndroidView`.
- **Cards register with `glassPanel`.** It reports the card's window bounds through
  `onGloballyPositioned`. Identical reports are ignored, and the panel is removed when the card
  leaves composition. `GlassGeometry.toBuffer` maps those bounds into the GL buffer: it subtracts
  the stage's own window origin, scales to the buffer, and flips y.
- **The rects reach the render thread as an immutable list** held in an `AtomicReference`.
- **One container helper covers every card.** `dashboardCardSurface` draws a glass panel plus a
  very faint fill when a stage is live, and the classic fill and hairline otherwise. A new card
  built on `DashCard` gets glass for free.

## How a frame is drawn

1. **Sky, into an FBO at 1/4 of screen resolution** (200×320 on a Mini):
   - a vertical gradient from the time of day;
   - three soft mesh-gradient blobs drifting on Lissajous paths over a gently domain-warped space;
   - two aurora ribbons with curtain folds;
   - the sun or moon glow.

   All of it is low-frequency, so drawing it small loses nothing.
2. **Blur, into an FBO at 1/8 resolution.** A horizontal pass downsamples while it blurs, then a
   vertical pass finishes the job. Each pass is a 9-tap Gaussian folded into 5 bilinear fetches.
3. **Composite to the half-resolution surface:**
   - the sky, upscaled bilinearly;
   - a legibility scrim like the classic dashboard's;
   - stars at night, drawn at this resolution so they stay crisp points;
   - ±0.5/255 dither against banding.
4. **One quad per glass panel**, covering the card plus a 16 dp margin for its shadow. Inside the
   rounded-rect SDF the quad shows the blurred sky:
   - **refracted:** samples are pulled inward, more strongly toward the rim, like a thick lens;
   - **split at the edge:** a faint chromatic aberration (separate R, G and B taps);
   - **frosted:** desaturated slightly, and pulled darker the brighter the sky is, so white text
     stays readable at noon;
   - **lit:** a bright specular rim lit from the top-left, plus a faint counter-rim;
   - **shadowed:** a soft contact shadow outside the card.

When only a card moved, steps 1–3 are skipped and the previous sky textures are reused.

Colours come from the classic `WeatherScene.sceneFor` and `SkyColors`, through
`GlassPalette.forMoment`, so the classic and glass looks agree about the sky. Night adds stars,
aurora and the moon. Rain, snow and fog turn the aurora off and wash the colours toward grey.

## Performance safeguards

- **Resolution is cut at every stage:** the surface is half resolution, the sky is drawn at 1/4
  and blurred at 1/8. There's no per-pixel loop over panels; each panel is its own quad.
- **Frames run at about 30 fps, and only after something happens.** These start ("poke") the
  animation window:
  - resume;
  - a touch-down anywhere (`HomeActivity.dispatchTouchEvent`);
  - a new track;
  - a change of weather code;
  - a card appearing.

  After **20 s** the drift eases to a stop over a few seconds and the stage sits on a **static
  frame**. Nothing is scheduled until the next poke.
- **Some events redraw once without restarting the animation:** a relayout, a palette change
  (checked once a minute, and it only redraws if the colours actually moved), or a panel rect
  change. The playback progress bar and the minute clock never restart the animation.
- **Nothing is drawn while the dashboard isn't resumed.** On pause the frame loop stops. When the
  dashboard leaves composition, the thread quits and EGL is released.
- **The animation clock wraps every 600 s,** and every shader frequency is a whole multiple of
  that period. The wrap is seamless, and the shaders never see a large time value.
- **Frame-time log.** **Log glass frame times** (shown only with the glass style) logs this every
  10 s under logcat tag `ImmortalGlass`:
  `frames=… avg=…ms max=…ms (CPU+GPU, incl. glFinish) buffer=… sky=… blur=… panels=… gles=…`.

  With the log on, every frame ends with `glFinish()`, so the number includes GPU time rather than
  just command submission. It also makes frames slightly slower than with the log off.

## Fallback

Classic is used whenever any of these is true:

- the device lacks GLES 2;
- the photo wallpaper is in use (glass replaces only the weather sky);
- an EGL or GL call fails during init or a frame, including a shader compile or link error or an
  incomplete FBO;
- no frames reached the screen within 4 s of resuming.

The renderer catches every `Throwable`, tears EGL down and reports back. The Compose state flips,
and that visit falls back to the classic `WeatherSky` and cards. A GL error keeps classic until
the process restarts. A missing first frame only lasts until the next resume.

A native driver crash can't be caught from Java. Before EGL init, the render thread writes an
"init pending" marker with `commit()`. The marker is cleared once 3 frames have been swapped, or
if init ends cleanly. If a new process finds the marker still set, the previous attempt died
inside the driver, and glass stays off until someone picks a dashboard style again in Settings.

## Screenshots (`/dev/screenshot`)

`HomeActivity.captureForeground` draws the window into a software `Canvas`. A `TextureView` draws
nothing there, and the decor's opaque black window background would cover anything painted
underneath. So the capture goes through `GlassStage.drawWindow(decor, canvas)`:

1. the window background drawable;
2. each visible glass `TextureView`'s last frame (`getBitmap()`), scaled to the view's bounds at
   its `getLocationInWindow`;
3. the decor's children (the Compose UI), drawn over it, skipping the decor's own background.

Without a glass stage in the tree, this is exactly the old `decor.draw(canvas)`.

## What to check on hardware

- **Frame time.** Turn on **Log glass frame times**, touch the dashboard, and read
  `adb logcat -s ImmortalGlass` or the fleet logcat.
  - Expect `avg` well under 33 ms on both the Mini and the Portal+.
  - If the Portal+ (960×540 buffer) is tight, lower `GlassStage.RENDER_SCALE` (try 0.4).
  - Also check `EGL up: GLES 3, Adreno …` to confirm the context version.
- **Heat and idle cost.** After 20–25 s untouched, the frame log should stop printing, because
  nothing renders. Leave the dashboard up for an hour and compare the device temperature with
  Classic.
- **Visual check:**
  - legibility of white text on a bright noon sky;
  - the rim highlight and refraction on each card;
  - no banding;
  - the first frame shows no flash of black;
  - cards that change size (music starting or stopping) don't leave a ghost or a lagging glass
    panel.
- **Lifecycle:**
  - the screensaver starting and stopping;
  - switching to the app grid and back;
  - rotation (the Mini);
  - a long track title (the marquee).
- **Screenshot:** `fleetctl` `/dev/screenshot` on the glass dashboard should show the sky under
  the cards.
