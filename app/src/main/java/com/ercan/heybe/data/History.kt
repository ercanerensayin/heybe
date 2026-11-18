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

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [HistoryItem::class], version = 1, exportSchema = false)
abstract class HeybeDb : RoomDatabase() {
    abstract fun history(): HistoryDao

    companion object {
        @Volatile private var instance: HeybeDb? = null
        fun get(context: Context): HeybeDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, HeybeDb::class.java, "heybe.db")
                .build().also { instance = it }
        }
    }
}
