from __future__ import annotations

from unittest.mock import MagicMock, patch

import numpy as np
import pytest
import torch

from audio_manager.config import AudioFormat, VADConfig
from audio_manager.types import MicRole, VADState
from audio_manager.vad_processor import VADProcessor


def make_mock_model(probabilities: list[float]) -> MagicMock:
    """Create a mock Silero model that returns probabilities in sequence."""
    model = MagicMock()
    prob_iter = iter(probabilities)

    def model_call(tensor, sample_rate):
        try:
            p = next(prob_iter)
        except StopIteration:
            p = 0.0
        result = MagicMock()
        result.item.return_value = p
        return result

    model.side_effect = model_call
    model.reset_states = MagicMock()
    return model


@pytest.fixture
def audio_block() -> np.ndarray:
    return np.zeros(512, dtype=np.int16)


@pytest.fixture
def vad_config() -> VADConfig:
    return VADConfig(
        threshold=0.5,
        min_speech_ms=32.0,
        min_silence_ms=64.0,
    )


class TestVADProcessorStateMachine:
    def test_initial_state_is_silence(self, vad_config):
        vad = VADProcessor(vad_config, AudioFormat(), MicRole.OMNI)
        assert vad.state == VADState.SILENCE

    def test_silence_to_speech_transition(self, vad_config, audio_block):
        # min_speech_ms=32ms with block_size=512 @ 16kHz = 1 frame needed
        probs = [0.9, 0.9]
        model = make_mock_model(probs)

        vad = VADProcessor(vad_config, AudioFormat(), MicRole.OMNI)
        vad._model = model

        t1 = vad.process_frame(audio_block)
        assert t1 is not None
        assert t1.state == VADState.SPEECH
        assert vad.state == VADState.SPEECH

    def test_speech_to_silence_transition(self, vad_config, audio_block):
        probs = [0.9, 0.9, 0.1, 0.1, 0.1]
        model = make_mock_model(probs)

        vad = VADProcessor(vad_config, AudioFormat(), MicRole.OMNI)
        vad._model = model

        vad.process_frame(audio_block)
        vad.process_frame(audio_block)
        assert vad.state == VADState.SPEECH

        vad.process_frame(audio_block)
        assert vad.state == VADState.SPEECH

        vad.process_frame(audio_block)
        assert vad.state == VADState.SILENCE

    def test_no_flicker_on_single_low_prob(self, vad_config, audio_block):
        probs = [0.9, 0.9, 0.1, 0.9, 0.9]
        model = make_mock_model(probs)

        vad = VADProcessor(vad_config, AudioFormat(), MicRole.OMNI)
        vad._model = model

        vad.process_frame(audio_block)
        vad.process_frame(audio_block)
        assert vad.state == VADState.SPEECH

        vad.process_frame(audio_block)
        assert vad.state == VADState.SPEECH

        vad.process_frame(audio_block)
        vad.process_frame(audio_block)
        assert vad.state == VADState.SPEECH

    def test_silence_stays_on_low_prob(self, vad_config, audio_block):
        probs = [0.1, 0.1, 0.1]
        model = make_mock_model(probs)

        vad = VADProcessor(vad_config, AudioFormat(), MicRole.OMNI)
        vad._model = model

        for _ in range(3):
            t = vad.process_frame(audio_block)
            assert t is None
        assert vad.state == VADState.SILENCE


class TestVADProcessorReset:
    def test_reset_clears_state(self, vad_config, audio_block):
        probs = [0.9, 0.9, 0.1]
        model = make_mock_model(probs)

        vad = VADProcessor(vad_config, AudioFormat(), MicRole.OMNI)
        vad._model = model

        vad.process_frame(audio_block)
        vad.process_frame(audio_block)
        assert vad.state == VADState.SPEECH

        vad.reset()
        assert vad.state == VADState.SILENCE
        assert vad.last_transition is None
        model.reset_states.assert_called_once()


class TestVADProcessorProperties:
    def test_mic_role(self):
        vad = VADProcessor(VADConfig(), AudioFormat(), MicRole.LAVALIER)
        assert vad._mic_role == MicRole.LAVALIER

    def test_last_transition_initially_none(self):
        vad = VADProcessor(VADConfig(), AudioFormat(), MicRole.OMNI)
        assert vad.last_transition is None

    def test_last_transition_updates(self, vad_config, audio_block):
        probs = [0.9, 0.9]
        model = make_mock_model(probs)
        vad = VADProcessor(vad_config, AudioFormat(), MicRole.OMNI)
        vad._model = model

        vad.process_frame(audio_block)
        vad.process_frame(audio_block)

        assert vad.last_transition is not None
        assert vad.last_transition.state == VADState.SPEECH


class TestVADProcessorEdgeCases:
    def test_boundary_threshold(self, audio_block):
        # Exactly at threshold (0.5 >= 0.5) should count as speech
        config = VADConfig(threshold=0.5, min_speech_ms=32.0)
        probs = [0.5]
        model = make_mock_model(probs)

        vad = VADProcessor(config, AudioFormat(), MicRole.OMNI)
        vad._model = model

        t = vad.process_frame(audio_block)
        assert t is not None
        assert vad.state == VADState.SPEECH

    def test_below_threshold_stays_silence(self, audio_block):
        config = VADConfig(threshold=0.5, min_speech_ms=32.0)
        probs = [0.49, 0.49]
        model = make_mock_model(probs)

        vad = VADProcessor(config, AudioFormat(), MicRole.OMNI)
        vad._model = model

        vad.process_frame(audio_block)
        vad.process_frame(audio_block)
        assert vad.state == VADState.SILENCE
