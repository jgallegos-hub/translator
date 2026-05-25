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
            // 3. Manual placement in Downloads
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            // 4. App-internal fallback
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

                        val config = EngineConfig(
                            modelPath = modelFile.absolutePath,
                            backend = backend,
                        )

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
