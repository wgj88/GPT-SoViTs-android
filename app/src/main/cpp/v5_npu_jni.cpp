#include <jni.h>
#include <QNN/QnnInterface.h>
#include <QNN/System/QnnSystemInterface.h>
#include <QNN/System/QnnSystemDlc.h>
#include <dlfcn.h>
#include <chrono>
#include <cstring>
#include <string>
#include <vector>
#include <android/log.h>

#define TAG "GSV5_NPU_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

static size_t elemCount(const uint32_t* dims, uint32_t rank) {
    size_t n = 1;
    for (uint32_t i = 0; i < rank; ++i) n *= dims[i];
    return n;
}

struct EngineContext {
    void* sysLib = nullptr;
    void* htpLib = nullptr;
    const QnnInterface_t* htpInterface = nullptr;
    const QnnSystemInterface_t* sysInterface = nullptr;

    Qnn_BackendHandle_t backend = nullptr;
    Qnn_DeviceHandle_t device = nullptr;
    Qnn_ContextHandle_t context = nullptr;

    // DiT
    QnnSystemDlc_Handle_t ditDlc = nullptr;
    Qnn_GraphHandle_t ditGraph = nullptr;
    std::vector<Qnn_Tensor_t> ditInputs;
    std::vector<Qnn_Tensor_t> ditOutputs;
    int idx_x = -1;
    int idx_time = -1;
    int idx_cond = -1;
    int idx_rope = -1;

    // Vocoder
    QnnSystemDlc_Handle_t vocoderDlc = nullptr;
    Qnn_GraphHandle_t vocoderGraph = nullptr;
    std::vector<Qnn_Tensor_t> vocoderInputs;
    std::vector<Qnn_Tensor_t> vocoderOutputs;

    bool initialized = false;
};

static EngineContext g_ctx;

void cleanupContext(EngineContext& ctx) {
    if (ctx.htpInterface) {
        const auto* api = &ctx.htpInterface->QNN_INTERFACE_VER_NAME;
        if (ctx.context && api->contextFree) api->contextFree(ctx.context, nullptr);
        if (ctx.device && api->deviceFree) api->deviceFree(ctx.device);
        if (ctx.backend && api->backendFree) api->backendFree(ctx.backend);
    }
    if (ctx.sysInterface) {
        const auto* sysApi = &ctx.sysInterface->QNN_SYSTEM_INTERFACE_VER_NAME;
        if (ctx.ditDlc && sysApi->systemDlcFree) sysApi->systemDlcFree(ctx.ditDlc);
        if (ctx.vocoderDlc && sysApi->systemDlcFree) sysApi->systemDlcFree(ctx.vocoderDlc);
    }
    if (ctx.htpLib) { dlclose(ctx.htpLib); ctx.htpLib = nullptr; }
    if (ctx.sysLib) { dlclose(ctx.sysLib); ctx.sysLib = nullptr; }
    ctx = EngineContext();
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_ai_gsv_mobile_QnnV5TurboNative_nativeInit(
    JNIEnv* env,
    jobject /* thiz */,
    jstring jDitDlcPath,
    jstring jVocoderDlcPath
) {
    if (g_ctx.initialized) {
        cleanupContext(g_ctx);
    }

    const char* ditPath = env->GetStringUTFChars(jDitDlcPath, nullptr);
    const char* vocoderPath = env->GetStringUTFChars(jVocoderDlcPath, nullptr);

    LOGI("Initializing QNN HTP for DiT=%s, Vocoder=%s", ditPath, vocoderPath);

    void* sysLib = dlopen("libQnnSystem.so", RTLD_NOW | RTLD_LOCAL);
    void* htpLib = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_LOCAL);
    if (!sysLib || !htpLib) {
        LOGE("Failed dlopen QNN libraries: sys=%p, htp=%p, dlerror=%s", sysLib, htpLib, dlerror());
        env->ReleaseStringUTFChars(jDitDlcPath, ditPath);
        env->ReleaseStringUTFChars(jVocoderDlcPath, vocoderPath);
        return JNI_FALSE;
    }
    g_ctx.sysLib = sysLib;
    g_ctx.htpLib = htpLib;

    using SysProvidersFn = Qnn_ErrorHandle_t (*)(const QnnSystemInterface_t***, uint32_t*);
    auto sysGet = reinterpret_cast<SysProvidersFn>(dlsym(sysLib, "QnnSystemInterface_getProviders"));
    using ProvidersFn = Qnn_ErrorHandle_t (*)(const QnnInterface_t***, uint32_t*);
    auto getProviders = reinterpret_cast<ProvidersFn>(dlsym(htpLib, "QnnInterface_getProviders"));
    if (!sysGet || !getProviders) {
        LOGE("Provider symbols missing in QNN libraries");
        cleanupContext(g_ctx);
        env->ReleaseStringUTFChars(jDitDlcPath, ditPath);
        env->ReleaseStringUTFChars(jVocoderDlcPath, vocoderPath);
        return JNI_FALSE;
    }

    const QnnSystemInterface_t** sysProviders = nullptr;
    uint32_t nSys = 0;
    auto rc = sysGet(&sysProviders, &nSys);
    if (rc || !nSys) {
        LOGE("QnnSystemInterface_getProviders failed rc=%lu", (unsigned long)rc);
        cleanupContext(g_ctx);
        env->ReleaseStringUTFChars(jDitDlcPath, ditPath);
        env->ReleaseStringUTFChars(jVocoderDlcPath, vocoderPath);
        return JNI_FALSE;
    }
    g_ctx.sysInterface = sysProviders[0];
    const auto* sysApi = &g_ctx.sysInterface->QNN_SYSTEM_INTERFACE_VER_NAME;

    const QnnInterface_t** providers = nullptr;
    uint32_t nProviders = 0;
    rc = getProviders(&providers, &nProviders);
    if (rc || !nProviders) {
        LOGE("QnnInterface_getProviders failed rc=%lu", (unsigned long)rc);
        cleanupContext(g_ctx);
        env->ReleaseStringUTFChars(jDitDlcPath, ditPath);
        env->ReleaseStringUTFChars(jVocoderDlcPath, vocoderPath);
        return JNI_FALSE;
    }

    const QnnInterface_t* selected = nullptr;
    for (uint32_t i = 0; i < nProviders; ++i) {
        if (providers[i]->providerName && std::string(providers[i]->providerName).find("HTP") != std::string::npos) {
            selected = providers[i];
            break;
        }
    }
    if (!selected) {
        LOGE("HTP provider not found among %u providers", nProviders);
        cleanupContext(g_ctx);
        env->ReleaseStringUTFChars(jDitDlcPath, ditPath);
        env->ReleaseStringUTFChars(jVocoderDlcPath, vocoderPath);
        return JNI_FALSE;
    }
    g_ctx.htpInterface = selected;
    const auto* api = &selected->QNN_INTERFACE_VER_NAME;

    // Backend, Device, Context
    rc = api->backendCreate(nullptr, nullptr, &g_ctx.backend);
    if (rc) { LOGE("backendCreate failed rc=%lu", (unsigned long)rc); cleanupContext(g_ctx); return JNI_FALSE; }

    if (api->deviceCreate) {
        rc = api->deviceCreate(nullptr, nullptr, &g_ctx.device);
        if (rc) { LOGE("deviceCreate failed rc=%lu", (unsigned long)rc); cleanupContext(g_ctx); return JNI_FALSE; }
    }

    rc = api->contextCreate(g_ctx.backend, g_ctx.device, nullptr, &g_ctx.context);
    if (rc) { LOGE("contextCreate failed rc=%lu", (unsigned long)rc); cleanupContext(g_ctx); return JNI_FALSE; }

    // 1. Compose DiT DLC
    rc = sysApi->systemDlcCreateFromFile(nullptr, ditPath, &g_ctx.ditDlc);
    if (rc) { LOGE("DiT DLC open failed rc=%lu", (unsigned long)rc); cleanupContext(g_ctx); return JNI_FALSE; }

    QnnSystemContext_GraphInfo_t* ditGraphs = nullptr;
    uint32_t numDitGraphs = 0;
    rc = sysApi->systemDlcComposeGraphs(g_ctx.ditDlc, nullptr, 0, g_ctx.backend, g_ctx.context, *selected,
                                        QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3, &ditGraphs, &numDitGraphs);
    if (rc || !numDitGraphs || !ditGraphs) {
        LOGE("DiT composeGraphs failed rc=%lu", (unsigned long)rc);
        cleanupContext(g_ctx);
        return JNI_FALSE;
    }

    const auto& ditGi = ditGraphs[0].graphInfoV3;
    rc = api->graphRetrieve(g_ctx.context, ditGi.graphName, &g_ctx.ditGraph);
    if (rc) { LOGE("DiT graphRetrieve failed rc=%lu", (unsigned long)rc); cleanupContext(g_ctx); return JNI_FALSE; }

    rc = api->graphFinalize(g_ctx.ditGraph, nullptr, nullptr);
    if (rc) { LOGE("DiT graphFinalize failed rc=%lu", (unsigned long)rc); cleanupContext(g_ctx); return JNI_FALSE; }

    g_ctx.ditInputs.resize(ditGi.numGraphInputs);
    for (uint32_t i = 0; i < ditGi.numGraphInputs; ++i) {
        g_ctx.ditInputs[i] = ditGi.graphInputs[i];
        g_ctx.ditInputs[i].v1.memType = QNN_TENSORMEMTYPE_RAW;
        std::string name = g_ctx.ditInputs[i].v1.name ? g_ctx.ditInputs[i].v1.name : "";
        if (name == "x") g_ctx.idx_x = i;
        else if (name == "time") g_ctx.idx_time = i;
        else if (name == "condition") g_ctx.idx_cond = i;
        else if (name == "rope_freqs") g_ctx.idx_rope = i;
    }

    g_ctx.ditOutputs.resize(ditGi.numGraphOutputs);
    for (uint32_t i = 0; i < ditGi.numGraphOutputs; ++i) {
        g_ctx.ditOutputs[i] = ditGi.graphOutputs[i];
        g_ctx.ditOutputs[i].v1.memType = QNN_TENSORMEMTYPE_RAW;
    }

    // 2. Compose Vocoder DLC
    rc = sysApi->systemDlcCreateFromFile(nullptr, vocoderPath, &g_ctx.vocoderDlc);
    if (rc) { LOGE("Vocoder DLC open failed rc=%lu", (unsigned long)rc); cleanupContext(g_ctx); return JNI_FALSE; }

    QnnSystemContext_GraphInfo_t* vocGraphs = nullptr;
    uint32_t numVocGraphs = 0;
    rc = sysApi->systemDlcComposeGraphs(g_ctx.vocoderDlc, nullptr, 0, g_ctx.backend, g_ctx.context, *selected,
                                        QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3, &vocGraphs, &numVocGraphs);
    if (rc || !numVocGraphs || !vocGraphs) {
        LOGE("Vocoder composeGraphs failed rc=%lu", (unsigned long)rc);
        cleanupContext(g_ctx);
        return JNI_FALSE;
    }

    const auto& vocGi = vocGraphs[0].graphInfoV3;
    rc = api->graphRetrieve(g_ctx.context, vocGi.graphName, &g_ctx.vocoderGraph);
    if (rc) { LOGE("Vocoder graphRetrieve failed rc=%lu", (unsigned long)rc); cleanupContext(g_ctx); return JNI_FALSE; }

    rc = api->graphFinalize(g_ctx.vocoderGraph, nullptr, nullptr);
    if (rc) { LOGE("Vocoder graphFinalize failed rc=%lu", (unsigned long)rc); cleanupContext(g_ctx); return JNI_FALSE; }

    g_ctx.vocoderInputs.resize(vocGi.numGraphInputs);
    for (uint32_t i = 0; i < vocGi.numGraphInputs; ++i) {
        g_ctx.vocoderInputs[i] = vocGi.graphInputs[i];
        g_ctx.vocoderInputs[i].v1.memType = QNN_TENSORMEMTYPE_RAW;
    }
    g_ctx.vocoderOutputs.resize(vocGi.numGraphOutputs);
    for (uint32_t i = 0; i < vocGi.numGraphOutputs; ++i) {
        g_ctx.vocoderOutputs[i] = vocGi.graphOutputs[i];
        g_ctx.vocoderOutputs[i].v1.memType = QNN_TENSORMEMTYPE_RAW;
    }

    env->ReleaseStringUTFChars(jDitDlcPath, ditPath);
    env->ReleaseStringUTFChars(jVocoderDlcPath, vocoderPath);

    g_ctx.initialized = true;
    LOGI("QNN HTP Context fully initialized and finalized in resident DSP memory!");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_ai_gsv_mobile_QnnV5TurboNative_nativeDitStep(
    JNIEnv* env,
    jobject /* thiz */,
    jobject jXBuf,
    jfloat jTime,
    jobject jCondBuf,
    jobject jRopeBuf,
    jobject jOutVelBuf
) {
    if (!g_ctx.initialized || !g_ctx.ditGraph) return JNI_FALSE;

    void* xData = env->GetDirectBufferAddress(jXBuf);
    void* condData = env->GetDirectBufferAddress(jCondBuf);
    void* ropeData = env->GetDirectBufferAddress(jRopeBuf);
    void* outVelData = env->GetDirectBufferAddress(jOutVelBuf);

    if (!xData || !condData || !ropeData || !outVelData) {
        LOGE("Invalid DirectByteBuffer pointers passed to nativeDitStep");
        return JNI_FALSE;
    }

    float tVal = jTime;

    g_ctx.ditInputs[g_ctx.idx_x].v1.clientBuf.data = xData;
    g_ctx.ditInputs[g_ctx.idx_x].v1.clientBuf.dataSize = 100 * 1000 * sizeof(float);

    g_ctx.ditInputs[g_ctx.idx_time].v1.clientBuf.data = &tVal;
    g_ctx.ditInputs[g_ctx.idx_time].v1.clientBuf.dataSize = 1 * sizeof(float);

    g_ctx.ditInputs[g_ctx.idx_cond].v1.clientBuf.data = condData;
    g_ctx.ditInputs[g_ctx.idx_cond].v1.clientBuf.dataSize = 1024 * 1000 * sizeof(float);

    g_ctx.ditInputs[g_ctx.idx_rope].v1.clientBuf.data = ropeData;
    g_ctx.ditInputs[g_ctx.idx_rope].v1.clientBuf.dataSize = 64 * 1000 * sizeof(float);

    g_ctx.ditOutputs[0].v1.clientBuf.data = outVelData;
    g_ctx.ditOutputs[0].v1.clientBuf.dataSize = 100 * 1000 * sizeof(float);

    const auto* api = &g_ctx.htpInterface->QNN_INTERFACE_VER_NAME;
    auto rc = api->graphExecute(g_ctx.ditGraph,
                                g_ctx.ditInputs.data(), g_ctx.ditInputs.size(),
                                g_ctx.ditOutputs.data(), g_ctx.ditOutputs.size(),
                                nullptr, nullptr);
    if (rc) {
        LOGE("DiT graphExecute failed rc=%lu", (unsigned long)rc);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_ai_gsv_mobile_QnnV5TurboNative_nativeVocoderChunk(
    JNIEnv* env,
    jobject /* thiz */,
    jobject jInMelBuf,
    jobject jOutPcmBuf
) {
    if (!g_ctx.initialized || !g_ctx.vocoderGraph) return JNI_FALSE;

    void* melData = env->GetDirectBufferAddress(jInMelBuf);
    void* pcmData = env->GetDirectBufferAddress(jOutPcmBuf);
    if (!melData || !pcmData) {
        LOGE("Invalid DirectByteBuffer pointers passed to nativeVocoderChunk");
        return JNI_FALSE;
    }

    g_ctx.vocoderInputs[0].v1.clientBuf.data = melData;
    g_ctx.vocoderInputs[0].v1.clientBuf.dataSize = 100 * 100 * sizeof(float);

    g_ctx.vocoderOutputs[0].v1.clientBuf.data = pcmData;
    g_ctx.vocoderOutputs[0].v1.clientBuf.dataSize = 48000 * sizeof(float);

    const auto* api = &g_ctx.htpInterface->QNN_INTERFACE_VER_NAME;
    auto rc = api->graphExecute(g_ctx.vocoderGraph,
                                g_ctx.vocoderInputs.data(), g_ctx.vocoderInputs.size(),
                                g_ctx.vocoderOutputs.data(), g_ctx.vocoderOutputs.size(),
                                nullptr, nullptr);
    if (rc) {
        LOGE("Vocoder graphExecute failed rc=%lu", (unsigned long)rc);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_ai_gsv_mobile_QnnV5TurboNative_nativeRelease(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    LOGI("Releasing QNN HTP Context");
    cleanupContext(g_ctx);
}

} // extern "C"
