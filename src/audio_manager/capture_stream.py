from __future__ import annotations

import logging
import threading
import time
from typing import TYPE_CHECKING

import numpy as np
import sounddevice as sd

from .buffers import RingBuffer
from .config import AudioFormat, RingBufferConfig
from .device_manager import AudioDeviceInfo
from .exceptions import DeviceDisconnectedError, StreamError
from .types import AudioDataEvent, MicRole

if TYPE_CHECKING:
    from .events import EventBus

logger = logging.getLogger(__name__)


class CaptureStream:
    """Captures audio from a single device using PortAudio callbacks.

    Architecture:
        PortAudio callback → RingBuffer (lock-free, no alloc)
        Drain thread → reads RingBuffer → emits AudioDataEvent via EventBus
    """

    def __init__(
        self,
        device_info: AudioDeviceInfo,
        mic_role: MicRole,
        audio_format: AudioFormat,
        event_bus: EventBus,
        ring_buffer_config: RingBufferConfig = RingBufferConfig(),
    ) -> None:
        self._device_info = device_info
        self._mic_role = mic_role
        self._audio_format = audio_format
        self._event_bus = event_bus

        capacity = int(ring_buffer_config.capacity_seconds * audio_format.sample_rate)
        self._ring_buffer = RingBuffer(capacity, dtype=np.dtype(audio_format.dtype))

        self._stream: sd.InputStream | None = None
        self._drain_thread: threading.Thread | None = None
        self._running = False

    @property
    def mic_role(self) -> MicRole:
        return self._mic_role

    @property
    def device_info(self) -> AudioDeviceInfo:
        return self._device_info

    @property
    def is_running(self) -> bool:
        return self._running

    @property
    def overflow_count(self) -> int:
        return self._ring_buffer.overflow_count

    def start(self) -> None:
        if self._running:
            return

        logger.info(
            "Starting capture: %s [%s] @ %dHz",
            self._device_info.name,
            self._mic_role.value,
            self._audio_format.sample_rate,
        )

        try:
            self._stream = sd.InputStream(
                device=self._device_info.index,
                channels=self._audio_format.channels,
                samplerate=self._audio_format.sample_rate,
                dtype=self._audio_format.dtype,
                blocksize=self._audio_format.block_size,
                callback=self._audio_callback,
            )
        except Exception as e:
            raise StreamError(f"Failed to open stream for {self._device_info.name}: {e}") from e

        self._running = True
        self._drain_thread = threading.Thread(
            target=self._drain_loop,
            name=f"drain-{self._mic_role.value}",
            daemon=True,
        )
        self._drain_thread.start()
        self._stream.start()

    def stop(self) -> None:
        if not self._running:
            return

        logger.info("Stopping capture: %s [%s]", self._device_info.name, self._mic_role.value)
        self._running = False

        if self._stream:
            try:
                self._stream.stop()
                self._stream.close()
            except sd.PortAudioError:
                logger.exception("Error closing stream")
            self._stream = None

        self._ring_buffer.write(np.array([0], dtype=np.int16))

        if self._drain_thread:
            self._drain_thread.join(timeout=2.0)
            self._drain_thread = None

        self._ring_buffer.clear()

    def _audio_callback(
        self,
        indata: np.ndarray,
        frames: int,
        time_info: dict,
        status: sd.CallbackFlags,
    ) -> None:
        if status:
            logger.warning("PortAudio status [%s]: %s", self._mic_role.value, status)
            if status.input_overflow:
                logger.warning("Input overflow on %s", self._device_info.name)

        self._ring_buffer.write(indata[:, 0] if indata.ndim > 1 else indata.ravel())

    def _drain_loop(self) -> None:
        block_size = self._audio_format.block_size
        logger.debug("Drain thread started for %s", self._mic_role.value)

        while self._running:
            if not self._ring_buffer.wait_for_data(timeout=0.1):
                continue

            while self._ring_buffer.available() >= block_size:
                audio = self._ring_buffer.read(block_size)
                event = AudioDataEvent(
                    mic_role=self._mic_role,
                    audio=audio,
                    timestamp=time.monotonic(),
                )
                self._event_bus.emit_sync(event)

        logger.debug("Drain thread stopped for %s", self._mic_role.value)
