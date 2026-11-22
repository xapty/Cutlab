# CutLab Feature Status

## Implemented (Phases 1 – 7)
- **Platform Compatibility**: Configured for **Android 9 Pie (API 28)** minimum SDK through Android 16 (API 36).
- **Project Management**: Create, open, rename, duplicate, switch Quick/Advanced mode without data loss, delete, search, filter by mode and aspect ratio.
- **Persistent Schema**: Versioned `ProjectDocument` (`schemaVersion = 1`) stored in Room alongside indexed `MediaAssetEntity` rows and DataStore studio defaults.
- **Media Import (Zero-Permission)**:
  - Video & Photo import via Android Photo Picker (`PickMultipleVisualMedia`).
  - Audio & general media import via Storage Access Framework (`OpenMultipleDocuments`).
  - Streaming copy to app-managed storage so imported Photo Picker assets persist across app restarts.
  - Real metadata extraction (`MediaMetadataRetriever` + `BitmapFactory` bounds) with graceful rejection of corrupted/unsupported files.
- **Adaptive Editor Workspace (Phase 2)**:
  - Portrait layout (preview monitor, interactive multi-track timeline, contextual bottom toolbar, Media Bin, Quick Tools & Inspector sheets) and Landscape layout (preview monitor + tabbed side inspector + expanded multi-track timeline) preserving state across orientation changes.
  - Interactive timeline ruler scrubbing, pinch/button zoom, track headers with mute/visibility and lock controls, adding tracks (`Video`, `Overlay`, `Text`, `Audio`, `Adjustment`).
  - Timeline-synced preview monitor with Play/Pause, Jump Start/End, and `-1F` / `+1F` frame stepping.
- **Non-Destructive Timeline Engine (Phase 3)**:
  - Command-based **Undo / Redo** (`TimelineCommandHistory`, 60-step stack) persisted across edits to Room.
  - **Split**, **Trim In / Trim Out** (draggable handles + `±0.25s` precision nudge), **Drag-and-Drop Repositioning** across compatible tracks, **Magnetic Snapping** with gold snap guide, **Duplicate**, **Ripple Delete**, **Multi-Clip Delete**, and **Track Reordering**.
- **Quick Edit Engine (Phase 4)**:
  - **Real-Time Transitions**: `Cross Dissolve`, `Fade Black`, `Zoom`, `Slide Left`, `Slide Up`, `Wipe`, `Blur`, `Spin`, and `Glitch` with adjustable duration and easing curves evaluated on actual preview frames.
  - **Color Filters & Adjustments**: 8 curated presets (`Original`, `Cinematic`, `Vivid`, `Warm Film`, `Cool Slate`, `Mono B&W`, `Dreamy`, `Cyber Neon`) plus `Brightness`, `Contrast`, `Saturation`, `Temperature`, `Tint`, `Vignette`, and `Soft Blur` sliders, plus Apply-to-All.
  - **Text & Kinetic Captions**: First-class `TEXT` clips with 6 style presets, 6 animation presets, color/pill/outline controls, scaling, alignment, and vertical positioning rendered live over preview frames.
  - **Speed & Audio Controls**: Non-destructive clip speed (`0.25x` – `4.0x`) with automatic non-overlapping timeline reflow and live ExoPlayer `PlaybackParameters`, plus per-clip volume, mute, fade-in, and fade-out envelopes.
  - **Quick Social Aspect Ratio Switcher**: One-tap switching (`9:16`, `16:9`, `1:1`, `4:5`, `21:9`).
- **Advanced Compositor Engine (Phase 5)**:
  - **OpenGL ES 2.0 + GLSL Multi-Layer Pipeline**: Dedicated `GlCompositorRenderer` with ping-pong FBO compositing, two concurrent `SurfaceTexture` OES hardware ExoPlayer video decoders, LRU downsampled `Bitmap` image texture caching, and `CompositorEngine` layer z-order resolution.
  - **Layer Transforms**: Real-time `Position X/Y`, `Scale X/Y` (with Lock XY toggle), `Rotation`, and `Opacity` for visual and text layers.
  - **Blend Modes**: `Normal`, `Add`, `Multiply`, `Screen`, `Overlay`, `Darken`, and `Lighten` evaluated in GLSL fragment shaders and Compose text layer blending.
  - **SDF Alpha Masks**: `Rectangle` (with rounded corners), `Ellipse`, and `Linear Split` masks with live `Center X/Y`, `Width/Height`, `Rotation`, `Feather`, and `Invert` toggle.
  - **GLSL Fragment Shaders**: `Chromatic Aberration`, `RGB Split`, `Film Grain`, `Bloom Glow`, `VHS Scanlines`, `Pixelate`, and `Crisp Edge` with real-time intensity controls.
  - **Adjustment Layers**: First-class `ADJUSTMENT` clips (`TrackType.ADJUSTMENT`) that cascade color grades, tint, and GLSL shader effects onto all visual layers underneath in timeline stack order.
- **Animation & Motion Graphics Engine (Phase 6)**:
  - **Multi-Property Keyframing**: Clip-relative keyframes for `Position X/Y`, `Scale X/Y`, `Rotation`, `Opacity`, `Anchor X/Y`, `Volume`, `Blur`, `Shader Intensity`, `Mask Feather`, and `Text Font Scale`, evaluated consistently across OpenGL layers, text overlays, and audio playback, and preserved across timeline split, trim, and speed changes.
  - **Interpolation & Cubic Bezier Solver**: Supports `Linear`, `Ease In`, `Ease Out`, `Ease In-Out`, `Hold`, and Newton-Raphson + binary-search `Cubic Bezier` evaluation (with overshoot curve support).
  - **Interactive Graph Editor**: Zoomable (`1.0x`–`4.0x`) & pannable curve canvas with draggable keyframe diamonds, draggable Bezier `P1`/`P2` tangent handles, multi-keyframe selection & editing, keyframe jump controls (`<` / `>`), and timeline diamond markers.
  - **Motion Graphics Presets & Safe Expressions**: One-tap `Overshoot`, `Bounce`, `Elastic`, `Shake`, `Smooth Zoom`, `Camera Pan`, `Fade In/Out`, and `Slide In` generators backed by `SafePropertyExpression` descriptors.
- **Audio Engine (Phase 7)**:
  - **Streaming PCM Waveform & Beat Extraction**: Background `MediaExtractor` + `MediaCodec` PCM peak envelope extraction (`PcmAudioExtractor`) cached to disk, dynamically sliced per trimmed/sped-up clip, and rendered directly on Audio/Video timeline clips and the Audio inspector canvas.
  - **Multi-Track Synchronization & PCM Mixing**: Shared-clock 4-channel synchronized `ExoPlayer` audio playback (`StudioMediaPlayer`) plus sample-rate-converted stereo PCM mixing (`MultiTrackAudioMixer`) with keyframed volume automation, fade-in/out envelopes, soft limiting, and hardware `AudioTrack` PCM audition (`MixedAudioPreviewPlayer`).
  - **Audio FX, EQ & Stereo Pan**: Biquad Low/High Shelf EQ, Vocal Clear / Radio / Studio Punch presets, 220ms delay Echo Mix, and equal-power Stereo Panning (`AudioDsp`).
  - **Multi-Track Mixer & Beat Markers**: Live master L/R peak meters, per-track mute & peak bars, manual `+ Beat @ Playhead` markers, automatic transient beat detection, and magnetic timeline snapping to beat markers.

## Planned (Phases 8+)
- Dual-path MP4 export (`Media3 Transformer` + custom GL/MediaCodec/MediaMuxer pipeline) (Phase 8).
- Advanced tools (speed ramping, LUTs, chroma key, motion tracking, proxy workflow) and release optimization (Phases 9–10).
