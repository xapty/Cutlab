package com.example.feature.project

import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import coil.compose.AsyncImage
import com.example.core.common.TimeFormatting
import com.example.core.designsystem.StudioBadge
import com.example.engine.effects.EffectsEngine
import com.example.engine.project.ClipAdjustments
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectDocument
import com.example.engine.project.TextAlignMode
import com.example.engine.project.TextAnimationPreset
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TextStylePreset
import com.example.engine.project.TransitionType
import com.example.engine.timeline.ActiveClipLayer
import com.example.engine.timeline.ActiveTimelineFrame
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalStudioColors
import com.example.ui.theme.SpaceGroteskFontFamily
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(UnstableApi::class)
@Composable
fun TimelinePreviewMonitor(
    project: ProjectDocument,
    activeFrame: ActiveTimelineFrame,
    assetsById: Map<String, MediaAsset>,
    playheadMs: Long,
    totalDurationMs: Long,
    isPlaying: Boolean,
    stageHeight: Dp = 188.dp,
    onTogglePlayPause: () -> Unit,
    onStepFrame: (Int) -> Unit,
    onJumpStart: () -> Unit,
    onJumpEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    val context = LocalContext.current
    val canvasRatio = (project.canvasWidth.toFloat() / project.canvasHeight.toFloat())
        .coerceIn(0.45f, 2.4f)

    val primaryVisual = activeFrame.primaryVisualLayer
    val visualAsset = primaryVisual?.let { assetsById[it.clip.assetId] }
    val outgoingVisual = activeFrame.outgoingVisualLayer
    val outgoingAsset = outgoingVisual?.let { assetsById[it.clip.assetId] }
    val primaryAudio = activeFrame.audioLayers.firstOrNull()
    val audioAsset = primaryAudio?.let { assetsById[it.clip.assetId] }

    val videoPlayer = remember(context) { ExoPlayer.Builder(context).build() }
    val audioPlayer = remember(context) { ExoPlayer.Builder(context).build() }
    var loadedVideoUri by remember { mutableStateOf<String?>(null) }
    var loadedAudioUri by remember { mutableStateOf<String?>(null) }
    var reportedVideoRatio by remember { mutableStateOf(16f / 9f) }

    DisposableEffect(videoPlayer, audioPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    reportedVideoRatio = (videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio) /
                        videoSize.height.toFloat()
                }
            }
        }
        videoPlayer.addListener(listener)
        onDispose {
            videoPlayer.removeListener(listener)
            videoPlayer.release()
            audioPlayer.release()
        }
    }

    // Sync active video clip at playhead (including speed & fades)
    LaunchedEffect(
        visualAsset?.playbackUri,
        primaryVisual?.clip?.clipId,
        primaryVisual?.clip?.speed,
        primaryVisual?.effectiveVolume,
        isPlaying,
        playheadMs,
    ) {
        val layer = primaryVisual
        if (layer != null && visualAsset != null &&
            visualAsset.type == MediaType.VIDEO &&
            visualAsset.status == MediaStatus.READY
        ) {
            if (loadedVideoUri != visualAsset.playbackUri) {
                loadedVideoUri = visualAsset.playbackUri
                if (visualAsset.width > 0 && visualAsset.height > 0) {
                    reportedVideoRatio = visualAsset.width.toFloat() / visualAsset.height.toFloat()
                }
                videoPlayer.setMediaItem(MediaItem.fromUri(visualAsset.playbackUri))
                videoPlayer.prepare()
            }
            val speed = layer.clip.speed.coerceIn(0.25f, 4.0f)
            if (abs(videoPlayer.playbackParameters.speed - speed) > 0.01f) {
                videoPlayer.playbackParameters = PlaybackParameters(speed)
            }
            val targetSourceMs = (
                layer.clip.sourceStartMs + (layer.clipRelativeMs * speed).toLong()
                ).coerceAtLeast(0L)
            videoPlayer.volume = layer.effectiveVolume.coerceIn(0f, 1f)
            if (isPlaying) {
                if (abs(videoPlayer.currentPosition - targetSourceMs) > 260L) {
                    videoPlayer.seekTo(targetSourceMs)
                }
                if (!videoPlayer.isPlaying) {
                    videoPlayer.playWhenReady = true
                }
            } else {
                if (videoPlayer.isPlaying || videoPlayer.playWhenReady) {
                    videoPlayer.playWhenReady = false
                }
                if (abs(videoPlayer.currentPosition - targetSourceMs) > 35L) {
                    videoPlayer.seekTo(targetSourceMs)
                }
            }
        } else {
            if (videoPlayer.playWhenReady || videoPlayer.isPlaying) {
                videoPlayer.playWhenReady = false
            }
            if (loadedVideoUri != null && visualAsset == null) {
                loadedVideoUri = null
                videoPlayer.clearMediaItems()
            }
        }
    }

    // Sync active audio track clip at playhead (including speed & fade envelope)
    LaunchedEffect(
        audioAsset?.playbackUri,
        primaryAudio?.clip?.clipId,
        primaryAudio?.clip?.speed,
        primaryAudio?.effectiveVolume,
        isPlaying,
        playheadMs,
    ) {
        val layer = primaryAudio
        if (layer != null && audioAsset != null && audioAsset.status == MediaStatus.READY) {
            if (loadedAudioUri != audioAsset.playbackUri) {
                loadedAudioUri = audioAsset.playbackUri
                audioPlayer.setMediaItem(MediaItem.fromUri(audioAsset.playbackUri))
                audioPlayer.prepare()
            }
            val speed = layer.clip.speed.coerceIn(0.25f, 4.0f)
            if (abs(audioPlayer.playbackParameters.speed - speed) > 0.01f) {
                audioPlayer.playbackParameters = PlaybackParameters(speed)
            }
            val targetSourceMs = (
                layer.clip.sourceStartMs + (layer.clipRelativeMs * speed).toLong()
                ).coerceAtLeast(0L)
            audioPlayer.volume = layer.effectiveVolume.coerceIn(0f, 1f)
            if (isPlaying) {
                if (abs(audioPlayer.currentPosition - targetSourceMs) > 260L) {
                    audioPlayer.seekTo(targetSourceMs)
                }
                if (!audioPlayer.isPlaying) {
                    audioPlayer.playWhenReady = true
                }
            } else {
                if (audioPlayer.isPlaying || audioPlayer.playWhenReady) {
                    audioPlayer.playWhenReady = false
                }
            }
        } else {
            if (audioPlayer.playWhenReady || audioPlayer.isPlaying) {
                audioPlayer.playWhenReady = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, studio.hairlineBorder, MaterialTheme.shapes.medium),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(stageHeight)
                .background(MaterialTheme.colorScheme.background)
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(canvasRatio)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black)
                    .border(1.dp, studio.hairlineBorder, RoundedCornerShape(6.dp))
                    .clipToBounds(),
                contentAlignment = Alignment.Center,
            ) {
                val stageWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
                val stageHeightPx = with(LocalDensity.current) { maxHeight.toPx() }

                val transitionType = primaryVisual?.activeTransition?.type ?: TransitionType.NONE
                val p = primaryVisual?.transitionProgress ?: 1f

                // Outgoing layer underneath during transitions
                if (outgoingVisual != null && outgoingAsset != null &&
                    outgoingAsset.status == MediaStatus.READY &&
                    transitionType != TransitionType.NONE && p < 1f
                ) {
                    val outEffective = EffectsEngine.resolveEffectiveAdjustments(outgoingVisual.clip.adjustments)
                    val outMatrix = EffectsEngine.buildColorMatrixValues(outEffective)
                    val outAlpha = when (transitionType) {
                        TransitionType.CROSS_DISSOLVE -> (1f - p).coerceIn(0f, 1f)
                        TransitionType.FADE_BLACK -> if (p < 0.5f) (1f - p * 2f).coerceIn(0f, 1f) else 0f
                        else -> 1f
                    }
                    val outShiftX = if (transitionType == TransitionType.SLIDE_LEFT) -stageWidthPx * 0.3f * p else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = outAlpha
                                translationX = outShiftX
                            },
                    ) {
                        AsyncImage(
                            model = outgoingAsset.playbackUri,
                            contentDescription = outgoingAsset.displayName,
                            contentScale = ContentScale.Fit,
                            colorFilter = ColorFilter.colorMatrix(ColorMatrix(outMatrix)),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // Primary visual layer
                when {
                    visualAsset == null && audioAsset == null && activeFrame.textLayers.isEmpty() -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Movie,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                            Text(
                                text = "${project.canvasWidth}×${project.canvasHeight}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    visualAsset == null && audioAsset != null && activeFrame.textLayers.isEmpty() -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.AudioFile,
                                contentDescription = "Audio layer active",
                                tint = studio.audioAmber,
                                modifier = Modifier.size(26.dp),
                            )
                            Text(
                                text = audioAsset.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    visualAsset != null && visualAsset.status != MediaStatus.READY -> {
                        Icon(
                            imageVector = Icons.Default.BrokenImage,
                            contentDescription = "Missing media",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(28.dp),
                        )
                    }

                    visualAsset != null && primaryVisual != null -> {
                        val effectiveAdj = EffectsEngine.resolveEffectiveAdjustments(primaryVisual.clip.adjustments)
                        val colorMatrixValues = remember(effectiveAdj) {
                            EffectsEngine.buildColorMatrixValues(effectiveAdj)
                        }
                        val transitionModifier = buildTransitionModifier(
                            transitionType = transitionType,
                            progress = p,
                            stageWidthPx = stageWidthPx,
                            stageHeightPx = stageHeightPx,
                            clipRelativeMs = primaryVisual.clipRelativeMs,
                        )
                        val extraBlur = (effectiveAdj.blurRadius + if (transitionType == TransitionType.BLUR) (1f - p) * 16f else 0f)
                            .coerceIn(0f, 24f)
                        val blurModifier = if (extraBlur > 0.4f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Modifier.blur(extraBlur.dp)
                        } else {
                            Modifier
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(transitionModifier)
                                .then(blurModifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (visualAsset.type == MediaType.VIDEO) {
                                VideoTextureSurface(
                                    videoPlayer = videoPlayer,
                                    videoAspectRatio = reportedVideoRatio,
                                    colorMatrixValues = colorMatrixValues,
                                    blurRadius = extraBlur,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else if (visualAsset.type == MediaType.IMAGE) {
                                AsyncImage(
                                    model = visualAsset.playbackUri,
                                    contentDescription = visualAsset.displayName,
                                    contentScale = ContentScale.Fit,
                                    colorFilter = ColorFilter.colorMatrix(ColorMatrix(colorMatrixValues)),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }

                            // Non-API31 blur fallback & Vignette + Glitch chromatic pass
                            VisualPostOverlay(
                                adjustments = effectiveAdj,
                                transitionType = transitionType,
                                transitionProgress = p,
                                activeBlurDp = extraBlur,
                            )
                        }
                    }
                }

                // Active Text & Kinetic Caption Layers rendered above media
                activeFrame.textLayers.forEach { textLayer ->
                    val spec = textLayer.clip.textSpec
                    if (spec != null) {
                        RenderedTextOverlay(
                            spec = spec,
                            clipRelativeMs = textLayer.clipRelativeMs,
                            stageWidthPx = stageWidthPx,
                            stageHeightPx = stageHeightPx,
                        )
                    }
                }
            }

            // Active Layer + Audio HUD Badges
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (primaryVisual != null && visualAsset != null) {
                    val speedSuffix = if (abs(primaryVisual.clip.speed - 1f) > 0.02f) {
                        " • ${String.format("%.2gx", primaryVisual.clip.speed)}"
                    } else ""
                    StudioBadge(
                        text = "${primaryVisual.track.name}: ${visualAsset.displayName}$speedSuffix",
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    )
                }
                if (audioAsset != null) {
                    StudioBadge(
                        text = "♪ ${audioAsset.displayName}",
                        containerColor = studio.audioAmberContainer.copy(alpha = 0.9f),
                        contentColor = studio.audioAmber,
                    )
                }
            }
        }

        // Studio Transport Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${TimeFormatting.formatTimecode(playheadMs, project.fps)} / ${
                    TimeFormatting.formatTimecode(totalDurationMs, project.fps)
                }",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("transport_timecode"),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(
                    onClick = onJumpStart,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("transport_jump_start"),
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Jump to start",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { onStepFrame(-1) },
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("transport_step_back"),
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
                        contentDescription = "Step back 1 frame",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    IconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("transport_play_pause"),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                IconButton(
                    onClick = { onStepFrame(1) },
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("transport_step_forward"),
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Step forward 1 frame",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(
                    onClick = onJumpEnd,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("transport_jump_end"),
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Jump to end",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoTextureSurface(
    videoPlayer: ExoPlayer,
    videoAspectRatio: Float,
    colorMatrixValues: FloatArray,
    blurRadius: Float,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { ctx ->
            val aspectLayout = AspectRatioFrameLayout(ctx).apply {
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                )
            }
            val textureView = TextureView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            aspectLayout.addView(textureView)
            aspectLayout.tag = textureView
            videoPlayer.setVideoTextureView(textureView)
            aspectLayout
        },
        update = { aspectLayout ->
            aspectLayout.setAspectRatio(videoAspectRatio.coerceIn(0.25f, 4.0f))
            val textureView = aspectLayout.tag as? TextureView ?: return@AndroidView
            val cmFilter = ColorMatrixColorFilter(colorMatrixValues)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val colorEffect = RenderEffect.createColorFilterEffect(cmFilter)
                val combined = if (blurRadius > 0.4f) {
                    val blur = RenderEffect.createBlurEffect(
                        blurRadius * 1.5f,
                        blurRadius * 1.5f,
                        Shader.TileMode.CLAMP,
                    )
                    RenderEffect.createChainEffect(blur, colorEffect)
                } else {
                    colorEffect
                }
                textureView.setRenderEffect(combined)
            } else {
                val paint = Paint().apply { colorFilter = cmFilter }
                textureView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
            }
        },
        onRelease = { aspectLayout ->
            val textureView = aspectLayout.tag as? TextureView
            if (textureView != null) {
                videoPlayer.clearVideoTextureView(textureView)
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun VisualPostOverlay(
    adjustments: ClipAdjustments,
    transitionType: TransitionType,
    transitionProgress: Float,
    activeBlurDp: Float,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Subtle temperature/exposure compensation overlay for video hardware surfaces on API 28-30
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && abs(adjustments.temperature) > 0.04f) {
            val tint = if (adjustments.temperature > 0f) {
                Color(0xFFFF9800).copy(alpha = adjustments.temperature * 0.14f)
            } else {
                Color(0xFF0EA5E9).copy(alpha = (-adjustments.temperature) * 0.14f)
            }
            drawRect(color = tint, blendMode = BlendMode.Overlay)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && activeBlurDp > 1f) {
            drawRect(color = Color.White.copy(alpha = (activeBlurDp / 24f) * 0.12f))
        }
        if (adjustments.vignette > 0.01f) {
            val radius = size.maxDimension * 0.72f
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = (adjustments.vignette * 0.75f).coerceIn(0f, 0.85f)),
                    ),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = radius,
                ),
            )
        }
        if (transitionType == TransitionType.GLITCH && transitionProgress < 1f) {
            val intensity = (1f - transitionProgress).coerceIn(0f, 1f)
            val sliceCount = 5
            for (i in 0 until sliceCount) {
                val y = (size.height / sliceCount) * i + ((i * 17) % 13)
                val bandH = (size.height * 0.08f) * intensity
                val color = if (i % 2 == 0) Color.Cyan else Color.Magenta
                drawRect(
                    color = color.copy(alpha = 0.28f * intensity),
                    topLeft = Offset(0f, y),
                    size = Size(size.width, bandH),
                )
            }
        }
    }
}

private fun buildTransitionModifier(
    transitionType: TransitionType,
    progress: Float,
    stageWidthPx: Float,
    stageHeightPx: Float,
    clipRelativeMs: Long,
): Modifier {
    if (transitionType == TransitionType.NONE || progress >= 1f) return Modifier
    val p = progress.coerceIn(0f, 1f)
    return when (transitionType) {
        TransitionType.NONE -> Modifier
        TransitionType.CROSS_DISSOLVE -> Modifier.graphicsLayer { alpha = p }
        TransitionType.FADE_BLACK -> Modifier.graphicsLayer {
            alpha = if (p < 0.5f) 0f else ((p - 0.5f) * 2f).coerceIn(0f, 1f)
        }
        TransitionType.ZOOM_IN -> Modifier.graphicsLayer {
            val s = 0.72f + 0.28f * p
            scaleX = s
            scaleY = s
            alpha = p
        }
        TransitionType.SLIDE_LEFT -> Modifier.graphicsLayer {
            translationX = stageWidthPx * (1f - p)
        }
        TransitionType.SLIDE_UP -> Modifier.graphicsLayer {
            translationY = stageHeightPx * (1f - p)
        }
        TransitionType.WIPE_RIGHT -> Modifier.clip(
            GenericShape { size, _ ->
                val w = size.width * p
                moveTo(0f, 0f)
                lineTo(w, 0f)
                lineTo(w, size.height)
                lineTo(0f, size.height)
                close()
            },
        )
        TransitionType.BLUR -> Modifier.graphicsLayer { alpha = p }
        TransitionType.SPIN -> Modifier.graphicsLayer {
            rotationZ = -48f * (1f - p)
            val s = 0.60f + 0.40f * p
            scaleX = s
            scaleY = s
            alpha = p
        }
        TransitionType.GLITCH -> Modifier.graphicsLayer {
            val jitter = sin(clipRelativeMs * 0.14f) * (1f - p) * 18f
            translationX = jitter
            alpha = (0.35f + 0.65f * p).coerceIn(0f, 1f)
        }
    }
}

@Composable
private fun RenderedTextOverlay(
    spec: TextOverlaySpec,
    clipRelativeMs: Long,
    stageWidthPx: Float,
    stageHeightPx: Float,
) {
    val animWindowMs = 450f
    val rawAnimT = (clipRelativeMs.toFloat() / animWindowMs).coerceIn(0f, 1f)
    val eased = EffectsEngine.applyEasing(rawAnimT, com.example.engine.project.EasingPreset.EASE_OUT)

    val displayText = when (spec.animationPreset) {
        TextAnimationPreset.TYPEWRITER -> {
            val charCount = ((spec.text.length) * (clipRelativeMs.toFloat() / 900f).coerceIn(0.05f, 1f))
                .roundToInt()
                .coerceIn(1, spec.text.length.coerceAtLeast(1))
            if (spec.text.isEmpty()) "" else spec.text.take(charCount)
        }
        else -> spec.text
    }

    val animAlpha = when (spec.animationPreset) {
        TextAnimationPreset.FADE_IN, TextAnimationPreset.SLIDE_UP -> eased
        TextAnimationPreset.POP_OVERSHOOT -> (eased * 1.4f).coerceIn(0f, 1f)
        else -> 1f
    }
    val animScale = when (spec.animationPreset) {
        TextAnimationPreset.POP_OVERSHOOT -> {
            if (rawAnimT < 0.7f) {
                0.65f + (rawAnimT / 0.7f) * 0.45f
            } else {
                1.10f - ((rawAnimT - 0.7f) / 0.3f) * 0.10f
            }
        }
        else -> 1f
    }
    val animTranslateY = when (spec.animationPreset) {
        TextAnimationPreset.SLIDE_UP -> (1f - eased) * 32f
        else -> 0f
    }
    val glitchOffsetX = if (spec.animationPreset == TextAnimationPreset.GLITCH && rawAnimT < 1f) {
        sin(clipRelativeMs * 0.25f) * 7f * (1f - rawAnimT)
    } else 0f

    val fontFamily = when (spec.stylePreset) {
        TextStylePreset.BOLD_TITLE, TextStylePreset.NEON_POP, TextStylePreset.KINETIC_BADGE -> SpaceGroteskFontFamily
        TextStylePreset.CINEMA_SERIF -> FontFamily.Serif
        TextStylePreset.MONO_TYPEWRITER -> JetBrainsMonoFontFamily
        TextStylePreset.MINIMAL_SUBTITLE -> InterFontFamily
    }
    val fontWeight = when (spec.stylePreset) {
        TextStylePreset.BOLD_TITLE, TextStylePreset.NEON_POP, TextStylePreset.KINETIC_BADGE -> FontWeight.ExtraBold
        TextStylePreset.CINEMA_SERIF -> FontWeight.SemiBold
        TextStylePreset.MONO_TYPEWRITER, TextStylePreset.MINIMAL_SUBTITLE -> FontWeight.Medium
    }
    val textAlign = when (spec.alignment) {
        TextAlignMode.LEFT -> TextAlign.Start
        TextAlignMode.CENTER -> TextAlign.Center
        TextAlignMode.RIGHT -> TextAlign.End
    }

    val textColor = Color(spec.textColorArgb.toInt())
    val bgColor = Color(spec.backgroundColorArgb.toInt())
    val baseSize = (18f * spec.fontScale.coerceIn(0.5f, 2.5f)).sp

    val offsetPxX = (spec.offsetX.coerceIn(-0.45f, 0.45f) * stageWidthPx).roundToInt()
    val offsetPxY = (spec.offsetY.coerceIn(-0.45f, 0.45f) * stageHeightPx).roundToInt()

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetPxX, offsetPxY) }
            .graphicsLayer {
                alpha = animAlpha
                scaleX = animScale
                scaleY = animScale
                translationY = animTranslateY
                translationX = glitchOffsetX
            }
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(
                horizontal = if (bgColor.alpha > 0.02f) 10.dp else 4.dp,
                vertical = if (bgColor.alpha > 0.02f) 4.dp else 2.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (spec.hasStroke) {
            Text(
                text = displayText,
                textAlign = textAlign,
                style = TextStyle(
                    fontFamily = fontFamily,
                    fontWeight = fontWeight,
                    fontSize = baseSize,
                    color = Color.Black.copy(alpha = 0.85f),
                    drawStyle = Stroke(width = 5f, join = StrokeJoin.Round),
                ),
            )
        }
        Text(
            text = displayText,
            textAlign = textAlign,
            style = TextStyle(
                fontFamily = fontFamily,
                fontWeight = fontWeight,
                fontSize = baseSize,
                color = textColor,
                shadow = if (spec.hasShadow) {
                    Shadow(
                        color = Color.Black.copy(alpha = 0.75f),
                        offset = Offset(2f, 3f),
                        blurRadius = 6f,
                    )
                } else null,
            ),
        )
    }
}
