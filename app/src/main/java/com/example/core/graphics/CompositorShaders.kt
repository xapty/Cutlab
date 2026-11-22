package com.example.core.graphics

object CompositorShaders {

    const val LAYER_VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        uniform mat4 uMvpMatrix;
        uniform mat4 uTexMatrix;
        varying vec2 vTexCoord;
        varying vec2 vCanvasUv;
        void main() {
            gl_Position = uMvpMatrix * aPosition;
            vCanvasUv = gl_Position.xy * 0.5 + 0.5;
            vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """

    const val BLIT_VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """

    private const val LAYER_FRAGMENT_BODY = """
        precision mediump float;
        varying vec2 vTexCoord;
        varying vec2 vCanvasUv;

        uniform mat4 uColorMatrix;
        uniform vec4 uColorOffset;
        uniform float uOpacity;
        uniform float uVignette;

        uniform int uShaderType;
        uniform float uShaderIntensity;
        uniform float uTimeSec;
        uniform vec2 uTexelSize;

        uniform int uMaskShape;
        uniform vec2 uMaskCenter;
        uniform vec2 uMaskSize;
        uniform float uMaskRotationRad;
        uniform float uMaskFeather;
        uniform float uMaskCornerRadius;
        uniform int uMaskInverted;

        float rand2d(vec2 co) {
            return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453);
        }

        float evalMaskAlpha(vec2 uv) {
            if (uMaskShape == 0) return 1.0;
            vec2 d = uv - uMaskCenter;
            float c = cos(-uMaskRotationRad);
            float s = sin(-uMaskRotationRad);
            vec2 r = vec2(d.x * c - d.y * s, d.x * s + d.y * c);
            vec2 halfSize = max(uMaskSize * 0.5, vec2(0.01));
            float feather = clamp(uMaskFeather, 0.002, 0.5);
            float dist = -1.0;
            if (uMaskShape == 1) {
                vec2 q = abs(r) - (halfSize - vec2(uMaskCornerRadius));
                dist = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uMaskCornerRadius;
            } else if (uMaskShape == 2) {
                vec2 n = r / halfSize;
                dist = (length(n) - 1.0) * min(halfSize.x, halfSize.y);
            } else if (uMaskShape == 3) {
                dist = r.x;
            }
            float a = 1.0 - smoothstep(-feather, feather, dist);
            return (uMaskInverted == 1) ? (1.0 - a) : a;
        }

        vec4 sampleWithShader(vec2 uv) {
            if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                return vec4(0.0);
            }
            vec4 baseColor = sampleLayerTexture(uv);
            if (uShaderType == 0 || uShaderIntensity <= 0.005) {
                return baseColor;
            }
            float amt = uShaderIntensity;
            if (uShaderType == 1) {
                // Chromatic Aberration (radial)
                vec2 dir = (uv - vec2(0.5)) * 0.025 * amt;
                float r = sampleLayerTexture(clamp(uv + dir, 0.0, 1.0)).r;
                float b = sampleLayerTexture(clamp(uv - dir, 0.0, 1.0)).b;
                return vec4(r, baseColor.g, b, baseColor.a);
            } else if (uShaderType == 2) {
                // RGB Split (horizontal)
                float shift = 0.022 * amt;
                float r = sampleLayerTexture(clamp(uv + vec2(shift, 0.0), 0.0, 1.0)).r;
                float b = sampleLayerTexture(clamp(uv - vec2(shift, 0.0), 0.0, 1.0)).b;
                return vec4(r, baseColor.g, b, baseColor.a);
            } else if (uShaderType == 3) {
                // Film Grain
                float noise = (rand2d(uv * 320.0 + vec2(uTimeSec * 17.0, uTimeSec * 31.0)) - 0.5) * 0.28 * amt;
                return vec4(clamp(baseColor.rgb + noise, 0.0, 1.0), baseColor.a);
            } else if (uShaderType == 4) {
                // Bloom Glow
                vec2 off = uTexelSize * (4.0 + 10.0 * amt);
                vec3 blur = (
                    sampleLayerTexture(clamp(uv + vec2(off.x, 0.0), 0.0, 1.0)).rgb +
                    sampleLayerTexture(clamp(uv - vec2(off.x, 0.0), 0.0, 1.0)).rgb +
                    sampleLayerTexture(clamp(uv + vec2(0.0, off.y), 0.0, 1.0)).rgb +
                    sampleLayerTexture(clamp(uv - vec2(0.0, off.y), 0.0, 1.0)).rgb
                ) * 0.25;
                vec3 bright = max(blur - vec3(0.45), vec3(0.0)) * (1.8 * amt);
                return vec4(clamp(baseColor.rgb + bright, 0.0, 1.0), baseColor.a);
            } else if (uShaderType == 5) {
                // VHS Scanlines
                float wave = sin(uv.y * 45.0 + uTimeSec * 6.0) * 0.004 * amt;
                vec2 suv = clamp(uv + vec2(wave, 0.0), 0.0, 1.0);
                vec4 c = sampleLayerTexture(suv);
                float scan = 1.0 - (0.24 * amt) * (0.5 + 0.5 * sin(uv.y * 280.0));
                return vec4(c.rgb * scan, c.a);
            } else if (uShaderType == 6) {
                // Pixelate
                float grid = mix(180.0, 22.0, amt);
                vec2 puv = floor(uv * grid) / grid;
                return sampleLayerTexture(clamp(puv, 0.0, 1.0));
            } else if (uShaderType == 7) {
                // Sharpen Edge
                vec2 t = uTexelSize * 1.5;
                vec3 neighbors = (
                    sampleLayerTexture(clamp(uv + vec2(t.x, 0.0), 0.0, 1.0)).rgb +
                    sampleLayerTexture(clamp(uv - vec2(t.x, 0.0), 0.0, 1.0)).rgb +
                    sampleLayerTexture(clamp(uv + vec2(0.0, t.y), 0.0, 1.0)).rgb +
                    sampleLayerTexture(clamp(uv - vec2(0.0, t.y), 0.0, 1.0)).rgb
                ) * 0.25;
                vec3 sharp = baseColor.rgb + (baseColor.rgb - neighbors) * (1.8 * amt);
                return vec4(clamp(sharp, 0.0, 1.0), baseColor.a);
            }
            return baseColor;
        }

        void main() {
            vec4 tex = sampleWithShader(vTexCoord);
            vec4 graded = clamp(uColorMatrix * vec4(tex.rgb, 1.0) + uColorOffset, 0.0, 1.0);
            if (uVignette > 0.01) {
                float d = distance(vCanvasUv, vec2(0.5));
                float vig = smoothstep(0.32, 0.85, d) * uVignette * 0.82;
                graded.rgb *= (1.0 - vig);
            }
            float maskA = evalMaskAlpha(vCanvasUv);
            float outA = tex.a * uOpacity * maskA;
            gl_FragColor = vec4(graded.rgb, clamp(outA, 0.0, 1.0));
        }
    """

    const val LAYER_FRAGMENT_SHADER_2D = """
        uniform sampler2D uTexture;
        vec4 sampleLayerTexture(vec2 uv) {
            return texture2D(uTexture, uv);
        }
    """ + LAYER_FRAGMENT_BODY

    const val LAYER_FRAGMENT_SHADER_OES = """
        #extension GL_OES_EGL_image_external : require
        uniform samplerExternalOES uTexture;
        vec4 sampleLayerTexture(vec2 uv) {
            return texture2D(uTexture, uv);
        }
    """ + LAYER_FRAGMENT_BODY

    const val BLEND_FRAGMENT_SHADER = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uBaseTex;
        uniform sampler2D uTopTex;
        uniform int uBlendMode;

        vec3 applyBlend(vec3 base, vec3 top, int mode) {
            if (mode == 1) {
                // Add
                return min(base + top, vec3(1.0));
            } else if (mode == 2) {
                // Multiply
                return base * top;
            } else if (mode == 3) {
                // Screen
                return 1.0 - (1.0 - base) * (1.0 - top);
            } else if (mode == 4) {
                // Overlay
                vec3 lo = 2.0 * base * top;
                vec3 hi = 1.0 - 2.0 * (1.0 - base) * (1.0 - top);
                return mix(lo, hi, step(vec3(0.5), base));
            } else if (mode == 5) {
                // Darken
                return min(base, top);
            } else if (mode == 6) {
                // Lighten
                return max(base, top);
            }
            return top;
        }

        void main() {
            vec4 base = texture2D(uBaseTex, vTexCoord);
            vec4 top = texture2D(uTopTex, vTexCoord);
            vec3 blended = applyBlend(base.rgb, top.rgb, uBlendMode);
            vec3 outRgb = mix(base.rgb, blended, top.a);
            float outA = clamp(base.a + top.a * (1.0 - base.a), 0.0, 1.0);
            gl_FragColor = vec4(outRgb, outA);
        }
    """

    const val PASSTHROUGH_FRAGMENT_SHADER = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D uTexture;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
    """
}
