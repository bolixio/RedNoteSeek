package com.rednoteseek.app

import androidx.lifecycle.ViewModel
import com.rednoteseek.core.network.download.Downloader
import com.rednoteseek.core.storage.AppContext
import com.rednoteseek.core.storage.dynamic.DynamicCacheRepository
import com.rednoteseek.core.storage.follow.FollowRepository
import com.rednoteseek.core.storage.history.AppDatabase
import com.rednoteseek.core.storage.history.SearchHistoryRepository

/**
 * App 级共享对象：无水印下载器、搜索历史、关注（账号关注名单的本地缓存）与动态页缓存。
 * 登录、搜索、详情都已改由 [com.rednoteseek.core.web.XhsWebEngine] 提供，旧的 Retrofit 直连栈不再使用。
 */
class SessionViewModel : ViewModel() {
    val downloader: Downloader by lazy { Downloader(AppContext.appContext) }
    val searchHistory: SearchHistoryRepository by lazy {
        SearchHistoryRepository(AppDatabase.get(AppContext.appContext).searchHistoryDao())
    }
    val follows: FollowRepository by lazy {
        FollowRepository(AppDatabase.get(AppContext.appContext).followDao())
    }
    val dynamicCache: DynamicCacheRepository by lazy {
        DynamicCacheRepository(AppDatabase.get(AppContext.appContext).dynamicSnapshotDao())
    }
}
