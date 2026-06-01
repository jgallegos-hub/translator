package com.travel2chicago.audiopoc.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.travel2chicago.audiopoc.AudioPocViewModel
import com.travel2chicago.audiopoc.audio.AudioDeviceManager

@Composable
fun AudioPocScreen(viewModel: AudioPocViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Audio Capture POC (Oboe)", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "USB mic → Oboe → ring buffer → SharedFlow → playback (loop / sine)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            SectionCard(title = "PERMISSIONS") {
                InfoRow("RECORD_AUDIO", if (state.hasRecordPermission) "GRANTED ✓" else "DENIED ✗")
                InfoRow("BLUETOOTH_CONNECT", if (state.hasBluetoothPermission) "GRANTED ✓" else "DENIED ✗")
            }
        }

        item {
            SectionCard(title = "1. INPUT DEVICES (${state.inputDevices.size})") {
                if (state.inputDevices.isEmpty()) {
                    Text(
                        "No input devices — wait or check permissions",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.inputDevices.forEach { device ->
                        DeviceRow(device, device.id == state.selectedInputId) { viewModel.selectInput(device.id) }
                    }
                }
            }
        }

        item {
            SectionCard(title = "2. OUTPUT DEVICES (${state.outputDevices.size})") {
                if (state.outputDevices.isEmpty()) {
                    Text("No output devices", style = MaterialTheme.typography.bodySmall)
                } else {
                    state.outputDevices.forEach { device ->
                        DeviceRow(device, device.id == state.selectedOutputId) { viewModel.selectOutput(device.id) }
                    }
                }
            }
        }

        item {
            SectionCard(title = "3. CAPTURE") {
                VuMeter(level = state.vuLevel)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.startCapture() },
                        enabled = !state.capturing && state.hasRecordPermission && state.selectedInput != null,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (state.capturing) "Capturing…" else "Start capture") }
                    OutlinedButton(
                        onClick = { viewModel.stopCapture() },
                        enabled = state.capturing,
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop") }
                }
                Spacer(Modifier.height(6.dp))
                InfoRow("Routed input id", state.captureRoutedDeviceId.toString())
                InfoRow("Actual sample rate", "${state.captureSampleRate} Hz")
                InfoRow("Latency", "${state.captureLatencyMs} ms")
                InfoRow("Samples captured", "${state.totalSamplesCaptured}")
                InfoRow("Last chunk frames", "${state.lastChunkFrames}")
                InfoRow("Last chunk peak", "${state.lastChunkPeak}")
                InfoRow("Ring overflow", "${state.overflowCount}")
            }
        }

        item {
            SectionCard(title = "4. PLAYBACK") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.startPlayback() },
                        enabled = !state.playing && state.selectedOutput != null,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (state.playing) "Playing…" else "Start playback") }
                    OutlinedButton(
                        onClick = { viewModel.stopPlayback() },
                        enabled = state.playing,
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop") }
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { viewModel.playSineWave() },
                    enabled = state.selectedOutput != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Play 440Hz sine 1.5s") }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = state.loopback,
                        onCheckedChange = { viewModel.setLoopback(it) },
                        enabled = state.capturing && state.playing,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Loopback (mic → speaker)", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(6.dp))
                InfoRow("Sink back-end", state.playbackSinkLabel)
                InfoRow("Routed output id", state.playbackRoutedDeviceId.toString())
                InfoRow("Actual sample rate", "${state.playbackSampleRate} Hz")
                InfoRow(
                    "Latency",
                    if (state.playbackLatencyMs < 0) "n/a" else "${state.playbackLatencyMs} ms",
                )
                InfoRow("Underflow / dropped samples", "${state.underflowCount}")
            }
        }

        item {
            SectionCard(title = "5. LOGS") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .background(Color(0xFF1E1E1E), RoundedCornerShape(4.dp))
                        .padding(8.dp),
                ) {
                    val listState = rememberLazyListState()
                    LaunchedEffect(state.logs.size) {
                        if (state.logs.isNotEmpty()) listState.animateScrollToItem(state.logs.size - 1)
                    }
                    LazyColumn(state = listState) {
                        items(state.logs) { line ->
                            val color = when {
                                line.contains("[ERROR]") -> Color(0xFFFF5252)
                                line.contains("OVERFLOW") -> Color(0xFFFF9100)
                                line.contains("✓") -> Color(0xFF69F0AE)
                                line.contains("Capture started") || line.contains("Playback started") -> Color(0xFFFFD740)
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

        state.error?.let { err ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(err, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ── Components ───────────────────────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun DeviceRow(entry: AudioDeviceManager.DeviceEntry, isSelected: Boolean, onClick: () -> Unit) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp)),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (isSelected) "●" else "○", fontWeight = FontWeight.Bold,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(entry.displayName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (entry.isPreferred) {
                Text("★", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Simple VU meter: a horizontal bar whose fill animates to the latest RMS
 * level. Color shifts from green → yellow → red as the level approaches
 * full scale. Animates smoothly so the bar feels alive even between
 * the (drainIntervalMs = 25 ms) chunks.
 */
@Composable
private fun VuMeter(level: Float) {
    val animated by animateFloatAsState(targetValue = level.coerceIn(0f, 1f), label = "vu")
    val color = when {
        animated > 0.85f -> Color(0xFFE53935)
        animated > 0.6f -> Color(0xFFFB8C00)
        else -> Color(0xFF43A047)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF2A2A2A)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .background(color),
        )
    }
}
