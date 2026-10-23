package com.rednoteseek.core.storage.dynamic

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DynamicCacheRepositoryTest {

    private val dao = FakeDynamicSnapshotDao()
    private val repo = DynamicCacheRepository(dao)

    @Test fun saveReplacesAndDeleteRemoves() = runBlocking {
        repo.save("u1", "[1]")
        repo.save("u2", "[2]")
        repo.save("u1", "[3]")
        repo.delete("u2")
        assertEquals(mapOf("u1" to "[3]"), repo.load())
    }
}

private class FakeDynamicSnapshotDao : DynamicSnapshotDao {
    private val rows = linkedMapOf<String, DynamicSnapshotEntity>()
    override suspend fun getAll() = rows.values.toList()
    override suspend fun upsert(item: DynamicSnapshotEntity) { rows[item.userId] = item }
    override suspend fun delete(userId: String) { rows.remove(userId) }
}
