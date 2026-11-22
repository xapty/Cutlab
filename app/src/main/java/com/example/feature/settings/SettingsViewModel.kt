package com.example.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.database.CutLabPreferencesRepository
import com.example.core.database.StudioPreferences
import com.example.engine.project.AspectRatioPreset
import com.example.engine.project.EditorMode
import com.example.engine.project.ProjectRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val preferences: StudioPreferences = StudioPreferences(),
    val managedStorageBytes: Long = 0L,
)

class SettingsViewModel(
    private val preferencesRepository: CutLabPreferencesRepository,
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    private val storageBytes = MutableStateFlow(0L)
    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()

    init {
        refreshStorageFootprint()
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        preferencesRepository.preferencesFlow,
        storageBytes,
    ) { prefs, bytes ->
        SettingsUiState(preferences = prefs, managedStorageBytes = bytes)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun refreshStorageFootprint() {
        viewModelScope.launch {
            storageBytes.value = projectRepository.getManagedStorageFootprintBytes()
        }
    }

    fun setDefaultAspectRatio(preset: AspectRatioPreset) {
        viewModelScope.launch { preferencesRepository.setDefaultAspectRatio(preset) }
    }

    fun setDefaultFps(fps: Int) {
        viewModelScope.launch { preferencesRepository.setDefaultFps(fps) }
    }

    fun setDefaultEditorMode(mode: EditorMode) {
        viewModelScope.launch { preferencesRepository.setDefaultEditorMode(mode) }
    }

    fun setAutoAppendImported(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setAutoAppendImported(enabled) }
    }

    fun pruneOrphanedMedia() {
        viewModelScope.launch {
            val pruned = projectRepository.pruneOrphanedMedia()
            refreshStorageFootprint()
            _messages.emit(
                if (pruned > 0) "Cleaned $pruned orphaned media file(s)" else "Storage is already clean",
            )
        }
    }

    companion object {
        fun factory(
            preferencesRepository: CutLabPreferencesRepository,
            projectRepository: ProjectRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SettingsViewModel(preferencesRepository, projectRepository) as T
            }
        }
    }
}
