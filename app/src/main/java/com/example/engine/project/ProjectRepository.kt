package com.example.engine.project

import android.net.Uri
import com.example.core.database.MediaAssetDao
import com.example.core.database.MediaAssetEntity
import com.example.core.database.ProjectDao
import com.example.core.database.ProjectEntity
import com.example.core.media.MediaImportResult
import com.example.core.media.MediaMetadataExtractor
import com.example.core.storage.MediaStorageManager
import com.example.engine.timeline.TimelineEngine
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class BatchImportReport(
    val importedAssets: List<MediaAsset>,
    val failures: List<MediaImportResult.Failure>,
)

class ProjectRepository(
    private val projectDao: ProjectDao,
    private val mediaAssetDao: MediaAssetDao,
    private val projectEngine: ProjectEngine,
    private val storageManager: MediaStorageManager,
    private val metadataExtractor: MediaMetadataExtractor,
    private val timelineEngine: TimelineEngine = TimelineEngine(),
) {
    fun observeProjectSummaries(): Flow<List<ProjectSummary>> {
        return combine(
            projectDao.observeAllProjects(),
            mediaAssetDao.observeAllAssets(),
        ) { projectEntities, assetEntities ->
            val assetsByProject = assetEntities.groupBy { it.projectId }
            projectEntities.map { entity ->
                val doc = decodeDocument(entity)
                val projectAssets = assetsByProject[entity.id].orEmpty().map { it.toDomain() }
                val cover = projectAssets.firstOrNull {
                    it.type != MediaType.AUDIO && it.status == MediaStatus.READY
                }?.playbackUri ?: entity.coverMediaUri
                ProjectSummary(
                    document = doc,
                    mediaCount = projectAssets.size,
                    videoCount = projectAssets.count { it.type == MediaType.VIDEO },
                    imageCount = projectAssets.count { it.type == MediaType.IMAGE },
                    audioCount = projectAssets.count { it.type == MediaType.AUDIO },
                    totalMediaDurationMs = projectAssets.sumOf { it.durationMs },
                    coverMediaUri = cover,
                )
            }
        }
    }

    fun observeProject(projectId: String): Flow<ProjectDocument?> {
        return projectDao.observeProjectById(projectId).map { entity ->
            entity?.let(::decodeDocument)
        }
    }

    fun observeProjectAssets(projectId: String): Flow<List<MediaAsset>> {
        return mediaAssetDao.observeAssetsForProject(projectId).map { list ->
            list.map { it.toDomain() }
        }
    }

    fun observeAllMediaAssets(): Flow<List<MediaAsset>> {
        return mediaAssetDao.observeAllAssets().map { list ->
            list.map { it.toDomain() }
        }
    }

    suspend fun createProject(
        name: String,
        aspectRatio: AspectRatioPreset,
        fps: Int,
        editorMode: EditorMode,
    ): ProjectDocument {
        val doc = projectEngine.createNewDocument(
            name = name,
            aspectRatio = aspectRatio,
            fps = fps,
            editorMode = editorMode,
        )
        projectDao.upsertProject(doc.toEntity(coverMediaUri = null))
        return doc
    }

    suspend fun switchEditorMode(projectId: String, targetMode: EditorMode) {
        val entity = projectDao.getProjectById(projectId) ?: return
        val currentDoc = decodeDocument(entity)
        val updated = projectEngine.switchMode(currentDoc, targetMode)
        projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
    }

    suspend fun renameProject(projectId: String, newName: String) {
        val entity = projectDao.getProjectById(projectId) ?: return
        val currentDoc = decodeDocument(entity)
        val updated = projectEngine.rename(currentDoc, newName)
        projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
    }

    suspend fun updateProjectCanvas(
        projectId: String,
        aspectRatio: AspectRatioPreset,
        fps: Int,
    ) {
        val entity = projectDao.getProjectById(projectId) ?: return
        val currentDoc = decodeDocument(entity)
        val updated = projectEngine.updateCanvas(currentDoc, aspectRatio, fps)
        projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
    }

    suspend fun updateProjectExportSettings(
        projectId: String,
        settings: ExportSettingsSpec,
    ) {
        val entity = projectDao.getProjectById(projectId) ?: return
        val currentDoc = decodeDocument(entity)
        val updated = currentDoc.copy(
            exportSettings = settings,
            updatedAt = System.currentTimeMillis(),
        )
        projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
    }

    suspend fun duplicateProject(projectId: String): ProjectDocument? {
        val entity = projectDao.getProjectById(projectId) ?: return null
        val sourceDoc = decodeDocument(entity)
        val newDoc = projectEngine.duplicateDocument(
            source = sourceDoc,
            newName = "${sourceDoc.name} Copy",
        )
        val sourceAssets = mediaAssetDao.getAssetsForProject(projectId)
        val assetIdMap = mutableMapOf<String, String>()
        val copiedAssetEntities = sourceAssets.map { asset ->
            val newAssetId = UUID.randomUUID().toString()
            assetIdMap[asset.id] = newAssetId
            val copiedPath = asset.managedFilePath?.let { path ->
                storageManager.copyFileForDuplicateProject(
                    sourcePath = path,
                    targetProjectId = newDoc.id,
                    newAssetId = newAssetId,
                )
            }
            asset.copy(
                id = newAssetId,
                projectId = newDoc.id,
                managedFilePath = copiedPath,
                proxyFilePath = null,
                statusName = if (copiedPath != null || asset.managedFilePath == null) {
                    asset.statusName
                } else {
                    MediaStatus.MISSING.name
                },
                importedAt = System.currentTimeMillis(),
            )
        }

        val remappedTracks = newDoc.tracks.map { track ->
            track.copy(
                clips = track.clips.map { clip ->
                    clip.copy(
                        clipId = UUID.randomUUID().toString(),
                        assetId = assetIdMap[clip.assetId] ?: clip.assetId,
                    )
                },
            )
        }
        val finalDoc = newDoc.copy(tracks = remappedTracks)
        projectDao.upsertProject(finalDoc.toEntity(coverMediaUri = entity.coverMediaUri))
        if (copiedAssetEntities.isNotEmpty()) {
            mediaAssetDao.insertAssets(copiedAssetEntities)
        }
        return finalDoc
    }

    suspend fun deleteProject(projectId: String) {
        val proxyPaths = mediaAssetDao.getAssetsForProject(projectId).mapNotNull { it.proxyFilePath }
        projectDao.deleteProjectById(projectId)
        storageManager.deleteProjectDirectory(projectId)
        storageManager.deleteFiles(proxyPaths)
    }

    suspend fun importMediaToProject(
        projectId: String?,
        uris: List<Uri>,
        hintType: MediaType?,
        appendToTimeline: Boolean = true,
    ): BatchImportReport {
        val imported = mutableListOf<MediaAsset>()
        val failures = mutableListOf<MediaImportResult.Failure>()

        for (uri in uris) {
            when (val result = metadataExtractor.importAndInspect(uri, projectId, hintType)) {
                is MediaImportResult.Success -> {
                    val asset = result.asset
                    mediaAssetDao.insertAsset(asset.toEntity())
                    imported += asset
                }
                is MediaImportResult.Failure -> {
                    failures += result
                }
            }
        }

        if (projectId != null && imported.isNotEmpty()) {
            val entity = projectDao.getProjectById(projectId)
            if (entity != null) {
                var doc = decodeDocument(entity)
                if (appendToTimeline) {
                    imported.forEach { asset ->
                        doc = projectEngine.appendAssetToFoundationTrack(doc, asset)
                    }
                } else {
                    doc = doc.copy(updatedAt = System.currentTimeMillis())
                }
                val newCover = entity.coverMediaUri
                    ?: imported.firstOrNull { it.type != MediaType.AUDIO }?.playbackUri
                projectDao.upsertProject(doc.toEntity(coverMediaUri = newCover))
            }
        }

        return BatchImportReport(importedAssets = imported, failures = failures)
    }

    suspend fun deleteMediaAsset(assetId: String) {
        val entity = mediaAssetDao.getAssetById(assetId) ?: return
        entity.projectId?.let { pid ->
            val projectEntity = projectDao.getProjectById(pid)
            if (projectEntity != null) {
                val updatedDoc = projectEngine.removeAssetReferences(
                    decodeDocument(projectEntity),
                    assetId,
                )
                val remainingAssets = mediaAssetDao.getAssetsForProject(pid)
                    .filterNot { it.id == assetId }
                val updatedCover = remainingAssets.firstOrNull {
                    it.mediaTypeName != MediaType.AUDIO.name
                }?.let { remaining ->
                    remaining.managedFilePath?.let { "file://$it" } ?: remaining.sourceUri
                }
                projectDao.upsertProject(updatedDoc.toEntity(coverMediaUri = updatedCover))
            }
        }
        mediaAssetDao.deleteAssetById(assetId)
        storageManager.deleteFile(entity.managedFilePath)
        storageManager.deleteFile(entity.proxyFilePath)
    }

    suspend fun updateAssetProxyPath(assetId: String, proxyFilePath: String?) {
        mediaAssetDao.updateProxyFilePath(assetId, proxyFilePath)
    }

    suspend fun verifyMediaIntegrity() {
        val paths = mediaAssetDao.getAllManagedFilePaths().toSet()
        val proxyPaths = mediaAssetDao.getAllProxyFilePaths().toSet()
        storageManager.pruneOrphanedFiles(paths, proxyPaths)
    }

    suspend fun getManagedStorageFootprintBytes(): Long =
        storageManager.calculateManagedStorageBytes()

    suspend fun pruneOrphanedMedia(): Int {
        val paths = mediaAssetDao.getAllManagedFilePaths().toSet()
        val proxyPaths = mediaAssetDao.getAllProxyFilePaths().toSet()
        return storageManager.pruneOrphanedFiles(paths, proxyPaths)
    }

    suspend fun addTrack(projectId: String, type: TrackType): TimelineTrack? {
        val entity = projectDao.getProjectById(projectId) ?: return null
        val (updated, createdTrack) = timelineEngine.addTrack(decodeDocument(entity), type)
        projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
        return createdTrack
    }

    suspend fun toggleTrackMute(projectId: String, trackId: String) {
        val entity = projectDao.getProjectById(projectId) ?: return
        val updated = timelineEngine.toggleTrackMute(decodeDocument(entity), trackId)
        projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
    }

    suspend fun toggleTrackLock(projectId: String, trackId: String) {
        val entity = projectDao.getProjectById(projectId) ?: return
        val updated = timelineEngine.toggleTrackLock(decodeDocument(entity), trackId)
        projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
    }

    suspend fun removeTrack(projectId: String, trackId: String) {
        val entity = projectDao.getProjectById(projectId) ?: return
        val updated = timelineEngine.removeTrack(decodeDocument(entity), trackId)
        projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
    }

    suspend fun insertAssetClip(
        projectId: String,
        assetId: String,
        targetTrackId: String? = null,
        atTimelineMs: Long? = null,
    ): TimelineClipRef? {
        val entity = projectDao.getProjectById(projectId) ?: return null
        val assetEntity = mediaAssetDao.getAssetById(assetId) ?: return null
        val (updated, newClip) = timelineEngine.insertAssetAsClip(
            document = decodeDocument(entity),
            asset = assetEntity.toDomain(),
            targetTrackId = targetTrackId,
            atTimelineMs = atTimelineMs,
        )
        if (newClip != null) {
            projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
        }
        return newClip
    }

    suspend fun removeClip(projectId: String, clipId: String) {
        val entity = projectDao.getProjectById(projectId) ?: return
        val updated = timelineEngine.removeClip(decodeDocument(entity), clipId)
        projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
    }

    suspend fun reorderTrack(projectId: String, trackId: String, delta: Int) {
        val entity = projectDao.getProjectById(projectId) ?: return
        val updated = timelineEngine.reorderTrack(decodeDocument(entity), trackId, delta)
        projectDao.upsertProject(updated.toEntity(coverMediaUri = entity.coverMediaUri))
    }

    suspend fun saveDocument(document: ProjectDocument) {
        val existingCover = projectDao.getProjectById(document.id)?.coverMediaUri
        projectDao.upsertProject(document.toEntity(coverMediaUri = existingCover))
    }

    private fun decodeDocument(entity: ProjectEntity): ProjectDocument {
        return runCatching { projectEngine.deserialize(entity.projectJson) }
            .getOrElse {
                projectEngine.createNewDocument(
                    name = entity.name,
                    aspectRatio = runCatching { AspectRatioPreset.valueOf(entity.aspectRatioName) }
                        .getOrDefault(AspectRatioPreset.VERTICAL_9_16),
                    fps = entity.fps,
                    editorMode = runCatching { EditorMode.valueOf(entity.editorModeName) }
                        .getOrDefault(EditorMode.QUICK),
                ).copy(
                    id = entity.id,
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt,
                )
            }
    }

    private fun ProjectDocument.toEntity(coverMediaUri: String?): ProjectEntity = ProjectEntity(
        id = id,
        name = name,
        aspectRatioName = aspectRatio.name,
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
        fps = fps,
        editorModeName = editorMode.name,
        schemaVersion = schemaVersion,
        projectJson = projectEngine.serialize(this),
        coverMediaUri = coverMediaUri,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun MediaAssetEntity.toDomain(): MediaAsset {
        val fileHealthy = managedFilePath == null || storageManager.fileExists(managedFilePath)
        val resolvedStatus = if (!fileHealthy) {
            MediaStatus.MISSING
        } else {
            runCatching { MediaStatus.valueOf(statusName) }.getOrDefault(MediaStatus.READY)
        }
        val healthyProxy = proxyFilePath?.takeIf { storageManager.fileExists(it) }
        return MediaAsset(
            id = id,
            projectId = projectId,
            type = runCatching { MediaType.valueOf(mediaTypeName) }.getOrDefault(MediaType.VIDEO),
            displayName = displayName,
            sourceUri = sourceUri,
            managedFilePath = managedFilePath,
            mimeType = mimeType,
            durationMs = durationMs,
            width = width,
            height = height,
            fps = fps,
            bitrate = bitrate,
            fileSizeBytes = fileSizeBytes,
            status = resolvedStatus,
            importedAt = importedAt,
            proxyFilePath = healthyProxy,
        )
    }

    private fun MediaAsset.toEntity(): MediaAssetEntity = MediaAssetEntity(
        id = id,
        projectId = projectId,
        mediaTypeName = type.name,
        displayName = displayName,
        sourceUri = sourceUri,
        managedFilePath = managedFilePath,
        mimeType = mimeType,
        durationMs = durationMs,
        width = width,
        height = height,
        fps = fps,
        bitrate = bitrate,
        fileSizeBytes = fileSizeBytes,
        statusName = status.name,
        importedAt = importedAt,
        proxyFilePath = proxyFilePath,
    )
}
