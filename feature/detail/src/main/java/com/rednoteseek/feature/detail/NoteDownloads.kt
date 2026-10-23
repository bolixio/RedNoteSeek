package com.rednoteseek.feature.detail

import com.rednoteseek.core.network.api.NoteCard
import com.rednoteseek.core.network.download.Downloader

/** 无水印保存笔记的一张图片（[imageIndex]）或视频（null）到相册，返回给用户看的结果文案。 */
internal suspend fun saveNoteMedia(downloader: Downloader, card: NoteCard, imageIndex: Int?): String {
    val name = sanitizeFileName(card.display_title.ifBlank { card.title })
    val saved = if (imageIndex == null) {
        downloader.downloadVideo(card, name)
    } else {
        downloader.downloadImage(card, imageIndex, name)
    }
    return if (saved != null) "已保存到相册" else FAILED
}

private const val FAILED = "下载失败：可能接口已变或受风控"

private fun sanitizeFileName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").take(40).ifBlank { "xhs_${System.currentTimeMillis()}" }
