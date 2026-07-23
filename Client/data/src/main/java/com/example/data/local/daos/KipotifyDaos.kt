package com.example.data.local.daos

import androidx.room.*
import com.example.data.local.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT 10")
    fun getRecentSearchHistory(): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearch(query: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun deleteSearch(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearHistory()
}

@Dao
interface LikedSongDao {
    @Query("SELECT * FROM liked_songs ORDER BY timestamp DESC")
    fun getAllLikedSongs(): Flow<List<LikedSongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertLikedSong(song: LikedSongEntity)

    @Delete
    fun deleteLikedSong(song: LikedSongEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM liked_songs WHERE id = :id)")
    fun isSongLiked(id: String): Boolean
}

@Dao
interface DownloadedSongDao {
    @Query("SELECT * FROM downloaded_songs ORDER BY timestamp DESC")
    fun getAllDownloadedSongs(): Flow<List<DownloadedSongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertDownloadedSong(song: DownloadedSongEntity)

    @Query("DELETE FROM downloaded_songs WHERE id = :id")
    fun deleteDownloadedSong(id: String)

    @Query("SELECT EXISTS(SELECT 1 FROM downloaded_songs WHERE id = :id)")
    fun isSongDownloaded(id: String): Boolean
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    fun updateMessageStatus(id: String, status: String)

    @Query("DELETE FROM messages")
    fun clearMessages()
}
