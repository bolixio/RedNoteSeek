package com.rednoteseek.core.network

import kotlinx.serialization.json.Json

/** 解析网页截获到的接口响应所用的 Json 配置。 */
object XhsHttp {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
}
