package com.rednoteseek.core.storage

import android.content.Context

/**
 * 全局 Context holder（简化依赖）。
 * 仅 Application 启动时设置一次。
 */
object AppContext {
    lateinit var appContext: Context
        private set

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }
}
