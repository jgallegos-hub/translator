"""End-to-end demo: capture speech → translate → play translated audio.

Supports both directions: EN→ES and ES→EN.

Usage:
    # Spanish → English (default)
    uv run python examples/translate_demo.py

    # English → Spanish
    uv run python examples/translate_demo.py --direction en-es

    # Custom devices and duration
    uv run python examples/translate_demo.py --input-device 1 --output-device 5 --duration 120

    # List audio devices
    uv run python examples/translate_demo.py --list-devices
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

# --- Direction presets ---
DIRECTION_PRESETS = {
    "es-en": {
        "stt_language": "es",
        "source_lang": "es",
        "target_lang": "en",
        "tts_voice": "en-US-GuyNeural",
        "source_label": "ES",
        "target_label": "EN",
        "speak_language": "Spanish",
    },
    "en-es": {
        "stt_language": "en",
        "source_lang": "en",
        "target_lang": "es",
        "tts_voice": "es-MX-JorgeNeural",
        "source_label": "EN",
        "target_label": "ES",
        "speak_language": "English",
    },
}


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
    direction: str,
) -> None:
    preset = DIRECTION_PRESETS[direction]

    # --- Config ---
    audio_format = AudioFormat()

    # Chunking inteligente por frases completas:
    #   - min 3s: acumula suficiente contexto para frases entendibles
    #   - silence_end 700ms: pausa natural indica fin de frase
    #   - max 6s: no acumular demasiado
    #   - pre_roll 200ms: capturar inicio de frase
    vad_config = VADConfig(threshold=0.5, min_silence_ms=700.0, pre_roll_ms=200.0)
    chunker_config = ChunkerConfig(
        min_chunk_ms=3000.0,
        max_chunk_ms=6000.0,
        silence_end_ms=700.0,
        pre_roll_ms=200.0,
    )

    pipeline_config = PipelineConfig(
        stt=STTConfig(
            model_size=whisper_model,
            device="cpu",
            compute_type="int8",
            language=preset["stt_language"],
        ),
        translator=TranslatorConfig(
            source_lang=preset["source_lang"],
            target_lang=preset["target_lang"],
        ),
        tts=TTSConfig(voice=preset["tts_voice"]),
        player=PlayerConfig(output_device=output_device),
    )

    # --- Resolve devices ---
    input_dev = DeviceManager.find_input(DeviceConfig(index=input_device))
    print(f"\n  Direction: {direction.upper()}")
    print(f"  Input:  [{input_dev.index}] {input_dev.name}")
    print(f"  Output: device {output_device}")
    print(f"  Whisper: {whisper_model} (lang={preset['stt_language']})")
    print(f"  Chunking: 3-6s, 700ms silence pause")
    print(f"  Duration: {duration}s\n")

    # --- Load models (one-time) ---
    print("Loading models (first run downloads ~1GB, be patient)...")
    pipeline = TranslationPipeline(pipeline_config)
    pipeline.load_models()

    vad = VADProcessor(vad_config, audio_format, MicRole.OMNI)
    vad.load_model()

    src_label = preset["source_label"]
    tgt_label = preset["target_label"]
    print(f"Models loaded. Speak in {preset['speak_language']}!\n")
    print("=" * 60)

    # --- Pipeline processing ---
    chunk_count = 0
    processing_lock = threading.Lock()

    def on_chunk(event):
        nonlocal chunk_count

        with processing_lock:
            chunk_count += 1
            n = chunk_count
            dur = event.duration_ms

        logger.info("--- Chunk %d (%.1fs) ---", n, dur / 1000)

        result = pipeline.process_chunk(event.audio)

        if result["original_text"].strip():
            print(f"\n  [{n}] {src_label}: {result['original_text']}")
            print(f"       {tgt_label}: {result['translated_text']}")
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
    parser = argparse.ArgumentParser(description="Real-time bidirectional translator")
    parser.add_argument("--direction", type=str, default="es-en",
                        choices=["es-en", "en-es"], help="Translation direction")
    parser.add_argument("--input-device", type=int, default=1, help="Input device index")
    parser.add_argument("--output-device", type=int, default=5, help="Output device index")
    parser.add_argument("--duration", type=float, default=60.0, help="Recording duration (seconds)")
    parser.add_argument("--whisper-model", type=str, default="small", help="Whisper model size")
    parser.add_argument("--list-devices", action="store_true", help="List audio devices and exit")
    args = parser.parse_args()

    if args.list_devices:
        list_devices()
        sys.exit(0)

    asyncio.run(main(
        args.input_device, args.output_device, args.duration,
        args.whisper_model, args.direction,
    ))
