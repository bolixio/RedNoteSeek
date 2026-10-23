package com.rednoteseek.core.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/** 搜索页上一张卡片：笔记 ID 与它在页面中的纵向绝对位置（CSS px）。 */
internal data class CardPosition(val id: String, val top: Int)

/** [PageScripts.reportCards] 的返回：目标卡片是否在页面上，以及当前所有卡片的位置。 */
internal data class CardSnapshot(
    val found: Boolean,
    val scrollY: Int,
    val viewport: Int,
    val cards: List<CardPosition>,
) {
    companion object {
        fun parse(json: String): CardSnapshot? = runCatching {
            val obj = Json.parseToJsonElement(json).jsonObject
            CardSnapshot(
                found = obj.getValue("found").jsonPrimitive.boolean,
                scrollY = obj.getValue("scrollY").jsonPrimitive.int,
                viewport = obj.getValue("viewport").jsonPrimitive.int,
                cards = obj.getValue("cards").jsonArray.map {
                    val card = it.jsonObject
                    CardPosition(card.getValue("id").jsonPrimitive.content, card.getValue("top").jsonPrimitive.int)
                },
            )
        }.getOrNull()
    }
}

internal object CardLocator {
    /**
     * 目标卡片已被虚拟列表回收时，估算网页应滚动到的位置。
     * 用页面上现存卡片在 App 结果列表里的序号与纵向位置做线性估算（双列瀑布流，序号与高度大致成正比）；
     * 只认得一张卡片时朝目标方向移动一屏。返回 null 表示无法定位。
     */
    fun nextScrollY(snapshot: CardSnapshot, searchOrder: List<String>, targetId: String): Int? {
        val target = searchOrder.indexOf(targetId)
        if (target < 0) return null
        val known = snapshot.cards.mapNotNull { card ->
            searchOrder.indexOf(card.id).takeIf { it >= 0 }?.let { it to card.top }
        }
        if (known.isEmpty()) return null
        val first = known.minBy { it.first }
        val last = known.maxBy { it.first }
        val estimate = if (last.first > first.first) {
            val perItem = (last.second - first.second).toDouble() / (last.first - first.first)
            (first.second + perItem * (target - first.first)).roundToInt()
        } else {
            val direction = if (target < first.first) -1 else 1
            snapshot.scrollY + direction * snapshot.viewport
        }
        // 让目标落在视口上部三分之一处，前后都有余量
        return (estimate - snapshot.viewport / 3).coerceAtLeast(0)
    }
}
