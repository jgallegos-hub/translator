from __future__ import annotations

import io
import logging
import time
import wave

import numpy as np
from faster_whisper import WhisperModel

from .config import STTConfig

logger = logging.getLogger(__name__)


class STTProcessor:
    """Speech-to-Text using faster-whisper (CTranslate2 backend).

    Accepts int16 16kHz mono audio chunks and returns transcribed text.
    """

    def __init__(self, config: STTConfig = STTConfig()) -> None:
        self._config = config
        self._model: WhisperModel | None = None

    def load_model(self) -> None:
        if self._model is not None:
            return
        logger.info(
            "Loading Whisper model '%s' on %s (%s)...",
            self._config.model_size,
            self._config.device,
            self._config.compute_type,
        )
        self._model = WhisperModel(
            self._config.model_size,
            device=self._config.device,
            compute_type=self._config.compute_type,
        )
        logger.info("Whisper model loaded")

    def transcribe(self, audio: np.ndarray, sample_rate: int = 16_000) -> str:
        """Transcribe int16 audio to text.

        Args:
            audio: numpy int16 array at 16kHz mono
            sample_rate: sample rate of the audio (default 16kHz)

        Returns:
            Transcribed text string.
        """
        if self._model is None:
            self.load_model()
        assert self._model is not None

        audio_float = audio.astype(np.float32) / 32768.0

        t0 = time.monotonic()
        segments, info = self._model.transcribe(
            audio_float,
            language=self._config.language,
            beam_size=self._config.beam_size,
            vad_filter=self._config.vad_filter,
        )

        text = " ".join(segment.text.strip() for segment in segments)
        elapsed_ms = (time.monotonic() - t0) * 1000

        logger.debug("STT: '%s' (%.0fms, lang=%s p=%.2f)", text, elapsed_ms, info.language, info.language_probability)
        return text
