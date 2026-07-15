package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.local.daos.*
import com.example.data.local.entities.*

@Database(
    entities = [
        SearchHistoryEntity::class,
        LikedSongEntity::class,
        DownloadedSongEntity::class,
        MessageEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class KipotifyDatabase : RoomDatabase() {
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun likedSongDao(): LikedSongDao
    abstract fun downloadedSongDao(): DownloadedSongDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: KipotifyDatabase? = null

        fun getDatabase(context: Context): KipotifyDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    KipotifyDatabase::class.java,
                    "kipotify_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
