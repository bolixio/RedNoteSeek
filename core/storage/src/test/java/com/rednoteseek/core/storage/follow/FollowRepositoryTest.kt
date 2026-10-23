package com.rednoteseek.core.storage.follow

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FollowRepositoryTest {

    private val dao = FakeFollowDao()
    private val repo = FollowRepository(dao, clock = { 42L })

    @Test fun followStoresEntityWithTimestamp() = runBlocking {
        repo.follow("u1", "博主", "https://a/1", "https://www.xiaohongshu.com/user/profile/u1?xsec_token=T")
        assertEquals(
            listOf(FollowEntity("u1", "博主", "https://a/1", "https://www.xiaohongshu.com/user/profile/u1?xsec_token=T", 42L)),
            repo.observe().first(),
        )
    }

    @Test fun unfollowRemovesOnlyThatUser() = runBlocking {
        repo.follow("u1", "a", "", "url1")
        repo.follow("u2", "b", "", "url2")
        repo.unfollow("u1")
        assertEquals(listOf("u2"), repo.observe().first().map { it.userId })
    }

    @Test fun syncKeepsOnlyAccountFollowsAndPreservesStoredUrl() = runBlocking {
        repo.follow("u1", "旧昵称", "old", "https://www.xiaohongshu.com/user/profile/u1?xsec_token=T")
        repo.follow("gone", "x", "", "url")
        repo.sync(
            listOf(
                AccountFollow("u2", "新人", "a2", "https://www.xiaohongshu.com/user/profile/u2"),
                AccountFollow("u1", "新昵称", "a1", "https://www.xiaohongshu.com/user/profile/u1"),
            ),
        )
        val rows = repo.observe().first().associateBy { it.userId }
        assertEquals(setOf("u1", "u2"), rows.keys)
        assertEquals(FollowEntity("u1", "新昵称", "a1", "https://www.xiaohongshu.com/user/profile/u1?xsec_token=T", 42L), rows["u1"])
        assertEquals(FollowEntity("u2", "新人", "a2", "https://www.xiaohongshu.com/user/profile/u2", 42L), rows["u2"])
    }

    @Test fun syncWithEmptyListClearsAll() = runBlocking {
        repo.follow("u1", "a", "", "url1")
        repo.sync(emptyList())
        assertEquals(emptyList<FollowEntity>(), repo.observe().first())
    }
}

private class FakeFollowDao : FollowDao {
    private val rows = MutableStateFlow<List<FollowEntity>>(emptyList())
    override fun observeAll(): Flow<List<FollowEntity>> = rows
    override suspend fun getAll(): List<FollowEntity> = rows.value
    override suspend fun upsert(item: FollowEntity) {
        rows.value = rows.value.filter { it.userId != item.userId } + item
    }
    override suspend fun delete(userId: String) {
        rows.value = rows.value.filter { it.userId != userId }
    }
}
