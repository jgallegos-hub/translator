from __future__ import annotations

import logging
import threading

import numpy as np

logger = logging.getLogger(__name__)


class RingBuffer:
    """Lock-free single-producer single-consumer ring buffer for audio samples.

    Uses numpy arrays with atomic-like index updates. The producer (PortAudio callback)
    writes via `write()`, the consumer (drain thread) reads via `read()`.
    No mutexes — safe for real-time audio callbacks.
    """

    def __init__(self, capacity: int, dtype: np.dtype = np.dtype("int16")) -> None:
        self._capacity = capacity
        self._buffer = np.zeros(capacity, dtype=dtype)
        self._write_pos = 0
        self._read_pos = 0
        self._overflow_count = 0
        self._data_available = threading.Event()

    @property
    def capacity(self) -> int:
        return self._capacity

    @property
    def overflow_count(self) -> int:
        return self._overflow_count

    def available(self) -> int:
        diff = self._write_pos - self._read_pos
        if diff < 0:
            diff += self._capacity
        return diff

    def free_space(self) -> int:
        return self._capacity - 1 - self.available()

    def write(self, data: np.ndarray) -> int:
        """Write samples to the buffer. Called from PortAudio callback — must not block."""
        n = len(data)
        if n == 0:
            return 0

        free = self.free_space()
        if n > free:
            dropped = n - free
            self._overflow_count += dropped
            data = data[-free:]
            n = free
            if n == 0:
                return 0

        wp = self._write_pos
        end = wp + n

        if end <= self._capacity:
            self._buffer[wp:end] = data
        else:
            first = self._capacity - wp
            self._buffer[wp:self._capacity] = data[:first]
            self._buffer[: n - first] = data[first:]

        self._write_pos = end % self._capacity
        self._data_available.set()
        return n

    def read(self, count: int) -> np.ndarray:
        """Read up to `count` samples from the buffer. Returns what's available (may be less)."""
        avail = self.available()
        n = min(count, avail)
        if n == 0:
            return np.array([], dtype=self._buffer.dtype)

        rp = self._read_pos
        end = rp + n

        if end <= self._capacity:
            out = self._buffer[rp:end].copy()
        else:
            first = self._capacity - rp
            out = np.empty(n, dtype=self._buffer.dtype)
            out[:first] = self._buffer[rp:self._capacity]
            out[first:] = self._buffer[: n - first]

        self._read_pos = end % self._capacity
        if self.available() == 0:
            self._data_available.clear()
        return out

    def read_all(self) -> np.ndarray:
        """Read all available samples."""
        return self.read(self.available())

    def wait_for_data(self, timeout: float | None = None) -> bool:
        """Block until data is available or timeout expires. For drain thread use."""
        return self._data_available.wait(timeout=timeout)

    def clear(self) -> None:
        self._read_pos = self._write_pos
        self._data_available.clear()
