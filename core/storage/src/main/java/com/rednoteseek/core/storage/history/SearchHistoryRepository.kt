package com.rednoteseek.core.storage.history

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import com.rednoteseek.core.storage.dynamic.DynamicSnapshotDao
import com.rednoteseek.core.storage.dynamic.DynamicSnapshotEntity
import com.rednoteseek.core.storage.dynamic.MIGRATION_2_3
import com.rednoteseek.core.storage.follow.FollowDao
import com.rednoteseek.core.storage.follow.FollowEntity
import com.rednoteseek.core.storage.follow.MIGRATION_1_2
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 搜索历史。保留 20 条最新的；重复关键词时更新 updatedAt 并置顶。
 */
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val keyword: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY updated_at DESC LIMIT 20")
    fun observeAll(): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE keyword = :keyword")
    suspend fun deleteOne(keyword: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Database(entities = [SearchHistoryEntity::class, FollowEntity::class, DynamicSnapshotEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun followDao(): FollowDao
    abstract fun dynamicSnapshotDao(): DynamicSnapshotDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "rednoteseek.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}

/** 给上层用的简化 facade。 */
class SearchHistoryRepository(private val dao: SearchHistoryDao) {
    fun observe(): Flow<List<String>> = dao.observeAll().map { list -> list.map { it.keyword } }

    suspend fun add(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        dao.upsert(SearchHistoryEntity(kw, System.currentTimeMillis()))
    }

    suspend fun delete(keyword: String) = dao.deleteOne(keyword)
    suspend fun clear() = dao.clear()
}
