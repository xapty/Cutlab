package com.example

import android.content.Context
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import com.example.core.database.CutLabDatabase
import com.example.core.database.CutLabPreferencesRepository
import com.example.core.media.MediaMetadataExtractor
import com.example.core.storage.MediaStorageManager
import com.example.engine.audio.MixedAudioPreviewPlayer
import com.example.engine.audio.MultiTrackAudioMixer
import com.example.engine.audio.PcmAudioExtractor
import com.example.engine.export.CustomGlVideoExporter
import com.example.engine.export.CutLabExportCoordinator
import com.example.engine.export.ExportOutputManager
import com.example.engine.export.ExportPlanner
import com.example.engine.export.Media3TransformerExporter
import com.example.engine.project.ProjectEngine
import com.example.engine.project.ProjectRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: CutLabDatabase by lazy { CutLabDatabase.getInstance(appContext) }
    val preferencesRepository: CutLabPreferencesRepository by lazy {
        CutLabPreferencesRepository(appContext)
    }
    val storageManager: MediaStorageManager by lazy { MediaStorageManager(appContext) }
    val metadataExtractor: MediaMetadataExtractor by lazy {
        MediaMetadataExtractor(storageManager)
    }
    val pcmAudioExtractor: PcmAudioExtractor by lazy { PcmAudioExtractor(appContext) }
    val mixedAudioPreviewPlayer: MixedAudioPreviewPlayer by lazy { MixedAudioPreviewPlayer() }
    val projectEngine: ProjectEngine by lazy { ProjectEngine() }
    val projectRepository: ProjectRepository by lazy {
        ProjectRepository(
            projectDao = database.projectDao(),
            mediaAssetDao = database.mediaAssetDao(),
            projectEngine = projectEngine,
            storageManager = storageManager,
            metadataExtractor = metadataExtractor,
        )
    }
    val exportPlanner: ExportPlanner by lazy { ExportPlanner() }
    val exportOutputManager: ExportOutputManager by lazy { ExportOutputManager(appContext) }
    val exportCoordinator: CutLabExportCoordinator by lazy {
        CutLabExportCoordinator(
            exportPlanner = exportPlanner,
            outputManager = exportOutputManager,
            media3Exporter = Media3TransformerExporter(appContext),
            customGlExporter = CustomGlVideoExporter(
                context = appContext,
                pcmAudioExtractor = pcmAudioExtractor,
                audioMixer = MultiTrackAudioMixer(),
            ),
        )
    }
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(appContext)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}
