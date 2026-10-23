package com.rednoteseek.core.network.download

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.rednoteseek.core.network.api.NoteCard
import com.rednoteseek.core.network.api.NoteImage
import com.rednoteseek.core.network.api.playableUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 无水印下载工具。
 *
 * 小红书图片资源路径是 `{trace_id}?imageView2/format/{fmt}`，CDN 有 4 个备用。
 * 服务端在请求头加 `Referer: https://www.xiaohongshu.com/`，否则 403。
 *
 * 视频用 `/feed` 接口返回的 `video.media.stream` 里挑出的 mp4 地址（见 [playableUrl]）。
 *
 * 写入位置：
 *   - API 29+：MediaStore.Images / MediaStore.Video，公共目录 Pictures/RedNoteSeek、Movies/RedNoteSeek
 *   - API <29：写入 getExternalStoragePublicDirectory 对应子目录
 */
class Downloader(
    private val context: Context,
    private val httpClient: OkHttpClient = defaultClient(),
) {

    /**
     * 下载笔记所有图片到系统相册。返回保存成功的文件 URI 列表。
     */
    suspend fun downloadImages(card: NoteCard, fileBaseName: String): List<Uri> = withContext(Dispatchers.IO) {
        traceIds(card).mapIndexedNotNull { idx, traceId ->
            saveImage(traceId, "$fileBaseName-${idx + 1}")
        }
    }

    /**
     * 只下载笔记的第 [index] 张图片（从 0 起），文件名带序号，与 [downloadImages] 一致。失败返回 null。
     */
    suspend fun downloadImage(card: NoteCard, index: Int, fileBaseName: String): Uri? = withContext(Dispatchers.IO) {
        val traceId = card.image_list.getOrNull(index)?.let(::traceIdOf) ?: return@withContext null
        saveImage(traceId, "$fileBaseName-${index + 1}")
    }

    private fun traceIds(card: NoteCard): List<String> = card.image_list.mapNotNull(::traceIdOf)

    private fun traceIdOf(img: NoteImage): String? {
        val url = img.info_list.firstOrNull()?.url
            ?: img.url_default
            ?: img.url
        return url?.let { imageKey(it) }
    }

    /**
     * 下载视频。返回保存的 URI，失败返回 null。
     */
    suspend fun downloadVideo(card: NoteCard, fileBaseName: String): Uri? = withContext(Dispatchers.IO) {
        val url = card.video?.playableUrl() ?: return@withContext null
        // master_url 是 http 地址，App 禁止明文流量；core:network 不能依赖 core:web，这里直接替换
        saveVideo(url.replaceFirst("http://", "https://"), "$fileBaseName.mp4")
    }

    private fun saveImage(traceId: String, baseName: String): Uri? {
        // 试 4 个 CDN + jpg/webp 两种格式
        val candidates = IMG_CDNS.flatMap { cdn ->
            listOf("$cdn/$traceId?imageView2/format/jpg", "$cdn/$traceId?imageView2/format/webp")
        }
        for (url in candidates) {
            val bytes = fetchBytes(url) ?: continue
            if (bytes.isEmpty()) continue
            // 校验是不是真图（防御 4xx HTML body）
            if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) continue
            return writeImage(bytes, baseName, "jpg")
        }
        return null
    }

    private fun saveVideo(url: String, fileName: String): Uri? {
        val bytes = fetchBytes(url) ?: return null
        if (bytes.size < 1024) return null  // 太小不像视频
        return writeVideo(bytes, fileName)
    }

    private fun fetchBytes(url: String): ByteArray? {
        val req = Request.Builder()
            .url(url)
            .header("Referer", "https://www.xiaohongshu.com/")
            .header("User-Agent", USER_AGENT)
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null
                else resp.body?.bytes()
            }
        }.getOrNull()
    }

    private fun writeImage(bytes: ByteArray, baseName: String, ext: String): Uri? {
        val mime = if (ext == "png") "image/png" else "image/jpeg"
        val fileName = "$baseName.${ext}"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RedNoteSeek")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv
            ) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val sub = File(dir, "RedNoteSeek").apply { mkdirs() }
            val f = File(sub, fileName)
            FileOutputStream(f).use { it.write(bytes) }
            Uri.fromFile(f)
        }
    }

    private fun writeVideo(bytes: ByteArray, fileName: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RedNoteSeek")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv
            ) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val sub = File(dir, "RedNoteSeek").apply { mkdirs() }
            val f = File(sub, fileName)
            FileOutputStream(f).use { it.write(bytes) }
            Uri.fromFile(f)
        }
    }

    companion object {
        /**
         * 取图片在 CDN 上的路径：去掉 host 与 `?`、`!` 之后的部分；
         * 新式地址形如 `/{时间戳}/{签名}/notes_pre_post/{id}`，去掉前两段，保留 `notes_pre_post/` 前缀（否则 404）。
         */
        internal fun imageKey(url: String): String? {
            val path = url.substringAfter("://").substringAfter('/', "")
                .substringBefore('?').substringBefore('!')
            val segments = path.split('/').filter { it.isNotEmpty() }
            val kept = if (segments.size >= 3 && segments[0].all { it.isDigit() }) segments.drop(2) else segments
            return kept.joinToString("/").ifBlank { null }
        }

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        val IMG_CDNS = listOf(
            "https://sns-img-qc.xhscdn.com",
            "https://sns-img-hw.xhscdn.com",
            "https://sns-img-bd.xhscdn.com",
            "https://sns-img-qn.xhscdn.com",
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
