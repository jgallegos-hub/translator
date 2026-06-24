#include "ring_buffer.h"

#include <algorithm>
#include <cstring>

namespace gemmapipeline {

RingBuffer::RingBuffer(std::size_t capacity_samples)
    : capacity_(capacity_samples), storage_(capacity_samples, 0) {}

std::size_t RingBuffer::write(const int16_t* src, std::size_t count) {
    if (count == 0 || capacity_ == 0) {
        return 0;
    }

    std::size_t effective_count = count;
    const int16_t* effective_src = src;
    if (effective_count > capacity_) {
        const std::size_t skip = effective_count - capacity_;
        overflow_count_.fetch_add(skip, std::memory_order_relaxed);
        effective_src += skip;
        effective_count = capacity_;
    }

    const std::size_t read_pos = read_pos_.load(std::memory_order_acquire);
    const std::size_t write_pos = write_pos_.load(std::memory_order_relaxed);
    const std::size_t used = write_pos - read_pos;
    const std::size_t free_space = capacity_ - used;

    if (effective_count > free_space) {
        const std::size_t to_drop = effective_count - free_space;
        overflow_count_.fetch_add(to_drop, std::memory_order_relaxed);
        read_pos_.store(read_pos + to_drop, std::memory_order_release);
    }

    const std::size_t start_index = write_pos % capacity_;
    const std::size_t first_chunk = std::min(effective_count, capacity_ - start_index);
    std::memcpy(storage_.data() + start_index, effective_src, first_chunk * sizeof(int16_t));
    const std::size_t second_chunk = effective_count - first_chunk;
    if (second_chunk > 0) {
        std::memcpy(storage_.data(), effective_src + first_chunk, second_chunk * sizeof(int16_t));
    }

    write_pos_.store(write_pos + effective_count, std::memory_order_release);
    return effective_count;
}

std::size_t RingBuffer::read(int16_t* dst, std::size_t count) {
    if (count == 0 || capacity_ == 0) {
        return 0;
    }

    const std::size_t write_pos = write_pos_.load(std::memory_order_acquire);
    const std::size_t read_pos = read_pos_.load(std::memory_order_relaxed);
    const std::size_t used = write_pos - read_pos;
    if (used == 0) {
        return 0;
    }

    const std::size_t to_read = std::min(count, used);
    const std::size_t start_index = read_pos % capacity_;
    const std::size_t first_chunk = std::min(to_read, capacity_ - start_index);
    std::memcpy(dst, storage_.data() + start_index, first_chunk * sizeof(int16_t));
    const std::size_t second_chunk = to_read - first_chunk;
    if (second_chunk > 0) {
        std::memcpy(dst + first_chunk, storage_.data(), second_chunk * sizeof(int16_t));
    }

    read_pos_.store(read_pos + to_read, std::memory_order_release);
    return to_read;
}

std::size_t RingBuffer::available() const {
    const std::size_t write_pos = write_pos_.load(std::memory_order_acquire);
    const std::size_t read_pos = read_pos_.load(std::memory_order_acquire);
    return write_pos - read_pos;
}

void RingBuffer::clear() {
    read_pos_.store(0, std::memory_order_relaxed);
    write_pos_.store(0, std::memory_order_relaxed);
    overflow_count_.store(0, std::memory_order_relaxed);
}

}  // namespace gemmapipeline
