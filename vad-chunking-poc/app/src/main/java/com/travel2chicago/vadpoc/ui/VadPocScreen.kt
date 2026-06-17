package com.travel2chicago.vadpoc.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import com.travel2chicago.vadpoc.ChunkSummary
import com.travel2chicago.vadpoc.VadPocViewModel
import com.travel2chicago.vadpoc.audio.AudioDeviceManager
import com.travel2chicago.vadpoc.audio.VadState

@Composable
fun VadPocScreen(viewModel: VadPocViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("VAD + Chunking POC", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Oboe capture → Silero VAD (ONNX) → 3–6 s chunks → bus events",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            SectionCard(title = "MODEL") {
                val status = when {
                    state.modelLoadError != null -> "ERROR: ${state.modelLoadError}"
                    state.modelLoaded -> "Silero VAD loaded ✓"
                    else -> "Loading…"
                }
                Text(status, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Asset: silero_vad.onnx — place in app/src/main/assets/",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                    Text("No input devices — wait or check permissions", style = MaterialTheme.typography.bodySmall)
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
            SectionCard(title = "3. PIPELINE") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.startPipeline() },
                        enabled = !state.pipelineRunning && state.hasRecordPermission &&
                            state.modelLoaded && state.selectedInput != null,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (state.pipelineRunning) "Running…" else "Start pipeline") }
                    OutlinedButton(
                        onClick = { viewModel.stopPipeline() },
                        enabled = state.pipelineRunning,
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop") }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StateDot(state.vadState)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "State: ${state.vadState}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                ProbabilityBar(prob = state.vadProbability)
                Text(
                    "Prob: %.3f".format(state.vadProbability),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (state.collecting) "🎙 Chunker: collecting…" else "Chunker: idle",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.collecting) Color(0xFF43A047) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                InfoRow("Chunks emitted", "${state.chunksEmitted}")
                InfoRow("Last chunk", "${state.lastChunkDurationMs} ms, peak ${state.lastChunkPeak}")
                InfoRow("Captured samples", "${state.totalSamplesCaptured}")
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.replayLastChunk() },
                        enabled = state.lastChunkSamples != null && state.selectedOutput != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Replay last chunk") }
                    OutlinedButton(
                        onClick = { viewModel.playSineWave() },
                        enabled = state.selectedOutput != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Sine 440Hz") }
                }
                Spacer(Modifier.height(6.dp))
                InfoRow("Sink back-end", state.playbackSinkLabel)
            }
        }

        item {
            SectionCard(title = "5. VAD TUNING") {
                SliderRow(
                    label = "threshold",
                    value = state.vadConfig.threshold,
                    onValueChange = { viewModel.setVadThreshold(it) },
                    valueRange = 0.05f..0.95f,
                    formatValue = { "%.2f".format(it) },
                )
                IntSliderRow(
                    label = "minSpeechMs",
                    value = state.vadConfig.minSpeechMs,
                    onValueChange = { viewModel.setVadMinSpeechMs(it) },
                    valueRange = 0..1000,
                    suffix = "ms",
                )
                IntSliderRow(
                    label = "minSilenceMs",
                    value = state.vadConfig.minSilenceMs,
                    onValueChange = { viewModel.setVadMinSilenceMs(it) },
                    valueRange = 0..2000,
                    suffix = "ms",
                )
            }
        }

        item {
            SectionCard(title = "6. CHUNKER TUNING") {
                IntSliderRow(
                    label = "minChunkMs",
                    value = state.chunkerConfig.minChunkMs,
                    onValueChange = { viewModel.setChunkerMinMs(it) },
                    valueRange = 200..10_000,
                    suffix = "ms",
                )
                IntSliderRow(
                    label = "maxChunkMs",
                    value = state.chunkerConfig.maxChunkMs,
                    onValueChange = { viewModel.setChunkerMaxMs(it) },
                    valueRange = 500..15_000,
                    suffix = "ms",
                )
                IntSliderRow(
                    label = "silenceEndMs",
                    value = state.chunkerConfig.silenceEndMs,
                    onValueChange = { viewModel.setChunkerSilenceEndMs(it) },
                    valueRange = 50..3000,
                    suffix = "ms",
                )
                IntSliderRow(
                    label = "preRollMs",
                    value = state.chunkerConfig.preRollMs,
                    onValueChange = { viewModel.setChunkerPreRollMs(it) },
                    valueRange = 0..1000,
                    suffix = "ms",
                )
            }
        }

        item {
            SectionCard(title = "7. CHUNK HISTORY (last ${state.chunkHistory.size})") {
                if (state.chunkHistory.isEmpty()) {
                    Text("No chunks yet — start the pipeline and talk", style = MaterialTheme.typography.bodySmall)
                } else {
                    state.chunkHistory.forEach { ChunkHistoryRow(it) }
                }
            }
        }

        item {
            SectionCard(title = "8. LOGS") {
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
                                line.contains("Chunk #") -> Color(0xFF69F0AE)
                                line.contains("VAD →") -> Color(0xFFFFD740)
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

@Composable
private fun StateDot(vadState: VadState) {
    val color = if (vadState == VadState.SPEECH) Color(0xFF43A047) else Color(0xFF9E9E9E)
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun ProbabilityBar(prob: Float) {
    val animated by animateFloatAsState(targetValue = prob.coerceIn(0f, 1f), label = "vadProb")
    val color = when {
        animated > 0.7f -> Color(0xFF43A047)
        animated > 0.4f -> Color(0xFFFB8C00)
        else -> Color(0xFF9E9E9E)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
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

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    formatValue: (Float) -> String,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatValue(value), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
    }
}

@Composable
private fun IntSliderRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: IntRange,
    suffix: String = "",
) {
    val floatRange = valueRange.first.toFloat()..valueRange.last.toFloat()
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$value $suffix", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = floatRange,
        )
    }
}

@Composable
private fun ChunkHistoryRow(c: ChunkSummary) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "#${c.index.toString().padStart(3, '0')}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(48.dp),
        )
        Text(
            "${c.durationMs}ms",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(72.dp),
        )
        Text(
            "peak ${c.peak}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}
