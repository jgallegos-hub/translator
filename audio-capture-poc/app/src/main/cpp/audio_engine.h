#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <oboe/Oboe.h>

#include "ring_buffer.h"

namespace audiopoc {

/**
 * Configuration for Oboe audio streams. Matches the prototype defaults
 * (16 kHz mono int16) but kept as a struct so a future production pipeline
 * can tune it without touching the engine class.
 */
struct AudioEngineConfig {
    int32_t sample_rate = 16000;
    int32_t channel_count = 1;
    int32_t frames_per_callback = 0;   // 0 = let Oboe pick
    std::size_t ring_buffer_seconds = 5;
};

/**
 * Owns one capture stream (USB mic → ring buffer) and one playback stream
 * (Kotlin buffer → USB DAC / BT speaker). Both run with PerformanceMode::
 * LowLatency. The audio callbacks are bounded and never allocate.
 *
 * Capture path:
 *   USB mic → onAudioReady() → capture_ring_.write()
 *   Kotlin drain thread → drain_capture() → JNI ShortArray
 *
 * Playback path:
 *   Kotlin write thread → enqueue_playback() → playback_ring_.write()
 *   USB DAC ← onAudioReady() ← playback_ring_.read() (silence on underrun)
 */
class AudioEngine
    : public oboe::AudioStreamDataCallback,
      public oboe::AudioStreamErrorCallback {
public:
    explicit AudioEngine(const AudioEngineConfig& cfg);
    ~AudioEngine() override;

    AudioEngine(const AudioEngine&) = delete;
    AudioEngine& operator=(const AudioEngine&) = delete;

    /**
     * Open + start the capture stream targeting the given Android device id.
     * Pass 0 to let Oboe pick the default input.
     * Returns true on success.
     */
    bool start_capture(int32_t input_device_id);
    void stop_capture();

    bool start_playback(int32_t output_device_id);
    void stop_playback();

    /** Copy up to `max_samples` from the capture ring buffer into `dst`. */
    std::size_t drain_capture(int16_t* dst, std::size_t max_samples);

    /** Append samples to the playback ring buffer (overflows drop oldest). */
    std::size_t enqueue_playback(const int16_t* src, std::size_t count);

    int32_t capture_latency_ms() const;
    int32_t playback_latency_ms() const;
    std::size_t capture_overflow_count() const { return capture_ring_.overflow_count(); }
    std::size_t playback_underflow_count() const { return playback_underflow_count_.load(std::memory_order_relaxed); }

    int32_t capture_routed_device_id() const;
    int32_t playback_routed_device_id() const;

    int32_t actual_sample_rate_capture() const;
    int32_t actual_sample_rate_playback() const;

    bool capture_active() const { return capture_stream_ != nullptr; }
    bool playback_active() const { return playback_stream_ != nullptr; }

    // ── oboe::AudioStreamDataCallback ────────────────────────────────────────
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream,
        void* audio_data,
        int32_t num_frames) override;

    // ── oboe::AudioStreamErrorCallback ───────────────────────────────────────
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    const AudioEngineConfig cfg_;
    RingBuffer capture_ring_;
    RingBuffer playback_ring_;
    std::atomic<std::size_t> playback_underflow_count_{0};

    // Stream pointers — protected by stream_mutex_ for open/close, but the
    // audio callbacks read them via Oboe so the mutex is not on the hot path.
    std::shared_ptr<oboe::AudioStream> capture_stream_;
    std::shared_ptr<oboe::AudioStream> playback_stream_;
    mutable std::mutex stream_mutex_;
};

}  // namespace audiopoc
