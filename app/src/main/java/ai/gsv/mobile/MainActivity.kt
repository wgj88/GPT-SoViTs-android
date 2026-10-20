package ai.gsv.mobile

import android.Manifest
import android.media.MediaPlayer
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.ResultReceiver
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val engine: TtsEngine get() = GsvRuntime.engine
    private val engineLock: Any get() = GsvRuntime.engineLock
    private var output: File? = null
    private var player: MediaPlayer? = null
    private var pendingApiPort: Int? = null

    private var ui by mutableStateOf(UiState())

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { rememberAndLoadModel(it, split = false) }
    }
    private val pickReference = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            ui = ui.copy(referenceUri = it, referenceName = displayName(it))
        }
    }
    private val saveOutput = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val source = output
        if (uri != null && source != null) {
            setBusy(getString(R.string.saving_audio))
            lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        require(source.isFile) { getString(R.string.audio_output_missing) }
                        contentResolver.openOutputStream(uri, "w").use { destination ->
                            requireNotNull(destination) { getString(R.string.audio_output_open_failed) }
                            source.inputStream().use { it.copyTo(destination) }
                        }
                    }
                }.onSuccess {
                    ui = ui.copy(busy = false, status = getString(R.string.audio_saved))
                }.onFailure {
                    ui = ui.copy(
                        busy = false,
                        status = getString(R.string.audio_save_failed, it.message.orEmpty()),
                    )
                }
            }
        }
    }
    private val requestExternalRead = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanExternalModels(silent = false)
        else ui = ui.copy(status = getString(R.string.external_models_permission_required))
    }
    private val manageExternalStorage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (hasExternalModelAccess()) scanExternalModels(silent = false)
        else ui = ui.copy(status = getString(R.string.external_models_permission_required))
    }
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingApiPort?.let(::startApiService)
        pendingApiPort = null
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()
        GsvRuntime.retainActivity()
        val loaded = engine.loadedPackage
        val restoredOutput = File(cacheDir, "tts.wav").takeIf {
            savedInstanceState?.getBoolean(STATE_CAN_PLAY) == true && it.isFile
        }
        output = restoredOutput
        ui = restoreSynthesisState(savedInstanceState).copy(
            port = intent.getIntExtra("api_port", 9880).toString(),
            models = readModelRecords(),
            appLanguage = AppLocale.selected(this),
            modelInfo = loaded?.let { "${it.name} / ${it.productVersion.ifBlank { it.version }} / ${it.sampleRate} Hz" }
                ?: getString(R.string.model_not_loaded),
            modelLoaded = loaded != null,
            backend = loaded?.let { engine.backendName } ?: getString(R.string.backend_not_loaded),
            runtimeOptions = loaded?.runtimeOptions ?: emptySet(),
            referenceOverrideSupported = loaded?.referenceInputVersion?.let { it >= 1 } ?: false,
            referenceExactPcm16kSamples = loaded?.referencePcm16kSamples?.takeIf {
                loaded.referenceDurationPolicy == "exact_samples" && it > 0
            },
            hasPresetSpeaker = loaded?.runtimeFile("sample_prompt.wav")?.isFile == true,
            canPlay = restoredOutput != null,
            serverEnabled = GsvRuntime.apiEndpoint != null,
            serverStatus = GsvRuntime.apiEndpoint?.let { "$it · POST /v1/audio/speech" }
                ?: GsvRuntime.apiError?.let { getString(R.string.api_start_failed, it) }
                ?: getString(R.string.api_stopped),
        )
        InferenceProgressBus.listener = { progress ->
            runOnUiThread {
                if (ui.busy) {
                    ui = ui.copy(
                        inferenceStage = progress.stage,
                        inferenceCurrent = progress.current,
                        inferenceTotal = progress.total,
                        inferenceRate = progress.iterationsPerSecond,
                    )
                }
            }
        }
        setContent { GsvTheme { MainScreen() } }
        DebugAcceptanceRunner.launch(this, intent)
        autoLoadDefaultModel()
        if (intent.getBooleanExtra("start_api", false)) startApiServer()
    }

    private fun autoLoadDefaultModel() {
        if (engine.isLoaded) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (hasExternalModelAccess()) {
                    val dirs = externalModelDirectories()
                    dirs.forEach { dir -> if (!dir.exists()) dir.mkdirs() }
                    val discovered = dirs.flatMap { dir ->
                        dir.listFiles().orEmpty().filter {
                            it.isFile && it.name.endsWith(".gsvm", ignoreCase = true)
                        }.toList()
                    }.distinctBy { it.canonicalPath }
                        .sortedBy { it.name.lowercase() }
                        .mapNotNull(::readExternalModelRecord)

                    if (discovered.isNotEmpty()) {
                        val existing = readModelRecords().associateBy(ModelRecord::uri)
                        val merged = (discovered.map { existing[it.uri] ?: it } + existing.values).distinctBy { it.uri }
                        writeModelRecords(merged)
                    }
                }

                val candidateFiles = listOf(
                    File(Environment.getExternalStorageDirectory(), "models/gsv/v5turbo-snapdragon-npu.gsvm"),
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "v5turbo-snapdragon-npu.gsvm"),
                    File(Environment.getExternalStorageDirectory(), "models/gs/v5turbo-snapdragon-npu.gsvm"),
                )
                val defaultFile = candidateFiles.firstOrNull { it.isFile }
                withContext(Dispatchers.Main) {
                    if (defaultFile != null) {
                        loadModelUri(Uri.fromFile(defaultFile), split = false, remember = true)
                    } else {
                        val records = readModelRecords()
                        if (records.isNotEmpty()) {
                            loadModelRecord(records.first())
                        }
                    }
                }
            }
        }
    }

    private fun applySystemBars() {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = if (dark) android.graphics.Color.rgb(16, 25, 22)
            else android.graphics.Color.rgb(245, 248, 244)
        window.navigationBarColor = if (dark) android.graphics.Color.rgb(16, 25, 22)
            else android.graphics.Color.rgb(245, 248, 244)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    private fun requestExternalModelScan() {
        if (hasExternalModelAccess()) {
            scanExternalModels(silent = false)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appSettings = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            runCatching { manageExternalStorage.launch(appSettings) }
                .onFailure { manageExternalStorage.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            requestExternalRead.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasExternalModelAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun externalModelDirectories(): List<File> = listOf(
        File(Environment.getExternalStorageDirectory(), "models/gsv"),
        File(Environment.getExternalStorageDirectory(), "models/gs"),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
    )

    private fun scanExternalModels(silent: Boolean) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val dirs = externalModelDirectories()
                    dirs.forEach { dir ->
                        if (!dir.exists()) dir.mkdirs()
                    }
                    val discovered = dirs.flatMap { dir ->
                        dir.listFiles().orEmpty().filter {
                            it.isFile && it.name.endsWith(".gsvm", ignoreCase = true)
                        }.toList()
                    }.distinctBy { it.canonicalPath }
                        .sortedBy { it.name.lowercase() }
                        .mapNotNull(::readExternalModelRecord)
                    discovered
                }
            }.onSuccess { discovered ->
                val existing = ui.models.associateBy(ModelRecord::uri)
                val refreshed = discovered.map { existing[it.uri] ?: it }
                val merged = (refreshed + ui.models).distinctBy { it.uri }
                writeModelRecords(merged)
                if (!silent || discovered.isNotEmpty()) {
                    ui = ui.copy(status = getString(R.string.external_models_scanned, discovered.size))
                }
                if (!engine.isLoaded && merged.isNotEmpty()) {
                    loadModelRecord(merged.first())
                }
            }.onFailure { error ->
                if (!silent) ui = ui.copy(
                    status = getString(R.string.external_models_scan_failed, error.message.orEmpty()),
                )
            }
        }
    }

    private fun readExternalModelRecord(file: File): ModelRecord? = runCatching {
        val manifest = file.inputStream().use(ModelPackage::inspectManifest)
        val role = manifest.optString("artifact_role", "combined")
        if (role == "pipeline" || role.startsWith("qnn-")) {
            return@runCatching null
        }
        ModelRecord(
            uri = Uri.fromFile(file).toString(),
            name = manifest.optString("name", file.nameWithoutExtension),
            version = manifest.optString("model_version", "v5turbo"),
            split = false,
            productVersion = manifest.optString("product_version", "5.0-npu-turbo"),
        )
    }.getOrNull()

    private fun rememberAndLoadModel(uri: Uri, split: Boolean = false) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        loadModelUri(uri, split = split, remember = true)
    }

    private fun loadModelUri(uri: Uri, split: Boolean = false, remember: Boolean) {
        if (!modelUriExists(uri)) {
            removeModelRecord(uri.toString())
            ui = ui.copy(status = getString(R.string.model_missing_removed))
            return
        }
        loadPackage(getString(R.string.model_loading), importer = {
            ModelPackage.import(this, uri)
        }) { model ->
            if (remember) saveModelRecord(
                ModelRecord(
                    uri = uri.toString(),
                    name = model.name,
                    version = model.version,
                    split = false,
                    productVersion = model.productVersion,
                )
            )
        }
    }

    private fun loadModelRecord(record: ModelRecord) {
        loadModelUri(Uri.parse(record.uri), split = false, remember = false)
    }

    private fun modelUriExists(uri: Uri): Boolean = runCatching {
        contentResolver.openInputStream(uri).use { requireNotNull(it); it.read() }
        true
    }.getOrDefault(false)

    private fun readModelRecords(): List<ModelRecord> {
        val stateFile = File(filesDir, "state/model-records.json")
        val raw = runCatching { stateFile.readText() }.getOrNull()
            ?: getSharedPreferences("models", MODE_PRIVATE).getString("records", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                array.getJSONObject(index).let {
                    ModelRecord(
                        it.getString("uri"),
                        it.getString("name"),
                        it.getString("version"),
                        it.optBoolean("split", false),
                        it.optString("qnn_uri").takeIf(String::isNotBlank),
                        it.optString("base_model_sha256").takeIf(String::isNotBlank),
                        it.optString("product_version").takeIf(String::isNotBlank).orEmpty(),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeModelRecords(records: List<ModelRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("uri", record.uri)
                    .put("name", record.name)
                    .put("version", record.version)
                    .put("split", record.split)
                    .put("qnn_uri", record.qnnUri ?: "")
                    .put("base_model_sha256", record.baseModelSha256 ?: "")
                    .put("product_version", record.productVersion),
            )
        }
        getSharedPreferences("models", MODE_PRIVATE).edit().putString("records", array.toString()).apply()
        val stateFile = File(filesDir, "state/model-records.json")
        stateFile.parentFile?.mkdirs()
        val pending = File(stateFile.parentFile, "${stateFile.name}.pending")
        pending.writeText(array.toString())
        if (stateFile.exists()) stateFile.delete()
        require(pending.renameTo(stateFile)) { getString(R.string.model_list_save_failed) }
        ui = ui.copy(models = records)
    }

    private fun saveModelRecord(record: ModelRecord) {
        writeModelRecords(listOf(record) + ui.models.filterNot { it.uri == record.uri })
    }

    private fun removeModelRecord(uri: String) = writeModelRecords(ui.models.filterNot { it.uri == uri })

    private fun loadPackage(message: String, importer: () -> ModelPackage, loaded: (ModelPackage) -> Unit = {}) {
        setBusy(message)
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    TimingContext.measure("model.import_and_engine_load") {
                        synchronized(engineLock) {
                            val model = importer()
                            engine.load(model)
                            model
                        }
                    }
                }
            }.onSuccess {
                loaded(it)
                ui = ui.copy(
                    busy = false,
                    modelInfo = "${it.name} / ${it.productVersion.ifBlank { it.version }} / ${it.sampleRate} Hz",
                    modelLoaded = true,
                    backend = engine.backendName,
                    runtimeOptions = it.runtimeOptions,
                    referenceOverrideSupported = it.referenceInputVersion >= 1,
                    hasPresetSpeaker = it.runtimeFile("sample_prompt.wav").isFile,
                    referenceExactPcm16kSamples = it.referencePcm16kSamples.takeIf { samples ->
                        it.referenceDurationPolicy == "exact_samples" && samples > 0
                    },
                    temperature = if ("temperature" in it.runtimeOptions) ui.temperature else "1.0",
                    topP = if ("top_p" in it.runtimeOptions) ui.topP else "1.0",
                    topK = if ("top_k" in it.runtimeOptions) ui.topK else "10",
                    penalty = if ("repetition_penalty" in it.runtimeOptions) ui.penalty else "1.35",
                    speed = if ("speed_factor" in it.runtimeOptions) ui.speed else "1.0",
                    steps = if ("sample_steps" in it.runtimeOptions) ui.steps else "32",
                    referenceUri = null,
                    referenceName = "",
                    referencePrompt = "",
                    status = getString(R.string.model_loaded),
                )
            }.onFailure { error ->
                android.util.Log.e("MainActivity", "loadPackage failed", error)
                ui = ui.copy(
                    busy = false,
                    status = if (error is UnsupportedLegacyModelException) {
                        getString(R.string.legacy_model_unsupported)
                    } else {
                        getString(R.string.import_failed, error.message.orEmpty())
                    },
                )
            }
        }
    }

    private fun synthesize() {
        val state = ui
        if (state.text.isBlank()) {
            val hint = getString(R.string.text_required_hint)
            android.widget.Toast.makeText(this, hint, android.widget.Toast.LENGTH_SHORT).show()
            ui = ui.copy(status = hint)
            return
        }
        if (state.referenceUri == null && !state.hasPresetSpeaker) {
            val hint = getString(R.string.reference_required_hint)
            android.widget.Toast.makeText(this, hint, android.widget.Toast.LENGTH_SHORT).show()
            ui = ui.copy(status = hint)
            return
        }
        if (state.referenceUri != null && state.referencePrompt.isBlank()) {
            val hint = getString(R.string.reference_prompt_required)
            android.widget.Toast.makeText(this, hint, android.widget.Toast.LENGTH_SHORT).show()
            ui = ui.copy(status = hint)
            return
        }
        if (state.referenceUri != null && !state.referenceOverrideSupported) {
            val hint = getString(R.string.reference_not_supported)
            android.widget.Toast.makeText(this, hint, android.widget.Toast.LENGTH_SHORT).show()
            ui = ui.copy(status = hint)
            return
        }
        File(cacheDir, "tts.wav").delete()
        output = null
        ui = ui.copy(
            busy = true,
            canPlay = false,
            inferenceStage = "",
            inferenceCurrent = 0,
            inferenceTotal = 0,
            inferenceRate = 0.0,
            status = if (state.referenceUri == null) getString(R.string.synthesizing)
            else getString(R.string.reference_decoding),
        )
        lifecycleScope.launch {
            runCatching {
                val options = SynthesisOptions(
                    state.temperature.toFloat(),
                    state.topP.toFloat(),
                    state.topK.toInt(),
                    state.penalty.toFloat(),
                    state.speed.toFloat(),
                    state.steps.toInt(),
                )
                val reference = state.referenceUri?.let { uri ->
                    withContext(Dispatchers.IO) {
                        ReferenceAudioDecoder.decode(
                            this@MainActivity,
                            uri,
                            state.referencePrompt,
                            state.referenceLanguage,
                            state.referenceExactPcm16kSamples,
                        )
                    }
                }
                withContext(Dispatchers.Default) {
                    synchronized(engineLock) {
                        engine.synthesize(
                            SynthesisRequest(
                                state.text.ifBlank { getString(R.string.default_sample_text) },
                                language = state.textLanguage,
                                options = options,
                                reference = reference,
                            ),
                            File(cacheDir, "tts.wav"),
                        )
                    }
                }
            }.onSuccess {
                output = it
                InferenceProgressBus.clear()
                ui = ui.copy(busy = false, canPlay = true, status = getString(R.string.synthesis_complete))
            }.onFailure { error ->
                InferenceProgressBus.clear()
                android.util.Log.e("MainActivity", "synthesize failed", error)
                val message = if (error is ReferenceDurationMismatch) {
                    getString(
                        R.string.reference_exact_duration_mismatch,
                        error.expectedSeconds,
                        error.actualSeconds,
                    )
                } else {
                    getString(R.string.synthesis_failed, error.message.orEmpty())
                }
                ui = ui.copy(busy = false, status = message)
            }
        }
    }

    private fun play() {
        output?.let { file ->
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.path)
                prepare()
                start()
            }
        }
    }

    private fun save() {
        if (output?.isFile != true) {
            ui = ui.copy(status = getString(R.string.audio_output_missing))
            return
        }
        saveOutput.launch("gsv-${System.currentTimeMillis()}.wav")
    }

    private fun startApiServer() {
        val port = ui.port.toIntOrNull()
        if (port == null || port !in 1024..65535) {
            ui = ui.copy(serverEnabled = false, serverStatus = getString(R.string.api_port_invalid))
            return
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingApiPort = port
            ui = ui.copy(serverEnabled = true, serverStatus = getString(R.string.api_starting))
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startApiService(port)
    }

    private fun startApiService(port: Int) {
        val receiver = object : ResultReceiver(Handler(mainLooper)) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                when (resultCode) {
                    LocalOpenAiService.RESULT_STARTED -> {
                        val endpoint = resultData?.getString(LocalOpenAiService.EXTRA_ENDPOINT).orEmpty()
                        ui = ui.copy(
                            serverEnabled = true,
                            serverStatus = "$endpoint · POST /v1/audio/speech",
                        )
                    }
                    LocalOpenAiService.RESULT_ERROR -> {
                        val error = resultData?.getString(LocalOpenAiService.EXTRA_ERROR).orEmpty()
                        ui = ui.copy(
                            serverEnabled = false,
                            serverStatus = getString(R.string.api_start_failed, error),
                        )
                    }
                }
            }
        }
        ui = ui.copy(serverEnabled = true, serverStatus = getString(R.string.api_starting))
        runCatching {
            ContextCompat.startForegroundService(
                this,
                LocalOpenAiService.startIntent(this, port, receiver),
            )
        }.onFailure {
            ui = ui.copy(
                serverEnabled = false,
                serverStatus = getString(R.string.api_start_failed, it.message.orEmpty()),
            )
        }
    }

    private fun stopApiServer() {
        stopService(LocalOpenAiService.stopIntent(this))
        GsvRuntime.apiEndpoint = null
        GsvRuntime.apiError = null
        ui = ui.copy(serverEnabled = false, serverStatus = getString(R.string.api_stopped))
    }

    private fun setBusy(status: String) {
        ui = ui.copy(busy = true, status = status)
    }

    private fun openWebsite(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun displayName(uri: Uri): String {
        val value = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        return value ?: uri.lastPathSegment ?: "reference audio"
    }

    private fun selectAppLanguage(language: AppLanguage) {
        if (ui.busy || ui.appLanguage == language) return
        AppLocale.set(this, language)
        recreate()
    }

    private fun restoreSynthesisState(state: Bundle?): UiState {
        if (state == null) return ui
        return ui.copy(
            text = state.getString(STATE_TEXT, ui.text),
            textLanguage = state.getString(STATE_TEXT_LANGUAGE, ui.textLanguage),
            temperature = state.getString(STATE_TEMPERATURE, ui.temperature),
            topP = state.getString(STATE_TOP_P, ui.topP),
            topK = state.getString(STATE_TOP_K, ui.topK),
            penalty = state.getString(STATE_PENALTY, ui.penalty),
            speed = state.getString(STATE_SPEED, ui.speed),
            steps = state.getString(STATE_STEPS, ui.steps),
            referenceUri = state.getString(STATE_REFERENCE_URI)?.takeIf(String::isNotBlank)?.let(Uri::parse),
            referenceName = state.getString(STATE_REFERENCE_NAME, ui.referenceName),
            referencePrompt = state.getString(STATE_REFERENCE_PROMPT, ui.referencePrompt),
            referenceLanguage = state.getString(STATE_REFERENCE_LANGUAGE, ui.referenceLanguage),
        )
    }

    @Composable
    private fun MainScreen() {
        GsvAppUi(
            state = ui,
            onStateChange = { ui = it },
            onSynthesize = ::synthesize,
            onPlay = ::play,
            onSave = ::save,
            onPickReference = { pickReference.launch(arrayOf("audio/*", "audio/wav", "audio/flac", "audio/mpeg")) },
            onPickModel = { pickModel.launch(packageTypes) },
            onScan = ::requestExternalModelScan,
            onLoadModel = ::loadModelRecord,
            onStartApi = ::startApiServer,
            onStopApi = ::stopApiServer,
            onSelectLanguage = ::selectAppLanguage,
            onOpenProject = { openWebsite(PROJECT_URL) },
            onOpenUpstream = { openWebsite(UPSTREAM_URL) },
        )
    }

    override fun onDestroy() {
        player?.release()
        InferenceProgressBus.listener = null
        GsvRuntime.releaseActivity(isFinishing)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_TEXT, ui.text)
        outState.putString(STATE_TEXT_LANGUAGE, ui.textLanguage)
        outState.putString(STATE_TEMPERATURE, ui.temperature)
        outState.putString(STATE_TOP_P, ui.topP)
        outState.putString(STATE_TOP_K, ui.topK)
        outState.putString(STATE_PENALTY, ui.penalty)
        outState.putString(STATE_SPEED, ui.speed)
        outState.putString(STATE_STEPS, ui.steps)
        outState.putString(STATE_REFERENCE_URI, ui.referenceUri?.toString())
        outState.putString(STATE_REFERENCE_NAME, ui.referenceName)
        outState.putString(STATE_REFERENCE_PROMPT, ui.referencePrompt)
        outState.putString(STATE_REFERENCE_LANGUAGE, ui.referenceLanguage)
        outState.putBoolean(STATE_CAN_PLAY, ui.canPlay && output?.isFile == true)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private val packageTypes = arrayOf("application/zip", "application/octet-stream", "*/*")
        private const val PROJECT_URL = "https://github.com/wgj88/GPT-SoViTs-android"
        private const val UPSTREAM_URL = "https://github.com/RVC-Boss/GPT-SoVITS"
        private const val STATE_TEXT = "synthesis.text"
        private const val STATE_TEXT_LANGUAGE = "synthesis.text_language"
        private const val STATE_TEMPERATURE = "synthesis.temperature"
        private const val STATE_TOP_P = "synthesis.top_p"
        private const val STATE_TOP_K = "synthesis.top_k"
        private const val STATE_PENALTY = "synthesis.penalty"
        private const val STATE_SPEED = "synthesis.speed"
        private const val STATE_STEPS = "synthesis.steps"
        private const val STATE_REFERENCE_URI = "synthesis.reference_uri"
        private const val STATE_REFERENCE_NAME = "synthesis.reference_name"
        private const val STATE_REFERENCE_PROMPT = "synthesis.reference_prompt"
        private const val STATE_REFERENCE_LANGUAGE = "synthesis.reference_language"
        private const val STATE_CAN_PLAY = "synthesis.can_play"
    }
}

internal data class UiState(
    val models: List<ModelRecord> = emptyList(),
    val modelInfo: String = "",
    val modelLoaded: Boolean = false,
    val backend: String = "",
    val status: String = "",
    val text: String = "",
    val textLanguage: String = "auto",
    val temperature: String = "1.0",
    val topP: String = "1.0",
    val topK: String = "10",
    val penalty: String = "1.35",
    val speed: String = "1.0",
    val steps: String = "32",
    val busy: Boolean = false,
    val canPlay: Boolean = false,
    val hasPresetSpeaker: Boolean = false,
    val referenceUri: Uri? = null,
    val referenceName: String = "",
    val referencePrompt: String = "",
    val referenceLanguage: String = "auto",
    val runtimeOptions: Set<String> = emptySet(),
    val referenceOverrideSupported: Boolean = false,
    val referenceExactPcm16kSamples: Int? = null,
    val port: String = "9880",
    val serverEnabled: Boolean = false,
    val serverStatus: String = "",
    val inferenceStage: String = "",
    val inferenceCurrent: Int = 0,
    val inferenceTotal: Int = 0,
    val inferenceRate: Double = 0.0,
    val appLanguage: AppLanguage = AppLanguage.CHINESE,
)

internal data class ModelRecord(
    val uri: String,
    val name: String,
    val version: String,
    val split: Boolean = false,
    val qnnUri: String? = null,
    val baseModelSha256: String? = null,
    val productVersion: String = "",
)

@Composable
private fun GsvTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF94DFC0), onPrimary = Color(0xFF063C2B),
            primaryContainer = Color(0xFF174D39), onPrimaryContainer = Color(0xFFD7F5E5),
            secondary = Color(0xFFB6D8CB), secondaryContainer = Color(0xFF293D37),
            onSecondaryContainer = Color(0xFFE2F3EA),
            tertiaryContainer = Color(0xFF33443C), onTertiaryContainer = Color(0xFFDCF3E6),
            background = Color(0xFF101916), onBackground = Color(0xFFE7F0EA),
            surface = Color(0xFF18221E), onSurface = Color(0xFFE7F0EA),
            surfaceVariant = Color(0xFF26352E), onSurfaceVariant = Color(0xFFB9CBC0),
            outline = Color(0xFF73897D),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF176B50), onPrimary = Color.White,
            primaryContainer = Color(0xFFD9F3E5), onPrimaryContainer = Color(0xFF154634),
            secondary = Color(0xFF426E60), secondaryContainer = Color(0xFFE4F1E9),
            onSecondaryContainer = Color(0xFF234B3C),
            tertiaryContainer = Color(0xFFEAF1EA), onTertiaryContainer = Color(0xFF284A39),
            background = Color(0xFFF5F8F4), onBackground = Color(0xFF17231B),
            surface = Color.White, onSurface = Color(0xFF17231B),
            surfaceVariant = Color(0xFFEAF0EA), onSurfaceVariant = Color(0xFF526459),
            outline = Color(0xFF84988A),
        )
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
