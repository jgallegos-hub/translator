from __future__ import annotations

import logging
import time

import numpy as np
import torch

from .config import AudioFormat, VADConfig
from .types import MicRole, VADState, VADTransitionEvent

logger = logging.getLogger(__name__)


class VADProcessor:
    """Voice Activity Detection using Silero VAD.

    Wraps the Silero model with a state machine that tracks
    SILENCE→SPEECH and SPEECH→SILENCE transitions with configurable
    minimum durations to avoid flicker.
    """

    def __init__(
        self,
        config: VADConfig,
        audio_format: AudioFormat,
        mic_role: MicRole,
    ) -> None:
        self._config = config
        self._audio_format = audio_format
        self._mic_role = mic_role
        self._state = VADState.SILENCE
        self._model: torch.jit.ScriptModule | None = None

        self._speech_start_time: float | None = None
        self._silence_start_time: float | None = None
        self._last_transition: VADTransitionEvent | None = None

        min_speech_samples = int(config.min_speech_ms / 1000.0 * audio_format.sample_rate)
        min_silence_samples = int(config.min_silence_ms / 1000.0 * audio_format.sample_rate)
        self._min_speech_frames = max(1, min_speech_samples // audio_format.block_size)
        self._min_silence_frames = max(1, min_silence_samples // audio_format.block_size)

        self._consecutive_speech = 0
        self._consecutive_silence = 0

    @property
    def state(self) -> VADState:
        return self._state

    @property
    def last_transition(self) -> VADTransitionEvent | None:
        return self._last_transition

    def load_model(self) -> None:
        if self._model is not None:
            return
        logger.info("Loading Silero VAD model...")
        self._model, _ = torch.hub.load(
            "snakers4/silero-vad",
            "silero_vad",
            trust_repo=True,
        )
        logger.info("Silero VAD model loaded")

    def process_frame(self, audio: np.ndarray) -> VADTransitionEvent | None:
        """Process a single audio frame and return a transition event if state changed."""
        if self._model is None:
            self.load_model()
        assert self._model is not None

        audio_float = audio.astype(np.float32) / 32768.0
        tensor = torch.from_numpy(audio_float)
        probability = self._model(tensor, self._audio_format.sample_rate).item()

        is_speech = probability >= self._config.threshold

        if is_speech:
            self._consecutive_speech += 1
            self._consecutive_silence = 0
        else:
            self._consecutive_silence += 1
            self._consecutive_speech = 0

        transition = None

        if self._state == VADState.SILENCE and self._consecutive_speech >= self._min_speech_frames:
            self._state = VADState.SPEECH
            transition = VADTransitionEvent(
                mic_role=self._mic_role,
                state=VADState.SPEECH,
                timestamp=time.monotonic(),
            )
            self._last_transition = transition
            logger.debug("VAD [%s]: SILENCE → SPEECH (p=%.3f)", self._mic_role.value, probability)

        elif self._state == VADState.SPEECH and self._consecutive_silence >= self._min_silence_frames:
            self._state = VADState.SILENCE
            transition = VADTransitionEvent(
                mic_role=self._mic_role,
                state=VADState.SILENCE,
                timestamp=time.monotonic(),
            )
            self._last_transition = transition
            logger.debug("VAD [%s]: SPEECH → SILENCE (p=%.3f)", self._mic_role.value, probability)

        return transition

    def reset(self) -> None:
        self._state = VADState.SILENCE
        self._consecutive_speech = 0
        self._consecutive_silence = 0
        self._speech_start_time = None
        self._silence_start_time = None
        self._last_transition = None
        if self._model is not None:
            self._model.reset_states()

    def process_frame_raw(self, audio: np.ndarray) -> float:
        """Return raw probability without state machine logic. For testing/debugging."""
        if self._model is None:
            self.load_model()
        assert self._model is not None

        audio_float = audio.astype(np.float32) / 32768.0
        tensor = torch.from_numpy(audio_float)
        return self._model(tensor, self._audio_format.sample_rate).item()
