package com.travel2chicago.audiohwcheck

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AudioHwCheckScreen(viewModel: AudioHwCheckViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header ─────────────────────────────────────────────────────────────
        item {
            Text(
                text = "Audio HW Check",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Saramonic USB mic → JBL Go 4 BT speaker (no NDK, no Oboe)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Permissions ────────────────────────────────────────────────────────
        item {
            SectionCard(title = "PERMISSIONS") {
                InfoRow("RECORD_AUDIO", if (state.hasRecordPermission) "GRANTED ✓" else "DENIED ✗")
                InfoRow(
                    "BLUETOOTH_CONNECT",
                    if (state.hasBluetoothPermission) "GRANTED ✓" else "DENIED ✗",
                )
                if (!state.hasRecordPermission) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Grant RECORD_AUDIO in system dialog or Settings to enable recording.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // Input devices ──────────────────────────────────────────────────────
        item {
            SectionCard(title = "1. INPUT DEVICES (${state.inputDevices.size})") {
                if (state.inputDevices.isEmpty()) {
                    Text(
                        "No input devices found — wait for detection or check permissions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.inputDevices.forEach { device ->
                        DeviceRow(
                            entry = device,
                            isSelected = device.id == state.selectedInputId,
                            onClick = { viewModel.selectInput(device.id) },
                        )
                    }
                }
            }
        }

        // Output devices ─────────────────────────────────────────────────────
        item {
            SectionCard(title = "2. OUTPUT DEVICES (${state.outputDevices.size})") {
                if (state.outputDevices.isEmpty()) {
                    Text(
                        "No output devices found.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.outputDevices.forEach { device ->
                        DeviceRow(
                            entry = device,
                            isSelected = device.id == state.selectedOutputId,
                            onClick = { viewModel.selectOutput(device.id) },
                        )
                    }
                }
            }
        }

        // Action buttons ─────────────────────────────────────────────────────
        item {
            SectionCard(title = "3. CAPTURE + PLAYBACK TEST") {
                val inputLabel = state.selectedInput?.displayName ?: "(no input)"
                val outputLabel = state.selectedOutput?.displayName ?: "(no output)"

                Text("Source: $inputLabel", style = MaterialTheme.typography.bodySmall)
                Text("Sink:   $outputLabel", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = { viewModel.startRecording() },
                    enabled = !state.recording && !state.playing &&
                        state.hasRecordPermission && state.selectedInput != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.recording) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Recording 5s...")
                    } else {
                        Text("🎤 RECORD 5s from selected input")
                    }
                }

                Spacer(Modifier.height(6.dp))

                Button(
                    onClick = { viewModel.playRecording() },
                    enabled = !state.recording && !state.playing &&
                        state.recordedSamples != null && state.selectedOutput != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.playing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Playing...")
                    } else {
                        Text("▶ PLAY recording on selected output")
                    }
                }
            }
        }

        // Status ─────────────────────────────────────────────────────────────
        item {
            SectionCard(title = "4. STATUS") {
                if (state.recordedSamples == null && state.playbackOk == null) {
                    Text(
                        "No recording yet. Tap RECORD to start.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.recordedSamples?.let { samples ->
                        InfoRow("Recorded samples", "${samples.size}")
                        InfoRow(
                            "Recorded duration",
                            "${"%.2f".format(samples.size.toFloat() / 16_000)}s wall=${state.recordedDurationMs}ms",
                        )
                        InfoRow(
                            "RMS avg",
                            "${"%.1f".format(state.recordedRmsAvg)}" +
                                if (state.recordedRmsAvg > 100.0) " (signal OK)" else " (silent?)",
                        )
                        state.recordingRoutedDeviceName?.let { name ->
                            val matchSym = when (state.recordingRoutingMatchesPreferred) {
                                true -> "✓"
                                false -> "✗"
                                null -> "?"
                            }
                            InfoRow("Routed input", "$matchSym $name")
                        }
                    }

                    state.playbackOk?.let { ok ->
                        Spacer(Modifier.height(6.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(6.dp))
                        InfoRow("Playback", if (ok) "OK ✓" else "FAILED ✗")
                        state.playbackRoutedDeviceName?.let { name ->
                            val matchSym = when (state.playbackRoutingMatchesPreferred) {
                                true -> "✓"
                                false -> "✗"
                                null -> "?"
                            }
                            InfoRow("Routed output", "$matchSym $name")
                        }
                    }
                }

                state.error?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Logs ───────────────────────────────────────────────────────────────
        item {
            SectionCard(title = "5. LOGS") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .background(Color(0xFF1E1E1E), RoundedCornerShape(4.dp))
                        .padding(8.dp),
                ) {
                    val listState = rememberLazyListState()
                    LaunchedEffect(state.logs.size) {
                        if (state.logs.isNotEmpty()) {
                            listState.animateScrollToItem(state.logs.size - 1)
                        }
                    }
                    LazyColumn(state = listState) {
                        items(state.logs) { line ->
                            val color = when {
                                line.contains("[ERROR]") -> Color(0xFFFF5252)
                                line.contains("✓") || line.contains("OK") -> Color(0xFF69F0AE)
                                line.contains("===") -> Color(0xFFFFD740)
                                else -> Color(0xFFE0E0E0)
                            }
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = color,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ── Reusable composables ─────────────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.65f),
        )
    }
}

@Composable
private fun DeviceRow(entry: DeviceEntry, isSelected: Boolean, onClick: () -> Unit) {
    val borderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(8.dp)),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isSelected) "●" else "○",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (entry.isPreferred) {
                Text(
                    text = "★ target",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
