// JNI bridge between Kotlin's NativeAudioEngine and the C++ AudioEngine.
//
// All functions are stateless wrappers that take a `long` handle (an opaque
// pointer to a heap-allocated AudioEngine). Lifetime is managed by Kotlin
// via nativeCreate / nativeDestroy.

#include <jni.h>
#include <android/log.h>
#include <cstdint>

#include "audio_engine.h"
#include "ring_buffer.h"

#define LOG_TAG "VadPocJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using vadpoc::AudioEngine;
using vadpoc::AudioEngineConfig;
using vadpoc::RingBuffer;

namespace {

inline AudioEngine* handle_to_engine(jlong handle) {
    return reinterpret_cast<AudioEngine*>(static_cast<uintptr_t>(handle));
}

inline RingBuffer* handle_to_buffer(jlong handle) {
    return reinterpret_cast<RingBuffer*>(static_cast<uintptr_t>(handle));
}

}  // namespace

extern "C" {

// ── AudioEngine lifecycle ───────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeAudioEngine_nativeCreate(
    JNIEnv* env, jobject /*thiz*/,
    jint sample_rate, jint channel_count, jint ring_buffer_seconds) {
    AudioEngineConfig cfg;
    cfg.sample_rate = sample_rate;
    cfg.channel_count = channel_count;
    cfg.ring_buffer_seconds = static_cast<std::size_t>(ring_buffer_seconds);
    auto* engine = new AudioEngine(cfg);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(engine));
}

JNIEXPORT void JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeAudioEngine_nativeDestroy(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* engine = handle_to_engine(handle);
    if (engine) {
        delete engine;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeAudioEngine_nativeStartCapture(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jint input_device_id) {
    auto* engine = handle_to_engine(handle);
    if (!engine) return JNI_FALSE;
    return engine->start_capture(input_device_id) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeAudioEngine_nativeStopCapture(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* engine = handle_to_engine(handle);
    if (engine) engine->stop_capture();
}

JNIEXPORT jboolean JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeAudioEngine_nativeStartPlayback(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jint output_device_id) {
    auto* engine = handle_to_engine(handle);
    if (!engine) return JNI_FALSE;
    return engine->start_playback(output_device_id) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeAudioEngine_nativeStopPlayback(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* engine = handle_to_engine(handle);
    if (engine) engine->stop_playback();
}

JNIEXPORT jint JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeAudioEngine_nativeDrainCapture(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jshortArray dst) {
    auto* engine = handle_to_engine(handle);
    if (!engine || dst == nullptr) return 0;

    const jsize length = env->GetArrayLength(dst);
    if (length <= 0) return 0;

    jboolean is_copy = JNI_FALSE;
    jshort* raw = env->GetShortArrayElements(dst, &is_copy);
    if (!raw) return 0;

    const std::size_t drained = engine->drain_capture(
        reinterpret_cast<int16_t*>(raw), static_cast<std::size_t>(length));
    env->ReleaseShortArrayElements(dst, raw, 0);
    return static_cast<jint>(drained);
}

JNIEXPORT jint JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeAudioEngine_nativeWritePlayback(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jshortArray src) {
    auto* engine = handle_to_engine(handle);
    if (!engine || src == nullptr) return 0;

    const jsize length = env->GetArrayLength(src);
    if (length <= 0) return 0;

    jshort* raw = env->GetShortArrayElements(src, nullptr);
    if (!raw) return 0;

    const std::size_t written = engine->enqueue_playback(
        reinterpret_cast<const int16_t*>(raw), static_cast<std::size_t>(length));
    env->ReleaseShortArrayElements(src, raw, JNI_ABORT);
    return static_cast<jint>(written);
}

// ── Metrics ─────────────────────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeAudioEngine_nativeCaptureLatencyMs(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* engine = handle_to_engine(handle);
    return engine ? engine->capture_latency_ms() : -1;
}

JNIEXPORT jint JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeAudioEngine_nativePlaybackLatencyMs(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* engine = handle_to_engine(handle);
    return engine ? engine->playback_latency_ms() : -1;
}

JNIEXPORT jlong JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeAudioEngine_nativeCaptureOverflowCount(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* engine = handle_to_engine(handle);
    return engine ? static_cast<jlong>(engine->capture_overflow_count()) : 0;
}

JNIEXPORT jlong JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeAudioEngine_nativePlaybackUnderflowCount(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* engine = handle_to_engine(handle);
    return engine ? static_cast<jlong>(engine->playback_underflow_count()) : 0;
}

JNIEXPORT jint JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeAudioEngine_nativeCaptureRoutedDeviceId(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* engine = handle_to_engine(handle);
    return engine ? engine->capture_routed_device_id() : -1;
}

JNIEXPORT jint JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeAudioEngine_nativePlaybackRoutedDeviceId(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* engine = handle_to_engine(handle);
    return engine ? engine->playback_routed_device_id() : -1;
}

JNIEXPORT jint JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeAudioEngine_nativeActualSampleRateCapture(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* engine = handle_to_engine(handle);
    return engine ? engine->actual_sample_rate_capture() : -1;
}

JNIEXPORT jint JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeAudioEngine_nativeActualSampleRatePlayback(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* engine = handle_to_engine(handle);
    return engine ? engine->actual_sample_rate_playback() : -1;
}

// ── Ring buffer (exposed so JVM tests can exercise the SPSC logic) ─────────

JNIEXPORT jlong JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeRingBuffer_nativeCreate(
    JNIEnv* env, jobject /*thiz*/, jint capacity_samples) {
    auto* buf = new RingBuffer(static_cast<std::size_t>(capacity_samples));
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(buf));
}

JNIEXPORT void JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeRingBuffer_nativeDestroy(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* buf = handle_to_buffer(handle);
    if (buf) delete buf;
}

JNIEXPORT jint JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeRingBuffer_nativeWrite(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jshortArray src) {
    auto* buf = handle_to_buffer(handle);
    if (!buf || src == nullptr) return 0;
    const jsize length = env->GetArrayLength(src);
    jshort* raw = env->GetShortArrayElements(src, nullptr);
    if (!raw) return 0;
    const std::size_t written = buf->write(
        reinterpret_cast<const int16_t*>(raw), static_cast<std::size_t>(length));
    env->ReleaseShortArrayElements(src, raw, JNI_ABORT);
    return static_cast<jint>(written);
}

JNIEXPORT jint JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeRingBuffer_nativeRead(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jshortArray dst) {
    auto* buf = handle_to_buffer(handle);
    if (!buf || dst == nullptr) return 0;
    const jsize length = env->GetArrayLength(dst);
    jshort* raw = env->GetShortArrayElements(dst, nullptr);
    if (!raw) return 0;
    const std::size_t read = buf->read(
        reinterpret_cast<int16_t*>(raw), static_cast<std::size_t>(length));
    env->ReleaseShortArrayElements(dst, raw, 0);
    return static_cast<jint>(read);
}

JNIEXPORT jint JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeRingBuffer_nativeAvailable(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* buf = handle_to_buffer(handle);
    return buf ? static_cast<jint>(buf->available()) : 0;
}

JNIEXPORT jlong JNICALL
Java_com_travel2chicago_vadpoc_audio_NativeRingBuffer_nativeOverflowCount(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* buf = handle_to_buffer(handle);
    return buf ? static_cast<jlong>(buf->overflow_count()) : 0;
}

}  // extern "C"
