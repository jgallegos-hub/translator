from __future__ import annotations

import numpy as np
import pytest

from audio_manager.config import AudioFormat

SAMPLE_RATE = 16_000
DTYPE = np.int16


@pytest.fixture
def audio_format() -> AudioFormat:
    return AudioFormat()


@pytest.fixture
def silence_1s() -> np.ndarray:
    return np.zeros(SAMPLE_RATE, dtype=DTYPE)


@pytest.fixture
def sine_wave_1s() -> np.ndarray:
    """440Hz sine wave, 1 second, normalized to int16 range."""
    t = np.linspace(0, 1.0, SAMPLE_RATE, endpoint=False)
    wave = np.sin(2 * np.pi * 440 * t)
    return (wave * 32767).astype(DTYPE)


@pytest.fixture
def speech_silence_pattern() -> np.ndarray:
    """500ms sine (speech-like) + 500ms silence, repeated twice = 2s total."""
    half = SAMPLE_RATE // 2
    t = np.linspace(0, 0.5, half, endpoint=False)
    speech = (np.sin(2 * np.pi * 440 * t) * 32767).astype(DTYPE)
    silence = np.zeros(half, dtype=DTYPE)
    return np.concatenate([speech, silence, speech, silence])


@pytest.fixture
def short_audio_block() -> np.ndarray:
    """512 samples — one standard block."""
    return np.arange(512, dtype=DTYPE)


@pytest.fixture
def mock_devices() -> list[dict]:
    return [
        {
            "name": "USB Omni Microphone",
            "index": 0,
            "max_input_channels": 2,
            "max_output_channels": 0,
            "default_samplerate": 44100.0,
            "hostapi": 0,
        },
        {
            "name": "Boya BY-M1 Lavalier",
            "index": 1,
            "max_input_channels": 1,
            "max_output_channels": 0,
            "default_samplerate": 48000.0,
            "hostapi": 0,
        },
        {
            "name": "Bluetooth Headphones",
            "index": 2,
            "max_input_channels": 1,
            "max_output_channels": 2,
            "default_samplerate": 44100.0,
            "hostapi": 0,
        },
        {
            "name": "Built-in Speaker",
            "index": 3,
            "max_input_channels": 0,
            "max_output_channels": 2,
            "default_samplerate": 44100.0,
            "hostapi": 0,
        },
    ]
