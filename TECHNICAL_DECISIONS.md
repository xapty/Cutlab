# CutLab Technical Decisions

1. **Zero-Permission Media Access + Managed Local Copies**:
   - CutLab uses `ActivityResultContracts.PickMultipleVisualMedia` (Photo Picker) for video/photos and `OpenMultipleDocuments` (SAF) for audio/files, avoiding broad storage permissions (`READ_EXTERNAL_STORAGE`, `READ_MEDIA_*`) for Google Play compliance.
   - Because Photo Picker URIs are temporary by default, `MediaStorageManager` streams imported media into app-private storage (`filesDir/cutlab_media/<projectId>/`) on `Dispatchers.IO` with a 64KB buffer so projects always reopen intact after restarts.

2. **Hybrid Dual-Path Rendering Strategy (Verified against Media3 1.6.1)**:
   - Official Media3 documentation confirms `CompositionPlayer` is `@ExperimentalApi` and currently focuses on single video + audio sequences, with multi-asset PiP/layering under active development.
   - Therefore, Phase 1 uses Media3 `ExoPlayer` for single-asset preview inspection, while Phases 5–8 will pair Media3 `Transformer` (Path A) for single-track/supported exports with a custom OpenGL ES + GLSL + `MediaCodec` + `MediaMuxer` pipeline (Path B) for multi-layer compositions, masks, blend modes, and custom shaders.

3. **Versioned Non-Destructive Project Schema**:
   - `ProjectDocument` stores rational/integer millisecond timing, canvas dimensions, frame rate, editor mode (`QUICK` / `ADVANCED`), and track definitions as versioned JSON in Room (`schemaVersion = 1`), ensuring both Quick Edit and Advanced Edit share one source of truth.

4. **Lightweight Constructor Injection**:
   - `AppContainer` in `CutLabApplication` provides constructor-injected dependencies without heavy annotation-processor build overhead, keeping incremental Gradle compilation fast while preserving clean testability.
