package com.narchives.reader.ui.screen.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.narchives.reader.data.preferences.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class SettingsUiState(
    val theme: String = "system",
    val readerFontSize: Int = 18,
    val readerFont: String = "serif",
    val waczCacheLimitMb: Int = 500,
    val cacheUsageMb: Int = 0,
)

class SettingsViewModel(
    private val userPreferences: UserPreferences,
    private val cacheDir: File,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val theme = userPreferences.theme.first()
            val fontSize = userPreferences.readerFontSize.first()
            val font = userPreferences.readerFont.first()
            val cacheLimit = userPreferences.waczCacheLimitMb.first()
            val cacheUsage = calculateCacheUsage()

            _uiState.update {
                it.copy(
                    theme = theme,
                    readerFontSize = fontSize,
                    readerFont = font,
                    waczCacheLimitMb = cacheLimit,
                    cacheUsageMb = cacheUsage,
                )
            }
        }
    }

    fun setTheme(theme: String) {
        viewModelScope.launch {
            userPreferences.setTheme(theme)
            _uiState.update { it.copy(theme = theme) }
        }
    }

    fun setReaderFontSize(size: Int) {
        viewModelScope.launch {
            userPreferences.setReaderFontSize(size)
            _uiState.update { it.copy(readerFontSize = size) }
        }
    }

    fun setReaderFont(font: String) {
        viewModelScope.launch {
            userPreferences.setReaderFont(font)
            _uiState.update { it.copy(readerFont = font) }
        }
    }

    fun setCacheLimit(limitMb: Int) {
        viewModelScope.launch {
            userPreferences.setWaczCacheLimitMb(limitMb)
            _uiState.update { it.copy(waczCacheLimitMb = limitMb) }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            val waczCacheDir = File(cacheDir, "wacz")
            waczCacheDir.deleteRecursively()
            _uiState.update { it.copy(cacheUsageMb = 0) }
        }
    }

    private fun calculateCacheUsage(): Int {
        val waczCacheDir = File(cacheDir, "wacz")
        if (!waczCacheDir.exists()) return 0
        val bytes = waczCacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return (bytes / (1024 * 1024)).toInt()
    }
}
