package com.travel2chicago.vadpoc.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

private const val TAG = "AudioDeviceManager"

class AudioDeviceManager(
    context: Context,
    private val bus: AudioEventBus,
) {
    data class DeviceEntry(
        val info: AudioDeviceInfo,
        val displayName: String,
        val typeName: String,
        val isPreferred: Boolean,
        val isInput: Boolean,
    ) {
        val id: Int get() = info.id
    }

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            addedDevices?.forEach { device ->
                val isInput = device.isSource
                Log.i(TAG, "Device added: ${describe(device)} (isInput=$isInput)")
                bus.emit(AudioEvent.DeviceConnected(device, isInput))
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            removedDevices?.forEach { device ->
                Log.i(TAG, "Device removed: ${describe(device)}")
                bus.emit(AudioEvent.DeviceDisconnected(device.id, describe(device)))
            }
        }
    }

    fun register() {
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun unregister() {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    fun listInputs(): List<DeviceEntry> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.toEntry(isInput = true) }

    fun listOutputs(): List<DeviceEntry> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.toEntry(isInput = false) }

    fun preferredInput(): DeviceEntry? = listInputs().firstOrNull { it.info.type.isUsbInput() }

    fun preferredOutput(): DeviceEntry? {
        val outputs = listOutputs()
        return outputs.firstOrNull { it.info.type.isUsbOutput() }
            ?: outputs.firstOrNull { it.info.type.isBluetoothOutput() }
    }

    private fun AudioDeviceInfo.toEntry(isInput: Boolean): DeviceEntry {
        val name = describe(this)
        val tName = typeName(type)
        val productLower = (productName?.toString() ?: "").lowercase()
        val preferred = if (isInput) {
            type.isUsbInput()
        } else {
            type.isUsbOutput() ||
                (type.isBluetoothOutput() && (productLower.contains("jbl") || productLower.contains("go")))
        }
        return DeviceEntry(
            info = this,
            displayName = name,
            typeName = tName,
            isPreferred = preferred,
            isInput = isInput,
        )
    }
}

internal fun describe(info: AudioDeviceInfo): String {
    val product = info.productName?.toString()?.trim().orEmpty()
    val type = typeName(info.type)
    return if (product.isNotEmpty()) "$product [$type] (id=${info.id})" else "[$type] (id=${info.id})"
}

internal fun Int.isUsbInput(): Boolean =
    this == AudioDeviceInfo.TYPE_USB_DEVICE ||
        this == AudioDeviceInfo.TYPE_USB_HEADSET ||
        this == AudioDeviceInfo.TYPE_USB_ACCESSORY

internal fun Int.isUsbOutput(): Boolean =
    this == AudioDeviceInfo.TYPE_USB_DEVICE ||
        this == AudioDeviceInfo.TYPE_USB_HEADSET ||
        this == AudioDeviceInfo.TYPE_USB_ACCESSORY

internal fun Int.isBluetoothOutput(): Boolean =
    this == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        this == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        this == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        this == AudioDeviceInfo.TYPE_BLE_SPEAKER

internal fun typeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
    AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
    AudioDeviceInfo.TYPE_HDMI -> "HDMI"
    AudioDeviceInfo.TYPE_DOCK -> "DOCK"
    AudioDeviceInfo.TYPE_FM -> "FM"
    AudioDeviceInfo.TYPE_AUX_LINE -> "AUX_LINE"
    AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
    AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
    AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
    else -> "TYPE_$type"
}
