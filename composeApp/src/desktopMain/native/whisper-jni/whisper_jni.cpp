// JNI surface of nuvio_whisper.dll for com.nuvio.app.features.voice.WhisperNative.
//
// One whisper_context per loaded model, owned by the Kotlin side through an opaque handle. Calls on
// one handle must not overlap (WhisperTranscriber serialises them); different handles may.
// Audio arrives as 16 kHz mono float samples in [-1, 1]; nothing here touches the file system
// except the model load.

#include <jni.h>

#include <atomic>
#include <string>
#include <vector>

#include "whisper.h"

namespace {

// whisper.cpp and ggml log every model load and decode to stderr; the app's own log says what matters.
void quietLog(enum ggml_log_level, const char *, void *) {}

std::atomic<bool> g_logSilenced{false};

void silenceLogs() {
    bool expected = false;
    if (g_logSilenced.compare_exchange_strong(expected, true)) {
        whisper_log_set(quietLog, nullptr);
        ggml_log_set(quietLog, nullptr);
    }
}

std::string toUtf8(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

// NewStringUTF wants Modified UTF-8, which differs from the model's UTF-8 for supplementary
// characters and NUL; going through a byte array and String(bytes, UTF_8) is exact.
jstring fromUtf8(JNIEnv *env, const std::string &text) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID constructor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(stringClass, constructor, bytes, charset));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(stringClass);
    return result;
}

whisper_context *context(jlong handle) { return reinterpret_cast<whisper_context *>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_nuvio_app_features_voice_WhisperNative_nativeInit(JNIEnv *env, jobject, jstring modelPath) {
    silenceLogs();
    const std::string path = toUtf8(env, modelPath);
    if (path.empty()) return 0;
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    params.flash_attn = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), params);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL Java_com_nuvio_app_features_voice_WhisperNative_nativeTranscribe(
    JNIEnv *env, jobject, jlong handle, jfloatArray samples, jstring initialPrompt, jint threads) {
    whisper_context *ctx = context(handle);
    if (ctx == nullptr || samples == nullptr) return nullptr;
    const jsize count = env->GetArrayLength(samples);
    std::vector<float> pcm(static_cast<size_t>(count));
    env->GetFloatArrayRegion(samples, 0, count, pcm.data());
    const std::string prompt = toUtf8(env, initialPrompt);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 4;
    params.language = "en";
    params.translate = false;
    params.no_context = true;
    params.single_segment = true;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();

    if (whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size())) != 0) return nullptr;
    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        const char *segment = whisper_full_get_segment_text(ctx, i);
        if (segment != nullptr) text += segment;
    }
    return fromUtf8(env, text);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_voice_WhisperNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    if (whisper_context *ctx = context(handle)) whisper_free(ctx);
}

JNIEXPORT jstring JNICALL Java_com_nuvio_app_features_voice_WhisperNative_nativeSystemInfo(JNIEnv *env, jobject) {
    return fromUtf8(env, whisper_print_system_info());
}

}  // extern "C"
