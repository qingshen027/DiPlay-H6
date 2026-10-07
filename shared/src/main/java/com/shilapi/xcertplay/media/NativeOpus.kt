package com.shilapi.xcertplay.media

import androidx.annotation.Keep

/** 每个句柄拥有独立 libopus 状态；调用方负责串行化编码与释放。 */
@Keep
internal object NativeOpus {
    init {
        System.loadLibrary("xcertplay_opus")
    }

    external fun create(bitrate: Int): Long
    external fun encode(handle: Long, pcm: ByteArray): ByteArray
    external fun destroy(handle: Long)
}
