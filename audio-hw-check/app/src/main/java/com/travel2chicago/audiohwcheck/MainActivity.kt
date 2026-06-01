package com.travel2chicago.audiohwcheck

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.travel2chicago.audiohwcheck.ui.theme.AudioHwCheckTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AudioHwCheckViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val record = result[Manifest.permission.RECORD_AUDIO] == true ||
            hasPermission(Manifest.permission.RECORD_AUDIO)
        val bluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            result[Manifest.permission.BLUETOOTH_CONNECT] == true ||
                hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true  // BLUETOOTH_CONNECT does not exist below API 31
        }
        viewModel.onPermissionsUpdated(hasRecord = record, hasBluetooth = bluetooth)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestPermissionsIfNeeded()

        setContent {
            AudioHwCheckTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AudioHwCheckScreen(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check permissions in case the user toggled them in Settings.
        val record = hasPermission(Manifest.permission.RECORD_AUDIO)
        val bluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }
        viewModel.onPermissionsUpdated(hasRecord = record, hasBluetooth = bluetooth)
    }

    private fun requestPermissionsIfNeeded() {
        val toRequest = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            toRequest += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            toRequest += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
