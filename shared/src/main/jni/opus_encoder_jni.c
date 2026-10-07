#include <jni.h>
#include <stdint.h>
#include "opus.h"

#define FRAME_SAMPLES 960
#define FRAME_BYTES (FRAME_SAMPLES * 2)
#define MAX_PACKET_BYTES 1275

static void throw_error(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type != NULL) {
        (*env)->ThrowNew(env, type, message);
        (*env)->DeleteLocalRef(env, type);
    }
}

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_NativeOpus_create(JNIEnv *env, jobject self, jint bitrate) {
    (void)self;
    int error = OPUS_OK;
    OpusEncoder *encoder = opus_encoder_create(48000, 1, OPUS_APPLICATION_VOIP, &error);
    if (encoder == NULL || error != OPUS_OK) {
        if (encoder != NULL) opus_encoder_destroy(encoder);
        throw_error(env, opus_strerror(error));
        return 0;
    }
    error = opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate));
    if (error == OPUS_OK) error = opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(5));
    if (error == OPUS_OK) error = opus_encoder_ctl(encoder, OPUS_SET_DTX(0));
    if (error == OPUS_OK) error = opus_encoder_ctl(encoder, OPUS_SET_VBR(1));
    if (error != OPUS_OK) {
        opus_encoder_destroy(encoder);
        throw_error(env, opus_strerror(error));
        return 0;
    }
    return (jlong)(intptr_t)encoder;
}

JNIEXPORT jbyteArray JNICALL
Java_com_shilapi_xcertplay_media_NativeOpus_encode(JNIEnv *env, jobject self,
                                                jlong handle, jbyteArray pcm) {
    (void)self;
    if (handle == 0 || pcm == NULL || (*env)->GetArrayLength(env, pcm) != FRAME_BYTES) {
        throw_error(env, "Opus 输入必须为 960 个单声道 PCM16LE 采样");
        return NULL;
    }
    jbyte bytes[FRAME_BYTES];
    opus_int16 samples[FRAME_SAMPLES];
    unsigned char packet[MAX_PACKET_BYTES];
    (*env)->GetByteArrayRegion(env, pcm, 0, FRAME_BYTES, bytes);
    if ((*env)->ExceptionCheck(env)) return NULL;
    /* 显式解码小端 PCM，不依赖 JNI 数组地址对齐或主机端序。 */
    for (int i = 0; i < FRAME_SAMPLES; ++i) {
        int value = (uint8_t)bytes[2 * i] | ((uint8_t)bytes[2 * i + 1] << 8);
        samples[i] = (opus_int16)(value >= 32768 ? value - 65536 : value);
    }
    int size = opus_encode((OpusEncoder *)(intptr_t)handle, samples, FRAME_SAMPLES,
                           packet, MAX_PACKET_BYTES);
    if (size < 0) {
        throw_error(env, opus_strerror(size));
        return NULL;
    }
    if (size == 0 || opus_packet_get_nb_samples(packet, size, 48000) != FRAME_SAMPLES) {
        throw_error(env, "libopus 未返回有效的 20ms access unit");
        return NULL;
    }
    /* opus_encode 返回裸 Opus 包，无 Ogg/OpusHead，也不会回传 PCM。 */
    jbyteArray result = (*env)->NewByteArray(env, size);
    if (result != NULL) (*env)->SetByteArrayRegion(env, result, 0, size, (const jbyte *)packet);
    return result;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_NativeOpus_destroy(JNIEnv *env, jobject self, jlong handle) {
    (void)env;
    (void)self;
    if (handle != 0) opus_encoder_destroy((OpusEncoder *)(intptr_t)handle);
}
