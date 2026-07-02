// Minimal stub for libwhisper-1.dll — see ggml_stub.cpp for why this exists. These 19 symbols
// are exactly what avfilter-11.dll's whisper audio filter imports; Nuvio never invokes that
// filter, so none of these are ever actually called. They exist purely to satisfy the import
// table so LoadLibraryExW on libmpv-2.dll's dependency chain succeeds.
#include <windows.h>

extern "C" {

__declspec(dllexport) void *whisper_context_default_params() { return nullptr; }
__declspec(dllexport) void whisper_free(void *) {}
__declspec(dllexport) int whisper_full(void *, void *, const float *, int) { return -1; }
__declspec(dllexport) void *whisper_full_default_params(int) { return nullptr; }
__declspec(dllexport) int whisper_full_get_segment_speaker_turn_next(void *, int) { return 0; }
__declspec(dllexport) long long whisper_full_get_segment_t0(void *, int) { return 0; }
__declspec(dllexport) long long whisper_full_get_segment_t1(void *, int) { return 0; }
__declspec(dllexport) const char *whisper_full_get_segment_text(void *, int) { return ""; }
__declspec(dllexport) int whisper_full_n_segments(void *) { return 0; }
__declspec(dllexport) void *whisper_init_from_file_with_params(const char *, void *) { return nullptr; }
__declspec(dllexport) void whisper_log_set(void *, void *) {}
__declspec(dllexport) void *whisper_vad_default_context_params() { return nullptr; }
__declspec(dllexport) void *whisper_vad_default_params() { return nullptr; }
__declspec(dllexport) void whisper_vad_free(void *) {}
__declspec(dllexport) void whisper_vad_free_segments(void *) {}
__declspec(dllexport) void *whisper_vad_init_from_file_with_params(const char *, void *) { return nullptr; }
__declspec(dllexport) void *whisper_vad_segments_from_samples(void *, void *, const float *, int) { return nullptr; }
__declspec(dllexport) long long whisper_vad_segments_get_segment_t0(void *, int) { return 0; }
__declspec(dllexport) long long whisper_vad_segments_get_segment_t1(void *, int) { return 0; }
__declspec(dllexport) int whisper_vad_segments_n_segments(void *) { return 0; }

}

BOOL APIENTRY DllMain(HMODULE, DWORD, LPVOID) {
    return TRUE;
}
