package ai.gsv.mobile

import java.nio.ByteBuffer

/**
 * JNI bindings for in-process Hexagon HTP NPU resident DiT and Vocoder execution.
 */
object QnnV5TurboNative {
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("gsv_v5_npu")
            isLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w("GSV5_NPU", "libgsv_v5_npu.so not loaded: ${e.message}")
        }
    }

    val available: Boolean get() = isLoaded

    external fun nativeInit(ditDlcPath: String, vocoderDlcPath: String): Boolean

    external fun nativeDitStep(
        xBuf: ByteBuffer,
        tVal: Float,
        condBuf: ByteBuffer,
        ropeBuf: ByteBuffer,
        outVelBuf: ByteBuffer
    ): Boolean

    external fun nativeVocoderChunk(
        inMelBuf: ByteBuffer,
        outPcmBuf: ByteBuffer
    ): Boolean

    external fun nativeRelease()
}
