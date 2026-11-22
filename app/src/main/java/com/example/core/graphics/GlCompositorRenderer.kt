package com.example.core.graphics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import com.example.engine.compositor.ResolvedCompositionFrame
import com.example.engine.compositor.ResolvedVisualLayer
import com.example.engine.effects.EffectsEngine
import com.example.engine.effects.Lut3DEngine
import com.example.engine.project.BlendModePreset
import com.example.engine.project.ClipLutSpec
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaType
import com.example.engine.project.TransitionType
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

import android.content.ComponentCallbacks2
import com.example.core.performance.DevicePerformanceMonitor
import kotlin.math.roundToInt

class GlCompositorRenderer(
    private val context: Context,
    private val onFrameRendered: ((frameMs: Float) -> Unit)? = null,
    private val onVideoSurfacesReady: (primarySurface: Surface?, secondarySurface: Surface?) -> Unit,
) : TextureView.SurfaceTextureListener {

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private var program2d = 0
    private var programOes = 0
    private var programBlend = 0
    private var programBlit = 0

    private val fboIds = IntArray(3)
    private val fboTexIds = IntArray(3)
    private var fboWidth = 0
    private var fboHeight = 0

    private val videoTexIds = IntArray(2)
    private val videoSurfaceTextures = arrayOfNulls<SurfaceTexture>(2)
    private val videoSurfaces = arrayOfNulls<Surface>(2)
    private val videoTexMatrices = Array(2) { FloatArray(16).apply { Matrix.setIdentityM(this, 0) } }
    private val videoFrameDirty = BooleanArray(2)

    private val imageTextureCache = object : LinkedHashMap<String, CachedGlTexture>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedGlTexture>?): Boolean {
            if (size > 10 && eldest != null) {
                GLES20.glDeleteTextures(1, intArrayOf(eldest.value.texId), 0)
                return true
            }
            return false
        }
    }

    private val lutTextureCache = object : LinkedHashMap<String, Int>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean {
            if (size > 8 && eldest != null) {
                GLES20.glDeleteTextures(1, intArrayOf(eldest.value), 0)
                return true
            }
            return false
        }
    }
    private var dummyLutTexId = 0

    private data class CachedGlTexture(val texId: Int, val width: Int, val height: Int)

    private var latestFrame: ResolvedCompositionFrame? = null
    private var primarySlotClipId: String? = null
    private var secondarySlotClipId: String? = null
    private var reportedPrimaryRatio = 16f / 9f
    private var reportedSecondaryRatio = 16f / 9f

    private val renderScheduled = AtomicBoolean(false)
    @Volatile
    private var previewResolutionScale: Float = 1.0f
    private var released = false

    private val quadVertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(
            floatArrayOf(
                // x, y, u, v (standard GL bottom-left 0,0 -> top-right 1,1)
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f,
            ),
        ).apply { position(0) }

    fun startIfNeeded() {
        if (glThread != null || released) return
        val thread = HandlerThread("CutLabGLCompositor").apply { start() }
        glThread = thread
        glHandler = Handler(thread.looper)
    }

    fun updateComposition(
        frame: ResolvedCompositionFrame,
        primaryClipId: String?,
        secondaryClipId: String?,
        primaryVideoRatio: Float,
        secondaryVideoRatio: Float,
    ) {
        latestFrame = frame
        primarySlotClipId = primaryClipId
        secondarySlotClipId = secondaryClipId
        reportedPrimaryRatio = primaryVideoRatio
        reportedSecondaryRatio = secondaryVideoRatio
        requestRender()
    }

    fun setPreviewResolutionScale(scale: Float) {
        val clamped = scale.coerceIn(0.4f, 1.0f)
        if (abs(previewResolutionScale - clamped) < 0.02f) return
        previewResolutionScale = clamped
        val handler = glHandler ?: return
        if (released || eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return
        handler.post {
            if (!released && eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
                if (EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    createOrResizeFbos(surfaceWidth, surfaceHeight)
                    drawFrameInternal()
                }
            }
        }
    }

    fun trimMemory(level: Int) {
        val handler = glHandler ?: return
        if (released || eglDisplay == EGL14.EGL_NO_DISPLAY) return
        handler.post {
            if (released || eglDisplay == EGL14.EGL_NO_DISPLAY) return@post
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            }
            if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
                level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
            ) {
                imageTextureCache.values.forEach { cached ->
                    GLES20.glDeleteTextures(1, intArrayOf(cached.texId), 0)
                }
                imageTextureCache.clear()
                lutTextureCache.values.forEach { texId ->
                    GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
                }
                lutTextureCache.clear()
            } else {
                while (imageTextureCache.size > 3) {
                    val eldestKey = imageTextureCache.keys.firstOrNull() ?: break
                    val removed = imageTextureCache.remove(eldestKey)
                    if (removed != null) {
                        GLES20.glDeleteTextures(1, intArrayOf(removed.texId), 0)
                    }
                }
            }
        }
    }

    fun requestRender() {
        val handler = glHandler ?: return
        if (released || eglSurface == EGL14.EGL_NO_SURFACE) return
        if (renderScheduled.compareAndSet(false, true)) {
            handler.post {
                renderScheduled.set(false)
                drawFrameInternal()
            }
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        startIfNeeded()
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        glHandler?.post {
            initEglAndResources(surface)
            createOrResizeFbos(surfaceWidth, surfaceHeight)
            drawFrameInternal()
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        glHandler?.post {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
                createOrResizeFbos(surfaceWidth, surfaceHeight)
                drawFrameInternal()
            }
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        glHandler?.post {
            releaseGlResources()
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    fun release() {
        released = true
        val handler = glHandler
        val thread = glThread
        if (handler != null && thread != null) {
            handler.post {
                releaseGlResources()
                thread.quitSafely()
            }
        }
        glHandler = null
        glThread = null
    }

    private fun initEglAndResources(outputTexture: SurfaceTexture) {
        releaseGlResources()
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        val eglConfig = configs[0] ?: return

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        val surfAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outputTexture, surfAttribs, 0)
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return

        program2d = linkProgram(CompositorShaders.LAYER_VERTEX_SHADER, CompositorShaders.LAYER_FRAGMENT_SHADER_2D)
        programOes = linkProgram(CompositorShaders.LAYER_VERTEX_SHADER, CompositorShaders.LAYER_FRAGMENT_SHADER_OES)
        programBlend = linkProgram(CompositorShaders.BLIT_VERTEX_SHADER, CompositorShaders.BLEND_FRAGMENT_SHADER)
        programBlit = linkProgram(CompositorShaders.BLIT_VERTEX_SHADER, CompositorShaders.PASSTHROUGH_FRAGMENT_SHADER)

        GLES20.glGenTextures(2, videoTexIds, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexIds[i])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val st = SurfaceTexture(videoTexIds[i])
            st.setOnFrameAvailableListener {
                videoFrameDirty[i] = true
                requestRender()
            }
            videoSurfaceTextures[i] = st
            videoSurfaces[i] = Surface(st)
        }

        val dummyArr = IntArray(1)
        GLES20.glGenTextures(1, dummyArr, 0)
        dummyLutTexId = dummyArr[0]
        if (dummyLutTexId != 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dummyLutTexId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            val px = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
                setPixel(0, 0, android.graphics.Color.BLACK)
            }
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, px, 0)
            px.recycle()
        }

        onVideoSurfacesReady(videoSurfaces[0], videoSurfaces[1])
    }

    private fun createOrResizeFbos(width: Int, height: Int) {
        val maxDim = DevicePerformanceMonitor.previewMaxFboDimension(previewResolutionScale)
        val scaledW = (width.toFloat() * previewResolutionScale).roundToInt()
        val scaledH = (height.toFloat() * previewResolutionScale).roundToInt()
        val targetW = scaledW.coerceIn(64, maxDim)
        val targetH = scaledH.coerceIn(64, maxDim)
        if (targetW == fboWidth && targetH == fboHeight && fboIds[0] != 0) return

        deleteFbos()
        fboWidth = targetW
        fboHeight = targetH
        GLES20.glGenFramebuffers(3, fboIds, 0)
        GLES20.glGenTextures(3, fboTexIds, 0)
        for (i in 0..2) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[i])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                targetW,
                targetH,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                null,
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[i])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                fboTexIds[i],
                0,
            )
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun drawFrameInternal() {
        if (released || eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return
        val frameStartNs = System.nanoTime()

        for (i in 0..1) {
            if (videoFrameDirty[i]) {
                videoFrameDirty[i] = false
                runCatching {
                    videoSurfaceTextures[i]?.updateTexImage()
                    videoSurfaceTextures[i]?.getTransformMatrix(videoTexMatrices[i])
                }
            }
        }

        val frame = latestFrame
        if (fboIds[0] == 0) {
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            return
        }

        // Clear base FBO (index 0) to opaque black
        var baseFboIdx = 0
        var scratchBaseIdx = 1
        val layerFboIdx = 2

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[baseFboIdx])
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (frame != null) {
            val canvasAspect = frame.canvasWidth.toFloat() / frame.canvasHeight.toFloat().coerceAtLeast(1f)
            val timeSec = (frame.playheadMs / 1000f)

            // Optional outgoing layer during transition
            val outLayer = frame.outgoingLayer
            val primaryLayer = frame.layers.firstOrNull()
            if (outLayer != null && primaryLayer?.activeTransition != null && primaryLayer.transitionProgress < 1f) {
                val p = primaryLayer.transitionProgress
                val outAlpha = when (primaryLayer.activeTransition.type) {
                    TransitionType.CROSS_DISSOLVE -> (1f - p).coerceIn(0f, 1f)
                    TransitionType.FADE_BLACK -> if (p < 0.5f) (1f - p * 2f).coerceIn(0f, 1f) else 0f
                    else -> 1f
                }
                val outShiftX = if (primaryLayer.activeTransition.type == TransitionType.SLIDE_LEFT) -0.3f * p else 0f
                if (renderLayerIntoFbo(outLayer, fboIds[layerFboIdx], canvasAspect, timeSec, outAlpha, outShiftX)) {
                    blendFbos(fboTexIds[baseFboIdx], fboTexIds[layerFboIdx], fboIds[scratchBaseIdx], BlendModePreset.NORMAL.shaderId)
                    val tmp = baseFboIdx
                    baseFboIdx = scratchBaseIdx
                    scratchBaseIdx = tmp
                }
            }

            // Composite each active visual layer bottom-to-top
            for (layer in frame.layers) {
                val (transOpacity, transShiftX) = computeTransitionValues(layer)
                if (renderLayerIntoFbo(layer, fboIds[layerFboIdx], canvasAspect, timeSec, transOpacity, transShiftX)) {
                    blendFbos(
                        baseTexId = fboTexIds[baseFboIdx],
                        topTexId = fboTexIds[layerFboIdx],
                        outFboId = fboIds[scratchBaseIdx],
                        blendModeId = layer.blendMode.shaderId,
                    )
                    val tmp = baseFboIdx
                    baseFboIdx = scratchBaseIdx
                    scratchBaseIdx = tmp
                }
            }
        }

        // Blit final composited FBO to window surface
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glUseProgram(programBlit)
        bindQuadAttributes(programBlit)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexIds[baseFboIdx])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programBlit, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        val elapsedMs = (System.nanoTime() - frameStartNs) / 1_000_000f
        onFrameRendered?.invoke(elapsedMs)
    }

    private fun computeTransitionValues(layer: ResolvedVisualLayer): Pair<Float, Float> {
        val t = layer.activeTransition ?: return 1f to 0f
        val p = layer.transitionProgress.coerceIn(0f, 1f)
        if (p >= 1f) return 1f to 0f
        return when (t.type) {
            TransitionType.CROSS_DISSOLVE, TransitionType.BLUR -> p to 0f
            TransitionType.FADE_BLACK -> (if (p < 0.5f) 0f else (p - 0.5f) * 2f) to 0f
            TransitionType.SLIDE_LEFT -> 1f to (1f - p) * 2f
            TransitionType.GLITCH -> (0.4f + 0.6f * p) to (sin(layer.sourceLayer.clipRelativeMs * 0.14f) * (1f - p) * 0.06f)
            TransitionType.ZOOM_IN, TransitionType.SPIN -> p to 0f
            else -> 1f to 0f
        }
    }

    private fun renderLayerIntoFbo(
        layer: ResolvedVisualLayer,
        targetFboId: Int,
        canvasAspect: Float,
        timeSec: Float,
        transitionAlpha: Float,
        transitionShiftX: Float,
    ): Boolean {
        val asset = layer.asset ?: return false
        val clipId = layer.sourceLayer.clip.clipId
        val isVideo = asset.type == MediaType.VIDEO

        val videoSlot = when {
            isVideo && clipId == primarySlotClipId -> 0
            isVideo && clipId == secondarySlotClipId -> 1
            else -> -1
        }

        val texTarget: Int
        val texId: Int
        val texW: Int
        val texH: Int
        val texMatrix = FloatArray(16)

        if (videoSlot >= 0) {
            texTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
            texId = videoTexIds[videoSlot]
            val ratio = if (videoSlot == 0) reportedPrimaryRatio else reportedSecondaryRatio
            texW = if (asset.width > 0) asset.width else (1080 * ratio).toInt()
            texH = if (asset.height > 0) asset.height else 1080
            System.arraycopy(videoTexMatrices[videoSlot], 0, texMatrix, 0, 16)
        } else {
            val cached = getOrLoadImageTexture(asset) ?: return false
            texTarget = GLES20.GL_TEXTURE_2D
            texId = cached.texId
            texW = cached.width
            texH = cached.height
            Matrix.setIdentityM(texMatrix, 0)
            // Flip Y for Bitmap textures
            Matrix.translateM(texMatrix, 0, 0f, 1f, 0f)
            Matrix.scaleM(texMatrix, 0, 1f, -1f, 1f)
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFboId)
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val program = if (texTarget == GLES11Ext.GL_TEXTURE_EXTERNAL_OES) programOes else program2d
        GLES20.glUseProgram(program)
        bindQuadAttributes(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(texTarget, texId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)

        val mvp = buildLayerMvpMatrix(
            layer = layer,
            assetWidth = texW,
            assetHeight = texH,
            canvasAspect = canvasAspect,
            transitionShiftX = transitionShiftX,
        )
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvpMatrix"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)

        // Color matrix
        val cm20 = EffectsEngine.buildColorMatrixValues(layer.effectiveAdjustments)
        val colorMat4 = floatArrayOf(
            cm20[0], cm20[5], cm20[10], 0f,
            cm20[1], cm20[6], cm20[11], 0f,
            cm20[2], cm20[7], cm20[12], 0f,
            0f, 0f, 0f, 1f,
        )
        val colorOff = floatArrayOf(cm20[4] / 255f, cm20[9] / 255f, cm20[14] / 255f, 0f)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uColorMatrix"), 1, false, colorMat4, 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uColorOffset"), 1, colorOff, 0)

        val combinedOpacity = (layer.effectiveOpacity * transitionAlpha).coerceIn(0f, 1f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uOpacity"), combinedOpacity)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uVignette"), layer.effectiveAdjustments.vignette)

        // Shader FX
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uShaderType"), layer.effectiveShader.type.shaderId)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uShaderIntensity"), layer.effectiveShader.intensity)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uTimeSec"), timeSec)
        GLES20.glUniform2f(
            GLES20.glGetUniformLocation(program, "uTexelSize"),
            1f / texW.coerceAtLeast(1),
            1f / texH.coerceAtLeast(1),
        )

        // Chroma Key (Phase 9)
        val ck = layer.effectiveChromaKey
        val ckRed = ((ck.keyColorArgb ushr 16) and 0xFF) / 255f
        val ckGreen = ((ck.keyColorArgb ushr 8) and 0xFF) / 255f
        val ckBlue = (ck.keyColorArgb and 0xFF) / 255f
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uChromaEnabled"), if (ck.enabled) 1 else 0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uChromaRgb"), ckRed, ckGreen, ckBlue)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uChromaSimilarity"), ck.similarity)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uChromaSmoothness"), ck.smoothness)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uChromaSpill"), ck.spillSuppression)

        // 3D LUT 2D-Strip (Phase 9)
        val lutTexId = getOrLoadLutTexture(layer.effectiveLut)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        if (lutTexId != 0 && layer.effectiveLut.isActive) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTexId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uLutEnabled"), 1)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLutIntensity"), layer.effectiveLut.intensity)
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, dummyLutTexId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uLutEnabled"), 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLutIntensity"), 0f)
        }
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uLutTexture"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        // SDF Mask (apply WIPE_RIGHT transition if active & no user mask overrides)
        val mask = layer.mask
        val wipeActive = layer.activeTransition?.type == TransitionType.WIPE_RIGHT && layer.transitionProgress < 1f
        if (wipeActive) {
            val p = layer.transitionProgress
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uMaskShape"), 3)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uMaskCenter"), p, 0.5f)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uMaskSize"), 1f, 1f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uMaskRotationRad"), 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uMaskFeather"), 0.02f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uMaskCornerRadius"), 0f)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uMaskInverted"), 0)
        } else {
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uMaskShape"), mask.shape.shaderId)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uMaskCenter"), mask.centerX, 1f - mask.centerY)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uMaskSize"), mask.width, mask.height)
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(program, "uMaskRotationRad"),
                Math.toRadians(mask.rotationDeg.toDouble()).toFloat(),
            )
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uMaskFeather"), mask.feather)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uMaskCornerRadius"), mask.cornerRadius)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uMaskInverted"), if (mask.inverted) 1 else 0)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        return true
    }

    private fun getOrLoadLutTexture(spec: ClipLutSpec): Int {
        if (!spec.isActive) return 0
        val key = Lut3DEngine.cacheKey(spec)
        lutTextureCache[key]?.let { return it }
        val table = Lut3DEngine.resolveLutTable(spec) ?: return 0
        val stripBmp = Lut3DEngine.packTableToGlStripBitmap(table)
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        if (texId == 0) {
            stripBmp.recycle()
            return 0
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, stripBmp, 0)
        stripBmp.recycle()
        lutTextureCache[key] = texId
        return texId
    }

    private fun buildLayerMvpMatrix(
        layer: ResolvedVisualLayer,
        assetWidth: Int,
        assetHeight: Int,
        canvasAspect: Float,
        transitionShiftX: Float,
    ): FloatArray {
        val assetAspect = (assetWidth.toFloat() / assetHeight.toFloat().coerceAtLeast(1f)).coerceIn(0.2f, 5f)
        val fitScaleX: Float
        val fitScaleY: Float
        if (assetAspect > canvasAspect) {
            fitScaleX = 1f
            fitScaleY = canvasAspect / assetAspect
        } else {
            fitScaleX = assetAspect / canvasAspect
            fitScaleY = 1f
        }

        val t = layer.transform
        var extraScale = 1f
        var extraRot = 0f
        var extraY = 0f
        if (layer.activeTransition != null && layer.transitionProgress < 1f) {
            val p = layer.transitionProgress
            when (layer.activeTransition.type) {
                TransitionType.ZOOM_IN -> extraScale = 0.72f + 0.28f * p
                TransitionType.SPIN -> {
                    extraScale = 0.6f + 0.4f * p
                    extraRot = -48f * (1f - p)
                }
                TransitionType.SLIDE_UP -> extraY = -(1f - p) * 2f
                else -> Unit
            }
        }

        val localAnchorX = (t.anchorX - 0.5f) * 2f
        val localAnchorY = (0.5f - t.anchorY) * 2f

        val m = FloatArray(16)
        Matrix.setIdentityM(m, 0)
        // Translate in NDC (-2..2 range for full canvas shift) + anchor pivot compensation
        Matrix.translateM(
            m,
            0,
            t.positionX * 2f + transitionShiftX + localAnchorX * fitScaleX,
            -t.positionY * 2f + extraY + localAnchorY * fitScaleY,
            0f,
        )
        // Rotate compensating for canvas aspect ratio so rotation stays square
        Matrix.scaleM(m, 0, 1f / canvasAspect, 1f, 1f)
        Matrix.rotateM(m, 0, -(t.rotationDeg + extraRot), 0f, 0f, 1f)
        Matrix.scaleM(m, 0, canvasAspect, 1f, 1f)
        // Apply user scale & aspect-fit scale
        Matrix.scaleM(
            m,
            0,
            fitScaleX * t.scaleX * extraScale,
            fitScaleY * t.scaleY * extraScale,
            1f,
        )
        Matrix.translateM(m, 0, -localAnchorX, -localAnchorY, 0f)
        return m
    }

    private fun blendFbos(
        baseTexId: Int,
        topTexId: Int,
        outFboId: Int,
        blendModeId: Int,
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFboId)
        GLES20.glViewport(0, 0, fboWidth, fboHeight)
        GLES20.glUseProgram(programBlend)
        bindQuadAttributes(programBlend)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, baseTexId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programBlend, "uBaseTex"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, topTexId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programBlend, "uTopTex"), 1)

        GLES20.glUniform1i(GLES20.glGetUniformLocation(programBlend, "uBlendMode"), blendModeId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun getOrLoadImageTexture(asset: MediaAsset): CachedGlTexture? {
        val key = asset.playbackUri
        imageTextureCache[key]?.let { return it }

        val decodeCap = DevicePerformanceMonitor.previewMaxFboDimension(previewResolutionScale)
        val bitmap = decodeSampledBitmap(asset, maxDimension = decodeCap) ?: return null
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        if (texId == 0) {
            bitmap.recycle()
            return null
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        val w = bitmap.width
        val h = bitmap.height
        bitmap.recycle()

        val cached = CachedGlTexture(texId, w, h)
        imageTextureCache[key] = cached
        return cached
    }

    private fun decodeSampledBitmap(asset: MediaAsset, maxDimension: Int): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openMediaStream(asset)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val rawW = bounds.outWidth
            val rawH = bounds.outHeight
            if (rawW <= 0 || rawH <= 0) return null
            var inSampleSize = 1
            while (max(rawW / inSampleSize, rawH / inSampleSize) > maxDimension) {
                inSampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            openMediaStream(asset)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }

    private fun openMediaStream(asset: MediaAsset) =
        asset.managedFilePath?.let { path ->
            val file = File(path)
            if (file.exists()) file.inputStream() else null
        } ?: context.contentResolver.openInputStream(Uri.parse(asset.sourceUri))

    private fun bindQuadAttributes(program: Int) {
        val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val uvHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        quadVertices.position(0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
        quadVertices.position(2)
        GLES20.glEnableVertexAttribArray(uvHandle)
        GLES20.glVertexAttribPointer(uvHandle, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
    }

    private fun linkProgram(vertSrc: String, fragSrc: String): Int {
        val v = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc)
        val f = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, v)
        GLES20.glAttachShader(program, f)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e("CutLabGL", "Program link failed: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return program
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e("CutLabGL", "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    private fun deleteFbos() {
        if (fboIds[0] != 0) {
            GLES20.glDeleteFramebuffers(3, fboIds, 0)
            fboIds.fill(0)
        }
        if (fboTexIds[0] != 0) {
            GLES20.glDeleteTextures(3, fboTexIds, 0)
            fboTexIds.fill(0)
        }
    }

    private fun releaseGlResources() {
        onVideoSurfacesReady(null, null)
        for (i in 0..1) {
            videoSurfaces[i]?.release()
            videoSurfaces[i] = null
            videoSurfaceTextures[i]?.release()
            videoSurfaceTextures[i] = null
        }
        if (videoTexIds[0] != 0) {
            GLES20.glDeleteTextures(2, videoTexIds, 0)
            videoTexIds.fill(0)
        }
        imageTextureCache.values.forEach { cached ->
            GLES20.glDeleteTextures(1, intArrayOf(cached.texId), 0)
        }
        imageTextureCache.clear()
        lutTextureCache.values.forEach { texId ->
            GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
        }
        lutTextureCache.clear()
        if (dummyLutTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(dummyLutTexId), 0)
            dummyLutTexId = 0
        }
        deleteFbos()

        listOf(program2d, programOes, programBlend, programBlit).forEach {
            if (it != 0) GLES20.glDeleteProgram(it)
        }
        program2d = 0
        programOes = 0
        programBlend = 0
        programBlit = 0

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }
}
