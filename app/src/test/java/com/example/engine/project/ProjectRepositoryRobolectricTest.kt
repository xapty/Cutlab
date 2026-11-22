package com.example.engine.project

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.CutLabDatabase
import com.example.core.media.MediaMetadataExtractor
import com.example.core.storage.MediaStorageManager
import com.example.engine.effects.EffectsEngine
import com.example.engine.timeline.TimelineEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class ProjectRepositoryRobolectricTest {

    private lateinit var context: Context
    private lateinit var database: CutLabDatabase
    private lateinit var repository: ProjectRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, CutLabDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val storage = MediaStorageManager(context)
        repository = ProjectRepository(
            projectDao = database.projectDao(),
            mediaAssetDao = database.mediaAssetDao(),
            projectEngine = ProjectEngine(),
            storageManager = storage,
            metadataExtractor = MediaMetadataExtractor(storage),
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `created project persists and switches modes without data loss`() = runTest {
        val created = repository.createProject(
            name = "AMV Cut 01",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 60,
            editorMode = EditorMode.QUICK,
        )

        val loaded = repository.observeProject(created.id).first()
        assertNotNull(loaded)
        assertEquals("AMV Cut 01", loaded?.name)
        assertEquals(EditorMode.QUICK, loaded?.editorMode)
        assertEquals(1080, loaded?.canvasWidth)
        assertEquals(1920, loaded?.canvasHeight)

        repository.switchEditorMode(created.id, EditorMode.ADVANCED)
        val afterSwitch = repository.observeProject(created.id).first()
        assertEquals(EditorMode.ADVANCED, afterSwitch?.editorMode)
        assertEquals(4, afterSwitch?.tracks?.size)

        val duplicate = repository.duplicateProject(created.id)
        assertNotNull(duplicate)
        val summaries = repository.observeProjectSummaries().first()
        assertEquals(2, summaries.size)

        repository.deleteProject(created.id)
        assertNull(repository.observeProject(created.id).first())
    }

    @Test
    fun `phase 3 timeline mutations split trim ripple delete and undo saveDocument persist across reopen`() = runTest {
        val created = repository.createProject(
            name = "Phase 3 Persistence",
            aspectRatio = AspectRatioPreset.LANDSCAPE_16_9,
            fps = 30,
            editorMode = EditorMode.ADVANCED,
        )
        val timelineEngine = TimelineEngine()
        val sampleAsset = MediaAsset(
            id = "asset-video-1",
            projectId = created.id,
            type = MediaType.VIDEO,
            displayName = "clip.mp4",
            sourceUri = "content://media/clip",
            managedFilePath = "/tmp/clip.mp4",
            mimeType = "video/mp4",
            durationMs = 6_000L,
            width = 1920,
            height = 1080,
            fps = 30f,
            bitrate = 2_000_000L,
            fileSizeBytes = 1_500_000L,
            status = MediaStatus.READY,
            importedAt = 1_000L,
        )

        val (docWithClip, inserted) = timelineEngine.insertAssetAsClip(created, sampleAsset)
        val clipId = inserted!!.clipId
        val (docSplit, rightId) = timelineEngine.splitClipAt(docWithClip, clipId, 2_000L)
        val docTrimmed = timelineEngine.trimClipRight(
            document = docSplit,
            clipId = rightId!!,
            desiredEndMs = 4_500L,
            maxSourceDurationMs = 6_000L,
        ).document
        repository.saveDocument(docTrimmed)

        val reopened = repository.observeProject(created.id).first()
        assertNotNull(reopened)
        val clips = reopened!!.tracks.first { it.type == TrackType.VIDEO }.clips
        assertEquals(2, clips.size)
        assertEquals(2_000L, clips[0].durationMs)
        assertEquals(2_000L, clips[1].timelineStartMs)
        assertEquals(2_500L, clips[1].durationMs)
        assertEquals(6_000L, clips[1].sourceTotalDurationMs)

        val docRippled = timelineEngine.rippleDeleteClip(reopened, clipId)
        repository.saveDocument(docRippled)

        val afterRipple = repository.observeProject(created.id).first()
        val remaining = afterRipple!!.tracks.first { it.type == TrackType.VIDEO }.clips
        assertEquals(1, remaining.size)
        assertEquals(0L, remaining.first().timelineStartMs)
        assertEquals(2_500L, remaining.first().durationMs)
    }

    @Test
    fun `phase 4 quick edit properties filters transitions speed audio fades and text survive save and mode switch`() = runTest {
        val created = repository.createProject(
            name = "Phase 4 Persistence",
            aspectRatio = AspectRatioPreset.VERTICAL_9_16,
            fps = 30,
            editorMode = EditorMode.QUICK,
        )
        val timelineEngine = TimelineEngine()
        val sampleAsset = MediaAsset(
            id = "asset-v1",
            projectId = created.id,
            type = MediaType.VIDEO,
            displayName = "clip.mp4",
            sourceUri = "content://media/clip",
            managedFilePath = "/tmp/clip.mp4",
            mimeType = "video/mp4",
            durationMs = 4_000L,
            width = 1080,
            height = 1920,
            fps = 30f,
            bitrate = 2_000_000L,
            fileSizeBytes = 1_000_000L,
            status = MediaStatus.READY,
            importedAt = 1_000L,
        )

        val (doc1, inserted) = timelineEngine.insertAssetAsClip(created, sampleAsset)
        val clipId = inserted!!.clipId
        val doc2 = timelineEngine.updateClipAdjustments(
            doc1,
            clipId,
            ClipAdjustments(filterPreset = FilterPreset.VIVID, saturation = 1.3f, vignette = 0.25f),
        )
        val doc3 = timelineEngine.updateClipTransition(
            doc2,
            clipId,
            ClipTransition(type = TransitionType.ZOOM_IN, durationMs = 600L),
        )
        val doc4 = timelineEngine.updateClipSpeed(doc3, clipId, 2.0f)
        val doc5 = timelineEngine.updateClipAudio(
            doc4,
            clipId,
            volume = 0.8f,
            isMuted = false,
            fadeInMs = 300L,
            fadeOutMs = 400L,
        )
        val (doc6, textClip) = timelineEngine.insertTextClip(
            doc5,
            EffectsEngine.defaultTextSpec(TextStylePreset.NEON_POP).copy(text = "KINETIC"),
        )
        repository.saveDocument(doc6)
        repository.switchEditorMode(created.id, EditorMode.ADVANCED)

        val reopened = repository.observeProject(created.id).first()!!
        assertEquals(EditorMode.ADVANCED, reopened.editorMode)
        val reopenedClip = reopened.tracks.first { it.type == TrackType.VIDEO }.clips.first()
        assertEquals(FilterPreset.VIVID, reopenedClip.adjustments.filterPreset)
        assertEquals(TransitionType.ZOOM_IN, reopenedClip.transitionIn?.type)
        assertEquals(2.0f, reopenedClip.speed, 0.01f)
        assertEquals(2_000L, reopenedClip.durationMs)
        assertEquals(0.8f, reopenedClip.volume, 0.01f)
        assertEquals(300L, reopenedClip.fadeInMs)
        assertEquals(400L, reopenedClip.fadeOutMs)

        val reopenedText = reopened.tracks.first { it.type == TrackType.TEXT }.clips.first { it.clipId == textClip!!.clipId }
        assertEquals("KINETIC", reopenedText.textSpec?.text)
        assertEquals(TextStylePreset.NEON_POP, reopenedText.textSpec?.stylePreset)
    }
}
