package com.example

import android.content.Context
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import com.example.core.database.CutLabDatabase
import com.example.core.database.CutLabPreferencesRepository
import com.example.core.media.MediaMetadataExtractor
import com.example.core.storage.MediaStorageManager
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
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(appContext)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}
