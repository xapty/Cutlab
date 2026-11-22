package com.example.feature.project

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.database.CutLabPreferencesRepository
import com.example.core.media.ProxyJobState
import com.example.core.media.ProxyMediaManager
import com.example.engine.animation.AnimationEngine
import com.example.engine.animation.MotionTrackingEngine
import com.example.engine.animation.TrackedMotionPoint
import com.example.engine.audio.DecodedPcmBuffer
import com.example.engine.audio.MasterAudioMixState
import com.example.engine.audio.MixedAudioPreviewPlayer
import com.example.engine.audio.MultiTrackAudioMixer
import com.example.engine.audio.PcmAudioExtractor
import com.example.engine.audio.WaveformEnvelope
import com.example.engine.effects.EffectsEngine
import com.example.engine.effects.Lut3DEngine
import com.example.engine.export.CutLabExportCoordinator
import com.example.engine.export.ExportJobState
import com.example.engine.export.ExportOutputManager
import com.example.engine.export.ExportPlan
import com.example.engine.export.ExportPlanner
import com.example.engine.export.ExportRenderPath
import com.example.engine.export.ExportRequest
import com.example.engine.export.ExportedArtifact
import com.example.engine.export.HardwareExportCapabilities
import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.BezierCurveSpec
import com.example.engine.project.BlendModePreset
import com.example.engine.project.ChromaKeySpec
import com.example.engine.project.ClipAdjustments
import com.example.engine.project.ClipAudioEffect
import com.example.engine.project.ClipKind
import com.example.engine.project.ClipLutSpec
import com.example.engine.project.ClipShaderEffect
import com.example.engine.project.ClipTransition
import com.example.engine.project.EditorMode
import com.example.engine.project.ExportSettingsSpec
import com.example.engine.project.KeyframeInterpolation
import com.example.engine.project.KeyframeProperty
import com.example.engine.project.LayerMask
import com.example.engine.project.LayerTransform
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import com.example.engine.project.MotionPreset
import com.example.engine.project.ProjectDocument
import com.example.engine.project.ProjectRepository
import com.example.engine.project.SpeedPoint
import com.example.engine.project.SpeedRampPreset
import com.example.engine.project.StabilizationSpec
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TextStylePreset
import com.example.engine.project.TimelineClipRef
import com.example.engine.project.TimelineTrack
import com.example.engine.project.TrackType
import com.example.engine.timeline.ActiveTimelineFrame
import com.example.engine.timeline.SpeedRampEngine
import com.example.engine.timeline.TimelineCommandHistory
import com.example.engine.timeline.TimelineEngine
import kotlinx.coroutines.Dispatchers
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
import java.util.Collections
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class InspectorTab(val label: String) {
    MEDIA("Media"),
    INSPECTOR("Inspector"),
    TOOLS("FX & Text"),
    COMPOSITOR("Compositor"),
    ANIMATION("Animate"),
    AUDIO("Audio"),
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
    val selectedKeyframeProperty: KeyframeProperty = KeyframeProperty.SCALE_X,
    val selectedKeyframeIds: Set<String> = emptySet(),
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
    val selectedKeyframeProperty: KeyframeProperty = KeyframeProperty.SCALE_X,
    val selectedKeyframeIds: Set<String> = emptySet(),
    val waveformsByAssetId: Map<String, WaveformEnvelope> = emptyMap(),
    val audioMixState: MasterAudioMixState = MasterAudioMixState(),
    val isAuditioningMix: Boolean = false,
    val preferredExportPath: ExportRenderPath = ExportRenderPath.AUTO,
    val hardwareCapabilities: HardwareExportCapabilities = HardwareExportCapabilities(),
    val exportPlan: ExportPlan? = null,
    val exportJobState: ExportJobState = ExportJobState.Idle,
    val projectExports: List<ExportedArtifact> = emptyList(),
    val preferProxyPlayback: Boolean = true,
    val proxyJobState: ProxyJobState = ProxyJobState.Idle,
    val isMotionAnalysisRunning: Boolean = false,
    val lastMotionTrackPoints: List<TrackedMotionPoint> = emptyList(),
    val isLoading: Boolean = true,
)

class ProjectWorkspaceViewModel(
    private val projectId: String,
    private val projectRepository: ProjectRepository,
    private val preferencesRepository: CutLabPreferencesRepository,
    private val animationEngine: AnimationEngine = AnimationEngine(),
    private val timelineEngine: TimelineEngine = TimelineEngine(animationEngine),
    private val pcmAudioExtractor: PcmAudioExtractor? = null,
    private val mixedAudioPreviewPlayer: MixedAudioPreviewPlayer? = null,
    private val audioMixer: MultiTrackAudioMixer = MultiTrackAudioMixer(animationEngine),
    private val exportPlanner: ExportPlanner = ExportPlanner(),
    private val exportOutputManager: ExportOutputManager? = null,
    private val exportCoordinator: CutLabExportCoordinator? = null,
    private val proxyMediaManager: ProxyMediaManager? = null,
    private val appContext: Context? = null,
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
    private val selectedKeyframeProperty = MutableStateFlow(KeyframeProperty.SCALE_X)
    private val selectedKeyframeIds = MutableStateFlow<Set<String>>(emptySet())
    private val waveformsByAssetId = MutableStateFlow<Map<String, WaveformEnvelope>>(emptyMap())
    private val isAuditioningMix = MutableStateFlow(false)
    private val extractingAssetIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val preferredExportPath = MutableStateFlow(ExportRenderPath.AUTO)
    private val projectExports = MutableStateFlow<List<ExportedArtifact>>(emptyList())
    private val fallbackExportJobState = MutableStateFlow<ExportJobState>(ExportJobState.Idle)
    private val fallbackProxyJobState = MutableStateFlow<ProxyJobState>(ProxyJobState.Idle)
    private val hardwareCapabilitiesState = MutableStateFlow(HardwareExportCapabilities())
    private val isMotionAnalysisRunning = MutableStateFlow(false)
    private val lastMotionTrackPoints = MutableStateFlow<List<TrackedMotionPoint>>(emptyList())

    private val draftDocument = MutableStateFlow<ProjectDocument?>(null)
    private var gestureStartDocument: ProjectDocument? = null
    private val commandHistory = TimelineCommandHistory()
    private val historyVersion = MutableStateFlow(0)

    private var playbackJob: Job? = null
    private var auditionJob: Job? = null

    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            hardwareCapabilitiesState.value = exportCoordinator?.hardwareCapabilities
                ?: exportPlanner.probeHardwareCapabilities()
        }
        refreshExportHistory()
        if (pcmAudioExtractor != null) {
            viewModelScope.launch {
                projectRepository.observeProjectAssets(projectId).collect { assets ->
                    assets.forEach { asset ->
                        if (asset.type != MediaType.IMAGE &&
                            asset.status == MediaStatus.READY &&
                            !waveformsByAssetId.value.containsKey(asset.id) &&
                            extractingAssetIds.add(asset.id)
                        ) {
                            launch {
                                try {
                                    val envelope = pcmAudioExtractor.extractWaveform(asset)
                                    if (envelope != null) {
                                        waveformsByAssetId.value = waveformsByAssetId.value + (asset.id to envelope)
                                    }
                                } finally {
                                    extractingAssetIds.remove(asset.id)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

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

    private val interactionExtras = combine(
        mediaFilter,
        isImporting,
        historyVersion,
        selectedKeyframeProperty,
        selectedKeyframeIds,
    ) { f, imp, h, kfProp, kfIds ->
        listOf(f, imp, h, kfProp, kfIds)
    }

    private val interactionControls = combine(
        timelineZoom,
        snappingEnabled,
        activeSnapGuideMs,
        inspectorTab,
        interactionExtras,
    ) { zoom, snap, guide, tab, extra ->
        listOf(zoom, snap, guide, tab, extra)
    }

    @Suppress("UNCHECKED_CAST")
    private val interactionState = combine(interactionBase, interactionControls) { base, ctrl ->
        val extra = ctrl[4] as List<Any?>
        base.copy(
            timelineZoom = ctrl[0] as Float,
            snappingEnabled = ctrl[1] as Boolean,
            activeSnapGuideMs = ctrl[2] as Long?,
            inspectorTab = ctrl[3] as InspectorTab,
            mediaFilter = extra[0] as MediaType?,
            isImporting = extra[1] as Boolean,
            historyVersion = extra[2] as Int,
            selectedKeyframeProperty = extra[3] as KeyframeProperty,
            selectedKeyframeIds = extra[4] as Set<String>,
        )
    }

    private val exportUiState = combine(
        preferredExportPath,
        hardwareCapabilitiesState,
        exportCoordinator?.jobState ?: fallbackExportJobState,
        projectExports,
    ) { path, caps, job, history ->
        listOf(path, caps, job, history)
    }

    private val phase9AuxState = combine(
        preferencesRepository.preferencesFlow,
        proxyMediaManager?.state ?: fallbackProxyJobState,
        isMotionAnalysisRunning,
        lastMotionTrackPoints,
    ) { prefs, proxyState, trackingBusy, trackPoints ->
        listOf(prefs.preferProxyPlayback, proxyState, trackingBusy, trackPoints)
    }

    @Suppress("UNCHECKED_CAST")
    private val audioAndExportState = combine(
        waveformsByAssetId,
        isAuditioningMix,
        exportUiState,
        phase9AuxState,
    ) { waveforms, auditioning, exportList, p9 ->
        listOf(waveforms, auditioning, exportList, p9)
    }

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<ProjectWorkspaceUiState> = combine(
        projectRepository.observeProject(projectId),
        projectRepository.observeProjectAssets(projectId),
        draftDocument,
        interactionState,
        audioAndExportState,
    ) { persistedDoc, assets, draft, interaction, extraList ->
        val wfMap = extraList[0] as Map<String, WaveformEnvelope>
        val auditioning = extraList[1] as Boolean
        val exportItems = extraList[2] as List<Any?>
        val p9Items = extraList[3] as List<Any?>
        val prefPath = exportItems[0] as ExportRenderPath
        val hwCaps = exportItems[1] as HardwareExportCapabilities
        val jobState = exportItems[2] as ExportJobState
        val exportHistory = exportItems[3] as List<ExportedArtifact>
        val preferProxy = p9Items[0] as Boolean
        val proxyState = p9Items[1] as ProxyJobState
        val motionBusy = p9Items[2] as Boolean
        val motionPoints = p9Items[3] as List<TrackedMotionPoint>

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

        val validKeyframeIds = primarySelectedClip?.keyframeTracks
            ?.firstOrNull { it.property == interaction.selectedKeyframeProperty }
            ?.keyframes
            ?.map { it.id }
            ?.toSet()
            ?.let { existing -> interaction.selectedKeyframeIds.intersect(existing) }
            ?: emptySet()

        val canSplit = doc != null && findSplittableClip(doc, clampedPlayhead, validSelectedIds) != null
        val audioMixState = doc?.let {
            audioMixer.computeLiveMeters(it, activeFrame, wfMap, assetsById)
        } ?: MasterAudioMixState()
        val exportPlan = doc?.let {
            exportPlanner.buildPlan(
                document = it,
                assetsById = assetsById,
                settings = it.exportSettings,
                preferredPath = prefPath,
                capabilities = hwCaps,
            )
        }

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
            selectedKeyframeProperty = interaction.selectedKeyframeProperty,
            selectedKeyframeIds = validKeyframeIds,
            waveformsByAssetId = wfMap,
            audioMixState = audioMixState,
            isAuditioningMix = auditioning,
            preferredExportPath = prefPath,
            hardwareCapabilities = hwCaps,
            exportPlan = exportPlan,
            exportJobState = jobState,
            projectExports = exportHistory,
            preferProxyPlayback = preferProxy,
            proxyJobState = proxyState,
            isMotionAnalysisRunning = motionBusy,
            lastMotionTrackPoints = motionPoints,
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
        stopPcmAudition()
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
        if (clip.clipKind == ClipKind.TEXT) return
        val updated = timelineEngine.updateClipAdjustments(doc, clip.clipId, adjustments)
        commitDocumentEdit("Color & Filter", doc, updated)
    }

    fun previewSelectedClipAdjustments(adjustments: ClipAdjustments) {
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind == ClipKind.TEXT) return
        val base = ensureGestureBaseDocument() ?: return
        draftDocument.value = timelineEngine.updateClipAdjustments(base, clip.clipId, adjustments)
    }

    fun applySelectedAdjustmentsToAll() {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind == ClipKind.TEXT) return
        val updated = timelineEngine.applyAdjustmentsToAllVisualClips(doc, clip.adjustments)
        commitDocumentEdit("Apply Filter to All", doc, updated)
        viewModelScope.launch { _messages.emit("Applied filter to all visual clips") }
    }

    // --- Phase 5: Advanced Compositor Operations ---

    fun addAdjustmentClip(atPlayhead: Boolean = true) {
        val doc = uiState.value.project ?: return
        val startMs = if (atPlayhead) playheadMs.value else null
        val (updated, created) = timelineEngine.insertAdjustmentClip(
            document = doc,
            targetTrackId = selectedTrackId.value.takeIf { id ->
                doc.tracks.firstOrNull { it.id == id }?.type == TrackType.ADJUSTMENT
            },
            atTimelineMs = startMs,
        )
        if (created != null) {
            selectedClipIds.value = setOf(created.clipId)
            selectedTrackId.value = created.trackId
            playheadMs.value = created.timelineStartMs
            commitDocumentEdit("Add Adjustment Layer", doc, updated)
            viewModelScope.launch { _messages.emit("Added adjustment layer clip") }
        } else {
            viewModelScope.launch { _messages.emit("Unlock an Adjustment track to add a layer") }
        }
    }

    fun updateSelectedClipTransform(transform: LayerTransform) {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind == ClipKind.ADJUSTMENT) return
        val updated = timelineEngine.updateClipTransform(doc, clip.clipId, transform)
        commitDocumentEdit("Layer Transform", doc, updated)
    }

    fun previewSelectedClipTransform(transform: LayerTransform) {
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind == ClipKind.ADJUSTMENT) return
        val base = ensureGestureBaseDocument() ?: return
        draftDocument.value = timelineEngine.updateClipTransform(base, clip.clipId, transform)
    }

    fun updateSelectedClipBlendMode(blendMode: BlendModePreset) {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind == ClipKind.ADJUSTMENT) return
        val updated = timelineEngine.updateClipBlendMode(doc, clip.clipId, blendMode)
        commitDocumentEdit("Blend Mode (${blendMode.label})", doc, updated)
    }

    fun updateSelectedClipMask(mask: LayerMask) {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind != ClipKind.MEDIA) return
        val updated = timelineEngine.updateClipMask(doc, clip.clipId, mask)
        commitDocumentEdit("Layer Mask", doc, updated)
    }

    fun previewSelectedClipMask(mask: LayerMask) {
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind != ClipKind.MEDIA) return
        val base = ensureGestureBaseDocument() ?: return
        draftDocument.value = timelineEngine.updateClipMask(base, clip.clipId, mask)
    }

    fun updateSelectedClipShaderEffect(shaderEffect: ClipShaderEffect) {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind == ClipKind.TEXT) return
        val updated = timelineEngine.updateClipShaderEffect(doc, clip.clipId, shaderEffect)
        commitDocumentEdit("Shader FX (${shaderEffect.type.label})", doc, updated)
    }

    fun previewSelectedClipShaderEffect(shaderEffect: ClipShaderEffect) {
        val clip = uiState.value.selectedClip ?: return
        if (clip.clipKind == ClipKind.TEXT) return
        val base = ensureGestureBaseDocument() ?: return
        draftDocument.value = timelineEngine.updateClipShaderEffect(base, clip.clipId, shaderEffect)
    }

    // --- Phase 6: Animation & Keyframe Graph Editor Operations ---

    fun selectKeyframeProperty(property: KeyframeProperty) {
        selectedKeyframeProperty.value = property
        selectedKeyframeIds.value = emptySet()
    }

    fun selectKeyframe(keyframeId: String?, multiSelect: Boolean = false) {
        if (keyframeId == null) {
            selectedKeyframeIds.value = emptySet()
            return
        }
        selectedKeyframeIds.value = if (multiSelect) {
            val cur = selectedKeyframeIds.value
            if (keyframeId in cur) cur - keyframeId else cur + keyframeId
        } else {
            setOf(keyframeId)
        }
    }

    fun jumpToClipRelativeTime(clipRelativeMs: Long) {
        val clip = uiState.value.selectedClip ?: return
        pausePlayback()
        seekTo(clip.timelineStartMs + clipRelativeMs.coerceIn(0L, clip.durationMs))
    }

    fun toggleKeyframeAtPlayhead(property: KeyframeProperty) {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val clipRelMs = (playheadMs.value - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
        val existingTrack = clip.keyframeTracks.firstOrNull { it.property == property }
        val existingNearby = existingTrack?.keyframes?.firstOrNull { abs(it.timeMs - clipRelMs) <= 40L }
        if (existingNearby != null) {
            val updated = timelineEngine.removeKeyframes(doc, clip.clipId, property, setOf(existingNearby.id))
            selectedKeyframeIds.value = selectedKeyframeIds.value - existingNearby.id
            commitDocumentEdit("Remove ${property.label} Keyframe", doc, updated)
        } else {
            val currentValue = animationEngine.evaluateProperty(clip, property, clipRelMs)
            val (updated, created) = timelineEngine.upsertKeyframe(
                document = doc,
                clipId = clip.clipId,
                property = property,
                timeMs = clipRelMs,
                value = currentValue,
            )
            if (created != null) {
                selectedKeyframeIds.value = setOf(created.id)
                commitDocumentEdit("Add ${property.label} Keyframe", doc, updated)
            }
        }
    }

    fun previewValueAtPlayhead(property: KeyframeProperty, newValue: Float) {
        val clip = uiState.value.selectedClip ?: return
        val base = ensureGestureBaseDocument() ?: return
        val clipRelMs = (playheadMs.value - clip.timelineStartMs).coerceIn(0L, clip.durationMs)
        val (updated, created) = timelineEngine.upsertKeyframe(
            document = base,
            clipId = clip.clipId,
            property = property,
            timeMs = clipRelMs,
            value = newValue,
        )
        if (created != null) {
            selectedKeyframeIds.value = setOf(created.id)
        }
        draftDocument.value = updated
    }

    fun previewMoveKeyframe(
        property: KeyframeProperty,
        keyframeId: String,
        newTimeMs: Long,
        newValue: Float,
    ) {
        val clip = uiState.value.selectedClip ?: return
        val base = ensureGestureBaseDocument() ?: return
        draftDocument.value = timelineEngine.updateKeyframeById(
            document = base,
            clipId = clip.clipId,
            property = property,
            keyframeId = keyframeId,
            newTimeMs = newTimeMs,
            newValue = newValue,
        )
    }

    fun previewBezierHandle(
        property: KeyframeProperty,
        keyframeId: String,
        bezier: BezierCurveSpec,
    ) {
        val clip = uiState.value.selectedClip ?: return
        val base = ensureGestureBaseDocument() ?: return
        draftDocument.value = timelineEngine.updateKeyframeById(
            document = base,
            clipId = clip.clipId,
            property = property,
            keyframeId = keyframeId,
            interpolation = KeyframeInterpolation.CUBIC_BEZIER,
            bezier = bezier,
        )
    }

    fun updateSelectedKeyframesInterpolation(
        property: KeyframeProperty,
        interpolation: KeyframeInterpolation,
        bezier: BezierCurveSpec? = null,
    ) {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val track = clip.keyframeTracks.firstOrNull { it.property == property } ?: return
        val targetIds = uiState.value.selectedKeyframeIds.ifEmpty {
            track.keyframes.firstOrNull()?.let { setOf(it.id) }.orEmpty()
        }
        if (targetIds.isEmpty()) return
        val updated = timelineEngine.updateMultipleKeyframesInterpolation(
            document = doc,
            clipId = clip.clipId,
            property = property,
            keyframeIds = targetIds,
            interpolation = interpolation,
            bezier = bezier,
        )
        commitDocumentEdit("Keyframe Easing (${interpolation.label})", doc, updated)
    }

    fun deleteSelectedKeyframes(property: KeyframeProperty) {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val ids = uiState.value.selectedKeyframeIds
        if (ids.isEmpty()) return
        val updated = timelineEngine.removeKeyframes(doc, clip.clipId, property, ids)
        selectedKeyframeIds.value = emptySet()
        commitDocumentEdit("Delete Keyframes", doc, updated)
    }

    fun clearPropertyKeyframes(property: KeyframeProperty) {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val updated = timelineEngine.clearKeyframeTrack(doc, clip.clipId, property)
        selectedKeyframeIds.value = emptySet()
        commitDocumentEdit("Clear ${property.label} Animation", doc, updated)
    }

    fun applyMotionPreset(preset: MotionPreset) {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val updated = timelineEngine.applyMotionPresetToClip(doc, clip.clipId, preset)
        selectedKeyframeIds.value = emptySet()
        commitDocumentEdit("Motion Preset (${preset.label})", doc, updated)
        viewModelScope.launch { _messages.emit("Applied ${preset.label} animation") }
    }

    // --- Phase 7: Audio Engine Operations ---

    fun updateSelectedClipAudioEffect(effect: ClipAudioEffect) {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val updated = timelineEngine.updateClipAudioEffect(doc, clip.clipId, effect)
        commitDocumentEdit("Audio FX (${effect.preset.label})", doc, updated)
    }

    fun previewSelectedClipAudioEffect(effect: ClipAudioEffect) {
        val clip = uiState.value.selectedClip ?: return
        val base = ensureGestureBaseDocument() ?: return
        draftDocument.value = timelineEngine.updateClipAudioEffect(base, clip.clipId, effect)
    }

    fun addBeatMarkerAtPlayhead() {
        val doc = uiState.value.project ?: return
        val (updated, _) = timelineEngine.addTimelineMarker(
            document = doc,
            timeMs = playheadMs.value,
            label = "Beat ${doc.markers.size + 1}",
            isBeatMarker = true,
        )
        if (updated != doc) {
            commitDocumentEdit("Add Beat Marker", doc, updated)
            viewModelScope.launch { _messages.emit("Added beat marker") }
        }
    }

    fun detectBeatsForSelectedClip() {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val waveform = uiState.value.waveformsByAssetId[clip.assetId] ?: return
        val updated = timelineEngine.addBeatMarkersFromClip(doc, clip, waveform.beatTimestampsMs)
        if (updated != doc) {
            commitDocumentEdit("Auto-Detect Beats", doc, updated)
            viewModelScope.launch {
                _messages.emit("Detected ${updated.markers.size - doc.markers.size} beat marker(s)")
            }
        } else {
            viewModelScope.launch { _messages.emit("No new beats detected in selected clip range") }
        }
    }

    fun removeTimelineMarker(markerId: String) {
        val doc = uiState.value.project ?: return
        val updated = timelineEngine.removeTimelineMarker(doc, markerId)
        if (updated != doc) {
            commitDocumentEdit("Remove Marker", doc, updated)
        }
    }

    fun clearAllTimelineMarkers() {
        val doc = uiState.value.project ?: return
        val updated = timelineEngine.clearTimelineMarkers(doc)
        if (updated != doc) {
            commitDocumentEdit("Clear Markers", doc, updated)
            viewModelScope.launch { _messages.emit("Cleared timeline markers") }
        }
    }

    fun toggleAuditionPcmMix() {
        if (isAuditioningMix.value) {
            stopPcmAudition()
            return
        }
        val doc = uiState.value.project ?: return
        val extractor = pcmAudioExtractor
        val player = mixedAudioPreviewPlayer
        if (extractor == null || player == null) {
            viewModelScope.launch { _messages.emit("Audio audition player unavailable") }
            return
        }
        pausePlayback()
        val startMs = playheadMs.value
        val windowMs = 2_500L
        val assetsById = uiState.value.assetsById
        auditionJob?.cancel()
        isAuditioningMix.value = true
        auditionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val windowEnd = startMs + windowMs
                val candidateClips = doc.tracks
                    .filter { !it.isMuted && (it.type == TrackType.AUDIO || it.type == TrackType.VIDEO || it.type == TrackType.OVERLAY) }
                    .flatMap { it.clips }
                    .filter { clip ->
                        clip.clipKind == ClipKind.MEDIA &&
                            !clip.isMuted &&
                            (clip.timelineStartMs + clip.durationMs) > startMs &&
                            clip.timelineStartMs < windowEnd
                    }

                if (candidateClips.isEmpty()) {
                    _messages.emit("No active unmuted audio clips at playhead")
                    isAuditioningMix.value = false
                    return@launch
                }

                val decodedCache = HashMap<String, DecodedPcmBuffer?>()
                for (clip in candidateClips) {
                    val asset = assetsById[clip.assetId] ?: continue
                    if (asset.type == MediaType.IMAGE) continue
                    val overlapStart = max(startMs, clip.timelineStartMs)
                    val overlapEnd = min(windowEnd, clip.timelineStartMs + clip.durationMs)
                    val overlapDur = overlapEnd - overlapStart
                    if (overlapDur <= 0L) continue
                    val relStart = overlapStart - clip.timelineStartMs
                    val relEnd = overlapEnd - clip.timelineStartMs
                    val sA = SpeedRampEngine.clipRelativeToSourceMs(clip, relStart, asset.durationMs)
                    val sB = SpeedRampEngine.clipRelativeToSourceMs(clip, relEnd, asset.durationMs)
                    val srcStart = min(sA, sB).coerceAtLeast(0L)
                    val srcDur = abs(sB - sA).coerceAtLeast(100L)
                    val key = "${clip.assetId}:$srcStart:$srcDur"
                    if (!decodedCache.containsKey(key)) {
                        decodedCache[key] = extractor.decodePcmSegment(asset, srcStart, srcDur)
                    }
                }

                val pcm16 = audioMixer.mixWindowToPcm16(
                    document = doc,
                    startMs = startMs,
                    windowDurationMs = windowMs,
                    targetSampleRate = 44_100,
                ) { assetId, srcStart, srcDur ->
                    decodedCache["$assetId:$srcStart:$srcDur"]
                }

                if (pcm16.all { it == 0.toShort() }) {
                    _messages.emit("Decoded mix window is silent")
                    isAuditioningMix.value = false
                    return@launch
                }

                player.playPcm16(pcm16, sampleRateHz = 44_100)
                delay(windowMs)
            } finally {
                player.stop()
                isAuditioningMix.value = false
            }
        }
    }

    private fun stopPcmAudition() {
        auditionJob?.cancel()
        auditionJob = null
        mixedAudioPreviewPlayer?.stop()
        isAuditioningMix.value = false
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

    // --- Phase 8: Export Engine Operations ---

    fun refreshExportHistory() {
        val mgr = exportOutputManager ?: return
        viewModelScope.launch {
            projectExports.value = mgr.listProjectExports(projectId)
        }
    }

    fun updateExportSettings(settings: ExportSettingsSpec) {
        viewModelScope.launch {
            projectRepository.updateProjectExportSettings(projectId, settings)
        }
    }

    fun setPreferredExportPath(path: ExportRenderPath) {
        preferredExportPath.value = path
    }

    fun startExport() {
        val doc = uiState.value.project ?: return
        val coordinator = exportCoordinator ?: run {
            viewModelScope.launch { _messages.emit("Export engine unavailable") }
            return
        }
        pausePlayback()
        stopPcmAudition()
        coordinator.startExport(
            scope = viewModelScope,
            request = ExportRequest(
                project = doc,
                assetsById = uiState.value.assetsById,
                settings = doc.exportSettings,
                preferredPath = preferredExportPath.value,
            ),
            onArtifactRecorded = {
                refreshExportHistory()
            },
        )
    }

    fun cancelExport() {
        exportCoordinator?.cancelExport()
    }

    fun resetExportState() {
        exportCoordinator?.resetState()
    }

    fun saveExportToGallery(artifact: ExportedArtifact) {
        val mgr = exportOutputManager ?: return
        viewModelScope.launch {
            val uri = mgr.saveToGallery(artifact)
            if (uri != null) {
                refreshExportHistory()
                _messages.emit("Saved to Movies/CutLab gallery")
            } else {
                _messages.emit("Unable to save video to gallery")
            }
        }
    }

    fun saveExportToUri(artifact: ExportedArtifact, destinationUri: Uri) {
        val mgr = exportOutputManager ?: return
        viewModelScope.launch {
            val ok = mgr.writeToDestinationUri(artifact, destinationUri)
            if (ok) {
                refreshExportHistory()
                _messages.emit("Saved MP4 to chosen location")
            } else {
                _messages.emit("Failed writing MP4 to destination")
            }
        }
    }

    fun createShareIntent(artifact: ExportedArtifact): Intent? {
        return runCatching {
            exportOutputManager?.createShareIntent(artifact)
        }.getOrNull()
    }

    fun deleteExportArtifact(artifactId: String) {
        val mgr = exportOutputManager ?: return
        viewModelScope.launch {
            mgr.deleteArtifact(artifactId)
            refreshExportHistory()
            _messages.emit("Removed exported file")
        }
    }

    // --- Phase 9: Advanced Video Tools & Proxies ---

    fun setPreferProxyPlayback(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setPreferProxyPlayback(enabled)
        }
    }

    fun updateSelectedClipSpeedRamp(preset: SpeedRampPreset, customPoints: List<SpeedPoint> = emptyList()) {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val asset = uiState.value.selectedClipAsset
        val isImage = asset?.type == MediaType.IMAGE
        if (isImage) {
            viewModelScope.launch { _messages.emit("Speed ramping applies to video and audio clips") }
            return
        }
        val updated = timelineEngine.updateClipSpeedRamp(doc, clip.clipId, preset, customPoints, isImageClip = false)
        commitDocumentEdit("Speed Ramp (${preset.label})", doc, updated)
    }

    fun toggleSelectedClipReverse() {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val asset = uiState.value.selectedClipAsset
        if (asset?.type == MediaType.IMAGE) {
            viewModelScope.launch { _messages.emit("Reverse playback applies to video & audio clips") }
            return
        }
        val nextReversed = !clip.isReversed
        val updated = timelineEngine.updateClipReversed(doc, clip.clipId, nextReversed, isImageClip = false)
        commitDocumentEdit(if (nextReversed) "Reverse Clip" else "Forward Clip", doc, updated)
        viewModelScope.launch {
            _messages.emit(if (nextReversed) "Reverse playback enabled" else "Normal forward playback restored")
        }
    }

    fun previewSelectedClipChromaKey(spec: ChromaKeySpec) {
        val clip = uiState.value.selectedClip ?: return
        val base = ensureGestureBaseDocument() ?: return
        draftDocument.value = timelineEngine.updateClipChromaKey(base, clip.clipId, spec)
    }

    fun updateSelectedClipChromaKey(spec: ChromaKeySpec) {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val updated = timelineEngine.updateClipChromaKey(doc, clip.clipId, spec)
        commitDocumentEdit("Chroma Key", doc, updated)
    }

    fun previewSelectedClipLut(spec: ClipLutSpec) {
        val clip = uiState.value.selectedClip ?: return
        val base = ensureGestureBaseDocument() ?: return
        draftDocument.value = timelineEngine.updateClipLut(base, clip.clipId, spec)
    }

    fun updateSelectedClipLut(spec: ClipLutSpec) {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val updated = timelineEngine.updateClipLut(doc, clip.clipId, spec)
        commitDocumentEdit("3D LUT (${spec.preset.label})", doc, updated)
    }

    fun importCustomCubeLut(uri: Uri) {
        val ctx = appContext ?: run {
            viewModelScope.launch { _messages.emit("Context unavailable for LUT import") }
            return
        }
        val clip = uiState.value.selectedClip ?: return
        viewModelScope.launch {
            val result = Lut3DEngine.importCubeFile(ctx, uri)
            result.onSuccess { (path, name) ->
                val latestDoc = uiState.value.project ?: return@onSuccess
                val updated = timelineEngine.updateClipLut(
                    latestDoc,
                    clip.clipId,
                    ClipLutSpec(
                        preset = com.example.engine.project.LutPreset.CUSTOM_CUBE,
                        intensity = 0.85f,
                        customCubePath = path,
                        customCubeName = name,
                    ),
                )
                commitDocumentEdit("Import .cube LUT ($name)", latestDoc, updated)
                _messages.emit("Loaded 3D LUT: $name")
            }.onFailure { err ->
                _messages.emit(err.message ?: "Failed parsing .cube LUT file")
            }
        }
    }

    fun runMotionTrackingOnSelectedClip(roiNormX: Float, roiNormY: Float) {
        val ctx = appContext
        val clip = uiState.value.selectedClip
        val asset = uiState.value.selectedClipAsset
        if (ctx == null || clip == null || asset == null || asset.type != MediaType.VIDEO) {
            viewModelScope.launch { _messages.emit("Select a video clip to run optical motion tracking") }
            return
        }
        if (isMotionAnalysisRunning.value) return
        viewModelScope.launch {
            isMotionAnalysisRunning.value = true
            try {
                val analysis = MotionTrackingEngine.analyzeTrackTarget(
                    context = ctx,
                    asset = asset,
                    clip = clip,
                    startNormX = roiNormX,
                    startNormY = roiNormY,
                )
                if (analysis.keyframeTracks.isEmpty()) {
                    _messages.emit("Unable to sample video frames for motion tracking")
                } else {
                    lastMotionTrackPoints.value = analysis.points
                    val doc = uiState.value.project ?: return@launch
                    val updated = timelineEngine.mergeKeyframeTracks(doc, clip.clipId, analysis.keyframeTracks)
                    commitDocumentEdit("Optical Motion Track", doc, updated)
                    _messages.emit("Tracked ${analysis.points.size} keyframes onto Position X/Y")
                }
            } finally {
                isMotionAnalysisRunning.value = false
            }
        }
    }

    fun runStabilizationOnSelectedClip(strength: Float) {
        val ctx = appContext
        val clip = uiState.value.selectedClip
        val asset = uiState.value.selectedClipAsset
        if (ctx == null || clip == null || asset == null || asset.type != MediaType.VIDEO) {
            viewModelScope.launch { _messages.emit("Select a video clip to run video stabilization") }
            return
        }
        if (isMotionAnalysisRunning.value) return
        viewModelScope.launch {
            isMotionAnalysisRunning.value = true
            try {
                val analysis = MotionTrackingEngine.analyzeStabilization(
                    context = ctx,
                    asset = asset,
                    clip = clip,
                    strength = strength,
                )
                if (analysis.keyframeTracks.isEmpty()) {
                    _messages.emit("Unable to sample video frames for stabilization")
                } else {
                    val doc = uiState.value.project ?: return@launch
                    val spec = StabilizationSpec(
                        enabled = true,
                        strength = strength,
                        cropScale = analysis.cropScale,
                    )
                    val updated = timelineEngine.updateClipStabilization(
                        document = doc,
                        clipId = clip.clipId,
                        stabilization = spec,
                        generatedTracks = analysis.keyframeTracks,
                    )
                    commitDocumentEdit("Stabilize Clip", doc, updated)
                    _messages.emit(
                        "Stabilized (${(strength * 100).toInt()}%, auto-crop ${String.format("%.2fx", analysis.cropScale)})",
                    )
                }
            } finally {
                isMotionAnalysisRunning.value = false
            }
        }
    }

    fun disableStabilizationOnSelectedClip() {
        val doc = uiState.value.project ?: return
        val clip = uiState.value.selectedClip ?: return
        val updated = timelineEngine.updateClipStabilization(
            document = doc,
            clipId = clip.clipId,
            stabilization = StabilizationSpec(enabled = false, strength = clip.stabilization.strength, cropScale = 1f),
            generatedTracks = emptyList(),
        )
        commitDocumentEdit("Remove Stabilization", doc, updated)
        viewModelScope.launch { _messages.emit("Stabilization transforms cleared") }
    }

    fun generateProxyForSelectedAsset() {
        val asset = uiState.value.selectedClipAsset
        val mgr = proxyMediaManager
        if (asset == null || mgr == null) {
            viewModelScope.launch { _messages.emit("Select a video clip first to generate a 540p proxy") }
            return
        }
        mgr.generateProxyForAsset(asset)
    }

    fun cancelActiveProxyJob() {
        proxyMediaManager?.cancelActiveProxyJob()
    }

    fun deleteProxyForSelectedAsset() {
        val asset = uiState.value.selectedClipAsset ?: return
        val mgr = proxyMediaManager ?: return
        viewModelScope.launch {
            mgr.deleteProxyForAsset(asset)
            _messages.emit("Deleted 540p proxy (original preserved)")
        }
    }

    override fun onCleared() {
        playbackJob?.cancel()
        stopPcmAudition()
        super.onCleared()
    }

    companion object {
        fun factory(
            projectId: String,
            projectRepository: ProjectRepository,
            preferencesRepository: CutLabPreferencesRepository,
            pcmAudioExtractor: PcmAudioExtractor? = null,
            mixedAudioPreviewPlayer: MixedAudioPreviewPlayer? = null,
            exportPlanner: ExportPlanner = ExportPlanner(),
            exportOutputManager: ExportOutputManager? = null,
            exportCoordinator: CutLabExportCoordinator? = null,
            proxyMediaManager: ProxyMediaManager? = null,
            appContext: Context? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ProjectWorkspaceViewModel(
                    projectId = projectId,
                    projectRepository = projectRepository,
                    preferencesRepository = preferencesRepository,
                    pcmAudioExtractor = pcmAudioExtractor,
                    mixedAudioPreviewPlayer = mixedAudioPreviewPlayer,
                    exportPlanner = exportPlanner,
                    exportOutputManager = exportOutputManager,
                    exportCoordinator = exportCoordinator,
                    proxyMediaManager = proxyMediaManager,
                    appContext = appContext,
                ) as T
            }
        }
    }
}
