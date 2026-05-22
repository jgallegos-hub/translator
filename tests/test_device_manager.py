from __future__ import annotations

from unittest.mock import patch

import pytest

from audio_manager.config import DeviceConfig
from audio_manager.device_manager import AudioDeviceInfo, DeviceManager
from audio_manager.exceptions import DeviceNotFoundError


@pytest.fixture
def _patch_devices(mock_devices):
    with patch("audio_manager.device_manager.sd.query_devices", return_value=mock_devices):
        yield


@pytest.mark.usefixtures("_patch_devices")
class TestDeviceManagerListAll:
    def test_lists_all_devices(self):
        devices = DeviceManager.list_all()
        assert len(devices) == 4

    def test_device_info_fields(self):
        devices = DeviceManager.list_all()
        omni = devices[0]
        assert omni.name == "USB Omni Microphone"
        assert omni.index == 0
        assert omni.max_input_channels == 2
        assert omni.is_input is True
        assert omni.is_output is False


@pytest.mark.usefixtures("_patch_devices")
class TestDeviceManagerFilter:
    def test_list_inputs(self):
        inputs = DeviceManager.list_inputs()
        assert len(inputs) == 3
        assert all(d.is_input for d in inputs)

    def test_list_outputs(self):
        outputs = DeviceManager.list_outputs()
        assert len(outputs) == 2
        assert all(d.is_output for d in outputs)


@pytest.mark.usefixtures("_patch_devices")
class TestDeviceManagerFind:
    def test_find_by_index(self):
        device = DeviceManager.find(DeviceConfig(index=1))
        assert device.name == "Boya BY-M1 Lavalier"

    def test_find_by_name(self):
        device = DeviceManager.find(DeviceConfig(name="Boya"))
        assert device.index == 1

    def test_find_by_name_case_insensitive(self):
        device = DeviceManager.find(DeviceConfig(name="boya"))
        assert device.index == 1

    def test_find_not_found_index(self):
        with pytest.raises(DeviceNotFoundError):
            DeviceManager.find(DeviceConfig(index=99))

    def test_find_not_found_name(self):
        with pytest.raises(DeviceNotFoundError):
            DeviceManager.find(DeviceConfig(name="Nonexistent"))

    def test_find_input_valid(self):
        device = DeviceManager.find_input(DeviceConfig(index=0))
        assert device.is_input is True

    def test_find_input_rejects_output_only(self):
        with pytest.raises(DeviceNotFoundError, match="no input channels"):
            DeviceManager.find_input(DeviceConfig(index=3))

    def test_find_output_valid(self):
        device = DeviceManager.find_output(DeviceConfig(index=3))
        assert device.is_output is True

    def test_find_output_rejects_input_only(self):
        with pytest.raises(DeviceNotFoundError, match="no output channels"):
            DeviceManager.find_output(DeviceConfig(index=1))


class TestAudioDeviceInfo:
    def test_immutable(self):
        info = AudioDeviceInfo(0, "Test", 2, 0, 44100.0, 0)
        with pytest.raises(AttributeError):
            info.name = "Other"  # type: ignore[misc]

    def test_is_input_output(self):
        both = AudioDeviceInfo(0, "Both", 2, 2, 44100.0, 0)
        assert both.is_input is True
        assert both.is_output is True

        input_only = AudioDeviceInfo(0, "In", 1, 0, 44100.0, 0)
        assert input_only.is_input is True
        assert input_only.is_output is False
