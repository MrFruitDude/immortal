/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * GLSL for the liquid-glass dashboard ([GlassRenderer]). Written as GLSL ES 1.00 (`#version 100`)
 * so the same sources run on an OpenGL ES 3.0 context and on the ES 2.0 fallback — no AGSL, no
 * RenderEffect, nothing newer than a 2018 Adreno guarantees. Original shaders, written for
 * Immortal; inspired by the look of the shaders.com component library (MeshGradient, Aurora,
 * Glass), not ported from it.
 *
 * Time: `uPhase` is the animation clock wrapped to 0..2π over [GlassFramePolicy.PERIOD_S], and
 * every frequency that multiplies it is a whole number, so the wrap is seamless and the shader
 * never sees a large time value (mediump-safe, no drift in precision after days of uptime).
 */
internal object GlassShaders {

  /** Full-screen triangle strip; `vUv` is 0..1 with y UP. */
  const val VS_FULL =
      """#version 100
attribute vec2 aPos;
varying vec2 vUv;
void main() {
  vUv = aPos * 0.5 + 0.5;
  gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

  /** A quad covering `uQuad` (x0, y0, x1, y1 in buffer pixels, y up) of a `uRes` buffer. */
  const val VS_QUAD =
      """#version 100
attribute vec2 aPos;
uniform vec4 uQuad;
uniform vec2 uRes;
varying vec2 vPx;
void main() {
  vec2 px = mix(uQuad.xy, uQuad.zw, aPos * 0.5 + 0.5);
  vPx = px;
  gl_Position = vec4(px / uRes * 2.0 - 1.0, 0.0, 1.0);
}
"""

  private const val PRECISION =
      """#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
"""

  /**
   * "Hash without Sine" by Dave Hoskins (MIT License, https://www.shadertoy.com/view/4djSRW):
   * a sine-free 2D -> 1D hash that stays stable on GPUs whose `sin` loses precision.
   */
  private const val HASH =
      """float hash12(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}
"""

  /**
   * The animated sky, rendered into a quarter-resolution FBO: a vertical time-of-day gradient,
   * three soft mesh-gradient blobs drifting on Lissajous paths over a gently domain-warped space,
   * two aurora ribbons with curtain striations, and the sun/moon glow. All of it is low-frequency,
   * so rendering it small and letting bilinear filtering upscale it costs nothing visible.
   */
  const val FS_BACKGROUND =
      """#version 100
$PRECISION
varying vec2 vUv;
uniform float uPhase;
uniform float uAspect;
uniform vec3 uTop;
uniform vec3 uBottom;
uniform vec3 uA;
uniform vec3 uB;
uniform vec3 uC;
uniform vec3 uRibbon;
uniform float uAurora;
uniform vec3 uGlow;      // x, y (0..1, y up), strength
uniform vec3 uGlowColor;

float blob(vec2 p, vec2 c, float k) {
  vec2 d = p - c;
  return exp(-dot(d, d) * k);
}

void main() {
  float t = uPhase;
  vec2 p = vec2(vUv.x * uAspect, vUv.y);
  // Gentle domain warp: everything below flows instead of sliding rigidly.
  vec2 w = p + 0.07 * vec2(sin(p.y * 3.1 + t * 9.0), cos(p.x * 2.7 - t * 7.0));

  vec3 col = mix(uBottom, uTop, smoothstep(0.0, 1.0, vUv.y));

  vec2 c1 = vec2(uAspect * (0.22 + 0.16 * sin(t * 8.0 + 1.0)), 0.78 + 0.10 * cos(t * 11.0));
  vec2 c2 = vec2(uAspect * (0.82 + 0.12 * cos(t * 7.0 + 2.0)), 0.52 + 0.16 * sin(t * 9.0 + 0.5));
  vec2 c3 = vec2(uAspect * (0.45 + 0.22 * sin(t * 6.0 + 4.0)), 0.16 + 0.10 * cos(t * 10.0 + 3.0));
  col = mix(col, uA, 0.70 * blob(w, c1, 5.0));
  col = mix(col, uB, 0.60 * blob(w, c2, 6.0));
  col = mix(col, uC, 0.65 * blob(w, c3, 4.0));

  // Aurora: two wavering ribbons; the upper one carries vertical curtain folds.
  float y0 = 0.70 + 0.07 * sin(w.x * 2.3 + t * 20.0) + 0.035 * sin(w.x * 5.1 - t * 27.0);
  float d0 = (vUv.y - y0) * 8.0;
  float folds = 0.55 + 0.45 * sin(w.x * 21.0 + 2.2 * sin(w.x * 3.0 + t * 17.0));
  float band0 = exp(-d0 * d0) * folds;
  // Curtains hang down from the ribbon: a softer tail below it.
  band0 += exp(-max(-d0, 0.0) * 1.6) * step(vUv.y, y0) * 0.35 * folds * exp(-d0 * d0 * 0.08);
  float y1 = 0.46 + 0.05 * sin(w.x * 3.7 - t * 14.0);
  float d1 = (vUv.y - y1) * 13.0;
  float band1 = exp(-d1 * d1);
  col += uAurora * (uRibbon * band0 * 0.42 + mix(uRibbon, uA, 0.5) * band1 * 0.22);

  // Sun / moon: a tight core glow plus a wide halo.
  vec2 g = vec2(uGlow.x * uAspect, uGlow.y);
  float gd = length(p - g);
  col += uGlowColor * uGlow.z * (exp(-gd * gd * 110.0) * 0.55 + exp(-gd * 5.0) * 0.18);

  gl_FragColor = vec4(col, 1.0);
}
"""

  /**
   * One pass of a separable Gaussian blur: 9 taps folded into 5 bilinear fetches (the
   * well-known linear-sampling weights; see Daniel Rákos, "Efficient Gaussian blur with linear
   * sampling", 2010). `uDir` is the step in UV between texels times the spread.
   */
  const val FS_BLUR =
      """#version 100
$PRECISION
varying vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uDir;
void main() {
  vec3 c = texture2D(uTex, vUv).rgb * 0.2270270270;
  c += texture2D(uTex, vUv + uDir * 1.3846153846).rgb * 0.3162162162;
  c += texture2D(uTex, vUv - uDir * 1.3846153846).rgb * 0.3162162162;
  c += texture2D(uTex, vUv + uDir * 3.2307692308).rgb * 0.0702702703;
  c += texture2D(uTex, vUv - uDir * 3.2307692308).rgb * 0.0702702703;
  gl_FragColor = vec4(c, 1.0);
}
"""

  /**
   * The visible background at the (half-resolution) buffer: the quarter-res sky upscaled, a
   * legibility scrim like the classic dashboard's, twinkling stars at night (drawn here, at the
   * higher resolution, so they stay crisp points), and ±0.5/255 dither against 8-bit banding.
   */
  const val FS_COMPOSITE =
      """#version 100
$PRECISION
varying vec2 vUv;
uniform sampler2D uBg;
uniform vec2 uRes;
uniform float uUnit;     // buffer pixels per dp
uniform float uStars;
uniform float uPhase;
$HASH
void main() {
  vec3 col = texture2D(uBg, vUv).rgb;
  // Darken toward the bottom (where the cards sit) like the classic 20%..35% black scrim.
  col *= mix(0.64, 0.80, vUv.y);

  if (uStars > 0.0) {
    float cellPx = 22.0 * uUnit;
    vec2 cell = floor(gl_FragCoord.xy / cellPx);
    float h = hash12(cell);
    if (h > 0.90) {
      vec2 jitter = vec2(hash12(cell + 17.3), hash12(cell + 41.9)) - 0.5;
      vec2 sp = (cell + 0.5 + jitter * 0.7) * cellPx;
      float d = length(gl_FragCoord.xy - sp);
      float size = (0.7 + 1.3 * fract(h * 37.0)) * uUnit;
      // 40..120 whole cycles per clock period: a 5..15 s twinkle that still wraps seamlessly.
      float tw = 0.65 + 0.35 * sin(uPhase * floor(40.0 + fract(h * 13.7) * 80.0) + h * 91.0);
      float upper = smoothstep(0.30, 0.65, vUv.y);
      col += vec3(0.92, 0.95, 1.0) * uStars * upper * tw * (1.0 - smoothstep(0.0, size, d)) * (0.45 + 0.55 * h);
    }
  }

  col += (hash12(gl_FragCoord.xy) - 0.5) / 255.0;
  gl_FragColor = vec4(col, 1.0);
}
"""

  /**
   * One liquid-glass panel, drawn as a quad over its card (plus a margin for the shadow) and
   * blended premultiplied over the composite. Inside the rounded-rect SDF it shows the blurred sky
   * refracted like a thick lens — samples pulled inward, strongest at the rim — with a faint
   * chromatic split at the edge, a frosted lift, a bright specular rim lit from the top-left and a
   * softer counter-rim. Outside it lays a soft contact shadow.
   */
  const val FS_PANEL =
      """#version 100
$PRECISION
varying vec2 vPx;
uniform sampler2D uBlur;
uniform vec2 uRes;
uniform vec4 uBox;       // centre xy, half size zw (buffer px, y up)
uniform float uRadius;
uniform float uUnit;     // buffer pixels per dp
$HASH
float sdRound(vec2 p, vec2 b, float r) {
  vec2 q = abs(p) - b + r;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
  vec2 p = vPx - uBox.xy;
  float d = sdRound(p, uBox.zw, uRadius);

  // Soft contact shadow, a little below the panel (y is up, so "below" is -y).
  float ds = sdRound(p + vec2(0.0, 3.0 * uUnit), uBox.zw, uRadius);
  float shadow = (1.0 - smoothstep(-2.0 * uUnit, 12.0 * uUnit, ds)) * 0.26;

  float cover = clamp(0.5 - d, 0.0, 1.0);   // 1-px antialiased edge
  if (cover <= 0.0) {
    gl_FragColor = vec4(0.0, 0.0, 0.0, shadow);
    return;
  }

  // Outward surface normal of the rounded rect (analytic SDF gradient).
  vec2 q = abs(p) - uBox.zw + uRadius;
  vec2 s = vec2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
  vec2 n;
  if (q.x > 0.0 || q.y > 0.0) {
    n = normalize(max(q, 0.0) + 1e-4) * s;
  } else {
    n = q.x > q.y ? vec2(s.x, 0.0) : vec2(0.0, s.y);
  }

  float rim = 24.0 * uUnit;
  float e = clamp(-d / rim, 0.0, 1.0);          // 0 at the edge, 1 a rim-width inside
  float lens = (1.0 - e) * (1.0 - e) * (1.0 - e);

  vec2 uv = gl_FragCoord.xy / uRes;
  vec2 off = -n * lens * rim * 1.1 / uRes;      // refraction: bend the sky inward at the rim
  vec2 ca = n * lens * 2.5 * uUnit / uRes;      // chromatic split, only near the edge
  vec3 col;
  col.r = texture2D(uBlur, uv + off + ca).r;
  col.g = texture2D(uBlur, uv + off).g;
  col.b = texture2D(uBlur, uv + off - ca).b;

  // Frost: a little desaturated and pulled toward a mid-dark tone, darker the brighter the sky
  // behind it, so the cards' white text stays legible on a noon sky as well as at night.
  float l = dot(col, vec3(0.299, 0.587, 0.114));
  col = mix(col, vec3(l), 0.12);
  col = col * (0.74 - 0.26 * smoothstep(0.3, 0.85, l)) + 0.05;

  // Light from the top-left.
  vec2 L = normalize(vec2(-0.55, 0.84));
  float facing = dot(n, L);
  float edge = 1.0 - smoothstep(0.0, 1.8 * uUnit, -d);
  float spec = edge * (0.22 + 0.78 * max(facing, 0.0)) + edge * 0.22 * max(-facing, 0.0);
  float sheen = (1.0 - smoothstep(0.0, rim * 1.6, -d)) * max(facing, 0.0) * 0.10;
  // A faint highlight band across the top of the glass.
  float topBand = smoothstep(uBox.w - rim * 3.0, uBox.w, p.y) * 0.05;
  col += vec3(spec * 0.75 + sheen + topBand);

  col += (hash12(gl_FragCoord.xy) - 0.5) / 255.0;
  float a = cover + shadow * (1.0 - cover);
  gl_FragColor = vec4(col * cover, a);
}
"""
}
