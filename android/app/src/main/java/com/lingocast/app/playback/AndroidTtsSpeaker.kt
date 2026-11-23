package com.lingocast.app.playback

import android.content.Context
import android.speech.tts.TextToSpeech
import com.lingocast.app.data.prefs.PreferencesStore
import java.util.Locale

class AndroidTtsSpeaker(
    context: Context,
    private val preferences: PreferencesStore
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    @Volatile
    private var ready = false

    override fun onInit(status: Int) {
        ready = (status == TextToSpeech.SUCCESS)
        applyLocale()
    }

    private fun applyLocale() {
        val engine = tts ?: return
        if (!ready) return
        val locale = when (preferences.state.value.ttsAccent.tag) {
            "en-US" -> Locale.US
            else -> Locale.UK
        }
        engine.language = locale
    }

    fun speak(text: String) {
        val engine = tts ?: return
        if (!ready || text.isBlank()) return
        applyLocale()
        engine.setSpeechRate(0.92f)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lingocast_${System.currentTimeMillis()}")
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
