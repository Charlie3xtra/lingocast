package com.lingocast.app.data.prefs

import android.content.Context
import com.lingocast.app.ui.theme.ThemeModePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SubtitleDisplayMode(val labelZh: String) {
    BILINGUAL("中英双语"),
    ENGLISH_ONLY("仅英文"),
    CHINESE_ONLY("仅中文"),
    BLIND("盲听模式")
}

enum class TtsAccent(val labelZh: String, val tag: String) {
    UK("英音 UK", "en-GB"),
    US("美音 US", "en-US")
}

data class UserPreferences(
    val themeMode: ThemeModePreference = ThemeModePreference.SYSTEM,
    val dynamicColor: Boolean = true,
    val defaultSubMode: SubtitleDisplayMode = SubtitleDisplayMode.BILINGUAL,
    val ttsAccent: TtsAccent = TtsAccent.UK,
    val lastSyncHost: String = "192.168.31.100:8765"
)

class PreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("lingocast_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<UserPreferences> = _state.asStateFlow()

    private fun load(): UserPreferences {
        val theme = runCatching {
            ThemeModePreference.valueOf(prefs.getString("theme_mode", ThemeModePreference.SYSTEM.name)!!)
        }.getOrDefault(ThemeModePreference.SYSTEM)
        val sub = runCatching {
            SubtitleDisplayMode.valueOf(prefs.getString("sub_mode", SubtitleDisplayMode.BILINGUAL.name)!!)
        }.getOrDefault(SubtitleDisplayMode.BILINGUAL)
        val accent = runCatching {
            TtsAccent.valueOf(prefs.getString("tts_accent", TtsAccent.UK.name)!!)
        }.getOrDefault(TtsAccent.UK)
        return UserPreferences(
            themeMode = theme,
            dynamicColor = prefs.getBoolean("dynamic_color", true),
            defaultSubMode = sub,
            ttsAccent = accent,
            lastSyncHost = prefs.getString("sync_host", "192.168.31.100:8765") ?: "192.168.31.100:8765"
        )
    }

    fun setThemeMode(mode: ThemeModePreference) {
        prefs.edit().putString("theme_mode", mode.name).apply()
        _state.value = _state.value.copy(themeMode = mode)
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean("dynamic_color", enabled).apply()
        _state.value = _state.value.copy(dynamicColor = enabled)
    }

    fun setDefaultSubMode(mode: SubtitleDisplayMode) {
        prefs.edit().putString("sub_mode", mode.name).apply()
        _state.value = _state.value.copy(defaultSubMode = mode)
    }

    fun setTtsAccent(accent: TtsAccent) {
        prefs.edit().putString("tts_accent", accent.name).apply()
        _state.value = _state.value.copy(ttsAccent = accent)
    }

    fun setLastSyncHost(host: String) {
        prefs.edit().putString("sync_host", host).apply()
        _state.value = _state.value.copy(lastSyncHost = host)
    }
}
