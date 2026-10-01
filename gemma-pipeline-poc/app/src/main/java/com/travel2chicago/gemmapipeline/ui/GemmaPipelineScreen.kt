package com.travel2chicago.gemmapipeline.ui

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
import com.travel2chicago.gemmapipeline.GemmaPipelineViewModel
import com.travel2chicago.gemmapipeline.TranslationEntry
import com.travel2chicago.gemmapipeline.ast.AstConfig
import com.travel2chicago.gemmapipeline.audio.AudioDeviceManager
import com.travel2chicago.gemmapipeline.audio.VadState
import com.travel2chicago.gemmapipeline.tts.TtsConfig

@Composable
fun GemmaPipelineScreen(
    viewModel: GemmaPipelineViewModel = viewModel(),
    onRequestStoragePermission: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Gemma Pipeline POC", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Capture → VAD → Chunker → Gemma 4 E4B AST (ES → EN)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            SectionCard(title = "GEMMA ENGINE") {
                val statusLine = when {
                    state.gemmaLoadError != null -> "ERROR: ${state.gemmaLoadError}"
                    state.gemmaLoaded -> "Loaded ✓ (${state.gemmaBackend}, ${state.gemmaLoadTimeMs}ms)"
                    state.gemmaLoading -> "Loading… (10–30s first run)"
                    !state.hasStoragePermission -> "Waiting for storage permission"
                    !state.gemmaModelExists -> "Model file not found"
                    else -> "Idle (tap Load below)"
                }
                Text(statusLine, style = MaterialTheme.typography.bodyMedium)
                Text(
                    state.gemmaModelPath,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!state.hasStoragePermission) {
                        Button(
                            onClick = onRequestStoragePermission,
                            modifier = Modifier.weight(1f),
                        ) { Text("Grant storage permission") }
                    } else if (!state.gemmaLoaded && !state.gemmaLoading) {
                        Button(
                            onClick = { viewModel.loadGemmaEngine() },
                            enabled = state.gemmaModelExists,
                            modifier = Modifier.weight(1f),
                        ) { Text("Load Gemma engine") }
                    }
                }
            }
        }

        item {
            SectionCard(title = "TTS ENGINE (Kokoro)") {
                val statusLine = when {
                    state.kokoroLoadError != null -> "ERROR: ${state.kokoroLoadError}"
                    state.kokoroLoaded -> "Loaded ✓ (${state.kokoroLoadTimeMs}ms, voice=${state.kokoroVoice})"
                    state.kokoroLoading -> "Loading… (5–15s first run)"
                    !state.hasStoragePermission -> "Waiting for storage permission"
                    !state.kokoroModelExists -> "Model files not found"
                    else -> "Idle (tap Load below)"
                }
                Text(statusLine, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "/sdcard/Download/kokoro_model/{kokoro-v1.0.onnx, voices-v1.0.bin}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!state.hasStoragePermission) {
                        Button(
                            onClick = onRequestStoragePermission,
                            modifier = Modifier.weight(1f),
                        ) { Text("Grant storage permission") }
                    } else if (!state.kokoroLoaded && !state.kokoroLoading) {
                        Button(
                            onClick = { viewModel.loadKokoroEngine() },
                            enabled = state.kokoroModelExists,
                            modifier = Modifier.weight(1f),
                        ) { Text("Load Kokoro engine") }
                    }
                }
            }
        }

        item {
            SectionCard(title = "PERMISSIONS") {
                InfoRow("RECORD_AUDIO", if (state.hasRecordPermission) "GRANTED ✓" else "DENIED ✗")
                InfoRow("BLUETOOTH_CONNECT", if (state.hasBluetoothPermission) "GRANTED ✓" else "DENIED ✗")
                InfoRow("MANAGE_EXTERNAL_STORAGE", if (state.hasStoragePermission) "GRANTED ✓" else "DENIED ✗")
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
                        enabled = !state.pipelineRunning && state.pipelineReady && state.selectedInput != null,
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
                    Text("State: ${state.vadState}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
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
                InfoRow("Ring overflow", "${state.overflowCount}")
            }
        }

        item {
            SectionCard(title = "3½. FASE 6 STREAMING") {
                Text(
                    "Toggle each stage independently. Router restarts on the " +
                        "next event; the pipeline itself keeps running.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                SwitchRow(
                    label = "AST streaming (Gemma → per-sentence)",
                    checked = state.astStreamingEnabled,
                    onCheckedChange = { viewModel.setAstStreamingEnabled(it) },
                )
                SwitchRow(
                    label = "TTS streaming (Kokoro → per-sentence PCM)",
                    checked = state.ttsStreamingEnabled,
                    onCheckedChange = { viewModel.setTtsStreamingEnabled(it) },
                )
                SwitchRow(
                    label = "MTP / speculative decoding (Gemma decode ~2.2×)",
                    checked = state.mtpEnabled,
                    onCheckedChange = { viewModel.setMtpEnabled(it) },
                )
                Text(
                    "MTP requires a Gemma reload to take effect. Default OFF — " +
                        "our replies are short (~5–10 tokens) so decode isn't " +
                        "the bottleneck, and our current model doesn't embed " +
                        "the drafter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                SwitchRow(
                    label = "GPU audio encoder (Fase 7 — needs SDK ≥ 0.14)",
                    checked = state.audioBackendGpu,
                    onCheckedChange = { viewModel.setAudioBackendGpu(it) },
                )
                Text(
                    "Audio prefill on GPU. Currently a no-op on our 0.12 SDK + " +
                        "Fase 0 model (0.15 upgrade regressed; see PROGRESS.md). " +
                        "Flag preserved for future SDK re-upgrade.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                SwitchRow(
                    label = "Full-duplex mode (mic stays open while TTS speaks)",
                    checked = state.fullDuplexMode,
                    onCheckedChange = { viewModel.setFullDuplexMode(it) },
                )
                Text(
                    if (state.fullDuplexMode)
                        "Full-duplex ON (default) — chunker keeps collecting during TTS. " +
                            "The AEC toggle below removes speaker loopback from the mic; " +
                            "even so, a highly reverberant room may still need a " +
                            "directional mic or headphones."
                    else
                        "Half-duplex — mic muted while TTS plays. Safe fallback " +
                            "when AEC alone can't suppress the room echo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                SwitchRow(
                    label = "Acoustic echo cancellation (HAL AEC + Java effect)",
                    checked = state.aecEnabled,
                    onCheckedChange = { viewModel.setAecEnabled(it) },
                )
                Text(
                    if (state.aecEnabled)
                        "AEC ON — Oboe capture uses InputPreset.VoiceCommunication " +
                            "(HAL AEC/NS/AGC) + AcousticEchoCanceler bound to the " +
                            "stream session. ~10–20 ms added latency."
                    else
                        "AEC OFF — Oboe uses InputPreset.Unprocessed (raw mic). " +
                            "Full-duplex will feed back the speaker unless the mic " +
                            "is directional or off-axis to the speaker.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                SwitchRow(
                    label = "WebRTC AECM (software AEC for external mic + BT speaker)",
                    checked = state.webrtcAecEnabled,
                    onCheckedChange = { viewModel.setWebrtcAecEnabled(it) },
                )
                Text(
                    if (state.webrtcAecEnabled)
                        "SW AEC ON (POC) — Kokoro PCM piped as far-end reference; " +
                            "mic capture filtered before VAD. Delay hardcoded 200 ms " +
                            "(BT A2DP typical). Frames farend=${state.webrtcAecFarendFrames} " +
                            "nearend=${state.webrtcAecNearendFrames}."
                    else
                        "SW AEC OFF — mic path unchanged. Flip ON when using external " +
                            "mic + BT speaker where the HAL AEC alone leaves echo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                // Anti-eco software layers (Capa 1 / 2 / 3). Counters live
                // in the TRANSLATIONS card below.
                SwitchRow(
                    label = "Skip non-Spanish audio (Gemma answers SKIP)",
                    checked = state.skipNonSpanish,
                    onCheckedChange = { viewModel.setSkipNonSpanish(it) },
                )
                SwitchRow(
                    label = "Echo text filter (drop replies similar to recent TTS)",
                    checked = state.echoTextFilterEnabled,
                    onCheckedChange = { viewModel.setEchoTextFilterEnabled(it) },
                )
                Text(
                    "RMS threshold: ${state.rmsThreshold.toInt()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = state.rmsThreshold.toFloat(),
                    onValueChange = { viewModel.setRmsThreshold(it.toDouble()) },
                    valueRange = AstConfig.RMS_SLIDER_MIN.toFloat()..AstConfig.RMS_SLIDER_MAX.toFloat(),
                )
                Text(
                    "Chunks under this RMS never reach Gemma. Calibration (logcat " +
                        "AstChunkRouter logs RMS per chunk): voice peaks ~28 000, " +
                        "echo ~13 000–18 000.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                SwitchRow(
                    label = "Audio-after-text (Google's recommended order)",
                    checked = state.audioAfterText,
                    onCheckedChange = { viewModel.setAudioAfterText(it) },
                )
                Text(
                    "Google's multimodal docs: place audio AFTER text for " +
                        "best AST accuracy. Legacy Fase 0 order was audio-first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                SwitchRow(
                    label = "Official AST prompt (transcribe + translate, English: marker)",
                    checked = state.useOfficialAstPrompt,
                    onCheckedChange = { viewModel.setUseOfficialAstPrompt(it) },
                )
                Text(
                    if (state.useOfficialAstPrompt)
                        "Router extracts text after 'English:' before emit. " +
                            "Marker-missing fallbacks: ${state.englishMarkerMissing}."
                    else
                        "Using legacy Fase 4 prompt (English-only output, " +
                            "no extraction).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                SwitchRow(
                    label = "Fast TTS (Android system TTS instead of Kokoro)",
                    checked = state.ttsFastMode,
                    onCheckedChange = { viewModel.setTtsFastMode(it) },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Kokoro voice (Quality mode):",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Trimmed catalogue: post-fix device A/B kept `am_puck` as
                // the travel default male voice and `af_heart` as the
                // female fallback. Other `am_*` / `af_*` voices from the
                // wider Kokoro NPZ can be re-added here if a specific
                // scenario demands them. Each entry is still filtered
                // against the loaded model's voices — the router would
                // throw IllegalArgumentException("Unknown voice …")
                // otherwise.
                val allVoices = listOf(
                    "am_puck" to "Male (Puck)",
                    "af_heart" to "Female (Heart)",
                )
                val availableVoices = allVoices.filter { (id, _) ->
                    // Empty availableVoices means Kokoro hasn't finished
                    // loading yet — show the full catalogue disabled rather
                    // than an empty row (avoids UI blink on cold start).
                    state.kokoroAvailableVoices.isEmpty() || id in state.kokoroAvailableVoices
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val currentVoice = state.kokoroVoice
                    availableVoices.forEach { (id, label) ->
                        if (currentVoice == id) {
                            Button(
                                onClick = { viewModel.setTtsVoice(id) },
                                enabled = state.kokoroLoaded && !state.ttsFastMode,
                                modifier = Modifier.weight(1f),
                            ) { Text(label) }
                        } else {
                            OutlinedButton(
                                onClick = { viewModel.setTtsVoice(id) },
                                enabled = state.kokoroLoaded && !state.ttsFastMode,
                                modifier = Modifier.weight(1f),
                            ) { Text(label) }
                        }
                    }
                }
                if (state.kokoroLoaded && availableVoices.size < allVoices.size) {
                    Text(
                        "${allVoices.size - availableVoices.size} voice(s) hidden — " +
                            "not present in loaded voices-v1.0.bin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.ttsFastMode) {
                    Text(
                        "Voice picker is Kokoro-only; Fast mode uses the system TTS voice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                // Speed slider — hot-swaps via TtsRouter.setSpeed on every
                // slider change; the next sentence uses the new value.
                // Values > 1.0 reduce Kokoro latency by generating fewer
                // output samples per sentence.
                Text(
                    "TTS Speed: ${"%.1f".format(state.ttsSpeed)}x",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = state.ttsSpeed,
                    onValueChange = { viewModel.setTtsSpeed(it) },
                    valueRange = TtsConfig.MIN_SPEED..TtsConfig.MAX_SPEED,
                    // 8 intermediate steps → 0.1 increments across the
                    // 0.8 – 1.5 range (Slider counts the endpoints as
                    // separate positions, so steps = intervals - 1 = 6).
                    steps = 6,
                    enabled = state.kokoroLoaded && !state.ttsFastMode,
                )
                Text(
                    text = when {
                        state.androidTtsError != null -> "⚠ Android TTS: ${state.androidTtsError}"
                        !state.androidTtsReady -> "Android TTS initialising…"
                        state.ttsFastMode -> "Fast mode ON — system TTS (~100–300 ms/utterance)"
                        else -> "Quality mode — Kokoro (~1.5–2.5 s/sentence, higher voice quality)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "First token: ${if (state.firstTokenLatencyMs > 0) "${state.firstTokenLatencyMs} ms" else "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "First audio: ${if (state.firstAudioLatencyMs > 0) "${state.firstAudioLatencyMs} ms" else "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Text(
                    "Both are wall-clock from ChunkReady.timestampNs. Watch " +
                        "them drop as Stage A → A+B → A+B+chunker-retune go on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = "4. TRANSLATIONS (last ${state.translations.size})") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "Avg latency: ${state.astAvgLatencyMs.toInt()} ms",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Queue: ${state.astQueueSize}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Dropped: ${state.totalDropped} | Errors: ${state.astErrors}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "Skipped non-Spanish: ${state.totalSkippedNonSpanish}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Echo dropped: ${state.totalEchoDropped}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Low RMS: ${state.totalDiscardedLowEnergy}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "Meta-text dropped: ${state.totalDiscardedMeta}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Actual mic state — reflects BOTH ttsPlaying AND
                    // fullDuplexMode. Under full-duplex, the mic stays open
                    // even while TTS speaks — the badge must not lie about
                    // that or the user thinks the mute is stuck.
                    val actuallyMuted = state.ttsPlaying && !state.fullDuplexMode
                    val label = when {
                        actuallyMuted -> "🔇 VAD muted (TTS)"
                        state.ttsPlaying && state.fullDuplexMode -> "🎙 VAD live (full-duplex, TTS)"
                        else -> "🎙 VAD live"
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (actuallyMuted)
                            MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.mutedMicFrames > 0) {
                    Text(
                        "Muted mic events since start: ${state.mutedMicFrames}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (state.translations.isEmpty()) {
                    Text(
                        "No translations yet — start the pipeline and speak Spanish",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.translations.forEach { entry ->
                        TranslationRow(entry)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        item {
            SectionCard(title = "5. TTS PLAYBACK (Kokoro 24 kHz)") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "Avg latency: ${state.ttsAvgLatencyMs.toInt()} ms",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Queue: ${state.ttsQueueSize}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Drops: ${state.ttsDropped} | Errors: ${state.ttsErrors}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.height(4.dp))
                InfoRow("Synthesized", "${state.totalSynthesized}")
                InfoRow("Spoken", "${state.totalSpoken}")
                InfoRow("Last duration", "${state.lastTtsDurationMs} ms")
                Spacer(Modifier.height(6.dp))
                Text(
                    if (state.lastSpokenText.isBlank()) "No spoken text yet"
                    else "🔊 \"${state.lastSpokenText.take(120)}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.lastSpokenText.isBlank())
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        item {
            SectionCard(title = "6. CHUNK PLAYBACK (16 kHz raw)") {
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
                    onClick = { viewModel.replayLastChunk() },
                    enabled = state.lastChunkSamples != null && state.selectedOutput != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Replay last chunk (sanity check audio sent to Gemma)") }
                Spacer(Modifier.height(6.dp))
                InfoRow("Sink back-end", state.playbackSinkLabel)
            }
        }

        item {
            SectionCard(title = "7. VAD TUNING") {
                SliderRow(
                    label = "threshold",
                    value = state.vadConfig.threshold,
                    onValueChange = { viewModel.setVadThreshold(it) },
                    valueRange = 0.05f..0.95f,
                    formatValue = { "%.2f".format(it) },
                )
                IntSliderRow("minSpeechMs", state.vadConfig.minSpeechMs,
                    { viewModel.setVadMinSpeechMs(it) }, 0..1000, "ms")
                IntSliderRow("minSilenceMs", state.vadConfig.minSilenceMs,
                    { viewModel.setVadMinSilenceMs(it) }, 0..2000, "ms")
            }
        }

        item {
            SectionCard(title = "8. CHUNKER TUNING") {
                IntSliderRow("minChunkMs", state.chunkerConfig.minChunkMs,
                    { viewModel.setChunkerMinMs(it) }, 200..10_000, "ms")
                IntSliderRow("maxChunkMs", state.chunkerConfig.maxChunkMs,
                    { viewModel.setChunkerMaxMs(it) }, 500..15_000, "ms")
                IntSliderRow("silenceEndMs", state.chunkerConfig.silenceEndMs,
                    { viewModel.setChunkerSilenceEndMs(it) }, 50..3000, "ms")
                IntSliderRow("preRollMs", state.chunkerConfig.preRollMs,
                    { viewModel.setChunkerPreRollMs(it) }, 0..1000, "ms")
            }
        }

        item {
            SectionCard(title = "9. LOGS") {
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
                                line.contains("Translation") -> Color(0xFF69F0AE)
                                line.contains("Chunk #") -> Color(0xFFCFFD60)
                                line.contains("VAD →") -> Color(0xFFFFD740)
                                line.contains("Gemma LOADED") -> Color(0xFF69F0AE)
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
            Text(
                title,
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

/**
 * Compact label + material Switch row. Used for the Fase 6 streaming
 * toggles; keeps the pattern that the sliders use — Column-per-control,
 * label on the left of a Row, control on the right.
 */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
private fun TranslationRow(entry: TranslationEntry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "#${entry.index.toString().padStart(3, '0')}  ${entry.sourceDurationMs}ms",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${entry.latencyMs}ms",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            entry.text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}
