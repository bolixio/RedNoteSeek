package com.rednoteseek.feature.detail

/** 收藏数随本次的收藏变化 ±1；接口给的是「1.2万」这类无法加减的文字时原样显示。 */
internal fun adjustCount(count: String, initially: Boolean, now: Boolean): String {
    val n = count.toIntOrNull() ?: return count
    val delta = when {
        now == initially -> 0
        now -> 1
        else -> -1
    }
    return (n + delta).coerceAtLeast(0).toString()
}
