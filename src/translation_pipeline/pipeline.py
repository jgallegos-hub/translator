from __future__ import annotations

import logging
import time

import numpy as np

from .config import PipelineConfig
from .player import AudioPlayer
from .stt import STTProcessor
from .translator import Translator
from .tts import TTSProcessor

logger = logging.getLogger(__name__)


class TranslationPipeline:
    """End-to-end pipeline: audio chunk → STT → translate → TTS → playback.

    Processes audio chunks sequentially. Each chunk goes through:
      1. Speech-to-Text (Whisper): int16 audio → English text
      2. Translation (Argos): English text → Spanish text
      3. Text-to-Speech (Edge TTS): Spanish text → audio
      4. Playback: audio → output device (JBL Go 4)
    """

    def __init__(self, config: PipelineConfig = PipelineConfig()) -> None:
        self._config = config
        self._stt = STTProcessor(config.stt)
        self._translator = Translator(config.translator)
        self._tts = TTSProcessor(config.tts)
        self._player = AudioPlayer(config.player)
        self._loaded = False

    def load_models(self) -> None:
        """Pre-load all models. Call once before processing."""
        if self._loaded:
            return

        logger.info("Loading pipeline models...")
        t0 = time.monotonic()

        self._stt.load_model()
        self._translator.load_model()
        # TTS doesn't need pre-loading (stateless API calls)

        elapsed = time.monotonic() - t0
        logger.info("Pipeline models loaded in %.1fs", elapsed)
        self._loaded = True

    def process_chunk(self, audio: np.ndarray, sample_rate: int = 16_000) -> dict:
        """Process a single audio chunk through the full pipeline.

        Args:
            audio: int16 numpy array at 16kHz mono.
            sample_rate: sample rate of input audio.

        Returns:
            Dict with timing and content info for each stage.
        """
        result = {
            "original_text": "",
            "translated_text": "",
            "timings": {},
            "total_ms": 0.0,
        }

        pipeline_start = time.monotonic()

        # --- Stage 1: STT ---
        t0 = time.monotonic()
        text_en = self._stt.transcribe(audio, sample_rate)
        result["timings"]["stt_ms"] = (time.monotonic() - t0) * 1000
        result["original_text"] = text_en

        if not text_en.strip():
            logger.debug("Pipeline: empty STT result, skipping")
            result["total_ms"] = (time.monotonic() - pipeline_start) * 1000
            return result

        # --- Stage 2: Translation ---
        t0 = time.monotonic()
        text_es = self._translator.translate(text_en)
        result["timings"]["translate_ms"] = (time.monotonic() - t0) * 1000
        result["translated_text"] = text_es

        if not text_es.strip():
            logger.debug("Pipeline: empty translation result, skipping")
            result["total_ms"] = (time.monotonic() - pipeline_start) * 1000
            return result

        # --- Stage 3: TTS ---
        t0 = time.monotonic()
        tts_audio, tts_sr = self._tts.synthesize(text_es)
        result["timings"]["tts_ms"] = (time.monotonic() - t0) * 1000

        if len(tts_audio) == 0:
            logger.debug("Pipeline: empty TTS audio, skipping playback")
            result["total_ms"] = (time.monotonic() - pipeline_start) * 1000
            return result

        # --- Stage 4: Playback ---
        t0 = time.monotonic()
        self._player.play(tts_audio, tts_sr)
        result["timings"]["playback_ms"] = (time.monotonic() - t0) * 1000

        result["total_ms"] = (time.monotonic() - pipeline_start) * 1000

        logger.info(
            "Pipeline: '%s' → '%s' | STT:%.0fms Translate:%.0fms TTS:%.0fms Play:%.0fms | Total:%.0fms",
            text_en[:40],
            text_es[:40],
            result["timings"]["stt_ms"],
            result["timings"]["translate_ms"],
            result["timings"]["tts_ms"],
            result["timings"]["playback_ms"],
            result["total_ms"],
        )

        return result

    def stop(self) -> None:
        self._player.stop()
