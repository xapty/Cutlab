package com.example.feature.project

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.core.common.TimeFormatting
import com.example.core.designsystem.EmptyStudioState
import com.example.core.designsystem.MediaTypeBadge
import com.example.core.designsystem.StudioBadge
import com.example.core.designsystem.StudioPanel
import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.ClipAdjustments
import com.example.engine.project.ClipTransition
import com.example.engine.project.EditorMode
import com.example.engine.project.FrameRateOption
import com.example.engine.project.MediaAsset
import com.example.engine.project.MediaStatus
import com.example.engine.project.MediaType
import com.example.engine.project.ProjectDocument
import com.example.engine.project.TextOverlaySpec
import com.example.engine.project.TextStylePreset
import com.example.engine.project.TimelineClipRef
import com.example.engine.project.TimelineTrack
import com.example.feature.timeline.TimelineEditorPane
import com.example.ui.theme.LocalStudioColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectWorkspaceScreen(
    viewModel: ProjectWorkspaceViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showMediaSheet by rememberSaveable { mutableStateOf(false) }
    var showInspectorSheet by rememberSaveable { mutableStateOf(false) }
    var activeQuickTool by rememberSaveable { mutableStateOf(QuickToolSheet.NONE) }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> viewModel.importMedia(uris, MediaType.VIDEO) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> viewModel.importMedia(uris, MediaType.IMAGE) }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.importMedia(uris, MediaType.AUDIO) }

    val anyFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.importMedia(uris, null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val project = uiState.project

    val onImportVideo = {
        videoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
        )
    }
    val onImportImage = {
        imagePickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }
    val onImportAudio = {
        audioPickerLauncher.launch(arrayOf("audio/*"))
    }
    val onImportFile = {
        anyFilePickerLauncher.launch(arrayOf("video/*", "image/*", "audio/*"))
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("workspace_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to projects",
                        )
                    }
                },
                title = {
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = project != null) { showRenameDialog = true }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = project?.name ?: "Loading...",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (project != null) {
                            Text(
                                text = "${project.aspectRatio.label} • ${project.fps} FPS",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::undo,
                        enabled = uiState.canUndo,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("editor_undo_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = uiState.undoLabel?.let { "Undo $it" } ?: "Undo",
                            tint = if (uiState.canUndo) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            },
                        )
                    }
                    IconButton(
                        onClick = viewModel::redo,
                        enabled = uiState.canRedo,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("editor_redo_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Redo,
                            contentDescription = uiState.redoLabel?.let { "Redo $it" } ?: "Redo",
                            tint = if (uiState.canRedo) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            },
                        )
                    }
                    if (project != null) {
                        WorkspaceModeSwitcher(
                            currentMode = project.editorMode,
                            onModeSelected = viewModel::switchEditorMode,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (project == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                if (uiState.isLoading) {
                    LinearProgressIndicator(modifier = Modifier.width(180.dp))
                } else {
                    EmptyStudioState(
                        icon = Icons.Default.Movie,
                        title = "Project Not Found",
                        subtitle = "This project may have been deleted.",
                        actionLabel = "Back to Library",
                        onAction = onNavigateBack,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (uiState.isImporting) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                val isLandscapeWorkspace = maxWidth >= 700.dp

                if (isLandscapeWorkspace) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(0.50f)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            TimelinePreviewMonitor(
                                project = project,
                                activeFrame = uiState.activeFrame,
                                assetsById = uiState.assetsById,
                                playheadMs = uiState.playheadMs,
                                totalDurationMs = uiState.totalDurationMs,
                                isPlaying = uiState.isPlaying,
                                stageHeight = 148.dp,
                                onTogglePlayPause = viewModel::togglePlayPause,
                                onStepFrame = viewModel::stepFrame,
                                onJumpStart = viewModel::jumpToStart,
                                onJumpEnd = viewModel::jumpToEnd,
                                modifier = Modifier
                                    .weight(0.56f)
                                    .fillMaxHeight(),
                            )
                            SideInspectorPanel(
                                uiState = uiState,
                                project = project,
                                onSelectTab = viewModel::setInspectorTab,
                                onFilterToggle = viewModel::toggleMediaFilter,
                                onImportVideo = onImportVideo,
                                onImportImage = onImportImage,
                                onImportAudio = onImportAudio,
                                onImportFile = onImportFile,
                                onInsertAsset = { assetId ->
                                    viewModel.insertAssetToTimeline(assetId, atPlayhead = false)
                                },
                                onDeleteAsset = viewModel::deleteAsset,
                                onSplitAtPlayhead = viewModel::splitAtPlayhead,
                                onDuplicateClip = viewModel::duplicateSelectedClip,
                                onRemoveSelectedClips = viewModel::removeSelectedClips,
                                onRippleDeleteClip = viewModel::rippleDeleteSelectedClip,
                                onNudgeTrimLeft = viewModel::nudgeTrimLeft,
                                onNudgeTrimRight = viewModel::nudgeTrimRight,
                                onJumpToClip = { clip -> viewModel.seekTo(clip.timelineStartMs) },
                                onToggleTrackMute = viewModel::toggleTrackMute,
                                onToggleTrackLock = viewModel::toggleTrackLock,
                                onReorderTrack = viewModel::reorderTrack,
                                onRemoveTrack = viewModel::removeTrack,
                                onUpdateCanvas = viewModel::updateCanvasSpecs,
                                onAddTextClip = { preset -> viewModel.addTextClip(preset, atPlayhead = true) },
                                onUpdateTextSpec = viewModel::updateSelectedTextSpec,
                                onPreviewTextSpec = viewModel::previewSelectedTextSpec,
                                onUpdateAdjustments = viewModel::updateSelectedClipAdjustments,
                                onPreviewAdjustments = viewModel::previewSelectedClipAdjustments,
                                onApplyFilterToAll = viewModel::applySelectedAdjustmentsToAll,
                                onUpdateTransition = viewModel::updateSelectedClipTransition,
                                onPreviewTransition = viewModel::previewSelectedClipTransition,
                                onUpdateSpeed = viewModel::updateSelectedClipSpeed,
                                onUpdateAudio = viewModel::updateSelectedClipAudio,
                                onPreviewAudio = viewModel::previewSelectedClipAudio,
                                onCommitSliderEdit = viewModel::commitClipGesture,
                                modifier = Modifier
                                    .weight(0.44f)
                                    .fillMaxHeight(),
                            )
                        }

                        TimelineEditorPane(
                            project = project,
                            assetsById = uiState.assetsById,
                            playheadMs = uiState.playheadMs,
                            totalDurationMs = uiState.totalDurationMs,
                            selectedClipIds = uiState.selectedClipIds,
                            selectedTrackId = uiState.selectedTrack?.id,
                            timelineZoom = uiState.timelineZoom,
                            snappingEnabled = uiState.snappingEnabled,
                            activeSnapGuideMs = uiState.activeSnapGuideMs,
                            onSeekMs = viewModel::seekTo,
                            onSelectClip = viewModel::selectClip,
                            onSelectTrack = viewModel::selectTrack,
                            onToggleTrackMute = viewModel::toggleTrackMute,
                            onToggleTrackLock = viewModel::toggleTrackLock,
                            onReorderTrack = viewModel::reorderTrack,
                            onAddTrack = viewModel::addTrack,
                            onZoomChange = viewModel::setTimelineZoom,
                            onToggleSnapping = viewModel::toggleSnapping,
                            onBeginClipGesture = viewModel::beginClipGesture,
                            onPreviewTrimLeft = viewModel::previewTrimLeft,
                            onPreviewTrimRight = viewModel::previewTrimRight,
                            onPreviewMoveClip = viewModel::previewMoveClip,
                            onCommitClipGesture = viewModel::commitClipGesture,
                            onCancelClipGesture = viewModel::cancelClipGesture,
                            modifier = Modifier
                                .weight(0.50f)
                                .fillMaxWidth(),
                        )
                    }
                } else {
                    // Portrait Workspace
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TimelinePreviewMonitor(
                            project = project,
                            activeFrame = uiState.activeFrame,
                            assetsById = uiState.assetsById,
                            playheadMs = uiState.playheadMs,
                            totalDurationMs = uiState.totalDurationMs,
                            isPlaying = uiState.isPlaying,
                            stageHeight = 186.dp,
                            onTogglePlayPause = viewModel::togglePlayPause,
                            onStepFrame = viewModel::stepFrame,
                            onJumpStart = viewModel::jumpToStart,
                            onJumpEnd = viewModel::jumpToEnd,
                        )

                        TimelineEditorPane(
                            project = project,
                            assetsById = uiState.assetsById,
                            playheadMs = uiState.playheadMs,
                            totalDurationMs = uiState.totalDurationMs,
                            selectedClipIds = uiState.selectedClipIds,
                            selectedTrackId = uiState.selectedTrack?.id,
                            timelineZoom = uiState.timelineZoom,
                            snappingEnabled = uiState.snappingEnabled,
                            activeSnapGuideMs = uiState.activeSnapGuideMs,
                            onSeekMs = viewModel::seekTo,
                            onSelectClip = viewModel::selectClip,
                            onSelectTrack = viewModel::selectTrack,
                            onToggleTrackMute = viewModel::toggleTrackMute,
                            onToggleTrackLock = viewModel::toggleTrackLock,
                            onReorderTrack = viewModel::reorderTrack,
                            onAddTrack = viewModel::addTrack,
                            onZoomChange = viewModel::setTimelineZoom,
                            onToggleSnapping = viewModel::toggleSnapping,
                            onBeginClipGesture = viewModel::beginClipGesture,
                            onPreviewTrimLeft = viewModel::previewTrimLeft,
                            onPreviewTrimRight = viewModel::previewTrimRight,
                            onPreviewMoveClip = viewModel::previewMoveClip,
                            onCommitClipGesture = viewModel::commitClipGesture,
                            onCancelClipGesture = viewModel::cancelClipGesture,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )

                        PortraitBottomToolbar(
                            mediaCount = uiState.allAssets.size,
                            selectedClipIds = uiState.selectedClipIds,
                            selectedClip = uiState.selectedClip,
                            canSplitAtPlayhead = uiState.canSplitAtPlayhead,
                            onOpenMediaSheet = { showMediaSheet = true },
                            onOpenInspectorSheet = { showInspectorSheet = true },
                            onOpenQuickTool = { activeQuickTool = it },
                            onSplitAtPlayhead = viewModel::splitAtPlayhead,
                            onDuplicateClip = viewModel::duplicateSelectedClip,
                            onRippleDeleteClip = viewModel::rippleDeleteSelectedClip,
                            onRemoveSelectedClips = viewModel::removeSelectedClips,
                        )
                    }
                }
            }
        }
    }

    if (showMediaSheet && project != null) {
        ModalBottomSheet(
            onDismissRequest = { showMediaSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Project Media Bin (${uiState.allAssets.size})",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ImportActionStrip(
                    onImportVideo = onImportVideo,
                    onImportImage = onImportImage,
                    onImportAudio = onImportAudio,
                    onImportFile = onImportFile,
                )
                MediaFilterChipsRow(
                    activeFilter = uiState.mediaFilter,
                    onFilterToggle = viewModel::toggleMediaFilter,
                )
                if (uiState.filteredAssets.isEmpty()) {
                    EmptyStudioState(
                        icon = Icons.Default.PermMedia,
                        title = "No Media Assets",
                        subtitle = "Import videos, photos, or audio above and tap any asset to place it on the timeline.",
                        actionLabel = "Import Video",
                        onAction = onImportVideo,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.height(300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(uiState.filteredAssets, key = { it.id }) { asset ->
                            MediaAssetRowCard(
                                asset = asset,
                                isSelected = uiState.selectedClipAsset?.id == asset.id,
                                onSelect = {
                                    viewModel.insertAssetToTimeline(asset.id, atPlayhead = false)
                                    showMediaSheet = false
                                },
                                onDelete = { viewModel.deleteAsset(asset.id) },
                                actionLabel = "+ Timeline",
                            )
                        }
                    }
                }
            }
        }
    }

    if (showInspectorSheet && project != null) {
        ModalBottomSheet(
            onDismissRequest = { showInspectorSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InspectorDetailsContent(
                    uiState = uiState,
                    project = project,
                    onSplitAtPlayhead = viewModel::splitAtPlayhead,
                    onDuplicateClip = viewModel::duplicateSelectedClip,
                    onRemoveSelectedClips = {
                        viewModel.removeSelectedClips()
                        showInspectorSheet = false
                    },
                    onRippleDeleteClip = {
                        viewModel.rippleDeleteSelectedClip()
                        showInspectorSheet = false
                    },
                    onNudgeTrimLeft = viewModel::nudgeTrimLeft,
                    onNudgeTrimRight = viewModel::nudgeTrimRight,
                    onJumpToClip = { clip ->
                        viewModel.seekTo(clip.timelineStartMs)
                        showInspectorSheet = false
                    },
                    onToggleTrackMute = viewModel::toggleTrackMute,
                    onToggleTrackLock = viewModel::toggleTrackLock,
                    onReorderTrack = viewModel::reorderTrack,
                    onRemoveTrack = viewModel::removeTrack,
                    onUpdateCanvas = viewModel::updateCanvasSpecs,
                )
            }
        }
    }

    if (activeQuickTool != QuickToolSheet.NONE && project != null) {
        ModalBottomSheet(
            onDismissRequest = {
                viewModel.commitClipGesture("Quick Edit")
                activeQuickTool = QuickToolSheet.NONE
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (activeQuickTool) {
                    QuickToolSheet.TEXT -> TextToolPanel(
                        selectedClip = uiState.selectedClip,
                        onAddTextClip = { preset -> viewModel.addTextClip(preset, atPlayhead = true) },
                        onUpdateTextSpec = viewModel::updateSelectedTextSpec,
                        onPreviewTextSpec = viewModel::previewSelectedTextSpec,
                        onCommitSliderEdit = viewModel::commitClipGesture,
                    )
                    QuickToolSheet.FILTERS -> FilterToolPanel(
                        clip = uiState.selectedClip,
                        onUpdateAdjustments = viewModel::updateSelectedClipAdjustments,
                        onPreviewAdjustments = viewModel::previewSelectedClipAdjustments,
                        onCommitSliderEdit = viewModel::commitClipGesture,
                        onApplyFilterToAll = viewModel::applySelectedAdjustmentsToAll,
                    )
                    QuickToolSheet.TRANSITIONS -> TransitionToolPanel(
                        clip = uiState.selectedClip,
                        onUpdateTransition = viewModel::updateSelectedClipTransition,
                        onPreviewTransition = viewModel::previewSelectedClipTransition,
                        onCommitSliderEdit = viewModel::commitClipGesture,
                    )
                    QuickToolSheet.SPEED -> SpeedToolPanel(
                        clip = uiState.selectedClip,
                        onUpdateSpeed = viewModel::updateSelectedClipSpeed,
                    )
                    QuickToolSheet.AUDIO -> AudioToolPanel(
                        clip = uiState.selectedClip,
                        onUpdateAudio = viewModel::updateSelectedClipAudio,
                        onPreviewAudio = viewModel::previewSelectedClipAudio,
                        onCommitSliderEdit = viewModel::commitClipGesture,
                    )
                    QuickToolSheet.RATIO -> RatioToolPanel(
                        project = project,
                        onUpdateCanvas = viewModel::updateCanvasSpecs,
                    )
                    QuickToolSheet.NONE -> Unit
                }
            }
        }
    }

    if (showRenameDialog && project != null) {
        var name by rememberSaveable(project.name) { mutableStateOf(project.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Project") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Project Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameProject(name)
                        showRenameDialog = false
                    },
                    enabled = name.isNotBlank(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SideInspectorPanel(
    uiState: ProjectWorkspaceUiState,
    project: ProjectDocument,
    onSelectTab: (InspectorTab) -> Unit,
    onFilterToggle: (MediaType?) -> Unit,
    onImportVideo: () -> Unit,
    onImportImage: () -> Unit,
    onImportAudio: () -> Unit,
    onImportFile: () -> Unit,
    onInsertAsset: (String) -> Unit,
    onDeleteAsset: (String) -> Unit,
    onSplitAtPlayhead: () -> Unit,
    onDuplicateClip: () -> Unit,
    onRemoveSelectedClips: () -> Unit,
    onRippleDeleteClip: () -> Unit,
    onNudgeTrimLeft: (Long) -> Unit,
    onNudgeTrimRight: (Long) -> Unit,
    onJumpToClip: (TimelineClipRef) -> Unit,
    onToggleTrackMute: (String) -> Unit,
    onToggleTrackLock: (String) -> Unit,
    onReorderTrack: (String, Int) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onUpdateCanvas: (AspectRatioPreset, Int) -> Unit,
    onAddTextClip: (TextStylePreset) -> Unit,
    onUpdateTextSpec: (TextOverlaySpec) -> Unit,
    onPreviewTextSpec: (TextOverlaySpec) -> Unit,
    onUpdateAdjustments: (ClipAdjustments) -> Unit,
    onPreviewAdjustments: (ClipAdjustments) -> Unit,
    onApplyFilterToAll: () -> Unit,
    onUpdateTransition: (ClipTransition?) -> Unit,
    onPreviewTransition: (ClipTransition?) -> Unit,
    onUpdateSpeed: (Float) -> Unit,
    onUpdateAudio: (Float, Boolean, Long, Long) -> Unit,
    onPreviewAudio: (Float, Boolean, Long, Long) -> Unit,
    onCommitSliderEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, studio.hairlineBorder),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(
                selectedTabIndex = uiState.inspectorTab.ordinal,
                containerColor = studio.elevatedPanel,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                InspectorTab.entries.forEach { tab ->
                    Tab(
                        selected = uiState.inspectorTab == tab,
                        onClick = { onSelectTab(tab) },
                        text = {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        },
                        modifier = Modifier.testTag("inspector_tab_${tab.name.lowercase()}"),
                    )
                }
            }

            when (uiState.inspectorTab) {
                InspectorTab.MEDIA -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ImportActionStrip(
                            onImportVideo = onImportVideo,
                            onImportImage = onImportImage,
                            onImportAudio = onImportAudio,
                            onImportFile = onImportFile,
                        )
                        MediaFilterChipsRow(
                            activeFilter = uiState.mediaFilter,
                            onFilterToggle = onFilterToggle,
                        )
                        if (uiState.filteredAssets.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Import video, photo, or audio above",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(uiState.filteredAssets, key = { it.id }) { asset ->
                                    MediaAssetRowCard(
                                        asset = asset,
                                        isSelected = uiState.selectedClipAsset?.id == asset.id,
                                        onSelect = { onInsertAsset(asset.id) },
                                        onDelete = { onDeleteAsset(asset.id) },
                                        actionLabel = "+ Track",
                                    )
                                }
                            }
                        }
                    }
                }

                InspectorTab.INSPECTOR -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        InspectorDetailsContent(
                            uiState = uiState,
                            project = project,
                            onSplitAtPlayhead = onSplitAtPlayhead,
                            onDuplicateClip = onDuplicateClip,
                            onRemoveSelectedClips = onRemoveSelectedClips,
                            onRippleDeleteClip = onRippleDeleteClip,
                            onNudgeTrimLeft = onNudgeTrimLeft,
                            onNudgeTrimRight = onNudgeTrimRight,
                            onJumpToClip = onJumpToClip,
                            onToggleTrackMute = onToggleTrackMute,
                            onToggleTrackLock = onToggleTrackLock,
                            onReorderTrack = onReorderTrack,
                            onRemoveTrack = onRemoveTrack,
                            onUpdateCanvas = onUpdateCanvas,
                        )
                    }
                }

                InspectorTab.TOOLS -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        QuickToolsOverviewContent(
                            project = project,
                            selectedClip = uiState.selectedClip,
                            selectedAsset = uiState.selectedClipAsset,
                            onAddTextClip = onAddTextClip,
                            onUpdateTextSpec = onUpdateTextSpec,
                            onPreviewTextSpec = onPreviewTextSpec,
                            onUpdateAdjustments = onUpdateAdjustments,
                            onPreviewAdjustments = onPreviewAdjustments,
                            onApplyFilterToAll = onApplyFilterToAll,
                            onUpdateTransition = onUpdateTransition,
                            onPreviewTransition = onPreviewTransition,
                            onUpdateSpeed = onUpdateSpeed,
                            onUpdateAudio = onUpdateAudio,
                            onPreviewAudio = onPreviewAudio,
                            onCommitSliderEdit = onCommitSliderEdit,
                            onUpdateCanvas = onUpdateCanvas,
                        )
                    }
                }

                InspectorTab.CANVAS -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CanvasSpecsEditor(
                            project = project,
                            onUpdateCanvas = onUpdateCanvas,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PortraitBottomToolbar(
    mediaCount: Int,
    selectedClipIds: Set<String>,
    selectedClip: TimelineClipRef?,
    canSplitAtPlayhead: Boolean,
    onOpenMediaSheet: () -> Unit,
    onOpenInspectorSheet: () -> Unit,
    onOpenQuickTool: (QuickToolSheet) -> Unit,
    onSplitAtPlayhead: () -> Unit,
    onDuplicateClip: () -> Unit,
    onRippleDeleteClip: () -> Unit,
    onRemoveSelectedClips: () -> Unit,
) {
    val studio = LocalStudioColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, studio.hairlineBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onOpenMediaSheet,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_media_bin_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.Default.PermMedia, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Media ($mediaCount)")
            }

            OutlinedButton(
                onClick = onSplitAtPlayhead,
                enabled = canSplitAtPlayhead,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_split_button"),
            ) {
                Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Split")
            }

            if (selectedClip != null) {
                OutlinedButton(
                    onClick = onDuplicateClip,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("toolbar_duplicate_button"),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Duplicate")
                }

                if (selectedClipIds.size == 1) {
                    OutlinedButton(
                        onClick = onRippleDeleteClip,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("toolbar_ripple_delete_button"),
                    ) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = "Ripple delete",
                            tint = studio.audioAmber,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Ripple", color = studio.audioAmber)
                    }
                }

                OutlinedButton(
                    onClick = onRemoveSelectedClips,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("toolbar_remove_clip_button"),
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Delete selected",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (selectedClipIds.size > 1) "Delete (${selectedClipIds.size})" else "Delete",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.TEXT) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_text_button"),
            ) {
                Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Text")
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.FILTERS) },
                enabled = selectedClip != null,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_filter_button"),
            ) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Filters")
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.TRANSITIONS) },
                enabled = selectedClip != null,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_transition_button"),
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Transition")
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.SPEED) },
                enabled = selectedClip != null,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_speed_button"),
            ) {
                Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Speed")
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.AUDIO) },
                enabled = selectedClip != null,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_audio_button"),
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Audio")
            }

            OutlinedButton(
                onClick = { onOpenQuickTool(QuickToolSheet.RATIO) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_ratio_button"),
            ) {
                Icon(Icons.Default.AspectRatio, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Ratio")
            }

            OutlinedButton(
                onClick = onOpenInspectorSheet,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("toolbar_inspector_button"),
            ) {
                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (selectedClip != null) "Clip" else "Inspector")
            }
        }
    }
}

@Composable
private fun InspectorDetailsContent(
    uiState: ProjectWorkspaceUiState,
    project: ProjectDocument,
    onSplitAtPlayhead: () -> Unit,
    onDuplicateClip: () -> Unit,
    onRemoveSelectedClips: () -> Unit,
    onRippleDeleteClip: () -> Unit,
    onNudgeTrimLeft: (Long) -> Unit,
    onNudgeTrimRight: (Long) -> Unit,
    onJumpToClip: (TimelineClipRef) -> Unit,
    onToggleTrackMute: (String) -> Unit,
    onToggleTrackLock: (String) -> Unit,
    onReorderTrack: (String, Int) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onUpdateCanvas: (AspectRatioPreset, Int) -> Unit,
) {
    val selectedClip = uiState.selectedClip
    val selectedAsset = uiState.selectedClipAsset
    val selectedTrack = uiState.selectedTrack

    if (selectedClip != null) {
        StudioPanel(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (uiState.selectedClipIds.size > 1) {
                        "${uiState.selectedClipIds.size} Clips Selected"
                    } else {
                        "Selected Clip"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = selectedAsset?.displayName ?: "Clip ${selectedClip.clipId.take(6)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Start: ${TimeFormatting.formatTimecode(selectedClip.timelineStartMs, project.fps)} • Dur: ${TimeFormatting.formatDurationLabel(selectedClip.durationMs)} • In: ${TimeFormatting.formatDurationLabel(selectedClip.sourceStartMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = onSplitAtPlayhead,
                        enabled = uiState.canSplitAtPlayhead,
                    ) {
                        Text("Split")
                    }
                    OutlinedButton(onClick = onDuplicateClip) {
                        Text("Duplicate")
                    }
                    OutlinedButton(onClick = onRippleDeleteClip) {
                        Text("Ripple Del")
                    }
                    OutlinedButton(
                        onClick = onRemoveSelectedClips,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }

                Text(
                    text = "Precision Trim Nudge",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(onClick = { onNudgeTrimLeft(-250L) }) { Text("In -0.25s") }
                    OutlinedButton(onClick = { onNudgeTrimLeft(250L) }) { Text("In +0.25s") }
                    OutlinedButton(onClick = { onNudgeTrimRight(-250L) }) { Text("Out -0.25s") }
                    OutlinedButton(onClick = { onNudgeTrimRight(250L) }) { Text("Out +0.25s") }
                }

                OutlinedButton(onClick = { onJumpToClip(selectedClip) }) {
                    Text("Jump Playhead to Start")
                }
            }
        }
    }

    if (selectedTrack != null) {
        val sortedTracks = project.tracks.sortedBy { it.orderIndex }
        val trackIdx = sortedTracks.indexOfFirst { it.id == selectedTrack.id }
        TrackInspectorCard(
            track = selectedTrack,
            canMoveUp = trackIdx > 0,
            canMoveDown = trackIdx in 0 until sortedTracks.lastIndex,
            canRemove = project.tracks.size > 1,
            onToggleMute = { onToggleTrackMute(selectedTrack.id) },
            onToggleLock = { onToggleTrackLock(selectedTrack.id) },
            onMoveUp = { onReorderTrack(selectedTrack.id, -1) },
            onMoveDown = { onReorderTrack(selectedTrack.id, 1) },
            onRemoveTrack = { onRemoveTrack(selectedTrack.id) },
        )
    }

    CanvasSpecsEditor(project = project, onUpdateCanvas = onUpdateCanvas)
}

@Composable
private fun TrackInspectorCard(
    track: TimelineTrack,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    onToggleMute: () -> Unit,
    onToggleLock: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemoveTrack: () -> Unit,
) {
    StudioPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Track: ${track.name}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${track.type.label} • ${track.clips.size} clip(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = track.isMuted,
                    onClick = onToggleMute,
                    label = { Text(if (track.isMuted) "Muted" else "Active") },
                )
                FilterChip(
                    selected = track.isLocked,
                    onClick = onToggleLock,
                    label = { Text(if (track.isLocked) "Locked" else "Unlocked") },
                )
                OutlinedButton(onClick = onMoveUp, enabled = canMoveUp) { Text("Up") }
                OutlinedButton(onClick = onMoveDown, enabled = canMoveDown) { Text("Down") }
                if (canRemove) {
                    OutlinedButton(
                        onClick = onRemoveTrack,
                        enabled = !track.isLocked,
                    ) {
                        Text("Delete Track")
                    }
                }
            }
        }
    }
}

@Composable
private fun CanvasSpecsEditor(
    project: ProjectDocument,
    onUpdateCanvas: (AspectRatioPreset, Int) -> Unit,
) {
    StudioPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Project Canvas & Timebase",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AspectRatioPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = project.aspectRatio == preset,
                        onClick = { onUpdateCanvas(preset, project.fps) },
                        label = { Text(preset.label) },
                    )
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FrameRateOption.entries.forEach { option ->
                    FilterChip(
                        selected = project.fps == option.fps,
                        onClick = { onUpdateCanvas(project.aspectRatio, option.fps) },
                        label = { Text(option.label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspaceModeSwitcher(
    currentMode: EditorMode,
    onModeSelected: (EditorMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(studio.elevatedPanel)
            .border(1.dp, studio.hairlineBorder, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        EditorMode.entries.forEach { mode ->
            val selected = currentMode == mode
            val bg = when {
                !selected -> Color.Transparent
                mode == EditorMode.QUICK -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            }
            val fg = when {
                !selected -> MaterialTheme.colorScheme.onSurfaceVariant
                mode == EditorMode.QUICK -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSecondaryContainer
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(bg)
                    .clickable { onModeSelected(mode) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("workspace_mode_${mode.name.lowercase()}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = if (mode == EditorMode.QUICK) Icons.Default.Bolt else Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = if (mode == EditorMode.QUICK) "Quick" else "Adv",
                    style = MaterialTheme.typography.labelLarge,
                    color = fg,
                )
            }
        }
    }
}

@Composable
private fun ImportActionStrip(
    onImportVideo: () -> Unit,
    onImportImage: () -> Unit,
    onImportAudio: () -> Unit,
    onImportFile: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ImportButton(
            label = "Video",
            icon = Icons.Default.Movie,
            tint = MaterialTheme.colorScheme.primary,
            onClick = onImportVideo,
            testTag = "import_video_button",
        )
        ImportButton(
            label = "Photo",
            icon = Icons.Default.Image,
            tint = MaterialTheme.colorScheme.secondary,
            onClick = onImportImage,
            testTag = "import_image_button",
        )
        ImportButton(
            label = "Audio",
            icon = Icons.Default.AudioFile,
            tint = LocalStudioColors.current.audioAmber,
            onClick = onImportAudio,
            testTag = "import_audio_button",
        )
        ImportButton(
            label = "Files",
            icon = Icons.Default.FolderOpen,
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = onImportFile,
            testTag = "import_files_button",
        )
    }
}

@Composable
private fun ImportButton(
    label: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    testTag: String,
) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, LocalStudioColors.current.hairlineBorder),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag(testTag),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "Import $label",
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun MediaFilterChipsRow(
    activeFilter: MediaType?,
    onFilterToggle: (MediaType?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaType.entries.forEach { type ->
            FilterChip(
                selected = activeFilter == type,
                onClick = { onFilterToggle(type) },
                label = { Text(type.label) },
                modifier = Modifier.testTag("bin_filter_${type.name.lowercase()}"),
            )
        }
    }
}

@Composable
fun MediaAssetRowCard(
    asset: MediaAsset,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    actionLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val studio = LocalStudioColors.current
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else studio.hairlineBorder
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onSelect)
            .testTag("media_asset_${asset.id}"),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(studio.elevatedPanel),
                contentAlignment = Alignment.Center,
            ) {
                if (asset.type == MediaType.AUDIO) {
                    Icon(
                        imageVector = Icons.Default.AudioFile,
                        contentDescription = null,
                        tint = studio.audioAmber,
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    AsyncImage(
                        model = asset.playbackUri,
                        contentDescription = asset.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = asset.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MediaTypeBadge(type = asset.type)
                    if (asset.status != MediaStatus.READY) {
                        StudioBadge(
                            text = asset.status.label.uppercase(),
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                val specs = buildString {
                    if (asset.durationMs > 0L) {
                        append(TimeFormatting.formatDurationLabel(asset.durationMs))
                    }
                    if (asset.resolutionLabel.isNotEmpty()) {
                        if (isNotEmpty()) append(" • ")
                        append(asset.resolutionLabel)
                    }
                }
                Text(
                    text = specs.ifBlank { asset.mimeType },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (actionLabel != null) {
                StudioBadge(
                    text = actionLabel,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("delete_asset_${asset.id}"),
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Remove ${asset.displayName}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
