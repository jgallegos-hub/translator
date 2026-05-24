from __future__ import annotations

from unittest.mock import MagicMock, patch

import numpy as np
import pytest

from translation_pipeline.config import PlayerConfig
from translation_pipeline.player import AudioPlayer


class TestAudioPlayer:
    @patch("translation_pipeline.player.sd.play")
    def test_play_float32(self, mock_play):
        player = AudioPlayer(PlayerConfig(output_device=5))
        audio = np.random.randn(24_000).astype(np.float32)

        player.play(audio, sample_rate=24_000)

        mock_play.assert_called_once()
        call_kwargs = mock_play.call_args[1]
        assert call_kwargs["device"] == 5
        assert call_kwargs["samplerate"] == 24_000

    @patch("translation_pipeline.player.sd.play")
    def test_play_int16_converts(self, mock_play):
        player = AudioPlayer(PlayerConfig(output_device=3))
        audio = np.full(16_000, 16384, dtype=np.int16)

        player.play(audio, sample_rate=16_000)

        mock_play.assert_called_once()
        played_audio = mock_play.call_args[0][0]
        assert played_audio.dtype == np.float32
        assert np.max(np.abs(played_audio)) <= 1.0

    def test_play_empty_does_nothing(self):
        player = AudioPlayer(PlayerConfig())
        player.play(np.array([], dtype=np.float32))

    @patch("translation_pipeline.player.sd.play")
    def test_play_uses_config_defaults(self, mock_play):
        player = AudioPlayer(PlayerConfig(output_device=7, sample_rate=22_050))
        audio = np.zeros(1000, dtype=np.float32)

        player.play(audio)

        call_kwargs = mock_play.call_args[1]
        assert call_kwargs["device"] == 7
        assert call_kwargs["samplerate"] == 22_050

    @patch("translation_pipeline.player.sd.stop")
    def test_stop(self, mock_stop):
        player = AudioPlayer(PlayerConfig())
        player.stop()
        mock_stop.assert_called_once()


class TestPlayerConfig:
    def test_defaults(self):
        c = PlayerConfig()
        assert c.output_device == 5
        assert c.sample_rate == 24_000

    def test_custom(self):
        c = PlayerConfig(output_device=3, sample_rate=16_000)
        assert c.output_device == 3
