from __future__ import annotations

import logging
from dataclasses import dataclass

import sounddevice as sd

from .config import DeviceConfig
from .exceptions import DeviceNotFoundError

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class AudioDeviceInfo:
    index: int
    name: str
    max_input_channels: int
    max_output_channels: int
    default_samplerate: float
    hostapi: int

    @property
    def is_input(self) -> bool:
        return self.max_input_channels > 0

    @property
    def is_output(self) -> bool:
        return self.max_output_channels > 0


class DeviceManager:
    """Enumerate and select audio devices via sounddevice/PortAudio."""

    @staticmethod
    def list_all() -> list[AudioDeviceInfo]:
        devices = sd.query_devices()
        if isinstance(devices, dict):
            devices = [devices]
        return [
            AudioDeviceInfo(
                index=i,
                name=str(d["name"]),
                max_input_channels=int(d["max_input_channels"]),
                max_output_channels=int(d["max_output_channels"]),
                default_samplerate=float(d["default_samplerate"]),
                hostapi=int(d["hostapi"]),
            )
            for i, d in enumerate(devices)
        ]

    @staticmethod
    def list_inputs() -> list[AudioDeviceInfo]:
        return [d for d in DeviceManager.list_all() if d.is_input]

    @staticmethod
    def list_outputs() -> list[AudioDeviceInfo]:
        return [d for d in DeviceManager.list_all() if d.is_output]

    @staticmethod
    def find(config: DeviceConfig) -> AudioDeviceInfo:
        devices = DeviceManager.list_all()

        if config.index is not None:
            for d in devices:
                if d.index == config.index:
                    return d
            raise DeviceNotFoundError(config.index)

        if config.name is not None:
            name_lower = config.name.lower()
            for d in devices:
                if name_lower in d.name.lower():
                    return d
            raise DeviceNotFoundError(config.name)

        raise DeviceNotFoundError("no identifier provided")

    @staticmethod
    def find_input(config: DeviceConfig) -> AudioDeviceInfo:
        device = DeviceManager.find(config)
        if not device.is_input:
            raise DeviceNotFoundError(
                f"{device.name} (index {device.index}) has no input channels"
            )
        return device

    @staticmethod
    def find_output(config: DeviceConfig) -> AudioDeviceInfo:
        device = DeviceManager.find(config)
        if not device.is_output:
            raise DeviceNotFoundError(
                f"{device.name} (index {device.index}) has no output channels"
            )
        return device


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    print("\n=== Audio Devices ===\n")
    for dev in DeviceManager.list_all():
        direction = []
        if dev.is_input:
            direction.append(f"IN({dev.max_input_channels}ch)")
        if dev.is_output:
            direction.append(f"OUT({dev.max_output_channels}ch)")
        print(f"  [{dev.index}] {dev.name}  {' | '.join(direction)}  @ {dev.default_samplerate}Hz")
