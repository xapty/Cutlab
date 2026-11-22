package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory

class CutLabApplication : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader = container.imageLoader
}
