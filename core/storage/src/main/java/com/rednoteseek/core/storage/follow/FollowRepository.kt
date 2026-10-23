package com.rednoteseek.core.storage.follow

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * 账号关注名单的本地缓存：网页端没有关注流，动态页靠逐个访问这些作者的主页拼出时间线。
 * 以截获到的账号名单为准（[FollowRepository.sync]）；App 里关注 / 取消关注成功后也立即写入。
 */
@Entity(tableName = "follows")
data class FollowEntity(
    @PrimaryKey @ColumnInfo(name = "user_id") val userId: String,
    val nickname: String,
    val avatar: String,
    /** 主页地址，刷新动态时原样加载；在 App 里关注的带 xsec_token，从账号名单同步来的不带。 */
    @ColumnInfo(name = "profile_url") val profileUrl: String,
    @ColumnInfo(name = "followed_at") val followedAt: Long,
)

@Dao
interface FollowDao {
    @Query("SELECT * FROM follows ORDER BY followed_at DESC")
    fun observeAll(): Flow<List<FollowEntity>>

    @Query("SELECT * FROM follows")
    suspend fun getAll(): List<FollowEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: FollowEntity)

    @Query("DELETE FROM follows WHERE user_id = :userId")
    suspend fun delete(userId: String)
}

/** v1 → v2：新增 follows 表，保留搜索历史。建表语句须与 [FollowEntity] 生成的结构一致。 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `follows` (`user_id` TEXT NOT NULL, `nickname` TEXT NOT NULL, " +
                "`avatar` TEXT NOT NULL, `profile_url` TEXT NOT NULL, `followed_at` INTEGER NOT NULL, PRIMARY KEY(`user_id`))"
        )
    }
}

class FollowRepository(
    private val dao: FollowDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observe(): Flow<List<FollowEntity>> = dao.observeAll()

    suspend fun follow(userId: String, nickname: String, avatar: String, profileUrl: String) =
        dao.upsert(FollowEntity(userId, nickname, avatar, profileUrl, clock()))

    suspend fun unfollow(userId: String) = dao.delete(userId)

    /**
     * 以账号关注名单为准：名单外的删除；昵称、头像按名单更新，已有记录的主页地址（可能带 xsec_token）
     * 与关注时间保留；新出现的按名单顺序排在前面。
     */
    suspend fun sync(users: List<AccountFollow>) {
        val existing = dao.getAll().associateBy { it.userId }
        val ids = users.map { it.userId }.toSet()
        existing.keys.filter { it !in ids }.forEach { dao.delete(it) }
        val now = clock()
        users.forEachIndexed { i, user ->
            val old = existing[user.userId]
            val item = FollowEntity(
                userId = user.userId,
                nickname = user.nickname.ifBlank { old?.nickname.orEmpty() },
                avatar = user.avatar.ifBlank { old?.avatar.orEmpty() },
                profileUrl = old?.profileUrl ?: user.profileUrl,
                followedAt = old?.followedAt ?: (now - i),
            )
            if (item != old) dao.upsert(item)
        }
    }
}

/** 账号关注名单里的一位用户；[profileUrl] 用于本地还没有记录时。 */
data class AccountFollow(val userId: String, val nickname: String, val avatar: String, val profileUrl: String)
