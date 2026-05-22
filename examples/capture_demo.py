"""Demo: capture audio from a mic, run VAD, emit chunks to .wav files.

Usage:
    uv run python examples/capture_demo.py
    uv run python examples/capture_demo.py --device 1 --duration 10
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import sys
import wave
from pathlib import Path

import numpy as np

from audio_manager.audio_chunker import AudioChunker
from audio_manager.capture_stream import CaptureStream
from audio_manager.config import AudioFormat, ChunkerConfig, RingBufferConfig, VADConfig
from audio_manager.device_manager import AudioDeviceInfo, DeviceManager
from audio_manager.events import EventBus
from audio_manager.types import AudioEventType, ChunkReadyEvent, MicRole, VADState
from audio_manager.vad_processor import VADProcessor

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
)
logger = logging.getLogger("capture_demo")

OUTPUT_DIR = Path("examples/output")


def save_wav(audio: np.ndarray, path: Path, sample_rate: int = 16_000) -> None:
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(audio.tobytes())


async def main(device_index: int, duration: float) -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    print("\n=== Available Input Devices ===\n")
    for dev in DeviceManager.list_inputs():
        marker = " <--" if dev.index == device_index else ""
        print(f"  [{dev.index}] {dev.name}{marker}")

    device = DeviceManager.find_input(
        __import__("audio_manager.config", fromlist=["DeviceConfig"]).DeviceConfig(index=device_index)
    )
    print(f"\nUsing: [{device.index}] {device.name}")
    print(f"Duration: {duration}s")
    print(f"Output: {OUTPUT_DIR.absolute()}\n")

    audio_format = AudioFormat()
    vad_config = VADConfig()
    chunker_config = ChunkerConfig()
    chunk_count = 0

    def on_chunk(event: ChunkReadyEvent) -> None:
        nonlocal chunk_count
        chunk_count += 1
        filename = OUTPUT_DIR / f"chunk_{chunk_count:03d}.wav"
        save_wav(event.audio, filename, audio_format.sample_rate)
        logger.info(
            "Chunk %d: %.0fms, %d samples → %s",
            chunk_count,
            event.duration_ms,
            len(event.audio),
            filename,
        )

    vad = VADProcessor(vad_config, audio_format, MicRole.OMNI)
    vad.load_model()

    chunker = AudioChunker(chunker_config, audio_format, MicRole.OMNI, on_chunk)

    bus = EventBus()

    frame_count = 0
    last_state = VADState.SILENCE

    def on_audio(event):
        nonlocal frame_count, last_state
        frame_count += 1
        transition = vad.process_frame(event.audio)
        current_state = vad.state

        if transition and current_state != last_state:
            logger.info("VAD: %s → %s", last_state.value, current_state.value)
            last_state = current_state

        chunker.feed(event.audio, current_state)

    bus.subscribe(AudioEventType.AUDIO_DATA, on_audio)
    await bus.start()

    stream = CaptureStream(
        device_info=device,
        mic_role=MicRole.OMNI,
        audio_format=audio_format,
        event_bus=bus,
        ring_buffer_config=RingBufferConfig(),
    )

    print("Recording... (speak into the mic)")
    stream.start()

    try:
        await asyncio.sleep(duration)
    except KeyboardInterrupt:
        print("\nInterrupted by user")

    stream.stop()

    remaining = chunker.flush()
    if remaining is not None:
        chunk_count += 1
        filename = OUTPUT_DIR / f"chunk_{chunk_count:03d}.wav"
        save_wav(remaining, filename, audio_format.sample_rate)
        logger.info("Flushed final chunk: %d samples → %s", len(remaining), filename)

    await bus.stop()

    print(f"\nDone! {chunk_count} chunks saved to {OUTPUT_DIR.absolute()}")
    print(f"Frames processed: {frame_count}")
    print(f"Buffer overflows: {stream.overflow_count}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Audio capture demo with VAD")
    parser.add_argument("--device", type=int, default=0, help="Input device index")
    parser.add_argument("--duration", type=float, default=5.0, help="Recording duration in seconds")
    args = parser.parse_args()

    asyncio.run(main(args.device, args.duration))
