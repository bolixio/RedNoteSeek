package com.rednoteseek.core.network

/**
 * xhs Web 端 API 通用响应包装。失败时 `success=false` 并带 `code` / `msg`。
 */
@kotlinx.serialization.Serializable
data class XhsResponse<T>(
    val code: Int = 0,
    val success: Boolean = true,
    val msg: String? = null,
    val data: T? = null,
)
