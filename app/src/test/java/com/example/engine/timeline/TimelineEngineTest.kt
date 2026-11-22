package com.example.engine.timeline

import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.ClipAdjustments
import com.example.engine.project.ClipTransition
import com.example.engine.project.EasingPreset
import com.example.engine.project.EditorMode
import com.example.engine.project.FilterPreset
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectEngine
import com.example.engine.project.TextAnimationPreset
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TrackType
import com.example.engine.project.TransitionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEngineTest {
    private val projectEngine = ProjectEngine()
    private val timelineEngine = TimelineEngine()

    private fun sampleAsset(
        id: String,
        type: MediaType,
        durationMs: Long = 3_000L,
    ): MediaAsset = MediaAsset(
        id = id,
        projectId = "proj-1",
        type = type,
        displayName = "$id.mp4",
        sourceUri = "content://media/$id",
        managedFilePath = "/tmp/$id",
        mimeType = if (type == MediaType.AUDIO) "audio/mp3" else "video/mp4",
        durationMs = durationMs,
        width = 1080,
        height = 1920,
        fps = 30f,
        bitrate = 1_000_000L,
        fileSizeBytes = 1_000_000L,
        status = MediaStatus.READY,
        importedAt = 1_000L,
    )

    @Test
    fun `insertAssetAsClip places sequential non-overlapping clips and evaluates playhead`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Phase 2 Test",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val v1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 2_000L)
        val v2 = sampleAsset("v2", MediaType.VIDEO, durationMs = 3_000L)
        val a1 = sampleAsset("a1", MediaType.AUDIO, durationMs = 5_000L)

        val (doc1, clip1) = timelineEngine.insertAssetAsClip(doc0, v1)
        val (doc2, clip2) = timelineEngine.insertAssetAsClip(doc1, v2)
        val (doc3, audioClip) = timelineEngine.insertAssetAsClip(doc2, a1)

        assertNotNull(clip1)
        assertNotNull(clip2)
        assertNotNull(audioClip)
        assertEquals(0L, clip1?.timelineStartMs)
        assertEquals(2_000L, clip2?.timelineStartMs)
        assertEquals(5_000L, timelineEngine.calculateTimelineDurationMs(doc3))

        val frameAt2500 = timelineEngine.evaluateFrame(doc3, 2_500L)
        assertEquals("v2", frameAt2500.primaryVisualLayer?.clip?.assetId)
        assertEquals(500L, frameAt2500.primaryVisualLayer?.clipRelativeMs)
        assertEquals("a1", frameAt2500.audioLayers.firstOrNull()?.clip?.assetId)
    }

    @Test
    fun `muted track is skipped during frame evaluation and locked track blocks edits`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Lock & Mute",
            aspectRatio = AspectRatioPreset.LANDSCAPE_16_9,
            fps = 60,
            editorMode = EditorMode.ADVANCED,
        )
        val v1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 4_000L)
        val (doc1, clip1) = timelineEngine.insertAssetAsClip(doc0, v1)
        val videoTrackId = doc1.tracks.first { it.type == TrackType.VIDEO }.id

        val mutedDoc = timelineEngine.toggleTrackMute(doc1, videoTrackId)
        val frameMuted = timelineEngine.evaluateFrame(mutedDoc, 1_000L)
        assertTrue(frameMuted.visualLayers.isEmpty())

        val lockedDoc = timelineEngine.toggleTrackLock(mutedDoc, videoTrackId)
        val (afterInsertAttempt, failedClip) = timelineEngine.insertAssetAsClip(
            lockedDoc,
            sampleAsset("v2", MediaType.VIDEO),
            targetTrackId = videoTrackId,
        )
        assertNull(failedClip)

        val afterRemoveAttempt = timelineEngine.removeClip(afterInsertAttempt, clip1!!.clipId)
        assertEquals(1, afterRemoveAttempt.tracks.first { it.id == videoTrackId }.clips.size)
    }

    @Test
    fun `addTrack appends track with incremented name`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Multi-Track",
            aspectRatio = AspectRatioPreset.SQUARE_1_1,
            fps = 30,
            editorMode = EditorMode.QUICK,
        )
        val (doc1, newTrack) = timelineEngine.addTrack(doc0, TrackType.VIDEO)
        assertEquals("V2 Video", newTrack.name)
        assertEquals(5, doc1.tracks.size)
    }

    @Test
    fun `splitClipAt divides clip accurately with sourceStartMs offset and rejects near edges`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Split Test",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val v1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 4_000L)
        val (doc1, clip1) = timelineEngine.insertAssetAsClip(doc0, v1)
        val clipId = clip1!!.clipId

        // Too close to edge (< MIN_CLIP_DURATION_MS) should fail
        val (_, invalidSplit) = timelineEngine.splitClipAt(doc1, clipId, 50L)
        assertNull(invalidSplit)

        // Valid split at 1500ms
        val (doc2, rightId) = timelineEngine.splitClipAt(doc1, clipId, 1_500L)
        assertNotNull(rightId)
        val clips = doc2.tracks.first { it.type == TrackType.VIDEO }.clips
        assertEquals(2, clips.size)
        assertEquals(0L, clips[0].timelineStartMs)
        assertEquals(1_500L, clips[0].durationMs)
        assertEquals(0L, clips[0].sourceStartMs)

        assertEquals(1_500L, clips[1].timelineStartMs)
        assertEquals(2_500L, clips[1].durationMs)
        assertEquals(1_500L, clips[1].sourceStartMs)
    }

    @Test
    fun `trimClipLeft and trimClipRight respect source bounds, neighbors, and snapping`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Trim Test",
            aspectRatio = AspectRatioPreset.LANDSCAPE_16_9,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val v1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 3_000L)
        val v2 = sampleAsset("v2", MediaType.VIDEO, durationMs = 3_000L)
        val (doc1, clip1) = timelineEngine.insertAssetAsClip(doc0, v1)
        val (doc2, clip2) = timelineEngine.insertAssetAsClip(doc1, v2)

        // Trim clip2 left edge forward by 500ms
        val leftTrimmed = timelineEngine.trimClipLeft(
            document = doc2,
            clipId = clip2!!.clipId,
            desiredStartMs = 3_500L,
            snappingEnabled = false,
        ).document
        val trimmed2 = leftTrimmed.tracks.flatMap { it.clips }.first { it.clipId == clip2.clipId }
        assertEquals(3_500L, trimmed2.timelineStartMs)
        assertEquals(2_500L, trimmed2.durationMs)
        assertEquals(500L, trimmed2.sourceStartMs)

        // Trim clip1 right edge toward clip2 start with snapping (within 160ms of 3500ms -> snaps to 3500ms, capped at source 3000ms)
        val rightTrimCapped = timelineEngine.trimClipRight(
            document = leftTrimmed,
            clipId = clip1!!.clipId,
            desiredEndMs = 3_450L,
            maxSourceDurationMs = 3_000L,
            snappingEnabled = true,
        )
        val trimmed1 = rightTrimCapped.document.tracks.flatMap { it.clips }.first { it.clipId == clip1.clipId }
        assertEquals(3_000L, trimmed1.durationMs)

        // Shortening clip1 to 2000ms then snapping right edge near playhead 2400ms
        val shortened = timelineEngine.trimClipRight(
            document = leftTrimmed,
            clipId = clip1.clipId,
            desiredEndMs = 2_350L,
            maxSourceDurationMs = 3_000L,
            snappingEnabled = true,
            playheadMs = 2_400L,
        )
        val trimmed1Snapped = shortened.document.tracks.flatMap { it.clips }.first { it.clipId == clip1.clipId }
        assertEquals(2_400L, trimmed1Snapped.durationMs)
        assertEquals(2_400L, shortened.snappedTargetMs)
    }

    @Test
    fun `moveClip handles cross-track compatible move, incompatible rejection, and collision avoidance`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Move Test",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val v1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 2_000L)
        val v2 = sampleAsset("v2", MediaType.VIDEO, durationMs = 2_000L)
        val (doc1, clip1) = timelineEngine.insertAssetAsClip(doc0, v1)
        val (doc2, clip2) = timelineEngine.insertAssetAsClip(doc1, v2)

        val overlayTrack = doc2.tracks.first { it.type == TrackType.OVERLAY }
        val audioTrack = doc2.tracks.first { it.type == TrackType.AUDIO }

        // Move clip2 onto overlay track at 500ms (with snapping disabled so exact 500ms)
        val movedToOverlay = timelineEngine.moveClip(
            document = doc2,
            clipId = clip2!!.clipId,
            desiredStartMs = 500L,
            targetTrackId = overlayTrack.id,
            assetType = MediaType.VIDEO,
            snappingEnabled = false,
        ).document
        val overlayClips = movedToOverlay.tracks.first { it.id == overlayTrack.id }.clips
        assertEquals(1, overlayClips.size)
        assertEquals(500L, overlayClips.first().timelineStartMs)

        // Attempt to move video clip1 to audio track -> stays on video track
        val rejectedAudioMove = timelineEngine.moveClip(
            document = movedToOverlay,
            clipId = clip1!!.clipId,
            desiredStartMs = 300L,
            targetTrackId = audioTrack.id,
            assetType = MediaType.VIDEO,
            snappingEnabled = false,
        ).document
        assertTrue(rejectedAudioMove.tracks.first { it.id == audioTrack.id }.clips.isEmpty())
    }

    @Test
    fun `duplicateClip, rippleDeleteClip, removeClips, reorderTrack, and CommandHistory work end to end`() {
        val history = TimelineCommandHistory()
        val doc0 = projectEngine.createNewDocument(
            name = "History & Ripple",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val v1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 2_000L)
        val (doc1, clip1) = timelineEngine.insertAssetAsClip(doc0, v1)
        history.record("Insert v1", doc0, doc1)

        val (doc2, dupId) = timelineEngine.duplicateClip(doc1, clip1!!.clipId)
        assertNotNull(dupId)
        history.record("Duplicate", doc1, doc2)

        val (doc3, thirdId) = timelineEngine.duplicateClip(doc2, dupId!!)
        assertNotNull(thirdId)
        history.record("Duplicate 2", doc2, doc3)

        val videoClipsBefore = doc3.tracks.first { it.type == TrackType.VIDEO }.clips
        assertEquals(listOf(0L, 2_000L, 4_000L), videoClipsBefore.map { it.timelineStartMs })

        // Ripple delete middle clip (2000..4000) -> third clip shifts left to 2000ms
        val doc4 = timelineEngine.rippleDeleteClip(doc3, dupId)
        history.record("Ripple Delete", doc3, doc4)
        val videoClipsAfterRipple = doc4.tracks.first { it.type == TrackType.VIDEO }.clips
        assertEquals(2, videoClipsAfterRipple.size)
        assertEquals(listOf(0L, 2_000L), videoClipsAfterRipple.map { it.timelineStartMs })

        // Undo ripple delete restores 3 clips
        val undoRev = history.undo()
        assertNotNull(undoRev)
        assertEquals(3, undoRev!!.before.tracks.first { it.type == TrackType.VIDEO }.clips.size)
        assertTrue(history.canRedo)

        // Redo restores 2 clips
        val redoRev = history.redo()
        assertNotNull(redoRev)
        assertEquals(2, redoRev!!.after.tracks.first { it.type == TrackType.VIDEO }.clips.size)

        // Reorder track
        val firstTrackId = doc4.tracks.sortedBy { it.orderIndex }.first().id
        val reordered = timelineEngine.reorderTrack(doc4, firstTrackId, +1)
        assertEquals(firstTrackId, reordered.tracks.sortedBy { it.orderIndex }[1].id)

        // Multi-clip removeClips
        val allIds = reordered.tracks.flatMap { it.clips }.map { it.clipId }.toSet()
        val cleared = timelineEngine.removeClips(reordered, allIds)
        assertEquals(0L, timelineEngine.calculateTimelineDurationMs(cleared))
    }

    @Test
    fun `phase 4 text overlay, speed adjustment, audio fade envelope, and transitions evaluate accurately`() {
        val doc0 = projectEngine.createNewDocument(
            name = "Phase 4 Quick Edit",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.QUICK,
        )
        val v1 = sampleAsset("v1", MediaType.VIDEO, durationMs = 2_000L)
        val v2 = sampleAsset("v2", MediaType.VIDEO, durationMs = 2_000L)
        val (doc1, clip1) = timelineEngine.insertAssetAsClip(doc0, v1)
        val (doc2, clip2) = timelineEngine.insertAssetAsClip(doc1, v2)

        // Add transitionIn on clip2 (starts at 2000ms)
        val doc3 = timelineEngine.updateClipTransition(
            doc2,
            clip2!!.clipId,
            ClipTransition(type = TransitionType.CROSS_DISSOLVE, durationMs = 600L, easing = EasingPreset.LINEAR),
        )
        val frameMidTransition = timelineEngine.evaluateFrame(doc3, 2_300L)
        assertNotNull(frameMidTransition.outgoingVisualLayer)
        assertEquals(clip1!!.clipId, frameMidTransition.outgoingVisualLayer?.clip?.clipId)
        assertEquals(0.5f, frameMidTransition.primaryVisualLayer!!.transitionProgress, 0.01f)

        // Speed up clip1 to 2.0x -> duration becomes 1000ms; then slow down to 0.5x -> shifts clip2 cleanly without overlap
        val docFast = timelineEngine.updateClipSpeed(doc3, clip1.clipId, 2.0f)
        val fast1 = docFast.tracks.flatMap { it.clips }.first { it.clipId == clip1.clipId }
        assertEquals(1_000L, fast1.durationMs)
        assertEquals(2.0f, fast1.speed, 0.01f)

        val docSlow = timelineEngine.updateClipSpeed(docFast, clip1.clipId, 0.5f)
        val videoClipsAfterSlow = docSlow.tracks.first { it.type == TrackType.VIDEO }.clips.sortedBy { it.timelineStartMs }
        assertEquals(4_000L, videoClipsAfterSlow[0].durationMs)
        assertEquals(4_000L, videoClipsAfterSlow[1].timelineStartMs)

        // Audio fade-in & fade-out evaluation
        val a1 = sampleAsset("a1", MediaType.AUDIO, durationMs = 4_000L)
        val (docWithAudio, audioClip) = timelineEngine.insertAssetAsClip(docSlow, a1)
        val docFaded = timelineEngine.updateClipAudio(
            document = docWithAudio,
            clipId = audioClip!!.clipId,
            volume = 1.0f,
            isMuted = false,
            fadeInMs = 1_000L,
            fadeOutMs = 1_000L,
        )
        assertEquals(0.5f, timelineEngine.evaluateFrame(docFaded, 500L).audioLayers.first().effectiveVolume, 0.02f)
        assertEquals(1.0f, timelineEngine.evaluateFrame(docFaded, 2_000L).audioLayers.first().effectiveVolume, 0.02f)
        assertEquals(0.5f, timelineEngine.evaluateFrame(docFaded, 3_500L).audioLayers.first().effectiveVolume, 0.02f)

        // Text clip evaluates simultaneously with underlying video layer
        val (docWithText, textClip) = timelineEngine.insertTextClip(
            document = docFaded,
            spec = TextOverlaySpec(text = "DROP", animationPreset = TextAnimationPreset.POP_OVERSHOOT),
            atTimelineMs = 500L,
            durationMs = 1_500L,
        )
        assertNotNull(textClip)
        val docFiltered = timelineEngine.updateClipAdjustments(
            docWithText,
            clip1.clipId,
            ClipAdjustments(filterPreset = FilterPreset.CINEMATIC, contrast = 1.2f),
        )
        val frameAt1000 = timelineEngine.evaluateFrame(docFiltered, 1_000L)
        assertEquals(1, frameAt1000.visualLayers.size)
        assertEquals(1, frameAt1000.textLayers.size)
        assertEquals("DROP", frameAt1000.textLayers.first().clip.textSpec?.text)
        assertEquals(FilterPreset.CINEMATIC, frameAt1000.primaryVisualLayer?.clip?.adjustments?.filterPreset)
    }
}
