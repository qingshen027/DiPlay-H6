package com.shilapi.xcertplay.media

import org.junit.Assert.assertTrue
import org.junit.Test

class NativeOpusTest {
    @Test
    fun softwareEncoderProducesRawTwentyMillisecondPackets() {
        repeat(3) {
            val handle = NativeOpus.create(48_000)
            assertTrue(handle != 0L)
            try {
                val pcm = ByteArray(1920)
                repeat(100) { frame ->
                    for (sample in 0 until 960) {
                        val value = if ((frame * 960 + sample) % 96 < 48) 8000 else -8000
                        pcm[sample * 2] = value.toByte()
                        pcm[sample * 2 + 1] = (value shr 8).toByte()
                    }
                    val packet = NativeOpus.encode(handle, pcm)
                    assertTrue(packet.size in 1..1275)
                    assertTrue(!packet.contentEquals(pcm))
                    assertTrue(!packet.take(8).toByteArray().contentEquals("OpusHead".toByteArray()))
                    // JNI 同时用 opus_packet_get_nb_samples 验证每个包恰为 960 个采样。
                }
            } finally {
                NativeOpus.destroy(handle)
            }
        }
    }

    @Test
    fun wrapperCloseIsIdempotent() {
        val encoder = OpusEncoder(48_000)
        try {
            assertTrue(encoder.available)
            encoder.encode(ByteArray(1920))
        } finally {
            encoder.close()
            encoder.close()
        }
        assertTrue(!encoder.available)
        assertTrue(encoder.encode(ByteArray(1920)).isEmpty())
    }
}
