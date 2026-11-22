package com.example.feature.project

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.database.CutLabPreferencesRepository
import com.example.engine.effects.EffectsEngine
import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.ClipAdjustments
import com.example.engine.project.ClipKind
import com.example.engine.project.ClipTransition
import com.example.engine.project.EditorMode
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectDocument
import com.example.engine.project.ProjectRepository
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TextStylePreset
import com.example.engine.project.TimelineClipRef
import com.example.engine.project.TimelineTrack
import com.example.engine.project.TrackType
import com.example.engine.timeline.ActiveTimelineFrame
import com.example.engine.timeline.TimelineCommandHistory
import com.example.engine.timeline.TimelineEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class InspectorTab(val label: String) {
    MEDIA("Media"),
    INSPECTOR("Inspector"),
    TOOLS("FX & Text"),
    CANVAS("Canvas"),
}

private data class EditorInteractionState(
    val playheadMs: Long = 0L,
    val isPlaying: Boolean = false,
    val selectedClipIds: Set<String> = emptySet(),
    val selectedTrackId: String? = null,
    val timelineZoom: Float = 1.0f,
    val snappingEnabled: Boolean = true,
    val activeSnapGuideMs: Long? = null,
    val inspectorTab: InspectorTab = InspectorTab.MEDIA,
    val mediaFilter: MediaType? = null,
    val isImporting: Boolean = false,
    val historyVersion: Int = 0,
)

data class ProjectWorkspaceUiState(
    val project: ProjectDocument? = null,
    val allAssets: List<MediaAsset> = emptyList(),
    val filteredAssets: List<MediaAsset> = emptyList(),
    val assetsById: Map<String, MediaAsset> = emptyMap(),
    val playheadMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val activeFrame: ActiveTimelineFrame = ActiveTimelineFrame(0L, emptyList(), emptyList()),
    val selectedClipIds: Set<String> = emptySet(),
    val selectedClip: TimelineClipRef? = null,
    val selectedClipAsset: MediaAsset? = null,
    val selectedTrack: TimelineTrack? = null,
    val timelineZoom: Float = 1.0f,
    val snappingEnabled: Boolean = true,
    val activeSnapGuideMs: Long? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoLabel: String? = null,
    val redoLabel: String? = null,
    val canSplitAtPlayhead: Boolean = false,
    val inspectorTab: InspectorTab = InspectorTab.MEDIA,
    val mediaFilter: MediaType? = null,
    val isImporting: Boolean = false,
    val isLoading: Boolean = true,
)

class ProjectWorkspaceViewModel(
    private val projectId: String,
    private val projectRepository: ProjectRepository,
    private val preferencesRepository: CutLabPreferencesRepository,
    private val timelineEngine: TimelineEngine = TimelineEngine(),
) : ViewModel() {

    private val playheadMs = MutableStateFlow(0L)
    private val isPlaying = MutableStateFlow(false)
    private val selectedClipIds = MutableStateFlow<Set<String>>(emptySet())
    private val selectedTrackId = MutableStateFlow<String?>(null)
    private val timelineZoom = MutableStateFlow(1.0f)
    private val snappingEnabled = MutableStateFlow(true)
    private val activeSnapGuideMs = MutableStateFlow<Long?>(null)
    private val inspectorTab = MutableStateFlow(InspectorTab.MEDIA)
    private val mediaFilter = MutableStateFlow<MediaType?>(null)
    private val isImporting = MutableStateFlow(false)

    private val draftDocument = MutableStateFlow<ProjectDocument?>(null)
    private var gestureStartDocument: ProjectDocument? = null
    private val commandHistory = TimelineCommandHistory()
    private val historyVersion = MutableStateFlow(0)

    private var playbackJob: Job? = null

    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()

    private val interactionBase = combine(
        playheadMs,
        isPlaying,
        selectedClipIds,
        selectedTrackId,
    ) { pos, playing, clipIds, trackId ->
        EditorInteractionState(
            playheadMs = pos,
            isPlaying = playing,
            selectedClipIds = clipIds,
            selectedTrackId = trackId,
        )
    }

    private val interactionControls = combine(
        timelineZoom,
        snappingEnabled,
        activeSnapGuideMs,
        inspectorTab,
        combine(mediaFilter, isImporting, historyVersion) { f, imp, h -> Triple(f, imp, h) },
    ) { zoom, snap, guide, tab, extra ->
        listOf(zoom, snap, guide, tab, extra)
    }

    @Suppress("UNCHECKED_CAST")
    private val interactionState = combine(interactionBase, interactionControls) { base, ctrl ->
        val extra = ctrl[4] as Triple<MediaType?, Boolean, Int>
        base.copy(
            timelineZoom = ctrl[0] as Float,
            snappingEnabled = ctrl[1] as Boolean,
            activeSnapGuideMs = ctrl[2] as Long?,
            inspectorTab = ctrl[3] as InspectorTab,
            mediaFilter = extra.first,
            isImporting = extra.second,
            historyVersion = extra.third,
        )
    }

    val uiState: StateFlow<ProjectWorkspaceUiState> = combine(
        projectRepository.observeProject(projectId),
        projectRepository.observeProjectAssets(projectId),
        draftDocument,
        interactionState,
    ) { persistedDoc, assets, draft, interaction ->
        val doc = draft ?: persistedDoc
        val assetsById = assets.associateBy { it.id }
        val filteredAssets = if (interaction.mediaFilter == null) {
            assets
        } else {
            assets.filter { it.type == interaction.mediaFilter }
        }
        val totalDuration = doc?.let { timelineEngine.calculateTimelineDurationMs(it) } ?: 0L
        val clampedPlayhead = interaction.playheadMs.coerceIn(0L, totalDuration.coerceAtLeast(0L))
        val activeFrame = doc?.let { timelineEngine.evaluateFrame(it, clampedPlayhead) }
            ?: ActiveTimelineFrame(clampedPlayhead, emptyList(), emptyList())

        val allClips = doc?.tracks?.flatMap { it.clips }.orEmpty()
        val validSelectedIds = interaction.selectedClipIds.filter { id ->
            allClips.any { it.clipId == id }
        }.toSet()
        val primarySelectedClip = if (validSelectedIds.size == 1) {
            allClips.firstOrNull { it.clipId == validSelectedIds.first() }
        } else {
            validSelectedIds.firstOrNull()?.let { id -> allClips.firstOrNull { it.clipId == id } }
        }
        val selectedClipAsset = primarySelectedClip?.let { assetsById[it.assetId] }
        val selectedTrack = doc?.tracks?.firstOrNull { it.id == interaction.selectedTrackId }
            ?: primarySelectedClip?.let { clip -> doc?.tracks?.firstOrNull { it.id == clip.trackId } }

        val canSplit = doc != null && findSplittableClip(doc, clampedPlayhead, validSelectedIds) != null

        ProjectWorkspaceUiState(
            project = doc,
            allAssets = assets,
            filteredAssets = filteredAssets,
            assetsById = assetsById,
            playheadMs = clampedPlayhead,
            totalDurationMs = totalDuration,
            isPlaying = interaction.isPlaying,
            activeFrame = activeFrame,
            selectedClipIds = validSelectedIds,
            selectedClip = primarySelectedClip,
            selectedClipAsset = selectedClipAsset,
            selectedTrack = selectedTrack,
            timelineZoom = interaction.timelineZoom,
            snappingEnabled = interaction.snappingEnabled,
            activeSnapGuideMs = interaction.activeSnapGuideMs,
            canUndo = commandHistory.canUndo,
            canRedo = commandHistory.canRedo,
            undoLabel = commandHistory.undoLabel,
            redoLabel = commandHistory.redoLabel,
            canSplitAtPlayhead = canSplit,
            inspectorTab = interaction.inspectorTab,
            mediaFilter = interaction.mediaFilter,
            isImporting = interaction.isImporting,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProjectWorkspaceUiState(),
    )

    fun togglePlayPause() {
        if (isPlaying.value) pausePlayback() else startPlayback()
    }

    fun startPlayback() {
        val total = uiState.value.totalDurationMs
        if (total <= 0L) {
            viewModelScope.launch { _messages.emit("Add media to the timeline to preview playback") }
            return
        }
        if (playheadMs.value >= total) {
            playheadMs.value = 0L
        }
        playbackJob?.cancel()
        isPlaying.value = true
        playbackJob = viewModelScope.launch {
            var lastTickNs = System.nanoTime()
            while (isActive && isPlaying.value) {
                delay(16L)
                val nowNs = System.nanoTime()
                val deltaMs = ((nowNs - lastTickNs) / 1_000_000L).coerceIn(1L, 100L)
                lastTickNs = nowNs

                val currentTotal = uiState.value.totalDurationMs
                val nextPos = playheadMs.value + deltaMs
                if (nextPos >= currentTotal) {
                    playheadMs.value = currentTotal
                    isPlaying.value = false
                } else {
                    playheadMs.value = nextPos
                }
            }
        }
    }

    fun pausePlayback() {
        isPlaying.value = false
        playbackJob?.cancel()
        playbackJob = null
    }

    fun seekTo(ms: Long) {
        val total = uiState.value.totalDurationMs.coerceAtLeast(0L)
        playheadMs.value = ms.coerceIn(0L, total)
    }

    fun stepFrame(deltaFrames: Int) {
        pausePlayback()
        val fps = uiState.value.project?.fps?.coerceAtLeast(1) ?: 30
        val frameMs = (1_000L / fps).coerceAtLeast(16L)
        seekTo(playheadMs.value + deltaFrames * frameMs)
    }

    fun jumpToStart() {
        pausePlayback()
        playheadMs.value = 0L
    }

    fun jumpToEnd() {
        pausePlayback()
        playheadMs.value = uiState.value.totalDurationMs
    }

    fun selectClip(clipId: String?, multiSelect: Boolean = false) {
        if (clipId == null) {
            selectedClipIds.value = emptySet()
            return
        }
        if (multiSelect) {
            val current = selectedClipIds.value
            selectedClipIds.value = if (clipId in current) current - clipId else current + clipId
        } else {
            selectedClipIds.value = setOf(clipId)
        }
        val ownerTrack = uiState.value.project?.tracks?.firstOrNull { track ->
            track.clips.any { it.clipId == clipId }
        }
        if (ownerTrack != null) {
            selectedTrackId.value = ownerTrack.id
        }
    }

    fun selectTrack(trackId: String?) {
        selectedTrackId.value = trackId
    }

    fun setTimelineZoom(zoom: Float) {
        timelineZoom.value = zoom.coerceIn(0.4f, 4.0f)
    }

    fun toggleSnapping() {
        snappingEnabled.value = !snappingEnabled.value
        val enabled = snappingEnabled.value
        viewModelScope.launch {
            _messages.emit(if (enabled) "Magnetic snapping ON" else "Magnetic snapping OFF")
        }
    }

    fun setInspectorTab(tab: InspectorTab) {
        inspectorTab.value = tab
    }

    fun toggleMediaFilter(type: MediaType?) {
        mediaFilter.value = if (mediaFilter.value == type) null else type
    }

    fun undo() {
        val rev = commandHistory.undo() ?: return
        historyVersion.value++
        viewModelScope.launch {
            projectRepository.saveDocument(rev.before)
            _messages.emit("Undid ${rev.label}")
        }
    }

    fun redo() {
        val rev = commandHistory.redo() ?: return
        historyVersion.value++
        viewModelScope.launch {
            projectRepository.saveDocument(rev.after)
            _messages.emit("Redid ${rev.label}")
        }
    }

    private fun commitDocumentEdit(
        label: String,
        before: ProjectDocument,
        after: ProjectDocument,
    ) {
        if (before == after) return
        commandHistory.record(label, before, after)
        historyVersion.value++
        viewModelScope.launch {
            projectRepository.saveDocument(after)
        }
    }

    fun switchEditorMode(mode: EditorMode) {
        viewModelScope.launch {
            projectRepository.switchEditorMode(projectId, mode)
        }
    }

    fun renameProject(newName: String) {
        viewModelScope.launch {
            projectRepository.renameProject(projectId, newName)
            _messages.emit("Project renamed")
        }
    }

    fun updateCanvasSpecs(aspectRatio: AspectRatioPreset, fps: Int) {
        viewModelScope.launch {
            projectRepository.updateProjectCanvas(projectId, aspectRatio, fps)
            _messages.emit("Canvas updated to ${aspectRatio.label} • ${fps} FPS")
        }
    }

    fun addTrack(type: TrackType) {
        val doc = uiState.value.project ?: return
        val (updated, created) = timelineEngine.addTrack(doc, type)
        val finalDoc = if (doc.editorMode == EditorMode.QUICK &&
            (type == TrackType.OVERLAY || type == TrackType.TEXT || type == TrackType.ADJUSTMENT)
        ) {
            updated.copy(editorMode = EditorMode.ADVANCED)
        } else {
            updated
        }
        selectedTrackId.value = created.id
        commitDocumentEdit("Add ${created.name}", doc, finalDoc)
        viewModelScope.launch { _messages.emit("Added ${created.name}") }
    }

    fun reorderTrack(trackId: String, delta: Int) {
        val doc = uiState.value.project ?: return
        val updated = timelineEngine.reorderTrack(doc, trackId, delta)
        commitDocumentEdit("Reorder Track", doc, updated)
    }

    fun toggleTrackMute(trackId: String) {
        val doc = uiState.value.project ?: return
        val updated = timelineEngine.toggleTrackMute(doc, trackId)
        commitDocumentEdit("Toggle Track Mute", doc, updated)
    }

    fun toggleTrackLock(trackId: String) {
        val doc = uiState.value.project ?: return
        val updated = timelineEngine.toggleTrackLock(doc, trackId)
        commitDocumentEdit("Toggle Track Lock", doc, updated)
    }

    fun removeTrack(trackId: String) {
        val doc = uiState.value.project ?: return
        val track = doc.tracks.firstOrNull { it.id == trackId } ?: return
        if (track.isLocked) {
            viewModelScope.launch { _messages.emit("Unlock ${track.name} before removing") }
            return
        }
        val updated = timelineEngine.removeTrack(doc, trackId)
        if (updated != doc) {
            if (selectedTrackId.value == trackId) selectedTrackId.value = null
            commitDocumentEdit("Remove ${track.name}", doc, updated)
            viewModelScope.launch { _messages.emit("Removed ${track.name}") }
        }
    }

    fun insertAssetToTimeline(assetId: String, atPlayhead: Boolean = false) {
        val doc = uiState.value.project ?: return
        val asset = uiState.value.assetsById[assetId] ?: return
        val startMs = if (atPlayhead) playheadMs.value else null
        val (updated, newClip) = timelineEngine.insertAssetAsClip(
            document = doc,
            asset = asset,
            targetTrackId = selectedTrackId.value,
            atTimelineMs = startMs,
        )
        if (newClip != null) {
            selectedClipIds.value = setOf(newClip.clipId)
            selectedTrackId.value = newClip.trackId
            playheadMs.value = newClip.timelineStartMs
            commitDocumentEdit("Insert Clip", doc, updated)
            viewModelScope.launch { _messages.emit("Added clip to timeline") }
        } else {
            viewModelScope.launch { _messages.emit("No unlocked compatible track available") }
        }
    }

    fun splitAtPlayhead() {
        pausePlayback()
        val doc = uiState.value.project ?: return
        val targetClip = findSplittableClip(doc, playheadMs.value, uiState.value.selectedClipIds)
        if (targetClip == null) {
            viewModelScope.launch { _messages.emit("Move playhead inside an unlocked clip to split") }
            return
        }
        val (updated, rightClipId) = timelineEngine.splitClipAt(doc, targetClip.clipId, playheadMs.value)
        if (rightClipId != null) {
            selectedClipIds.value = setOf(rightClipId)
            commitDocumentEdit("Split Clip", doc, updated)
            viewModelScope.launch { _messages.emit("Clip split at playhead") }
        }
    }

    fun duplicateSelectedClip() {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val ownerTrack = doc.tracks.firstOrNull { it.id == clip.trackId }
        if (ownerTrack?.isLocked == true) {
            viewModelScope.launch { _messages.emit("Track ${ownerTrack.name} is locked") }
            return
        }
        val (updated, duplicatedId) = timelineEngine.duplicateClip(doc, clip.clipId)
        if (duplicatedId != null) {
            selectedClipIds.value = setOf(duplicatedId)
            commitDocumentEdit("Duplicate Clip", doc, updated)
            viewModelScope.launch { _messages.emit("Clip duplicated") }
        }
    }

    fun removeSelectedClips() {
        val doc = uiState.value.project ?: return
        val ids = uiState.value.selectedClipIds
        if (ids.isEmpty()) return
        val updated = timelineEngine.removeClips(doc, ids)
        if (updated == doc) {
            viewModelScope.launch { _messages.emit("Selected clip track is locked") }
            return
        }
        selectedClipIds.value = emptySet()
        commitDocumentEdit(
            if (ids.size > 1) "Delete ${ids.size} Clips" else "Delete Clip",
            doc,
            updated,
        )
        viewModelScope.launch {
            _messages.emit(if (ids.size > 1) "Removed ${ids.size} clips" else "Clip deleted")
        }
    }

    fun rippleDeleteSelectedClip() {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val ownerTrack = doc.tracks.firstOrNull { it.id == clip.trackId }
        if (ownerTrack?.isLocked == true) {
            viewModelScope.launch { _messages.emit("Track ${ownerTrack.name} is locked") }
            return
        }
        val updated = timelineEngine.rippleDeleteClip(doc, clip.clipId)
        selectedClipIds.value = emptySet()
        commitDocumentEdit("Ripple Delete", doc, updated)
        viewModelScope.launch { _messages.emit("Ripple deleted clip") }
    }

    fun nudgeTrimLeft(deltaMs: Long) {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val res = timelineEngine.trimClipLeft(
            document = doc,
            clipId = clip.clipId,
            desiredStartMs = clip.timelineStartMs + deltaMs,
            snappingEnabled = false,
        )
        if (res.document != doc) {
            commitDocumentEdit("Trim In", doc, res.document)
        }
    }

    fun nudgeTrimRight(deltaMs: Long) {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val assetDuration = uiState.value.selectedClipAsset
            ?.takeIf { it.type != MediaType.IMAGE }
            ?.durationMs ?: 0L
        val res = timelineEngine.trimClipRight(
            document = doc,
            clipId = clip.clipId,
            desiredEndMs = clip.timelineStartMs + clip.durationMs + deltaMs,
            maxSourceDurationMs = assetDuration,
            snappingEnabled = false,
        )
        if (res.document != doc) {
            commitDocumentEdit("Trim Out", doc, res.document)
        }
    }

    fun beginClipGesture() {
        pausePlayback()
        val current = uiState.value.project ?: return
        gestureStartDocument = current
        draftDocument.value = current
    }

    fun previewTrimLeft(clipId: String, desiredStartMs: Long) {
        val base = draftDocument.value ?: return
        val res = timelineEngine.trimClipLeft(
            document = base,
            clipId = clipId,
            desiredStartMs = desiredStartMs,
            snappingEnabled = snappingEnabled.value,
            playheadMs = playheadMs.value,
        )
        draftDocument.value = res.document
        activeSnapGuideMs.value = res.snappedTargetMs
    }

    fun previewTrimRight(clipId: String, desiredEndMs: Long) {
        val base = draftDocument.value ?: return
        val clip = base.tracks.flatMap { it.clips }.firstOrNull { it.clipId == clipId } ?: return
        val assetDuration = uiState.value.assetsById[clip.assetId]
            ?.takeIf { it.type != MediaType.IMAGE }
            ?.durationMs ?: 0L
        val res = timelineEngine.trimClipRight(
            document = base,
            clipId = clipId,
            desiredEndMs = desiredEndMs,
            maxSourceDurationMs = assetDuration,
            snappingEnabled = snappingEnabled.value,
            playheadMs = playheadMs.value,
        )
        draftDocument.value = res.document
        activeSnapGuideMs.value = res.snappedTargetMs
    }

    fun previewMoveClip(clipId: String, desiredStartMs: Long, targetTrackId: String?) {
        val base = draftDocument.value ?: return
        val clip = base.tracks.flatMap { it.clips }.firstOrNull { it.clipId == clipId } ?: return
        val assetType = uiState.value.assetsById[clip.assetId]?.type
        val res = timelineEngine.moveClip(
            document = base,
            clipId = clipId,
            desiredStartMs = desiredStartMs,
            targetTrackId = targetTrackId,
            assetType = assetType,
            snappingEnabled = snappingEnabled.value,
            playheadMs = playheadMs.value,
        )
        draftDocument.value = res.document
        activeSnapGuideMs.value = res.snappedTargetMs
    }

    fun commitClipGesture(label: String) {
        val before = gestureStartDocument
        val after = draftDocument.value
        gestureStartDocument = null
        draftDocument.value = null
        activeSnapGuideMs.value = null
        if (before != null && after != null && before != after) {
            commitDocumentEdit(label, before, after)
        }
    }

    fun cancelClipGesture() {
        gestureStartDocument = null
        draftDocument.value = null
        activeSnapGuideMs.value = null
    }

    // --- Phase 4: Quick Edit Engine Operations ---

    fun addTextClip(
        preset: TextStylePreset = TextStylePreset.BOLD_TITLE,
        atPlayhead: Boolean = true,
    ) {
        val doc = uiState.value.project ?: return
        val spec = EffectsEngine.defaultTextSpec(preset)
        val startMs = if (atPlayhead) playheadMs.value else null
        val (updated, created) = timelineEngine.insertTextClip(
            document = doc,
            spec = spec,
            targetTrackId = selectedTrackId.value.takeIf { id ->
                doc.tracks.firstOrNull { it.id == id }?.type == TrackType.TEXT
            },
            atTimelineMs = startMs,
        )
        if (created != null) {
            selectedClipIds.value = setOf(created.clipId)
            selectedTrackId.value = created.trackId
            playheadMs.value = created.timelineStartMs
            commitDocumentEdit("Add Text", doc, updated)
            viewModelScope.launch { _messages.emit("Added ${preset.label} text clip") }
        } else {
            viewModelScope.launch { _messages.emit("Unlock a Text track to add captions") }
        }
    }

    fun updateSelectedTextSpec(spec: TextOverlaySpec) {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind != ClipKind.TEXT) return
        val updated = timelineEngine.updateClipText(doc, clip.clipId, spec)
        commitDocumentEdit("Edit Text", doc, updated)
    }

    fun previewSelectedTextSpec(spec: TextOverlaySpec) {
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind != ClipKind.TEXT) return
        val base = ensureGestureBaseDocument() ?: return
        draftDocument.value = timelineEngine.updateClipText(base, clip.clipId, spec)
    }

    fun updateSelectedClipSpeed(newSpeed: Float) {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val isImage = uiState.value.selectedClipAsset?.type == MediaType.IMAGE
        if (clip.clipKind == ClipKind.TEXT || isImage) {
            viewModelScope.launch { _messages.emit("Speed applies to video and audio clips") }
            return
        }
        val updated = timelineEngine.updateClipSpeed(doc, clip.clipId, newSpeed, isImageClip = false)
        commitDocumentEdit("Change Speed (${String.format("%.2gx", newSpeed)})", doc, updated)
    }

    fun updateSelectedClipAudio(
        volume: Float,
        isMuted: Boolean,
        fadeInMs: Long,
        fadeOutMs: Long,
    ) {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val updated = timelineEngine.updateClipAudio(doc, clip.clipId, volume, isMuted, fadeInMs, fadeOutMs)
        commitDocumentEdit("Audio Settings", doc, updated)
    }

    fun previewSelectedClipAudio(
        volume: Float,
        isMuted: Boolean,
        fadeInMs: Long,
        fadeOutMs: Long,
    ) {
        val clip = uiState.value.selectedClip ?: return
        val base = ensureGestureBaseDocument() ?: return
        draftDocument.value = timelineEngine.updateClipAudio(
            base, clip.clipId, volume, isMuted, fadeInMs, fadeOutMs,
        )
    }

    fun updateSelectedClipTransition(transition: ClipTransition?) {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val updated = timelineEngine.updateClipTransition(doc, clip.clipId, transition)
        commitDocumentEdit("Transition", doc, updated)
    }

    fun previewSelectedClipTransition(transition: ClipTransition?) {
        val clip = uiState.value.selectedClip ?: return
        val base = ensureGestureBaseDocument() ?: return
        draftDocument.value = timelineEngine.updateClipTransition(base, clip.clipId, transition)
    }

    fun updateSelectedClipAdjustments(adjustments: ClipAdjustments) {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind != ClipKind.MEDIA) return
        val updated = timelineEngine.updateClipAdjustments(doc, clip.clipId, adjustments)
        commitDocumentEdit("Color & Filter", doc, updated)
    }

    fun previewSelectedClipAdjustments(adjustments: ClipAdjustments) {
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind != ClipKind.MEDIA) return
        val base = ensureGestureBaseDocument() ?: return
        draftDocument.value = timelineEngine.updateClipAdjustments(base, clip.clipId, adjustments)
    }

    fun applySelectedAdjustmentsToAll() {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind != ClipKind.MEDIA) return
        val updated = timelineEngine.applyAdjustmentsToAllVisualClips(doc, clip.adjustments)
        commitDocumentEdit("Apply Filter to All", doc, updated)
        viewModelScope.launch { _messages.emit("Applied filter to all visual clips") }
    }

    private fun ensureGestureBaseDocument(): ProjectDocument? {
        val current = draftDocument.value ?: uiState.value.project ?: return null
        if (gestureStartDocument == null) {
            gestureStartDocument = current
        }
        return current
    }

    fun importMedia(uris: List<Uri>, hintType: MediaType?) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            isImporting.value = true
            try {
                val before = uiState.value.project
                val prefs = preferencesRepository.preferencesFlow.first()
                val report = projectRepository.importMediaToProject(
                    projectId = projectId,
                    uris = uris,
                    hintType = hintType,
                    appendToTimeline = prefs.autoAppendImportedToTimeline,
                )
                if (before != null && prefs.autoAppendImportedToTimeline && report.importedAssets.isNotEmpty()) {
                    val after = projectRepository.observeProject(projectId).first()
                    if (after != null && after != before) {
                        commandHistory.record("Import Media", before, after)
                        historyVersion.value++
                    }
                }
                val message = buildString {
                    if (report.importedAssets.isNotEmpty()) {
                        append("Imported ${report.importedAssets.size} asset(s)")
                    }
                    if (report.failures.isNotEmpty()) {
                        if (isNotEmpty()) append(" • ")
                        append("${report.failures.size} unsupported/failed: ${report.failures.first().reason}")
                    }
                }
                if (message.isNotBlank()) {
                    _messages.emit(message)
                }
            } finally {
                isImporting.value = false
            }
        }
    }

    fun deleteAsset(assetId: String) {
        viewModelScope.launch {
            projectRepository.deleteMediaAsset(assetId)
            _messages.emit("Asset deleted from project")
        }
    }

    private fun findSplittableClip(
        document: ProjectDocument,
        atMs: Long,
        selectedIds: Set<String>,
    ): TimelineClipRef? {
        val unlockedClips = document.tracks
            .filterNot { it.isLocked }
            .sortedBy { it.orderIndex }
            .flatMap { it.clips }

        val validAtPlayhead = unlockedClips.filter { clip ->
            atMs >= clip.timelineStartMs + 100L &&
                atMs <= (clip.timelineStartMs + clip.durationMs - 100L)
        }
        return validAtPlayhead.firstOrNull { it.clipId in selectedIds }
            ?: validAtPlayhead.firstOrNull()
    }

    override fun onCleared() {
        playbackJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(
            projectId: String,
            projectRepository: ProjectRepository,
            preferencesRepository: CutLabPreferencesRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ProjectWorkspaceViewModel(
                    projectId = projectId,
                    projectRepository = projectRepository,
                    preferencesRepository = preferencesRepository,
                ) as T
            }
        }
    }
}
