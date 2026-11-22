package com.example.engine.project

import java.util.UUID
import kotlinx.serialization.json.Json

class ProjectEngine(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    fun createNewDocument(
        name: String,
        aspectRatio: AspectRatioPreset,
        fps: Int,
        editorMode: EditorMode,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        val projectId = UUID.randomUUID().toString()
        val sanitizedName = name.trim().ifEmpty { "Untitled Cut" }
        return ProjectDocument(
            schemaVersion = CURRENT_PROJECT_SCHEMA_VERSION,
            id = projectId,
            name = sanitizedName,
            aspectRatio = aspectRatio,
            canvasWidth = aspectRatio.width,
            canvasHeight = aspectRatio.height,
            fps = fps,
            editorMode = editorMode,
            tracks = defaultTracks(),
            exportSettings = ExportSettingsSpec(
                resolutionHeight = minOf(aspectRatio.width, aspectRatio.height).coerceAtLeast(720),
                fps = fps,
            ),
            createdAt = nowMs,
            updatedAt = nowMs,
        )
    }

    fun duplicateDocument(
        source: ProjectDocument,
        newName: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        val newId = UUID.randomUUID().toString()
        return source.copy(
            id = newId,
            name = newName,
            createdAt = nowMs,
            updatedAt = nowMs,
        )
    }

    fun switchMode(
        document: ProjectDocument,
        targetMode: EditorMode,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        if (document.editorMode == targetMode) return document
        return document.copy(
            editorMode = targetMode,
            updatedAt = nowMs,
        )
    }

    fun rename(
        document: ProjectDocument,
        newName: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        val sanitized = newName.trim().ifEmpty { document.name }
        return document.copy(name = sanitized, updatedAt = nowMs)
    }

    fun updateCanvas(
        document: ProjectDocument,
        aspectRatio: AspectRatioPreset,
        fps: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        return document.copy(
            aspectRatio = aspectRatio,
            canvasWidth = aspectRatio.width,
            canvasHeight = aspectRatio.height,
            fps = fps,
            exportSettings = document.exportSettings.copy(fps = fps),
            updatedAt = nowMs,
        )
    }

    fun appendAssetToFoundationTrack(
        document: ProjectDocument,
        asset: MediaAsset,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        val targetType = when (asset.type) {
            MediaType.VIDEO, MediaType.IMAGE -> TrackType.VIDEO
            MediaType.AUDIO -> TrackType.AUDIO
        }
        val targetTrack = document.tracks.firstOrNull { it.type == targetType } ?: return document
        val clipStartMs = targetTrack.clips.maxOfOrNull { it.timelineStartMs + it.durationMs } ?: 0L
        val defaultDuration = when (asset.type) {
            MediaType.IMAGE -> 3_000L
            MediaType.VIDEO, MediaType.AUDIO -> asset.durationMs.coerceAtLeast(1_000L)
        }
        val newClip = TimelineClipRef(
            clipId = UUID.randomUUID().toString(),
            assetId = asset.id,
            trackId = targetTrack.id,
            timelineStartMs = clipStartMs,
            sourceStartMs = 0L,
            durationMs = defaultDuration,
        )
        val updatedTracks = document.tracks.map { track ->
            if (track.id == targetTrack.id) {
                track.copy(clips = track.clips + newClip)
            } else {
                track
            }
        }
        return document.copy(
            tracks = updatedTracks,
            updatedAt = nowMs,
        )
    }

    fun removeAssetReferences(
        document: ProjectDocument,
        assetId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): ProjectDocument {
        val updatedTracks = document.tracks.map { track ->
            track.copy(clips = track.clips.filterNot { it.assetId == assetId })
        }
        return document.copy(
            tracks = updatedTracks,
            updatedAt = nowMs,
        )
    }

    fun serialize(document: ProjectDocument): String = json.encodeToString(document)

    fun deserialize(rawJson: String): ProjectDocument = json.decodeFromString(rawJson)

    private fun defaultTracks(): List<TimelineTrack> = listOf(
        TimelineTrack(
            id = "track_v1",
            name = "V1 Main",
            type = TrackType.VIDEO,
            orderIndex = 0,
        ),
        TimelineTrack(
            id = "track_ov1",
            name = "OV1 Overlay",
            type = TrackType.OVERLAY,
            orderIndex = 1,
        ),
        TimelineTrack(
            id = "track_t1",
            name = "T1 Titles",
            type = TrackType.TEXT,
            orderIndex = 2,
        ),
        TimelineTrack(
            id = "track_a1",
            name = "A1 Audio",
            type = TrackType.AUDIO,
            orderIndex = 3,
        ),
    )
}
