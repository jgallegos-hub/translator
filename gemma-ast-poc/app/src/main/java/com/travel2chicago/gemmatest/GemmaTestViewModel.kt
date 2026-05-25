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
            log("  Engine may fail without .xnnpack_cache and .bin files")
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
     * Priority: AI Edge Gallery dir (has companion files) > Downloads > internal.
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
                            "Alt: adb push model + companion files to /sdcard/Download/",
                    )
                }
            }

            _state.update { it.copy(downloading = false) }
        }
    }

    // --- Load model ---

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

                // Try backends in order: GPU → CPU
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

                        val config = buildEngineConfig(modelFile.absolutePath, backend, backendName)

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

                        // Create conversation
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
     * The companion files (.xnnpack_cache, .bin) must be in the same dir.
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

    /**
     * Build EngineConfig, attempting to set audioBackend via reflection.
     * The audioBackend field exists in EngineConfig but we don't know its
     * exact constructor signature, so we try reflection.
     */
    private fun buildEngineConfig(modelPath: String, backend: Any, backendName: String): EngineConfig {
        // First try: construct with audioBackend via reflection
        try {
            val configClass = EngineConfig::class.java
            val constructors = configClass.declaredConstructors

            // Find constructor that takes 3+ params (modelPath, backend, audioBackend, ...)
            for (constructor in constructors) {
                val paramTypes = constructor.parameterTypes
                log("EngineConfig constructor: (${paramTypes.map { it.simpleName }.joinToString()})")

                // Look for constructor with String, Backend, Backend pattern
                if (paramTypes.size >= 3 &&
                    paramTypes[0] == String::class.java &&
                    paramTypes[1].simpleName.contains("Backend") &&
                    paramTypes[2].simpleName.contains("Backend")
                ) {
                    constructor.isAccessible = true
                    val audioBackend = Backend.CPU() // Audio processing on CPU is fine
                    // Fill remaining params with defaults
                    val args = Array(paramTypes.size) { i ->
                        when (i) {
                            0 -> modelPath
                            1 -> backend
                            2 -> audioBackend
                            else -> getDefaultValue(paramTypes[i])
                        }
                    }
                    val config = constructor.newInstance(*args) as EngineConfig
                    log("EngineConfig created WITH audioBackend=CPU")
                    return config
                }
            }
        } catch (e: Exception) {
            log("Reflection EngineConfig failed: ${e.message}")
        }

        // Fallback: standard 2-param construction
        log("Using standard EngineConfig (no audioBackend)")
        return EngineConfig(
            modelPath = modelPath,
            backend = backend as Backend,
        )
    }

    private fun getDefaultValue(type: Class<*>): Any? {
        return when {
            type == Int::class.java || type == Integer::class.java -> 0
            type == Long::class.java || type == java.lang.Long::class.java -> 0L
            type == Float::class.java || type == java.lang.Float::class.java -> 0f
            type == Double::class.java || type == java.lang.Double::class.java -> 0.0
            type == Boolean::class.java || type == java.lang.Boolean::class.java -> false
            type == String::class.java -> ""
            else -> null
        }
    }

    // --- Audio AST Test ---

    /**
     * Test Audio Speech Translation: send audio to Gemma and get English text.
     * Tries multiple strategies:
     * 1. Content.AudioFile with WAV file path
     * 2. Content.AudioBytes with WAV file bytes
     * 3. Content.AudioBytes with raw PCM bytes
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

                val prompt = "Translate the following Spanish speech to English. " +
                    "Output ONLY the English translation, nothing else."

                // Strategy 1: Content.AudioFile with file path
                var success = tryAudioFileStrategy(audioFile.absolutePath, prompt)

                // Strategy 2: Content.AudioBytes with WAV bytes
                if (!success) {
                    val wavBytes = audioFile.readBytes()
                    success = tryAudioBytesStrategy(wavBytes, "WAV bytes", prompt)
                }

                // Strategy 3: Content.AudioBytes with raw PCM (strip WAV header)
                if (!success) {
                    val pcmBytes = extractPcmFromWav(audioFile)
                    if (pcmBytes != null) {
                        success = tryAudioBytesStrategy(pcmBytes, "raw PCM bytes", prompt)
                    }
                }

                if (!success) {
                    log("ALL audio strategies failed.")
                    log("CONCLUSION: Audio AST may need different API approach or model configuration.")
                    _state.update {
                        it.copy(
                            audioTestError = "All audio input strategies failed. See logs.",
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
     * Try sending audio via Content.AudioFile(path) using reflection
     * to call sendMessage with Message or Contents.
     */
    private fun tryAudioFileStrategy(filePath: String, prompt: String): Boolean {
        log("--- Strategy 1: Content.AudioFile ---")
        try {
            // Construct Content.AudioFile
            val audioFileClass = Class.forName("com.google.ai.edge.litertlm.Content\$AudioFile")
            val audioConstructor = audioFileClass.declaredConstructors.firstOrNull()
                ?: throw Exception("No constructor for Content.AudioFile")

            log("AudioFile constructor params: ${audioConstructor.parameterTypes.map { it.simpleName }}")
            audioConstructor.isAccessible = true

            val audioContent = audioConstructor.newInstance(filePath)
            log("Content.AudioFile created with path: $filePath")

            return trySendAudioMessage(audioContent as Content, prompt, "AudioFile")
        } catch (e: Exception) {
            log("Strategy 1 FAILED: ${e.message}")
            e.cause?.let { log("  Cause: ${it.message}") }
            return false
        }
    }

    /**
     * Try sending audio via Content.AudioBytes(byteArray) using reflection.
     */
    private fun tryAudioBytesStrategy(audioBytes: ByteArray, description: String, prompt: String): Boolean {
        log("--- Strategy: Content.AudioBytes ($description, ${audioBytes.size} bytes) ---")
        try {
            val audioBytesClass = Class.forName("com.google.ai.edge.litertlm.Content\$AudioBytes")
            val audioConstructor = audioBytesClass.declaredConstructors.firstOrNull()
                ?: throw Exception("No constructor for Content.AudioBytes")

            log("AudioBytes constructor params: ${audioConstructor.parameterTypes.map { it.simpleName }}")
            audioConstructor.isAccessible = true

            val audioContent = audioConstructor.newInstance(audioBytes)
            log("Content.AudioBytes created ($description)")

            return trySendAudioMessage(audioContent as Content, prompt, "AudioBytes($description)")
        } catch (e: Exception) {
            log("Strategy AudioBytes($description) FAILED: ${e.message}")
            e.cause?.let { log("  Cause: ${it.message}") }
            return false
        }
    }

    /**
     * Try to send a multi-modal message (audio + text) to the conversation.
     * Tries multiple sendMessage overloads via reflection.
     */
    private fun trySendAudioMessage(audioContent: Content, prompt: String, strategyName: String): Boolean {
        val conv = conversation ?: throw Exception("No conversation")

        // Build Contents with audio + text
        val textContent = Content.Text(prompt)
        val contents = Contents.of(audioContent, textContent)
        val userMsg = Message.user(contents)

        log("Message built: ${userMsg.javaClass.simpleName}")
        log("Contents: audio(${audioContent.javaClass.simpleName}) + text")

        val startTime = System.nanoTime()

        // Try 1: sendMessage(Message)
        try {
            val sendMethod = conv.javaClass.methods.find { m ->
                m.name == "sendMessage" &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0].isAssignableFrom(Message::class.java)
            }
            if (sendMethod != null) {
                log("Found sendMessage(Message) overload, calling...")
                val result = sendMethod.invoke(conv, userMsg) as Message
                val responseText = result.toString().trim()
                val timeMs = (System.nanoTime() - startTime) / 1_000_000

                log("SUCCESS with sendMessage(Message)!")
                log("Response: $responseText")
                log("Time: ${timeMs}ms")

                _state.update {
                    it.copy(
                        audioTestRunning = false,
                        audioTestOutput = responseText,
                        audioTestTimeMs = timeMs,
                        audioTestStrategy = strategyName,
                    )
                }
                return true
            }
        } catch (e: Exception) {
            log("sendMessage(Message) failed: ${e.message}")
            e.cause?.let { log("  Cause: ${it.message}") }
        }

        // Try 2: sendMessage(Contents)
        try {
            val sendMethod = conv.javaClass.methods.find { m ->
                m.name == "sendMessage" &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0].isAssignableFrom(Contents::class.java)
            }
            if (sendMethod != null) {
                log("Found sendMessage(Contents) overload, calling...")
                val result = sendMethod.invoke(conv, contents) as Message
                val responseText = result.toString().trim()
                val timeMs = (System.nanoTime() - startTime) / 1_000_000

                log("SUCCESS with sendMessage(Contents)!")
                log("Response: $responseText")
                log("Time: ${timeMs}ms")

                _state.update {
                    it.copy(
                        audioTestRunning = false,
                        audioTestOutput = responseText,
                        audioTestTimeMs = timeMs,
                        audioTestStrategy = strategyName,
                    )
                }
                return true
            }
        } catch (e: Exception) {
            log("sendMessage(Contents) failed: ${e.message}")
            e.cause?.let { log("  Cause: ${it.message}") }
        }

        // Try 3: sendMessageAsync(Message) → collect Flow
        try {
            val sendAsyncMethod = conv.javaClass.methods.find { m ->
                m.name == "sendMessageAsync" &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0].isAssignableFrom(Message::class.java)
            }
            if (sendAsyncMethod != null) {
                log("Found sendMessageAsync(Message) overload, calling...")
                @Suppress("UNCHECKED_CAST")
                val flow = sendAsyncMethod.invoke(conv, userMsg) as kotlinx.coroutines.flow.Flow<Message>

                val fullResponse = StringBuilder()
                var tokenCount = 0
                kotlinx.coroutines.runBlocking {
                    flow.collect { msg ->
                        fullResponse.append(msg.toString())
                        tokenCount++
                    }
                }

                val responseText = fullResponse.toString().trim()
                val timeMs = (System.nanoTime() - startTime) / 1_000_000

                log("SUCCESS with sendMessageAsync(Message)!")
                log("Response: $responseText")
                log("Time: ${timeMs}ms | Tokens: $tokenCount")

                _state.update {
                    it.copy(
                        audioTestRunning = false,
                        audioTestOutput = responseText,
                        audioTestTimeMs = timeMs,
                        audioTestStrategy = strategyName,
                    )
                }
                return true
            }
        } catch (e: Exception) {
            log("sendMessageAsync(Message) failed: ${e.message}")
            e.cause?.let { log("  Cause: ${it.message}") }
        }

        // Log all available methods for debugging
        log("Available Conversation methods:")
        conv.javaClass.methods
            .filter { it.name.contains("send", ignoreCase = true) }
            .forEach { m ->
                log("  ${m.name}(${m.parameterTypes.map { it.simpleName }.joinToString()})")
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
     * 16kHz, mono, 16-bit PCM — matches our pipeline audio format.
     * This is for API acceptance testing only (not real translation).
     */
    private fun generateSyntheticWav(file: File, durationMs: Int = 3000) {
        val sampleRate = 16000
        val numSamples = sampleRate * durationMs / 1000
        val pcmData = ShortArray(numSamples) { i ->
            (Short.MAX_VALUE * 0.5 * sin(2.0 * Math.PI * 440.0 * i / sampleRate)).toInt().toShort()
        }

        val dataSize = numSamples * 2  // 16-bit = 2 bytes per sample
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        // WAV header
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
     * Extract raw PCM data from a WAV file (skip the 44-byte header).
     */
    private fun extractPcmFromWav(wavFile: File): ByteArray? {
        try {
            val bytes = wavFile.readBytes()
            if (bytes.size < 44) return null

            // Verify RIFF/WAVE header
            val header = String(bytes, 0, 4)
            if (header != "RIFF") {
                log("Not a valid WAV file (header: $header)")
                return null
            }

            // Find "data" chunk
            var offset = 12 // after RIFF header
            while (offset < bytes.size - 8) {
                val chunkId = String(bytes, offset, 4)
                val chunkSize = ByteBuffer.wrap(bytes, offset + 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int

                if (chunkId == "data") {
                    val pcmStart = offset + 8
                    val pcmEnd = minOf(pcmStart + chunkSize, bytes.size)
                    val pcm = bytes.copyOfRange(pcmStart, pcmEnd)
                    log("Extracted PCM: ${pcm.size} bytes from WAV (data chunk at offset $offset)")
                    return pcm
                }
                offset += 8 + chunkSize
            }

            // Fallback: skip 44 bytes
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

        try {
            // Check Content class for AudioBytes / AudioFile subclasses
            val contentClass = Content::class.java
            val subclasses = contentClass.declaredClasses.map { it.simpleName }
            details.appendLine("Content subclasses: ${subclasses.joinToString()}")
            log("Content subclasses: ${subclasses.joinToString()}")

            val audioSubclasses = subclasses.filter {
                it.contains("Audio", ignoreCase = true)
            }
            if (audioSubclasses.isNotEmpty()) {
                details.appendLine("AUDIO Content types found: ${audioSubclasses.joinToString()}")
                log("AUDIO Content types found: ${audioSubclasses.joinToString()}")
                audioSupported = true
            }

            // Try to instantiate Content.AudioBytes to confirm it exists
            try {
                val audioBytesClass = Class.forName("com.google.ai.edge.litertlm.Content\$AudioBytes")
                val constructors = audioBytesClass.declaredConstructors.map {
                    "${it.name}(${it.parameterTypes.map { p -> p.simpleName }.joinToString()})"
                }
                details.appendLine("Content.AudioBytes constructors: ${constructors.joinToString()}")
                log("Content.AudioBytes constructors: ${constructors.joinToString()}")
                audioSupported = true
            } catch (e: ClassNotFoundException) {
                details.appendLine("Content.AudioBytes NOT found")
                log("Content.AudioBytes NOT found")
            }

            // Try Content.AudioFile
            try {
                val audioFileClass = Class.forName("com.google.ai.edge.litertlm.Content\$AudioFile")
                details.appendLine("Content.AudioFile FOUND")
                log("Content.AudioFile FOUND")
                audioSupported = true
            } catch (e: ClassNotFoundException) {
                details.appendLine("Content.AudioFile NOT found")
            }

            // Check EngineConfig for audioBackend field
            val configClass = EngineConfig::class.java
            val configFields = configClass.declaredFields.map { it.name }
            details.appendLine("EngineConfig fields: ${configFields.joinToString()}")
            log("EngineConfig fields: ${configFields.joinToString()}")

            val hasAudioBackend = configFields.any { it.contains("audio", ignoreCase = true) }
            if (hasAudioBackend) {
                details.appendLine("EngineConfig HAS audioBackend field!")
                log("EngineConfig HAS audioBackend field!")
            }

            // Check Message factory methods
            val messageClass = Message::class.java
            val methods = messageClass.declaredMethods.map { it.name }.distinct()
            details.appendLine("Message methods: ${methods.joinToString()}")
            log("Message methods: ${methods.joinToString()}")

            // Check Contents class
            val contentsClass = Contents::class.java
            val contentsMethods = contentsClass.declaredMethods.map { it.name }.distinct()
            details.appendLine("Contents methods: ${contentsMethods.joinToString()}")
            log("Contents methods: ${contentsMethods.joinToString()}")

            // Check Conversation sendMessage overloads
            val convClass = Conversation::class.java
            val convMethods = convClass.declaredMethods.map {
                "${it.name}(${it.parameterTypes.map { p -> p.simpleName }.joinToString()})"
            }
            details.appendLine("Conversation methods: ${convMethods.joinToString()}")
            log("Conversation methods: ${convMethods.joinToString()}")

        } catch (e: Exception) {
            details.appendLine("Reflection check failed: ${e.message}")
            logError("Audio support check failed", e)
        }

        if (audioSupported) {
            details.appendLine("")
            details.appendLine("AUDIO SUPPORT DETECTED!")
            details.appendLine("Content.AudioBytes and/or Content.AudioFile exist.")
            details.appendLine("Use: Message.user(Contents.of(Content.AudioBytes(pcmData), Content.Text(prompt)))")
            details.appendLine("EngineConfig supports audioBackend parameter.")
            log("AUDIO SUPPORT DETECTED in LiteRT-LM API!")
        } else {
            details.appendLine("")
            details.appendLine("CONCLUSION: Audio input NOT supported in current Kotlin API")
            details.appendLine("FALLBACK: Use STT (Whisper ONNX) for audio->text, then Gemma for translation")
            log("CONCLUSION: Audio NOT supported. Need STT fallback.")
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

                // Try streaming with Flow first
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
                    // Fallback to synchronous
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
