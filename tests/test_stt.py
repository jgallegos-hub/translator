from __future__ import annotations

from unittest.mock import MagicMock, patch

import numpy as np
import pytest

from translation_pipeline.config import STTConfig
from translation_pipeline.stt import STTProcessor


def make_mock_whisper(text: str = "Hello world"):
    """Create a mock WhisperModel that returns fixed text."""
    mock_model = MagicMock()

    mock_segment = MagicMock()
    mock_segment.text = text

    mock_info = MagicMock()
    mock_info.language = "en"
    mock_info.language_probability = 0.99

    mock_model.transcribe.return_value = (iter([mock_segment]), mock_info)
    return mock_model


class TestSTTProcessor:
    def test_transcribe_returns_text(self):
        stt = STTProcessor(STTConfig())
        stt._model = make_mock_whisper("Hello world")

        audio = np.zeros(16_000, dtype=np.int16)
        result = stt.transcribe(audio)

        assert result == "Hello world"

    def test_transcribe_empty_audio(self):
        stt = STTProcessor(STTConfig())
        stt._model = make_mock_whisper("")

        audio = np.zeros(512, dtype=np.int16)
        result = stt.transcribe(audio)

        assert result == ""

    def test_transcribe_converts_int16_to_float32(self):
        stt = STTProcessor(STTConfig())
        mock = make_mock_whisper("test")
        stt._model = mock

        audio = np.full(16_000, 16384, dtype=np.int16)
        stt.transcribe(audio)

        call_args = mock.transcribe.call_args
        audio_arg = call_args[0][0]
        assert audio_arg.dtype == np.float32
        assert np.max(np.abs(audio_arg)) <= 1.0

    def test_config_passed_to_transcribe(self):
        config = STTConfig(language="en", beam_size=3, vad_filter=True)
        stt = STTProcessor(config)
        mock = make_mock_whisper("test")
        stt._model = mock

        stt.transcribe(np.zeros(16_000, dtype=np.int16))

        call_kwargs = mock.transcribe.call_args[1]
        assert call_kwargs["language"] == "en"
        assert call_kwargs["beam_size"] == 3
        assert call_kwargs["vad_filter"] is True

    def test_multiple_segments_joined(self):
        stt = STTProcessor(STTConfig())

        seg1 = MagicMock()
        seg1.text = "Hello"
        seg2 = MagicMock()
        seg2.text = "world"

        mock_info = MagicMock()
        mock_info.language = "en"
        mock_info.language_probability = 0.99

        mock_model = MagicMock()
        mock_model.transcribe.return_value = (iter([seg1, seg2]), mock_info)
        stt._model = mock_model

        result = stt.transcribe(np.zeros(16_000, dtype=np.int16))
        assert result == "Hello world"


class TestSTTConfig:
    def test_defaults(self):
        c = STTConfig()
        assert c.model_size == "small"
        assert c.device == "cpu"
        assert c.compute_type == "int8"
        assert c.language == "en"

    def test_custom(self):
        c = STTConfig(model_size="tiny", device="cuda", beam_size=5)
        assert c.model_size == "tiny"
        assert c.beam_size == 5
