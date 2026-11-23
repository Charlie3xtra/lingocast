package com.lingocast.app.data.prefs

import android.content.Context
import com.lingocast.app.data.model.SavedSubscription
import com.lingocast.app.ui.theme.ThemeModePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    val lastSyncHost: String = "",
    val subscriptions: List<SavedSubscription> = emptyList()
)

class PreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("lingocast_prefs", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

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
        val rawHost = prefs.getString("sync_host", "") ?: ""
        val cleanHost = if (rawHost == "192.168.31.100:8765") "" else rawHost
        val subsRaw = prefs.getString("subscriptions_json", "[]") ?: "[]"
        val subs = runCatching {
            json.decodeFromString<List<SavedSubscription>>(subsRaw)
        }.getOrDefault(emptyList())
        return UserPreferences(
            themeMode = theme,
            dynamicColor = prefs.getBoolean("dynamic_color", true),
            defaultSubMode = sub,
            ttsAccent = accent,
            lastSyncHost = cleanHost,
            subscriptions = subs
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
        prefs.edit().putString("sync_host", host.trim()).apply()
        _state.value = _state.value.copy(lastSyncHost = host.trim())
    }

    fun saveSubscription(subscription: SavedSubscription) {
        val current = _state.value.subscriptions.toMutableList()
        val idx = current.indexOfFirst { it.id == subscription.id || it.url.equals(subscription.url, ignoreCase = true) }
        if (idx >= 0) {
            current[idx] = subscription
        } else {
            current.add(0, subscription)
        }
        prefs.edit().putString("subscriptions_json", json.encodeToString(current)).apply()
        _state.value = _state.value.copy(subscriptions = current)
    }

    fun removeSubscription(id: String) {
        val next = _state.value.subscriptions.filterNot { it.id == id }
        prefs.edit().putString("subscriptions_json", json.encodeToString(next)).apply()
        _state.value = _state.value.copy(subscriptions = next)
    }
}
