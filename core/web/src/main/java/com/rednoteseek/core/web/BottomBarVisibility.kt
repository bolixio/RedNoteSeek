package com.rednoteseek.core.web

import androidx.compose.runtime.mutableStateOf

/**
 * 底栏显隐信号：网页/列表滚动时隐藏，停止滚动 300ms 后显示。
 * 定义在 core:web（底层）避免与 design-system 循环依赖；UI 层读取，滚动事件源调用 onScroll。
 */
object BottomBarVisibility {

    val visible = mutableStateOf(true)

    private var hideTask: java.util.concurrent.ScheduledFuture<*>? = null

    private val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

    /** 滚动事件：立刻隐藏，停止 300ms 后恢复。线程安全。 */
    fun onScroll() {

        visible.value = false

        hideTask?.cancel(false)

        hideTask = scheduler.schedule({ visible.value = true }, 300, java.util.concurrent.TimeUnit.MILLISECONDS)

    }

}
