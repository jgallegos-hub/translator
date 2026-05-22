from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from .capture_stream import CaptureStream
from .config import AudioManagerConfig
from .device_manager import DeviceManager
from .types import MicRole

if TYPE_CHECKING:
    from .events import EventBus

logger = logging.getLogger(__name__)


class DualMicCapture:
    """Orchestrates two CaptureStreams: omnidirectional (English) and lavalier (Spanish)."""

    def __init__(self, config: AudioManagerConfig, event_bus: EventBus) -> None:
        self._config = config
        self._event_bus = event_bus
        self._omni: CaptureStream | None = None
        self._lavalier: CaptureStream | None = None

    @property
    def omni(self) -> CaptureStream | None:
        return self._omni

    @property
    def lavalier(self) -> CaptureStream | None:
        return self._lavalier

    @property
    def is_running(self) -> bool:
        omni_running = self._omni.is_running if self._omni else False
        lav_running = self._lavalier.is_running if self._lavalier else False
        return omni_running or lav_running

    def start(self) -> None:
        omni_device = DeviceManager.find_input(self._config.omni_device)
        lavalier_device = DeviceManager.find_input(self._config.lavalier_device)

        logger.info(
            "Starting dual capture: omni=[%s], lavalier=[%s]",
            omni_device.name,
            lavalier_device.name,
        )

        self._omni = CaptureStream(
            device_info=omni_device,
            mic_role=MicRole.OMNI,
            audio_format=self._config.audio_format,
            event_bus=self._event_bus,
            ring_buffer_config=self._config.ring_buffer,
        )
        self._lavalier = CaptureStream(
            device_info=lavalier_device,
            mic_role=MicRole.LAVALIER,
            audio_format=self._config.audio_format,
            event_bus=self._event_bus,
            ring_buffer_config=self._config.ring_buffer,
        )

        self._omni.start()
        self._lavalier.start()

    def stop(self) -> None:
        logger.info("Stopping dual capture")
        if self._omni:
            self._omni.stop()
        if self._lavalier:
            self._lavalier.stop()

    def mute_omni(self) -> None:
        if self._omni and self._omni.is_running:
            self._omni.stop()
            logger.info("Omni mic muted")

    def unmute_omni(self) -> None:
        if self._omni and not self._omni.is_running:
            self._omni.start()
            logger.info("Omni mic unmuted")
