"""End-to-end demo: capture English speech → translate → play Spanish audio.

Integrates Module 1 (Audio Manager) with Module 2 (Translation Pipeline).

Usage:
    uv run python examples/translate_demo.py
    uv run python examples/translate_demo.py --input-device 1 --output-device 5 --duration 30
    uv run python examples/translate_demo.py --whisper-model small --list-devices
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import sys
import threading
import time

import numpy as np

from audio_manager.audio_chunker import AudioChunker
from audio_manager.capture_stream import CaptureStream
from audio_manager.config import AudioFormat, ChunkerConfig, DeviceConfig, RingBufferConfig, VADConfig
from audio_manager.device_manager import DeviceManager
from audio_manager.events import EventBus
from audio_manager.types import AudioEventType, MicRole, VADState
from audio_manager.vad_processor import VADProcessor
from translation_pipeline.config import PipelineConfig, PlayerConfig, STTConfig, TTSConfig, TranslatorConfig
from translation_pipeline.pipeline import TranslationPipeline

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
)
logger = logging.getLogger("translate_demo")


def list_devices() -> None:
    print("\n=== Audio Devices ===\n")
    print("  INPUT DEVICES:")
    for dev in DeviceManager.list_inputs():
        print(f"    [{dev.index}] {dev.name} ({dev.max_input_channels}ch @ {dev.default_samplerate}Hz)")
    print("\n  OUTPUT DEVICES:")
    for dev in DeviceManager.list_outputs():
        print(f"    [{dev.index}] {dev.name} ({dev.max_output_channels}ch @ {dev.default_samplerate}Hz)")
    print()


async def main(
    input_device: int,
    output_device: int,
    duration: float,
    whisper_model: str,
) -> None:
    # --- Config ---
    audio_format = AudioFormat()
    vad_config = VADConfig(threshold=0.5, min_silence_ms=300.0)
    chunker_config = ChunkerConfig(min_chunk_ms=500.0, max_chunk_ms=2000.0)

    pipeline_config = PipelineConfig(
        stt=STTConfig(model_size=whisper_model, device="cpu", compute_type="int8"),
        translator=TranslatorConfig(source_lang="en", target_lang="es"),
        tts=TTSConfig(voice="es-MX-JorgeNeural"),
        player=PlayerConfig(output_device=output_device),
    )

    # --- Resolve devices ---
    input_dev = DeviceManager.find_input(DeviceConfig(index=input_device))
    print(f"\n  Input:  [{input_dev.index}] {input_dev.name}")
    print(f"  Output: device {output_device}")
    print(f"  Whisper: {whisper_model}")
    print(f"  Duration: {duration}s\n")

    # --- Load models (one-time) ---
    print("Loading models (this may take a minute on first run)...")
    pipeline = TranslationPipeline(pipeline_config)
    pipeline.load_models()

    vad = VADProcessor(vad_config, audio_format, MicRole.OMNI)
    vad.load_model()
    print("Models loaded. Start speaking in English!\n")
    print("=" * 60)

    # --- Pipeline processing in background thread ---
    chunk_count = 0
    processing_lock = threading.Lock()

    def on_chunk(event):
        nonlocal chunk_count

        with processing_lock:
            chunk_count += 1
            n = chunk_count
            duration_ms = event.duration_ms

        logger.info("--- Chunk %d (%.0fms) ---", n, duration_ms)

        result = pipeline.process_chunk(event.audio)

        if result["original_text"].strip():
            print(f"\n  [{n}] EN: {result['original_text']}")
            print(f"       ES: {result['translated_text']}")
            timings = result["timings"]
            parts = [f"{k}:{v:.0f}" for k, v in timings.items()]
            print(f"       ⏱  {' | '.join(parts)} | total:{result['total_ms']:.0f}ms")
        else:
            logger.debug("Chunk %d: no speech detected by Whisper", n)

    # --- Audio pipeline ---
    chunker = AudioChunker(chunker_config, audio_format, MicRole.OMNI, on_chunk)
    bus = EventBus()

    last_state = VADState.SILENCE

    def on_audio(event):
        nonlocal last_state
        transition = vad.process_frame(event.audio)
        current_state = vad.state

        if transition and current_state != last_state:
            symbol = "🎙️" if current_state == VADState.SPEECH else "🔇"
            logger.info("VAD: %s %s", symbol, current_state.value)
            last_state = current_state

        chunker.feed(event.audio, current_state)

    bus.subscribe(AudioEventType.AUDIO_DATA, on_audio)
    await bus.start()

    stream = CaptureStream(
        device_info=input_dev,
        mic_role=MicRole.OMNI,
        audio_format=audio_format,
        event_bus=bus,
        ring_buffer_config=RingBufferConfig(),
    )

    stream.start()

    try:
        await asyncio.sleep(duration)
    except KeyboardInterrupt:
        print("\n\nInterrupted by user")

    stream.stop()

    remaining = chunker.flush()
    if remaining is not None:
        from audio_manager.types import ChunkReadyEvent

        on_chunk(ChunkReadyEvent(
            mic_role=MicRole.OMNI,
            audio=remaining,
            duration_ms=(len(remaining) / audio_format.sample_rate) * 1000,
            timestamp=time.monotonic(),
        ))

    await bus.stop()
    pipeline.stop()

    print(f"\n{'=' * 60}")
    print(f"Done! Processed {chunk_count} chunks.")
    print(f"Buffer overflows: {stream.overflow_count}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Real-time EN→ES translator demo")
    parser.add_argument("--input-device", type=int, default=1, help="Input device index (mic)")
    parser.add_argument("--output-device", type=int, default=5, help="Output device index (speaker)")
    parser.add_argument("--duration", type=float, default=60.0, help="Recording duration in seconds")
    parser.add_argument("--whisper-model", type=str, default="small", help="Whisper model size")
    parser.add_argument("--list-devices", action="store_true", help="List audio devices and exit")
    args = parser.parse_args()

    if args.list_devices:
        list_devices()
        sys.exit(0)

    asyncio.run(main(args.input_device, args.output_device, args.duration, args.whisper_model))
