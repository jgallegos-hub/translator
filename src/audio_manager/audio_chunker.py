from __future__ import annotations

import logging
import time
from collections import deque

import numpy as np

from .config import AudioFormat, ChunkerConfig
from .types import ChunkCallback, ChunkReadyEvent, MicRole, VADState

logger = logging.getLogger(__name__)


class AudioChunker:
    """Accumulates VAD-confirmed speech into 1-2 second chunks.

    Maintains a pre-roll buffer that captures audio just before
    speech onset, preventing first-syllable clipping.
    Emits chunks via callback when:
      - Speech ends (silence detected after min_chunk_ms)
      - Max chunk duration reached (forced split)
    """

    def __init__(
        self,
        config: ChunkerConfig,
        audio_format: AudioFormat,
        mic_role: MicRole,
        on_chunk: ChunkCallback,
    ) -> None:
        self._config = config
        self._audio_format = audio_format
        self._mic_role = mic_role
        self._on_chunk = on_chunk

        pre_roll_samples = int(config.pre_roll_ms / 1000.0 * audio_format.sample_rate)
        self._pre_roll_frames = max(1, pre_roll_samples // audio_format.block_size)

        self._min_chunk_samples = int(config.min_chunk_ms / 1000.0 * audio_format.sample_rate)
        self._max_chunk_samples = int(config.max_chunk_ms / 1000.0 * audio_format.sample_rate)

        silence_end_samples = int(config.silence_end_ms / 1000.0 * audio_format.sample_rate)
        self._silence_end_frames = max(1, silence_end_samples // audio_format.block_size)

        self._pre_roll_buffer: deque[np.ndarray] = deque(maxlen=self._pre_roll_frames)
        self._chunk_buffer: list[np.ndarray] = []
        self._chunk_samples = 0
        self._collecting = False
        self._silence_count = 0
        self._chunk_start_time: float = 0.0

    @property
    def is_collecting(self) -> bool:
        return self._collecting

    def feed(self, audio: np.ndarray, vad_state: VADState) -> None:
        """Feed an audio frame with its VAD state."""
        if not self._collecting:
            self._pre_roll_buffer.append(audio.copy())

            if vad_state == VADState.SPEECH:
                self._start_collecting()
                return

        if self._collecting:
            self._chunk_buffer.append(audio.copy())
            self._chunk_samples += len(audio)
            self._silence_count = 0 if vad_state == VADState.SPEECH else self._silence_count + 1

            if self._chunk_samples >= self._max_chunk_samples:
                self._emit_chunk()
            elif (
                vad_state == VADState.SILENCE
                and self._silence_count >= self._silence_end_frames
                and self._chunk_samples >= self._min_chunk_samples
            ):
                self._emit_chunk()

    def flush(self) -> np.ndarray | None:
        """Force-emit any buffered audio. Returns the chunk or None if empty."""
        if not self._collecting or not self._chunk_buffer:
            return None

        chunk = np.concatenate(self._chunk_buffer)
        self._reset()
        return chunk

    def _start_collecting(self) -> None:
        self._collecting = True
        self._chunk_start_time = time.monotonic()
        self._silence_count = 0

        for frame in self._pre_roll_buffer:
            self._chunk_buffer.append(frame)
            self._chunk_samples += len(frame)

        self._pre_roll_buffer.clear()
        logger.debug("Chunker [%s]: started collecting", self._mic_role.value)

    def _emit_chunk(self) -> None:
        if not self._chunk_buffer:
            return

        chunk = np.concatenate(self._chunk_buffer)
        duration_ms = (len(chunk) / self._audio_format.sample_rate) * 1000.0

        event = ChunkReadyEvent(
            mic_role=self._mic_role,
            audio=chunk,
            duration_ms=duration_ms,
            timestamp=time.monotonic(),
        )

        logger.debug(
            "Chunker [%s]: emitting chunk %.0fms (%d samples)",
            self._mic_role.value,
            duration_ms,
            len(chunk),
        )

        self._reset()

        try:
            self._on_chunk(event)
        except Exception:
            logger.exception("Error in chunk callback")

    def _reset(self) -> None:
        self._chunk_buffer.clear()
        self._chunk_samples = 0
        self._collecting = False
        self._silence_count = 0
