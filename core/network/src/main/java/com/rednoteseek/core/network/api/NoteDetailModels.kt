package com.rednoteseek.core.network.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 笔记详情（网页 feed 响应的 data）。 */
@Serializable
data class NoteDetailResult(
    val items: List<NoteDetailItem> = emptyList(),
)

@Serializable
data class NoteDetailItem(
    val id: String = "",
    val note_card: NoteCard = NoteCard(),
    @SerialName("xsec_token") val xsecToken: String = "",
)
