from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True, slots=True)
class AudioFormat:
    sample_rate: int = 16_000
    channels: int = 1
    dtype: str = "int16"
    block_size: int = 512

    @property
    def frame_duration_ms(self) -> float:
        return (self.block_size / self.sample_rate) * 1000


@dataclass(frozen=True, slots=True)
class DeviceConfig:
    index: int | None = None
    name: str | None = None

    def __post_init__(self) -> None:
        if self.index is None and self.name is None:
            raise ValueError("DeviceConfig requires either index or name")


@dataclass(frozen=True, slots=True)
class VADConfig:
    threshold: float = 0.5
    min_speech_ms: float = 50.0
    min_silence_ms: float = 300.0
    pre_roll_ms: float = 100.0

    def __post_init__(self) -> None:
        if not 0.0 < self.threshold < 1.0:
            raise ValueError(f"threshold must be in (0, 1), got {self.threshold}")


@dataclass(frozen=True, slots=True)
class ChunkerConfig:
    min_chunk_ms: float = 500.0
    max_chunk_ms: float = 2000.0
    silence_end_ms: float = 300.0
    pre_roll_ms: float = 100.0


@dataclass(frozen=True, slots=True)
class RingBufferConfig:
    capacity_seconds: float = 5.0


@dataclass(frozen=True, slots=True)
class AudioManagerConfig:
    audio_format: AudioFormat = field(default_factory=AudioFormat)
    omni_device: DeviceConfig = field(default_factory=lambda: DeviceConfig(index=0))
    lavalier_device: DeviceConfig = field(default_factory=lambda: DeviceConfig(index=1))
    vad: VADConfig = field(default_factory=VADConfig)
    chunker: ChunkerConfig = field(default_factory=ChunkerConfig)
    ring_buffer: RingBufferConfig = field(default_factory=RingBufferConfig)
