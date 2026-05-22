from __future__ import annotations

import threading
import time

import numpy as np
import pytest

from audio_manager.buffers import RingBuffer


class TestRingBufferBasic:
    def test_create(self):
        buf = RingBuffer(1024)
        assert buf.capacity == 1024
        assert buf.available() == 0
        assert buf.free_space() == 1023

    def test_write_read(self):
        buf = RingBuffer(1024)
        data = np.arange(100, dtype=np.int16)
        written = buf.write(data)
        assert written == 100
        assert buf.available() == 100

        out = buf.read(100)
        np.testing.assert_array_equal(out, data)
        assert buf.available() == 0

    def test_partial_read(self):
        buf = RingBuffer(1024)
        data = np.arange(100, dtype=np.int16)
        buf.write(data)
        out = buf.read(50)
        assert len(out) == 50
        np.testing.assert_array_equal(out, data[:50])
        assert buf.available() == 50

    def test_read_more_than_available(self):
        buf = RingBuffer(1024)
        data = np.arange(100, dtype=np.int16)
        buf.write(data)
        out = buf.read(200)
        assert len(out) == 100

    def test_read_empty(self):
        buf = RingBuffer(1024)
        out = buf.read(100)
        assert len(out) == 0

    def test_write_empty(self):
        buf = RingBuffer(1024)
        written = buf.write(np.array([], dtype=np.int16))
        assert written == 0

    def test_read_all(self):
        buf = RingBuffer(1024)
        data = np.arange(200, dtype=np.int16)
        buf.write(data)
        out = buf.read_all()
        np.testing.assert_array_equal(out, data)
        assert buf.available() == 0


class TestRingBufferWrap:
    def test_wrap_around(self):
        buf = RingBuffer(128)
        d1 = np.arange(100, dtype=np.int16)
        buf.write(d1)
        buf.read(80)

        d2 = np.arange(100, 200, dtype=np.int16)
        buf.write(d2)

        remaining = buf.read_all()
        expected = np.concatenate([d1[80:], d2])
        np.testing.assert_array_equal(remaining, expected)

    def test_multiple_wraps(self):
        buf = RingBuffer(64)
        for i in range(10):
            data = np.full(30, i, dtype=np.int16)
            buf.write(data)
            out = buf.read_all()
            np.testing.assert_array_equal(out, data)


class TestRingBufferOverflow:
    def test_overflow_drops_old(self):
        buf = RingBuffer(64)
        data = np.arange(100, dtype=np.int16)
        written = buf.write(data)
        assert written < 100
        assert buf.overflow_count > 0

    def test_overflow_keeps_newest(self):
        buf = RingBuffer(64)
        data = np.arange(100, dtype=np.int16)
        buf.write(data)
        out = buf.read_all()
        np.testing.assert_array_equal(out, data[-len(out):])


class TestRingBufferClear:
    def test_clear(self):
        buf = RingBuffer(1024)
        buf.write(np.arange(100, dtype=np.int16))
        buf.clear()
        assert buf.available() == 0
        out = buf.read(100)
        assert len(out) == 0


class TestRingBufferWait:
    def test_wait_for_data_returns_immediately_when_data_present(self):
        buf = RingBuffer(1024)
        buf.write(np.arange(10, dtype=np.int16))
        assert buf.wait_for_data(timeout=0.01) is True

    def test_wait_for_data_blocks_then_returns(self):
        buf = RingBuffer(1024)

        def delayed_write():
            time.sleep(0.05)
            buf.write(np.arange(10, dtype=np.int16))

        t = threading.Thread(target=delayed_write)
        t.start()
        assert buf.wait_for_data(timeout=1.0) is True
        t.join()

    def test_wait_for_data_timeout(self):
        buf = RingBuffer(1024)
        assert buf.wait_for_data(timeout=0.01) is False


class TestRingBufferThreadSafety:
    def test_concurrent_write_read(self):
        buf = RingBuffer(8192)
        total_samples = 50_000
        block_size = 512
        collected: list[np.ndarray] = []
        errors: list[Exception] = []

        def producer():
            try:
                written = 0
                val = np.int16(0)
                while written < total_samples:
                    n = min(block_size, total_samples - written)
                    data = np.full(n, val, dtype=np.int16)
                    buf.write(data)
                    val = np.int16((int(val) + 1) % 32000)
                    written += n
                    time.sleep(0.0001)
            except Exception as e:
                errors.append(e)

        def consumer():
            try:
                read = 0
                while read < total_samples:
                    if buf.wait_for_data(timeout=0.01):
                        chunk = buf.read_all()
                        if len(chunk) > 0:
                            collected.append(chunk)
                            read += len(chunk)
            except Exception as e:
                errors.append(e)

        p = threading.Thread(target=producer)
        c = threading.Thread(target=consumer)
        c.start()
        p.start()
        p.join(timeout=5)
        c.join(timeout=5)

        assert not errors, f"Thread errors: {errors}"
        total_read = sum(len(c) for c in collected)
        assert total_read + buf.overflow_count >= total_samples
