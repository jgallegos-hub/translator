from __future__ import annotations

import asyncio
import time
from unittest.mock import MagicMock, patch

import numpy as np
import pytest

from audio_manager.capture_stream import CaptureStream
from audio_manager.config import AudioFormat, RingBufferConfig
from audio_manager.device_manager import AudioDeviceInfo
from audio_manager.events import EventBus
from audio_manager.exceptions import StreamError
from audio_manager.types import AudioEventType, MicRole


def make_device(index: int = 0, name: str = "Test Mic") -> AudioDeviceInfo:
    return AudioDeviceInfo(
        index=index,
        name=name,
        max_input_channels=1,
        max_output_channels=0,
        default_samplerate=16000.0,
        hostapi=0,
    )


class FakeInputStream:
    """Simulates sd.InputStream for testing."""

    def __init__(self, **kwargs):
        self.callback = kwargs.get("callback")
        self.started = False
        self.closed = False

    def start(self):
        self.started = True

    def stop(self):
        self.started = False

    def close(self):
        self.closed = True

    def inject_audio(self, data: np.ndarray, frames: int | None = None):
        if self.callback:
            if data.ndim == 1:
                data = data.reshape(-1, 1)
            self.callback(data, frames or len(data), {}, MagicMock(input_overflow=False))


class TestCaptureStreamLifecycle:
    @patch("audio_manager.capture_stream.sd.InputStream", FakeInputStream)
    async def test_start_stop(self):
        bus = EventBus()
        await bus.start()

        stream = CaptureStream(
            device_info=make_device(),
            mic_role=MicRole.OMNI,
            audio_format=AudioFormat(),
            event_bus=bus,
        )
        stream.start()
        assert stream.is_running is True

        stream.stop()
        assert stream.is_running is False
        await bus.stop()

    @patch("audio_manager.capture_stream.sd.InputStream", FakeInputStream)
    async def test_double_start(self):
        bus = EventBus()
        await bus.start()

        stream = CaptureStream(
            device_info=make_device(),
            mic_role=MicRole.OMNI,
            audio_format=AudioFormat(),
            event_bus=bus,
        )
        stream.start()
        stream.start()
        assert stream.is_running is True
        stream.stop()
        await bus.stop()

    @patch("audio_manager.capture_stream.sd.InputStream", FakeInputStream)
    async def test_double_stop(self):
        bus = EventBus()
        await bus.start()

        stream = CaptureStream(
            device_info=make_device(),
            mic_role=MicRole.OMNI,
            audio_format=AudioFormat(),
            event_bus=bus,
        )
        stream.start()
        stream.stop()
        stream.stop()
        await bus.stop()

    def test_stream_error_on_bad_device(self):
        bus = MagicMock()

        def raise_error(**kwargs):
            raise Exception("PortAudio error")

        with patch("audio_manager.capture_stream.sd.InputStream", side_effect=raise_error):
            stream = CaptureStream(
                device_info=make_device(index=999),
                mic_role=MicRole.OMNI,
                audio_format=AudioFormat(),
                event_bus=bus,
            )
            with pytest.raises(StreamError):
                stream.start()


class TestCaptureStreamAudioFlow:
    @patch("audio_manager.capture_stream.sd.InputStream", FakeInputStream)
    async def test_audio_reaches_event_bus(self):
        bus = EventBus()
        received = []
        bus.subscribe(AudioEventType.AUDIO_DATA, received.append)
        await bus.start()

        stream = CaptureStream(
            device_info=make_device(),
            mic_role=MicRole.OMNI,
            audio_format=AudioFormat(block_size=512),
            event_bus=bus,
        )
        stream.start()

        fake_stream: FakeInputStream = stream._stream  # type: ignore[assignment]
        audio_block = np.arange(512, dtype=np.int16)
        fake_stream.inject_audio(audio_block)

        await asyncio.sleep(0.2)
        stream.stop()
        await bus.stop()

        assert len(received) >= 1
        assert received[0].mic_role == MicRole.OMNI
        np.testing.assert_array_equal(received[0].audio, audio_block)

    @patch("audio_manager.capture_stream.sd.InputStream", FakeInputStream)
    async def test_multiple_blocks(self):
        bus = EventBus()
        received = []
        bus.subscribe(AudioEventType.AUDIO_DATA, received.append)
        await bus.start()

        stream = CaptureStream(
            device_info=make_device(),
            mic_role=MicRole.LAVALIER,
            audio_format=AudioFormat(block_size=512),
            event_bus=bus,
        )
        stream.start()

        fake_stream: FakeInputStream = stream._stream  # type: ignore[assignment]
        for i in range(5):
            block = np.full(512, i, dtype=np.int16)
            fake_stream.inject_audio(block)

        await asyncio.sleep(0.3)
        stream.stop()
        await bus.stop()

        assert len(received) >= 5
        assert all(e.mic_role == MicRole.LAVALIER for e in received)


class TestCaptureStreamProperties:
    @patch("audio_manager.capture_stream.sd.InputStream", FakeInputStream)
    async def test_properties(self):
        bus = EventBus()
        await bus.start()

        device = make_device(index=1, name="BY-M1")
        stream = CaptureStream(
            device_info=device,
            mic_role=MicRole.LAVALIER,
            audio_format=AudioFormat(),
            event_bus=bus,
        )

        assert stream.mic_role == MicRole.LAVALIER
        assert stream.device_info.name == "BY-M1"
        assert stream.overflow_count == 0
        await bus.stop()
