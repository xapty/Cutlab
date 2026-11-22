# CutLab Project Architecture

## Overview
CutLab is a native Android video editor built with Kotlin, Jetpack Compose, Material 3, Room, DataStore, and Jetpack Media3. It separates UI screens and ViewModels from non-destructive project models, storage management, and media processing.

## Current Package Boundaries (Phase 1 Foundation)
Inside `:app` (`com.example`), layers follow Clean Architecture + MVVM with strict dependency direction (`feature -> engine -> core`):

- `core/common`: Frame-accurate `RationalTime`, timecode formatting, and file size formatters.
- `core/designsystem` & `ui/theme`: Obsidian dark studio theme (`CutLabTheme`), `Space Grotesk` / `Inter` / `JetBrains Mono` typography, and reusable studio panels/badges.
- `core/database`: Room `CutLabDatabase` (`projects`, `media_assets`), DAOs returning reactive `Flow`, and DataStore `CutLabPreferencesRepository`.
- `core/storage`: `MediaStorageManager` for streaming 64KB buffered import copies into app-private storage (`filesDir/cutlab_media/<projectId>/`), orphan pruning, and footprint diagnostics.
- `core/media`: `MediaMetadataExtractor` using `MediaMetadataRetriever` and bounds-only `BitmapFactory` for video, image, and audio inspection.
- `engine/project`: Versioned `@Serializable` `ProjectDocument` schema (`schemaVersion = 1`), `ProjectEngine` (non-destructive project creation, mode switching, duplication, foundation track assignment), and `ProjectRepository`.
- `feature/home`: Project library (`HomeScreen`, `HomeViewModel`, `CreateProjectDialog`) with search, mode/aspect-ratio filtering, rename, duplicate, and delete.
- `feature/project`: Project workspace (`ProjectWorkspaceScreen`, `ProjectWorkspaceViewModel`, `StudioAssetMonitor`) with adaptive portrait/landscape layouts, Quick/Advanced mode toggle, Photo Picker & SAF media import, and Media3 `ExoPlayer` asset playback.
- `feature/mediaimport`: Global Media Vault (`MediaVaultScreen`, `MediaVaultViewModel`) for cross-project asset browsing and metadata inspection.
- `feature/settings`: Studio defaults (`SettingsScreen`, `SettingsViewModel`) and storage cleanup.

## Future Gradle Multi-Module Extraction Path
When timeline editing, GLSL compositing, and hardware export grow in Phases 2–8, the package namespaces above map 1:1 to standalone Gradle modules (`:core:common`, `:core:database`, `:engine:project-engine`, `:engine:timeline-engine`, `:engine:compositor-engine`, `:engine:export-engine`).
