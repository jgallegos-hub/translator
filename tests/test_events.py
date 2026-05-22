from __future__ import annotations

import asyncio
import time

import numpy as np
import pytest

from audio_manager.events import EventBus
from audio_manager.types import (
    AudioDataEvent,
    AudioEventType,
    MicRole,
    VADState,
    VADTransitionEvent,
)


@pytest.fixture
def sample_audio_event() -> AudioDataEvent:
    return AudioDataEvent(
        mic_role=MicRole.OMNI,
        audio=np.zeros(512, dtype=np.int16),
        timestamp=time.monotonic(),
    )


@pytest.fixture
def sample_vad_event() -> VADTransitionEvent:
    return VADTransitionEvent(
        mic_role=MicRole.LAVALIER,
        state=VADState.SPEECH,
        timestamp=time.monotonic(),
    )


class TestEventBusLifecycle:
    async def test_start_stop(self):
        bus = EventBus()
        await bus.start()
        assert bus._running is True
        await bus.stop()
        assert bus._running is False

    async def test_double_start(self):
        bus = EventBus()
        await bus.start()
        await bus.start()
        assert bus._running is True
        await bus.stop()

    async def test_double_stop(self):
        bus = EventBus()
        await bus.start()
        await bus.stop()
        await bus.stop()


class TestEventBusSubscription:
    async def test_subscribe_and_receive(self, sample_audio_event):
        bus = EventBus()
        received = []

        bus.subscribe(AudioEventType.AUDIO_DATA, received.append)
        await bus.start()
        await bus.emit_async(sample_audio_event)
        await asyncio.sleep(0.05)
        await bus.stop()

        assert len(received) == 1
        assert received[0].mic_role == MicRole.OMNI

    async def test_multiple_handlers(self, sample_audio_event):
        bus = EventBus()
        received_a: list = []
        received_b: list = []

        bus.subscribe(AudioEventType.AUDIO_DATA, received_a.append)
        bus.subscribe(AudioEventType.AUDIO_DATA, received_b.append)
        await bus.start()
        await bus.emit_async(sample_audio_event)
        await asyncio.sleep(0.05)
        await bus.stop()

        assert len(received_a) == 1
        assert len(received_b) == 1

    async def test_wrong_event_type_not_received(self, sample_audio_event):
        bus = EventBus()
        received = []

        bus.subscribe(AudioEventType.VAD_TRANSITION, received.append)
        await bus.start()
        await bus.emit_async(sample_audio_event)
        await asyncio.sleep(0.05)
        await bus.stop()

        assert len(received) == 0

    async def test_unsubscribe(self, sample_audio_event):
        bus = EventBus()
        received = []

        handler = received.append
        bus.subscribe(AudioEventType.AUDIO_DATA, handler)
        bus.unsubscribe(AudioEventType.AUDIO_DATA, handler)
        await bus.start()
        await bus.emit_async(sample_audio_event)
        await asyncio.sleep(0.05)
        await bus.stop()

        assert len(received) == 0

    async def test_different_event_types(self, sample_audio_event, sample_vad_event):
        bus = EventBus()
        audio_received = []
        vad_received = []

        bus.subscribe(AudioEventType.AUDIO_DATA, audio_received.append)
        bus.subscribe(AudioEventType.VAD_TRANSITION, vad_received.append)
        await bus.start()
        await bus.emit_async(sample_audio_event)
        await bus.emit_async(sample_vad_event)
        await asyncio.sleep(0.05)
        await bus.stop()

        assert len(audio_received) == 1
        assert len(vad_received) == 1


class TestEventBusSyncEmit:
    async def test_emit_sync_from_thread(self, sample_audio_event):
        bus = EventBus()
        received = []

        bus.subscribe(AudioEventType.AUDIO_DATA, received.append)
        await bus.start()

        import threading

        def emit_from_thread():
            bus.emit_sync(sample_audio_event)

        t = threading.Thread(target=emit_from_thread)
        t.start()
        t.join()
        await asyncio.sleep(0.05)
        await bus.stop()

        assert len(received) == 1


class TestEventBusErrorHandling:
    async def test_handler_exception_doesnt_crash(self, sample_audio_event):
        bus = EventBus()
        received = []

        def bad_handler(event):
            raise RuntimeError("handler error")

        bus.subscribe(AudioEventType.AUDIO_DATA, bad_handler)
        bus.subscribe(AudioEventType.AUDIO_DATA, received.append)
        await bus.start()
        await bus.emit_async(sample_audio_event)
        await asyncio.sleep(0.05)
        await bus.stop()

        assert len(received) == 1
