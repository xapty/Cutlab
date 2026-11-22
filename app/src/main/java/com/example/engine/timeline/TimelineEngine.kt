package com.example.engine.timeline

import com.example.engine.effects.EffectsEngine
import com.example.engine.project.ClipAdjustments
import com.example.engine.project.ClipKind
import com.example.engine.project.ClipTransition
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectDocument
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TimelineClipRef
import com.example.engine.project.TimelineTrack
import com.example.engine.project.TrackType
import com.example.engine.project.TransitionType
import java.util.UUID
import kotlin.math.abs

const val MIN_CLIP_DURATION_MS = 100L
const val MAX_IMAGE_CLIP_DURATION_MS = 600_000L
const val TEXT_ASSET_PLACEHOLDER_ID = "cutlab_text_layer"

data class ActiveClipLayer(
    val track: TimelineTrack,
    val clip: TimelineClipRef,
    val clipRelativeMs: Long,
    val effectiveVolume: Float = clip.volume,
    val transitionProgress: Float = 1f,
    val activeTransition: ClipTransition? = null,
)

data class ActiveTimelineFrame(
    val playheadMs: Long,
    val visualLayers: List<ActiveClipLayer>,
    val audioLayers: List<ActiveClipLayer>,
    val textLayers: List<ActiveClipLayer> = emptyList(),
    val outgoingVisualLayer: ActiveClipLayer? = null,
) {
    val primaryVisualLayer: ActiveClipLayer?
        get() = visualLayers.firstOrNull()
}

data class SnapResult(
    val positionMs: Long,
    val snappedTargetMs: Long?,
)

data class ClipTransformResult(
    val document: ProjectDocument,
    val snappedTargetMs: Long? = null,
)

class TimelineEngine {

    fun calculateTimelineDurationMs(document: ProjectDocument): Long {
        return document.tracks
            .flatMap { it.clips }
            .maxOfOrNull { it.timelineStartMs + it.durationMs }
            ?: 0L
    }

    fun evaluateFrame(document: ProjectDocument, playheadMs: Long): ActiveTimelineFrame {
        val safePlayhead = playheadMs.coerceAtLeast(0L)
        val sortedTracks = document.tracks.sortedBy { it.orderIndex }

        val visualLayers = mutableListOf<ActiveClipLayer>()
        val textLayers = mutableListOf<ActiveClipLayer>()
        val audioLayers = mutableListOf<ActiveClipLayer>()
        var outgoingVisualLayer: ActiveClipLayer? = null

        for (track in sortedTracks) {
            if (track.isMuted) continue
            val sortedClips = track.clips.sortedBy { it.timelineStartMs }
            val activeIndex = sortedClips.indexOfFirst { clip ->
                safePlayhead >= clip.timelineStartMs &&
                    safePlayhead < (clip.timelineStartMs + clip.durationMs)
            }
            if (activeIndex == -1) continue

            val activeClip = sortedClips[activeIndex]
            val relativeMs = (safePlayhead - activeClip.timelineStartMs)
                .coerceIn(0L, activeClip.durationMs)

            val effectiveVol = computeEffectiveVolume(track, activeClip, relativeMs)
            val transition = activeClip.transitionIn?.takeIf { it.type != TransitionType.NONE && it.durationMs > 0L }
            val (transitionProgress, activeTransition) = if (transition != null && relativeMs < transition.durationMs) {
                val rawT = (relativeMs.toFloat() / transition.durationMs.toFloat()).coerceIn(0f, 1f)
                EffectsEngine.applyEasing(rawT, transition.easing) to transition
            } else {
                1f to null
            }

            if (activeTransition != null &&
                (track.type == TrackType.VIDEO || track.type == TrackType.OVERLAY) &&
                outgoingVisualLayer == null &&
                activeIndex > 0
            ) {
                val prevClip = sortedClips[activeIndex - 1]
                val prevEnd = prevClip.timelineStartMs + prevClip.durationMs
                if (activeClip.timelineStartMs - prevEnd <= 60L) {
                    outgoingVisualLayer = ActiveClipLayer(
                        track = track,
                        clip = prevClip,
                        clipRelativeMs = (prevClip.durationMs - 16L).coerceAtLeast(0L),
                        effectiveVolume = 0f,
                        transitionProgress = 1f,
                    )
                }
            }

            val layer = ActiveClipLayer(
                track = track,
                clip = activeClip,
                clipRelativeMs = relativeMs,
                effectiveVolume = effectiveVol,
                transitionProgress = transitionProgress,
                activeTransition = activeTransition,
            )

            when (track.type) {
                TrackType.VIDEO, TrackType.OVERLAY -> visualLayers += layer
                TrackType.TEXT -> textLayers += layer
                TrackType.AUDIO -> audioLayers += layer
                TrackType.ADJUSTMENT -> Unit
            }
        }

        return ActiveTimelineFrame(
            playheadMs = safePlayhead,
            visualLayers = visualLayers,
            audioLayers = audioLayers,
            textLayers = textLayers,
            outgoingVisualLayer = outgoingVisualLayer,
        )
    }

    private fun computeEffectiveVolume(
        track: TimelineTrack,
        clip: TimelineClipRef,
        clipRelativeMs: Long,
    ): Float {
        if (track.isMuted || clip.isMuted || clip.volume <= 0f) return 0f
        var envelope = 1f
        if (clip.fadeInMs > 0L && clipRelativeMs < clip.fadeInMs) {
            envelope *= (clipRelativeMs.toFloat() / clip.fadeInMs.toFloat()).coerceIn(0f, 1f)
        }
        val remainingMs = (clip.durationMs - clipRelativeMs).coerceAtLeast(0L)
        if (clip.fadeOutMs > 0L && remainingMs < clip.fadeOutMs) {
            envelope *= (remainingMs.toFloat() / clip.fadeOutMs.toFloat()).coerceIn(0f, 1f)
        }
        return (clip.volume * envelope).coerceIn(0f, 2.0f)
    }

    fun collectSnapTargets(
        document: ProjectDocument,
        excludeClipId: String? = null,
        playheadMs: Long? = null,
    ): List<Long> {
        val targets = mutableSetOf(0L)
        if (playheadMs != null && playheadMs >= 0L) {
            targets += playheadMs
        }
        for (track in document.tracks) {
            for (clip in track.clips) {
                if (clip.clipId == excludeClipId) continue
                targets += clip.timelineStartMs
                targets += clip.timelineStartMs + clip.durationMs
            }
        }
        return targets.sorted()
    }

    fun snapPosition(
        desiredMs: Long,
        snapTargets: List<Long>,
        snappingEnabled: Boolean,
        thresholdMs: Long = 160L,
    ): SnapResult {
        if (!snappingEnabled || snapTargets.isEmpty()) {
            return SnapResult(desiredMs, null)
        }
        var bestTarget: Long? = null
        var bestDelta = thresholdMs + 1L
        for (target in snapTargets) {
            val delta = abs(desiredMs - target)
            if (delta < bestDelta) {
                bestDelta = delta
                bestTarget = target
            }
        }
        return if (bestTarget != null && bestDelta <= thresholdMs) {
            SnapResult(bestTarget, bestTarget)
        } else {
            SnapResult(desiredMs, null)
        }
    }

    fun addTrack(
        document: ProjectDocument,
        type: TrackType,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<ProjectDocument, TimelineTrack> {
        val existingOfType = document.tracks.count { it.type == type }
        val number = existingOfType + 1
        val newTrack = TimelineTrack(
            id = "track_${type.name.lowercase()}_${UUID.randomUUID().toString().take(6)}",
            name = "${type.shortPrefix}$number ${type.label}",
            type = type,
            orderIndex = document.tracks.size,
        )
        val updated = document.copy(
            tracks = document.tracks + newTrack,
            updatedAt = nowMs,
        )
        return updated to newTrack
    }

    fun reorderTrack(
        document: ProjectDocument,
        trackId: String,
        delta: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        val sorted = document.tracks.sortedBy { it.orderIndex }.toMutableList()
        val currentIdx = sorted.indexOfFirst { it.id == trackId }
        if (currentIdx == -1) return document
        val targetIdx = (currentIdx + delta).coerceIn(0, sorted.lastIndex)
        if (targetIdx == currentIdx) return document
        val moving = sorted.removeAt(currentIdx)
        sorted.add(targetIdx, moving)
        val reindexed = sorted.mapIndexed { index, track -> track.copy(orderIndex = index) }
        return document.copy(tracks = reindexed, updatedAt = nowMs)
    }

    fun toggleTrackMute(
        document: ProjectDocument,
        trackId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        return document.copy(
            tracks = document.tracks.map { track ->
                if (track.id == trackId) track.copy(isMuted = !track.isMuted) else track
            },
            updatedAt = nowMs,
        )
    }

    fun toggleTrackLock(
        document: ProjectDocument,
        trackId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        return document.copy(
            tracks = document.tracks.map { track ->
                if (track.id == trackId) track.copy(isLocked = !track.isLocked) else track
            },
            updatedAt = nowMs,
        )
    }

    fun removeTrack(
        document: ProjectDocument,
        trackId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        if (document.tracks.size <= 1) return document
        val target = document.tracks.firstOrNull { it.id == trackId } ?: return document
        if (target.isLocked) return document
        val remaining = document.tracks
            .filterNot { it.id == trackId }
            .sortedBy { it.orderIndex }
            .mapIndexed { index, track -> track.copy(orderIndex = index) }
        return document.copy(tracks = remaining, updatedAt = nowMs)
    }

    fun insertAssetAsClip(
        document: ProjectDocument,
        asset: MediaAsset,
        targetTrackId: String? = null,
        atTimelineMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<ProjectDocument, TimelineClipRef?> {
        val compatibleTypes = compatibleTrackTypes(asset.type)
        val targetTrack = if (targetTrackId != null) {
            document.tracks.firstOrNull {
                it.id == targetTrackId && it.type in compatibleTypes && !it.isLocked
            }
        } else {
            document.tracks.firstOrNull {
                it.type in compatibleTypes && !it.isLocked
            }
        } ?: return document to null

        val duration = when (asset.type) {
            MediaType.IMAGE -> 3_000L
            MediaType.VIDEO, MediaType.AUDIO -> asset.durationMs.coerceAtLeast(1_000L)
        }
        val sourceTotal = when (asset.type) {
            MediaType.IMAGE -> MAX_IMAGE_CLIP_DURATION_MS
            MediaType.VIDEO, MediaType.AUDIO -> asset.durationMs.coerceAtLeast(duration)
        }

        val desiredStart = atTimelineMs ?: (
            targetTrack.clips.maxOfOrNull { it.timelineStartMs + it.durationMs } ?: 0L
        )
        val nonOverlappingStart = resolveNonOverlappingStart(
            existingClips = targetTrack.clips,
            desiredStartMs = desiredStart.coerceAtLeast(0L),
            durationMs = duration,
        )

        val newClip = TimelineClipRef(
            clipId = UUID.randomUUID().toString(),
            assetId = asset.id,
            trackId = targetTrack.id,
            timelineStartMs = nonOverlappingStart,
            sourceStartMs = 0L,
            durationMs = duration,
            sourceTotalDurationMs = sourceTotal,
        )

        val updatedTracks = document.tracks.map { track ->
            if (track.id == targetTrack.id) {
                track.copy(clips = (track.clips + newClip).sortedBy { it.timelineStartMs })
            } else {
                track
            }
        }
        return document.copy(tracks = updatedTracks, updatedAt = nowMs) to newClip
    }

    fun insertTextClip(
        document: ProjectDocument,
        spec: TextOverlaySpec,
        targetTrackId: String? = null,
        atTimelineMs: Long? = null,
        durationMs: Long = 3_000L,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<ProjectDocument, TimelineClipRef?> {
        var workingDoc = document
        var targetTrack = if (targetTrackId != null) {
            workingDoc.tracks.firstOrNull {
                it.id == targetTrackId && it.type == TrackType.TEXT && !it.isLocked
            }
        } else {
            workingDoc.tracks.firstOrNull { it.type == TrackType.TEXT && !it.isLocked }
        }
        if (targetTrack == null && targetTrackId == null) {
            val hasLockedOnly = workingDoc.tracks.any { it.type == TrackType.TEXT && it.isLocked }
            if (hasLockedOnly) return document to null
            val (docWithTrack, newTrack) = addTrack(workingDoc, TrackType.TEXT, nowMs)
            workingDoc = docWithTrack
            targetTrack = newTrack
        }
        val resolvedTrack = targetTrack ?: return document to null

        val safeDuration = durationMs.coerceIn(MIN_CLIP_DURATION_MS, MAX_IMAGE_CLIP_DURATION_MS)
        val desiredStart = (atTimelineMs ?: (
            resolvedTrack.clips.maxOfOrNull { it.timelineStartMs + it.durationMs } ?: 0L
        )).coerceAtLeast(0L)

        val nonOverlappingStart = resolveNonOverlappingStart(
            existingClips = resolvedTrack.clips,
            desiredStartMs = desiredStart,
            durationMs = safeDuration,
        )

        val newClip = TimelineClipRef(
            clipId = UUID.randomUUID().toString(),
            assetId = TEXT_ASSET_PLACEHOLDER_ID,
            trackId = resolvedTrack.id,
            timelineStartMs = nonOverlappingStart,
            sourceStartMs = 0L,
            durationMs = safeDuration,
            sourceTotalDurationMs = MAX_IMAGE_CLIP_DURATION_MS,
            clipKind = ClipKind.TEXT,
            textSpec = spec,
        )

        val updatedTracks = workingDoc.tracks.map { track ->
            if (track.id == resolvedTrack.id) {
                track.copy(clips = (track.clips + newClip).sortedBy { it.timelineStartMs })
            } else {
                track
            }
        }
        return workingDoc.copy(tracks = updatedTracks, updatedAt = nowMs) to newClip
    }

    fun updateClipText(
        document: ProjectDocument,
        clipId: String,
        spec: TextOverlaySpec,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        if (clip.clipKind != ClipKind.TEXT) return@updateClipById clip
        clip.copy(textSpec = spec)
    }

    fun updateClipSpeed(
        document: ProjectDocument,
        clipId: String,
        newSpeed: Float,
        isImageClip: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        val ownerTrack = document.tracks.firstOrNull { track ->
            track.clips.any { it.clipId == clipId }
        } ?: return document
        if (ownerTrack.isLocked) return document

        val clip = ownerTrack.clips.first { it.clipId == clipId }
        if (clip.clipKind == ClipKind.TEXT || isImageClip) return document

        val clampedSpeed = newSpeed.coerceIn(0.25f, 4.0f)
        if (abs(clampedSpeed - clip.speed) < 0.001f) return document

        val currentSourceSpanMs = (clip.durationMs * clip.speed).toLong().coerceAtLeast(MIN_CLIP_DURATION_MS)
        val newDurationMs = (currentSourceSpanMs / clampedSpeed).toLong()
            .coerceAtLeast(MIN_CLIP_DURATION_MS)
        val durationDelta = newDurationMs - clip.durationMs
        val oldEndMs = clip.timelineStartMs + clip.durationMs

        val sortedClips = ownerTrack.clips.sortedBy { it.timelineStartMs }
        val clipIndex = sortedClips.indexOfFirst { it.clipId == clipId }
        val nextClipStart = if (clipIndex < sortedClips.lastIndex) {
            sortedClips[clipIndex + 1].timelineStartMs
        } else Long.MAX_VALUE
        val overlapShift = if (clip.timelineStartMs + newDurationMs > nextClipStart) {
            (clip.timelineStartMs + newDurationMs) - nextClipStart
        } else 0L

        val updatedClip = clip.copy(
            speed = clampedSpeed,
            durationMs = newDurationMs,
            fadeInMs = clip.fadeInMs.coerceAtMost(newDurationMs),
            fadeOutMs = clip.fadeOutMs.coerceAtMost(newDurationMs),
            transitionIn = clip.transitionIn?.let {
                it.copy(durationMs = it.durationMs.coerceAtMost(newDurationMs))
            },
        )

        val updatedClips = sortedClips.map { c ->
            when {
                c.clipId == clipId -> updatedClip
                overlapShift > 0L && c.timelineStartMs >= oldEndMs -> {
                    c.copy(timelineStartMs = c.timelineStartMs + overlapShift)
                }
                durationDelta < 0L -> c
                else -> c
            }
        }

        val updatedTracks = document.tracks.map { track ->
            if (track.id == ownerTrack.id) track.copy(clips = updatedClips) else track
        }
        return document.copy(tracks = updatedTracks, updatedAt = nowMs)
    }

    fun updateClipAudio(
        document: ProjectDocument,
        clipId: String,
        volume: Float,
        isMuted: Boolean,
        fadeInMs: Long,
        fadeOutMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        val safeVol = volume.coerceIn(0f, 2.0f)
        val safeFadeIn = fadeInMs.coerceIn(0L, clip.durationMs)
        val safeFadeOut = fadeOutMs.coerceIn(0L, clip.durationMs)
        clip.copy(
            volume = safeVol,
            isMuted = isMuted,
            fadeInMs = safeFadeIn,
            fadeOutMs = safeFadeOut,
        )
    }

    fun updateClipTransition(
        document: ProjectDocument,
        clipId: String,
        transition: ClipTransition?,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        val sanitized = transition?.takeIf { it.type != TransitionType.NONE }?.let {
            it.copy(durationMs = it.durationMs.coerceIn(100L, clip.durationMs.coerceAtLeast(100L)))
        }
        clip.copy(transitionIn = sanitized)
    }

    fun updateClipAdjustments(
        document: ProjectDocument,
        clipId: String,
        adjustments: ClipAdjustments,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = updateClipById(document, clipId, nowMs) { clip ->
        if (clip.clipKind != ClipKind.MEDIA) return@updateClipById clip
        clip.copy(adjustments = adjustments)
    }

    fun applyAdjustmentsToAllVisualClips(
        document: ProjectDocument,
        adjustments: ClipAdjustments,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        val updatedTracks = document.tracks.map { track ->
            if (track.isLocked || (track.type != TrackType.VIDEO && track.type != TrackType.OVERLAY)) {
                track
            } else {
                track.copy(
                    clips = track.clips.map { clip ->
                        if (clip.clipKind == ClipKind.MEDIA) {
                            clip.copy(adjustments = adjustments)
                        } else {
                            clip
                        }
                    },
                )
            }
        }
        return document.copy(tracks = updatedTracks, updatedAt = nowMs)
    }

    private fun updateClipById(
        document: ProjectDocument,
        clipId: String,
        nowMs: Long,
        transform: (TimelineClipRef) -> TimelineClipRef,
    ): ProjectDocument {
        val ownerTrack = document.tracks.firstOrNull { track ->
            track.clips.any { it.clipId == clipId }
        } ?: return document
        if (ownerTrack.isLocked) return document

        val updatedTracks = document.tracks.map { track ->
            if (track.id == ownerTrack.id) {
                track.copy(
                    clips = track.clips.map { if (it.clipId == clipId) transform(it) else it },
                )
            } else {
                track
            }
        }
        return document.copy(tracks = updatedTracks, updatedAt = nowMs)
    }

    fun splitClipAt(
        document: ProjectDocument,
        clipId: String,
        splitTimelineMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<ProjectDocument, String?> {
        val ownerTrack = document.tracks.firstOrNull { track ->
            track.clips.any { it.clipId == clipId }
        } ?: return document to null
        if (ownerTrack.isLocked) return document to null

        val targetClip = ownerTrack.clips.first { it.clipId == clipId }
        val clipEnd = targetClip.timelineStartMs + targetClip.durationMs
        if (splitTimelineMs < targetClip.timelineStartMs + MIN_CLIP_DURATION_MS ||
            splitTimelineMs > clipEnd - MIN_CLIP_DURATION_MS
        ) {
            return document to null
        }

        val leftDuration = splitTimelineMs - targetClip.timelineStartMs
        val rightDuration = targetClip.durationMs - leftDuration
        val leftClip = targetClip.copy(
            durationMs = leftDuration,
            fadeInMs = targetClip.fadeInMs.coerceAtMost(leftDuration),
            fadeOutMs = 0L,
            transitionIn = targetClip.transitionIn?.let {
                it.copy(durationMs = it.durationMs.coerceAtMost(leftDuration))
            },
        )
        val rightClipId = UUID.randomUUID().toString()
        val rightClip = targetClip.copy(
            clipId = rightClipId,
            timelineStartMs = splitTimelineMs,
            sourceStartMs = targetClip.sourceStartMs + (leftDuration * targetClip.speed).toLong(),
            durationMs = rightDuration,
            fadeInMs = 0L,
            fadeOutMs = targetClip.fadeOutMs.coerceAtMost(rightDuration),
            transitionIn = null,
        )

        val updatedTracks = document.tracks.map { track ->
            if (track.id == ownerTrack.id) {
                val nextClips = track.clips
                    .filterNot { it.clipId == clipId }
                    .plus(listOf(leftClip, rightClip))
                    .sortedBy { it.timelineStartMs }
                track.copy(clips = nextClips)
            } else {
                track
            }
        }
        return document.copy(tracks = updatedTracks, updatedAt = nowMs) to rightClipId
    }

    fun trimClipLeft(
        document: ProjectDocument,
        clipId: String,
        desiredStartMs: Long,
        snappingEnabled: Boolean = false,
        playheadMs: Long? = null,
        snapThresholdMs: Long = 160L,
        nowMs: Long = System.currentTimeMillis(),
    ): ClipTransformResult {
        val ownerTrack = document.tracks.firstOrNull { track ->
            track.clips.any { it.clipId == clipId }
        } ?: return ClipTransformResult(document)
        if (ownerTrack.isLocked) return ClipTransformResult(document)

        val sortedClips = ownerTrack.clips.sortedBy { it.timelineStartMs }
        val clipIndex = sortedClips.indexOfFirst { it.clipId == clipId }
        val clip = sortedClips[clipIndex]
        val clipEnd = clip.timelineStartMs + clip.durationMs

        val snapTargets = collectSnapTargets(document, excludeClipId = clipId, playheadMs = playheadMs)
        val snapped = snapPosition(desiredStartMs, snapTargets, snappingEnabled, snapThresholdMs)

        val prevClipEnd = if (clipIndex > 0) {
            sortedClips[clipIndex - 1].let { it.timelineStartMs + it.durationMs }
        } else 0L
        val earliestSourceLimit = if (clip.clipKind == ClipKind.TEXT) {
            0L
        } else {
            clip.timelineStartMs - (clip.sourceStartMs / clip.speed).toLong()
        }
        val minStart = maxOf(0L, prevClipEnd, earliestSourceLimit)
        val maxStart = clipEnd - MIN_CLIP_DURATION_MS

        val clampedStart = snapped.positionMs.coerceIn(minStart, maxStart)
        if (clampedStart == clip.timelineStartMs) {
            return ClipTransformResult(document, snapped.snappedTargetMs)
        }

        val deltaTimeline = clampedStart - clip.timelineStartMs
        val newSourceStart = if (clip.clipKind == ClipKind.TEXT) {
            0L
        } else {
            (clip.sourceStartMs + (deltaTimeline * clip.speed).toLong()).coerceAtLeast(0L)
        }
        val newDuration = clipEnd - clampedStart
        val trimmed = clip.copy(
            timelineStartMs = clampedStart,
            sourceStartMs = newSourceStart,
            durationMs = newDuration,
            fadeInMs = clip.fadeInMs.coerceAtMost(newDuration),
            fadeOutMs = clip.fadeOutMs.coerceAtMost(newDuration),
            transitionIn = clip.transitionIn?.let {
                it.copy(durationMs = it.durationMs.coerceAtMost(newDuration))
            },
        )

        val updatedTracks = document.tracks.map { track ->
            if (track.id == ownerTrack.id) {
                track.copy(
                    clips = track.clips.map { if (it.clipId == clipId) trimmed else it }
                        .sortedBy { it.timelineStartMs },
                )
            } else {
                track
            }
        }
        val activeSnap = if (clampedStart == snapped.snappedTargetMs) snapped.snappedTargetMs else null
        return ClipTransformResult(document.copy(tracks = updatedTracks, updatedAt = nowMs), activeSnap)
    }

    fun trimClipRight(
        document: ProjectDocument,
        clipId: String,
        desiredEndMs: Long,
        maxSourceDurationMs: Long = 0L,
        snappingEnabled: Boolean = false,
        playheadMs: Long? = null,
        snapThresholdMs: Long = 160L,
        nowMs: Long = System.currentTimeMillis(),
    ): ClipTransformResult {
        val ownerTrack = document.tracks.firstOrNull { track ->
            track.clips.any { it.clipId == clipId }
        } ?: return ClipTransformResult(document)
        if (ownerTrack.isLocked) return ClipTransformResult(document)

        val sortedClips = ownerTrack.clips.sortedBy { it.timelineStartMs }
        val clipIndex = sortedClips.indexOfFirst { it.clipId == clipId }
        val clip = sortedClips[clipIndex]

        val snapTargets = collectSnapTargets(document, excludeClipId = clipId, playheadMs = playheadMs)
        val snapped = snapPosition(desiredEndMs, snapTargets, snappingEnabled, snapThresholdMs)

        val minEnd = clip.timelineStartMs + MIN_CLIP_DURATION_MS
        val nextClipStart = if (clipIndex < sortedClips.lastIndex) {
            sortedClips[clipIndex + 1].timelineStartMs
        } else Long.MAX_VALUE

        val resolvedSourceCap = maxOf(maxSourceDurationMs, clip.sourceTotalDurationMs)
        val sourceEndLimit = if (resolvedSourceCap > 0L && clip.clipKind != ClipKind.TEXT) {
            val remainingSource = ((resolvedSourceCap - clip.sourceStartMs) / clip.speed).toLong()
                .coerceAtLeast(MIN_CLIP_DURATION_MS)
            clip.timelineStartMs + remainingSource
        } else {
            Long.MAX_VALUE
        }
        val maxEnd = minOf(nextClipStart, sourceEndLimit).coerceAtLeast(minEnd)

        val clampedEnd = snapped.positionMs.coerceIn(minEnd, maxEnd)
        val newDuration = clampedEnd - clip.timelineStartMs
        if (newDuration == clip.durationMs) {
            return ClipTransformResult(document, snapped.snappedTargetMs)
        }

        val trimmed = clip.copy(
            durationMs = newDuration,
            fadeInMs = clip.fadeInMs.coerceAtMost(newDuration),
            fadeOutMs = clip.fadeOutMs.coerceAtMost(newDuration),
            transitionIn = clip.transitionIn?.let {
                it.copy(durationMs = it.durationMs.coerceAtMost(newDuration))
            },
        )
        val updatedTracks = document.tracks.map { track ->
            if (track.id == ownerTrack.id) {
                track.copy(
                    clips = track.clips.map { if (it.clipId == clipId) trimmed else it },
                )
            } else {
                track
            }
        }
        val activeSnap = if (clampedEnd == snapped.snappedTargetMs) snapped.snappedTargetMs else null
        return ClipTransformResult(document.copy(tracks = updatedTracks, updatedAt = nowMs), activeSnap)
    }

    fun moveClip(
        document: ProjectDocument,
        clipId: String,
        desiredStartMs: Long,
        targetTrackId: String? = null,
        assetType: MediaType? = null,
        snappingEnabled: Boolean = true,
        playheadMs: Long? = null,
        snapThresholdMs: Long = 160L,
        nowMs: Long = System.currentTimeMillis(),
    ): ClipTransformResult {
        val sourceTrack = document.tracks.firstOrNull { track ->
            track.clips.any { it.clipId == clipId }
        } ?: return ClipTransformResult(document)
        if (sourceTrack.isLocked) return ClipTransformResult(document)

        val clip = sourceTrack.clips.first { it.clipId == clipId }
        val resolvedTargetTrack = if (targetTrackId != null && targetTrackId != sourceTrack.id) {
            val candidate = document.tracks.firstOrNull { it.id == targetTrackId }
            val typeOk = when {
                clip.clipKind == ClipKind.TEXT -> candidate?.type == TrackType.TEXT
                assetType != null -> candidate != null && candidate.type in compatibleTrackTypes(assetType)
                else -> candidate?.type == sourceTrack.type
            }
            if (candidate != null && !candidate.isLocked && typeOk) candidate else sourceTrack
        } else {
            sourceTrack
        }

        val snapTargets = collectSnapTargets(document, excludeClipId = clipId, playheadMs = playheadMs)
        val startSnap = snapPosition(desiredStartMs.coerceAtLeast(0L), snapTargets, snappingEnabled, snapThresholdMs)
        val endSnap = snapPosition(
            (desiredStartMs + clip.durationMs).coerceAtLeast(clip.durationMs),
            snapTargets,
            snappingEnabled,
            snapThresholdMs,
        )

        val (snappedStart, activeSnapTarget) = when {
            startSnap.snappedTargetMs != null &&
                (endSnap.snappedTargetMs == null ||
                    abs(desiredStartMs - startSnap.snappedTargetMs) <= abs((desiredStartMs + clip.durationMs) - endSnap.snappedTargetMs)) ->
                startSnap.positionMs to startSnap.snappedTargetMs
            endSnap.snappedTargetMs != null ->
                (endSnap.positionMs - clip.durationMs).coerceAtLeast(0L) to endSnap.snappedTargetMs
            else -> desiredStartMs.coerceAtLeast(0L) to null
        }

        val otherClipsOnTarget = resolvedTargetTrack.clips.filterNot { it.clipId == clipId }
        val finalStart = resolveNearestValidStart(
            existingClips = otherClipsOnTarget,
            desiredStartMs = snappedStart,
            durationMs = clip.durationMs,
        )

        val movedClip = clip.copy(
            trackId = resolvedTargetTrack.id,
            timelineStartMs = finalStart,
        )

        val updatedTracks = document.tracks.map { track ->
            when {
                track.id == sourceTrack.id && resolvedTargetTrack.id == sourceTrack.id -> {
                    track.copy(
                        clips = (otherClipsOnTarget + movedClip).sortedBy { it.timelineStartMs },
                    )
                }
                track.id == sourceTrack.id -> {
                    track.copy(clips = track.clips.filterNot { it.clipId == clipId })
                }
                track.id == resolvedTargetTrack.id -> {
                    track.copy(
                        clips = (track.clips + movedClip).sortedBy { it.timelineStartMs },
                    )
                }
                else -> track
            }
        }
        val finalSnapGuide = if (finalStart == activeSnapTarget || finalStart + clip.durationMs == activeSnapTarget) {
            activeSnapTarget
        } else {
            null
        }
        return ClipTransformResult(document.copy(tracks = updatedTracks, updatedAt = nowMs), finalSnapGuide)
    }

    fun duplicateClip(
        document: ProjectDocument,
        clipId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<ProjectDocument, String?> {
        val ownerTrack = document.tracks.firstOrNull { track ->
            track.clips.any { it.clipId == clipId }
        } ?: return document to null
        if (ownerTrack.isLocked) return document to null

        val sourceClip = ownerTrack.clips.first { it.clipId == clipId }
        val insertStart = resolveNonOverlappingStart(
            existingClips = ownerTrack.clips,
            desiredStartMs = sourceClip.timelineStartMs + sourceClip.durationMs,
            durationMs = sourceClip.durationMs,
        )
        val duplicatedId = UUID.randomUUID().toString()
        val duplicated = sourceClip.copy(
            clipId = duplicatedId,
            timelineStartMs = insertStart,
        )
        val updatedTracks = document.tracks.map { track ->
            if (track.id == ownerTrack.id) {
                track.copy(clips = (track.clips + duplicated).sortedBy { it.timelineStartMs })
            } else {
                track
            }
        }
        return document.copy(tracks = updatedTracks, updatedAt = nowMs) to duplicatedId
    }

    fun removeClip(
        document: ProjectDocument,
        clipId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument = removeClips(document, setOf(clipId), nowMs)

    fun removeClips(
        document: ProjectDocument,
        clipIds: Set<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        if (clipIds.isEmpty()) return document
        val updatedTracks = document.tracks.map { track ->
            if (track.isLocked) {
                track
            } else {
                track.copy(clips = track.clips.filterNot { it.clipId in clipIds })
            }
        }
        return document.copy(tracks = updatedTracks, updatedAt = nowMs)
    }

    fun rippleDeleteClip(
        document: ProjectDocument,
        clipId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        val ownerTrack = document.tracks.firstOrNull { track ->
            track.clips.any { it.clipId == clipId }
        } ?: return document
        if (ownerTrack.isLocked) return document

        val targetClip = ownerTrack.clips.first { it.clipId == clipId }
        val shiftAmount = targetClip.durationMs
        val targetEnd = targetClip.timelineStartMs + targetClip.durationMs

        val updatedClips = ownerTrack.clips
            .filterNot { it.clipId == clipId }
            .sortedBy { it.timelineStartMs }
            .map { clip ->
                if (clip.timelineStartMs >= targetEnd) {
                    clip.copy(timelineStartMs = (clip.timelineStartMs - shiftAmount).coerceAtLeast(0L))
                } else {
                    clip
                }
            }

        val updatedTracks = document.tracks.map { track ->
            if (track.id == ownerTrack.id) track.copy(clips = updatedClips) else track
        }
        return document.copy(tracks = updatedTracks, updatedAt = nowMs)
    }

    private fun compatibleTrackTypes(assetType: MediaType): Set<TrackType> = when (assetType) {
        MediaType.VIDEO, MediaType.IMAGE -> setOf(TrackType.VIDEO, TrackType.OVERLAY)
        MediaType.AUDIO -> setOf(TrackType.AUDIO)
    }

    private fun resolveNonOverlappingStart(
        existingClips: List<TimelineClipRef>,
        desiredStartMs: Long,
        durationMs: Long,
    ): Long {
        if (existingClips.isEmpty()) return desiredStartMs
        val sorted = existingClips.sortedBy { it.timelineStartMs }
        var candidate = desiredStartMs
        for (clip in sorted) {
            val clipEnd = clip.timelineStartMs + clip.durationMs
            val overlaps = candidate < clipEnd && (candidate + durationMs) > clip.timelineStartMs
            if (overlaps) {
                candidate = clipEnd
            }
        }
        return candidate
    }

    private fun resolveNearestValidStart(
        existingClips: List<TimelineClipRef>,
        desiredStartMs: Long,
        durationMs: Long,
    ): Long {
        val sorted = existingClips.sortedBy { it.timelineStartMs }
        if (sorted.isEmpty()) return desiredStartMs.coerceAtLeast(0L)

        val desired = desiredStartMs.coerceAtLeast(0L)
        val overlapsAny = sorted.any { clip ->
            val clipEnd = clip.timelineStartMs + clip.durationMs
            desired < clipEnd && (desired + durationMs) > clip.timelineStartMs
        }
        if (!overlapsAny) return desired

        val candidates = mutableListOf<Long>()
        if (sorted.first().timelineStartMs >= durationMs) {
            candidates += (sorted.first().timelineStartMs - durationMs).coerceAtLeast(0L)
        }
        for (i in 0 until sorted.lastIndex) {
            val gapStart = sorted[i].timelineStartMs + sorted[i].durationMs
            val gapEnd = sorted[i + 1].timelineStartMs
            if (gapEnd - gapStart >= durationMs) {
                candidates += desired.coerceIn(gapStart, gapEnd - durationMs)
            }
        }
        candidates += sorted.last().let { it.timelineStartMs + it.durationMs }
        return candidates.minByOrNull { abs(it - desired) } ?: desired
    }
}
