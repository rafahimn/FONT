package com.natuu.assistant

import android.content.Context
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * On-device Natuu chat runtime.
 * The model is downloaded once into private app storage and runs fully on-device afterward.
 * A single executor owns all model, conversation, and download work to avoid concurrent engine access.
 */
class OfflineModelManager(context: Context) {
    interface DownloadCallback {
        fun onProgress(percent: Int, downloadedBytes: Long, totalBytes: Long)
        fun onComplete(success: Boolean, message: String)
    }

    interface ReplyCallback {
        fun onComplete(success: Boolean, reply: String)
    }

    private val appContext = context.applicationContext
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val modelFile = File(appContext.filesDir, MODEL_FILENAME)
    private val partialFile = File(appContext.filesDir, "$MODEL_FILENAME.part")

    // Accessed and mutated only from worker except read-only isModelReady().
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    fun isModelReady(): Boolean = modelFile.isFile && modelFile.length() >= MIN_MODEL_BYTES

    fun downloadModel(callback: DownloadCallback) {
        worker.execute {
            if (isModelReady()) {
                callback.onProgress(100, modelFile.length(), modelFile.length())
                callback.onComplete(true, "The offline AI model is already downloaded.")
                return@execute
            }

            var connection: HttpURLConnection? = null
            try {
                partialFile.delete()
                connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 30000
                    readTimeout = 90000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Natuu-Android/1.1")
                }
                val status = connection.responseCode
                if (status !in 200..299) {
                    throw IllegalStateException("Model download failed (HTTP $status). Please try again.")
                }
                val total = connection.contentLengthLong
                var downloaded = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(partialFile).use { output ->
                        val buffer = ByteArray(1024 * 128)
                        var count: Int
                        var lastPercent = -1
                        while (input.read(buffer).also { count = it } != -1) {
                            output.write(buffer, 0, count)
                            downloaded += count
                            val percent = if (total > 0) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else -1
                            if (percent != lastPercent) {
                                callback.onProgress(percent, downloaded, total)
                                lastPercent = percent
                            }
                        }
                        output.fd.sync()
                    }
                }

                // This particular public model is approximately 586 MB. Reject an HTML/error page
                // or truncated download rather than treating it as a ready model.
                if (partialFile.length() < MIN_MODEL_BYTES) {
                    throw IllegalStateException("The download appears incomplete. The file is ${partialFile.length() / (1024 * 1024)} MB. Please try again.")
                }
                if (modelFile.exists()) modelFile.delete()
                if (!partialFile.renameTo(modelFile)) {
                    partialFile.copyTo(modelFile, overwrite = true)
                    partialFile.delete()
                }
                callback.onProgress(100, modelFile.length(), modelFile.length())
                callback.onComplete(true, "The offline AI model is ready. Text chat can now work without an internet connection.")
            } catch (e: Exception) {
                partialFile.delete()
                callback.onComplete(false, e.message ?: "Could not download the offline AI model.")
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun generateReply(prompt: String, callback: ReplyCallback) {
        worker.execute {
            try {
                if (!isModelReady()) {
                    throw IllegalStateException("The offline AI model has not been downloaded. Download it from Settings.")
                }
                ensureConversationLoaded()
                val output = StringBuilder()
                runBlocking {
                    checkNotNull(conversation).sendMessageAsync(prompt).collect { chunk ->
                        output.append(chunk.toString())
                    }
                }
                val text = output.toString().trim()
                if (text.isEmpty()) throw IllegalStateException("Offline AI returned an empty response. Please try again.")
                callback.onComplete(true, text)
            } catch (e: Exception) {
                callback.onComplete(false, e.message ?: "Offline AI could not run. Check the phone RAM and model compatibility.")
            }
        }
    }

    private fun ensureConversationLoaded() {
        if (engine != null && conversation != null) return
        val candidate = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                cacheDir = File(appContext.cacheDir, "natuu-litert-cache").absolutePath
            )
        )
        // Engine initialization can take several seconds; this runs only on the background worker.
        candidate.initialize()
        engine = candidate
        try {
            conversation = candidate.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        "You are Natuu, a friendly personal assistant. Reply in Bangla/Bengali when the user writes Bangla, otherwise reply in the user's language. Be honest about limitations, helpful and concise. You cannot directly control the phone unless the app confirms an action."
                    )
                )
            )
        } catch (e: Exception) {
            engine = null
            try { candidate.close() } catch (_: Exception) { }
            throw e
        }
    }

    fun close() {
        worker.execute {
            try { conversation?.close() } catch (_: Exception) { }
            conversation = null
            try { engine?.close() } catch (_: Exception) { }
            engine = null
        }
        worker.shutdown()
    }

    companion object {
        private const val MODEL_FILENAME = "Qwen3-0.6B.litertlm"
        private const val MIN_MODEL_BYTES = 300L * 1024L * 1024L
        private const val MODEL_URL = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm?download=true"
    }
}
