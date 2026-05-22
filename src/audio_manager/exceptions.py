from __future__ import annotations


class AudioManagerError(Exception):
    pass


class DeviceNotFoundError(AudioManagerError):
    def __init__(self, identifier: str | int) -> None:
        self.identifier = identifier
        super().__init__(f"Audio device not found: {identifier}")


class DeviceDisconnectedError(AudioManagerError):
    def __init__(self, device_name: str) -> None:
        self.device_name = device_name
        super().__init__(f"Audio device disconnected: {device_name}")


class StreamError(AudioManagerError):
    pass


class BufferOverflowError(AudioManagerError):
    def __init__(self, dropped_frames: int) -> None:
        self.dropped_frames = dropped_frames
        super().__init__(f"Ring buffer overflow: {dropped_frames} frames dropped")
