package ai.gsv.mobile

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * High-performance ExecutionBackend bridging the Jetpack Compose Android UI
 * directly to the resident Qualcomm Hexagon NPU GPT-SoVITS V5 Turbo engine.
 */
class V5TurboBridgeBackend(
    private val endpoint: String = "http://127.0.0.1:9872"
) : ExecutionBackend {

    override fun supports(model: ModelPackage): Boolean {
        if (model.version in setOf("v5turbo", "v5dev")) return true
        if (model.executor in setOf("v5turbo-bridge", "qnn-htp-v5", "remote-v5")) return true
        return false
    }

    override fun open(model: ModelPackage): ExecutionSession {
        checkBackendHealth(endpoint)
        return V5TurboBridgeSession(model, endpoint)
    }

    private fun checkBackendHealth(baseUrl: String) {
        val url = URL("$baseUrl/health")
        try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 1500
                readTimeout = 1500
                requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "Backend /health returned HTTP $code")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not ping V5 Turbo backend at $baseUrl: ${e.message}")
        }
    }

    companion object {
        const val TAG = "V5TurboBridge"
    }
}

/**
 * Session executing synthesis requests against the resident Qualcomm Hexagon V69/V73/V75 NPU.
 */
class V5TurboBridgeSession(
    private val model: ModelPackage,
    private val endpoint: String,
) : ExecutionSession {

    override val displayName: String = "GPT-SoVITS V5 Turbo (Snapdragon NPU 100% Resident)"

    private var cachedSpeakerHash: String? = null

    override fun synthesize(request: SynthesisRequest, output: File): File {
        val trace = TimingTrace("synthesis.v5turbo.npu", output)
        return try {
            TimingContext.with(trace) {
                TimingContext.measure("request.validate") {
                    require(request.text.isNotBlank()) { "text must not be empty" }
                }

                // 1. Sync reference speaker if provided, or model preset prompt
                if (request.reference != null) {
                    TimingContext.measure("reference.sync") {
                        syncSpeakerIfNeeded(request.reference)
                    }
                } else if (cachedSpeakerHash == null) {
                    TimingContext.measure("model.preset_speaker.sync") {
                        syncModelPresetSpeaker()
                    }
                }

                // 2. Synthesize via V5 Turbo NPU REST pipeline
                val timingElapsed = TimingContext.measure("npu.synthesize") {
                    executeTts(request, output)
                }

                TimingContext.mark("v5turbo.elapsed_seconds", timingElapsed)
            }
            trace.finish(true)
            output
        } catch (error: Throwable) {
            trace.finish(false, error)
            throw error
        }
    }

    private fun syncSpeakerIfNeeded(ref: ReferenceInput) {
        val hash = sha256("${ref.text}:${ref.language}:${ref.pcm16k.size}")
        if (hash == cachedSpeakerHash) {
            return
        }

        val tempWav = File.createTempFile("v5_ref_", ".wav")
        try {
            val shortPcm = ShortArray(ref.pcm16k.size) { i ->
                (ref.pcm16k[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
            }
            WavWriter.write(tempWav, shortPcm, 16000)
            uploadSpeakerMultipart(tempWav, ref.text, ref.language)
            cachedSpeakerHash = hash
        } finally {
            tempWav.delete()
        }
    }

    private fun syncModelPresetSpeaker() {
        val presetWav = model.runtimeFile("sample_prompt.wav")
        val presetTxt = model.runtimeFile("sample_prompt.txt")
        if (presetWav.isFile) {
            val promptText = if (presetTxt.isFile) presetTxt.readText().trim() else "你好，欢迎使用 GPT-SoVITS V5 离线极速语音合成。"
            uploadSpeakerMultipart(presetWav, promptText, "zh")
            cachedSpeakerHash = sha256("preset:${presetWav.length()}")
        }
    }

    private fun uploadSpeakerMultipart(wavFile: File, promptText: String, promptLang: String) {
        val boundary = "===V5NpuBoundary${System.currentTimeMillis()}==="
        val lineEnd = "\r\n"
        val url = URL("$endpoint/upload_speaker")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 15000
            doOutput = true
            doInput = true
            useCaches = false
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        conn.outputStream.use { os ->
            val writer = PrintWriter(OutputStreamWriter(os, "UTF-8"), true)

            // prompt_text
            writer.append("--").append(boundary).append(lineEnd)
            writer.append("Content-Disposition: form-data; name=\"prompt_text\"").append(lineEnd)
            writer.append("Content-Type: text/plain; charset=UTF-8").append(lineEnd)
            writer.append(lineEnd)
            writer.append(promptText).append(lineEnd)
            writer.flush()

            // prompt_lang
            writer.append("--").append(boundary).append(lineEnd)
            writer.append("Content-Disposition: form-data; name=\"prompt_lang\"").append(lineEnd)
            writer.append("Content-Type: text/plain; charset=UTF-8").append(lineEnd)
            writer.append(lineEnd)
            writer.append(promptLang).append(lineEnd)
            writer.flush()

            // file
            writer.append("--").append(boundary).append(lineEnd)
            writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"${wavFile.name}\"").append(lineEnd)
            writer.append("Content-Type: audio/wav").append(lineEnd)
            writer.append(lineEnd)
            writer.flush()

            FileInputStream(wavFile).use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    os.write(buffer, 0, bytesRead)
                }
                os.flush()
            }

            writer.append(lineEnd)
            writer.append("--").append(boundary).append("--").append(lineEnd)
            writer.flush()
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
            throw IllegalStateException("Failed to upload reference audio to V5 NPU backend: $errorText")
        }
    }

    private fun executeTts(request: SynthesisRequest, output: File): String {
        val url = URL("$endpoint/tts")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 60000
            doOutput = true
            doInput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "audio/wav")
        }

        val payload = JSONObject().apply {
            put("text", request.text)
            put("text_lang", request.language)
            put("speed", request.options.speedFactor.toDouble())
            put("seed", if (request.seed >= 0) request.seed else 20261018L)
        }

        conn.outputStream.use { os ->
            os.write(payload.toString().toByteArray(Charsets.UTF_8))
            os.flush()
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
            throw IllegalStateException("V5 Turbo NPU synthesis failed ($code): $err")
        }

        val elapsed = conn.getHeaderField("X-Elapsed-Seconds") ?: "0.00"

        output.parentFile?.mkdirs()
        conn.inputStream.use { input ->
            FileOutputStream(output).use { fos ->
                val buffer = ByteArray(16384)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    fos.write(buffer, 0, read)
                }
            }
        }

        require(output.exists() && output.length() > 44) {
            "V5 NPU backend returned an empty or invalid WAV file"
        }

        // Write timing sidecar
        val timingFile = File("${output.path}.timing.json")
        timingFile.writeText(
            JSONObject().apply {
                put("elapsed_seconds", elapsed.toDoubleOrNull() ?: 0.0)
                put("backend", displayName)
                put("model_version", model.version)
                put("sample_rate", model.sampleRate)
            }.toString(2)
        )

        return elapsed
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    override fun close() {
        cachedSpeakerHash = null
    }
}
