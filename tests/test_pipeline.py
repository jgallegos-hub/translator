from __future__ import annotations

from unittest.mock import MagicMock, patch

import numpy as np
import pytest

from translation_pipeline.config import PipelineConfig
from translation_pipeline.pipeline import TranslationPipeline


def make_pipeline_with_mocks() -> tuple[TranslationPipeline, dict]:
    """Create a pipeline with all components mocked."""
    pipeline = TranslationPipeline(PipelineConfig())

    mock_stt = MagicMock()
    mock_stt.transcribe.return_value = "Hello world"

    mock_translator = MagicMock()
    mock_translator.translate.return_value = "Hola mundo"

    mock_tts = MagicMock()
    mock_tts.synthesize.return_value = (np.zeros(24_000, dtype=np.float32), 24_000)

    mock_player = MagicMock()

    pipeline._stt = mock_stt
    pipeline._translator = mock_translator
    pipeline._tts = mock_tts
    pipeline._player = mock_player
    pipeline._loaded = True

    return pipeline, {
        "stt": mock_stt,
        "translator": mock_translator,
        "tts": mock_tts,
        "player": mock_player,
    }


class TestTranslationPipeline:
    def test_full_pipeline(self):
        pipeline, mocks = make_pipeline_with_mocks()
        audio = np.zeros(16_000, dtype=np.int16)

        result = pipeline.process_chunk(audio)

        mocks["stt"].transcribe.assert_called_once()
        mocks["translator"].translate.assert_called_once_with("Hello world")
        mocks["tts"].synthesize.assert_called_once_with("Hola mundo")
        mocks["player"].play.assert_called_once()

        assert result["original_text"] == "Hello world"
        assert result["translated_text"] == "Hola mundo"
        assert result["total_ms"] >= 0
        assert "stt_ms" in result["timings"]
        assert "translate_ms" in result["timings"]
        assert "tts_ms" in result["timings"]
        assert "playback_ms" in result["timings"]

    def test_empty_stt_skips_downstream(self):
        pipeline, mocks = make_pipeline_with_mocks()
        mocks["stt"].transcribe.return_value = ""

        audio = np.zeros(16_000, dtype=np.int16)
        result = pipeline.process_chunk(audio)

        mocks["translator"].translate.assert_not_called()
        mocks["tts"].synthesize.assert_not_called()
        mocks["player"].play.assert_not_called()
        assert result["original_text"] == ""

    def test_empty_translation_skips_tts(self):
        pipeline, mocks = make_pipeline_with_mocks()
        mocks["translator"].translate.return_value = ""

        audio = np.zeros(16_000, dtype=np.int16)
        result = pipeline.process_chunk(audio)

        mocks["tts"].synthesize.assert_not_called()
        mocks["player"].play.assert_not_called()

    def test_empty_tts_skips_playback(self):
        pipeline, mocks = make_pipeline_with_mocks()
        mocks["tts"].synthesize.return_value = (np.array([], dtype=np.float32), 24_000)

        audio = np.zeros(16_000, dtype=np.int16)
        result = pipeline.process_chunk(audio)

        mocks["player"].play.assert_not_called()

    def test_timings_are_populated(self):
        pipeline, _ = make_pipeline_with_mocks()
        audio = np.zeros(16_000, dtype=np.int16)

        result = pipeline.process_chunk(audio)

        for key in ["stt_ms", "translate_ms", "tts_ms", "playback_ms"]:
            assert key in result["timings"]
            assert result["timings"][key] >= 0

    def test_stop(self):
        pipeline, mocks = make_pipeline_with_mocks()
        pipeline.stop()
        mocks["player"].stop.assert_called_once()


class TestPipelineConfig:
    def test_defaults(self):
        c = PipelineConfig()
        assert c.stt.model_size == "small"
        assert c.translator.source_lang == "en"
        assert c.tts.voice == "es-MX-JorgeNeural"
        assert c.player.output_device == 5
