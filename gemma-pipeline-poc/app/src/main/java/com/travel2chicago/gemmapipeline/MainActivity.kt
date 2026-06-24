package com.travel2chicago.gemmapipeline

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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
import com.travel2chicago.gemmapipeline.ui.GemmaPipelineScreen
import com.travel2chicago.gemmapipeline.ui.theme.GemmaPipelineTheme

class MainActivity : ComponentActivity() {

    private val viewModel: GemmaPipelineViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        viewModel.onPermissionsUpdated(
            record = hasPermission(Manifest.permission.RECORD_AUDIO),
            bluetooth = bluetoothGranted(),
            storage = storageGranted(),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestStandardPermissionsIfNeeded()

        setContent {
            GemmaPipelineTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    GemmaPipelineScreen(
                        viewModel = viewModel,
                        onRequestStoragePermission = { openManageAllFilesAccess() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onPermissionsUpdated(
            record = hasPermission(Manifest.permission.RECORD_AUDIO),
            bluetooth = bluetoothGranted(),
            storage = storageGranted(),
        )
    }

    private fun requestStandardPermissionsIfNeeded() {
        val toRequest = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            toRequest += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            toRequest += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            !hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        ) {
            toRequest += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    /**
     * On Android 11+ accessing arbitrary files under /sdcard requires
     * MANAGE_EXTERNAL_STORAGE which is a per-app system setting (not a
     * runtime dialog). We send the user to the dedicated settings screen
     * and re-check on next onResume.
     */
    private fun openManageAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun storageGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun bluetoothGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
