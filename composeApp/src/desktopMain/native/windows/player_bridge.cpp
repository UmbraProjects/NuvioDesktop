#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <dwmapi.h>
#include <wrl.h>
#include <WebView2.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cwctype>
#include <deque>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

using Microsoft::WRL::Callback;
using Microsoft::WRL::ComPtr;

extern "C" {
typedef struct mpv_handle mpv_handle;

typedef enum mpv_format {
    MPV_FORMAT_NONE = 0,
    MPV_FORMAT_STRING = 1,
    MPV_FORMAT_OSD_STRING = 2,
    MPV_FORMAT_FLAG = 3,
    MPV_FORMAT_INT64 = 4,
    MPV_FORMAT_DOUBLE = 5,
} mpv_format;

typedef enum mpv_event_id {
    MPV_EVENT_NONE = 0,
    MPV_EVENT_SHUTDOWN = 1,
    MPV_EVENT_LOG_MESSAGE = 2,
    MPV_EVENT_FILE_LOADED = 8,
    MPV_EVENT_PLAYBACK_RESTART = 21,
    MPV_EVENT_PROPERTY_CHANGE = 22,
} mpv_event_id;

typedef struct mpv_event_property {
    const char *name;
    mpv_format format;
    void *data;
} mpv_event_property;

typedef struct mpv_event_log_message {
    const char *prefix;
    const char *level;
    const char *text;
    int log_level;
} mpv_event_log_message;

typedef struct mpv_event {
    mpv_event_id event_id;
    int error;
    uint64_t reply_userdata;
    void *data;
} mpv_event;
}

namespace {

HMODULE gModule = nullptr;
constexpr UINT WM_NUVIO_TASK = WM_APP + 0x4E50;
constexpr UINT_PTR NUVIO_TIMER_ID = 0x4E50;

// Diagnostic mpv log -> %TEMP%\nuvio-mpv.log. Normal playback writes only bridge lifecycle
// markers and mpv warnings/errors; full verbose mpv output requires RTX VSR/HDR to be active
// or the NUVIO_MPV_VERBOSE environment variable (see the requestLogMessages call in startMpv).
std::string nuvioMpvLogPath() {
    char tempPath[MAX_PATH];
    DWORD len = GetTempPathA(MAX_PATH, tempPath);
    if (len == 0 || len > MAX_PATH) return std::string();
    return std::string(tempPath) + "nuvio-mpv.log";
}

void nuvioMpvLogReset() {
    const std::string path = nuvioMpvLogPath();
    if (path.empty()) return;
    FILE *f = nullptr;
    if (fopen_s(&f, path.c_str(), "w") == 0 && f) fclose(f);
}

void nuvioMpvLogAppend(const std::string &line) {
    const std::string path = nuvioMpvLogPath();
    if (path.empty()) return;
    FILE *f = nullptr;
    if (fopen_s(&f, path.c_str(), "a") == 0 && f) {
        fwrite(line.data(), 1, line.size(), f);
        fclose(f);
    }
}

// Wall-clock HH:MM:SS.mmm so bridge markers can be correlated with the app's Kotlin-side
// logs (PlaybackStartTrace et al.) when breaking down playback start time.
std::string nuvioLogTimestamp() {
    SYSTEMTIME st = {};
    GetLocalTime(&st);
    char buffer[16];
    std::snprintf(buffer, sizeof(buffer), "%02u:%02u:%02u.%03u",
                  (unsigned)st.wHour, (unsigned)st.wMinute, (unsigned)st.wSecond, (unsigned)st.wMilliseconds);
    return std::string(buffer);
}

void nuvioBridgeLog(const std::string &line) {
    nuvioMpvLogAppend("[nuvio-bridge " + nuvioLogTimestamp() + "] " + line + "\n");
}

std::string redactedSourceSummary(const std::string &sourceUrl) {
    if (sourceUrl.empty()) return "(empty)";
    // IMDB ids are "tt" followed by digits; a bare find("tt") matched the "tt" inside
    // "https://" and reduced every URL to the summary "ttps:".
    size_t idPos = std::string::npos;
    for (size_t pos = sourceUrl.find("tt"); pos != std::string::npos; pos = sourceUrl.find("tt", pos + 1)) {
        if (pos + 2 < sourceUrl.size() && std::isdigit((unsigned char)sourceUrl[pos + 2])) {
            idPos = pos;
            break;
        }
    }
    if (idPos != std::string::npos) {
        size_t end = idPos;
        while (end < sourceUrl.size() && (std::isalnum((unsigned char)sourceUrl[end]) || sourceUrl[end] == ':' || sourceUrl[end] == '-')) {
            ++end;
        }
        return sourceUrl.substr(idPos, end - idPos);
    }
    const size_t lastSlash = sourceUrl.find_last_of("/\\");
    std::string tail = lastSlash == std::string::npos ? sourceUrl : sourceUrl.substr(lastSlash + 1);
    const size_t query = tail.find('?');
    if (query != std::string::npos) tail.resize(query);
    if (tail.size() > 96) tail.resize(96);
    return tail.empty() ? "(unknown)" : tail;
}
const wchar_t *kMessageWindowClass = L"NuvioPlayerBridgeMessageWindow";
const wchar_t *kContainerWindowClass = L"NuvioPlayerBridgeContainerWindow";
constexpr DWORD kDwmwaUseImmersiveDarkMode = 20;
constexpr DWORD kDwmwaUseImmersiveDarkModeLegacy = 19;
constexpr DWORD kDwmwaBorderColor = 34;
constexpr DWORD kDwmwaCaptionColor = 35;
constexpr DWORD kDwmwaTextColor = 36;

std::wstring toWide(const std::string &value) {
    if (value.empty()) return std::wstring();
    int size = MultiByteToWideChar(CP_UTF8, 0, value.data(), (int)value.size(), nullptr, 0);
    if (size <= 0) return std::wstring();
    std::wstring result((size_t)size, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, value.data(), (int)value.size(), result.data(), size);
    return result;
}

std::string toUtf8(const std::wstring &value) {
    if (value.empty()) return std::string();
    int size = WideCharToMultiByte(CP_UTF8, 0, value.data(), (int)value.size(), nullptr, 0, nullptr, nullptr);
    if (size <= 0) return std::string();
    std::string result((size_t)size, '\0');
    WideCharToMultiByte(CP_UTF8, 0, value.data(), (int)value.size(), result.data(), size, nullptr, nullptr);
    return result;
}

std::string jstringToUtf8(JNIEnv *env, jstring value) {
    if (!value) return std::string();
    jsize length = env->GetStringLength(value);
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (!chars) return std::string();
    std::wstring wide(reinterpret_cast<const wchar_t *>(chars), (size_t)length);
    env->ReleaseStringChars(value, chars);
    return toUtf8(wide);
}

jstring newJavaStringUtf8(JNIEnv *env, const std::string &value) {
    std::wstring wide = toWide(value);
    return env->NewString(reinterpret_cast<const jchar *>(wide.data()), (jsize)wide.size());
}

std::vector<std::string> jstringArrayToVector(JNIEnv *env, jobjectArray values) {
    std::vector<std::string> result;
    if (!values) return result;
    jsize count = env->GetArrayLength(values);
    result.reserve((size_t)count);
    for (jsize index = 0; index < count; index++) {
        jstring item = (jstring)env->GetObjectArrayElement(values, index);
        std::string value = jstringToUtf8(env, item);
        if (!value.empty()) {
            result.push_back(value);
        }
        env->DeleteLocalRef(item);
    }
    return result;
}

void throwJavaError(JNIEnv *env, const std::string &message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (exceptionClass) {
        env->ThrowNew(exceptionClass, message.c_str());
    }
}

std::string trim(const std::string &value) {
    const char *spaces = " \t\r\n";
    size_t start = value.find_first_not_of(spaces);
    if (start == std::string::npos) return std::string();
    size_t end = value.find_last_not_of(spaces);
    return value.substr(start, end - start + 1);
}

std::string lowerCopy(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char ch) {
        return (char)std::tolower(ch);
    });
    return value;
}

COLORREF rgbIntToColorRef(jint rgb) {
    BYTE red = (BYTE)((rgb >> 16) & 0xFF);
    BYTE green = (BYTE)((rgb >> 8) & 0xFF);
    BYTE blue = (BYTE)(rgb & 0xFF);
    return RGB(red, green, blue);
}

void setDwmWindowAttribute(HWND hwnd, DWORD attribute, const void *value, DWORD valueSize) {
    (void)DwmSetWindowAttribute(hwnd, attribute, value, valueSize);
}

struct SavedWindowPlacement {
    bool valid = false;
    LONG_PTR style = 0;
    RECT rect{};
};

SavedWindowPlacement g_borderlessFullscreenSaved;

void setBorderlessFullscreen(HWND hwnd, bool enable) {
    if (!hwnd || !IsWindow(hwnd)) return;

    if (enable) {
        if (g_borderlessFullscreenSaved.valid) return;

        g_borderlessFullscreenSaved.style = GetWindowLongPtrW(hwnd, GWL_STYLE);
        GetWindowRect(hwnd, &g_borderlessFullscreenSaved.rect);
        g_borderlessFullscreenSaved.valid = true;

        HMONITOR monitor = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
        MONITORINFO monitorInfo{};
        monitorInfo.cbSize = sizeof(monitorInfo);
        if (!GetMonitorInfoW(monitor, &monitorInfo)) {
            g_borderlessFullscreenSaved.valid = false;
            return;
        }

        LONG_PTR newStyle = g_borderlessFullscreenSaved.style & ~(WS_CAPTION | WS_THICKFRAME | WS_MINIMIZEBOX | WS_MAXIMIZEBOX | WS_SYSMENU);
        SetWindowLongPtrW(hwnd, GWL_STYLE, newStyle);

        const RECT &monitorRect = monitorInfo.rcMonitor;
        SetWindowPos(
            hwnd,
            HWND_TOP,
            monitorRect.left,
            monitorRect.top,
            monitorRect.right - monitorRect.left,
            monitorRect.bottom - monitorRect.top,
            SWP_NOOWNERZORDER | SWP_FRAMECHANGED | SWP_ASYNCWINDOWPOS
        );
    } else {
        if (!g_borderlessFullscreenSaved.valid) return;

        SetWindowLongPtrW(hwnd, GWL_STYLE, g_borderlessFullscreenSaved.style);
        const RECT &savedRect = g_borderlessFullscreenSaved.rect;
        SetWindowPos(
            hwnd,
            nullptr,
            savedRect.left,
            savedRect.top,
            savedRect.right - savedRect.left,
            savedRect.bottom - savedRect.top,
            SWP_NOZORDER | SWP_NOOWNERZORDER | SWP_FRAMECHANGED | SWP_ASYNCWINDOWPOS
        );
        g_borderlessFullscreenSaved.valid = false;
    }
}

void applyDwmWindowChrome(HWND hwnd, bool darkMode, COLORREF captionColor, COLORREF borderColor, COLORREF textColor) {
    if (!hwnd || !IsWindow(hwnd)) return;

    BOOL enabled = darkMode ? TRUE : FALSE;
    HRESULT darkModeResult = DwmSetWindowAttribute(
        hwnd,
        kDwmwaUseImmersiveDarkMode,
        &enabled,
        sizeof(enabled)
    );
    if (FAILED(darkModeResult)) {
        setDwmWindowAttribute(hwnd, kDwmwaUseImmersiveDarkModeLegacy, &enabled, sizeof(enabled));
    }

    setDwmWindowAttribute(hwnd, kDwmwaCaptionColor, &captionColor, sizeof(captionColor));
    setDwmWindowAttribute(hwnd, kDwmwaBorderColor, &borderColor, sizeof(borderColor));
    setDwmWindowAttribute(hwnd, kDwmwaTextColor, &textColor, sizeof(textColor));
}

bool containsCaseInsensitive(const std::string &haystack, const std::string &needle) {
    return lowerCopy(haystack).find(lowerCopy(needle)) != std::string::npos;
}

std::string jsonEscape(const std::string &value) {
    std::string result;
    result.reserve(value.size() + 8);
    for (unsigned char ch : value) {
        switch (ch) {
            case '\\': result += "\\\\"; break;
            case '"': result += "\\\""; break;
            case '\b': result += "\\b"; break;
            case '\f': result += "\\f"; break;
            case '\n': result += "\\n"; break;
            case '\r': result += "\\r"; break;
            case '\t': result += "\\t"; break;
            default:
                if (ch < 0x20) {
                    char buffer[8];
                    std::snprintf(buffer, sizeof(buffer), "\\u%04x", ch);
                    result += buffer;
                } else {
                    result.push_back((char)ch);
                }
        }
    }
    return result;
}

std::wstring javaScriptStringLiteral(const std::string &value) {
    std::string escaped;
    escaped.reserve(value.size() + 8);
    escaped.push_back('"');
    escaped += jsonEscape(value);
    escaped.push_back('"');
    return toWide(escaped);
}

std::wstring moduleDirectory() {
    wchar_t buffer[MAX_PATH] = {};
    DWORD length = GetModuleFileNameW(gModule, buffer, MAX_PATH);
    if (length == 0 || length >= MAX_PATH) return std::wstring();
    std::wstring path(buffer, buffer + length);
    size_t separator = path.find_last_of(L"\\/");
    if (separator == std::wstring::npos) return std::wstring();
    return path.substr(0, separator);
}

std::string modulePath(HMODULE handle) {
    if (!handle) return "(not loaded)";
    wchar_t buffer[32768] = {};
    DWORD length = GetModuleFileNameW(handle, buffer, (DWORD)(sizeof(buffer) / sizeof(buffer[0])));
    if (length == 0 || length >= (DWORD)(sizeof(buffer) / sizeof(buffer[0]))) {
        return "(loaded, path unavailable; GetLastError=" + std::to_string(GetLastError()) + ")";
    }
    return toUtf8(std::wstring(buffer, buffer + length));
}

bool fileExists(const std::wstring &path) {
    DWORD attributes = GetFileAttributesW(path.c_str());
    return attributes != INVALID_FILE_ATTRIBUTES && (attributes & FILE_ATTRIBUTE_DIRECTORY) == 0;
}

std::wstring moduleFilePath(const wchar_t *name) {
    std::wstring moduleDir = moduleDirectory();
    return moduleDir.empty() ? std::wstring() : moduleDir + L"\\" + name;
}

void configureBundledDllSearchPath() {
    static std::once_flag configureOnce;
    std::call_once(configureOnce, []() {
        std::wstring moduleDir = moduleDirectory();
        if (moduleDir.empty()) {
            nuvioMpvLogAppend("[nuvio] DLL search isolation skipped: moduleDirectory() empty\n");
            return;
        }

        HMODULE kernel32 = GetModuleHandleW(L"kernel32.dll");
        using SetDefaultDllDirectoriesFn = BOOL(WINAPI *)(DWORD);
        using AddDllDirectoryFn = DLL_DIRECTORY_COOKIE(WINAPI *)(PCWSTR);
        auto setDefaultDllDirectories = kernel32
            ? reinterpret_cast<SetDefaultDllDirectoriesFn>(GetProcAddress(kernel32, "SetDefaultDllDirectories"))
            : nullptr;
        auto addDllDirectory = kernel32
            ? reinterpret_cast<AddDllDirectoryFn>(GetProcAddress(kernel32, "AddDllDirectory"))
            : nullptr;

        BOOL defaultDirsOk = FALSE;
        DLL_DIRECTORY_COOKIE cookie = nullptr;
        if (setDefaultDllDirectories && addDllDirectory) {
            defaultDirsOk = setDefaultDllDirectories(
                LOAD_LIBRARY_SEARCH_APPLICATION_DIR |
                LOAD_LIBRARY_SEARCH_SYSTEM32 |
                LOAD_LIBRARY_SEARCH_USER_DIRS
            );
            if (defaultDirsOk) {
                cookie = addDllDirectory(moduleDir.c_str());
            }
        }

        // SetDllDirectory is kept as a fallback for older systems and for libraries that still
        // use legacy LoadLibrary search semantics. SetDefaultDllDirectories above removes PATH
        // from the default DLL search path for future loads, which prevents an installed SVP 4
        // VapourSynth runtime from being mixed with Nuvio's bundled mpv/Python stack.
        BOOL setDirOk = SetDllDirectoryW(moduleDir.c_str());
        nuvioMpvLogAppend("[nuvio] DLL search isolation moduleDir=" + toUtf8(moduleDir) +
            " defaultDirs=" + std::to_string((int)defaultDirsOk) +
            " addDir=" + std::to_string(cookie != nullptr) +
            " setDllDirectory=" + std::to_string((int)setDirOk) + "\n");
    });
}

void preloadBundledDll(const wchar_t *name, bool required) {
    std::wstring path = moduleFilePath(name);
    std::string utf8Name = toUtf8(std::wstring(name));
    if (path.empty() || !fileExists(path)) {
        if (required) {
            nuvioMpvLogAppend("[nuvio] bundled DLL missing: " + utf8Name + "\n");
        }
        return;
    }
    if (HMODULE existing = GetModuleHandleW(name)) {
        nuvioMpvLogAppend("[nuvio] already loaded " + utf8Name + " from " + modulePath(existing) + "\n");
        return;
    }

    HMODULE handle = LoadLibraryExW(
        path.c_str(),
        nullptr,
        LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS
    );
    if (!handle) {
        nuvioMpvLogAppend("[nuvio] failed to preload " + utf8Name +
            " from " + toUtf8(path) +
            " GetLastError=" + std::to_string(GetLastError()) + "\n");
        return;
    }
    nuvioMpvLogAppend("[nuvio] preloaded " + utf8Name + " from " + modulePath(handle) + "\n");
}

void preloadBundledVapourSynthRuntime() {
    static std::once_flag preloadOnce;
    std::call_once(preloadOnce, []() {
        configureBundledDllSearchPath();
        preloadBundledDll(L"libpython3.14.dll", true);
        preloadBundledDll(L"libvapoursynth.dll", true);
        preloadBundledDll(L"vsscript.dll", true);
    });
}

// mpv's vf_vapoursynth filter loads Python (via vsscript.dll) lazily, only when a file with
// that filter actually opens - so this just needs to land before the first such attempt, not
// before mpv_create() specifically. Setting PYTHONHOME here (once per mpv instance, cheap,
// idempotent) points Python at the curated stdlib subset NativePlayerBridge.kt extracts to
// <installDir>/lib/python3.14 - kept as a harmless fallback for any code path that does honor
// PYTHONHOME, but a WinDbg trace confirmed the real crash path (mpv -> vsscript_init ->
// Py_InitializeEx) does NOT consult PYTHONHOME at all: it walks upward from Nuvio.exe's own
// directory looking for a bare lib/pythonX.Y/os.py, so <installDir>/lib is also where
// NativePlayerBridge.kt now actually extracts the stdlib to (see extractPythonLibIfNeeded).
void setPythonHomeEnvironmentVariable() {
    std::wstring dir = moduleDirectory();
    if (dir.empty()) {
        nuvioMpvLogAppend("[nuvio] setPythonHomeEnvironmentVariable: moduleDirectory() empty, skipping\n");
        return;
    }
    std::wstring pythonHome = dir;
    std::wstring pythonLibLandmark = dir + L"\\lib\\python3.14\\os.py";
    if (GetFileAttributesW(pythonLibLandmark.c_str()) == INVALID_FILE_ATTRIBUTES) {
        nuvioMpvLogAppend("[nuvio] setPythonHomeEnvironmentVariable: " + toUtf8(pythonLibLandmark) +
            " does not exist (GetLastError=" + std::to_string(GetLastError()) + "), skipping\n");
        return;
    }
    BOOL setOk = SetEnvironmentVariableW(L"PYTHONHOME", pythonHome.c_str());
    wchar_t readback[4096] = {};
    DWORD readbackLen = GetEnvironmentVariableW(L"PYTHONHOME", readback, 4096);
    nuvioMpvLogAppend("[nuvio] setPythonHomeEnvironmentVariable: set=" + std::to_string((int)setOk) +
        " target=" + toUtf8(pythonHome) +
        " readback=" + (readbackLen > 0 ? toUtf8(std::wstring(readback, readback + readbackLen)) : std::string("(empty)")) + "\n");

    // Scrub any inherited PYTHONPATH. SVP 4 Pro (and other standalone VapourSynth/Python installs)
    // set a user/system PYTHONPATH pointing at their own plugins directory. Our bundled interpreter
    // reads it at Py_InitializeEx and pulls in that foreign, ABI-incompatible `vapoursynth` module
    // and plugins, which crashes Nuvio the first time an interpolated file opens. We locate our own
    // stdlib by the exe-relative directory walk (see comment above), not PYTHONPATH, so clearing it
    // costs us nothing and isolates the bundled interpreter. Confirmed fix from an SVP4 Pro user.
    wchar_t priorPythonPath[4096] = {};
    DWORD priorPythonPathLen = GetEnvironmentVariableW(L"PYTHONPATH", priorPythonPath, 4096);
    SetEnvironmentVariableW(L"PYTHONPATH", nullptr);
    nuvioMpvLogAppend("[nuvio] scrubbed inherited PYTHONPATH (was " +
        (priorPythonPathLen > 0
            ? toUtf8(std::wstring(priorPythonPath, priorPythonPath + priorPythonPathLen))
            : std::string("(unset)")) + ")\n");
}

std::wstring tempUserDataDirectory() {
    wchar_t tempPath[MAX_PATH] = {};
    DWORD length = GetTempPathW(MAX_PATH, tempPath);
    std::wstring result = length > 0 ? std::wstring(tempPath, tempPath + length) : L".\\";
    if (!result.empty() && result.back() != L'\\' && result.back() != L'/') {
        result.push_back(L'\\');
    }
    result += L"NuvioWebView2";
    CreateDirectoryW(result.c_str(), nullptr);
    return result;
}

std::string hresultMessage(const char *operation, HRESULT hr) {
    std::ostringstream builder;
    builder << operation << " failed: 0x" << std::hex << (unsigned long)hr;
    return builder.str();
}

struct MpvApi {
    using mpv_create_fn = mpv_handle *(*)();
    using mpv_initialize_fn = int (*)(mpv_handle *);
    using mpv_terminate_destroy_fn = void (*)(mpv_handle *);
    using mpv_set_option_fn = int (*)(mpv_handle *, const char *, mpv_format, void *);
    using mpv_set_option_string_fn = int (*)(mpv_handle *, const char *, const char *);
    using mpv_set_property_fn = int (*)(mpv_handle *, const char *, mpv_format, void *);
    using mpv_set_property_string_fn = int (*)(mpv_handle *, const char *, const char *);
    using mpv_get_property_fn = int (*)(mpv_handle *, const char *, mpv_format, void *);
    using mpv_command_fn = int (*)(mpv_handle *, const char **);
    using mpv_error_string_fn = const char *(*)(int);
    using mpv_free_fn = void (*)(void *);
    using mpv_wait_event_fn = mpv_event *(*)(mpv_handle *, double);
    using mpv_wakeup_fn = void (*)(mpv_handle *);
    using mpv_observe_property_fn = int (*)(mpv_handle *, uint64_t, const char *, mpv_format);
    using mpv_request_log_messages_fn = int (*)(mpv_handle *, const char *);

    HMODULE library = nullptr;
    std::once_flag loadOnce;
    std::string loadFailure;

    mpv_create_fn create = nullptr;
    mpv_initialize_fn initialize = nullptr;
    mpv_terminate_destroy_fn terminateDestroy = nullptr;
    mpv_set_option_fn setOption = nullptr;
    mpv_set_option_string_fn setOptionString = nullptr;
    mpv_set_property_fn setProperty = nullptr;
    mpv_set_property_string_fn setPropertyString = nullptr;
    mpv_get_property_fn getProperty = nullptr;
    mpv_command_fn command = nullptr;
    mpv_error_string_fn errorString = nullptr;
    mpv_free_fn freeValue = nullptr;
    mpv_wait_event_fn waitEvent = nullptr;
    mpv_wakeup_fn wakeup = nullptr;
    mpv_observe_property_fn observeProperty = nullptr;
    mpv_request_log_messages_fn requestLogMessages = nullptr;

    void ensureLoaded() {
        std::call_once(loadOnce, [this]() { load(); });
        if (!library) {
            throw std::runtime_error(loadFailure.empty() ? "Unable to load libmpv-2.dll." : loadFailure);
        }
    }

    std::string errorText(int error) {
        if (!errorString) return "unknown";
        const char *text = errorString(error);
        return text ? text : "unknown";
    }

    void load() {
        configureBundledDllSearchPath();

        std::vector<std::wstring> candidates;

        wchar_t envPath[32768] = {};
        DWORD envCapacity = (DWORD)(sizeof(envPath) / sizeof(envPath[0]));
        DWORD envLength = GetEnvironmentVariableW(L"NUVIO_LIBMPV_PATH", envPath, envCapacity);
        if (envLength > 0 && envLength < envCapacity) {
            candidates.emplace_back(envPath, envPath + envLength);
        }

        std::wstring moduleDir = moduleDirectory();
        if (!moduleDir.empty()) {
            candidates.push_back(moduleDir + L"\\libmpv-2.dll");
        }
        candidates.push_back(L"libmpv-2.dll");
        candidates.push_back(L"C:\\Program Files (x86)\\Nuvio\\app\\native\\libmpv-2.dll");
        // Deliberately no raw MSYS2 (C:\msys64\...) fallback here: a symbol-resolution mismatch
        // on the first successful candidate makes loadSymbol() FreeLibrary+throw, and because
        // that throw escapes the std::call_once callable, the *next* call to mpvApi() retries
        // this whole candidate list from scratch. On a dev machine with MSYS2 installed, that
        // retry used to reach a hardcoded C:\msys64\ucrt64\bin\libmpv-2.dll candidate and load a
        // second, conflicting copy of the entire mpv/ffmpeg dependency chain into the same
        // process (confirmed live in WinDbg) — corrupting shared global state badly enough to
        // crash Python's own `encodings` import moments later. Never reachable/correct for an
        // end-user install regardless of the underlying mechanism, so just don't offer it.

        // LoadLibraryExW on this ~130-DLL chain has been observed to fail transiently (confirmed
        // via nuvio-mpv.log: "Unable to load libmpv-2.dll" even for the one candidate — the app's
        // own bundled copy — that has loaded successfully dozens of times before and after). A
        // few quick retries absorb a brief disk/AV-scan lock without needing a fallback path to
        // somewhere else on the system (the previous MSYS2 fallback caused a worse failure mode
        // than just retrying does).
        constexpr int maxAttemptsPerCandidate = 3;
        constexpr auto retryDelay = std::chrono::milliseconds(250);
        for (const std::wstring &candidate : candidates) {
            for (int attempt = 0; attempt < maxAttemptsPerCandidate && !library; attempt++) {
                if (attempt > 0) std::this_thread::sleep_for(retryDelay);
                if (candidate.find(L'\\') != std::wstring::npos || candidate.find(L'/') != std::wstring::npos) {
                    library = LoadLibraryExW(
                        candidate.c_str(),
                        nullptr,
                        LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS
                    );
                } else {
                    library = LoadLibraryExW(candidate.c_str(), nullptr, LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
                }
            }
            if (library) break;
        }

        if (!library) {
            loadFailure = "Unable to load libmpv-2.dll. Bundle it under native/windows or set NUVIO_LIBMPV_PATH.";
            return;
        }

        create = loadSymbol<mpv_create_fn>("mpv_create");
        initialize = loadSymbol<mpv_initialize_fn>("mpv_initialize");
        terminateDestroy = loadSymbol<mpv_terminate_destroy_fn>("mpv_terminate_destroy");
        setOption = loadSymbol<mpv_set_option_fn>("mpv_set_option");
        setOptionString = loadSymbol<mpv_set_option_string_fn>("mpv_set_option_string");
        setProperty = loadSymbol<mpv_set_property_fn>("mpv_set_property");
        setPropertyString = loadSymbol<mpv_set_property_string_fn>("mpv_set_property_string");
        getProperty = loadSymbol<mpv_get_property_fn>("mpv_get_property");
        command = loadSymbol<mpv_command_fn>("mpv_command");
        errorString = loadSymbol<mpv_error_string_fn>("mpv_error_string");
        freeValue = loadSymbol<mpv_free_fn>("mpv_free");
        waitEvent = loadSymbol<mpv_wait_event_fn>("mpv_wait_event");
        wakeup = loadSymbol<mpv_wakeup_fn>("mpv_wakeup");
        observeProperty = loadSymbol<mpv_observe_property_fn>("mpv_observe_property");
        requestLogMessages = loadSymbol<mpv_request_log_messages_fn>("mpv_request_log_messages");
    }

    template <typename T>
    T loadSymbol(const char *name) {
        FARPROC symbol = GetProcAddress(library, name);
        if (!symbol) {
            loadFailure = std::string("libmpv-2.dll is missing export ") + name + ".";
            FreeLibrary(library);
            library = nullptr;
            throw std::runtime_error(loadFailure);
        }
        return reinterpret_cast<T>(symbol);
    }
};

MpvApi &mpvApi() {
    static MpvApi api;
    api.ensureLoaded();
    return api;
}

class WindowsMpvWebPlayer;
LRESULT CALLBACK messageWindowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam);
LRESULT CALLBACK containerWindowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam);

void registerWindowClasses() {
    static std::once_flag once;
    std::call_once(once, []() {
        WNDCLASSEXW messageClass = {};
        messageClass.cbSize = sizeof(messageClass);
        messageClass.lpfnWndProc = messageWindowProc;
        messageClass.hInstance = gModule;
        messageClass.lpszClassName = kMessageWindowClass;
        RegisterClassExW(&messageClass);

        WNDCLASSEXW containerClass = {};
        containerClass.cbSize = sizeof(containerClass);
        containerClass.lpfnWndProc = containerWindowProc;
        containerClass.hInstance = gModule;
        containerClass.hbrBackground = (HBRUSH)GetStockObject(BLACK_BRUSH);
        containerClass.lpszClassName = kContainerWindowClass;
        RegisterClassExW(&containerClass);
    });
}

std::mutex gWebView2WarmupMutex;
std::condition_variable gWebView2WarmupCv;
std::thread gWebView2WarmupThread;
DWORD gWebView2WarmupThreadId = 0;
bool gWebView2WarmupStarted = false;
bool gWebView2WarmupReady = false;
bool gWebView2WarmupSucceeded = false;

void notifyWebView2WarmupReady(bool succeeded) {
    {
        std::lock_guard<std::mutex> lock(gWebView2WarmupMutex);
        if (!gWebView2WarmupReady) {
            gWebView2WarmupReady = true;
            gWebView2WarmupSucceeded = succeeded;
        }
    }
    gWebView2WarmupCv.notify_all();
}

void runWebView2WarmupThread(std::string controlsUrl) {
    {
        std::lock_guard<std::mutex> lock(gWebView2WarmupMutex);
        gWebView2WarmupThreadId = GetCurrentThreadId();
    }
    gWebView2WarmupCv.notify_all();

    MSG queueProbe = {};
    PeekMessageW(&queueProbe, nullptr, WM_USER, WM_USER, PM_NOREMOVE);

    bool didOleInitialize = false;
    ComPtr<ICoreWebView2Environment> environment;
    ComPtr<ICoreWebView2Controller> controller;
    ComPtr<ICoreWebView2> webView;
    EventRegistrationToken messageToken = {};
    EventRegistrationToken navigationToken = {};

    HRESULT oleResult = OleInitialize(nullptr);
    didOleInitialize = SUCCEEDED(oleResult);
    if (FAILED(oleResult)) {
        notifyWebView2WarmupReady(false);
        return;
    }

    std::wstring userDataDir = tempUserDataDirectory();
    HRESULT envCallResult = CreateCoreWebView2EnvironmentWithOptions(
        nullptr,
        userDataDir.c_str(),
        nullptr,
        Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
            [&](HRESULT envResult, ICoreWebView2Environment *createdEnvironment) -> HRESULT {
                if (FAILED(envResult) || !createdEnvironment) {
                    notifyWebView2WarmupReady(false);
                    PostQuitMessage(0);
                    return S_OK;
                }

                environment = createdEnvironment;
                HRESULT controllerCallResult = createdEnvironment->CreateCoreWebView2Controller(
                    HWND_MESSAGE,
                    Callback<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(
                        [&](HRESULT controllerResult, ICoreWebView2Controller *createdController) -> HRESULT {
                            if (FAILED(controllerResult) || !createdController) {
                                notifyWebView2WarmupReady(false);
                                PostQuitMessage(0);
                                return S_OK;
                            }

                            controller = createdController;
                            controller->put_IsVisible(FALSE);
                            createdController->get_CoreWebView2(&webView);
                            if (!webView) {
                                notifyWebView2WarmupReady(false);
                                PostQuitMessage(0);
                                return S_OK;
                            }

                            webView->add_WebMessageReceived(
                                Callback<ICoreWebView2WebMessageReceivedEventHandler>(
                                    [&](ICoreWebView2 *, ICoreWebView2WebMessageReceivedEventArgs *args) -> HRESULT {
                                        if (!args) return S_OK;
                                        PWSTR messageJson = nullptr;
                                        if (SUCCEEDED(args->get_WebMessageAsJson(&messageJson)) && messageJson) {
                                            std::wstring message(messageJson);
                                            CoTaskMemFree(messageJson);
                                            if (message.find(L"controlsReady") != std::wstring::npos) {
                                                notifyWebView2WarmupReady(true);
                                            }
                                        }
                                        return S_OK;
                                    }
                                ).Get(),
                                &messageToken
                            );

                            webView->add_NavigationCompleted(
                                Callback<ICoreWebView2NavigationCompletedEventHandler>(
                                    [&](ICoreWebView2 *, ICoreWebView2NavigationCompletedEventArgs *args) -> HRESULT {
                                        BOOL navigationSucceeded = FALSE;
                                        if (args) {
                                            args->get_IsSuccess(&navigationSucceeded);
                                        }
                                        notifyWebView2WarmupReady(navigationSucceeded == TRUE);
                                        return S_OK;
                                    }
                                ).Get(),
                                &navigationToken
                            );

                            std::wstring url = toWide(controlsUrl);
                            HRESULT navigateResult = webView->Navigate(url.c_str());
                            if (FAILED(navigateResult)) {
                                notifyWebView2WarmupReady(false);
                                PostQuitMessage(0);
                            }
                            return S_OK;
                        }
                    ).Get()
                );
                if (FAILED(controllerCallResult)) {
                    notifyWebView2WarmupReady(false);
                    PostQuitMessage(0);
                }
                return S_OK;
            }
        ).Get()
    );
    if (FAILED(envCallResult)) {
        notifyWebView2WarmupReady(false);
    } else {
        MSG msg = {};
        while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }

    if (webView && messageToken.value != 0) {
        webView->remove_WebMessageReceived(messageToken);
    }
    if (webView && navigationToken.value != 0) {
        webView->remove_NavigationCompleted(navigationToken);
    }
    if (controller) {
        controller->Close();
        controller.Reset();
    }
    webView.Reset();
    environment.Reset();
    if (didOleInitialize) {
        OleUninitialize();
    }
}

bool startWebView2Warmup(const std::string &controlsUrl) {
    {
        std::lock_guard<std::mutex> lock(gWebView2WarmupMutex);
        if (!gWebView2WarmupStarted) {
            gWebView2WarmupStarted = true;
            gWebView2WarmupReady = false;
            gWebView2WarmupSucceeded = false;
            gWebView2WarmupThread = std::thread(runWebView2WarmupThread, controlsUrl);
        }
    }

    std::unique_lock<std::mutex> waitLock(gWebView2WarmupMutex);
    bool completed = gWebView2WarmupCv.wait_for(
        waitLock,
        std::chrono::seconds(5),
        []() { return gWebView2WarmupReady; }
    );
    if (!completed) return false;
    return gWebView2WarmupSucceeded;
}

void stopWebView2Warmup() {
    std::thread threadToJoin;
    DWORD threadId = 0;
    {
        std::unique_lock<std::mutex> lock(gWebView2WarmupMutex);
        if (!gWebView2WarmupStarted) return;
        gWebView2WarmupCv.wait_for(
            lock,
            std::chrono::seconds(1),
            []() { return gWebView2WarmupThreadId != 0; }
        );
        threadId = gWebView2WarmupThreadId;
    }

    if (threadId != 0) {
        PostThreadMessageW(threadId, WM_QUIT, 0, 0);
    }

    {
        std::lock_guard<std::mutex> lock(gWebView2WarmupMutex);
        if (gWebView2WarmupThread.joinable()) {
            threadToJoin = std::move(gWebView2WarmupThread);
        }
    }
    if (threadToJoin.joinable()) {
        threadToJoin.join();
    }

    {
        std::lock_guard<std::mutex> lock(gWebView2WarmupMutex);
        gWebView2WarmupStarted = false;
        gWebView2WarmupReady = false;
        gWebView2WarmupSucceeded = false;
        gWebView2WarmupThreadId = 0;
    }
}

class WindowsMpvWebPlayer : public std::enable_shared_from_this<WindowsMpvWebPlayer> {
    struct InitializationState {
        std::mutex mutex;
        std::condition_variable cv;
        bool complete = false;
        std::string failure;
    };

public:
    void initialize(
        HWND host,
        const std::string &sourceUrl,
        const std::string &audioUrl,
        const std::vector<std::string> &headerLines,
        bool playWhenReady,
        long long initialPositionMs,
        const std::string &controlsUrl,
        JavaVM *vm,
        bool nvidiaRtxSuperResolutionEnabled,
        bool nvidiaRtxHdrEnabled,
        const std::string &animeSvpFilter,
        const std::vector<std::string> &extraMpvOptionsIn,
        jobject sink,
        jmethodID method
    ) {
        if (!host || !IsWindow(host)) {
            throw std::runtime_error("Unable to resolve the AWT host HWND for native playback.");
        }

        javaVm = vm;
        eventSink = sink;
        eventMethod = method;
        hostHwnd = host;
        // Set before spawning the UI/mpv thread so it is visible there. When present
        // (e.g. a YouTube trailer with separate hi-res video + audio tracks) it is
        // attached via the audio-add command once the main file has loaded.
        externalAudioUrl = audioUrl;
        extraMpvOptions = extraMpvOptionsIn;

        nuvioMpvLogReset();
        nuvioBridgeLog(
            "initialize requested source=" + redactedSourceSummary(sourceUrl) +
            " audio=" + (audioUrl.empty() ? "no" : "yes") +
            " playWhenReady=" + (playWhenReady ? "yes" : "no") +
            " initialMs=" + std::to_string(initialPositionMs) +
            " rtxVsr=" + (nvidiaRtxSuperResolutionEnabled ? "yes" : "no") +
            " rtxHdr=" + (nvidiaRtxHdrEnabled ? "yes" : "no") +
            " animeSvp=" + (animeSvpFilter.empty() ? "no" : "yes")
        );

        auto initState = std::make_shared<InitializationState>();
        auto self = shared_from_this();
        nuvioBridgeLog("native ui thread starting");
        uiThread = std::thread(
            [self, sourceUrl, headerLines, playWhenReady, initialPositionMs, controlsUrl, nvidiaRtxSuperResolutionEnabled, nvidiaRtxHdrEnabled, animeSvpFilter, initState]() {
                self->runNativeUiThread(sourceUrl, headerLines, playWhenReady, initialPositionMs, controlsUrl, nvidiaRtxSuperResolutionEnabled, nvidiaRtxHdrEnabled, animeSvpFilter, initState);
            }
        );

        std::unique_lock<std::mutex> lock(initState->mutex);
        initState->cv.wait(lock, [&]() { return initState->complete; });
        if (!initState->failure.empty()) {
            lock.unlock();
            nuvioBridgeLog("initialize failed: " + initState->failure);
            if (uiThread.joinable()) {
                uiThread.join();
            }
            throw std::runtime_error(initState->failure);
        }
        nuvioBridgeLog("initialize complete");
    }

    void shutdown() {
        if (shuttingDown.exchange(true)) {
            return;
        }

        nuvioBridgeLog("shutdown begin");
        stopping.store(true);
        {
            std::lock_guard<std::mutex> lock(mpvMutex);
            if (mpv && mpvApi().wakeup) {
                nuvioBridgeLog("shutdown wake mpv");
                mpvApi().wakeup(mpv);
            }
        }

        nuvioBridgeLog("shutdown send ui cleanup");
        bool uiCleanupCompleted = sendUiTask([self = shared_from_this()]() {
            self->cleanupUiResources();
            PostQuitMessage(0);
        });
        nuvioBridgeLog(uiCleanupCompleted ? "shutdown ui cleanup returned" : "shutdown ui cleanup timed out");

        if (eventThread.joinable()) {
            nuvioBridgeLog("shutdown joining event thread");
            eventThread.join();
            nuvioBridgeLog("shutdown event thread joined");
        }
        {
            std::lock_guard<std::mutex> lock(mpvMutex);
            if (mpv) {
                nuvioBridgeLog("shutdown terminate mpv");
                mpvApi().terminateDestroy(mpv);
                mpv = nullptr;
                nuvioBridgeLog("shutdown mpv terminated");
            }
        }
        if (uiThread.joinable() && GetCurrentThreadId() != uiThreadId && uiCleanupCompleted) {
            nuvioBridgeLog("shutdown joining ui thread");
            uiThread.join();
            nuvioBridgeLog("shutdown ui thread joined");
        } else if (uiThread.joinable() && GetCurrentThreadId() != uiThreadId) {
            // If the native UI thread is wedged inside WebView/Win32 teardown, keep this
            // object alive and leak it rather than destroying state still owned by the thread.
            detachedLifetimeHold = shared_from_this();
            nuvioBridgeLog("shutdown detaching stuck ui thread");
            uiThread.detach();
        }

        if (eventSink) {
            bool didAttach = false;
            JNIEnv *env = jniEnvDidAttach(&didAttach);
            if (env) {
                env->DeleteGlobalRef(eventSink);
            }
            if (didAttach) {
                javaVm->DetachCurrentThread();
            }
            eventSink = nullptr;
        }
        eventMethod = nullptr;
        javaVm = nullptr;
        nuvioBridgeLog("shutdown complete");
    }

    void processUiTasks() {
        std::deque<std::function<void()>> tasks;
        {
            std::lock_guard<std::mutex> lock(uiTaskMutex);
            tasks.swap(uiTasks);
        }
        for (auto &task : tasks) {
            task();
        }
    }

    void onTimer() {
        if (shuttingDown.load()) return;
        layoutNativeSubviews();
        syncControls();
        if (subtitleAssOverrideInitialCheckPending.load() &&
            std::chrono::steady_clock::now() >= subtitleAssOverrideCheckDeadline) {
            subtitleAssOverrideInitialCheckPending = false;
            refreshSubtitleAssOverrideMode();
        }
    }

    void runJavaScript(const std::string &script) {
        postUiTask([self = shared_from_this(), script]() {
            if (!self->webView || !self->controlsWebReady.load()) return;
            std::wstring wideScript = toWide(script);
            self->webView->ExecuteScript(wideScript.c_str(), nullptr);
        });
    }

    void setCursorHidden(bool hidden) {
        postUiTask([self = shared_from_this(), hidden]() {
            if (self->cursorHidden == hidden) return;
            self->cursorHidden = hidden;
            ShowCursor(hidden ? FALSE : TRUE);
        });
    }

    void updateControlsJson(const std::string &controlsJson) {
        if (controlsJson.empty()) return;
        {
            std::lock_guard<std::mutex> lock(controlsMutex);
            pendingControlsJson = controlsJson;
        }
        postUiTask([self = shared_from_this()]() {
            self->flushPendingControlsJsonIfReady();
        });
    }

    void setPaused(bool paused) {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return;
        int flag = paused ? 1 : 0;
        mpvApi().setProperty(mpv, "pause", MPV_FORMAT_FLAG, &flag);
    }

    bool isPaused() {
        return flagProperty("pause", true);
    }

    // Keep seeks at least this far from the end. With keep-open=yes, landing
    // on (or past) EOF parks mpv on the last frame with eof-reached=true, which
    // the app reads as "ended" — so a scrub/seek that resolves near the end
    // would otherwise cut straight to the end of the file.
    static constexpr double seekEndGuardSeconds = 1.0;
    static constexpr double svpSpeedBypassThreshold = 1.50;
    static constexpr double svpSpeedRestoreThreshold = 1.25;

    // Initial streaming buffer (content-seconds). These only serve as the values in effect
    // between loadfile and the moment the Kotlin side applies the user's buffer preset (a few
    // milliseconds later on the main player path) — and for hero trailers, which never apply
    // a preset and keep them for their whole playback. All runtime buffer sizing, including
    // speed scaling, is owned by NativePlayerController.applyDesktopBufferPreset.
    static constexpr double baseReadaheadSecs = 180.0;
    static constexpr double baseCacheSecs = 600.0;

    static bool containsVapourSynthFilter(const std::string &filters) {
        return filters.find("vapoursynth") != std::string::npos;
    }

    static std::vector<std::string> splitVideoFilters(const std::string &filters) {
        std::vector<std::string> parts;
        std::string current;
        int bracketDepth = 0;
        for (char ch : filters) {
            if (ch == '[') {
                ++bracketDepth;
            } else if (ch == ']' && bracketDepth > 0) {
                --bracketDepth;
            }

            if (ch == ',' && bracketDepth == 0) {
                if (!current.empty()) parts.push_back(current);
                current.clear();
            } else {
                current.push_back(ch);
            }
        }
        if (!current.empty()) parts.push_back(current);
        return parts;
    }

    static std::string joinVideoFilters(const std::vector<std::string> &parts) {
        std::string out;
        for (const auto &part : parts) {
            if (part.empty()) continue;
            if (!out.empty()) out.push_back(',');
            out += part;
        }
        return out;
    }

    static std::string removeVapourSynthFilters(const std::string &filters) {
        std::vector<std::string> kept;
        for (const auto &part : splitVideoFilters(filters)) {
            if (!containsVapourSynthFilter(part)) kept.push_back(part);
        }
        return joinVideoFilters(kept);
    }

    void applySpeedSensitiveVideoFiltersLocked(double speed) {
        if (requestedVideoFilters.empty() || !containsVapourSynthFilter(requestedVideoFilters)) {
            svpBypassedForSpeed = false;
            return;
        }

        bool shouldBypass = speed >= svpSpeedBypassThreshold ||
            (svpBypassedForSpeed && speed > svpSpeedRestoreThreshold);
        std::string nextFilters = shouldBypass
            ? removeVapourSynthFilters(requestedVideoFilters)
            : requestedVideoFilters;

        setVideoFiltersPropertyLocked(nextFilters);
        if (shouldBypass != svpBypassedForSpeed) {
            svpBypassedForSpeed = shouldBypass;
            nuvioMpvLogAppend(std::string("[nuvio] anime SVP ") +
                (shouldBypass ? "bypassed" : "restored") +
                " for playback speed=" + std::to_string(speed) +
                " vf=" + nextFilters + "\n");
        }
    }

    double durationSecondsLocked() {
        double value = 0.0;
        if (mpvApi().getProperty(mpv, "duration", MPV_FORMAT_DOUBLE, &value) < 0) return 0.0;
        return std::isfinite(value) ? value : 0.0;
    }

    void setVideoFiltersPropertyLocked(const std::string &filters) {
        if (filters == appliedVideoFilters) {
            if (containsVapourSynthFilter(filters)) {
                nuvioMpvLogAppend("[nuvio] skipped duplicate vf containing vapoursynth\n");
            }
            return;
        }
        if (containsVapourSynthFilter(filters)) {
            preloadBundledVapourSynthRuntime();
        }
        int result = mpvApi().setPropertyString(mpv, "vf", filters.c_str());
        if (result >= 0) {
            appliedVideoFilters = filters;
        } else {
            nuvioMpvLogAppend("[nuvio] mpv property rejected: vf=" + filters +
                " (" + mpvApi().errorText(result) + ")\n");
        }
    }

    bool tryGetDoubleLocked(const char *name, double &out) {
        double value = 0.0;
        if (mpvApi().getProperty(mpv, name, MPV_FORMAT_DOUBLE, &value) < 0) return false;
        if (!std::isfinite(value)) return false;
        out = value;
        return true;
    }

    bool flagPropertyLocked(const char *name, bool fallback) {
        int flag = fallback ? 1 : 0;
        if (mpvApi().getProperty(mpv, name, MPV_FORMAT_FLAG, &flag) < 0) return fallback;
        return flag != 0;
    }

    double clampSeekSecondsLocked(double seconds) {
        double clamped = std::max(0.0, seconds);
        double duration = durationSecondsLocked();
        if (duration > seekEndGuardSeconds) {
            clamped = std::min(clamped, duration - seekEndGuardSeconds);
        }
        return clamped;
    }

    void issueSeekLocked(double targetSeconds) {
        std::string seconds = std::to_string(targetSeconds);
        const char *command[] = {"seek", seconds.c_str(), "absolute+keyframes", nullptr};
        if (mpvApi().command(mpv, command) >= 0) {
            pendingSeekTargetSeconds = targetSeconds;
            pendingSeekIssuedAt = std::chrono::steady_clock::now();
        }
    }

    void seekToMilliseconds(long long positionMs) {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return;
        issueSeekLocked(clampSeekSecondsLocked((double)positionMs / 1000.0));
    }

    void seekByMilliseconds(long long offsetMs) {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return;
        // Base relative seeks on the most recent seek target while a prior seek is still
        // settling. time-pos lags (or is briefly unavailable) until mpv finishes seeking,
        // so reading it raw makes rapid repeated seeks collapse into a single step — and
        // when the property read fails outright, "current position" used to become 0 and
        // playback jumped to the start of the file.
        bool seeking = flagPropertyLocked("seeking", false);
        bool pendingFresh = pendingSeekTargetSeconds >= 0.0 &&
            (std::chrono::steady_clock::now() - pendingSeekIssuedAt) < std::chrono::milliseconds(800);
        double base;
        double timePos = 0.0;
        if (pendingSeekTargetSeconds >= 0.0 && (seeking || pendingFresh)) {
            base = pendingSeekTargetSeconds;
        } else if (tryGetDoubleLocked("time-pos", timePos)) {
            base = std::max(0.0, timePos);
        } else if (pendingSeekTargetSeconds >= 0.0) {
            base = pendingSeekTargetSeconds;
        } else {
            // No playable position yet; dropping the seek beats jumping to 0:00.
            return;
        }
        issueSeekLocked(clampSeekSecondsLocked(base + (double)offsetMs / 1000.0));
    }

    void setSpeed(double speed) {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return;
        double clamped = std::max(0.25, std::min(4.0, speed));
        mpvApi().setProperty(mpv, "speed", MPV_FORMAT_DOUBLE, &clamped);
        // Buffer sizing (demuxer-readahead-secs / cache-secs / cache-pause-wait) is owned by
        // the Kotlin side: NativePlayerController re-applies the user's buffer preset scaled
        // by the new rate on every speed change. Scaling it here too from the init-time
        // constants silently fought the preset the user actually selected.
        applySpeedSensitiveVideoFiltersLocked(clamped);
    }

    double speed() {
        return doubleProperty("speed", 1.0);
    }

    void setVolume(double volume) {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return;
        // Allow boosting above 100% for quiet content (mirrors the volume-max option set
        // at startup). The shared volume model caps the desktop fraction at 2.0 (200%).
        double clamped = std::max(0.0, std::min(200.0, volume));
        mpvApi().setProperty(mpv, "volume", MPV_FORMAT_DOUBLE, &clamped);
    }

    double volume() {
        return doubleProperty("volume", 100.0);
    }

    void setMute(bool muted) {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return;
        int flag = muted ? 1 : 0;
        mpvApi().setProperty(mpv, "mute", MPV_FORMAT_FLAG, &flag);
    }

    bool isMuted() {
        return flagProperty("mute", false);
    }

    void setResizeMode(int mode) {
        switch (mode) {
            case 1: // Fill (stretch)
                setStringProperty("keepaspect", "no");
                setStringProperty("panscan", "0.0");
                setStringProperty("video-unscaled", "no");
                break;
            case 2: // Zoom (crop)
                setStringProperty("keepaspect", "yes");
                setStringProperty("panscan", "1.0");
                setStringProperty("video-unscaled", "no");
                break;
            default: // Fit (letterbox)
                setStringProperty("keepaspect", "yes");
                setStringProperty("panscan", "0.0");
                setStringProperty("video-unscaled", "no");
                break;
        }
    }

    long long durationMs() {
        return (long long)std::llround(doubleProperty("duration", 0.0) * 1000.0);
    }

    long long positionMs() {
        return (long long)std::llround(doubleProperty("time-pos", 0.0) * 1000.0);
    }

    long long bufferedPositionMs() {
        double buffered = rawPositionSeconds() + cacheAheadSeconds();
        return (long long)std::llround(std::max(buffered, 0.0) * 1000.0);
    }

    bool isLoading() {
        bool paused = isPaused();
        bool eofReached = isEnded();
        bool idle = flagProperty("core-idle", true);
        bool seeking = flagProperty("seeking", false);
        bool bufferingCache = flagProperty("paused-for-cache", false);
        bool fileReady = doubleProperty("duration", 0.0) > 0.0 || int64Property("track-list/count", 0) > 0;
        return !fileReady || (idle && !paused && !eofReached) || seeking || bufferingCache;
    }

    bool isEnded() {
        // Right after a file loads, mpv can pass through a transient eof-reached=true while it
        // settles the initial resume seek and any track-switch "refresh seek" (both routinely
        // observed in nuvio-mpv.log as "video=eof (paused)" moments after "playback restart
        // complete"), before real playback has actually begun. Reading eof-reached as genuine
        // "ended" during that window incorrectly marks a title fully watched (and drops it out
        // of Continue Watching) if the user backs out within the first couple of seconds.
        if (std::chrono::steady_clock::now() < eofSuppressedUntil.load()) return false;
        return flagProperty("eof-reached", false);
    }

    std::string audioTracksJson() {
        return tracksJsonForType("audio");
    }

    std::string subtitleTracksJson() {
        return tracksJsonForType("sub");
    }

    void selectAudioTrackId(int trackId) {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return;
        int64_t id = trackId;
        mpvApi().setProperty(mpv, "aid", MPV_FORMAT_INT64, &id);
    }

    void selectSubtitleTrackId(int trackId) {
        {
            std::lock_guard<std::mutex> lock(mpvMutex);
            if (!mpv) return;
            if (trackId < 0) {
                mpvApi().setPropertyString(mpv, "sid", "no");
                return;
            }
            int64_t id = trackId;
            mpvApi().setProperty(mpv, "sid", MPV_FORMAT_INT64, &id);
        }
        // Re-derive sub-ass-override for whatever track just became selected — the helper
        // functions above each take mpvMutex themselves, so this must run after it's released.
        refreshSubtitleAssOverrideMode();
    }

    void addSubtitleUrl(const std::string &url) {
        if (url.empty()) return;
        command({"sub-add", url, "select"});
        refreshSubtitleAssOverrideMode();
    }

    void removeExternalSubtitles() {
        removeExternalSubtitleTracks();
        setStringProperty("sid", "no");
    }

    void removeExternalSubtitlesAndSelect(int trackId) {
        removeExternalSubtitleTracks();
        if (trackId >= 0) {
            selectSubtitleTrackId(trackId);
        } else {
            setStringProperty("sid", "no");
        }
    }

    void setSubtitleDelayMs(int delayMs) {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return;
        int clamped = std::max(-60000, std::min(60000, delayMs));
        double delaySeconds = (double)clamped / 1000.0;
        mpvApi().setProperty(mpv, "sub-delay", MPV_FORMAT_DOUBLE, &delaySeconds);
    }

    void applySubtitleStyle(
        const std::string &textColor,
        const std::string &backgroundColor,
        const std::string &outlineColor,
        double outlineSize,
        bool bold,
        double fontSize,
        int subPos,
        const std::string &fontName
    ) {
        refreshSubtitleAssOverrideMode();
        // Empty font name reverts to mpv's built-in default ("sans-serif").
        setStringProperty("sub-font", fontName.empty() ? "sans-serif" : fontName);
        setStringProperty("sub-color", textColor.empty() ? "#FFFFFFFF" : textColor);
        setStringProperty("sub-back-color", backgroundColor.empty() ? "#00000000" : backgroundColor);
        setStringProperty("sub-outline-color", outlineColor.empty() ? "#FF000000" : outlineColor);
        setStringProperty(
            "sub-border-style",
            backgroundColor.rfind("#00", 0) == 0 ? "outline-and-shadow" : "opaque-box"
        );
        setStringProperty("sub-bold", bold ? "yes" : "no");

        {
            std::lock_guard<std::mutex> lock(mpvMutex);
            if (!mpv) return;
            double outline = std::max(0.0, std::min(8.0, outlineSize));
            double size = std::max(24.0, std::min(96.0, fontSize));
            int64_t position = std::max(0, std::min(150, subPos));
            mpvApi().setProperty(mpv, "sub-outline-size", MPV_FORMAT_DOUBLE, &outline);
            mpvApi().setProperty(mpv, "sub-font-size", MPV_FORMAT_DOUBLE, &size);
            mpvApi().setProperty(mpv, "sub-pos", MPV_FORMAT_INT64, &position);
        }

        // mpv doesn't repaint the embedded surface while paused/idle, so a style change
        // made while paused wouldn't show until the next frame. Force a redraw.
        forceVideoRedraw();
    }

    void setMpvPropertyString(const std::string &key, const std::string &value) {
        if (key == "vf" && value.find("vapoursynth") != std::string::npos) {
            preloadBundledVapourSynthRuntime();
            wchar_t readback[4096] = {};
            DWORD readbackLen = GetEnvironmentVariableW(L"PYTHONHOME", readback, 4096);
            nuvioMpvLogAppend("[nuvio] about to set vf=vapoursynth; PYTHONHOME readback=" +
                (readbackLen > 0 ? toUtf8(std::wstring(readback, readback + readbackLen)) : std::string("(unset)")) + "\n");
        }
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return;
        if (key == "vf") {
            requestedVideoFilters = value;
            if (!containsVapourSynthFilter(value)) {
                svpBypassedForSpeed = false;
                setVideoFiltersPropertyLocked(value);
                return;
            }
            double currentSpeed = 1.0;
            tryGetDoubleLocked("speed", currentSpeed);
            bool shouldBypass = currentSpeed >= svpSpeedBypassThreshold ||
                (svpBypassedForSpeed && currentSpeed > svpSpeedRestoreThreshold);
            std::string effectiveValue = shouldBypass ? removeVapourSynthFilters(value) : value;
            setVideoFiltersPropertyLocked(effectiveValue);
            if (shouldBypass != svpBypassedForSpeed) {
                svpBypassedForSpeed = shouldBypass;
                nuvioMpvLogAppend(std::string("[nuvio] anime SVP ") +
                    (shouldBypass ? "bypassed" : "restored") +
                    " for playback speed=" + std::to_string(currentSpeed) +
                    " vf=" + effectiveValue + "\n");
            }
            return;
        }
        mpvApi().setPropertyString(mpv, key.c_str(), value.c_str());
    }

    // mpv only repaints the wid-embedded video surface on a size change while otherwise
    // idle, so equalizer / colorspace property changes (HDR + colour presets) don't show
    // until the window is resized — which is why minimize/restore "fixes" it. Nudge the
    // container window size by 1px and back on the UI thread to force a VO reconfigure and
    // redraw immediately.
    void forceVideoRedraw() {
        auto self = shared_from_this();
        postUiTask([self]() {
            if (!self->containerHwnd || !IsWindow(self->containerHwnd)) return;
            RECT rect{};
            GetClientRect(self->containerHwnd, &rect);
            LONG width = rect.right - rect.left;
            LONG height = rect.bottom - rect.top;
            if (width <= 1 || height <= 1) return;
            // A visibility transition forces D3D11/gpu-next to rebuild the embedded
            // swapchain, matching the part of minimize/restore that makes profile changes
            // appear. Keep the transition on the native UI thread and redraw all children
            // so the WebView controls remain synchronized with the video surface.
            ShowWindow(self->containerHwnd, SW_HIDE);
            SetWindowPos(self->containerHwnd, nullptr, 0, 0, width - 1, height - 1,
                         SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
            ShowWindow(self->containerHwnd, SW_SHOWNA);
            SetWindowPos(self->containerHwnd, HWND_TOP, 0, 0, width, height,
                         SWP_NOMOVE | SWP_NOACTIVATE | SWP_FRAMECHANGED | SWP_SHOWWINDOW);
            SendMessageW(self->containerHwnd, WM_SIZE, SIZE_RESTORED, MAKELPARAM(width, height));
            RedrawWindow(
                self->hostHwnd,
                nullptr,
                nullptr,
                RDW_INVALIDATE | RDW_UPDATENOW | RDW_ALLCHILDREN | RDW_FRAME
            );

            // Trailer playback's D3D11 swapchain only commits dynamic colour changes on
            // a top-level activation transition (the same reason alt-tab makes them show).
            // Reproduce those notifications without moving focus to another application.
            HWND topLevel = GetAncestor(self->hostHwnd, GA_ROOT);
            if (topLevel && IsWindow(topLevel)) {
                DWORD currentThreadId = GetCurrentThreadId();
                SendMessageW(topLevel, WM_NCACTIVATE, FALSE, 0);
                SendMessageW(topLevel, WM_ACTIVATEAPP, FALSE, currentThreadId);
                SendMessageW(topLevel, WM_ACTIVATE, WA_INACTIVE, 0);
                SendMessageW(topLevel, WM_ACTIVATEAPP, TRUE, currentThreadId);
                SendMessageW(topLevel, WM_NCACTIVATE, TRUE, 0);
                SendMessageW(topLevel, WM_ACTIVATE, WA_ACTIVE, 0);
            }
        });
    }

    void logBridge(const std::string &line) {
        nuvioBridgeLog(line);
    }

    void logHresult(const std::string &operation, HRESULT result) {
        std::ostringstream stream;
        stream << operation << " hr=0x" << std::hex << (unsigned long)result;
        nuvioBridgeLog(stream.str());
    }

    // True for hero-trailer preview surfaces, which must never hold OS keyboard focus.
    // Read by containerWindowProc to reject mouse activation (WM_MOUSEACTIVATE).
    bool isPassiveSurface() const { return passiveSurface; }

private:
    HWND hostHwnd = nullptr;
    HWND containerHwnd = nullptr;
    HWND messageHwnd = nullptr;
    DWORD uiThreadId = 0;
    bool didOleInitialize = false;
    bool cursorHidden = false;
    // A passive surface is a hero-trailer preview (controlsUrl carries "heroTrailer=1"). It must
    // never take OS keyboard focus: the Compose UI stays the sole keyboard owner and only
    // trailer-specific actions (mute/volume) are forwarded to it. Full-screen playback leaves
    // this false so mpv/WebView2 receive focus normally (text entry, forms, shortcuts).
    bool passiveSurface = false;
    std::thread uiThread;

    ComPtr<ICoreWebView2Environment> environment;
    ComPtr<ICoreWebView2Controller> controller;
    ComPtr<ICoreWebView2> webView;
    EventRegistrationToken messageToken = {};

    std::mutex uiTaskMutex;
    std::deque<std::function<void()>> uiTasks;
    std::shared_ptr<WindowsMpvWebPlayer> detachedLifetimeHold;

    std::mutex mpvMutex;
    mpv_handle *mpv = nullptr;
    std::thread eventThread;
    std::atomic_bool stopping = false;
    std::atomic_bool shuttingDown = false;

    JavaVM *javaVm = nullptr;
    jobject eventSink = nullptr;
    jmethodID eventMethod = nullptr;

    std::atomic_bool controlsWebReady = false;
    std::mutex controlsMutex;
    std::string pendingControlsJson;
    double initialStartSeconds = 0.0;

    // Last seek target issued (seconds), used to accumulate rapid relative seeks while a
    // prior seek is still in flight. Guarded by mpvMutex; -1 when no seek is pending.
    double pendingSeekTargetSeconds = -1.0;
    std::chrono::steady_clock::time_point pendingSeekIssuedAt{};

    // Per-message occurrence counts backing shouldWriteMpvLogLine. Event-thread only.
    std::map<std::string, int> mpvLogLineCounts;

    // Cached track-list JSON for the 500ms controls sync. Rebuilding the lists means dozens
    // of mutex-locked mpv property reads per tick; tracks only change on file load, external
    // subtitle add/remove, or selection changes, all of which show up in the key below.
    std::string cachedTracksKey;
    std::string cachedAudioTracksJson = "[]";
    std::string cachedSubtitleTracksJson = "[]";

    std::string videoParamsPrimaries;
    std::string videoParamsGamma;
    bool videoParamsPrimariesReceived = false;
    bool videoParamsGammaReceived = false;
    std::string requestedVideoFilters;
    std::string appliedVideoFilters;
    bool svpBypassedForSpeed = false;
    // Arms the one-shot "playbackRestart" notification below: set on FILE_LOADED, cleared by
    // the first PLAYBACK_RESTART, so the app learns when the first frame of a load rendered
    // without hearing about every post-seek restart. Only touched on the mpv event thread.
    bool playbackRestartPendingForFile = false;

    // Empty until the first refresh actually applies a mode, so it never matches and the
    // first call always sets sub-ass-override explicitly rather than assuming mpv's default.
    std::string appliedSubAssOverrideMode;
    // One-shot delayed check a few seconds after each file load, for whatever subtitle track
    // mpv auto-selects on its own (no explicit selectSubtitleTrackId/addSubtitleUrl call fires
    // for that). Deliberately not a recurring poll — continuous rechecking interfered with
    // seeking/playback start. Set from the mpv event thread, read from the UI/timer thread —
    // the deadline is written before the atomic flag so the flag's release/acquire ordering
    // makes it visible by the time the reader observes the flag as true.
    std::atomic_bool subtitleAssOverrideInitialCheckPending = false;
    std::chrono::steady_clock::time_point subtitleAssOverrideCheckDeadline;
    std::atomic<std::chrono::steady_clock::time_point> eofSuppressedUntil{std::chrono::steady_clock::time_point{}};

    std::string externalAudioUrl;
    // User's desktop mpv options ("key=value"), applied just before mpv_initialize so they
    // override Nuvio's built-in options. Carries the audio-passthrough and custom-options settings.
    std::vector<std::string> extraMpvOptions;
    bool vsrLogActive = false;

    friend LRESULT CALLBACK messageWindowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam);
    friend LRESULT CALLBACK containerWindowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam);

    void runNativeUiThread(
        std::string sourceUrl,
        std::vector<std::string> headerLines,
        bool playWhenReady,
        long long initialPositionMs,
        std::string controlsUrl,
        bool nvidiaRtxSuperResolutionEnabled,
        bool nvidiaRtxHdrEnabled,
        std::string animeSvpFilter,
        std::shared_ptr<InitializationState> initState
    ) {
        std::string failure;
        try {
            nuvioBridgeLog("native ui thread entered");
            initializeOnNativeUiThread(sourceUrl, headerLines, playWhenReady, initialPositionMs, controlsUrl, nvidiaRtxSuperResolutionEnabled, nvidiaRtxHdrEnabled, animeSvpFilter);
        } catch (const std::exception &error) {
            failure = error.what();
            nuvioBridgeLog("native ui thread init exception: " + failure);
            cleanupUiResources();
        }

        {
            std::lock_guard<std::mutex> lock(initState->mutex);
            initState->failure = failure;
            initState->complete = true;
        }
        initState->cv.notify_one();

        if (!failure.empty()) {
            return;
        }

        nuvioBridgeLog("native ui message loop begin");
        MSG msg = {};
        while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
        nuvioBridgeLog("native ui message loop end");
    }

    void initializeOnNativeUiThread(
        const std::string &sourceUrl,
        const std::vector<std::string> &headerLines,
        bool playWhenReady,
        long long initialPositionMs,
        const std::string &controlsUrl,
        bool nvidiaRtxSuperResolutionEnabled,
        bool nvidiaRtxHdrEnabled,
        const std::string &animeSvpFilter
    ) {
        nuvioBridgeLog("init register window classes");
        registerWindowClasses();
        uiThreadId = GetCurrentThreadId();
        // Hero-trailer previews pass "heroTrailer=1" in the controls URL. Mark this surface passive
        // so its container refuses focus-stealing mouse activation and its WebView2 bounces any
        // focus it grabs back to the Compose window (see containerWindowProc / startWebView).
        passiveSurface = controlsUrl.find("heroTrailer=1") != std::string::npos;
        nuvioBridgeLog("init ole");
        HRESULT oleResult = OleInitialize(nullptr);
        didOleInitialize = SUCCEEDED(oleResult);
        if (FAILED(oleResult)) {
            throw std::runtime_error(hresultMessage("OleInitialize", oleResult));
        }

        nuvioBridgeLog("init create message hwnd");
        messageHwnd = CreateWindowExW(
            0,
            kMessageWindowClass,
            L"",
            0,
            0,
            0,
            0,
            0,
            HWND_MESSAGE,
            nullptr,
            gModule,
            this
        );
        if (!messageHwnd) {
            throw std::runtime_error("Unable to create Windows player message window.");
        }

        nuvioBridgeLog("init create container hwnd");
        RECT bounds = {};
        GetClientRect(hostHwnd, &bounds);
        LONG width = std::max<LONG>(1, bounds.right - bounds.left);
        LONG height = std::max<LONG>(1, bounds.bottom - bounds.top);
        containerHwnd = CreateWindowExW(
            // Passive trailer surfaces get WS_EX_NOACTIVATE so the container can't be activated
            // (and thus can't pull OS focus off the Compose window) when clicked.
            passiveSurface ? WS_EX_NOACTIVATE : 0,
            kContainerWindowClass,
            L"",
            WS_CHILD | WS_VISIBLE | WS_CLIPSIBLINGS,
            0,
            0,
            width,
            height,
            hostHwnd,
            nullptr,
            gModule,
            this
        );
        if (!containerHwnd) {
            throw std::runtime_error("Unable to create native player container window.");
        }

        nuvioBridgeLog("init start webview");
        startWebView(controlsUrl);
        nuvioBridgeLog("init start mpv");
        startMpv(sourceUrl, headerLines, playWhenReady, initialPositionMs, nvidiaRtxSuperResolutionEnabled, nvidiaRtxHdrEnabled, animeSvpFilter);
        nuvioBridgeLog("init layout");
        layoutNativeSubviews();
        if (!SetTimer(messageHwnd, NUVIO_TIMER_ID, 500, nullptr)) {
            throw std::runtime_error("Unable to start native player timer.");
        }
        nuvioBridgeLog("init timer started");
    }

    void cleanupUiResources() {
        nuvioBridgeLog("cleanup begin");
        if (cursorHidden) {
            nuvioBridgeLog("cleanup show cursor");
            cursorHidden = false;
            ShowCursor(TRUE);
        }
        if (messageHwnd) {
            nuvioBridgeLog("cleanup kill timer");
            KillTimer(messageHwnd, NUVIO_TIMER_ID);
        }
        if (webView && messageToken.value != 0) {
            nuvioBridgeLog("cleanup remove web message handler");
            webView->remove_WebMessageReceived(messageToken);
            messageToken.value = 0;
        }
        if (controller) {
            nuvioBridgeLog("cleanup close webview controller");
            controller->Close();
            nuvioBridgeLog("cleanup reset webview controller");
            controller.Reset();
        }
        nuvioBridgeLog("cleanup reset webview");
        webView.Reset();
        nuvioBridgeLog("cleanup reset environment");
        environment.Reset();
        if (containerHwnd) {
            nuvioBridgeLog("cleanup destroy container");
            DestroyWindow(containerHwnd);
            containerHwnd = nullptr;
        }
        if (messageHwnd) {
            nuvioBridgeLog("cleanup destroy message window");
            HWND hwnd = messageHwnd;
            messageHwnd = nullptr;
            DestroyWindow(hwnd);
        }
        if (didOleInitialize) {
            nuvioBridgeLog("cleanup ole uninitialize");
            OleUninitialize();
            didOleInitialize = false;
        }
        nuvioBridgeLog("cleanup end");
    }

    void postUiTask(std::function<void()> task) {
        if (shuttingDown.load()) return;
        {
            std::lock_guard<std::mutex> lock(uiTaskMutex);
            uiTasks.push_back(std::move(task));
        }
        HWND target = messageHwnd;
        if (target) {
            PostMessageW(target, WM_NUVIO_TASK, 0, 0);
        }
    }

    bool sendUiTask(std::function<void()> task) {
        if (GetCurrentThreadId() == uiThreadId || !messageHwnd) {
            task();
            return true;
        }

        auto done = std::make_shared<bool>(false);
        auto doneMutex = std::make_shared<std::mutex>();
        auto doneCv = std::make_shared<std::condition_variable>();
        {
            std::lock_guard<std::mutex> lock(uiTaskMutex);
            uiTasks.push_back([task = std::move(task), done, doneMutex, doneCv]() mutable {
                task();
                {
                    std::lock_guard<std::mutex> doneLock(*doneMutex);
                    *done = true;
                }
                doneCv->notify_one();
            });
        }
        HWND target = messageHwnd;
        if (target) {
            PostMessageW(target, WM_NUVIO_TASK, 0, 0);
        }

        std::unique_lock<std::mutex> waitLock(*doneMutex);
        bool completed = doneCv->wait_for(waitLock, std::chrono::seconds(5), [&]() { return *done; });
        if (!completed) {
            nuvioBridgeLog("sendUiTask timed out waiting for native ui thread");
        }
        return completed;
    }

    void startWebView(const std::string &controlsUrl) {
        nuvioBridgeLog("webview create environment");
        const bool controlsFocusable = controlsUrl.find("heroTrailer=1") == std::string::npos;
        std::wstring userDataDir = tempUserDataDirectory();
        auto weakSelf = weak_from_this();
        HRESULT result = CreateCoreWebView2EnvironmentWithOptions(
            nullptr,
            userDataDir.c_str(),
            nullptr,
            Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
                [weakSelf, controlsUrl, controlsFocusable](HRESULT envResult, ICoreWebView2Environment *createdEnvironment) -> HRESULT {
                    auto self = weakSelf.lock();
                    if (!self || self->shuttingDown.load()) return S_OK;
                    if (FAILED(envResult) || !createdEnvironment) {
                        if (self) self->logHresult("webview environment failed", envResult);
                        return S_OK;
                    }
                    self->logBridge("webview environment created");
                    self->environment = createdEnvironment;
                    auto controllerWeakSelf = weakSelf;
                    HRESULT controllerResult = createdEnvironment->CreateCoreWebView2Controller(
                        self->containerHwnd,
                        Callback<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(
                            [controllerWeakSelf, controlsUrl, controlsFocusable](HRESULT controllerResult, ICoreWebView2Controller *createdController) -> HRESULT {
                                auto controllerSelf = controllerWeakSelf.lock();
                                if (!controllerSelf || controllerSelf->shuttingDown.load()) return S_OK;
                                if (FAILED(controllerResult) || !createdController) {
                                    if (controllerSelf) controllerSelf->logHresult("webview controller failed", controllerResult);
                                    return S_OK;
                                }
                                controllerSelf->logBridge("webview controller created");
                                controllerSelf->controller = createdController;
                                createdController->get_CoreWebView2(&controllerSelf->webView);

                                ComPtr<ICoreWebView2Controller2> controller2;
                                if (SUCCEEDED(createdController->QueryInterface(IID_PPV_ARGS(&controller2))) && controller2) {
                                    COREWEBVIEW2_COLOR transparent = {0, 0, 0, 0};
                                    controller2->put_DefaultBackgroundColor(transparent);
                                }

                                ComPtr<ICoreWebView2Settings> settings;
                                if (controllerSelf->webView && SUCCEEDED(controllerSelf->webView->get_Settings(&settings)) && settings) {
                                    settings->put_AreDefaultContextMenusEnabled(FALSE);
                                    settings->put_IsStatusBarEnabled(FALSE);
                                    // The controls overlay is a headless HTML surface driven entirely by
                                    // updateControls()/JS; it must never intercept playback hotkeys. Disable
                                    // the browser's built-in accelerator keys so keys like F7 (caret
                                    // browsing), F5 (reload), Ctrl+F/P, etc. can't leak into the WebView2
                                    // during playback. Needs the Settings3 interface (WebView2 SDK
                                    // 1.0.864.35+); older runtimes degrade gracefully via the null check.
                                    ComPtr<ICoreWebView2Settings3> settings3;
                                    if (SUCCEEDED(settings->QueryInterface(IID_PPV_ARGS(&settings3))) && settings3) {
                                        settings3->put_AreBrowserAcceleratorKeysEnabled(FALSE);
                                    }
                                }

                                if (controllerSelf->webView) {
                                    auto messageWeakSelf = controllerWeakSelf;
                                    controllerSelf->webView->add_WebMessageReceived(
                                        Callback<ICoreWebView2WebMessageReceivedEventHandler>(
                                            [messageWeakSelf](ICoreWebView2 *, ICoreWebView2WebMessageReceivedEventArgs *args) -> HRESULT {
                                                auto messageSelf = messageWeakSelf.lock();
                                                if (!messageSelf || messageSelf->shuttingDown.load() || !args) return S_OK;
                                                PWSTR messageJson = nullptr;
                                                if (SUCCEEDED(args->get_WebMessageAsJson(&messageJson)) && messageJson) {
                                                    messageSelf->handleWebMessage(std::wstring(messageJson));
                                                    CoTaskMemFree(messageJson);
                                                }
                                                return S_OK;
                                            }
                                        ).Get(),
                                        &controllerSelf->messageToken
                                    );
                                    controllerSelf->layoutNativeSubviews();
                                    std::wstring url = toWide(controlsUrl);
                                    controllerSelf->logBridge("webview navigate controls");
                                    controllerSelf->webView->Navigate(url.c_str());
                                    if (controlsFocusable) {
                                        createdController->MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC);
                                    } else {
                                        controllerSelf->logBridge("webview focus skipped for passive hero trailer controls");
                                    }
                                }
                                return S_OK;
                            }
                        ).Get()
                    );
                    (void)controllerResult;
                    return S_OK;
                }
            ).Get()
        );
        if (FAILED(result)) {
            throw std::runtime_error(hresultMessage("CreateCoreWebView2EnvironmentWithOptions", result));
        }
        nuvioBridgeLog("webview environment request posted");
    }

    void startMpv(
        const std::string &sourceUrl,
        const std::vector<std::string> &headerLines,
        bool playWhenReady,
        long long initialPositionMs,
        bool nvidiaRtxSuperResolutionEnabled,
        bool nvidiaRtxHdrEnabled,
        const std::string &animeSvpFilter
    ) {
        nuvioBridgeLog("mpv create");
        setPythonHomeEnvironmentVariable();
        MpvApi &api = mpvApi();
        {
            std::lock_guard<std::mutex> lock(mpvMutex);
            mpv = api.create();
            if (!mpv) {
                throw std::runtime_error("mpv_create failed.");
            }
            initialStartSeconds = initialPositionMs > 0 ? (double)initialPositionMs / 1000.0 : 0.0;

            // Capture mpv's own log to %TEMP%\nuvio-mpv.log so filter/hwdec issues are visible.
            // Verbose ("v") level is only requested when diagnosing the RTX pipeline or when
            // NUVIO_MPV_VERBOSE is set: at "v" mpv emits hundreds of messages during open/probe
            // alone, and each one costs an open/append/close of the log file on the same event
            // thread that dispatches playback events to the app — measurably delaying startup
            // event handling. Normal playback keeps warnings/errors only.
            if (mpvApi().requestLogMessages) {
                vsrLogActive = true;
                const char *verboseEnv = std::getenv("NUVIO_MPV_VERBOSE");
                bool verboseLog = (verboseEnv && *verboseEnv && std::string(verboseEnv) != "0") ||
                    nvidiaRtxSuperResolutionEnabled || nvidiaRtxHdrEnabled;
                nuvioMpvLogAppend(std::string("[nuvio] Logging enabled (level=") +
                    (verboseLog ? "v" : "warn") + ")\n");
                mpvApi().requestLogMessages(mpv, verboseLog ? "v" : "warn");
            }

            nuvioBridgeLog("mpv set options");
            setMpvOptionStringLocked("config", "no");
            setMpvOptionStringLocked("osc", "no");
            setMpvOptionStringLocked("input-default-bindings", "yes");
            setMpvOptionStringLocked("input-vo-keyboard", "no");
            setMpvOptionStringLocked("keep-open", "yes");

            // Renderer
            setMpvOptionStringLocked("vo", "gpu-next");
            setMpvOptionStringLocked("gpu-api", "d3d11");
            setMpvOptionStringLocked("hwdec", "d3d11va");
            // Restrict D3D11VA to codecs it actually supports. "all" combined with
            // vd-lavc-software-fallback=no breaks older codecs (MPEG-4 Visual / DivX,
            // MPEG-2, Theora, etc.) found in AVI and other containers: D3D11VA rejects them,
            // and with no software fallback mpv silently fails to decode the video.
            // Restrict hardware decoding to codecs D3D11VA actually supports, so containers
            // like AVI with older codecs (DivX/Xvid, MPEG-4 Visual) go straight to software
            // without any hardware attempt. Software fallback is always allowed — disabling
            // it for a general media player silently kills video when D3D11VA fails on edge
            // cases (e.g. H.264-in-AVI with non-standard container extradata).
            setMpvOptionStringLocked("hwdec-codecs", "h264,hevc,vp9,vp8,av1,vc1,mpeg2video");
            // 0 = auto-detect core count. A fixed low cap starves software decoding
            // (the fallback path for codecs D3D11VA rejects) on modern CPUs.
            setMpvOptionStringLocked("vd-lavc-threads", "0");

            // NVIDIA RTX Video Super Resolution (opt-in). Pin the D3D11 device to the NVIDIA GPU
            // and run the d3d11 video processor with NVIDIA's super-resolution scaler. Default-off
            // so the standard pipeline above is untouched unless the user enables it.
            if (nvidiaRtxSuperResolutionEnabled) {
                // Pin the D3D11 device to the NVIDIA GPU (matters on hybrid-GPU machines).
                // The actual RTX VSR filter and its source/display-aware scale are applied from the
                // Kotlin runtime profile path (applyDesktopAnimeProfile), which is the single owner
                // of `vf` — setting it here too would just get overwritten when that path runs.
                setMpvOptionStringLocked("d3d11-adapter", "NVIDIA");
            }

            // NVIDIA RTX Video True HDR (opt-in, requires RTX GPU + Windows HDR enabled).
            // When enabled, d3d11vpp (the NVIDIA video processor) converts SDR → HDR using the
            // driver's AI-based tone mapping. This requires mpv master ≥ Feb 19 2026 which:
            //   (1) automatically sets IMGFMT_X2BGR10 output on the VP (10-bit BT.2020+PQ RGB),
            //   (2) uses ID3D11VideoContext1 for proper DXGI_COLOR_SPACE HDR signalling.
            // The `nvidia-true-hdr` option is set via the `vf` string in Kotlin
            // (applyDesktopAnimeProfile), since that function owns the vf chain. We only set
            // the display-output properties here at init time.
            if (nvidiaRtxHdrEnabled) {
                setMpvOptionStringLocked("d3d11-adapter", "NVIDIA");
                // Let mpv auto-negotiate color space with the OS HDR pipeline.
                // On SDR displays these resolve to SDR; on HDR displays they signal PQ/BT.2020.
                setMpvOptionStringLocked("d3d11-output-csp", "auto");
                setMpvOptionStringLocked("target-colorspace-hint", "auto");
            }

            // HDR / tonemapping
            // target-colorspace-hint lets the OS signal the display for passthrough on HDR screens;
            // on SDR screens mpv falls back to the tone-mapping path set below.
            setMpvOptionStringLocked("target-colorspace-hint", "auto");
            setMpvOptionStringLocked("target-colorspace-hint-mode", "target");
            setMpvOptionStringLocked("target-contrast", "auto");
            setMpvOptionStringLocked("tone-mapping", "bt.2446a");
            setMpvOptionStringLocked("tone-mapping-param", "0.5");
            setMpvOptionStringLocked("tone-mapping-mode", "hybrid");
            setMpvOptionStringLocked("gamut-mapping-mode", "perceptual");
            setMpvOptionStringLocked("hdr-compute-peak", "yes");
            setMpvOptionStringLocked("hdr-peak-percentile", "99.8");
            setMpvOptionStringLocked("hdr-peak-decay-rate", "20");
            setMpvOptionStringLocked("hdr-contrast-recovery", "0.3");

            // Scaling
            setMpvOptionStringLocked("scale", "spline36");
            setMpvOptionStringLocked("cscale", "lanczos");
            setMpvOptionStringLocked("dscale", "mitchell");
            setMpvOptionStringLocked("scale-antiring", "0.7");
            setMpvOptionStringLocked("cscale-antiring", "0.7");
            setMpvOptionStringLocked("dscale-antiring", "0.7");
            setMpvOptionStringLocked("sigmoid-upscaling", "yes");
            setMpvOptionStringLocked("correct-downscaling", "yes");
            setMpvOptionStringLocked("linear-downscaling", "no");

            // Dithering
            setMpvOptionStringLocked("dither", "fruit");
            setMpvOptionStringLocked("dither-depth", "10");
            setMpvOptionStringLocked("temporal-dither", "yes");
            setMpvOptionStringLocked("temporal-dither-period", "1");

            // Debanding
            setMpvOptionStringLocked("deband", "yes");
            setMpvOptionStringLocked("deband-iterations", "2");
            setMpvOptionStringLocked("deband-threshold", "35");
            setMpvOptionStringLocked("deband-range", "16");
            setMpvOptionStringLocked("deband-grain", "0");

            if (!animeSvpFilter.empty()) {
                preloadBundledVapourSynthRuntime();
                requestedVideoFilters = animeSvpFilter;
                if (setMpvOptionStringLocked("vf", animeSvpFilter.c_str())) {
                    appliedVideoFilters = animeSvpFilter;
                }
            }

            // Streaming cache (1x baseline; setSpeed scales these with playback rate)
            setMpvOptionStringLocked("cache", "yes");
            setMpvOptionStringLocked("cache-pause", "yes");
            setMpvOptionStringLocked("cache-pause-initial", "yes");
            // How much media must be buffered before (re)starting playback — this directly
            // sets time-to-first-frame and post-seek resume latency. The large readahead
            // above provides the actual stall resilience once playing, so keep this small.
            setMpvOptionStringLocked("cache-pause-wait", "2");
            setMpvOptionStringLocked("cache-secs", std::to_string((long long)baseCacheSecs).c_str());
            setMpvOptionStringLocked("demuxer-readahead-secs", std::to_string((long long)baseReadaheadSecs).c_str());
            // Separate YouTube video/audio streams can exhaust the byte ceiling well before
            // the requested time-based readahead at 2x, especially for high-bitrate trailers.
            setMpvOptionStringLocked("demuxer-max-bytes", "1GiB");
            setMpvOptionStringLocked("demuxer-max-back-bytes", "128MiB");
            setMpvOptionStringLocked("stream-buffer-size", "256MiB");
            // HTTP reconnect options — skip for local file paths (no scheme = local file).
            bool isLocalFile = sourceUrl.find("://") == std::string::npos ||
                               sourceUrl.rfind("file://", 0) == 0;
            if (!isLocalFile) {
                setMpvOptionStringLocked("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5");
            }

            setMpvOptionStringLocked("hr-seek", "no");

            // Permit software amplification above 100% so quiet content can be boosted.
            // setVolume() clamps to this ceiling; the shared volume model caps at 200%.
            setMpvOptionStringLocked("volume-max", "200");

            int64_t wid = (int64_t)(intptr_t)containerHwnd;
            int widResult = api.setOption(mpv, "wid", MPV_FORMAT_INT64, &wid);
            if (widResult < 0) {
                throw std::runtime_error(std::string("mpv wid option failed: ") + api.errorText(widResult));
            }

            if (!headerLines.empty()) {
                std::string headers;
                for (size_t index = 0; index < headerLines.size(); index++) {
                    if (index > 0) headers.push_back(',');
                    for (char c : headerLines[index]) {
                        if (c == '\\' || c == ',') headers.push_back('\\');
                        headers.push_back(c);
                    }
                }
                setMpvOptionStringLocked("http-header-fields", headers.c_str());
            }

            // User-supplied options (audio passthrough + custom options box) are applied last so
            // they override any of Nuvio's built-in options above. Each entry is "key=value";
            // a malformed line (no '=') is skipped rather than aborting playback.
            for (const std::string &option : extraMpvOptions) {
                std::string::size_type equals = option.find('=');
                if (equals == std::string::npos) continue;
                std::string key = option.substr(0, equals);
                std::string value = option.substr(equals + 1);
                if (key.empty()) continue;
                if (!setMpvOptionStringLocked(key.c_str(), value.c_str())) {
                    nuvioMpvLogAppend("[nuvio] ignored custom mpv option: " + key + "\n");
                }
            }

            nuvioBridgeLog("mpv initialize");
            int initResult = api.initialize(mpv);
            if (initResult < 0) {
                throw std::runtime_error(std::string("mpv_initialize failed: ") + api.errorText(initResult));
            }
            nuvioBridgeLog("mpv initialized");

            api.observeProperty(mpv, 0, "video-params/primaries", MPV_FORMAT_STRING);
            api.observeProperty(mpv, 0, "video-params/gamma", MPV_FORMAT_STRING);

            // Up/Down are reserved for app-level volume control; explicitly
            // disable mpv's built-in seek bindings for them so they can't
            // intercept the keypress (e.g. if input-vo-keyboard is briefly
            // bypassed while the player surface is recreated on source switch).
            {
                const char *unbindUp[] = {"keybind", "UP", "ignore", nullptr};
                api.command(mpv, unbindUp);
                const char *unbindDown[] = {"keybind", "DOWN", "ignore", nullptr};
                api.command(mpv, unbindDown);
            }

            // mpv's EDL demuxer can expose independent URLs as tracks of one input. This
            // is the same shape used by mpv's own YouTube integration and avoids the
            // runtime audio-add operation that can wedge the embedded Windows surface.
            std::string playbackSource = sourceUrl;
            if (!externalAudioUrl.empty()) {
                playbackSource =
                    "edl://%" + std::to_string(sourceUrl.size()) + "%" + sourceUrl +
                    ";!new_stream;%" + std::to_string(externalAudioUrl.size()) + "%" + externalAudioUrl;
            }

            std::vector<const char *> loadCommand = {"loadfile", playbackSource.c_str()};
            std::string loadOptions;
            if (initialPositionMs > 0) {
                char startBuffer[64];
                std::snprintf(startBuffer, sizeof(startBuffer), "start=%.3f", (double)initialPositionMs / 1000.0);
                loadOptions = startBuffer;
                loadCommand.push_back("replace");
                loadCommand.push_back("-1");
                loadCommand.push_back(loadOptions.c_str());
            }
            loadCommand.push_back(nullptr);

            nuvioBridgeLog("mpv loadfile");
            int commandResult = api.command(mpv, loadCommand.data());
            if (commandResult < 0) {
                throw std::runtime_error(std::string("mpv loadfile failed: ") + api.errorText(commandResult));
            }
            nuvioBridgeLog("mpv loadfile accepted");

        }

        setPaused(!playWhenReady);
        auto self = shared_from_this();
        nuvioBridgeLog("mpv event thread starting");
        eventThread = std::thread([self]() { self->drainMpvEvents(); });
        nuvioBridgeLog("mpv start complete");
    }

    void layoutNativeSubviews() {
        if (!hostHwnd || !IsWindow(hostHwnd)) {
            return;
        }
        RECT bounds = {};
        GetClientRect(hostHwnd, &bounds);
        LONG width = std::max<LONG>(1, bounds.right - bounds.left);
        LONG height = std::max<LONG>(1, bounds.bottom - bounds.top);
        if (containerHwnd) {
            SetWindowPos(containerHwnd, HWND_TOP, 0, 0, width, height, SWP_SHOWWINDOW | SWP_NOACTIVATE);
        }
        if (controller) {
            RECT webBounds = {0, 0, width, height};
            controller->put_Bounds(webBounds);
            controller->put_IsVisible(TRUE);
        }
    }

    void flushPendingControlsJsonIfReady() {
        if (!webView || !controlsWebReady.load()) {
            return;
        }
        std::string controlsJson;
        {
            std::lock_guard<std::mutex> lock(controlsMutex);
            controlsJson = pendingControlsJson;
            pendingControlsJson.clear();
        }
        if (controlsJson.empty()) return;
        std::wstring script =
            L"(function(){if(!window.playerControls)return 'missing';window.playerControls(JSON.parse(" +
            javaScriptStringLiteral(controlsJson) +
            L"));return 'applied';})()";
        webView->ExecuteScript(script.c_str(), nullptr);
    }

    void syncControls() {
        if (!webView) return;
        double duration = doubleProperty("duration", 0.0);
        double position = doubleProperty("time-pos", 0.0);
        // Absolute time (seconds) up to which media is demuxer-cached ahead, so the controls can
        // draw a lighter "buffered" region on the seek bar.
        double buffered = (double)bufferedPositionMs() / 1000.0;
        bool paused = isPaused();
        bool loading = isLoading();
        // Track metadata (titles, languages, codecs) is immutable per track; the lists only
        // change when tracks appear/disappear or the selection moves, so key the cache on
        // count + selected audio/subtitle ids instead of re-reading every track each tick.
        std::string tracksKey = std::to_string(int64Property("track-list/count", 0)) +
            "|" + stringProperty("aid", "") + "|" + stringProperty("sid", "");
        if (tracksKey != cachedTracksKey) {
            cachedTracksKey = tracksKey;
            cachedAudioTracksJson = audioTracksJson();
            cachedSubtitleTracksJson = subtitleTracksJson();
        }
        const std::string &audioTracks = cachedAudioTracksJson;
        const std::string &subtitleTracks = cachedSubtitleTracksJson;

        std::ostringstream script;
        script << "window.playerUpdate({duration:" << duration
               << ",position:" << position
               << ",buffered:" << buffered
               << ",paused:" << (paused ? "true" : "false")
               << ",loading:" << (loading ? "true" : "false")
               << ",audioTracks:" << audioTracks
               << ",subtitleTracks:" << subtitleTracks
               << "})";
        std::wstring wideScript = toWide(script.str());
        webView->ExecuteScript(wideScript.c_str(), nullptr);
    }

    // Moves OS keyboard focus off the WebView2 child and onto the app's top-level window. A
    // passive trailer surface must never keep focus, but clicking its chrome (mute/volume) can
    // still focus the WebView2's HWND. Compose's own requestFocus() can't pull Win32 focus back
    // off a live native child, so we do it here at the Win32 level. AttachThreadInput bridges the
    // WebView2/AWT thread boundary so SetFocus is honoured; Kotlin then routes focus to its
    // Compose node (see the "heroTrailerFocusEscaped" event below). Runs on the native UI thread.
    void reclaimHostKeyboardFocus() {
        HWND topLevel = GetAncestor(hostHwnd, GA_ROOT);
        if (!topLevel || !IsWindow(topLevel)) return;
        DWORD thisThread = GetCurrentThreadId();
        DWORD targetThread = GetWindowThreadProcessId(topLevel, nullptr);
        bool attached = false;
        if (targetThread != 0 && targetThread != thisThread) {
            attached = AttachThreadInput(thisThread, targetThread, TRUE) != FALSE;
        }
        SetFocus(topLevel);
        if (attached) {
            AttachThreadInput(thisThread, targetThread, FALSE);
        }
    }

    void handleWebMessage(const std::wstring &messageJson) {
        std::string type = extractJsonString(messageJson, L"type");
        if (type.empty()) return;
        double value = extractJsonNumber(messageJson, L"value", 0.0);

        if (type == "heroTrailerReclaimFocus") {
            // Fired by the trailer chrome (controls.js) when a mute/volume interaction ends.
            // Unstick Win32 focus from the WebView2 here, then let Kotlin place Compose focus.
            // Passive surfaces only — the full-screen player legitimately keeps WebView2 focus.
            if (passiveSurface) {
                reclaimHostKeyboardFocus();
                sendPlayerEvent("heroTrailerFocusEscaped", 0.0);
            }
            return;
        }
        if (type == "controlsReady") {
            controlsWebReady.store(true);
            flushPendingControlsJsonIfReady();
            syncControls();
            return;
        }
        if (type == "selectAudioTrack") {
            selectAudioTrackId((int)std::llround(value));
            syncControls();
            return;
        }
        if (type == "selectSubtitleTrack") {
            selectSubtitleTrackId((int)std::llround(value));
            syncControls();
            return;
        }
        sendPlayerEvent(type, value);
    }

    static std::string extractJsonString(const std::wstring &json, const std::wstring &field) {
        std::wstring key = L"\"" + field + L"\"";
        size_t keyIndex = json.find(key);
        if (keyIndex == std::wstring::npos) return std::string();
        size_t colon = json.find(L':', keyIndex + key.size());
        if (colon == std::wstring::npos) return std::string();
        size_t quote = json.find(L'"', colon + 1);
        if (quote == std::wstring::npos) return std::string();

        std::wstring result;
        bool escaping = false;
        for (size_t index = quote + 1; index < json.size(); index++) {
            wchar_t ch = json[index];
            if (escaping) {
                switch (ch) {
                    case L'"': result.push_back(L'"'); break;
                    case L'\\': result.push_back(L'\\'); break;
                    case L'/': result.push_back(L'/'); break;
                    case L'b': result.push_back(L'\b'); break;
                    case L'f': result.push_back(L'\f'); break;
                    case L'n': result.push_back(L'\n'); break;
                    case L'r': result.push_back(L'\r'); break;
                    case L't': result.push_back(L'\t'); break;
                    default: result.push_back(ch); break;
                }
                escaping = false;
                continue;
            }
            if (ch == L'\\') {
                escaping = true;
                continue;
            }
            if (ch == L'"') break;
            result.push_back(ch);
        }
        return toUtf8(result);
    }

    static double extractJsonNumber(const std::wstring &json, const std::wstring &field, double fallback) {
        std::wstring key = L"\"" + field + L"\"";
        size_t keyIndex = json.find(key);
        if (keyIndex == std::wstring::npos) return fallback;
        size_t colon = json.find(L':', keyIndex + key.size());
        if (colon == std::wstring::npos) return fallback;
        size_t start = json.find_first_not_of(L" \t\r\n", colon + 1);
        if (start == std::wstring::npos) return fallback;
        size_t end = start;
        while (end < json.size()) {
            wchar_t ch = json[end];
            if (!(std::iswdigit(ch) || ch == L'-' || ch == L'+' || ch == L'.' || ch == L'e' || ch == L'E')) {
                break;
            }
            end++;
        }
        if (end <= start) return fallback;
        try {
            return std::stod(json.substr(start, end - start));
        } catch (...) {
            return fallback;
        }
    }

    static bool isHdrContent(const std::string &primaries, const std::string &gamma) {
        if (gamma == "st2084" || gamma == "hlg" || gamma == "arib-std-b67") return true;
        if (primaries == "bt.2020" || primaries == "bt.2020-cl") return true;
        return false;
    }

    // Repeated-message suppressor for the mpv log. Some streams emit the same ffmpeg warning
    // pair for every frame (e.g. "h264: Late SEI is not implemented" on web-DL sources) —
    // roughly ten lines per second for the entire playback, each paying an open/append/close
    // of the log file on this event thread and burying the useful markers. Each distinct
    // message is written a few times, then silenced with a one-time notice. Counting per
    // message (not just consecutive-duplicate collapsing) is deliberate: the warnings arrive
    // as an alternating A/B pair that consecutive dedupe would never catch. Only touched on
    // the mpv event thread. The map is capped as a safety valve — pathological log variety
    // just falls back to writing everything rather than growing without bound.
    bool shouldWriteMpvLogLine(const std::string &line) {
        static constexpr int kRepeatLimit = 5;
        static constexpr size_t kMaxTrackedMessages = 128;
        auto existing = mpvLogLineCounts.find(line);
        if (existing == mpvLogLineCounts.end() && mpvLogLineCounts.size() >= kMaxTrackedMessages) {
            return true;
        }
        int count = (existing == mpvLogLineCounts.end())
            ? (mpvLogLineCounts[line] = 1)
            : ++existing->second;
        if (count < kRepeatLimit) return true;
        if (count == kRepeatLimit) {
            nuvioMpvLogAppend("[nuvio] (suppressing further repeats of the previous message)\n");
        }
        return false;
    }

    void drainMpvEvents() {
        while (!stopping.load()) {
            mpv_handle *current = nullptr;
            {
                std::lock_guard<std::mutex> lock(mpvMutex);
                current = mpv;
            }
            if (!current) {
                return;
            }

            mpv_event *event = mpvApi().waitEvent(current, 0.5);
            if (!event) continue;
            if (event->event_id == MPV_EVENT_SHUTDOWN) {
                return;
            }
            if (event->event_id == MPV_EVENT_LOG_MESSAGE && vsrLogActive && event->data) {
                auto *msg = static_cast<mpv_event_log_message *>(event->data);
                if (msg && msg->prefix && msg->level && msg->text) {
                    std::string line = std::string("[") + msg->level + "] " + msg->prefix + ": " + msg->text;
                    if (shouldWriteMpvLogLine(line)) {
                        nuvioMpvLogAppend(line);
                    }
                }
            }
            if (event->event_id == MPV_EVENT_PLAYBACK_RESTART && playbackRestartPendingForFile) {
                playbackRestartPendingForFile = false;
                sendPlayerEvent("playbackRestart", 1.0);
            }
            if (event->event_id == MPV_EVENT_FILE_LOADED) {
                playbackRestartPendingForFile = true;
                {
                    // A stale seek target from the previous file must not seed relative
                    // seeks issued right after a source switch.
                    std::lock_guard<std::mutex> lock(mpvMutex);
                    pendingSeekTargetSeconds = -1.0;
                }
                sendPlayerEvent("fileLoaded", 1.0);
                subtitleAssOverrideCheckDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
                subtitleAssOverrideInitialCheckPending = true;
                eofSuppressedUntil.store(std::chrono::steady_clock::now() + std::chrono::seconds(3));
                // Ask VPP to scale directly to the embedded surface instead of always using 2x.
                // This lets 720p reach 4K in one NVIDIA VSR pass while avoiding needless work
                // when the source already matches or exceeds the surface dimensions.
                int64_t videoWidth = int64Property("video-params/w", 0);
                int64_t videoHeight = int64Property("video-params/h", 0);
                RECT surfaceBounds{};
                if (videoWidth > 0 && videoHeight > 0 && containerHwnd &&
                    GetClientRect(containerHwnd, &surfaceBounds)) {
                    double surfaceWidth = (double)(surfaceBounds.right - surfaceBounds.left);
                    double surfaceHeight = (double)(surfaceBounds.bottom - surfaceBounds.top);
                    double widthScale = surfaceWidth / (double)videoWidth;
                    double heightScale = surfaceHeight / (double)videoHeight;
                    double vsrScale = std::max(1.0, std::min(4.0, std::min(widthScale, heightScale)));
                    sendPlayerEvent("videoVsrScale", vsrScale);
                    if (vsrLogActive) {
                        nuvioMpvLogAppend("[nuvio] dynamic VSR scale=" + std::to_string(vsrScale) +
                            " source=" + std::to_string(videoWidth) + "x" + std::to_string(videoHeight) +
                            " surface=" + std::to_string((long long)surfaceWidth) + "x" +
                            std::to_string((long long)surfaceHeight) + "\n");
                    }
                }
                if (vsrLogActive) {
                    // Record what actually took effect so we can confirm hwdec + the VSR filter.
                    nuvioMpvLogAppend(std::string("[nuvio] hwdec-current=") + stringProperty("hwdec-current") +
                        " vf=" + stringProperty("vf") +
                        " video-params/w=" + stringProperty("video-params/w") +
                        " dwidth=" + stringProperty("dwidth") + "\n");
                }
            }
            if (event->event_id == MPV_EVENT_PROPERTY_CHANGE && event->data) {
                auto *prop = static_cast<mpv_event_property *>(event->data);
                if (!prop || !prop->name) continue;
                std::string propName(prop->name);
                bool hasValue = prop->format == MPV_FORMAT_STRING && prop->data;
                std::string propValue = hasValue ? std::string(*static_cast<char **>(prop->data)) : "";

                if (propName == "video-params/primaries") {
                    videoParamsPrimaries = hasValue ? propValue : "";
                    videoParamsPrimariesReceived = true;
                } else if (propName == "video-params/gamma") {
                    videoParamsGamma = hasValue ? propValue : "";
                    videoParamsGammaReceived = true;
                }

                if (videoParamsPrimariesReceived && videoParamsGammaReceived) {
                    bool hdr = isHdrContent(videoParamsPrimaries, videoParamsGamma);
                    videoParamsPrimariesReceived = false;
                    videoParamsGammaReceived = false;
                    sendPlayerEvent("videoParams", hdr ? 1.0 : 0.0);
                }
            }
        }
    }

    JNIEnv *jniEnvDidAttach(bool *didAttach) {
        if (didAttach) *didAttach = false;
        if (!javaVm) return nullptr;
        JNIEnv *env = nullptr;
        jint status = javaVm->GetEnv((void **)&env, JNI_VERSION_1_6);
        if (status == JNI_OK) return env;
        if (status != JNI_EDETACHED) return nullptr;
        if (javaVm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK) {
            return nullptr;
        }
        if (didAttach) *didAttach = true;
        return env;
    }

    void sendPlayerEvent(const std::string &type, double value) {
        if (!eventSink || !eventMethod) return;
        bool didAttach = false;
        JNIEnv *env = jniEnvDidAttach(&didAttach);
        if (!env) return;

        jstring eventType = newJavaStringUtf8(env, type);
        env->CallVoidMethod(eventSink, eventMethod, eventType, (jdouble)value);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        if (eventType) {
            env->DeleteLocalRef(eventType);
        }
        if (didAttach) {
            javaVm->DetachCurrentThread();
        }
    }

    bool setMpvOptionStringLocked(const char *name, const char *value) {
        int result = mpvApi().setOptionString(mpv, name, value);
        if (result < 0) {
            std::string message = std::string("[nuvio] mpv option rejected: ") + name + "=" + value +
                " (" + mpvApi().errorText(result) + ")\n";
            OutputDebugStringA(message.c_str());
            return false;
        }
        return true;
    }

    double doubleProperty(const char *name, double fallback) {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return fallback;
        double value = fallback;
        int result = mpvApi().getProperty(mpv, name, MPV_FORMAT_DOUBLE, &value);
        if (result < 0) {
            return fallback;
        }
        return value;
    }

    std::string stringProperty(const char *name) {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return std::string();
        char *value = nullptr;
        int result = mpvApi().getProperty(mpv, name, MPV_FORMAT_STRING, &value);
        if (result < 0 || !value) return std::string();
        std::string out(value);
        if (mpvApi().freeValue) mpvApi().freeValue(value);
        return out;
    }

    long long int64Property(const char *name, long long fallback) {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return fallback;
        int64_t value = fallback;
        int result = mpvApi().getProperty(mpv, name, MPV_FORMAT_INT64, &value);
        if (result < 0) {
            return fallback;
        }
        return value;
    }

    bool flagProperty(const char *name, bool fallback) {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return fallback;
        int flag = fallback ? 1 : 0;
        int result = mpvApi().getProperty(mpv, name, MPV_FORMAT_FLAG, &flag);
        if (result < 0) {
            return fallback;
        }
        return flag != 0;
    }

    std::string stringProperty(const char *name, const std::string &fallback) {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return fallback;
        char *value = nullptr;
        int propertyResult = mpvApi().getProperty(mpv, name, MPV_FORMAT_STRING, &value);
        if (propertyResult < 0 || !value) {
            return fallback;
        }
        std::string result(value);
        mpvApi().freeValue(value);
        return result;
    }

    void setStringProperty(const char *name, const std::string &value) {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return;
        mpvApi().setPropertyString(mpv, name, value.c_str());
    }

    void command(const std::vector<std::string> &args) {
        if (args.empty()) return;
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return;
        std::vector<const char *> cargs;
        cargs.reserve(args.size() + 1);
        for (const std::string &arg : args) {
            cargs.push_back(arg.c_str());
        }
        cargs.push_back(nullptr);
        mpvApi().command(mpv, cargs.data());
    }

    double rawPositionSeconds() {
        double position = doubleProperty("time-pos", 0.0);
        return std::isfinite(position) ? std::max(position, 0.0) : 0.0;
    }

    double effectiveCachePositionSeconds() {
        double position = rawPositionSeconds();
        if (initialStartSeconds > 0.0 && position + 5.0 < initialStartSeconds) {
            return initialStartSeconds;
        }
        return position;
    }

    double cacheAheadSeconds() {
        double effectivePosition = effectiveCachePositionSeconds();
        double cacheTime = doubleProperty("demuxer-cache-time", 0.0);
        if (std::isfinite(cacheTime) && cacheTime > 0.0) {
            if (cacheTime >= effectivePosition - 5.0) {
                return std::max(cacheTime - effectivePosition, 0.0);
            }
            return cacheTime;
        }

        double cacheDuration = doubleProperty("demuxer-cache-duration", 0.0);
        if (std::isfinite(cacheDuration) && cacheDuration > 0.0) {
            return cacheDuration;
        }
        return 0.0;
    }

    void removeExternalSubtitleTracks() {
        long long count = int64Property("track-list/count", 0);
        if (count <= 0) return;
        for (long long index = count - 1; index >= 0; index--) {
            std::string prefix = "track-list/" + std::to_string(index);
            std::string type = stringProperty((prefix + "/type").c_str(), "");
            bool external = flagProperty((prefix + "/external").c_str(), false);
            if (type == "sub" && external) {
                long long trackId = int64Property((prefix + "/id").c_str(), -1);
                if (trackId >= 0) {
                    command({"sub-remove", std::to_string(trackId)});
                }
            }
        }
    }

    std::string tracksJsonForType(const std::string &wantedType) {
        long long count = int64Property("track-list/count", 0);
        std::ostringstream json;
        json << "[";
        int logicalIndex = 0;
        bool first = true;
        for (long long index = 0; index < count; index++) {
            std::string prefix = "track-list/" + std::to_string(index);
            std::string type = stringProperty((prefix + "/type").c_str(), "");
            if (type != wantedType) continue;

            long long trackId = int64Property((prefix + "/id").c_str(), logicalIndex + 1);
            std::string title = trackStringAtIndex(index, "title");
            std::string language = trackStringAtIndex(index, "lang");
            std::string codec = trackStringAtIndex(index, "codec");
            std::string decoderDescription = trackStringAtIndex(index, "decoder-desc");
            std::string channels = trackStringAtIndex(index, "demux-channels");
            long long channelCount = int64Property((prefix + "/demux-channel-count").c_str(), 0);
            bool selected = flagProperty((prefix + "/selected").c_str(), false);
            bool forced = flagProperty((prefix + "/forced").c_str(), false);
            std::string label = formatTrackTitle(type, logicalIndex, title, language, codec, decoderDescription, channels, (int)channelCount);

            if (!first) json << ",";
            first = false;
            json << "{"
                 << "\"index\":" << logicalIndex << ","
                 << "\"id\":\"" << jsonEscape(std::to_string(trackId)) << "\","
                 << "\"label\":\"" << jsonEscape(label) << "\","
                 << "\"language\":\"" << jsonEscape(language) << "\","
                 << "\"selected\":" << (selected ? "true" : "false") << ","
                 << "\"forced\":" << (forced ? "true" : "false")
                 << "}";
            logicalIndex++;
        }
        json << "]";
        return json.str();
    }

    std::string trackStringAtIndex(long long index, const std::string &field) {
        return trim(stringProperty(("track-list/" + std::to_string(index) + "/" + field).c_str(), ""));
    }

    std::string selectedSubtitleCodec() {
        long long count = int64Property("track-list/count", 0);
        for (long long index = 0; index < count; index++) {
            std::string prefix = "track-list/" + std::to_string(index);
            if (stringProperty((prefix + "/type").c_str(), "") != "sub") continue;
            if (!flagProperty((prefix + "/selected").c_str(), false)) continue;
            return trackStringAtIndex(index, "codec");
        }
        return "";
    }

    // ASS/SSA subtitles carry their own positioning and styling (karaoke fills, \fad()/\t()
    // transform animations, absolute \pos() coordinates, PlayResX/PlayResY-relative layout,
    // etc.) that "force" silently discards in favour of the plain sub-* properties below — for
    // real ASS content that can mean broken positioning, blank text, or animation effects
    // rendering as a flat static frame instead of actually animating. "yes" still turned out to
    // interfere with this (confirmed: crossfade/transform effects stayed static even under
    // "yes"), so ASS/SSA now gets "no" — mpv applies zero style overrides and fully trusts the
    // file's own styling/animation. Only force-override for plain-text formats (SRT/VTT) where
    // there is no original styling to lose in the first place.
    //
    // Codec info for an externally added subtitle (sub-add on a URL) isn't necessarily known
    // the instant the command returns — mpv still has to fetch/parse it. Callers invoke this
    // right after track selection as a best-effort immediate attempt, and a one-shot delayed
    // check a few seconds after file load (see subtitleAssOverrideInitialCheckPending) catches
    // whatever track mpv auto-selected on its own. Deliberately NOT a recurring poll — that
    // interfered with seeking/playback start.
    void refreshSubtitleAssOverrideMode() {
        std::string codec = selectedSubtitleCodec();
        bool isAssSubtitle = codec.find("ass") != std::string::npos || codec.find("ssa") != std::string::npos;
        std::string mode = isAssSubtitle ? "no" : "force";
        nuvioBridgeLog("refreshSubtitleAssOverrideMode: codec=\"" + codec + "\" mode=" + mode +
            (mode == appliedSubAssOverrideMode ? " (unchanged)" : " (applying)"));
        if (mode == appliedSubAssOverrideMode) return;
        appliedSubAssOverrideMode = mode;
        setStringProperty("sub-ass-override", mode);
    }

    std::string formatTrackTitle(
        const std::string &type,
        int index,
        const std::string &title,
        const std::string &language,
        const std::string &codec,
        const std::string &decoderDescription,
        const std::string &channels,
        int channelCount
    ) {
        std::string base = !trim(title).empty()
            ? trim(title)
            : (!trim(language).empty()
                ? trim(language)
                : (type == "sub" ? "Subtitle " + std::to_string(index + 1) : "Track " + std::to_string(index + 1)));
        std::string codecName = codecDisplayName(codec);
        if (codecName.empty()) codecName = codecDisplayName(decoderDescription);
        std::string channelName = type == "audio" ? channelLayoutName(channels, channelCount) : "";

        std::vector<std::string> details;
        for (const std::string &detail : {channelName, codecName}) {
            if (!detail.empty() && !containsCaseInsensitive(base, detail)) {
                details.push_back(detail);
            }
        }
        if (details.empty()) return base;
        std::string suffix;
        for (size_t detailIndex = 0; detailIndex < details.size(); detailIndex++) {
            if (detailIndex > 0) suffix += ", ";
            suffix += details[detailIndex];
        }
        return base + " (" + suffix + ")";
    }

    std::string channelLayoutName(const std::string &channels, int channelCount) {
        std::string normalized = trim(channels);
        if (!normalized.empty() && lowerCopy(normalized) != "unknown") {
            std::string lower = lowerCopy(normalized);
            if (lower == "mono") return "Mono";
            if (lower == "stereo") return "Stereo";
            return normalized;
        }
        switch (channelCount) {
            case 1: return "Mono";
            case 2: return "Stereo";
            case 6: return "5.1";
            case 8: return "7.1";
            default: return channelCount > 0 ? std::to_string(channelCount) + "ch" : "";
        }
    }

    std::string codecDisplayName(const std::string &value) {
        std::string raw = trim(value);
        if (raw.empty()) return "";
        std::string codec = lowerCopy(raw);
        if (codec.find("eac3") != std::string::npos || codec.find("e-ac-3") != std::string::npos || codec.find("e ac-3") != std::string::npos) {
            return codec.find("joc") != std::string::npos || codec.find("atmos") != std::string::npos ? "E-AC-3-JOC" : "E-AC-3";
        }
        if (codec.find("truehd") != std::string::npos || codec.find("true hd") != std::string::npos) return "TrueHD";
        if (codec.find("ac3") != std::string::npos || codec.find("ac-3") != std::string::npos) return "AC-3";
        if (codec.find("dts-hd") != std::string::npos || codec.find("dtshd") != std::string::npos || codec.find("dts hd") != std::string::npos) return "DTS-HD";
        if (codec.find("dts") != std::string::npos || codec == "dca") return "DTS";
        if (codec.find("aac") != std::string::npos) return "AAC";
        if (codec.find("mp3") != std::string::npos || codec.find("mpeg audio") != std::string::npos) return "MP3";
        if (codec.find("mp2") != std::string::npos) return "MP2";
        if (codec.find("opus") != std::string::npos) return "Opus";
        if (codec.find("vorbis") != std::string::npos) return "Vorbis";
        if (codec.find("flac") != std::string::npos) return "FLAC";
        if (codec.find("alac") != std::string::npos) return "ALAC";
        if (codec.find("pcm") != std::string::npos || codec.find("wav") != std::string::npos) return "WAV";
        if (codec.find("pgs") != std::string::npos || codec.find("hdmv") != std::string::npos) return "PGS";
        if (codec.find("subrip") != std::string::npos || codec == "srt") return "SRT";
        if (codec.find("ass") != std::string::npos || codec.find("ssa") != std::string::npos) return "SSA";
        if (codec.find("webvtt") != std::string::npos || codec == "vtt") return "VTT";
        if (codec.find("ttml") != std::string::npos) return "TTML";
        if (codec.find("mov_text") != std::string::npos || codec.find("tx3g") != std::string::npos) return "TX3G";
        if (codec.find("dvb") != std::string::npos) return "DVB";
        return raw;
    }
};

LRESULT CALLBACK messageWindowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
    if (message == WM_NCCREATE) {
        auto *create = reinterpret_cast<CREATESTRUCTW *>(lParam);
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(create->lpCreateParams));
        return TRUE;
    }
    auto *player = reinterpret_cast<WindowsMpvWebPlayer *>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    switch (message) {
        case WM_NUVIO_TASK:
            if (player) player->processUiTasks();
            return 0;
        case WM_TIMER:
            if (player && wParam == NUVIO_TIMER_ID) player->onTimer();
            return 0;
        case WM_NCDESTROY:
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
            return 0;
        default:
            return DefWindowProcW(hwnd, message, wParam, lParam);
    }
}

LRESULT CALLBACK containerWindowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
    if (message == WM_NCCREATE) {
        auto *create = reinterpret_cast<CREATESTRUCTW *>(lParam);
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(create->lpCreateParams));
        return TRUE;
    }
    auto *player = reinterpret_cast<WindowsMpvWebPlayer *>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    switch (message) {
        case WM_MOUSEACTIVATE:
            // A passive trailer surface must never steal keyboard focus. Refuse activation so a
            // click on the video (or the mpv child under this container) can't move OS focus off
            // the Compose window. MA_NOACTIVATE still delivers the click to children (the
            // WebView2 mute/volume chrome keeps working); the WebView2's own GotFocus bounce
            // (see startWebView) covers focus it grabs internally on click.
            if (player && player->isPassiveSurface()) {
                return MA_NOACTIVATE;
            }
            return DefWindowProcW(hwnd, message, wParam, lParam);
        case WM_SIZE:
            return 0;
        case WM_ERASEBKGND: {
            RECT rect = {};
            GetClientRect(hwnd, &rect);
            FillRect((HDC)wParam, &rect, (HBRUSH)GetStockObject(BLACK_BRUSH));
            return 1;
        }
        case WM_NCDESTROY:
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
            return 0;
        default:
            return DefWindowProcW(hwnd, message, wParam, lParam);
    }
}

std::shared_ptr<WindowsMpvWebPlayer> playerFromHandle(jlong handle) {
    if (handle == 0) return nullptr;
    auto *holder = reinterpret_cast<std::shared_ptr<WindowsMpvWebPlayer> *>(handle);
    return holder ? *holder : nullptr;
}

} // namespace

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        gModule = module;
        DisableThreadLibraryCalls(module);
        // NOT calling SetDefaultDllDirectories here: it's incompatible with
        // MpvApi::load()'s LoadLibraryExW(..., LOAD_WITH_ALTERED_SEARCH_PATH) call — Windows
        // documents (and this was confirmed the hard way) that once SetDefaultDllDirectories
        // has been called in a process, any subsequent LOAD_WITH_ALTERED_SEARCH_PATH load fails
        // outright with ERROR_INVALID_PARAMETER. That combination previously broke libmpv-2.dll
        // loading entirely (both anime and plain live-action playback). It was added to stop a
        // stray fallback to an unrelated MSYS2 install, which is now handled by simply not
        // offering that fallback path in MpvApi::load()'s own candidate list instead.
    }
    return TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_create(
    JNIEnv *env,
    jobject,
    jlong hostViewPtr,
    jstring sourceUrl,
    jstring sourceAudioUrl,
    jobjectArray headerLines,
    jboolean playWhenReady,
    jlong initialPositionMs,
    jstring controlsPageUrl,
    jboolean nvidiaRtxSuperResolutionEnabled,
    jboolean nvidiaRtxHdrEnabled,
    jstring animeSvpFilter,
    jobjectArray extraMpvOptions,
    jobject eventSink
) {
    HWND hostHwnd = (HWND)(intptr_t)hostViewPtr;
    std::string sourceUrlText = jstringToUtf8(env, sourceUrl);
    std::string sourceAudioUrlText = sourceAudioUrl ? jstringToUtf8(env, sourceAudioUrl) : std::string();
    std::vector<std::string> headerLineValues = jstringArrayToVector(env, headerLines);
    std::string controlsPageUrlText = jstringToUtf8(env, controlsPageUrl);
    std::string animeSvpFilterText = animeSvpFilter ? jstringToUtf8(env, animeSvpFilter) : std::string();
    std::vector<std::string> extraMpvOptionsValues = jstringArrayToVector(env, extraMpvOptions);
    JavaVM *javaVm = nullptr;
    env->GetJavaVM(&javaVm);

    jobject eventSinkRef = nullptr;
    jmethodID eventMethod = nullptr;
    if (eventSink) {
        eventSinkRef = env->NewGlobalRef(eventSink);
        jclass eventSinkClass = env->GetObjectClass(eventSink);
        eventMethod = env->GetMethodID(eventSinkClass, "onPlayerEvent", "(Ljava/lang/String;D)V");
        env->DeleteLocalRef(eventSinkClass);
        if (!eventMethod) {
            if (eventSinkRef) env->DeleteGlobalRef(eventSinkRef);
            throwJavaError(env, "Native player event sink is missing onPlayerEvent(String, Double).");
            return 0;
        }
    }

    auto player = std::make_shared<WindowsMpvWebPlayer>();
    try {
        player->initialize(
            hostHwnd,
            sourceUrlText,
            sourceAudioUrlText,
            headerLineValues,
            playWhenReady == JNI_TRUE,
            initialPositionMs,
            controlsPageUrlText,
            javaVm,
            nvidiaRtxSuperResolutionEnabled == JNI_TRUE,
            nvidiaRtxHdrEnabled == JNI_TRUE,
            animeSvpFilterText,
            extraMpvOptionsValues,
            eventSinkRef,
            eventMethod
        );
    } catch (const std::exception &error) {
        player->shutdown();
        throwJavaError(env, error.what());
        return 0;
    }

    auto *holder = new std::shared_ptr<WindowsMpvWebPlayer>(player);
    jlong handle = (jlong)(intptr_t)holder;
    return handle;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_warmupWebView2(JNIEnv *env, jobject, jstring controlsPageUrl) {
    std::string controlsPageUrlText = jstringToUtf8(env, controlsPageUrl);
    return startWebView2Warmup(controlsPageUrlText) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_shutdownWebView2Warmup(JNIEnv *, jobject) {
    stopWebView2Warmup();
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_dispose(JNIEnv *, jobject, jlong handle) {
    if (handle == 0) return;
    auto *holder = reinterpret_cast<std::shared_ptr<WindowsMpvWebPlayer> *>(handle);
    std::shared_ptr<WindowsMpvWebPlayer> player = *holder;
    delete holder;
    if (player) player->shutdown();
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_updateControls(JNIEnv *env, jobject, jlong handle, jstring controlsJson) {
    auto player = playerFromHandle(handle);
    std::string controlsJsonText = jstringToUtf8(env, controlsJson);
    if (player) player->updateControlsJson(controlsJsonText);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_runJavaScript(JNIEnv *env, jobject, jlong handle, jstring script) {
    auto player = playerFromHandle(handle);
    std::string scriptText = jstringToUtf8(env, script);
    if (player) player->runJavaScript(scriptText);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setCursorHidden(JNIEnv *, jobject, jlong handle, jboolean hidden) {
    auto player = playerFromHandle(handle);
    if (player) player->setCursorHidden(hidden == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setPaused(JNIEnv *, jobject, jlong handle, jboolean paused) {
    auto player = playerFromHandle(handle);
    if (player) player->setPaused(paused == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_seekTo(JNIEnv *, jobject, jlong handle, jlong positionMs) {
    auto player = playerFromHandle(handle);
    if (player) player->seekToMilliseconds(positionMs);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_seekBy(JNIEnv *, jobject, jlong handle, jlong offsetMs) {
    auto player = playerFromHandle(handle);
    if (player) player->seekByMilliseconds(offsetMs);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setSpeed(JNIEnv *, jobject, jlong handle, jfloat speed) {
    auto player = playerFromHandle(handle);
    if (player) player->setSpeed(speed);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_durationMs(JNIEnv *, jobject, jlong handle) {
    auto player = playerFromHandle(handle);
    return player ? player->durationMs() : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_positionMs(JNIEnv *, jobject, jlong handle) {
    auto player = playerFromHandle(handle);
    return player ? player->positionMs() : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_bufferedPositionMs(JNIEnv *, jobject, jlong handle) {
    auto player = playerFromHandle(handle);
    return player ? player->bufferedPositionMs() : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_isLoading(JNIEnv *, jobject, jlong handle) {
    auto player = playerFromHandle(handle);
    return player && player->isLoading() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_isEnded(JNIEnv *, jobject, jlong handle) {
    auto player = playerFromHandle(handle);
    return player && player->isEnded() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_isPaused(JNIEnv *, jobject, jlong handle) {
    auto player = playerFromHandle(handle);
    return !player || player->isPaused() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_speed(JNIEnv *, jobject, jlong handle) {
    auto player = playerFromHandle(handle);
    return player ? (jfloat)player->speed() : 1.0f;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setVolume(JNIEnv *, jobject, jlong handle, jfloat volume) {
    auto player = playerFromHandle(handle);
    if (player) player->setVolume(volume);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_volume(JNIEnv *, jobject, jlong handle) {
    auto player = playerFromHandle(handle);
    return player ? (jfloat)player->volume() : 100.0f;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setMute(JNIEnv *, jobject, jlong handle, jboolean muted) {
    auto player = playerFromHandle(handle);
    if (player) player->setMute(muted == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_isMuted(JNIEnv *, jobject, jlong handle) {
    auto player = playerFromHandle(handle);
    return player && player->isMuted() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setResizeMode(JNIEnv *, jobject, jlong handle, jint mode) {
    auto player = playerFromHandle(handle);
    if (player) player->setResizeMode(mode);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_audioTracksJson(JNIEnv *env, jobject, jlong handle) {
    auto player = playerFromHandle(handle);
    return newJavaStringUtf8(env, player ? player->audioTracksJson() : "[]");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_subtitleTracksJson(JNIEnv *env, jobject, jlong handle) {
    auto player = playerFromHandle(handle);
    return newJavaStringUtf8(env, player ? player->subtitleTracksJson() : "[]");
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_selectAudioTrack(JNIEnv *, jobject, jlong handle, jint trackId) {
    auto player = playerFromHandle(handle);
    if (player) player->selectAudioTrackId(trackId);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_selectSubtitleTrack(JNIEnv *, jobject, jlong handle, jint trackId) {
    auto player = playerFromHandle(handle);
    if (player) player->selectSubtitleTrackId(trackId);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_addSubtitleUrl(JNIEnv *env, jobject, jlong handle, jstring url) {
    auto player = playerFromHandle(handle);
    if (player) player->addSubtitleUrl(jstringToUtf8(env, url));
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_clearExternalSubtitles(JNIEnv *, jobject, jlong handle) {
    auto player = playerFromHandle(handle);
    if (player) player->removeExternalSubtitles();
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_clearExternalSubtitlesAndSelect(JNIEnv *, jobject, jlong handle, jint trackId) {
    auto player = playerFromHandle(handle);
    if (player) player->removeExternalSubtitlesAndSelect(trackId);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_applyWindowChrome(
    JNIEnv *,
    jobject,
    jlong windowHwnd,
    jboolean darkMode,
    jint captionColorRgb,
    jint borderColorRgb,
    jint textColorRgb
) {
    applyDwmWindowChrome(
        (HWND)(intptr_t)windowHwnd,
        darkMode == JNI_TRUE,
        rgbIntToColorRef(captionColorRgb),
        rgbIntToColorRef(borderColorRgb),
        rgbIntToColorRef(textColorRgb)
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setBorderlessFullscreen(JNIEnv *, jobject, jlong windowHwnd, jboolean enabled) {
    setBorderlessFullscreen((HWND)(intptr_t)windowHwnd, enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setSubtitleDelayMs(JNIEnv *, jobject, jlong handle, jint delayMs) {
    auto player = playerFromHandle(handle);
    if (player) player->setSubtitleDelayMs(delayMs);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_applySubtitleStyle(
    JNIEnv *env,
    jobject,
    jlong handle,
    jstring textColor,
    jstring backgroundColor,
    jstring outlineColor,
    jfloat outlineSize,
    jboolean bold,
    jfloat fontSize,
    jint subPos,
    jstring fontName
) {
    auto player = playerFromHandle(handle);
    if (!player) return;
    player->applySubtitleStyle(
        jstringToUtf8(env, textColor),
        jstringToUtf8(env, backgroundColor),
        jstringToUtf8(env, outlineColor),
        outlineSize,
        bold == JNI_TRUE,
        fontSize,
        subPos,
        fontName ? jstringToUtf8(env, fontName) : std::string()
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setMpvProperty(
    JNIEnv *env,
    jobject,
    jlong handle,
    jstring key,
    jstring value
) {
    auto player = playerFromHandle(handle);
    if (!player) return;
    player->setMpvPropertyString(jstringToUtf8(env, key), jstringToUtf8(env, value));
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_forceVideoRedraw(
    JNIEnv *,
    jobject,
    jlong handle
) {
    auto player = playerFromHandle(handle);
    if (!player) return;
    player->forceVideoRedraw();
}
