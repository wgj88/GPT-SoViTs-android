package ai.gsv.mobile

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Downloads the multilingual whisper.cpp tiny model on first use and keeps it app-private. */
internal object WhisperModelManager {
    private const val MODEL_NAME = "ggml-tiny.bin"
    private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin?download=true"
    private const val MIN_MODEL_BYTES = 50L * 1024L * 1024L

    fun ensureModel(filesDir: File, onProgress: (Int) -> Unit = {}): File {
        val modelDir = File(filesDir, "models/whisper").apply { mkdirs() }
        val model = File(modelDir, MODEL_NAME)
        if (model.isFile && model.length() >= MIN_MODEL_BYTES) return model
        val partial = File(modelDir, "$MODEL_NAME.download")
        partial.delete()
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "GPT-SoViTs-Android/3.2.1")
        }
        try {
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "model download returned HTTP $code" }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        if (total > 0L) {
                            val percent = ((copied * 100L) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
            check(partial.length() >= MIN_MODEL_BYTES) {
                "downloaded Whisper model is too small (${partial.length()} bytes); retry with a stable network"
            }
            check(partial.renameTo(model)) { "could not install downloaded Whisper model" }
            return model
        } catch (error: Exception) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }
}
