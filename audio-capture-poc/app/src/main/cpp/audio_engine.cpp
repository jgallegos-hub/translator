#include "audio_engine.h"

#include <android/log.h>
#include <algorithm>
#include <cstring>

#define LOG_TAG "AudioEnginePoc"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace audiopoc {

namespace {

std::size_t ring_capacity_for(const AudioEngineConfig& cfg) {
    return static_cast<std::size_t>(cfg.sample_rate) *
           cfg.ring_buffer_seconds *
           static_cast<std::size_t>(cfg.channel_count);
}

}  // namespace

AudioEngine::AudioEngine(const AudioEngineConfig& cfg)
    : cfg_(cfg),
      capture_ring_(ring_capacity_for(cfg)),
      playback_ring_(ring_capacity_for(cfg)) {
    LOGI("AudioEngine created: sr=%d ch=%d ring=%zu samples",
         cfg.sample_rate, cfg.channel_count, ring_capacity_for(cfg));
}

AudioEngine::~AudioEngine() {
    stop_capture();
    stop_playback();
}

bool AudioEngine::start_capture(int32_t input_device_id) {
    std::lock_guard<std::mutex> lock(stream_mutex_);
    if (capture_stream_) {
        LOGW("start_capture: already running");
        return true;
    }

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::I16)
        ->setSampleRate(cfg_.sample_rate)
        ->setChannelCount(cfg_.channel_count)
        ->setInputPreset(oboe::InputPreset::Unprocessed)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    if (input_device_id > 0) {
        builder.setDeviceId(input_device_id);
    }
    if (cfg_.frames_per_callback > 0) {
        builder.setFramesPerCallback(cfg_.frames_per_callback);
    }

    std::shared_ptr<oboe::AudioStream> stream;
    oboe::Result result = builder.openStream(stream);
    if (result != oboe::Result::OK) {
        LOGE("start_capture: openStream failed: %s", oboe::convertToText(result));
        return false;
    }

    LOGI("Capture opened: actual sr=%d ch=%d fmt=%d device=%d framesPerBurst=%d",
         stream->getSampleRate(),
         stream->getChannelCount(),
         static_cast<int>(stream->getFormat()),
         stream->getDeviceId(),
         stream->getFramesPerBurst());

    result = stream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("start_capture: requestStart failed: %s", oboe::convertToText(result));
        stream->close();
        return false;
    }

    capture_stream_ = stream;
    return true;
}

void AudioEngine::stop_capture() {
    std::shared_ptr<oboe::AudioStream> to_close;
    {
        std::lock_guard<std::mutex> lock(stream_mutex_);
        to_close.swap(capture_stream_);
    }
    if (to_close) {
        to_close->stop();
        to_close->close();
        LOGI("Capture stopped");
    }
}

bool AudioEngine::start_playback(int32_t output_device_id) {
    std::lock_guard<std::mutex> lock(stream_mutex_);
    if (playback_stream_) {
        LOGW("start_playback: already running");
        return true;
    }

    playback_ring_.clear();
    playback_underflow_count_.store(0, std::memory_order_relaxed);

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::I16)
        ->setSampleRate(cfg_.sample_rate)
        ->setChannelCount(cfg_.channel_count)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Speech)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    if (output_device_id > 0) {
        builder.setDeviceId(output_device_id);
    }
    if (cfg_.frames_per_callback > 0) {
        builder.setFramesPerCallback(cfg_.frames_per_callback);
    }

    std::shared_ptr<oboe::AudioStream> stream;
    oboe::Result result = builder.openStream(stream);
    if (result != oboe::Result::OK) {
        LOGE("start_playback: openStream failed: %s", oboe::convertToText(result));
        return false;
    }

    LOGI("Playback opened: actual sr=%d ch=%d fmt=%d device=%d framesPerBurst=%d",
         stream->getSampleRate(),
         stream->getChannelCount(),
         static_cast<int>(stream->getFormat()),
         stream->getDeviceId(),
         stream->getFramesPerBurst());

    result = stream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("start_playback: requestStart failed: %s", oboe::convertToText(result));
        stream->close();
        return false;
    }

    playback_stream_ = stream;
    return true;
}

void AudioEngine::stop_playback() {
    std::shared_ptr<oboe::AudioStream> to_close;
    {
        std::lock_guard<std::mutex> lock(stream_mutex_);
        to_close.swap(playback_stream_);
    }
    if (to_close) {
        to_close->stop();
        to_close->close();
        LOGI("Playback stopped");
    }
}

std::size_t AudioEngine::drain_capture(int16_t* dst, std::size_t max_samples) {
    return capture_ring_.read(dst, max_samples);
}

std::size_t AudioEngine::enqueue_playback(const int16_t* src, std::size_t count) {
    return playback_ring_.write(src, count);
}

int32_t AudioEngine::capture_latency_ms() const {
    std::lock_guard<std::mutex> lock(stream_mutex_);
    if (!capture_stream_) return -1;
    // calculateLatencyMillis() returns ResultWithValue<double> — check
    // operator bool() / hasValue() before reading .value().
    auto latency = capture_stream_->calculateLatencyMillis();
    return latency ? static_cast<int32_t>(latency.value()) : -1;
}

int32_t AudioEngine::playback_latency_ms() const {
    std::lock_guard<std::mutex> lock(stream_mutex_);
    if (!playback_stream_) return -1;
    auto latency = playback_stream_->calculateLatencyMillis();
    return latency ? static_cast<int32_t>(latency.value()) : -1;
}

int32_t AudioEngine::capture_routed_device_id() const {
    std::lock_guard<std::mutex> lock(stream_mutex_);
    return capture_stream_ ? capture_stream_->getDeviceId() : -1;
}

int32_t AudioEngine::playback_routed_device_id() const {
    std::lock_guard<std::mutex> lock(stream_mutex_);
    return playback_stream_ ? playback_stream_->getDeviceId() : -1;
}

int32_t AudioEngine::actual_sample_rate_capture() const {
    std::lock_guard<std::mutex> lock(stream_mutex_);
    return capture_stream_ ? capture_stream_->getSampleRate() : -1;
}

int32_t AudioEngine::actual_sample_rate_playback() const {
    std::lock_guard<std::mutex> lock(stream_mutex_);
    return playback_stream_ ? playback_stream_->getSampleRate() : -1;
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
    oboe::AudioStream* stream,
    void* audio_data,
    int32_t num_frames) {
    const int32_t channels = stream->getChannelCount();
    const std::size_t sample_count =
        static_cast<std::size_t>(num_frames) * static_cast<std::size_t>(channels);

    if (stream->getDirection() == oboe::Direction::Input) {
        // Capture: append to the capture ring.
        const auto* src = static_cast<const int16_t*>(audio_data);
        capture_ring_.write(src, sample_count);
    } else {
        // Playback: drain from the playback ring; pad with silence on underrun.
        auto* dst = static_cast<int16_t*>(audio_data);
        const std::size_t read = playback_ring_.read(dst, sample_count);
        if (read < sample_count) {
            std::memset(dst + read, 0, (sample_count - read) * sizeof(int16_t));
            playback_underflow_count_.fetch_add(sample_count - read, std::memory_order_relaxed);
        }
    }
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    LOGW("Stream error after close (direction=%d): %s",
         static_cast<int>(stream->getDirection()),
         oboe::convertToText(error));
    // Oboe has already closed the stream; release our shared_ptr so the
    // managers see capture_active()/playback_active() = false.
    std::lock_guard<std::mutex> lock(stream_mutex_);
    if (stream->getDirection() == oboe::Direction::Input) {
        capture_stream_.reset();
    } else {
        playback_stream_.reset();
    }
}

}  // namespace audiopoc
