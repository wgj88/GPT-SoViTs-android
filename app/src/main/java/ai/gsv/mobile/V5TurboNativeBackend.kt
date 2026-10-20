package ai.gsv.mobile

import android.util.Log
import org.json.JSONObject

/**
 * 100% In-Process Native ExecutionBackend for GPT-SoVITS V5 Turbo
 * running directly on Qualcomm Hexagon HTP NPU (V69 / V73 / V75 / V79 / V81).
 * Completely eliminates Python, Termux, and background daemon dependencies.
 */
class V5TurboNativeBackend : ExecutionBackend {

    override fun supports(model: ModelPackage): Boolean {
        if (model.version in setOf("v5turbo", "v5dev") &&
            model.executor in setOf("v5turbo-native-htp", "qnn-htp-v5turbo", "qnn-htp-v5", "v5turbo-native")
        ) {
            return QnnV5TurboNative.available
        }
        return false
    }

    override fun open(model: ModelPackage): ExecutionSession {
        require(QnnV5TurboNative.available) {
            "libgsv_v5_npu.so is not available on this device"
        }
        val target = QualcommTargetPolicy.current().target ?: QualcommTargetSoc.SNAPDRAGON_8_PLUS_GEN_1
        val descriptorFile = model.runtimeFile(
            if (model.backendArtifact.isNotBlank()) model.backendArtifact else "runtime/backend_descriptor.json"
        )
        val descriptor = if (descriptorFile.isFile) {
            JSONObject(descriptorFile.readText())
        } else {
            JSONObject().apply {
                put("dit_dlc", "runtime/dit.dlc")
                put("vocoder_dlc", "runtime/vocoder.dlc")
            }
        }
        Log.i(TAG, "Opening V5 Turbo Native NPU Session for ${model.name}")
        return QnnV5TurboRuntime.openArtifactSession(model, target, descriptor)
    }

    companion object {
        const val TAG = "V5TurboNative"
    }
}
