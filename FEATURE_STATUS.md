# CutLab Feature Status

## Implemented (Phases 1 – 4)
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
  - Timeline-synced preview monitor using Media3 `ExoPlayer` (TextureView surface) for video & audio layers and Coil for images, with Play/Pause, Jump Start/End, and `-1F` / `+1F` frame stepping.
- **Non-Destructive Timeline Engine (Phase 3)**:
  - Command-based **Undo / Redo** (`TimelineCommandHistory`, 60-step stack) persisted across edits to Room.
  - **Split**, **Trim In / Trim Out** (draggable handles + `±0.25s` precision nudge), **Drag-and-Drop Repositioning** across compatible tracks, **Magnetic Snapping** with gold snap guide, **Duplicate**, **Ripple Delete**, **Multi-Clip Delete**, and **Track Reordering**.
- **Quick Edit Engine (Phase 4)**:
  - **Real-Time Transitions**: `Cross Dissolve`, `Fade Black`, `Zoom`, `Slide Left`, `Slide Up`, `Wipe`, `Blur`, `Spin`, and `Glitch` with adjustable duration and easing curves evaluated on actual preview frames.
  - **Color Filters & Adjustments**: 8 curated presets (`Original`, `Cinematic`, `Vivid`, `Warm Film`, `Cool Slate`, `Mono B&W`, `Dreamy`, `Cyber Neon`) plus `Brightness`, `Contrast`, `Saturation`, `Temperature`, `Vignette`, and `Soft Blur` sliders, plus Apply-to-All.
  - **Text & Kinetic Captions**: First-class `TEXT` clips with 6 style presets (`Bold Title`, `Subtitle`, `Neon Pop`, `Badge Pill`, `Cinema`, `Mono Code`), 6 animation presets (`Static`, `Fade`, `Slide Up`, `Pop`, `Typewriter`, `Glitch`), color/pill/outline controls, scaling, alignment, and vertical positioning rendered live over preview frames.
  - **Speed & Audio Controls**: Non-destructive clip speed (`0.25x` – `4.0x`) with automatic non-overlapping timeline reflow and live ExoPlayer `PlaybackParameters`, plus per-clip volume, mute, fade-in, and fade-out envelopes.
  - **Quick Social Aspect Ratio Switcher**: One-tap switching (`9:16`, `16:9`, `1:1`, `4:5`, `21:9`) accessible directly from the Quick Edit bar.

## Planned (Phases 5+)
- OpenGL ES / GLSL multi-layer compositor, blend modes, masks, and adjustment layers (Phase 5).
- Keyframe & Bezier Graph Editor, audio waveform mixing, and dual-path MP4 export (`Media3 Transformer` + custom GL/MediaCodec pipeline) (Phases 6–8).
- Advanced tools (speed ramping, LUTs, chroma key, motion tracking, proxy workflow) and release optimization (Phases 9–10).
