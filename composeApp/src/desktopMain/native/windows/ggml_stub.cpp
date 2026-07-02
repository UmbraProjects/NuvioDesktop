// Minimal stub for ggml.dll. ffmpeg's avfilter-11.dll links against libmpv-2.dll's own
// dependency chain and statically imports ggml_backend_load_all from ggml.dll to support its
// (never-enabled-by-Nuvio) whisper audio filter. The real ggml.dll + libwhisper-1.dll +
// ggml-cpu-*/vulkan/opencl/rpc backend set crashes on load — confirmed via a standalone
// LoadLibraryExW probe outside of Nuvio's own process entirely — with
// "GGML_ASSERT(prev != ggml_uncaught_exception) failed" inside ggml's own backend
// auto-discovery code, a bug in that build rather than in anything Nuvio owns. Since nothing in
// Nuvio ever builds an "-af whisper=..." filter string, avfilter-11.dll only needs this symbol
// to *exist* to satisfy its import table at load time — it is never actually called.
#include <windows.h>

extern "C" __declspec(dllexport) void ggml_backend_load_all() {
    // no-op
}

BOOL APIENTRY DllMain(HMODULE, DWORD, LPVOID) {
    return TRUE;
}
