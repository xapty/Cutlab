package com.example.feature.project

import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.annotation.OptIn
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
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import com.example.core.common.TimeFormatting
import com.example.core.designsystem.StudioBadge
import com.example.core.graphics.GlCompositorRenderer
import com.example.engine.animation.AnimationEngine
import com.example.engine.compositor.CompositorEngine
import com.example.engine.effects.EffectsEngine
import com.example.engine.project.BlendModePreset
import com.example.engine.project.LayerTransform
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectDocument
import com.example.engine.project.TextAlignMode
import com.example.engine.project.TextAnimationPreset
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TextStylePreset
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
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val animationEngine = remember { AnimationEngine() }
    val compositorEngine = remember(animationEngine) { CompositorEngine(animationEngine) }
    val canvasRatio = (project.canvasWidth.toFloat() / project.canvasHeight.toFloat())
        .coerceIn(0.45f, 2.4f)

    val resolvedComposition = remember(activeFrame, assetsById, project.canvasWidth, project.canvasHeight) {
        compositorEngine.resolveFrame(
            activeFrame = activeFrame,
            assetsById = assetsById,
            canvasWidth = project.canvasWidth,
            canvasHeight = project.canvasHeight,
        )
    }

    val activeVideoLayers = remember(resolvedComposition) {
        resolvedComposition.layers.filter {
            it.asset?.type == MediaType.VIDEO && it.asset.status == MediaStatus.READY
        }
    }
    val primaryVideoLayer = activeVideoLayers.getOrNull(0)?.sourceLayer
    val primaryVideoAsset = activeVideoLayers.getOrNull(0)?.asset
    val secondaryResolvedVideo = activeVideoLayers.getOrNull(1)
        ?: resolvedComposition.outgoingLayer?.takeIf {
            it.asset?.type == MediaType.VIDEO && it.asset.status == MediaStatus.READY
        }
    val secondaryVideoLayer = secondaryResolvedVideo?.sourceLayer
    val secondaryVideoAsset = secondaryResolvedVideo?.asset

    val activeAudioSlots = remember(activeFrame.audioLayers, assetsById) {
        activeFrame.audioLayers
            .filter { layer ->
                val a = assetsById[layer.clip.assetId]
                a != null && a.status == MediaStatus.READY
            }
            .take(4)
    }
    val primaryAudio = activeAudioSlots.firstOrNull()
    val audioAsset = primaryAudio?.let { assetsById[it.clip.assetId] }

    val primaryVideoPlayer = remember(context) { ExoPlayer.Builder(context).build() }
    val secondaryVideoPlayer = remember(context) { ExoPlayer.Builder(context).build() }
    val audioPlayers = remember(context) { List(4) { ExoPlayer.Builder(context).build() } }

    var loadedPrimaryVideoUri by remember { mutableStateOf<String?>(null) }
    var loadedSecondaryVideoUri by remember { mutableStateOf<String?>(null) }
    val loadedAudioUris = remember { Array<String?>(4) { null } }

    var reportedPrimaryRatio by remember { mutableFloatStateOf(16f / 9f) }
    var reportedSecondaryRatio by remember { mutableFloatStateOf(16f / 9f) }

    var attachedPrimarySurface by remember { mutableStateOf<Surface?>(null) }
    var attachedSecondarySurface by remember { mutableStateOf<Surface?>(null) }

    val glRenderer = remember(context) {
        GlCompositorRenderer(context) { s0, s1 ->
            mainHandler.post {
                attachedPrimarySurface = s0
                attachedSecondarySurface = s1
                primaryVideoPlayer.setVideoSurface(s0)
                secondaryVideoPlayer.setVideoSurface(s1)
            }
        }
    }

    DisposableEffect(glRenderer, primaryVideoPlayer, secondaryVideoPlayer, audioPlayers) {
        val primaryListener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    reportedPrimaryRatio = (videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio) /
                        videoSize.height.toFloat()
                    glRenderer.requestRender()
                }
            }
        }
        val secondaryListener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    reportedSecondaryRatio = (videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio) /
                        videoSize.height.toFloat()
                    glRenderer.requestRender()
                }
            }
        }
        primaryVideoPlayer.addListener(primaryListener)
        secondaryVideoPlayer.addListener(secondaryListener)
        onDispose {
            primaryVideoPlayer.removeListener(primaryListener)
            secondaryVideoPlayer.removeListener(secondaryListener)
            primaryVideoPlayer.setVideoSurface(null)
            secondaryVideoPlayer.setVideoSurface(null)
            glRenderer.release()
            primaryVideoPlayer.release()
            secondaryVideoPlayer.release()
            audioPlayers.forEach { it.release() }
        }
    }

    // Push resolved multi-layer composition state to GLSL compositor on every change
    LaunchedEffect(
        resolvedComposition,
        primaryVideoLayer?.clip?.clipId,
        secondaryVideoLayer?.clip?.clipId,
        reportedPrimaryRatio,
        reportedSecondaryRatio,
        attachedPrimarySurface,
        attachedSecondarySurface,
    ) {
        glRenderer.updateComposition(
            frame = resolvedComposition,
            primaryClipId = primaryVideoLayer?.clip?.clipId,
            secondaryClipId = secondaryVideoLayer?.clip?.clipId,
            primaryVideoRatio = reportedPrimaryRatio,
            secondaryVideoRatio = reportedSecondaryRatio,
        )
    }

    // Sync Primary Video Slot
    LaunchedEffect(
        primaryVideoAsset?.playbackUri,
        primaryVideoLayer?.clip?.clipId,
        primaryVideoLayer?.clip?.speed,
        primaryVideoLayer?.effectiveVolume,
        isPlaying,
        playheadMs,
        attachedPrimarySurface,
    ) {
        val result = syncExoPlayerToLayer(
            player = primaryVideoPlayer,
            layer = primaryVideoLayer,
            asset = primaryVideoAsset,
            loadedUri = loadedPrimaryVideoUri,
            isPlaying = isPlaying,
        )
        loadedPrimaryVideoUri = result.first
        if (result.second > 0f) reportedPrimaryRatio = result.second
    }

    // Sync Secondary Overlay Video Slot
    LaunchedEffect(
        secondaryVideoAsset?.playbackUri,
        secondaryVideoLayer?.clip?.clipId,
        secondaryVideoLayer?.clip?.speed,
        secondaryVideoLayer?.effectiveVolume,
        isPlaying,
        playheadMs,
        attachedSecondarySurface,
    ) {
        val result = syncExoPlayerToLayer(
            player = secondaryVideoPlayer,
            layer = secondaryVideoLayer,
            asset = secondaryVideoAsset,
            loadedUri = loadedSecondaryVideoUri,
            isPlaying = isPlaying,
        )
        loadedSecondaryVideoUri = result.first
        if (result.second > 0f) reportedSecondaryRatio = result.second
    }

    // Sync Multi-Track Audio Pool (up to 4 simultaneous active audio layers on timeline clock)
    LaunchedEffect(activeAudioSlots, isPlaying, playheadMs) {
        for (slotIdx in audioPlayers.indices) {
            val player = audioPlayers[slotIdx]
            val layer = activeAudioSlots.getOrNull(slotIdx)
            val slotAsset = layer?.let { assetsById[it.clip.assetId] }
            if (layer != null && slotAsset != null && slotAsset.status == MediaStatus.READY) {
                if (loadedAudioUris[slotIdx] != slotAsset.playbackUri) {
                    loadedAudioUris[slotIdx] = slotAsset.playbackUri
                    player.setMediaItem(MediaItem.fromUri(slotAsset.playbackUri))
                    player.prepare()
                }
                val speed = layer.clip.speed.coerceIn(0.25f, 4.0f)
                if (abs(player.playbackParameters.speed - speed) > 0.01f) {
                    player.playbackParameters = PlaybackParameters(speed)
                }
                val targetSourceMs = (
                    layer.clip.sourceStartMs + (layer.clipRelativeMs * speed).toLong()
                    ).coerceAtLeast(0L)
                player.volume = layer.effectiveVolume.coerceIn(0f, 1f)
                if (isPlaying) {
                    if (abs(player.currentPosition - targetSourceMs) > 260L) {
                        player.seekTo(targetSourceMs)
                    }
                    if (!player.isPlaying) {
                        player.playWhenReady = true
                    }
                } else {
                    if (player.isPlaying || player.playWhenReady) {
                        player.playWhenReady = false
                    }
                }
            } else {
                if (player.playWhenReady || player.isPlaying) {
                    player.playWhenReady = false
                }
                if (loadedAudioUris[slotIdx] != null && slotAsset == null) {
                    loadedAudioUris[slotIdx] = null
                    player.clearMediaItems()
                }
            }
        }
    }

    val anyVisualAssetMissing = resolvedComposition.layers.any {
        it.asset != null && it.asset.status != MediaStatus.READY
    }
    val hasVisualContent = resolvedComposition.layers.isNotEmpty()

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

                // OpenGL ES 2.0 + GLSL Multi-Layer Compositor Surface (always mounted cleanly when visuals exist)
                if (hasVisualContent && !anyVisualAssetMissing) {
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                isOpaque = false
                                surfaceTextureListener = glRenderer
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (!hasVisualContent && audioAsset == null && activeFrame.textLayers.isEmpty()) {
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
                } else if (!hasVisualContent && audioAsset != null && activeFrame.textLayers.isEmpty()) {
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
                } else if (anyVisualAssetMissing) {
                    Icon(
                        imageVector = Icons.Default.BrokenImage,
                        contentDescription = "Missing media",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp),
                    )
                }

                // Active Text & Kinetic Typography Layers rendered above GL stage
                activeFrame.textLayers.forEach { textLayer ->
                    val animatedSpec = animationEngine.evaluateTextSpec(textLayer.clip, textLayer.clipRelativeMs)
                    val animatedTransform = animationEngine.evaluateTransform(textLayer.clip, textLayer.clipRelativeMs)
                    if (animatedSpec != null) {
                        RenderedTextOverlay(
                            spec = animatedSpec,
                            transform = animatedTransform,
                            blendMode = textLayer.clip.blendMode,
                            clipRelativeMs = textLayer.clipRelativeMs,
                            stageWidthPx = stageWidthPx,
                            stageHeightPx = stageHeightPx,
                        )
                    }
                }
            }

            // Active Layer + Adjustment + Audio HUD Badges
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val topVisual = resolvedComposition.layers.lastOrNull()
                if (topVisual != null && topVisual.asset != null) {
                    val layerCountTag = if (resolvedComposition.layers.size > 1) {
                        "${resolvedComposition.layers.size}L • "
                    } else ""
                    val speedSuffix = if (abs(topVisual.sourceLayer.clip.speed - 1f) > 0.02f) {
                        " • ${String.format("%.2gx", topVisual.sourceLayer.clip.speed)}"
                    } else ""
                    StudioBadge(
                        text = "$layerCountTag${topVisual.sourceLayer.track.name}: ${topVisual.asset.displayName}$speedSuffix",
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    )
                }
                if (resolvedComposition.hasActiveAdjustmentLayer) {
                    StudioBadge(
                        text = "ADJ",
                        containerColor = studio.readyEmeraldContainer.copy(alpha = 0.9f),
                        contentColor = studio.readyEmerald,
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
                M3Surface(
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

private fun syncExoPlayerToLayer(
    player: ExoPlayer,
    layer: ActiveClipLayer?,
    asset: MediaAsset?,
    loadedUri: String?,
    isPlaying: Boolean,
): Pair<String?, Float> {
    var nextUri = loadedUri
    var newRatio = -1f
    if (layer != null && asset != null &&
        asset.type == MediaType.VIDEO &&
        asset.status == MediaStatus.READY
    ) {
        if (nextUri != asset.playbackUri) {
            nextUri = asset.playbackUri
            if (asset.width > 0 && asset.height > 0) {
                newRatio = asset.width.toFloat() / asset.height.toFloat()
            }
            player.setMediaItem(MediaItem.fromUri(asset.playbackUri))
            player.prepare()
        }
        val speed = layer.clip.speed.coerceIn(0.25f, 4.0f)
        if (abs(player.playbackParameters.speed - speed) > 0.01f) {
            player.playbackParameters = PlaybackParameters(speed)
        }
        val targetSourceMs = (
            layer.clip.sourceStartMs + (layer.clipRelativeMs * speed).toLong()
            ).coerceAtLeast(0L)
        player.volume = layer.effectiveVolume.coerceIn(0f, 1f)
        if (isPlaying) {
            if (abs(player.currentPosition - targetSourceMs) > 260L) {
                player.seekTo(targetSourceMs)
            }
            if (!player.isPlaying) {
                player.playWhenReady = true
            }
        } else {
            if (player.isPlaying || player.playWhenReady) {
                player.playWhenReady = false
            }
            if (abs(player.currentPosition - targetSourceMs) > 35L) {
                player.seekTo(targetSourceMs)
            }
        }
    } else {
        if (player.playWhenReady || player.isPlaying) {
            player.playWhenReady = false
        }
        if (nextUri != null && asset == null) {
            nextUri = null
            player.clearMediaItems()
        }
    }
    return nextUri to newRatio
}

@Composable
private fun RenderedTextOverlay(
    spec: TextOverlaySpec,
    transform: LayerTransform,
    blendMode: BlendModePreset,
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

    val offsetPxX = ((spec.offsetX + transform.positionX).coerceIn(-0.9f, 0.9f) * stageWidthPx).roundToInt()
    val offsetPxY = ((spec.offsetY + transform.positionY).coerceIn(-0.9f, 0.9f) * stageHeightPx).roundToInt()
    val composeBlend = when (blendMode) {
        BlendModePreset.NORMAL -> BlendMode.SrcOver
        BlendModePreset.ADD -> BlendMode.Plus
        BlendModePreset.MULTIPLY -> BlendMode.Multiply
        BlendModePreset.SCREEN -> BlendMode.Screen
        BlendModePreset.OVERLAY -> BlendMode.Overlay
        BlendModePreset.DARKEN -> BlendMode.Darken
        BlendModePreset.LIGHTEN -> BlendMode.Lighten
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetPxX, offsetPxY) }
            .then(
                if (blendMode != BlendModePreset.NORMAL) {
                    Modifier.drawWithContent {
                        val layerPaint = Paint().apply { this.blendMode = composeBlend }
                        drawContext.canvas.saveLayer(size.toRect(), layerPaint)
                        drawContent()
                        drawContext.canvas.restore()
                    }
                } else {
                    Modifier
                },
            )
            .graphicsLayer {
                alpha = (animAlpha * transform.opacity).coerceIn(0f, 1f)
                scaleX = animScale * transform.scaleX
                scaleY = animScale * transform.scaleY
                rotationZ = transform.rotationDeg
                translationY = animTranslateY
                translationX = glitchOffsetX
                transformOrigin = TransformOrigin(transform.anchorX, transform.anchorY)
                compositingStrategy = CompositingStrategy.Offscreen
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
