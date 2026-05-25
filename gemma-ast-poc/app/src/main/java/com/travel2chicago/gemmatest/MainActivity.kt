package com.travel2chicago.gemmatest

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.travel2chicago.gemmatest.ui.theme.GemmaTestTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStoragePermission()
        enableEdgeToEdge()
        setContent {
            GemmaTestTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    GemmaTestScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check after user returns from Settings (where they grant permission)
        if (hasStoragePermission()) {
            // Trigger model re-scan now that we have permission
            // ViewModel will be initialized by Compose, so this is safe
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // Pre-Android 11, requestLegacyExternalStorage=true in manifest is enough
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                // Open Settings screen where user grants "All files access"
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback: open general storage settings
                    val fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(fallbackIntent)
                }
            }
        }
    }
}

@Composable
fun GemmaTestScreen(viewModel: GemmaTestViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // --- Header ---
        item {
            Text(
                text = "Gemma 4 E4B — AST POC",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Validacion de Audio Speech Translation",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        // --- Section 1: Model ---
        item {
            SectionCard(title = "1. MODELO") {
                InfoRow("Path", state.modelPath)
                InfoRow("Exists", if (state.modelExists) "YES" else "NO")

                if (!state.modelExists) {
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Best: Install AI Edge Gallery, download Gemma 4 E4B there.\n" +
                            "The model + companion files will be at:\n" +
                            "/sdcard/Android/data/com.google.ai.edge.gallery/files/",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { viewModel.downloadModel() },
                        enabled = !state.downloading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.downloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scanning...")
                        } else {
                            Text("Scan for Model")
                        }
                    }

                    state.downloadError?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                if (state.modelExists && !state.modelLoaded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.loadModel() },
                        enabled = !state.modelLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.modelLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Loading model (~10s)...")
                        } else {
                            Text("Load Model")
                        }
                    }
                }

                if (state.modelLoaded) {
                    InfoRow("Load time", "${state.modelLoadTimeMs} ms")
                    InfoRow("Backend", state.backendUsed)
                    StatusChip(text = "LOADED", color = Color(0xFF4CAF50))
                }
            }
        }

        // --- Section 2: Audio Support ---
        item {
            SectionCard(title = "2. AUDIO SUPPORT") {
                when (state.audioSupported) {
                    null -> {
                        if (state.modelLoaded) {
                            Text("Checking...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(
                                "Load model first to check audio support",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    true -> {
                        StatusChip(text = "AUDIO SUPPORTED", color = Color(0xFF4CAF50))
                    }
                    false -> {
                        StatusChip(text = "AUDIO NOT SUPPORTED", color = Color(0xFFF44336))
                    }
                }

                if (state.audioCheckDetails.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.audioCheckDetails,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp,
                    )
                }
            }
        }

        // --- Section 3: Audio AST Test ---
        item {
            SectionCard(title = "3. AUDIO AST TEST (Audio→English)") {
                if (!state.modelLoaded) {
                    Text(
                        "Load model first",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (state.audioSupported != true) {
                    Text(
                        "Audio support not detected",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Test sending audio to Gemma for translation.\n" +
                            "Uses test_audio_es.wav from /sdcard/Download/\n" +
                            "or generates a synthetic 440Hz tone.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    Button(
                        onClick = { viewModel.runAudioASTTest() },
                        enabled = !state.audioTestRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.audioTestRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Testing audio...")
                        } else {
                            Text("Run Audio AST Test")
                        }
                    }

                    // Results
                    if (state.audioTestOutput.isNotEmpty() || state.audioTestTimeMs > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))

                        Text("Audio AST Results", fontWeight = FontWeight.Bold)
                        InfoRow("Source", state.audioTestSource)
                        InfoRow("Strategy", state.audioTestStrategy)
                        InfoRow("EN output", state.audioTestOutput.ifEmpty { "(empty)" })
                        InfoRow("Time", "${state.audioTestTimeMs} ms")
                        StatusChip(text = "AUDIO AST WORKS!", color = Color(0xFF4CAF50))
                    }

                    state.audioTestError?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // --- Section 4: Translation Test ---
        item {
            SectionCard(title = "4. TRANSLATION TEST (Text-to-Text)") {
                if (!state.modelLoaded) {
                    Text(
                        "Load model first",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Test Gemma's ability to translate Spanish to English:",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )

                    // Test case buttons
                    viewModel.testCases.forEach { (spanish, description) ->
                        OutlinedButton(
                            onClick = { viewModel.runTranslationTest(spanish) },
                            enabled = !state.testRunning,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = if (spanish.isEmpty()) "(empty)" else spanish,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (state.testRunning) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Translating...", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    // Results
                    if (state.englishOutput.isNotEmpty() || state.inferenceTimeMs > 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))

                        Text("Results", fontWeight = FontWeight.Bold)
                        InfoRow("ES input", state.spanishInput.ifEmpty { "(empty)" })
                        InfoRow("EN output", state.englishOutput.ifEmpty { "(empty)" })
                        InfoRow("Inference", "${state.inferenceTimeMs} ms")
                        InfoRow("Tokens", "${state.tokensGenerated}")
                        InfoRow("Speed", "${String.format("%.1f", state.tokensPerSecond)} tok/s")
                    }
                }
            }
        }

        // --- Section 5: System Metrics ---
        item {
            SectionCard(title = "5. SYSTEM METRICS") {
                InfoRow("Java Heap", "${state.javaHeapMB} MB")
                InfoRow("Native Heap", "${state.nativeHeapMB} MB")
                InfoRow("Total Memory", "${state.totalMemoryMB} MB")
                InfoRow("Backend", state.backendUsed)
            }
        }

        // --- Section 6: Logs ---
        item {
            SectionCard(title = "6. LOGS") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .background(
                            Color(0xFF1E1E1E),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(8.dp),
                ) {
                    val listState = rememberLazyListState()

                    LaunchedEffect(state.logs.size) {
                        if (state.logs.isNotEmpty()) {
                            listState.animateScrollToItem(state.logs.size - 1)
                        }
                    }

                    LazyColumn(state = listState) {
                        items(state.logs) { logLine ->
                            val color = when {
                                logLine.contains("[ERROR]") -> Color(0xFFFF5252)
                                logLine.contains("FOUND") || logLine.contains("SUPPORTED") -> Color(0xFF69F0AE)
                                logLine.contains("CONCLUSION") -> Color(0xFFFFD740)
                                else -> Color(0xFFE0E0E0)
                            }
                            Text(
                                text = logLine,
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

        // --- Error banner ---
        state.error?.let { error ->
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        // Bottom padding
        item { Spacer(modifier = Modifier.height(32.dp)) }
    }
}

// --- Reusable components ---

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
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
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.3f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.7f),
        )
    }
}

@Composable
fun StatusChip(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.15f),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = color,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}
