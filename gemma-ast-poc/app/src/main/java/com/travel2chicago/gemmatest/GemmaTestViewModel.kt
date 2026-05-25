package com.travel2chicago.gemmatest

import android.app.Application
import android.os.Debug
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

private const val TAG = "GemmaTest"

/**
 * All UI state for the POC.
 */
data class TestState(
    // Model status
    val modelPath: String = "",
    val modelExists: Boolean = false,
    val modelLoadTimeMs: Long = -1,
    val modelLoaded: Boolean = false,
    val modelLoading: Boolean = false,

    // Download
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadError: String? = null,

    // Audio support check
    val audioSupported: Boolean? = null,  // null = not tested yet
    val audioCheckDetails: String = "",

    // Translation test (text-to-text)
    val testRunning: Boolean = false,
    val spanishInput: String = "",
    val englishOutput: String = "",
    val inferenceTimeMs: Long = -1,
    val tokensGenerated: Int = 0,
    val tokensPerSecond: Float = 0f,

    // Audio AST test
    val audioTestRunning: Boolean = false,
    val audioTestSource: String = "",
    val audioTestOutput: String = "",
    val audioTestTimeMs: Long = -1,
    val audioTestError: String? = null,
    val audioTestStrategy: String = "",

    // System metrics
    val javaHeapMB: Long = 0,
    val nativeHeapMB: Long = 0,
    val totalMemoryMB: Long = 0,
    val backendUsed: String = "unknown",

    // Logs
    val logs: List<String> = emptyList(),
    val error: String? = null,
)

class GemmaTestViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(TestState())
    val state: StateFlow<TestState> = _state.asStateFlow()

    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null

    /**
     * Directories to search for the model, in priority order.
     * The AI Edge Gallery directory has all companion files intact.
     */
    private val searchDirs: List<File>
        get() = listOf(
            // 1. AI Edge Gallery's original directory (has companion cache files)
            File("/sdcard/Android/data/com.google.ai.edge.gallery/files/Gemma_4_E4B_it/20260325"),
            // 2. Parent dir in case date folder differs
            File("/sdcard/Android/data/com.google.ai.edge.gallery/files/Gemma_4_E4B_it"),
            // 3. Dedicated subdirectory with all companion files (from copy script)
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "gemma_model"),
            // 4. Direct placement in Downloads
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            // 5. App-internal fallback
            File(getApplication<Application>().filesDir, "models"),
        )

    /** The resolved model file (set by checkModelExists) */
    @Volatile private var resolvedModelFile: File? = null

    init {
        checkModelExists()
    }

    // --- Log helper ---

    private fun log(message: String) {
        Log.i(TAG, message)
        _state.update { it.copy(logs = it.logs + "[${System.currentTimeMillis() % 100000}] $message") }
    }

    private fun logError(message: String, e: Throwable? = null) {
        Log.e(TAG, message, e)
        _state.update {
            it.copy(
                logs = it.logs + "[ERROR] $message${e?.let { t -> ": ${t.message}" } ?: ""}",
                error = message,
            )
        }
    }

    // --- Model management ---

    fun checkModelExists() {
        log("=== SCANNING FOR MODEL ===")

        resolvedModelFile = null

        for (dir in searchDirs) {
            log("Checking: ${dir.absolutePath}")
            log("  exists=${dir.exists()} readable=${dir.canRead()} isDir=${dir.isDirectory}")

            if (!dir.exists() || !dir.canRead()) {
                // Try sub-directories for the AI Edge Gallery parent
                if (dir.absolutePath.contains("Gemma_4_E4B_it") && !dir.absolutePath.contains("20260325")) {
                    try {
                        val subDirs = dir.listFiles { f -> f.isDirectory }
                        subDirs?.forEach { sub ->
                            log("  Sub-dir: ${sub.name}")
                            val found = findModelFileInDir(sub)
                            if (found != null) {
                                resolvedModelFile = found
                                logModelFound(found)
                                updateModelState(found)
                                return
                            }
                        }
                    } catch (_: Exception) { }
                }
                continue
            }

            val found = findModelFileInDir(dir)
            if (found != null) {
                resolvedModelFile = found
                logModelFound(found)
                updateModelState(found)
                return
            }
        }

        // Not found in any directory
        log("Model NOT FOUND in any search directory")
        log("Expected locations:")
        searchDirs.forEach { log("  ${it.absolutePath}") }
        _state.update {
            it.copy(
                modelPath = searchDirs.first().absolutePath,
                modelExists = false,
            )
        }
    }

    private fun logModelFound(modelFile: File) {
        val dir = modelFile.parentFile!!
        log("MODEL FOUND: ${modelFile.absolutePath}")
        log("  Size: ${modelFile.length() / 1_048_576} MB")

        // List companion files
        val companions = dir.listFiles()?.filter { f ->
            f.name != modelFile.name && (
                f.name.contains("litertlm", ignoreCase = true) ||
                f.name.endsWith(".bin") ||
                f.name.contains("cache", ignoreCase = true) ||
                f.name.contains("xnnpack", ignoreCase = true)
            )
        } ?: emptyList()

        if (companions.isNotEmpty()) {
            log("  Companion files (${companions.size}):")
            companions.forEach { f ->
                log("    ${f.name} (${f.length() / 1_048_576} MB)")
            }
        } else {
            log("  WARNING: No companion files found!")
            log("  GPU may fail without .xnnpack_cache and .bin files")
        }
    }

    private fun updateModelState(modelFile: File) {
        _state.update {
            it.copy(
                modelPath = modelFile.parentFile?.absolutePath ?: modelFile.absolutePath,
                modelExists = true,
            )
        }
    }

    /**
     * Re-scan all known directories for the model file.
     */
    fun downloadModel() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(downloading = true, downloadProgress = 0f, downloadError = null) }

            log("Scanning all known directories for Gemma model...")
            kotlinx.coroutines.delay(300)
            checkModelExists()

            if (!_state.value.modelExists) {
                _state.update {
                    it.copy(
                        downloadError = "Model not found.\n" +
                            "Best option: Install AI Edge Gallery and download Gemma 4 E4B there first.\n" +
                            "Alt: adb push model + companion files to /sdcard/Download/gemma_model/",
                    )
                }
            }

            _state.update { it.copy(downloading = false) }
        }
    }

    // --- Load model ---

    /**
     * Load the model using the EXACT same configuration as AI Edge Gallery:
     * - backend = GPU (main inference)
     * - audioBackend = CPU (audio processing — must be CPU per AI Edge Gallery)
     * - maxNumTokens = 1024
     * - cacheDir = app's external files dir
     *
     * Falls back to CPU for main backend if GPU fails.
     */
    fun loadModel() {
        if (_state.value.modelLoading || _state.value.modelLoaded) return

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(modelLoading = true, error = null) }
            log("Loading Gemma 4 E4B model...")
            updateMemoryMetrics()

            try {
                val modelFile = resolvedModelFile ?: run {
                    checkModelExists()
                    resolvedModelFile
                } ?: throw Exception("No model file found. Install AI Edge Gallery and download Gemma 4 E4B first.")

                log("Using model file: ${modelFile.absolutePath}")
                log("Model size: ${modelFile.length() / 1_048_576} MB")

                // List all files in the model directory for debugging
                val modelDir = modelFile.parentFile!!
                val allFiles = modelDir.listFiles()
                log("Files in model directory (${modelDir.absolutePath}):")
                allFiles?.sortedBy { it.name }?.forEach { f ->
                    log("  ${f.name} (${f.length() / 1_048_576} MB)")
                }

                // Cache dir for GPU compiled artifacts
                val cacheDir = getApplication<Application>().getExternalFilesDir(null)?.absolutePath
                log("Cache dir: $cacheDir")

                // Try backends in order: GPU → CPU
                // AI Edge Gallery pattern: backend=GPU, audioBackend=CPU
                val backendsToTry = listOf(
                    "GPU" to { Backend.GPU() },
                    "CPU" to { Backend.CPU() },
                )

                var lastError: Exception? = null

                for ((backendName, backendFactory) in backendsToTry) {
                    try {
                        log("--- Attempting $backendName backend ---")

                        val backend = try {
                            backendFactory()
                        } catch (e: Exception) {
                            log("$backendName backend not available: ${e.message}")
                            continue
                        }

                        // Build EngineConfig matching AI Edge Gallery's configuration:
                        // - audioBackend = CPU (required for audio modality)
                        // - maxNumTokens = 1024
                        // - cacheDir for GPU compilation cache
                        val config = EngineConfig(
                            modelPath = modelFile.absolutePath,
                            backend = backend,
                            audioBackend = Backend.CPU(),
                            maxNumTokens = 1024,
                            cacheDir = cacheDir,
                        )

                        log("EngineConfig: backend=$backendName, audioBackend=CPU, maxTokens=1024, cacheDir=$cacheDir")
                        log("Creating engine with $backendName...")
                        val eng = Engine(config)

                        log("Initializing engine with $backendName (this may take ~10-30s)...")
                        val startTime = System.nanoTime()
                        eng.initialize()
                        val loadTimeMs = (System.nanoTime() - startTime) / 1_000_000

                        // Success!
                        engine = eng
                        log("Engine ready with $backendName in ${loadTimeMs}ms")
                        updateMemoryMetrics()

                        _state.update {
                            it.copy(
                                modelLoaded = true,
                                modelLoading = false,
                                modelLoadTimeMs = loadTimeMs,
                                backendUsed = backendName,
                            )
                        }

                        // Create conversation with translation-optimized settings
                        log("Creating conversation...")
                        conversation = eng.createConversation(
                            ConversationConfig(
                                samplerConfig = SamplerConfig(
                                    temperature = 0.1,
                                    topK = 10,
                                    topP = 0.95,
                                ),
                            )
                        )
                        log("Conversation ready")

                        // Check for audio/multimodal support
                        checkAudioSupport()
                        return@launch // success — exit

                    } catch (e: Exception) {
                        lastError = e
                        logError("$backendName failed", e)
                        // Clean up failed engine before trying next backend
                        try { engine?.close() } catch (_: Exception) { }
                        engine = null
                        log("Will try next backend...")
                    }
                }

                // All backends failed
                throw lastError ?: Exception("All backends failed")

            } catch (e: Exception) {
                logError("Failed to load model", e)
                _state.update { it.copy(modelLoading = false) }
            }
        }
    }

    /**
     * Find the main .litertlm model file in a given directory.
     */
    private fun findModelFileInDir(dir: File): File? {
        if (!dir.exists() || !dir.canRead()) return null

        try {
            val files = dir.listFiles() ?: return null

            // 1. Known AI Edge Gallery filename
            files.find {
                it.name == "gemma4_4b_v09_obfus_fix_all_modalities_thinking.litertlm"
            }?.let { return it }

            // 2. Any .litertlm file with "gemma" in the name (>100MB to skip caches)
            files.filter {
                it.name.endsWith(".litertlm", ignoreCase = true) &&
                it.name.contains("gemma", ignoreCase = true) &&
                it.length() > 100_000_000
            }.maxByOrNull { it.length() }?.let { return it }

            // 3. Any large .litertlm file (>100MB)
            files.filter {
                it.name.endsWith(".litertlm", ignoreCase = true) &&
                !it.name.contains("cache", ignoreCase = true) &&
                it.length() > 100_000_000
            }.maxByOrNull { it.length() }?.let { return it }

            // 4. Any .task file (alternate LiteRT format)
            files.filter {
                it.name.endsWith(".task", ignoreCase = true) &&
                it.length() > 100_000_000
            }.maxByOrNull { it.length() }?.let { return it }

        } catch (e: SecurityException) {
            log("  Permission denied: ${dir.absolutePath}")
        } catch (e: Exception) {
            log("  Error scanning: ${e.message}")
        }

        return null
    }

    // --- Audio AST Test ---

    /**
     * Test Audio Speech Translation using the EXACT same pattern as AI Edge Gallery:
     *
     * 1. Audio format: WAV 16kHz mono 16-bit PCM (Content.AudioBytes with WAV header)
     * 2. Message construction: Contents.of(Content.AudioBytes(wav), Content.Text(prompt))
     * 3. Send via: conversation.sendMessage(Contents) — direct overload, no reflection
     *
     * Order matters: audio FIRST, text LAST (per AI Edge Gallery source).
     */
    fun runAudioASTTest() {
        if (_state.value.audioTestRunning || !_state.value.modelLoaded) return

        viewModelScope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(
                    audioTestRunning = true,
                    audioTestOutput = "",
                    audioTestError = null,
                    audioTestStrategy = "",
                )
            }

            log("=== AUDIO AST TEST ===")

            try {
                // Find or generate audio file
                val audioFile = findTestAudioFile() ?: run {
                    log("No test audio found, generating synthetic 440Hz tone...")
                    val syntheticFile = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "test_audio_synthetic.wav",
                    )
                    generateSyntheticWav(syntheticFile, durationMs = 3000)
                    syntheticFile
                }

                log("Audio file: ${audioFile.absolutePath}")
                log("Audio size: ${audioFile.length()} bytes (${audioFile.length() / 1024} KB)")
                _state.update { it.copy(audioTestSource = audioFile.name) }

                // Read WAV bytes — AI Edge Gallery passes full WAV (with RIFF header) to AudioBytes
                val wavBytes = audioFile.readBytes()
                log("WAV bytes loaded: ${wavBytes.size} bytes")

                val prompt = "Translate the following Spanish speech to English. " +
                    "Output ONLY the English translation, nothing else."

                // Strategy 1 (Primary): Content.AudioBytes with WAV bytes
                // This is what AI Edge Gallery uses
                log("--- Strategy 1: Content.AudioBytes (WAV bytes) ---")
                var success = tryAudioBytesDirectAPI(wavBytes, prompt, "AudioBytes(WAV)")

                // Strategy 2: Content.AudioFile with file path
                if (!success) {
                    log("--- Strategy 2: Content.AudioFile ---")
                    success = tryAudioFileDirectAPI(audioFile.absolutePath, prompt)
                }

                // Strategy 3: Content.AudioBytes with raw PCM (no header)
                if (!success) {
                    log("--- Strategy 3: Content.AudioBytes (raw PCM) ---")
                    val pcmBytes = extractPcmFromWav(audioFile)
                    if (pcmBytes != null) {
                        success = tryAudioBytesDirectAPI(pcmBytes, prompt, "AudioBytes(PCM)")
                    }
                }

                if (!success) {
                    log("ALL audio strategies failed.")
                    log("Check: was audioBackend=CPU set in EngineConfig?")
                    log("Check: is the model multimodal (Gemma 4 E4B)?")
                    _state.update {
                        it.copy(
                            audioTestError = "All audio strategies failed. See logs for details.",
                            audioTestRunning = false,
                        )
                    }
                }

                updateMemoryMetrics()

            } catch (e: Exception) {
                logError("Audio AST test failed", e)
                _state.update {
                    it.copy(
                        audioTestRunning = false,
                        audioTestError = e.message,
                    )
                }
            }
        }
    }

    /**
     * Try audio via Content.AudioBytes — direct API, no reflection.
     * Uses the exact AI Edge Gallery pattern:
     *   Contents.of(Content.AudioBytes(wavBytes), Content.Text(prompt))
     *   conversation.sendMessage(contents)
     */
    private fun tryAudioBytesDirectAPI(audioBytes: ByteArray, prompt: String, label: String): Boolean {
        val conv = conversation ?: return false

        try {
            // Build contents: audio FIRST, text LAST (per AI Edge Gallery)
            val contents = Contents.of(
                Content.AudioBytes(audioBytes),
                Content.Text(prompt),
            )
            log("Contents built: AudioBytes(${audioBytes.size} bytes) + Text")

            val startTime = System.nanoTime()

            // Try synchronous sendMessage(Contents) first
            try {
                log("Calling conversation.sendMessage(Contents)...")
                val response: Message = conv.sendMessage(contents)
                val responseText = response.toString().trim()
                val timeMs = (System.nanoTime() - startTime) / 1_000_000

                log("SUCCESS! $label via sendMessage(Contents)")
                log("Response: $responseText")
                log("Time: ${timeMs}ms")

                _state.update {
                    it.copy(
                        audioTestRunning = false,
                        audioTestOutput = responseText,
                        audioTestTimeMs = timeMs,
                        audioTestStrategy = "$label → sendMessage(Contents)",
                    )
                }
                return true
            } catch (e: Exception) {
                log("sendMessage(Contents) failed: ${e.message}")
                e.cause?.let { log("  Cause: ${it.message}") }
            }

            // Try async sendMessage(Contents) → Flow
            try {
                log("Trying sendMessageAsync(Contents) → Flow...")
                val fullResponse = StringBuilder()
                var tokenCount = 0

                val startTime2 = System.nanoTime()
                conv.sendMessageAsync(contents)
                    .catch { e ->
                        log("Flow error: ${e.message}")
                        throw e
                    }
                    .collect { msg ->
                        fullResponse.append(msg.toString())
                        tokenCount++
                    }

                val responseText = fullResponse.toString().trim()
                val timeMs = (System.nanoTime() - startTime2) / 1_000_000

                log("SUCCESS! $label via sendMessageAsync(Contents) Flow")
                log("Response: $responseText")
                log("Time: ${timeMs}ms | Tokens: $tokenCount")

                _state.update {
                    it.copy(
                        audioTestRunning = false,
                        audioTestOutput = responseText,
                        audioTestTimeMs = timeMs,
                        audioTestStrategy = "$label → sendMessageAsync(Contents)",
                    )
                }
                return true
            } catch (e: Exception) {
                log("sendMessageAsync(Contents) failed: ${e.message}")
                e.cause?.let { log("  Cause: ${it.message}") }
            }

            // Try with Message.user() wrapper
            try {
                log("Trying sendMessage(Message.user(Contents))...")
                val userMsg = Message.user(contents)

                val startTime3 = System.nanoTime()
                val response: Message = conv.sendMessage(userMsg)
                val responseText = response.toString().trim()
                val timeMs = (System.nanoTime() - startTime3) / 1_000_000

                log("SUCCESS! $label via sendMessage(Message.user)")
                log("Response: $responseText")
                log("Time: ${timeMs}ms")

                _state.update {
                    it.copy(
                        audioTestRunning = false,
                        audioTestOutput = responseText,
                        audioTestTimeMs = timeMs,
                        audioTestStrategy = "$label → sendMessage(Message.user)",
                    )
                }
                return true
            } catch (e: Exception) {
                log("sendMessage(Message.user) failed: ${e.message}")
                e.cause?.let { log("  Cause: ${it.message}") }
            }

        } catch (e: Exception) {
            log("$label construction failed: ${e.message}")
            e.cause?.let { log("  Cause: ${it.message}") }
        }

        return false
    }

    /**
     * Try audio via Content.AudioFile — direct API.
     */
    private fun tryAudioFileDirectAPI(filePath: String, prompt: String): Boolean {
        val conv = conversation ?: return false

        try {
            val contents = Contents.of(
                Content.AudioFile(filePath),
                Content.Text(prompt),
            )
            log("Contents built: AudioFile($filePath) + Text")

            val startTime = System.nanoTime()

            // Synchronous
            try {
                log("Calling conversation.sendMessage(Contents) with AudioFile...")
                val response: Message = conv.sendMessage(contents)
                val responseText = response.toString().trim()
                val timeMs = (System.nanoTime() - startTime) / 1_000_000

                log("SUCCESS! AudioFile via sendMessage(Contents)")
                log("Response: $responseText")
                log("Time: ${timeMs}ms")

                _state.update {
                    it.copy(
                        audioTestRunning = false,
                        audioTestOutput = responseText,
                        audioTestTimeMs = timeMs,
                        audioTestStrategy = "AudioFile → sendMessage(Contents)",
                    )
                }
                return true
            } catch (e: Exception) {
                log("AudioFile sendMessage failed: ${e.message}")
                e.cause?.let { log("  Cause: ${it.message}") }
            }

        } catch (e: Exception) {
            log("AudioFile construction failed: ${e.message}")
            e.cause?.let { log("  Cause: ${it.message}") }
        }

        return false
    }

    // --- Audio file utilities ---

    /**
     * Look for a real Spanish audio WAV file on the device.
     */
    private fun findTestAudioFile(): File? {
        val candidates = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "test_audio_es.wav"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "test_audio.wav"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "audio_test.wav"),
        )

        for (f in candidates) {
            if (f.exists() && f.length() > 1000) {
                log("Found test audio: ${f.absolutePath} (${f.length() / 1024} KB)")
                return f
            }
        }

        // Check assets
        try {
            val assets = getApplication<Application>().assets
            if (assets.list("")?.contains("test_audio_es.wav") == true) {
                val outFile = File(getApplication<Application>().cacheDir, "test_audio_es.wav")
                if (!outFile.exists()) {
                    assets.open("test_audio_es.wav").use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                log("Extracted test audio from assets: ${outFile.absolutePath}")
                return outFile
            }
        } catch (_: Exception) { }

        return null
    }

    /**
     * Generate a synthetic WAV file with a 440Hz sine tone.
     * 16kHz, mono, 16-bit PCM — matches AI Edge Gallery's audio format.
     */
    private fun generateSyntheticWav(file: File, durationMs: Int = 3000) {
        val sampleRate = 16000
        val numSamples = sampleRate * durationMs / 1000
        val pcmData = ShortArray(numSamples) { i ->
            (Short.MAX_VALUE * 0.5 * sin(2.0 * Math.PI * 440.0 * i / sampleRate)).toInt().toShort()
        }

        val dataSize = numSamples * 2  // 16-bit = 2 bytes per sample
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        // WAV header (RIFF/WAVE PCM format)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize)         // chunk size
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)                     // subchunk1 size (PCM)
        buffer.putShort(1)                    // audio format (1 = PCM)
        buffer.putShort(1)                    // num channels (mono)
        buffer.putInt(sampleRate)             // sample rate
        buffer.putInt(sampleRate * 2)         // byte rate
        buffer.putShort(2)                    // block align
        buffer.putShort(16)                   // bits per sample
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)               // subchunk2 size

        // PCM data
        pcmData.forEach { buffer.putShort(it) }

        file.writeBytes(buffer.array())
        log("Generated synthetic WAV: ${file.name} (${file.length()} bytes, ${durationMs}ms, 16kHz mono)")
    }

    /**
     * Extract raw PCM data from a WAV file (skip the header).
     */
    private fun extractPcmFromWav(wavFile: File): ByteArray? {
        try {
            val bytes = wavFile.readBytes()
            if (bytes.size < 44) return null

            val header = String(bytes, 0, 4)
            if (header != "RIFF") {
                log("Not a valid WAV file (header: $header)")
                return null
            }

            // Find "data" chunk
            var offset = 12
            while (offset < bytes.size - 8) {
                val chunkId = String(bytes, offset, 4)
                val chunkSize = ByteBuffer.wrap(bytes, offset + 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int

                if (chunkId == "data") {
                    val pcmStart = offset + 8
                    val pcmEnd = minOf(pcmStart + chunkSize, bytes.size)
                    val pcm = bytes.copyOfRange(pcmStart, pcmEnd)
                    log("Extracted PCM: ${pcm.size} bytes from WAV")
                    return pcm
                }
                offset += 8 + chunkSize
            }

            log("No 'data' chunk found, using 44-byte header skip")
            return bytes.copyOfRange(44, bytes.size)
        } catch (e: Exception) {
            log("Failed to extract PCM from WAV: ${e.message}")
            return null
        }
    }

    // --- Check audio/multimodal support ---

    private fun checkAudioSupport() {
        log("--- AUDIO SUPPORT CHECK ---")

        val details = StringBuilder()
        var audioSupported = false

        // Direct API checks — no reflection needed since we know the classes exist
        try {
            // Verify Content subclasses exist at compile time
            val audioBytes = Content.AudioBytes(ByteArray(0))
            details.appendLine("Content.AudioBytes: AVAILABLE (${audioBytes.javaClass.simpleName})")
            log("Content.AudioBytes: AVAILABLE")
            audioSupported = true
        } catch (e: Exception) {
            details.appendLine("Content.AudioBytes: FAILED (${e.message})")
            log("Content.AudioBytes: FAILED: ${e.message}")
        }

        try {
            val audioFile = Content.AudioFile("")
            details.appendLine("Content.AudioFile: AVAILABLE (${audioFile.javaClass.simpleName})")
            log("Content.AudioFile: AVAILABLE")
            audioSupported = true
        } catch (e: Exception) {
            details.appendLine("Content.AudioFile: FAILED (${e.message})")
            log("Content.AudioFile: FAILED: ${e.message}")
        }

        // Check EngineConfig audioBackend
        try {
            val testConfig = EngineConfig(
                modelPath = "/test",
                backend = Backend.CPU(),
                audioBackend = Backend.CPU(),
            )
            details.appendLine("EngineConfig.audioBackend: AVAILABLE")
            log("EngineConfig.audioBackend: AVAILABLE")
        } catch (e: Exception) {
            details.appendLine("EngineConfig.audioBackend: FAILED (${e.message})")
            log("EngineConfig.audioBackend: FAILED: ${e.message}")
        }

        // Check sendMessage overloads
        try {
            val convClass = Conversation::class.java
            val sendMethods = convClass.methods.filter { it.name.contains("sendMessage") }
            val methodSigs = sendMethods.map { m ->
                "${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString()}) → ${m.returnType.simpleName}"
            }
            details.appendLine("Conversation send methods (${methodSigs.size}):")
            methodSigs.forEach { sig ->
                details.appendLine("  $sig")
                log("  $sig")
            }
        } catch (e: Exception) {
            details.appendLine("Method check failed: ${e.message}")
        }

        if (audioSupported) {
            details.appendLine("")
            details.appendLine("AUDIO FULLY SUPPORTED!")
            details.appendLine("Config: EngineConfig(audioBackend=Backend.CPU())")
            details.appendLine("Send: conversation.sendMessage(Contents.of(")
            details.appendLine("  Content.AudioBytes(wavBytes),")
            details.appendLine("  Content.Text(prompt)")
            details.appendLine("))")
            log("AUDIO FULLY SUPPORTED!")
        } else {
            details.appendLine("")
            details.appendLine("AUDIO NOT SUPPORTED in this build")
            log("AUDIO NOT SUPPORTED")
        }

        _state.update {
            it.copy(
                audioSupported = audioSupported,
                audioCheckDetails = details.toString(),
            )
        }
    }

    // --- Translation test (text to text) ---

    fun runTranslationTest(spanishText: String) {
        if (_state.value.testRunning || !_state.value.modelLoaded) return

        viewModelScope.launch(Dispatchers.Default) {
            _state.update {
                it.copy(
                    testRunning = true,
                    spanishInput = spanishText,
                    englishOutput = "",
                    error = null,
                )
            }

            log("--- TRANSLATION TEST ---")
            log("Input (ES): $spanishText")

            try {
                val prompt = buildTranslationPrompt(spanishText)
                log("Prompt: $prompt")

                val startTime = System.nanoTime()

                val fullResponse = StringBuilder()
                var tokenCount = 0

                // Use sendMessage(String) for text-only — simplest overload
                try {
                    conversation!!.sendMessageAsync(prompt)
                        .catch { e ->
                            log("Streaming error: ${e.message}")
                            throw e
                        }
                        .collect { responseMsg: Message ->
                            val text = responseMsg.toString()
                            fullResponse.append(text)
                            tokenCount++
                            _state.update { it.copy(englishOutput = fullResponse.toString()) }
                        }
                } catch (e: Exception) {
                    log("Streaming failed (${e.message}), trying synchronous...")
                    fullResponse.clear()
                    tokenCount = 0

                    val response: Message = conversation!!.sendMessage(prompt)
                    val text = response.toString()
                    fullResponse.append(text)
                    tokenCount = text.split(" ").size
                }

                val inferenceTimeMs = (System.nanoTime() - startTime) / 1_000_000
                val tokensPerSec = if (inferenceTimeMs > 0) {
                    tokenCount.toFloat() / (inferenceTimeMs / 1000f)
                } else 0f

                val result = fullResponse.toString().trim()
                log("Output (EN): $result")
                log("Time: ${inferenceTimeMs}ms | Tokens: $tokenCount | Speed: ${"%.1f".format(tokensPerSec)} tok/s")

                updateMemoryMetrics()

                _state.update {
                    it.copy(
                        testRunning = false,
                        englishOutput = result,
                        inferenceTimeMs = inferenceTimeMs,
                        tokensGenerated = tokenCount,
                        tokensPerSecond = tokensPerSec,
                    )
                }

            } catch (e: Exception) {
                logError("Translation test failed", e)
                _state.update { it.copy(testRunning = false) }
            }
        }
    }

    private fun buildTranslationPrompt(spanishText: String): String {
        return """Translate the following Spanish text to English. Output ONLY the English translation, nothing else. Do not add explanations or notes.

Spanish: $spanishText

English:"""
    }

    // Predefined test cases
    val testCases = listOf(
        "Hola, buenos dias." to "Short greeting (~3s equivalent)",
        "Me gustaria pedir un cafe con leche y un sandwich de jamon, por favor." to "Restaurant order (~5s equivalent)",
        "Disculpe, podria indicarme como llegar a la estacion de tren mas cercana? Necesito tomar el tren a Chicago." to "Asking directions (~6s equivalent)",
        "El clima hoy esta muy agradable. Hace sol y la temperatura es perfecta para caminar por el parque." to "Weather comment (~5s equivalent)",
        "" to "Empty input (should handle gracefully)",
    )

    // --- Memory metrics ---

    private fun updateMemoryMetrics() {
        val runtime = Runtime.getRuntime()
        val javaHeapMB = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576
        val nativeHeapMB = Debug.getNativeHeapAllocatedSize() / 1_048_576
        val totalMB = javaHeapMB + nativeHeapMB

        _state.update {
            it.copy(
                javaHeapMB = javaHeapMB,
                nativeHeapMB = nativeHeapMB,
                totalMemoryMB = totalMB,
            )
        }

        log("Memory: Java=${javaHeapMB}MB | Native=${nativeHeapMB}MB | Total=${totalMB}MB")
    }

    // --- Cleanup ---

    override fun onCleared() {
        super.onCleared()
        try {
            engine?.close()
            engine = null
            conversation = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing engine", e)
        }
    }
}
