# CutLab Technical Decisions

1. **Zero-Permission Media Access + Managed Local Copies**:
   - CutLab uses `ActivityResultContracts.PickMultipleVisualMedia` (Photo Picker) for video/photos and `OpenMultipleDocuments` / `OpenDocument` (SAF) for audio and `.cube` LUT files, avoiding broad storage permissions (`READ_EXTERNAL_STORAGE`, `READ_MEDIA_*`) for Google Play policy compliance.
   - Because Photo Picker URIs are temporary by default, `MediaStorageManager` streams imported media into app-private storage (`filesDir/cutlab_media/<projectId>/`) on `Dispatchers.IO` with a 64KB buffer so projects reopen reliably across restarts.

2. **Hybrid Dual-Path Rendering Strategy (Media3 Transformer + Custom GL/MediaCodec)**:
   - Sequential single-track timelines with compatible transforms/effects route to **Path A (`Media3TransformerExporter`)**.
   - Multi-track compositing, animated Bezier keyframes, blend modes, SDF masks, chroma key, 3D LUTs, custom GLSL shaders, cascading adjustment layers, text layers, speed ramping/reverse playback, and multi-track DSP audio automatically route to **Path B (`CustomGlVideoExporter`)** built on EGL14, OpenGL ES 2.0, `MediaCodec`, `MultiTrackAudioMixer`, and `MediaMuxer`. Auto fallback to Path B also triggers if Media3 rejects a source container/codec.

3. **Versioned Non-Destructive Project Schema & Unified Editing Core**:
   - `ProjectDocument` stores integer millisecond timeline offsets, canvas dimensions, FPS, editor mode (`QUICK` / `ADVANCED`), tracks, keyframes, LUTs, speed ramps, and beat markers in Room v2 (`CutLabDatabase`), so switching between Quick Edit and Advanced Edit never loses project state.

4. **Shared Composition & Animation Evaluators**:
   - Live GL preview (`GlCompositorRenderer` + `StudioMediaPlayer`) and offline Path B export (`CustomGlVideoExporter`) share the exact same `CompositorEngine`, `AnimationEngine`, `EffectsEngine`, `Lut3DEngine`, `SpeedRampEngine`, and `CompositorShaders` code to maintain preview-to-export fidelity.

5. **Adaptive Thermal & Memory Scaling (API 28–36)**:
   - `DevicePerformanceMonitor` uses `PowerManager` thermal status listeners on API 29+ (with a clean `NOMINAL` fallback on Android 9 / API 28) combined with `Application.onTrimMemory` callbacks to evict Coil/waveform/GL texture caches and downscale live preview FBO resolution (`100%` -> `75%` -> `50%`) without degrading full-resolution export quality.
