package com.lingocast.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SeriesEntity::class,
        EpisodeEntity::class,
        SavedWordEntity::class,
        SubtitleIndexEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class LingoDatabase : RoomDatabase() {
    abstract fun seriesDao(): SeriesDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun savedWordDao(): SavedWordDao
    abstract fun subtitleIndexDao(): SubtitleIndexDao

    companion object {
        fun build(context: Context): LingoDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                LingoDatabase::class.java,
                "lingocast.db"
            ).build()
    }
}
