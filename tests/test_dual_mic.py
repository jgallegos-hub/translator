from __future__ import annotations

import asyncio
from unittest.mock import MagicMock, patch

import numpy as np
import pytest

from audio_manager.config import AudioManagerConfig, DeviceConfig
from audio_manager.dual_mic import DualMicCapture
from audio_manager.events import EventBus
from audio_manager.exceptions import DeviceNotFoundError
from audio_manager.types import AudioEventType, MicRole
from tests.test_capture_stream import FakeInputStream


@pytest.fixture
def dual_config(mock_devices):
    return AudioManagerConfig(
        omni_device=DeviceConfig(index=0),
        lavalier_device=DeviceConfig(index=1),
    )


@pytest.fixture
def _patch_sd(mock_devices):
    with (
        patch("audio_manager.device_manager.sd.query_devices", return_value=mock_devices),
        patch("audio_manager.capture_stream.sd.InputStream", FakeInputStream),
    ):
        yield


@pytest.mark.usefixtures("_patch_sd")
class TestDualMicCapture:
    async def test_start_stop(self, dual_config):
        bus = EventBus()
        await bus.start()

        dual = DualMicCapture(dual_config, bus)
        dual.start()
        assert dual.is_running is True
        assert dual.omni is not None
        assert dual.lavalier is not None
        assert dual.omni.mic_role == MicRole.OMNI
        assert dual.lavalier.mic_role == MicRole.LAVALIER

        dual.stop()
        assert dual.is_running is False
        await bus.stop()

    async def test_audio_from_both_mics(self, dual_config):
        bus = EventBus()
        received = []
        bus.subscribe(AudioEventType.AUDIO_DATA, received.append)
        await bus.start()

        dual = DualMicCapture(dual_config, bus)
        dual.start()

        assert dual.omni is not None and dual.lavalier is not None

        omni_stream = dual.omni._stream
        lav_stream = dual.lavalier._stream
        assert omni_stream is not None and lav_stream is not None

        omni_stream.inject_audio(np.full(512, 1, dtype=np.int16))  # type: ignore[union-attr]
        lav_stream.inject_audio(np.full(512, 2, dtype=np.int16))  # type: ignore[union-attr]

        await asyncio.sleep(0.3)
        dual.stop()
        await bus.stop()

        omni_events = [e for e in received if e.mic_role == MicRole.OMNI]
        lav_events = [e for e in received if e.mic_role == MicRole.LAVALIER]
        assert len(omni_events) >= 1
        assert len(lav_events) >= 1

    async def test_mute_unmute_omni(self, dual_config):
        bus = EventBus()
        await bus.start()

        dual = DualMicCapture(dual_config, bus)
        dual.start()
        assert dual.omni is not None

        dual.mute_omni()
        assert dual.omni.is_running is False
        assert dual.lavalier is not None
        assert dual.lavalier.is_running is True

        dual.unmute_omni()
        assert dual.omni.is_running is True

        dual.stop()
        await bus.stop()


class TestDualMicCaptureErrors:
    async def test_invalid_device_raises(self):
        config = AudioManagerConfig(
            omni_device=DeviceConfig(index=99),
            lavalier_device=DeviceConfig(index=1),
        )
        bus = EventBus()
        await bus.start()

        with (
            patch("audio_manager.device_manager.sd.query_devices", return_value=[]),
            pytest.raises(DeviceNotFoundError),
        ):
            dual = DualMicCapture(config, bus)
            dual.start()

        await bus.stop()
