from __future__ import annotations

import logging
import threading
import time

import numpy as np
import sounddevice as sd

from .config import PlayerConfig

logger = logging.getLogger(__name__)


class AudioPlayer:
    """Play audio to a specific output device using sounddevice.

    Designed for playing TTS output through the JBL Go 4 (or any
    specific output device by index).
    """

    def __init__(self, config: PlayerConfig = PlayerConfig()) -> None:
        self._config = config
        self._lock = threading.Lock()
        self._playing = False

    @property
    def is_playing(self) -> bool:
        return self._playing

    def play(self, audio: np.ndarray, sample_rate: int | None = None) -> None:
        """Play audio array on the configured output device. Blocks until done.

        Args:
            audio: numpy float32 or int16 audio array.
            sample_rate: sample rate (defaults to config value).
        """
        if len(audio) == 0:
            return

        sr = sample_rate or self._config.sample_rate

        if audio.dtype == np.int16:
            audio = audio.astype(np.float32) / 32768.0

        with self._lock:
            self._playing = True
            t0 = time.monotonic()
            try:
                sd.play(
                    audio,
                    samplerate=sr,
                    device=self._config.output_device,
                    blocking=True,
                )
                elapsed_ms = (time.monotonic() - t0) * 1000
                logger.debug(
                    "Player: played %d samples @ %dHz on device %d (%.0fms)",
                    len(audio),
                    sr,
                    self._config.output_device,
                    elapsed_ms,
                )
            except sd.PortAudioError as e:
                logger.error("Playback error on device %d: %s", self._config.output_device, e)
            finally:
                self._playing = False

    def play_async(self, audio: np.ndarray, sample_rate: int | None = None) -> threading.Thread:
        """Play audio in a background thread. Returns the thread."""
        t = threading.Thread(
            target=self.play,
            args=(audio, sample_rate),
            daemon=True,
        )
        t.start()
        return t

    def stop(self) -> None:
        sd.stop()
        self._playing = False
