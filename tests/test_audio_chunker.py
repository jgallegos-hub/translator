from __future__ import annotations

import numpy as np
import pytest

from audio_manager.audio_chunker import AudioChunker
from audio_manager.config import AudioFormat, ChunkerConfig
from audio_manager.types import ChunkReadyEvent, MicRole, VADState

BLOCK = 512
SR = 16_000


def make_chunker(
    on_chunk,
    min_chunk_ms: float = 100.0,
    max_chunk_ms: float = 2000.0,
    silence_end_ms: float = 64.0,
    pre_roll_ms: float = 64.0,
) -> AudioChunker:
    config = ChunkerConfig(
        min_chunk_ms=min_chunk_ms,
        max_chunk_ms=max_chunk_ms,
        silence_end_ms=silence_end_ms,
        pre_roll_ms=pre_roll_ms,
    )
    return AudioChunker(config, AudioFormat(), MicRole.OMNI, on_chunk)


def speech_block(value: int = 1) -> np.ndarray:
    return np.full(BLOCK, value, dtype=np.int16)


def silence_block() -> np.ndarray:
    return np.zeros(BLOCK, dtype=np.int16)


class TestAudioChunkerBasic:
    def test_no_emission_on_silence(self):
        chunks = []
        chunker = make_chunker(chunks.append)

        for _ in range(10):
            chunker.feed(silence_block(), VADState.SILENCE)

        assert len(chunks) == 0
        assert chunker.is_collecting is False

    def test_starts_collecting_on_speech(self):
        chunks = []
        chunker = make_chunker(chunks.append)

        chunker.feed(speech_block(), VADState.SPEECH)
        assert chunker.is_collecting is True

    def test_emits_after_speech_then_silence(self):
        chunks: list[ChunkReadyEvent] = []
        chunker = make_chunker(chunks.append, min_chunk_ms=32.0, silence_end_ms=64.0)

        for _ in range(5):
            chunker.feed(speech_block(), VADState.SPEECH)

        for _ in range(5):
            chunker.feed(silence_block(), VADState.SILENCE)

        assert len(chunks) == 1
        assert chunks[0].mic_role == MicRole.OMNI
        assert chunks[0].duration_ms > 0
        assert len(chunks[0].audio) > 0

    def test_no_emission_before_min_chunk(self):
        chunks: list[ChunkReadyEvent] = []
        chunker = make_chunker(chunks.append, min_chunk_ms=1000.0, silence_end_ms=32.0)

        chunker.feed(speech_block(), VADState.SPEECH)

        for _ in range(3):
            chunker.feed(silence_block(), VADState.SILENCE)

        assert len(chunks) == 0
        assert chunker.is_collecting is True


class TestAudioChunkerPreRoll:
    def test_pre_roll_included(self):
        chunks: list[ChunkReadyEvent] = []
        chunker = make_chunker(chunks.append, min_chunk_ms=32.0, silence_end_ms=64.0, pre_roll_ms=64.0)

        chunker.feed(np.full(BLOCK, 99, dtype=np.int16), VADState.SILENCE)
        chunker.feed(np.full(BLOCK, 100, dtype=np.int16), VADState.SILENCE)

        for _ in range(3):
            chunker.feed(speech_block(200), VADState.SPEECH)

        for _ in range(5):
            chunker.feed(silence_block(), VADState.SILENCE)

        assert len(chunks) == 1
        chunk_data = chunks[0].audio
        assert len(chunk_data) > 3 * BLOCK


class TestAudioChunkerMaxDuration:
    def test_forced_split_at_max(self):
        chunks: list[ChunkReadyEvent] = []
        chunker = make_chunker(chunks.append, min_chunk_ms=32.0, max_chunk_ms=100.0)

        max_samples = int(0.1 * SR)
        frames_needed = (max_samples // BLOCK) + 2

        for _ in range(frames_needed):
            chunker.feed(speech_block(), VADState.SPEECH)

        assert len(chunks) >= 1
        assert chunks[0].duration_ms <= 200.0


class TestAudioChunkerFlush:
    def test_flush_returns_buffered(self):
        chunks = []
        chunker = make_chunker(chunks.append)

        for _ in range(3):
            chunker.feed(speech_block(), VADState.SPEECH)

        result = chunker.flush()
        assert result is not None
        assert len(result) > 0
        assert chunker.is_collecting is False

    def test_flush_returns_none_when_empty(self):
        chunks = []
        chunker = make_chunker(chunks.append)
        assert chunker.flush() is None

    def test_flush_returns_none_after_emission(self):
        chunks = []
        chunker = make_chunker(chunks.append, min_chunk_ms=32.0, silence_end_ms=32.0)

        chunker.feed(speech_block(), VADState.SPEECH)
        chunker.feed(speech_block(), VADState.SPEECH)

        for _ in range(3):
            chunker.feed(silence_block(), VADState.SILENCE)

        assert len(chunks) == 1
        assert chunker.flush() is None


class TestAudioChunkerMultipleChunks:
    def test_multiple_speech_segments(self):
        chunks: list[ChunkReadyEvent] = []
        chunker = make_chunker(chunks.append, min_chunk_ms=32.0, silence_end_ms=64.0)

        for _ in range(3):
            chunker.feed(speech_block(1), VADState.SPEECH)
        for _ in range(5):
            chunker.feed(silence_block(), VADState.SILENCE)

        for _ in range(3):
            chunker.feed(speech_block(2), VADState.SPEECH)
        for _ in range(5):
            chunker.feed(silence_block(), VADState.SILENCE)

        assert len(chunks) == 2


class TestAudioChunkerCallbackError:
    def test_callback_exception_doesnt_crash(self):
        def bad_callback(event):
            raise RuntimeError("callback error")

        chunker = make_chunker(bad_callback, min_chunk_ms=32.0, silence_end_ms=32.0)

        for _ in range(3):
            chunker.feed(speech_block(), VADState.SPEECH)
        for _ in range(3):
            chunker.feed(silence_block(), VADState.SILENCE)

        assert chunker.is_collecting is False
