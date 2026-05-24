from __future__ import annotations

import asyncio
import io
import logging
import time

import numpy as np
import soundfile as sf

from .config import TTSConfig

logger = logging.getLogger(__name__)


class TTSProcessor:
    """Text-to-Speech using Microsoft Edge TTS (high quality neural voices).

    Generates Spanish audio from text. Returns numpy arrays ready for playback.
    Requires internet connection.
    """

    def __init__(self, config: TTSConfig = TTSConfig()) -> None:
        self._config = config

    def synthesize(self, text: str) -> tuple[np.ndarray, int]:
        """Synthesize text to audio.

        Args:
            text: Text in target language (Spanish).

        Returns:
            Tuple of (audio numpy float32 array, sample_rate).
        """
        if not text or not text.strip():
            return np.array([], dtype=np.float32), 24_000

        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            loop = None

        if loop and loop.is_running():
            import concurrent.futures

            with concurrent.futures.ThreadPoolExecutor() as pool:
                future = pool.submit(self._synthesize_sync, text)
                return future.result(timeout=30)
        else:
            return self._synthesize_sync(text)

    def _synthesize_sync(self, text: str) -> tuple[np.ndarray, int]:
        return asyncio.run(self._synthesize_async(text))

    async def _synthesize_async(self, text: str) -> tuple[np.ndarray, int]:
        import edge_tts

        t0 = time.monotonic()
        communicate = edge_tts.Communicate(
            text=text,
            voice=self._config.voice,
            rate=self._config.rate,
            volume=self._config.volume,
        )

        audio_bytes = io.BytesIO()
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                audio_bytes.write(chunk["data"])

        audio_bytes.seek(0)
        elapsed_ms = (time.monotonic() - t0) * 1000

        if audio_bytes.getbuffer().nbytes == 0:
            logger.warning("TTS: empty audio for text '%s'", text[:50])
            return np.array([], dtype=np.float32), 24_000

        audio_data, sample_rate = sf.read(audio_bytes)
        audio_float = audio_data.astype(np.float32)

        logger.debug(
            "TTS: '%s' → %d samples @ %dHz (%.0fms)",
            text[:50],
            len(audio_float),
            sample_rate,
            elapsed_ms,
        )
        return audio_float, sample_rate
