package com.lingocast.app

import android.content.Context
import com.lingocast.app.data.db.LingoDatabase
import com.lingocast.app.data.dict.OfflineDictionary
import com.lingocast.app.data.importexport.CoursePackImporter
import com.lingocast.app.data.prefs.PreferencesStore
import com.lingocast.app.data.repo.CourseRepository
import com.lingocast.app.playback.AndroidTtsSpeaker
import com.lingocast.app.playback.PlayerController
import com.lingocast.app.playback.ShadowRecorder
import kotlinx.serialization.json.Json

class AppContainer(context: Context) {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
    val database = LingoDatabase.build(context)
    val dictionary = OfflineDictionary(context)
    val preferences = PreferencesStore(context)
    val importer = CoursePackImporter(context, database, json)
    val repository = CourseRepository(database, json, dictionary)
    val ttsSpeaker = AndroidTtsSpeaker(context, preferences)
    val shadowRecorder = ShadowRecorder(context)
    val playerController = PlayerController(context, repository, preferences)
}
