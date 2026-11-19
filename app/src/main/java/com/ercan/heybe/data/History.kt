package com.ercan.heybe.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

object Status {
    const val RUNNING = "RUNNING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
}

@Entity(tableName = "history")
data class HistoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long = System.currentTimeMillis(),
    val toolId: String,
    val title: String,
    val uri: String? = null,
    val path: String? = null,
    val mime: String = "application/octet-stream",
    val size: Long = 0,
    val status: String = Status.DONE,
    val progress: Int = 0,
    val error: String? = null,
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY time DESC")
    fun all(): Flow<List<HistoryItem>>

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun get(id: Long): HistoryItem?

    @Insert
    suspend fun insert(item: HistoryItem): Long

    @Update
    suspend fun update(item: HistoryItem)

    @Query("SELECT * FROM history")
    suspend fun list(): List<HistoryItem>

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Perde: video başına kaldığı yer ve tercihler. */
@Entity(tableName = "playback")
data class Playback(
    @PrimaryKey val key: String,
    val title: String,
    val position: Long = 0,
    val duration: Long = 0,
    val lastPlayed: Long = System.currentTimeMillis(),
    val watched: Boolean = false,
    val subUri: String? = null,
    val subOffsetMs: Long = 0,
    val audioLang: String? = null,
    val textLang: String? = null,
    val textOff: Boolean = false,
)

@Dao
interface PlaybackDao {
    @Query("SELECT * FROM playback WHERE `key` = :key")
    suspend fun get(key: String): Playback?

    @Query("SELECT * FROM playback WHERE `key` IN (:keys)")
    suspend fun getAll(keys: List<String>): List<Playback>

    @Query("SELECT * FROM playback WHERE watched = 0 AND position > 5000 ORDER BY lastPlayed DESC LIMIT 12")
    fun recents(): Flow<List<Playback>>

    @Query("SELECT * FROM playback")
    fun all(): Flow<List<Playback>>

    @Query("SELECT * FROM playback")
    suspend fun list(): List<Playback>

    @androidx.room.Upsert
    suspend fun upsert(p: Playback)

    @Query("UPDATE playback SET watched = 0, position = 0 WHERE `key` = :key")
    suspend fun clear(key: String)
}

@Database(entities = [HistoryItem::class, Playback::class], version = 2, exportSchema = false)
abstract class HeybeDb : RoomDatabase() {
    abstract fun history(): HistoryDao
    abstract fun playback(): PlaybackDao

    companion object {
        @Volatile private var instance: HeybeDb? = null

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `playback` (`key` TEXT NOT NULL, `title` TEXT NOT NULL, `position` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `lastPlayed` INTEGER NOT NULL, `watched` INTEGER NOT NULL, `subUri` TEXT, `subOffsetMs` INTEGER NOT NULL, `audioLang` TEXT, `textLang` TEXT, `textOff` INTEGER NOT NULL, PRIMARY KEY(`key`))")
            }
        }
        fun get(context: Context): HeybeDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, HeybeDb::class.java, "heybe.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}
