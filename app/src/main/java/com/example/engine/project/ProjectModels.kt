package com.example.engine.project

import kotlinx.serialization.Serializable

const val CURRENT_PROJECT_SCHEMA_VERSION = 1

@Serializable
enum class EditorMode(val label: String, val subtitle: String) {
    QUICK("Quick Edit", "Streamlined cuts, presets & social ratios"),
    ADVANCED("Advanced Edit", "Multi-track layers, keyframes & graph curves"),
}

@Serializable
enum class AspectRatioPreset(
    val label: String,
    val width: Int,
    val height: Int,
    val tag: String,
) {
    VERTICAL_9_16("9:16", 1080, 1920, "Reels / TikTok"),
    LANDSCAPE_16_9("16:9", 1920, 1080, "YouTube / AMV"),
    SQUARE_1_1("1:1", 1080, 1080, "Square Feed"),
    PORTRAIT_4_5("4:5", 1080, 1350, "Portrait Feed"),
    CINEMA_21_9("21:9", 2560, 1080, "Anamorphic"),
}

@Serializable
enum class FrameRateOption(val fps: Int, val label: String) {
    FPS_24(24, "24 FPS"),
    FPS_25(25, "25 FPS"),
    FPS_30(30, "30 FPS"),
    FPS_50(50, "50 FPS"),
    FPS_60(60, "60 FPS"),
}

@Serializable
enum class MediaType(val label: String) {
    VIDEO("Video"),
    IMAGE("Image"),
    AUDIO("Audio"),
}

@Serializable
enum class MediaStatus(val label: String) {
    READY("Ready"),
    MISSING("Missing"),
    UNSUPPORTED("Unsupported"),
}

@Serializable
enum class TrackType(val shortPrefix: String, val label: String) {
    VIDEO("V", "Video"),
    OVERLAY("OV", "Overlay"),
    TEXT("T", "Text"),
    AUDIO("A", "Audio"),
    ADJUSTMENT("ADJ", "Adjustment"),
}

@Serializable
enum class ClipKind {
    MEDIA,
    TEXT,
}

@Serializable
enum class TransitionType(val label: String) {
    NONE("None"),
    CROSS_DISSOLVE("Dissolve"),
    FADE_BLACK("Fade Black"),
    ZOOM_IN("Zoom"),
    SLIDE_LEFT("Slide Left"),
    SLIDE_UP("Slide Up"),
    WIPE_RIGHT("Wipe"),
    BLUR("Blur"),
    SPIN("Spin"),
    GLITCH("Glitch"),
}

@Serializable
enum class EasingPreset(val label: String) {
    LINEAR("Linear"),
    EASE_IN("Ease In"),
    EASE_OUT("Ease Out"),
    EASE_IN_OUT("Ease In-Out"),
}

@Serializable
data class ClipTransition(
    val type: TransitionType = TransitionType.CROSS_DISSOLVE,
    val durationMs: Long = 500L,
    val easing: EasingPreset = EasingPreset.EASE_IN_OUT,
)

@Serializable
enum class FilterPreset(val label: String) {
    NONE("Original"),
    CINEMATIC("Cinematic"),
    VIVID("Vivid"),
    WARM_VINTAGE("Warm Film"),
    COOL_SLATE("Cool Slate"),
    MONO("Mono B&W"),
    DREAMY("Dreamy"),
    CYBER_NEON("Cyber Neon"),
}

@Serializable
data class ClipAdjustments(
    val filterPreset: FilterPreset = FilterPreset.NONE,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val exposure: Float = 0f,
    val temperature: Float = 0f,
    val vignette: Float = 0f,
    val blurRadius: Float = 0f,
    val sharpen: Float = 0f,
) {
    val isDefault: Boolean
        get() = filterPreset == FilterPreset.NONE &&
            brightness == 0f &&
            contrast == 1f &&
            saturation == 1f &&
            exposure == 0f &&
            temperature == 0f &&
            vignette == 0f &&
            blurRadius == 0f &&
            sharpen == 0f
}

@Serializable
enum class TextStylePreset(val label: String) {
    BOLD_TITLE("Bold Title"),
    MINIMAL_SUBTITLE("Subtitle"),
    NEON_POP("Neon Pop"),
    KINETIC_BADGE("Badge Pill"),
    CINEMA_SERIF("Cinema"),
    MONO_TYPEWRITER("Mono Code"),
}

@Serializable
enum class TextAnimationPreset(val label: String) {
    NONE("Static"),
    FADE_IN("Fade"),
    SLIDE_UP("Slide Up"),
    POP_OVERSHOOT("Pop"),
    TYPEWRITER("Typewriter"),
    GLITCH("Glitch"),
}

@Serializable
enum class TextAlignMode(val label: String) {
    LEFT("Left"),
    CENTER("Center"),
    RIGHT("Right"),
}

@Serializable
data class TextOverlaySpec(
    val text: String = "Your Title",
    val stylePreset: TextStylePreset = TextStylePreset.BOLD_TITLE,
    val animationPreset: TextAnimationPreset = TextAnimationPreset.POP_OVERSHOOT,
    val fontScale: Float = 1.0f,
    val textColorArgb: Long = 0xFFFFFFFF,
    val backgroundColorArgb: Long = 0x00000000,
    val hasShadow: Boolean = true,
    val hasStroke: Boolean = false,
    val alignment: TextAlignMode = TextAlignMode.CENTER,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

@Serializable
data class TimelineClipRef(
    val clipId: String,
    val assetId: String,
    val trackId: String,
    val timelineStartMs: Long,
    val sourceStartMs: Long,
    val durationMs: Long,
    val speed: Float = 1.0f,
    val volume: Float = 1.0f,
    val sourceTotalDurationMs: Long = 0L,
    val clipKind: ClipKind = ClipKind.MEDIA,
    val isMuted: Boolean = false,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val transitionIn: ClipTransition? = null,
    val adjustments: ClipAdjustments = ClipAdjustments(),
    val textSpec: TextOverlaySpec? = null,
)

@Serializable
data class TimelineTrack(
    val id: String,
    val name: String,
    val type: TrackType,
    val orderIndex: Int,
    val isMuted: Boolean = false,
    val isLocked: Boolean = false,
    val clips: List<TimelineClipRef> = emptyList(),
)

@Serializable
data class ExportSettingsSpec(
    val resolutionHeight: Int = 1080,
    val fps: Int = 30,
    val codec: String = "H.264",
    val audioBitrateKbps: Int = 192,
)

@Serializable
data class ProjectDocument(
    val schemaVersion: Int = CURRENT_PROJECT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val aspectRatio: AspectRatioPreset,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val fps: Int,
    val editorMode: EditorMode,
    val tracks: List<TimelineTrack>,
    val exportSettings: ExportSettingsSpec,
    val createdAt: Long,
    val updatedAt: Long,
)

data class MediaAsset(
    val id: String,
    val projectId: String?,
    val type: MediaType,
    val displayName: String,
    val sourceUri: String,
    val managedFilePath: String?,
    val mimeType: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Float,
    val bitrate: Long,
    val fileSizeBytes: Long,
    val status: MediaStatus,
    val importedAt: Long,
) {
    val playbackUri: String
        get() = managedFilePath?.let { "file://$it" } ?: sourceUri

    val resolutionLabel: String
        get() = if (width > 0 && height > 0) "${width}×${height}" else ""
}

data class ProjectSummary(
    val document: ProjectDocument,
    val mediaCount: Int,
    val videoCount: Int,
    val imageCount: Int,
    val audioCount: Int,
    val totalMediaDurationMs: Long,
    val coverMediaUri: String?,
)
