# CutLab Project Architecture

## Overview
CutLab is a native Android video editor built with Kotlin, Jetpack Compose, Material 3, Room, DataStore, Jetpack Media3 (`ExoPlayer` + `Transformer`), and a custom OpenGL ES 2.0 + GLSL + `MediaCodec` + `MediaMuxer` rendering pipeline. It separates UI workspaces and ViewModels from non-destructive project models, timeline commands, GPU shaders, DSP audio, and export orchestration.

## Package Structure (`com.example`)
Layers follow Clean Architecture + MVVM with strict dependency direction (`feature -> engine -> core`):

- `core/common`: Frame-accurate `RationalTime`, timecode formatting, and file size formatters.
- `core/designsystem` & `ui/theme`: Obsidian dark studio theme (`CutLabTheme`), `Space Grotesk` / `Inter` / `JetBrains Mono` typography, and reusable studio panels/badges.
- `core/database`: Room `CutLabDatabase` v2 (`projects`, `media_assets`, `MIGRATION_1_2`), reactive DAOs, and DataStore `CutLabPreferencesRepository` (aspect ratio, FPS, editor mode, proxy toggle, preview quality mode).
- `core/storage`: `MediaStorageManager` for streaming 64KB buffered import copies into app-private storage (`filesDir/cutlab_media/<projectId>/`), orphan pruning, and storage diagnostics.
- `core/media`: `MediaMetadataExtractor` (`MediaMetadataRetriever` + bounds-only `BitmapFactory`) and `ProxyMediaManager` (Media3 Transformer 540p H.264 proxy transcoding).
- `core/graphics`: `CompositorShaders` and `GlCompositorRenderer` (EGL14 window context, ping-pong FBO compositing, dual `SurfaceTexture` OES video decoders, 2D-strip 3D LUT sampling, YCbCr chroma key, SDF alpha masks, LRU texture caches, adaptive preview FBO scaling, and memory trim handlers).
- `core/performance`: `DevicePerformanceMonitor` (API 29+ thermal listener with API 28 fallback, JVM heap monitor, `onTrimMemory` dispatch, GL frame latency tracker, crash diagnostic recorder, and persistent pipeline/export benchmark history).
- `engine/project`: Versioned `@Serializable` `ProjectDocument` schema (`schemaVersion = 1`), `ProjectEngine`, and `ProjectRepository`.
- `engine/timeline`: `TimelineEngine`, `TimelineCommandHistory` (60-step Undo/Redo), and `SpeedRampEngine` (piecewise-linear integral source-time solver and reverse timing).
- `engine/compositor`: `CompositorEngine` (z-order visual stack evaluation, active transition timing, cascaded adjustment layers).
- `engine/animation`: `AnimationEngine` (Cubic Bezier Newton-Raphson solver, overshoot/bounce presets, safe descriptors) and `MotionTrackingEngine` (SAD optical patch tracker + median-consensus trajectory stabilization).
- `engine/effects`: `EffectsEngine` (color matrices/LUT shaders) and `Lut3DEngine` (Adobe `.cube` parser, 7 procedural cinema LUTs, trilinear resampling, `256x16` RGBA GL strip packer).
- `engine/audio`: `PcmAudioExtractor`, `AudioDsp` (biquad EQ, echo, equal-power pan), `MultiTrackAudioMixer` (sample-rate conversion, keyframed volume + fades + soft clipping), and `MixedAudioPreviewPlayer`.
- `engine/export`: Dual-path `ExportPlanner`, `CutLabExportCoordinator`, `Media3TransformerExporter` (Path A), `CustomGlVideoExporter` (Path B EGL offscreen GL + H.264/H.265 `MediaCodec` + AAC + `MediaMuxer`), and `ExportOutputManager` (MP4 container validation, Gallery/SAF delivery, stale temp cleanup).
- `feature/home`: Project library (`HomeScreen`, `HomeViewModel`, `CreateProjectDialog`).
- `feature/project`: Adaptive Portrait & Landscape editor (`ProjectWorkspaceScreen`, `ProjectWorkspaceViewModel`), Quick & Advanced panels (`QuickEditPanels`, `AdvancedCompositorPanels`, `AnimationPanels`, `AudioPanels`, `Phase9AdvancedPanels`), and `StudioMediaPlayer` (`TimelinePreviewMonitor`).
- `feature/timeline`: Multi-track interactive `TimelineEditorPane` with drag/trim/snap/zoom, waveform bars, beat markers, and keyframe diamond overlays.
- `feature/export`: `ExportStudioSheet` (hardware capability check, Path A/B plan preview, progress, cancellation, Gallery save & Share).
- `feature/mediaimport` & `feature/settings`: Global Media Vault (`MediaVaultScreen`) and Studio Settings & Telemetry/Benchmark Console (`SettingsScreen`, `SettingsViewModel`).
