// JNI surface of nuvio_whisper.dll for com.nuvio.app.features.voice.WhisperNative.
//
// One whisper_context per loaded model, owned by the Kotlin side through an opaque handle. Calls on
// one handle must not overlap (WhisperTranscriber serialises them); different handles may.
// Audio arrives as 16 kHz mono float samples in [-1, 1]; nothing here touches the file system
// except the model load.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <mutex>
#include <cstdlib>
#include <string>
#include <vector>

#include "grammar-parser.h"
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

// The encoder normally runs over a full 30 s window (1500 frames, 50 per second) however short the
// clip, which is nearly all of a command's decode time: small.en took ~3.2 s for a one-word
// "pause". Voice control only ever sends short commands, so the window is cut to the clip plus a
// margin, but never below 384 frames (~7.7 s). The floor is measured, not a guess: on noisy,
// echoey command clips (small.en) a 200-frame window repeated the command ("Pause. Pause.",
// "Play. Play. Play.") and sometimes ran on for 8 s, while 384 matched the full window's text on
// every clip at ~0.7 s against ~3.1 s. Too short a window is what the model never saw in training.
int audioContextFor(whisper_context *ctx, size_t sampleCount) {
    // NUVIO_WHISPER_AUDIO_CTX=<frames> overrides it (0 = full window), for measuring.
    if (const char *forced = std::getenv("NUVIO_WHISPER_AUDIO_CTX")) return std::atoi(forced);
    constexpr int kFramesPerSecond = 50;
    constexpr int kMarginFrames = 2 * kFramesPerSecond;
    constexpr int kFloorFrames = 384;
    const int full = whisper_model_n_audio_ctx(ctx);
    const int clipFrames = static_cast<int>((sampleCount * kFramesPerSecond + WHISPER_SAMPLE_RATE - 1) / WHISPER_SAMPLE_RATE);
    int frames = clipFrames + kMarginFrames;
    if (frames < kFloorFrames) frames = kFloorFrames;
    frames = (frames + 31) / 32 * 32;  // whole blocks, as whisper.cpp's own examples pick it
    return frames >= full ? 0 : frames;  // 0 = the model's own full window
}

struct GrammarArgs {
    const whisper_grammar_element **rules;
    size_t count;
    size_t startRule;
};

struct Decoded {
    std::string text;
    float logprobSum = 0.0f;
    float logprobMin = 0.0f;
    int tokens = 0;
};

// One utterance through whisper, the same settings for both calls; with [grammar] the output is held
// to it and no temperature fallback runs (a constrained decode's low log-probabilities are the
// confidence the caller reads, not a reason to decode again).
bool decode(whisper_context *ctx, const std::vector<float> &pcm, const std::string &prompt, int threads,
            const GrammarArgs *grammar, Decoded &out) {
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
    params.audio_ctx = audioContextFor(ctx, pcm.size());
    // A command is a handful of words; a decode stuck repeating itself stops here instead of
    // running on (and through whisper's temperature fallbacks) for seconds.
    params.max_tokens = 48;
    if (grammar != nullptr) {
        params.grammar_rules = grammar->rules;
        params.n_grammar_rules = grammar->count;
        params.i_start_rule = grammar->startRule;
        params.grammar_penalty = 100.0f;
        params.temperature_inc = 0.0f;
    }

    if (whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size())) != 0) return false;
    const whisper_token eot = whisper_token_eot(ctx);
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        const char *segment = whisper_full_get_segment_text(ctx, i);
        if (segment != nullptr) out.text += segment;
        const int tokens = whisper_full_n_tokens(ctx, i);
        for (int j = 0; j < tokens; ++j) {
            const whisper_token_data token = whisper_full_get_token_data(ctx, i, j);
            if (token.id >= eot) continue;  // timestamps, start/end markers: not words
            out.logprobSum += token.plog;
            out.logprobMin = out.tokens == 0 ? token.plog : std::min(out.logprobMin, token.plog);
            out.tokens++;
        }
    }
    return true;
}

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
    Decoded decoded;
    if (!decode(ctx, pcm, prompt, threads, nullptr, decoded)) return nullptr;
    return fromUtf8(env, decoded.text);
}

// The same decode, its output held to [grammar] (GBNF, starting at [startRule]): only text the
// grammar allows can come out, so "go write one" cannot when "write" is in no command. Returns
// "<mean log-probability>\t<min log-probability>\t<text>", the confidence for telling a command
// from noise forced into one; null when the grammar does not parse or decoding failed.
JNIEXPORT jstring JNICALL Java_com_nuvio_app_features_voice_WhisperNative_nativeTranscribeWithGrammar(
    JNIEnv *env, jobject, jlong handle, jfloatArray samples, jstring initialPrompt, jint threads,
    jstring grammarText, jstring startRule) {
    whisper_context *ctx = context(handle);
    if (ctx == nullptr || samples == nullptr) return nullptr;
    const jsize count = env->GetArrayLength(samples);
    std::vector<float> pcm(static_cast<size_t>(count));
    env->GetFloatArrayRegion(samples, 0, count, pcm.data());
    const std::string prompt = toUtf8(env, initialPrompt);
    const std::string grammarSource = toUtf8(env, grammarText);
    const std::string rule = toUtf8(env, startRule);

    // Parsed once per distinct grammar: it only changes when the person's own phrases do.
    static std::mutex grammarLock;
    static std::string cachedSource;
    static grammar_parser::parse_state cachedGrammar;
    std::lock_guard<std::mutex> guard(grammarLock);
    if (grammarSource != cachedSource) {
        cachedGrammar = grammar_parser::parse(grammarSource.c_str());
        cachedSource = grammarSource;
    }
    if (cachedGrammar.rules.empty()) return nullptr;
    const auto start = cachedGrammar.symbol_ids.find(rule);
    if (start == cachedGrammar.symbol_ids.end()) return nullptr;
    auto rules = cachedGrammar.c_rules();

    Decoded decoded;
    GrammarArgs grammar{rules.data(), rules.size(), start->second};
    if (!decode(ctx, pcm, prompt, threads, &grammar, decoded)) return nullptr;
    const float mean = decoded.tokens > 0 ? decoded.logprobSum / static_cast<float>(decoded.tokens) : -100.0f;
    char head[64];
    std::snprintf(head, sizeof(head), "%.4f\t%.4f\t", mean, decoded.tokens > 0 ? decoded.logprobMin : -100.0f);
    return fromUtf8(env, std::string(head) + decoded.text);
}

JNIEXPORT void JNICALL Java_com_nuvio_app_features_voice_WhisperNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    if (whisper_context *ctx = context(handle)) whisper_free(ctx);
}

JNIEXPORT jstring JNICALL Java_com_nuvio_app_features_voice_WhisperNative_nativeSystemInfo(JNIEnv *env, jobject) {
    return fromUtf8(env, whisper_print_system_info());
}

}  // extern "C"
