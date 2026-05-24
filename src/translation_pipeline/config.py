from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True, slots=True)
class STTConfig:
    model_size: str = "small"
    device: str = "cpu"
    compute_type: str = "int8"
    language: str = "en"
    beam_size: int = 1
    vad_filter: bool = False


@dataclass(frozen=True, slots=True)
class TranslatorConfig:
    source_lang: str = "en"
    target_lang: str = "es"


@dataclass(frozen=True, slots=True)
class TTSConfig:
    voice: str = "es-MX-JorgeNeural"
    rate: str = "+0%"
    volume: str = "+0%"


@dataclass(frozen=True, slots=True)
class PlayerConfig:
    output_device: int = 5
    sample_rate: int = 24_000


@dataclass(frozen=True, slots=True)
class PipelineConfig:
    stt: STTConfig = field(default_factory=STTConfig)
    translator: TranslatorConfig = field(default_factory=TranslatorConfig)
    tts: TTSConfig = field(default_factory=TTSConfig)
    player: PlayerConfig = field(default_factory=PlayerConfig)
