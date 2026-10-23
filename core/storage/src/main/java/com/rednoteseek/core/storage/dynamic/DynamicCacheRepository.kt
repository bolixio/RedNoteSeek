package com.rednoteseek.core.storage.dynamic

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 动态页缓存：每位关注作者最近一次读到的主页首屏笔记（JSON，由 feature:profile 编解码）。
 * 刷新时只访问有未看笔记的作者，其余作者的笔记从这里取，App 重启后也不必全部重新访问。
 */
@Entity(tableName = "dynamic_snapshots")
data class DynamicSnapshotEntity(
    @PrimaryKey @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "notes_json") val notesJson: String,
)

@Dao
interface DynamicSnapshotDao {
    @Query("SELECT * FROM dynamic_snapshots")
    suspend fun getAll(): List<DynamicSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DynamicSnapshotEntity)

    @Query("DELETE FROM dynamic_snapshots WHERE user_id = :userId")
    suspend fun delete(userId: String)
}

/** v2 → v3：新增 dynamic_snapshots 表。建表语句须与 [DynamicSnapshotEntity] 生成的结构一致。 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `dynamic_snapshots` (`user_id` TEXT NOT NULL, `notes_json` TEXT NOT NULL, " +
                "PRIMARY KEY(`user_id`))"
        )
    }
}

class DynamicCacheRepository(private val dao: DynamicSnapshotDao) {
    /** 作者 ID → 首屏笔记 JSON。 */
    suspend fun load(): Map<String, String> = dao.getAll().associate { it.userId to it.notesJson }

    suspend fun save(userId: String, notesJson: String) = dao.upsert(DynamicSnapshotEntity(userId, notesJson))

    suspend fun delete(userId: String) = dao.delete(userId)
}
