from __future__ import annotations

import pytest

from audio_manager.config import (
    AudioFormat,
    AudioManagerConfig,
    ChunkerConfig,
    DeviceConfig,
    RingBufferConfig,
    VADConfig,
)


class TestAudioFormat:
    def test_defaults(self):
        fmt = AudioFormat()
        assert fmt.sample_rate == 16_000
        assert fmt.channels == 1
        assert fmt.dtype == "int16"
        assert fmt.block_size == 512

    def test_frame_duration_ms(self):
        fmt = AudioFormat()
        assert fmt.frame_duration_ms == pytest.approx(32.0)

    def test_immutable(self):
        fmt = AudioFormat()
        with pytest.raises(AttributeError):
            fmt.sample_rate = 44100  # type: ignore[misc]


class TestDeviceConfig:
    def test_by_index(self):
        d = DeviceConfig(index=0)
        assert d.index == 0
        assert d.name is None

    def test_by_name(self):
        d = DeviceConfig(name="USB Mic")
        assert d.name == "USB Mic"
        assert d.index is None

    def test_requires_identifier(self):
        with pytest.raises(ValueError, match="requires either"):
            DeviceConfig()


class TestVADConfig:
    def test_defaults(self):
        v = VADConfig()
        assert v.threshold == 0.5
        assert v.min_speech_ms == 50.0
        assert v.min_silence_ms == 300.0

    def test_invalid_threshold(self):
        with pytest.raises(ValueError, match="threshold"):
            VADConfig(threshold=1.5)

        with pytest.raises(ValueError, match="threshold"):
            VADConfig(threshold=0.0)


class TestChunkerConfig:
    def test_defaults(self):
        c = ChunkerConfig()
        assert c.min_chunk_ms == 500.0
        assert c.max_chunk_ms == 2000.0


class TestAudioManagerConfig:
    def test_full_config(self):
        config = AudioManagerConfig(
            omni_device=DeviceConfig(name="USB Omni"),
            lavalier_device=DeviceConfig(name="BY-M1"),
        )
        assert config.omni_device.name == "USB Omni"
        assert config.audio_format.sample_rate == 16_000
        assert config.vad.threshold == 0.5

    def test_default_config(self):
        config = AudioManagerConfig()
        assert config.omni_device.index == 0
        assert config.lavalier_device.index == 1
        assert isinstance(config.ring_buffer, RingBufferConfig)
