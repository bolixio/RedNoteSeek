package com.rednoteseek.feature.dots

import com.rednoteseek.core.web.ChatEvent
import com.rednoteseek.core.web.DotsChatSource
import com.rednoteseek.core.web.DotsConversation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow

/** 两个 ViewModel 测试共用的假数据源：记录调用，结果可由测试指定。 */
internal class FakeDotsChatSource : DotsChatSource {
    override val events = MutableSharedFlow<ChatEvent>()

    var listOpens = 0
    val conversationOpens = mutableListOf<String>()
    val sent = mutableListOf<String>()
    var sendResult = true
    var stopCalls = 0
    val deleted = mutableListOf<String>()
    var deleteResult = true
    /** 非空时 delete 挂起直到它完成，用来测忙碌状态。 */
    var deleteGate: CompletableDeferred<Boolean>? = null
    val sourcesOpened = mutableListOf<Int>()
    var openSourcesResult = true
    var closeSourcesCalls = 0

    override suspend fun openList() {
        listOpens++
    }

    override suspend fun openConversation(id: String) {
        conversationOpens += id
    }

    override suspend fun send(text: String): Boolean {
        sent += text
        return sendResult
    }

    override fun stop() {
        stopCalls++
    }

    override suspend fun delete(conversation: DotsConversation): Boolean {
        deleted += conversation.id
        deleteGate?.let { return it.await() }
        return deleteResult
    }

    override suspend fun openSources(answerIndex: Int): Boolean {
        sourcesOpened += answerIndex
        return openSourcesResult
    }

    override fun closeSources() {
        closeSourcesCalls++
    }
}
