from __future__ import annotations

import enum
from dataclasses import dataclass
from typing import Callable

import numpy as np


class MicRole(enum.Enum):
    OMNI = "omni"
    LAVALIER = "lavalier"


class VADState(enum.Enum):
    SILENCE = "silence"
    SPEECH = "speech"


class AudioEventType(enum.Enum):
    AUDIO_DATA = "audio_data"
    VAD_TRANSITION = "vad_transition"
    CHUNK_READY = "chunk_ready"
    DEVICE_LOST = "device_lost"


@dataclass(frozen=True, slots=True)
class AudioDataEvent:
    mic_role: MicRole
    audio: np.ndarray
    timestamp: float


@dataclass(frozen=True, slots=True)
class VADTransitionEvent:
    mic_role: MicRole
    state: VADState
    timestamp: float


@dataclass(frozen=True, slots=True)
class ChunkReadyEvent:
    mic_role: MicRole
    audio: np.ndarray
    duration_ms: float
    timestamp: float


@dataclass(frozen=True, slots=True)
class DeviceLostEvent:
    mic_role: MicRole
    device_name: str
    timestamp: float


AudioEvent = AudioDataEvent | VADTransitionEvent | ChunkReadyEvent | DeviceLostEvent

ChunkCallback = Callable[[ChunkReadyEvent], None]
