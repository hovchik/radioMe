// JNI bridge to Codec2 for com.radiome.core.voice.Codec2.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "codec2.h"

JNIEXPORT jlong JNICALL Java_com_radiome_core_voice_Codec2_nativeCreate(JNIEnv *env, jclass cls, jint mode) {
    return (jlong)(intptr_t)codec2_create(mode);
}

JNIEXPORT void JNICALL Java_com_radiome_core_voice_Codec2_nativeDestroy(JNIEnv *env, jclass cls, jlong h) {
    if (h) codec2_destroy((struct CODEC2 *)(intptr_t)h);
}

JNIEXPORT jint JNICALL Java_com_radiome_core_voice_Codec2_nativeSamplesPerFrame(JNIEnv *env, jclass cls, jlong h) {
    return codec2_samples_per_frame((struct CODEC2 *)(intptr_t)h);
}

JNIEXPORT jint JNICALL Java_com_radiome_core_voice_Codec2_nativeBitsPerFrame(JNIEnv *env, jclass cls, jlong h) {
    return codec2_bits_per_frame((struct CODEC2 *)(intptr_t)h);
}

JNIEXPORT jint JNICALL Java_com_radiome_core_voice_Codec2_nativeBytesPerFrame(JNIEnv *env, jclass cls, jlong h) {
    return codec2_bytes_per_frame((struct CODEC2 *)(intptr_t)h);
}

/** Encodes one frame: pcm holds samples_per_frame 16-bit samples at 8 kHz, out receives bytes_per_frame bytes. */
JNIEXPORT void JNICALL Java_com_radiome_core_voice_Codec2_nativeEncode(JNIEnv *env, jclass cls, jlong h, jshortArray pcm, jbyteArray out) {
    jshort *in = (*env)->GetShortArrayElements(env, pcm, NULL);
    jbyte *bits = (*env)->GetByteArrayElements(env, out, NULL);
    codec2_encode((struct CODEC2 *)(intptr_t)h, (unsigned char *)bits, in);
    (*env)->ReleaseByteArrayElements(env, out, bits, 0);
    (*env)->ReleaseShortArrayElements(env, pcm, in, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_com_radiome_core_voice_Codec2_nativeDecode(JNIEnv *env, jclass cls, jlong h, jbyteArray frame, jshortArray pcm) {
    jbyte *bits = (*env)->GetByteArrayElements(env, frame, NULL);
    jshort *out = (*env)->GetShortArrayElements(env, pcm, NULL);
    codec2_decode((struct CODEC2 *)(intptr_t)h, out, (const unsigned char *)bits);
    (*env)->ReleaseShortArrayElements(env, pcm, out, 0);
    (*env)->ReleaseByteArrayElements(env, frame, bits, JNI_ABORT);
}
