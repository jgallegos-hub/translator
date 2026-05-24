package com.travel2chicago.gemmatest

import android.app.Application
import android.os.Debug
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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

    // Default model directory
    private val modelDir: File
        get() = File(getApplication<Application>().filesDir, "models")

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

    private fun checkModelExists() {
        val dir = modelDir
        val exists = dir.exists() && (dir.listFiles()?.any { it.length() > 1_000_000 } == true)
        _state.update {
            it.copy(
                modelPath = dir.absolutePath,
                modelExists = exists,
            )
        }
        if (exists) {
            log("Model found at: ${dir.absolutePath}")
            dir.listFiles()?.forEach { f ->
                log("  ${f.name} (${f.length() / 1_048_576} MB)")
            }
        } else {
            log("No model found at: ${dir.absolutePath}")
        }
    }

    /**
     * Download model from HuggingFace.
     *
     * The LiteRT-LM model format is typically a single .task file or a directory.
     * We try to download from litert-community/gemma-4-E4B-it-litert-lm on HuggingFace.
     */
    fun downloadModel() {
        if (_state.value.downloading) return

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(downloading = true, downloadProgress = 0f, downloadError = null) }
            log("Starting model download from HuggingFace...")
            log("NOTE: Gemma 4 E4B is ~2.5GB. This will take a while on mobile data.")

            try {
                // Create model directory
                modelDir.mkdirs()

                // HuggingFace API to list repo files
                val repoUrl = "https://huggingface.co/api/models/litert-community/gemma-4-E4B-it-litert-lm"
                log("Checking model repo: $repoUrl")

                val connection = URL(repoUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    throw Exception("HuggingFace API returned $responseCode. Model may not exist or may require authentication.")
                }

                val repoInfo = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                log("Repo info received. Parsing file list...")
                log("Raw response (first 500 chars): ${repoInfo.take(500)}")

                // For POC: try to download the main model file
                // LiteRT-LM models are typically a single .bin or .task file
                val filesToTry = listOf(
                    "model.bin",
                    "gemma-4-e4b-it.task",
                    "gemma-4-e4b-it.litertlm",
                    "model.litertlm",
                )

                var downloaded = false
                for (filename in filesToTry) {
                    val fileUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/$filename"
                    log("Trying: $fileUrl")

                    try {
                        val fileConn = URL(fileUrl).openConnection() as HttpURLConnection
                        fileConn.requestMethod = "HEAD"
                        fileConn.connectTimeout = 10000
                        fileConn.instanceFollowRedirects = true

                        if (fileConn.responseCode == 200) {
                            val fileSize = fileConn.contentLengthLong
                            log("Found $filename (${fileSize / 1_048_576} MB)")
                            fileConn.disconnect()

                            // Download the file
                            downloadFile(fileUrl, File(modelDir, filename), fileSize)
                            downloaded = true
                            break
                        }
                        fileConn.disconnect()
                    } catch (e: Exception) {
                        log("  Not found or error: ${e.message}")
                    }
                }

                if (!downloaded) {
                    log("Could not find model files automatically.")
                    log("Manual download instructions:")
                    log("1. Visit: https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm")
                    log("2. Download model files")
                    log("3. Push to device: adb push <files> ${modelDir.absolutePath}/")
                    _state.update { it.copy(downloadError = "Auto-download failed. See logs for manual instructions.") }
                }

                checkModelExists()
            } catch (e: Exception) {
                logError("Download failed", e)
                _state.update { it.copy(downloadError = e.message) }
            } finally {
                _state.update { it.copy(downloading = false) }
            }
        }
    }

    private suspend fun downloadFile(url: String, dest: File, totalSize: Long) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true

        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(65536)
                var bytesRead: Long = 0
                var len: Int

                while (input.read(buffer).also { len = it } != -1) {
                    output.write(buffer, 0, len)
                    bytesRead += len

                    if (totalSize > 0) {
                        val progress = bytesRead.toFloat() / totalSize
                        _state.update { it.copy(downloadProgress = progress) }

                        // Log every ~50MB
                        if (bytesRead % (50 * 1_048_576) < 65536) {
                            log("Downloaded: ${bytesRead / 1_048_576} / ${totalSize / 1_048_576} MB (${(progress * 100).toInt()}%)")
                        }
                    }
                }
            }
        }
        conn.disconnect()
        log("Download complete: ${dest.name} (${dest.length() / 1_048_576} MB)")
    }

    // --- Load model ---

    fun loadModel() {
        if (_state.value.modelLoading || _state.value.modelLoaded) return

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(modelLoading = true, error = null) }
            log("Loading Gemma 4 E4B model...")
            updateMemoryMetrics()

            try {
                val startTime = System.nanoTime()

                // Find model file in the model directory
                val modelFile = findModelFile()
                    ?: throw Exception("No model file found in ${modelDir.absolutePath}")

                log("Using model file: ${modelFile.absolutePath}")
                log("Model size: ${modelFile.length() / 1_048_576} MB")

                // Try GPU backend first, fall back to CPU
                val backend = try {
                    log("Attempting GPU backend...")
                    Backend.GPU
                } catch (e: Exception) {
                    log("GPU not available, using CPU: ${e.message}")
                    Backend.CPU
                }

                val backendName = backend.javaClass.simpleName
                log("Backend: $backendName")

                val config = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend,
                )

                log("Creating engine (this takes ~10s)...")
                engine = Engine(config)

                val loadTimeMs = (System.nanoTime() - startTime) / 1_000_000

                log("Engine created in ${loadTimeMs}ms")
                updateMemoryMetrics()

                _state.update {
                    it.copy(
                        modelLoaded = true,
                        modelLoading = false,
                        modelLoadTimeMs = loadTimeMs,
                        backendUsed = backendName,
                    )
                }

                // Create a conversation
                log("Creating conversation...")
                conversation = engine!!.createConversation(ConversationConfig())
                log("Conversation ready")

                // Check for audio/multimodal support
                checkAudioSupport()

            } catch (e: Exception) {
                logError("Failed to load model", e)
                _state.update { it.copy(modelLoading = false) }
            }
        }
    }

    private fun findModelFile(): File? {
        if (!modelDir.exists()) return null

        // Look for model files in order of preference
        val extensions = listOf(".litertlm", ".task", ".bin", ".tflite")
        for (ext in extensions) {
            val files = modelDir.listFiles { _, name -> name.endsWith(ext) }
            if (files != null && files.isNotEmpty()) {
                return files.first()
            }
        }

        // If directory has files, return the largest one (likely the model)
        return modelDir.listFiles()
            ?.filter { it.isFile && it.length() > 1_000_000 }
            ?.maxByOrNull { it.length() }
    }

    // --- Check audio/multimodal support ---

    private fun checkAudioSupport() {
        log("--- AUDIO SUPPORT CHECK ---")

        val details = StringBuilder()
        var audioSupported = false

        try {
            // Check Message class for audio-related methods via reflection
            val messageClass = Message::class.java
            val methods = messageClass.declaredMethods.map { it.name }
            details.appendLine("Message methods: ${methods.joinToString()}")
            log("Message methods: ${methods.joinToString()}")

            // Check for audio-specific factory methods
            val audioMethods = methods.filter {
                it.contains("audio", ignoreCase = true) ||
                it.contains("pcm", ignoreCase = true) ||
                it.contains("wav", ignoreCase = true) ||
                it.contains("media", ignoreCase = true) ||
                it.contains("multimodal", ignoreCase = true)
            }

            if (audioMethods.isNotEmpty()) {
                details.appendLine("Audio-related methods found: ${audioMethods.joinToString()}")
                log("Audio-related methods found: ${audioMethods.joinToString()}")
                audioSupported = true
            } else {
                details.appendLine("No audio-related methods found in Message class")
                log("No audio-related methods found in Message class")
            }

            // Also check Message.Companion for factory methods
            try {
                val companionClass = Class.forName("com.google.ai.edge.litertlm.Message\$Companion")
                val companionMethods = companionClass.declaredMethods.map { it.name }
                details.appendLine("Message.Companion methods: ${companionMethods.joinToString()}")
                log("Message.Companion methods: ${companionMethods.joinToString()}")

                val audioCompanionMethods = companionMethods.filter {
                    it.contains("audio", ignoreCase = true) ||
                    it.contains("image", ignoreCase = true) ||
                    it.contains("media", ignoreCase = true)
                }
                if (audioCompanionMethods.isNotEmpty()) {
                    details.appendLine("Multimodal factory methods: ${audioCompanionMethods.joinToString()}")
                    log("Multimodal factory methods found: ${audioCompanionMethods.joinToString()}")
                    audioSupported = true
                }
            } catch (e: ClassNotFoundException) {
                details.appendLine("Message.Companion not found")
            }

            // Check EngineConfig for multimodal options
            val configClass = EngineConfig::class.java
            val configFields = configClass.declaredFields.map { it.name }
            details.appendLine("EngineConfig fields: ${configFields.joinToString()}")
            log("EngineConfig fields: ${configFields.joinToString()}")

            // Look for audioBackend field specifically
            val hasAudioBackend = configFields.any { it.contains("audio", ignoreCase = true) }
            if (hasAudioBackend) {
                details.appendLine("EngineConfig HAS audio-related fields!")
                log("EngineConfig HAS audio-related fields!")
            }

            // Check Conversation class
            val convClass = Conversation::class.java
            val convMethods = convClass.declaredMethods.map {
                "${it.name}(${it.parameterTypes.map { p -> p.simpleName }.joinToString()})"
            }
            details.appendLine("Conversation methods: ${convMethods.joinToString()}")
            log("Conversation methods: ${convMethods.joinToString()}")

            // Check ConversationConfig
            try {
                val convConfigClass = ConversationConfig::class.java
                val ccFields = convConfigClass.declaredFields.map { it.name }
                details.appendLine("ConversationConfig fields: ${ccFields.joinToString()}")
                log("ConversationConfig fields: ${ccFields.joinToString()}")
            } catch (e: Exception) {
                details.appendLine("ConversationConfig: ${e.message}")
            }

            // Check for audio-related classes in the package
            val audioClasses = listOf(
                "com.google.ai.edge.litertlm.AudioInput",
                "com.google.ai.edge.litertlm.AudioMessage",
                "com.google.ai.edge.litertlm.MultimodalMessage",
                "com.google.ai.edge.litertlm.MediaContent",
                "com.google.ai.edge.litertlm.Content",
                "com.google.ai.edge.litertlm.Contents",
            )
            for (className in audioClasses) {
                try {
                    val cls = Class.forName(className)
                    val clsMethods = cls.declaredMethods.map { it.name }
                    details.appendLine("FOUND class: $className")
                    details.appendLine("  methods: ${clsMethods.joinToString()}")
                    log("FOUND class: $className - methods: ${clsMethods.joinToString()}")

                    // Check for audio-related methods in Content/Contents
                    val audioContentMethods = clsMethods.filter {
                        it.contains("audio", ignoreCase = true) ||
                        it.contains("pcm", ignoreCase = true)
                    }
                    if (audioContentMethods.isNotEmpty()) {
                        details.appendLine("  AUDIO methods: ${audioContentMethods.joinToString()}")
                        log("  AUDIO methods in $className: ${audioContentMethods.joinToString()}")
                        audioSupported = true
                    }
                } catch (e: ClassNotFoundException) {
                    // Not found, expected
                }
            }

        } catch (e: Exception) {
            details.appendLine("Reflection check failed: ${e.message}")
            logError("Audio support check failed", e)
        }

        if (!audioSupported) {
            details.appendLine("")
            details.appendLine("CONCLUSION: Audio input NOT supported in current Kotlin API")
            details.appendLine("The LiteRT-LM Kotlin API only accepts text via Message.user(String)")
            details.appendLine("Gemma 4 E4B supports audio at C++ level but Kotlin wrapper is text-only")
            details.appendLine("")
            details.appendLine("FALLBACK: Use STT (Whisper ONNX) for audio->text, then Gemma for text translation")
            log("CONCLUSION: Audio NOT supported in Kotlin API. Need STT fallback.")
        } else {
            details.appendLine("")
            details.appendLine("AUDIO SUPPORT DETECTED! Investigate the methods above to build audio input.")
            log("AUDIO SUPPORT DETECTED! Review methods above.")
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

                // Build user message
                val message = Message.user(prompt)
                val fullResponse = StringBuilder()
                var tokenCount = 0

                // Try streaming first
                try {
                    conversation!!.sendMessageAsync(message).collect { responseMsg ->
                        // Each emission is a Message — extract text content
                        val text = extractTextFromMessage(responseMsg)
                        fullResponse.append(text)
                        tokenCount++

                        // Update UI with partial results
                        _state.update { it.copy(englishOutput = fullResponse.toString()) }
                    }
                } catch (e: Exception) {
                    // If streaming fails, try synchronous
                    log("Streaming failed (${e.message}), trying synchronous...")
                    val response = conversation!!.sendMessage(message)
                    val text = extractTextFromMessage(response)
                    fullResponse.append(text)
                    tokenCount = text.split(" ").size
                }

                val inferenceTimeMs = (System.nanoTime() - startTime) / 1_000_000
                val tokensPerSec = if (inferenceTimeMs > 0) {
                    tokenCount.toFloat() / (inferenceTimeMs / 1000f)
                } else 0f

                val result = fullResponse.toString().trim()
                log("Output (EN): $result")
                log("Time: ${inferenceTimeMs}ms | Tokens: $tokenCount | Speed: ${String.format("%.1f", tokensPerSec)} tok/s")

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

    /**
     * Extract text from a Message object.
     *
     * The LiteRT-LM Message class does not have a simple .text property.
     * We try multiple approaches to extract the text content:
     * 1. getContents() -> filter for text content
     * 2. toString() as fallback
     * 3. Reflection to find any text accessor
     */
    private fun extractTextFromMessage(msg: Message): String {
        // Try 1: Use getContents() API
        try {
            val contents = msg.getContents()
            if (contents != null) {
                // Contents might have a text representation
                val text = contents.toString()
                if (text.isNotBlank() && !text.startsWith("com.google.")) {
                    return text
                }

                // Try to get individual content items via reflection
                try {
                    val getContentsMethod = contents.javaClass.getMethod("getContents")
                    val items = getContentsMethod.invoke(contents)
                    if (items is List<*>) {
                        return items.mapNotNull { item ->
                            // Try getText() on each content item
                            try {
                                val getTextMethod = item!!.javaClass.getMethod("getText")
                                getTextMethod.invoke(item) as? String
                            } catch (e: Exception) {
                                item?.toString()
                            }
                        }.joinToString("")
                    }
                } catch (e: Exception) {
                    // getContents().getContents() not available
                }
            }
        } catch (e: Exception) {
            log("getContents() failed: ${e.message}")
        }

        // Try 2: Reflection to find text-related methods
        try {
            val methods = msg.javaClass.methods
            for (method in methods) {
                if (method.name in listOf("getText", "text", "getContent", "content") &&
                    method.parameterCount == 0 &&
                    method.returnType == String::class.java
                ) {
                    val result = method.invoke(msg)
                    if (result is String && result.isNotBlank()) {
                        return result
                    }
                }
            }
        } catch (e: Exception) {
            // Reflection failed
        }

        // Try 3: toString() as last resort
        val str = msg.toString()
        // Clean up common wrapper patterns
        return str
            .removePrefix("Message(")
            .removeSuffix(")")
            .trim()
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
