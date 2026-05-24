from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import numpy as np
import pytest

from translation_pipeline.config import TTSConfig
from translation_pipeline.tts import TTSProcessor


class TestTTSProcessor:
    def test_synthesize_empty_text(self):
        tts = TTSProcessor(TTSConfig())
        audio, sr = tts.synthesize("")
        assert len(audio) == 0
        assert sr == 24_000

    def test_synthesize_whitespace_only(self):
        tts = TTSProcessor(TTSConfig())
        audio, sr = tts.synthesize("   ")
        assert len(audio) == 0


class TestTTSConfig:
    def test_defaults(self):
        c = TTSConfig()
        assert c.voice == "es-MX-JorgeNeural"
        assert c.rate == "+0%"
        assert c.volume == "+0%"

    def test_custom_voice(self):
        c = TTSConfig(voice="es-ES-AlvaroNeural", rate="+10%")
        assert c.voice == "es-ES-AlvaroNeural"
        assert c.rate == "+10%"
