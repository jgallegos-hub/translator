#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace vadpoc {

/**
 * Single-Producer Single-Consumer (SPSC) lock-free ring buffer for int16 PCM
 * samples. Port of the Python `RingBuffer` in `src/audio_manager/buffers.py`.
 *
 * Threading contract:
 *   - One thread (the Oboe audio callback) calls `write()` only.
 *   - One thread (the drain coroutine) calls `read()` and `available()` only.
 *   - `overflow_count()` and `clear()` can be called from any thread but are
 *     advisory (they don't block the callback).
 *
 * Overflow policy: when the buffer has insufficient space, `write()` keeps the
 * newest data and drops the oldest by advancing `read_pos_`. This matches the
 * Python prototype which logs overflow but never blocks the audio thread.
 */
class RingBuffer {
public:
    explicit RingBuffer(std::size_t capacity_samples);
    ~RingBuffer() = default;

    RingBuffer(const RingBuffer&) = delete;
    RingBuffer& operator=(const RingBuffer&) = delete;

    std::size_t write(const int16_t* src, std::size_t count);
    std::size_t read(int16_t* dst, std::size_t count);
    std::size_t available() const;
    std::size_t capacity() const { return capacity_; }
    std::size_t overflow_count() const { return overflow_count_.load(std::memory_order_relaxed); }
    void clear();

private:
    const std::size_t capacity_;
    std::vector<int16_t> storage_;
    std::atomic<std::size_t> read_pos_{0};
    std::atomic<std::size_t> write_pos_{0};
    std::atomic<std::size_t> overflow_count_{0};
};

}  // namespace vadpoc
