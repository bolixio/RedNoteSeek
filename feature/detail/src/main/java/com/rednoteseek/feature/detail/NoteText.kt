package com.rednoteseek.feature.detail

/** 正文里的话题写作 `#名称[话题]#`，去掉标记只保留 `#名称`。 */
internal fun cleanDesc(desc: String): String = desc.replace("[话题]#", "").trim()

/** 返回 desc 中没有以 `#名称` 出现的标签名；正文末尾通常已带话题，避免重复显示。 */
internal fun extraTags(desc: String, tags: List<String>): List<String> = tags.filter { "#$it" !in desc }
