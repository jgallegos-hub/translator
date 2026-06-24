#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <oboe/Oboe.h>

#include "ring_buffer.h"

namespace gemmapipeline {

struct AudioEngineConfig {
    int32_t sample_rate = 16000;
    int32_t channel_count = 1;
    int32_t frames_per_callback = 0;
    std::size_t ring_buffer_seconds = 5;
};

class AudioEngine
    : public oboe::AudioStreamDataCallback,
      public oboe::AudioStreamErrorCallback {
public:
    explicit AudioEngine(const AudioEngineConfig& cfg);
    ~AudioEngine() override;

    AudioEngine(const AudioEngine&) = delete;
    AudioEngine& operator=(const AudioEngine&) = delete;

    bool start_capture(int32_t input_device_id);
    void stop_capture();

    bool start_playback(int32_t output_device_id);
    void stop_playback();

    std::size_t drain_capture(int16_t* dst, std::size_t max_samples);
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

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream,
        void* audio_data,
        int32_t num_frames) override;

    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    const AudioEngineConfig cfg_;
    RingBuffer capture_ring_;
    RingBuffer playback_ring_;
    std::atomic<std::size_t> playback_underflow_count_{0};

    std::shared_ptr<oboe::AudioStream> capture_stream_;
    std::shared_ptr<oboe::AudioStream> playback_stream_;
    mutable std::mutex stream_mutex_;
};

}  // namespace gemmapipeline
