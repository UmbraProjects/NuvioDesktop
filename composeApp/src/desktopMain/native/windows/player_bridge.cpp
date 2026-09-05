#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <dwmapi.h>
#include <dxgi1_6.h>
#include <commctrl.h>
#include <propkey.h>
#include <propvarutil.h>
#include <roapi.h>
#include <shlobj.h>
#include <winstring.h>
#include <windows.foundation.h>
#include <windows.storage.streams.h>
#include <wrl.h>
#include <wrl/event.h>
#include <wrl/wrappers/corewrappers.h>
#include <windows.media.h>
#include <systemmediatransportcontrolsinterop.h>
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
#include <fstream>
#include <iomanip>
#include <iterator>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_set>
#include <vector>

using Microsoft::WRL::Callback;
using Microsoft::WRL::ComPtr;
using Microsoft::WRL::Wrappers::HStringReference;

namespace WMedia = ABI::Windows::Media;

// The System Media Transport Controls session. This is what makes the keyboard's
// play/pause/next/prev keys reach Nuvio while the app is in the BACKGROUND: Windows owns the
// keys and routes them to whichever process is the active media session, so unlike an AWT
// KeyEventDispatcher (focus-only) or RegisterHotKey (an exclusive grab that would steal the
// keys from Spotify et al.) this cooperates with the rest of the system. It also puts Nuvio in
// the volume-flyout media widget.
typedef ABI::Windows::Foundation::ITypedEventHandler<
    WMedia::SystemMediaTransportControls *,
    WMedia::SystemMediaTransportControlsButtonPressedEventArgs *>
    SystemMediaButtonPressedHandler;

// SMTC only ever routes the keys to ONE session process-wide, and Windows picks it — a
// foreground browser with a paused YouTube tab will win it off a paused Nuvio, after which
// Nuvio can never be resumed by media key (it can't become "current" without playing, and it
// can't play without the key). So the session is backed by a WM_APPCOMMAND subclass on the
// top-level window: whenever Nuvio has focus the keys are handled here unconditionally, and
// consuming the message stops it reaching the shell, so SMTC can't also fire for the same press.
constexpr UINT_PTR kMediaKeySubclassId = 1;

// ISystemMediaTransportControlsInterop::GetForWindow returns a per-window SINGLETON, and the
// WM_APPCOMMAND subclass is likewise keyed by (window, id). Source failover and binge autoplay
// tear down and recreate players every few seconds against the same top-level window, so a
// departing player's cleanup would otherwise disable the session — and unhook the subclass —
// that its replacement had already installed. Only the current owner may tear either down.
std::mutex gSystemMediaOwnerMutex;
const void *gSystemMediaOwner = nullptr;

// Windows resolves the name and icon shown in the media flyout ("Unknown app" otherwise) from
// the process AppUserModelID, which it looks up against Start Menu shortcuts carrying a matching
// System.AppUserModel.ID. A plain shortcut is NOT enough — verified on a machine where a
// shortcut pointing at the running exe already existed and the flyout still said "Unknown app".
const wchar_t *const kNuvioAppUserModelId = L"Nuvio.Desktop";

extern "C" {
typedef struct mpv_handle mpv_handle;

typedef enum mpv_format {
    MPV_FORMAT_NONE = 0,
    MPV_FORMAT_STRING = 1,
    MPV_FORMAT_OSD_STRING = 2,
    MPV_FORMAT_FLAG = 3,
    MPV_FORMAT_INT64 = 4,
    MPV_FORMAT_DOUBLE = 5,
    MPV_FORMAT_NODE = 6,
    MPV_FORMAT_NODE_ARRAY = 7,
    MPV_FORMAT_NODE_MAP = 8,
} mpv_format;

// ABI declarations from libmpv client.h; node contents are freed by libmpv.
typedef struct mpv_node {
    union { char *string; int flag; int64_t int64; double double_; struct mpv_node_list *list; void *ba; } u;
    mpv_format format;
} mpv_node;
typedef struct mpv_node_list { int num; mpv_node *values; char **keys; } mpv_node_list;

typedef enum mpv_event_id {
    MPV_EVENT_NONE = 0,
    MPV_EVENT_SHUTDOWN = 1,
    MPV_EVENT_LOG_MESSAGE = 2,
    MPV_EVENT_END_FILE = 7,
    MPV_EVENT_FILE_LOADED = 8,
    MPV_EVENT_START_FILE = 6,
    MPV_EVENT_VIDEO_RECONFIG = 17,
    MPV_EVENT_AUDIO_RECONFIG = 18,
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

typedef enum mpv_end_file_reason {
    MPV_END_FILE_REASON_EOF = 0,
    MPV_END_FILE_REASON_STOP = 2,
    MPV_END_FILE_REASON_QUIT = 3,
    MPV_END_FILE_REASON_ERROR = 4,
    MPV_END_FILE_REASON_REDIRECT = 5,
} mpv_end_file_reason;

typedef struct mpv_event_end_file {
    int reason;
    int error;
    int64_t playlist_entry_id;
    int64_t playlist_insert_id;
    int playlist_insert_num_entries;
} mpv_event_end_file;

typedef struct mpv_event {
    mpv_event_id event_id;
    int error;
    uint64_t reply_userdata;
    void *data;
} mpv_event;
}

namespace {

std::string base64Encode(const std::vector<unsigned char> &bytes) {
    static constexpr char alphabet[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string output;
    output.reserve(((bytes.size() + 2) / 3) * 4);
    for (size_t index = 0; index < bytes.size(); index += 3) {
        uint32_t block = (uint32_t)bytes[index] << 16;
        if (index + 1 < bytes.size()) block |= (uint32_t)bytes[index + 1] << 8;
        if (index + 2 < bytes.size()) block |= bytes[index + 2];
        output.push_back(alphabet[(block >> 18) & 0x3f]);
        output.push_back(alphabet[(block >> 12) & 0x3f]);
        output.push_back(index + 1 < bytes.size() ? alphabet[(block >> 6) & 0x3f] : '=');
        output.push_back(index + 2 < bytes.size() ? alphabet[block & 0x3f] : '=');
    }
    return output;
}

HMODULE gModule = nullptr;
constexpr UINT WM_NUVIO_TASK = WM_APP + 0x4E50;
constexpr UINT_PTR NUVIO_TIMER_ID = 0x4E50;

// Diagnostic mpv logs live beside nuvio.log in %LOCALAPPDATA%\NuvioHTPC\logs.
// Keep five player sessions in total: the active log plus four previous logs. Normal
// playback writes only bridge lifecycle markers and mpv warnings/errors; full verbose
// mpv output requires RTX VSR/HDR to be active or the NUVIO_MPV_VERBOSE environment
// variable (see the requestLogMessages call in startMpv).
constexpr int NUVIO_MPV_LOG_COUNT = 5;
std::mutex gPlaybackLogMutex;
std::wstring gLatestMainPlaybackLog;
std::atomic<unsigned long long> gPlaybackSessionSequence{0};
thread_local std::wstring gPlaybackThreadLog;


std::wstring nuvioMpvLogDirectory() {
    wchar_t localAppData[32768] = {};
    DWORD length = GetEnvironmentVariableW(L"LOCALAPPDATA", localAppData,
                                            (DWORD)(sizeof(localAppData) / sizeof(localAppData[0])));
    std::wstring root;
    if (length > 0 && length < sizeof(localAppData) / sizeof(localAppData[0])) {
        root.assign(localAppData, length);
    } else {
        wchar_t userProfile[32768] = {};
        length = GetEnvironmentVariableW(L"USERPROFILE", userProfile,
                                         (DWORD)(sizeof(userProfile) / sizeof(userProfile[0])));
        if (length == 0 || length >= sizeof(userProfile) / sizeof(userProfile[0])) return {};
        root.assign(userProfile, length);
        root += L"\\AppData\\Local";
    }

    const std::wstring appDirectory = root + L"\\NuvioHTPC";
    const std::wstring logDirectory = appDirectory + L"\\logs";
    CreateDirectoryW(appDirectory.c_str(), nullptr);
    CreateDirectoryW(logDirectory.c_str(), nullptr);
    return logDirectory;
}

std::wstring nuvioMpvLogPath(int backupIndex = 0) {
    const std::wstring directory = nuvioMpvLogDirectory();
    if (directory.empty()) return {};
    std::wstring path = directory + L"\\nuvio-mpv.log";
    if (backupIndex > 0) path += L"." + std::to_wstring(backupIndex);
    return path;
}

void nuvioMpvLogReset(const std::wstring &sessionPath) {
    std::lock_guard<std::mutex> lock(gPlaybackLogMutex);
    gLatestMainPlaybackLog = sessionPath;
    // Rotate once per native player initialization. The session that just ended becomes .1,
    // which preserves its failure evidence even when autoplay immediately creates a new player.
    const std::wstring oldest = nuvioMpvLogPath(NUVIO_MPV_LOG_COUNT - 1);
    if (oldest.empty()) return;
    DeleteFileW(oldest.c_str());
    for (int index = NUVIO_MPV_LOG_COUNT - 2; index >= 0; --index) {
        const std::wstring source = nuvioMpvLogPath(index);
        const std::wstring destination = nuvioMpvLogPath(index + 1);
        MoveFileExW(source.c_str(), destination.c_str(), MOVEFILE_REPLACE_EXISTING);
    }

    const std::wstring path = nuvioMpvLogPath();
    FILE *f = nullptr;
    if (_wfopen_s(&f, path.c_str(), L"w") == 0 && f) fclose(f);
}

void nuvioMpvLogAppend(const std::string &line, const std::wstring &sessionPath = {}) {
    std::lock_guard<std::mutex> lock(gPlaybackLogMutex);
    const std::wstring explicitPath = sessionPath.empty() ? gPlaybackThreadLog : sessionPath;
    const std::wstring path = explicitPath.empty() ? nuvioMpvLogPath() : explicitPath;
    if (path.empty()) return;
    // mpv includes complete media URLs in verbose open/seek messages. Provider URLs commonly
    // contain debrid tokens or signed paths, and this log directory is intended to be shared for
    // support, so retain the host for diagnosis but never persist the path/query credentials.
    std::string safeLine;
    safeLine.reserve(line.size());
    size_t cursor = 0;
    while (cursor < line.size()) {
        const size_t http = line.find("http://", cursor);
        const size_t https = line.find("https://", cursor);
        const size_t urlStart = http == std::string::npos ? https
            : https == std::string::npos ? http
            : std::min(http, https);
        if (urlStart == std::string::npos) {
            safeLine.append(line, cursor, std::string::npos);
            break;
        }
        safeLine.append(line, cursor, urlStart - cursor);
        const size_t authorityStart = urlStart + (line.compare(urlStart, 8, "https://") == 0 ? 8 : 7);
        size_t authorityEnd = line.find_first_of("/?# \t\r\n\"'<>(){}", authorityStart);
        if (authorityEnd == std::string::npos) authorityEnd = line.size();
        const size_t userInfoEnd = line.rfind('@', authorityEnd);
        const size_t hostStart = userInfoEnd != std::string::npos && userInfoEnd >= authorityStart
            ? userInfoEnd + 1
            : authorityStart;
        safeLine.append(line, urlStart, authorityStart - urlStart);
        safeLine.append(line, hostStart, authorityEnd - hostStart);
        safeLine += "/<redacted>";
        size_t urlEnd = line.find_first_of(" \t\r\n\"'<>[](){}", authorityEnd);
        cursor = urlEnd == std::string::npos ? line.size() : urlEnd;
    }
    auto append = [&](const std::wstring &target) {
        FILE *f = nullptr;
        if (_wfopen_s(&f, target.c_str(), L"a") == 0 && f) {
            fwrite(safeLine.data(), 1, safeLine.size(), f);
            fclose(f);
        }
    };
    append(path);
    // Keep the familiar current log, but trailers and outgoing sessions never write into it.
    if (!explicitPath.empty() && explicitPath == gLatestMainPlaybackLog) append(nuvioMpvLogPath());
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

// Persistent gpu-next shader cache directory (%LOCALAPPDATA%\NuvioHTPC\shader-cache), returned as
// UTF-8 for mpv's option string. Because startMpv sets config=no, mpv has no config directory and
// therefore resolves no default shader-cache location — so every launch was recompiling the whole
// gpu-next pipeline (scalers, deband, and any active Anime4K / custom GLSL shaders) from HLSL to
// DXBC, the "(slow!)" translations that stall the first seconds of playback. Pointing mpv at a
// stable per-user directory makes those compiles a one-time cost that is reused on later launches.
std::string nuvioMpvShaderCacheDirectoryUtf8() {
    const std::wstring logDirectory = nuvioMpvLogDirectory();
    if (logDirectory.empty()) return {};
    // logDirectory is ...\NuvioHTPC\logs; place the cache as a sibling ...\NuvioHTPC\shader-cache.
    const std::wstring::size_type separator = logDirectory.find_last_of(L'\\');
    if (separator == std::wstring::npos) return {};
    const std::wstring cacheDirectory = logDirectory.substr(0, separator) + L"\\shader-cache";
    CreateDirectoryW(cacheDirectory.c_str(), nullptr);
    return toUtf8(cacheDirectory);
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

struct SavedWindowStyle {
    bool valid = false;
    HWND hwnd = nullptr;
    LONG_PTR style = 0;
};

SavedWindowStyle g_compactPlayerWindowSaved;

struct CompactWindowInteraction {
    HWND hwnd = nullptr;
    RECT initialRect{};
    POINT initialPointer{};
    int edge = 0; // 0 moves; 1..8 use the same clockwise edge mapping as the controls UI.
    bool active = false;
};

CompactWindowInteraction g_compactWindowInteraction;
std::mutex g_compactWindowInteractionMutex;

void endCompactPlayerWindowInteraction(HWND hwnd) {
    std::lock_guard<std::mutex> lock(g_compactWindowInteractionMutex);
    if (hwnd && g_compactWindowInteraction.hwnd != hwnd) return;
    g_compactWindowInteraction = CompactWindowInteraction{};
}

void beginCompactPlayerWindowInteraction(HWND hwnd, int edge) {
    if (!hwnd || !IsWindow(hwnd) || !g_compactPlayerWindowSaved.valid || edge < 0 || edge > 8) return;

    RECT rect{};
    POINT pointer{};
    if (!GetWindowRect(hwnd, &rect) || !GetCursorPos(&pointer)) return;

    std::lock_guard<std::mutex> lock(g_compactWindowInteractionMutex);
    g_compactWindowInteraction.hwnd = hwnd;
    g_compactWindowInteraction.initialRect = rect;
    g_compactWindowInteraction.initialPointer = pointer;
    g_compactWindowInteraction.edge = edge;
    g_compactWindowInteraction.active = true;
}

void updateCompactPlayerWindowInteraction(HWND hwnd) {
    POINT pointer{};
    if (!GetCursorPos(&pointer)) return;

    std::lock_guard<std::mutex> lock(g_compactWindowInteractionMutex);
    const CompactWindowInteraction &interaction = g_compactWindowInteraction;
    if (!interaction.active || interaction.hwnd != hwnd || !IsWindow(hwnd)) return;

    const int dx = pointer.x - interaction.initialPointer.x;
    const int dy = pointer.y - interaction.initialPointer.y;
    RECT next = interaction.initialRect;
    if (interaction.edge == 0) {
        OffsetRect(&next, dx, dy);
    } else {
        if (interaction.edge == 1 || interaction.edge == 2 || interaction.edge == 8) next.top += dy;
        if (interaction.edge == 2 || interaction.edge == 3 || interaction.edge == 4) next.right += dx;
        if (interaction.edge == 4 || interaction.edge == 5 || interaction.edge == 6) next.bottom += dy;
        if (interaction.edge == 6 || interaction.edge == 7 || interaction.edge == 8) next.left += dx;

        constexpr LONG minimumWidth = 320;
        constexpr LONG minimumHeight = 180;
        if (next.right - next.left < minimumWidth) {
            if (interaction.edge == 6 || interaction.edge == 7 || interaction.edge == 8) {
                next.left = next.right - minimumWidth;
            } else {
                next.right = next.left + minimumWidth;
            }
        }
        if (next.bottom - next.top < minimumHeight) {
            if (interaction.edge == 1 || interaction.edge == 2 || interaction.edge == 8) {
                next.top = next.bottom - minimumHeight;
            } else {
                next.bottom = next.top + minimumHeight;
            }
        }
    }

    SetWindowPos(
        hwnd,
        nullptr,
        next.left,
        next.top,
        next.right - next.left,
        next.bottom - next.top,
        SWP_NOZORDER | SWP_NOOWNERZORDER | SWP_NOACTIVATE
    );
}

void setCompactPlayerWindow(HWND hwnd, bool enable) {
    if (!hwnd || !IsWindow(hwnd)) return;

    if (enable) {
        if (g_compactPlayerWindowSaved.valid) return;
        g_compactPlayerWindowSaved.hwnd = hwnd;
        g_compactPlayerWindowSaved.style = GetWindowLongPtrW(hwnd, GWL_STYLE);
        g_compactPlayerWindowSaved.valid = true;
        LONG_PTR style = g_compactPlayerWindowSaved.style &
            ~(WS_CAPTION | WS_THICKFRAME | WS_MINIMIZEBOX | WS_MAXIMIZEBOX | WS_SYSMENU);
        SetWindowLongPtrW(hwnd, GWL_STYLE, style);
    } else {
        if (!g_compactPlayerWindowSaved.valid || g_compactPlayerWindowSaved.hwnd != hwnd) return;
        SetWindowLongPtrW(hwnd, GWL_STYLE, g_compactPlayerWindowSaved.style);
        g_compactPlayerWindowSaved = SavedWindowStyle{};
        endCompactPlayerWindowInteraction(hwnd);
    }

    SetWindowPos(
        hwnd,
        nullptr,
        0,
        0,
        0,
        0,
        SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOOWNERZORDER | SWP_FRAMECHANGED
    );
}

void beginCompactPlayerWindowMove(HWND hwnd) {
    beginCompactPlayerWindowInteraction(hwnd, 0);
}

void beginCompactPlayerWindowResize(HWND hwnd, int edge) {
    beginCompactPlayerWindowInteraction(hwnd, edge);
}

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

        // WS_MAXIMIZE goes too, not just the frame bits: Windows keeps re-constraining a window
        // that still carries the maximized state to the monitor's work area, so entering
        // fullscreen from a maximized window would leave the taskbar strip uncovered along one
        // edge no matter what rect is passed below. The saved style is restored on exit, which
        // puts the maximized state back.
        LONG_PTR newStyle = g_borderlessFullscreenSaved.style & ~(WS_CAPTION | WS_THICKFRAME | WS_MINIMIZEBOX | WS_MAXIMIZEBOX | WS_SYSMENU | WS_MAXIMIZE);
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
    using mpv_free_node_contents_fn = void (*)(mpv_node *);
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
    mpv_free_node_contents_fn freeNodeContents = nullptr;
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
        freeNodeContents = loadSymbol<mpv_free_node_contents_fn>("mpv_free_node_contents");
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
#ifndef NUVIO_PLAYBACK_STARTUP_TEST
    api.ensureLoaded();
#endif
    return api;
}

class WindowsMpvWebPlayer;
LRESULT CALLBACK messageWindowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam);
LRESULT CALLBACK containerWindowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam);
LRESULT CALLBACK mediaKeyRootSubclassProc(
    HWND hwnd,
    UINT message,
    WPARAM wParam,
    LPARAM lParam,
    UINT_PTR idSubclass,
    DWORD_PTR refData
);

std::wstring currentExecutablePath() {
    wchar_t buffer[32768] = {};
    const DWORD length = GetModuleFileNameW(nullptr, buffer, ARRAYSIZE(buffer));
    if (length == 0 || length >= ARRAYSIZE(buffer)) return {};
    return std::wstring(buffer, length);
}

std::wstring startMenuShortcutPath() {
    PWSTR programs = nullptr;
    if (FAILED(SHGetKnownFolderPath(FOLDERID_Programs, 0, nullptr, &programs)) || !programs) {
        return {};
    }
    std::wstring path(programs);
    CoTaskMemFree(programs);
    return path + L"\\Nuvio.lnk";
}

bool setShortcutAumid(IShellLinkW *link) {
    ComPtr<IPropertyStore> store;
    if (FAILED(link->QueryInterface(IID_PPV_ARGS(&store)))) return false;
    PROPVARIANT value;
    if (FAILED(InitPropVariantFromString(kNuvioAppUserModelId, &value))) return false;
    const bool ok = SUCCEEDED(store->SetValue(PKEY_AppUserModel_ID, value)) &&
                    SUCCEEDED(store->Commit());
    PropVariantClear(&value);
    return ok;
}

// Reports whether the shortcut points at this exe, and whether it already carries our AUMID, so
// a normal launch rewrites nothing. Rewriting matters: touching the Start Menu entry re-flags
// Nuvio as "recently added", and touching a taskbar pin risks disturbing the pin itself.
bool inspectShortcut(
    const std::wstring &linkPath,
    const std::wstring &exePath,
    bool *targetsUs,
    bool *hasOurAumid
) {
    *targetsUs = false;
    *hasOurAumid = false;

    ComPtr<IShellLinkW> link;
    if (FAILED(CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&link)))) {
        return false;
    }
    ComPtr<IPersistFile> file;
    if (FAILED(link.As(&file)) || FAILED(file->Load(linkPath.c_str(), STGM_READ))) return false;

    wchar_t target[MAX_PATH] = {};
    if (FAILED(link->GetPath(target, ARRAYSIZE(target), nullptr, 0))) return false;
    *targetsUs = _wcsicmp(target, exePath.c_str()) == 0;

    ComPtr<IPropertyStore> store;
    if (SUCCEEDED(link.As(&store))) {
        PROPVARIANT value;
        PropVariantInit(&value);
        if (SUCCEEDED(store->GetValue(PKEY_AppUserModel_ID, &value)) && value.vt == VT_LPWSTR) {
            *hasOurAumid = value.pwszVal && wcscmp(value.pwszVal, kNuvioAppUserModelId) == 0;
        }
        PropVariantClear(&value);
    }
    return true;
}

// Stamps our AUMID onto an EXISTING shortcut that already launches this exe, leaving everything
// else about it untouched. Required because once the process declares an explicit AUMID, Windows
// only associates a launcher with the running window when the shortcut declares the same one —
// otherwise a pinned taskbar icon spawns a second, separate taskbar entry.
bool stampExistingShortcut(const std::wstring &linkPath, const std::wstring &exePath) {
    bool targetsUs = false;
    bool hasOurAumid = false;
    if (!inspectShortcut(linkPath, exePath, &targetsUs, &hasOurAumid)) return false;
    if (!targetsUs || hasOurAumid) return false;

    ComPtr<IShellLinkW> link;
    if (FAILED(CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&link)))) {
        return false;
    }
    ComPtr<IPersistFile> file;
    if (FAILED(link.As(&file)) || FAILED(file->Load(linkPath.c_str(), STGM_READWRITE))) return false;
    if (!setShortcutAumid(link.Get())) return false;
    return SUCCEEDED(file->Save(linkPath.c_str(), TRUE));
}

// Never creates shortcuts here — only repairs ones the user already made. Nuvio has no business
// adding itself to a taskbar or desktop the user didn't ask it to.
int stampShortcutsInDirectory(const std::wstring &directory, const std::wstring &exePath) {
    if (directory.empty()) return 0;
    WIN32_FIND_DATAW found = {};
    HANDLE search = FindFirstFileW((directory + L"\\*.lnk").c_str(), &found);
    if (search == INVALID_HANDLE_VALUE) return 0;
    int stamped = 0;
    do {
        if (found.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) continue;
        if (stampExistingShortcut(directory + L"\\" + found.cFileName, exePath)) stamped++;
    } while (FindNextFileW(search, &found));
    FindClose(search);
    return stamped;
}

std::wstring knownFolder(REFKNOWNFOLDERID id) {
    PWSTR path = nullptr;
    if (FAILED(SHGetKnownFolderPath(id, 0, nullptr, &path)) || !path) return {};
    std::wstring result(path);
    CoTaskMemFree(path);
    return result;
}

// The taskbar pin store has no KNOWNFOLDERID of its own.
std::wstring taskbarPinnedDirectory() {
    const std::wstring appData = knownFolder(FOLDERID_RoamingAppData);
    if (appData.empty()) return {};
    return appData + L"\\Microsoft\\Internet Explorer\\Quick Launch\\User Pinned\\TaskBar";
}

// Rewritten (not just created) whenever the target drifts, so moving a portable copy self-heals
// on next launch instead of leaving a shortcut aimed at a path that no longer exists.
bool ensureStartMenuShortcut(const std::wstring &exePath) {
    const std::wstring linkPath = startMenuShortcutPath();
    if (linkPath.empty()) return false;

    bool targetsUs = false;
    bool hasOurAumid = false;
    if (inspectShortcut(linkPath, exePath, &targetsUs, &hasOurAumid) && targetsUs && hasOurAumid) {
        return true;
    }

    ComPtr<IShellLinkW> link;
    if (FAILED(CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&link)))) {
        return false;
    }
    link->SetPath(exePath.c_str());
    link->SetIconLocation(exePath.c_str(), 0);
    link->SetDescription(L"Nuvio");
    const size_t slash = exePath.find_last_of(L"\\/");
    if (slash != std::wstring::npos) {
        link->SetWorkingDirectory(exePath.substr(0, slash).c_str());
    }
    setShortcutAumid(link.Get());

    ComPtr<IPersistFile> file;
    if (FAILED(link.As(&file))) return false;
    return SUCCEEDED(file->Save(linkPath.c_str(), TRUE));
}

void initializeAppIdentity() {
    SetCurrentProcessExplicitAppUserModelID(kNuvioAppUserModelId);

    const std::wstring exePath = currentExecutablePath();
    if (exePath.empty()) return;

    // The JVM thread that calls this may or may not already have COM up; only balance the
    // initialisation we actually performed.
    const HRESULT comResult = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    const bool shouldUninitialize = SUCCEEDED(comResult);

    const bool wrote = ensureStartMenuShortcut(exePath);
    // Repair launchers the user already created. Without our AUMID on these, launching from a
    // taskbar pin opens a SECOND taskbar entry instead of lighting up the pinned one.
    const int stampedTaskbar = stampShortcutsInDirectory(taskbarPinnedDirectory(), exePath);
    const int stampedDesktop = stampShortcutsInDirectory(knownFolder(FOLDERID_Desktop), exePath);

    if (shouldUninitialize) CoUninitialize();

    nuvioBridgeLog(
        std::string("app identity ") + (wrote ? "shortcut ok" : "shortcut unavailable") +
        ", aumid " + toUtf8(kNuvioAppUserModelId) +
        ", stamped taskbar=" + std::to_string(stampedTaskbar) +
        " desktop=" + std::to_string(stampedDesktop)
    );
}

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

class WindowsMpvWebPlayer : public std::enable_shared_from_this<WindowsMpvWebPlayer> {
    struct InitializationState {
        std::mutex mutex;
        std::condition_variable cv;
        bool complete = false;
        std::string failure;
    };

public:
    void nuvioMpvLogAppend(const std::string &line) {
        const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - startupStartedAt).count();
        ::nuvioMpvLogAppend("[session=" + playbackSessionId + " trace=" + appTraceId + " +" + std::to_string(elapsed) + "ms] " + line, playbackLogPath);
    }

    void nuvioBridgeLog(const std::string &line) {
        nuvioMpvLogAppend("[nuvio-bridge " + nuvioLogTimestamp() + "] " + line + "\n");
    }

    void initialize(
        HWND host,
        const std::string &sourceUrl,
        const std::string &audioUrl,
        const std::vector<std::string> &headerLines,
        bool playWhenReady,
        long long initialPositionMs,
        double initialProgressFraction,
        const std::string &controlsUrl,
        JavaVM *vm,
        bool nvidiaRtxSuperResolutionEnabled,
        bool nvidiaRtxHdrEnabled,
        bool isAnimeContent,
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
        thumbnailSourceUrl = sourceUrl;
        thumbnailHeaderLines = headerLines;
        // Set before spawning the UI/mpv thread so it is visible there. When present
        // (e.g. a YouTube trailer with separate hi-res video + audio tracks) it is
        // attached via the audio-add command once the main file has loaded.
        externalAudioUrl = audioUrl;
        extraMpvOptions = extraMpvOptionsIn;
        for (const auto &option : extraMpvOptions) {
            const std::string prefix = "@nuvio-trace-id=";
            if (option.rfind(prefix, 0) == 0) appTraceId = option.substr(prefix.size());
        }
        initialRtxSuperResolutionEnabled = nvidiaRtxSuperResolutionEnabled;
        initialRtxHdrEnabled = nvidiaRtxHdrEnabled;
        initialAnimeContent = isAnimeContent;
        // Do not install VapourSynth as an mpv option during probing. It is applied synchronously
        // when FILE_LOADED arrives, before the first PLAYBACK_RESTART can reach the app.
        deferredAnimeSvpFilter = animeSvpFilter;

        startupStartedAt = std::chrono::steady_clock::now();
        SYSTEMTIME sessionTime{};
        GetLocalTime(&sessionTime);
        char sessionBuffer[80];
        std::snprintf(sessionBuffer, sizeof(sessionBuffer), "%04u%02u%02u-%02u%02u%02u-%03u-%lu-%llu",
            sessionTime.wYear, sessionTime.wMonth, sessionTime.wDay, sessionTime.wHour,
            sessionTime.wMinute, sessionTime.wSecond, sessionTime.wMilliseconds,
            GetCurrentProcessId(), ++gPlaybackSessionSequence);
        playbackSessionId = sessionBuffer;
        const bool trailer = controlsUrl.find("heroTrailer=1") != std::string::npos;
        const std::wstring prefix = trailer ? L"nuvio-trailer-" : L"nuvio-playback-";
        const auto directory = nuvioMpvLogDirectory();
        playbackLogPath = directory + L"\\" + prefix + toWide(playbackSessionId) + L".log";
        // Separate bounded retention for complete main-player and trailer sessions.
        WIN32_FIND_DATAW data{};
        HANDLE search = FindFirstFileW((directory + L"\\" + prefix + L"*.log").c_str(), &data);
        std::vector<std::wstring> oldLogs;
        if (search != INVALID_HANDLE_VALUE) {
            do { if (!(data.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) oldLogs.emplace_back(data.cFileName); }
            while (FindNextFileW(search, &data));
            FindClose(search);
        }
        std::sort(oldLogs.begin(), oldLogs.end(), std::greater<std::wstring>());
        const size_t keepPrevious = trailer ? 4 : 19;
        for (size_t i = keepPrevious; i < oldLogs.size(); ++i) DeleteFileW((directory + L"\\" + oldLogs[i]).c_str());
        if (!trailer) nuvioMpvLogReset(playbackLogPath);
        nuvioBridgeLog(
            "initialize requested source=" + redactedSourceSummary(sourceUrl) +
            " audio=" + (audioUrl.empty() ? "no" : "yes") +
            " playWhenReady=" + (playWhenReady ? "yes" : "no") +
            " initialMs=" + std::to_string(initialPositionMs) +
            " initialProgress=" + std::to_string(initialProgressFraction) +
            " rtxVsr=" + (nvidiaRtxSuperResolutionEnabled ? "yes" : "no") +
            " rtxHdr=" + (nvidiaRtxHdrEnabled ? "yes" : "no") +
            " animeSvp=" + (animeSvpFilter.empty() ? "no" : "yes")
        );

        auto initState = std::make_shared<InitializationState>();
        auto self = shared_from_this();
        nuvioBridgeLog("native ui thread starting");
        uiThread = std::thread(
            [self, sourceUrl, headerLines, playWhenReady, initialPositionMs, initialProgressFraction, controlsUrl, nvidiaRtxSuperResolutionEnabled, nvidiaRtxHdrEnabled, animeSvpFilter, initState]() {
                self->runNativeUiThread(sourceUrl, headerLines, playWhenReady, initialPositionMs, initialProgressFraction, controlsUrl, nvidiaRtxSuperResolutionEnabled, nvidiaRtxHdrEnabled, animeSvpFilter, initState);
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
            // Serialize against lazy worker creation so a request cannot create a joinable thread
            // after shutdown has already checked it.
            std::lock_guard<std::mutex> lock(thumbnailMutex);
            thumbnailStopping.store(true);
            ++thumbnailRequestGeneration;
        }
        thumbnailCv.notify_all();
        if (thumbnailThread.joinable()) {
            thumbnailThread.join();
        }
        {
            std::lock_guard<std::mutex> lock(mpvMutex);
            if (mpv && mpvApi().wakeup) {
                nuvioBridgeLog("shutdown wake mpv");
                mpvApi().wakeup(mpv);
            }
        }

        // Stop the mpv event loop and destroy its renderer before destroying the parent container
        // window on the native UI thread. DestroyWindow(containerHwnd) can synchronously wait for
        // mpv's D3D child window; doing UI cleanup first created a circular wait that consistently
        // consumed sendUiTask's five-second timeout for passive hero trailers.
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

        nuvioBridgeLog("shutdown send ui cleanup");
        bool uiCleanupCompleted = sendUiTask([self = shared_from_this()]() {
            self->cleanupUiResources();
            PostQuitMessage(0);
        });
        nuvioBridgeLog(uiCleanupCompleted ? "shutdown ui cleanup returned" : "shutdown ui cleanup timed out");

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

        {
            // WebView callbacks run on the native UI thread while mpv callbacks run on the event
            // thread. Serialize final JNI ref deletion with both paths so a late callback cannot
            // invoke Java through a global reference teardown has just released.
            std::lock_guard<std::mutex> eventLock(eventSinkMutex);
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
        }
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
            if (!self->webView || !self->controlsWebReady.load()) {
                // The controls page is still loading (or the WebView isn't up yet). Queue the
                // script so early UI calls — e.g. the failover "trying next source" pill sent
                // right after a source switch — run once the page reports controlsReady instead
                // of being silently dropped. Bounded so a page that never loads can't grow it.
                if (self->pendingControlsScripts.size() < 32) {
                    self->pendingControlsScripts.push_back(script);
                }
                return;
            }
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
        const bool requestedPlay = !paused;
        // A resume seek is a startup transaction: remember the caller's intent, but do not let
        // playback run until mpv reports a frame at the validated target. Otherwise a remote MKV
        // whose header probe leaves the demuxer at EOF can emit a genuine-looking EOF before the
        // FILE_LOADED seek settles and incorrectly trigger next-episode autoplay.
        if (initialResumeTransactionPending.load()) {
            initialResumeShouldPlay.store(requestedPlay);
            paused = true;
        }
        if (profileOwnsPause) {
            profileResumeRequested = requestedPlay;
            paused = true;
        }
        // While the initial SVP graph is realizing, keep mpv paused even if the common/UI layer
        // asks to play. Remember the latest intent and honour it as soon as filtered output is
        // confirmed. A user pause during pre-roll cancels the automatic resume.
        if (svpPrerollPending) {
            svpPrerollResumeRequested = requestedPlay;
            paused = true;
        }
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

    // Some sources report a placeholder/probe video track at FILE_LOADED before the demuxer has
    // resolved the real stream — seen in the wild as "h264 1280x720 1 fps 2 kbps" with a 120s
    // duration on a 24-minute WEB-DL. Those numbers are not merely wrong for us: building the
    // VapourSynth graph against them means mpv must reconfigure the entire filter when the real
    // parameters arrive, tearing down a graph that is still initialising and already being asked
    // for frames ("Frame requested during init! This is unsupported."). That trips mpv's own
    // assert(!p->in_node_active) in vf_vapoursynth.c, which aborts the process outright on a
    // libmpv built with assertions. The same bogus duration also makes a legitimate resume
    // position look like EOF, so both consumers wait on this check.
    static constexpr double minimumPlausibleVideoFps = 5.0;
    static constexpr long long minimumPlausibleVideoDimension = 96;
    static constexpr double providerWaitVideoDurationSeconds = 120.0;
    static constexpr double providerWaitVideoDurationToleranceSeconds = 1.5;
    static constexpr auto svpVideoParameterWaitTimeout = std::chrono::seconds(12);
    static constexpr auto initialResumeParameterWaitTimeout = std::chrono::seconds(5);

    // d3d11vpp truncates the scaled output dimensions (then rounds an odd result up to even),
    // but rounds the inherited crop rectangle to the nearest integer. For some scales those two
    // rules disagree by one pixel: 720 * 3.970588... becomes a 2858px output while the full-frame
    // crop edge becomes 2859. gpu-next rejects that crop on every frame and mpv floods the log.
    // Step just below every such half-pixel boundary so a full-frame crop always fits the output.
    static float cropSafeD3d11vppScale(long long width, long long height, double desiredScale) {
        float scale = static_cast<float>(std::max(1.0, std::min(4.0, desiredScale)));
        const long long dimensions[] = {width, height};
        for (int pass = 0; pass < 16; ++pass) {
            bool adjusted = false;
            for (long long dimension : dimensions) {
                if (dimension <= 0) continue;
                const float scaled = scale * static_cast<float>(dimension);
                int outputExtent = static_cast<int>(scaled);
                if ((outputExtent & 1) != 0) ++outputExtent;
                if (std::lrintf(scaled) <= outputExtent) continue;

                const float boundary =
                    (static_cast<float>(static_cast<int>(scaled)) + 0.5f) /
                    static_cast<float>(dimension);
                scale = std::nextafter(boundary, 0.0f);
                adjusted = true;
                break;
            }
            if (!adjusted) return std::max(1.0f, scale);
        }
        // The loop normally converges in one pass. If pathological dimensions alternate across
        // several boundaries, disabling the upscale is safer than reviving the per-frame loop.
        return 1.0f;
    }

    static std::string roundTripFloatText(float value) {
        std::ostringstream text;
        text << std::setprecision(std::numeric_limits<float>::max_digits10) << value;
        return text.str();
    }

    // Must not be called while holding mpvMutex: the property helpers take it themselves.
    bool videoParametersLookResolved() {
        if (int64Property("video-params/w", 0) < minimumPlausibleVideoDimension) return false;
        if (int64Property("video-params/h", 0) < minimumPlausibleVideoDimension) return false;
        double fps = doubleProperty("container-fps", 0.0);
        if (fps <= 0.0) fps = doubleProperty("estimated-vf-fps", 0.0);
        return fps >= minimumPlausibleVideoFps;
    }

    bool looksLikeProviderWaitVideo() {
        const double duration = doubleProperty("duration", 0.0);
        return duration > 0.0 &&
            std::abs(duration - providerWaitVideoDurationSeconds) <=
                providerWaitVideoDurationToleranceSeconds;
    }

    bool videoParametersLookSafeForSvp() {
        if (!videoParametersLookResolved()) return false;
        // Debrid services can encode their static "file is being downloaded" notice as a normal
        // 24/30fps 720p video. Resolution and FPS therefore are insufficient guards: its exact
        // two-minute duration is the stable signature seen from these provider endpoints. Never
        // build VapourSynth against it; the real file can replace the track underneath mpv and
        // reconfiguring a graph that is still initialising asserts in vf_vapoursynth.c.
        return !looksLikeProviderWaitVideo();
    }

    // Publishes the decoded source dimensions to Kotlin, which needs them to decide whether an
    // upscaling shader chain (Anime4K) is worth running at all — a 4K source on a modest GPU can
    // stall the whole app. Both values ride in one event so Kotlin never observes half a size:
    // packed as w * 65536 + h, exact in a double for any dimension mpv can report.
    //
    // Must not be called while holding mpvMutex: the property helpers take it themselves.
    void reportVideoSourceSize() {
        const int64_t width = int64Property("video-params/w", 0);
        const int64_t height = int64Property("video-params/h", 0);
        if (width <= 0 || height <= 0 || width > 65535 || height > 65535) return;
        const int64_t packed = width * 65536 + height;
        if (packed == lastReportedVideoSizePacked) return;
        lastReportedVideoSizePacked = packed;
        sendPlayerEvent("videoSourceSize", static_cast<double>(packed));
    }

    std::string describeVideoParameters() {
        double fps = doubleProperty("container-fps", 0.0);
        if (fps <= 0.0) fps = doubleProperty("estimated-vf-fps", 0.0);
        return std::to_string(int64Property("video-params/w", 0)) + "x" +
            std::to_string(int64Property("video-params/h", 0)) +
            " fps=" + std::to_string(fps) +
            " duration=" + std::to_string(doubleProperty("duration", 0.0));
    }

    // Applies the anime SVP filter deferred from initialize(), but only once the stream parameters
    // can plausibly be real. Event-thread only; must not be called while holding mpvMutex.
    void tryApplyDeferredAnimeSvp(const char *reason) {
        if (deferredAnimeSvpFilter.empty()) return;
        if (!videoParametersLookSafeForSvp()) {
            if (!svpAwaitingVideoParameters) {
                svpAwaitingVideoParameters = true;
                const std::string params = describeVideoParameters();
                std::lock_guard<std::mutex> lock(mpvMutex);
                // Pre-roll's timeout is normally armed by a successful apply. Arm it here too:
                // if the parameters never resolve, that timeout is what gives up on SVP and lets
                // playback start unfiltered instead of waiting on a first frame forever.
                svpPrerollDeadline = std::chrono::steady_clock::now() +
                    svpVideoParameterWaitTimeout + std::chrono::seconds(2);
                nuvioMpvLogAppend(std::string("[nuvio] anime SVP deferred at ") + reason +
                    ": video parameters unresolved or provider wait video " + params + "\n");
            }
            return;
        }

        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return;
        videoPipelineManaged = true;
        requestedVideoFilters = deferredAnimeSvpFilter;
        double currentSpeed = 1.0;
        tryGetDoubleLocked("speed", currentSpeed);
        applySpeedSensitiveVideoFiltersLocked(currentSpeed);
        nuvioMpvLogAppend(std::string("[nuvio] deferred anime SVP handled at ") + reason +
            " speed=" + std::to_string(currentSpeed) + " vf=" + appliedVideoFilters + "\n");
        deferredAnimeSvpFilter.clear();
        svpAwaitingVideoParameters = false;
        svpPrerollDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(8);
    }

    // Seeks to the stored resume position once the stream parameters can plausibly be real.
    // The near-EOF reset below discards the user's position outright, so it must not run against
    // a placeholder duration: the same probe track that breaks VapourSynth also reported a 120s
    // duration for a 24-minute file, which turned a legitimate 4-minute resume into a reset to 0.
    // If the parameters never resolve, apply the position anyway but skip the reset — keeping a
    // possibly-late position beats silently sending the user back to the start.
    // Event-thread only; must not be called while holding mpvMutex.
    void tryApplyInitialResume(const char *reason) {
        if (initialResumeApplied) return;
        const bool parametersResolved = videoParametersLookResolved();
        const double duration = doubleProperty("duration", 0.0);
        const bool needsDuration = initialStartSeconds.load() <= 0.0 && initialStartProgressFraction > 0.0 && duration <= 0.0;
        // Opening a remote file can outlast the parameter timeout. A percentage cannot be
        // converted until duration exists: do not consume it as a zero-second resume while
        // the demuxer is still opening. Start the bounded fallback only after FILE_LOADED.
        if (needsDuration && !fileLoadedForCurrentSource) return;
        if (!parametersResolved || needsDuration) {
            const auto now = std::chrono::steady_clock::now();
            if (initialResumeWaitDeadline == std::chrono::steady_clock::time_point{}) {
                initialResumeWaitDeadline = now + initialResumeParameterWaitTimeout;
                nuvioMpvLogAppend(std::string("[nuvio] initial resume waiting at ") + reason +
                    " for video parameters " + describeVideoParameters() + "\n");
                return;
            }
            if (now < initialResumeWaitDeadline) return;
        }

        initialResumeApplied = true;
        if (needsDuration) {
            nuvioMpvLogAppend("[nuvio] initial percentage resume unavailable: loaded media has no duration\n");
            initialStartProgressFraction = 0.0;
        }
        double requestedStart = initialStartSeconds.load();
        if (requestedStart <= 0.0 && initialStartProgressFraction > 0.0 && duration > 0.0) {
            requestedStart = duration * initialStartProgressFraction;
            initialStartSeconds.store(requestedStart);
        }
        if (looksLikeProviderWaitVideo() && requestedStart > duration + seekEndGuardSeconds) {
            // This is a status clip, not the requested episode. Seeking a legitimate episode
            // resume into it clamps to 01:59, which the common layer then mistakes for completion.
            // Let the notice play from its beginning; common code marks it diagnostic and either
            // chooses another cached source or suppresses progress/autoplay.
            initialResumeApplied = true;
            initialStartSeconds.store(0.0);
            initialStartProgressFraction = 0.0;
            initialResumeTransactionPending.store(false);
            seekToMilliseconds(0L);
            nuvioMpvLogAppend("[nuvio] initial resume suppressed for provider wait video target=" +
                std::to_string(requestedStart) + " duration=" + std::to_string(duration) + "\n");
            return;
        }
        if (parametersResolved && requestedStart > 0.0 && duration > 0.0 &&
            requestedStart / duration >= 0.90) {
            nuvioMpvLogAppend("[nuvio] initial resume reset near EOF requested=" +
                std::to_string(requestedStart) + " duration=" + std::to_string(duration) + "\n");
            initialStartSeconds.store(0.0);
            initialStartProgressFraction = 0.0;
            seekToMilliseconds(0L);
        } else if (requestedStart > 0.0) {
            // Exact (hr) seek: the resume transaction validates the landed position against a
            // ±2s tolerance before unpausing. A keyframe seek can settle several seconds off
            // target on sparse-keyframe encodes and then never satisfy that check, leaving
            // playback force-paused forever.
            seekToMilliseconds((long long)std::llround(requestedStart * 1000.0), true);
            nuvioMpvLogAppend(std::string("[nuvio] initial resume applied at ") + reason +
                " target=" + std::to_string(requestedStart) +
                " duration=" + std::to_string(duration) +
                " params=" + (parametersResolved ? "resolved" : "unresolved") + "\n");
        }
    }

    // Event-thread only. Poll settled output as well as PLAYBACK_RESTART: SVP can consume
    // the restart while constructing its graph, and a paused output need not emit another.
    // Polling never issues retry seeks or accepts an off-target position.
    bool tryCompleteInitialResume(bool restartEvent) {
        if (initialResumeTransactionPending.load() &&
            (flagProperty("seeking", true) || (!restartEvent && !flagProperty("vo-configured", false)))) return false;
        bool initialResumeReady = true;
        if (initialResumeTransactionPending.load()) {
            // A natural playback restart can arrive before the parameter gate has issued
            // the initial resume seek. Validating that position as though it were the seek
            // result caused an immediate retry, followed by the real apply a few seconds
            // later: two remote range requests during startup. Ignore restarts until the
            // resume transaction has actually been applied.
            if (!initialResumeApplied || !fileLoadedForCurrentSource) {
                initialResumeReady = false;
            } else {
                const double target = initialStartSeconds.load();
                const double position = doubleProperty("time-pos", -1.0);
                const bool rawEof = flagProperty("eof-reached", false);
                const bool positionMatches = position >= 0.0 &&
                    (target <= 0.0 ? position <= 2.0 : std::abs(position - target) <= 2.0);
                initialResumeReady = positionMatches && !rawEof && !flagProperty("seeking", true);
                if (restartEvent && !initialResumeReady && !rawEof && position >= 0.0 && target > 0.0) {
                    // The resume seek settled at a real position but outside tolerance
                    // (seen with sparse-keyframe encodes). Nothing else re-seeks during
                    // the transaction, so "waiting" here would force-pause forever:
                    // retry once with an exact seek, then accept whatever the retry
                    // lands on rather than deadlock.
                    if (!initialResumeSeekRetried.exchange(true)) {
                        nuvioMpvLogAppend("[nuvio] initial resume off target; retrying exact seek target=" +
                            std::to_string(target) + " position=" + std::to_string(position) + "\n");
                        seekToMilliseconds((long long)std::llround(target * 1000.0), true);
                    } else {
                        initialResumeReady = true;
                        nuvioMpvLogAppend("[nuvio] initial resume accepting off-target position target=" +
                            std::to_string(target) + " position=" + std::to_string(position) + "\n");
                    }
                }
                if (initialResumeReady) {
                    startupPlaybackReady = true;
                    initialResumeTransactionPending.store(false);
                    eofSuppressedUntil.store(
                        std::chrono::steady_clock::now() + std::chrono::seconds(3)
                    );
                    const bool shouldPlay = initialResumeShouldPlay.load();
                    // Seeking and profile/SVP initialization can complete in either order.
                    // Keep the remaining startup gates in control of the final unpause.
                    setPaused(!shouldPlay);
                    nuvioMpvLogAppend("[nuvio] initial resume transaction completed target=" +
                        std::to_string(target) + " position=" + std::to_string(position) +
                        " play=" + (shouldPlay ? std::string("yes") : std::string("no")) + "\n");
                } else if (restartEvent) {
                    nuvioMpvLogAppend("[nuvio] initial resume transaction waiting target=" +
                        std::to_string(target) + " position=" + std::to_string(position) +
                        " eof=" + (rawEof ? std::string("yes") : std::string("no")) + "\n");
                }
            }
        }
        return initialResumeReady;
    }

    void publishPlaybackRestartIfReady() {
        if (!playbackRestartPendingForFile || !startupPlaybackReady ||
            initialResumeTransactionPending.load() || svpPrerollPending ||
            profileOwnsPause || profileRenderPending.load()) return;
        playbackRestartPendingForFile = false;
        sendPlayerEvent("playbackRestart", 1.0);
    }

    // Releases the vf/hwdec writes held while the initial VapourSynth graph was building (see
    // setVideoFiltersPropertyLocked). Must not be called while holding mpvMutex.
    void releaseSvpGraphInitLatch(const char *reason) {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!svpGraphInitInFlight) return;
        svpGraphInitInFlight = false;
        const bool hadPendingHwdec = !svpPendingHwdec.empty();
        if (hadPendingHwdec && mpv) {
            mpvApi().setPropertyString(mpv, "hwdec", svpPendingHwdec.c_str());
        }
        svpPendingHwdec.clear();
        const bool hadPendingFilters = svpPendingVideoFiltersValid;
        if (hadPendingFilters) {
            std::string filters = svpPendingVideoFilters;
            svpPendingVideoFiltersValid = false;
            svpPendingVideoFilters.clear();
            setVideoFiltersPropertyLocked(filters);
        }
        if (svpPendingPipelineUpdate) {
            svpPendingPipelineUpdate = false;
            double currentSpeed = 1.0;
            tryGetDoubleLocked("speed", currentSpeed);
            applySpeedSensitiveVideoFiltersLocked(currentSpeed);
        }
        if (hadPendingHwdec || hadPendingFilters) {
            nuvioMpvLogAppend(std::string("[nuvio] replayed held pipeline writes at ") + reason +
                " vf=" + appliedVideoFilters + "\n");
        }
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

    void beginSvpPrerollLocked() {
        if (svpPrerollPending) return;
        // Late anime detection or a speed restore gets the same graph protection as startup.
        svpPrerollPending = true;
        svpPrerollResumeRequested = initialResumeTransactionPending.load() ? initialResumeShouldPlay.load()
            : profileOwnsPause ? profileResumeRequested : !flagPropertyLocked("pause", true);
        profileOwnsPause = false;
        int paused = 1;
        mpvApi().setProperty(mpv, "pause", MPV_FORMAT_FLAG, &paused);
        svpPrerollUsesInitialPosition = initialResumeTransactionPending.load() || !fileLoadedForCurrentSource;
        svpPrerollTargetSeconds = -1.0;
        if (!svpPrerollUsesInitialPosition) tryGetDoubleLocked("time-pos", svpPrerollTargetSeconds);
        svpPrerollRewindPending = false;
        svpRuntimeReady = false;
        svpFilteredOutputReady = false;
        svpStartupProfileRequested = false;
        svpStartupProfileApplied.store(false);
        svpProfileSettleDeadline = {};
        svpPrerollDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(8);
        mpvApi().requestLogMessages(mpv, "v");
    }

    bool videoParametersLookSafeForSvpLocked() {
        double width = 0, height = 0, fps = 0, duration = 0;
        tryGetDoubleLocked("video-params/w", width);
        tryGetDoubleLocked("video-params/h", height);
        tryGetDoubleLocked("container-fps", fps);
        if (fps <= 0) tryGetDoubleLocked("estimated-vf-fps", fps);
        tryGetDoubleLocked("duration", duration);
        return width >= minimumPlausibleVideoDimension && height >= minimumPlausibleVideoDimension &&
            fps >= minimumPlausibleVideoFps && !(duration > 0 &&
                std::abs(duration - providerWaitVideoDurationSeconds) <= providerWaitVideoDurationToleranceSeconds);
    }

    void applySpeedSensitiveVideoFiltersLocked(double speed) {
        if (!videoPipelineManaged) return;
        const bool requested = containsVapourSynthFilter(requestedVideoFilters);
        const bool bypass = requested && (speed >= svpSpeedBypassThreshold ||
            (svpBypassedForSpeed && speed > svpSpeedRestoreThreshold));
        const std::string filters = bypass ? removeVapourSynthFilters(requestedVideoFilters) : requestedVideoFilters;
        const bool enabled = containsVapourSynthFilter(filters);
        if (svpGraphInitInFlight && filters != appliedVideoFilters) {
            // Keep the complete transition pending, including decoder and timing options.
            svpPendingPipelineUpdate = true;
            return;
        }
        const bool changed = enabled != effectiveSvpActive;
        if (enabled && !effectiveSvpActive) {
            beginSvpPrerollLocked();
            if (!videoParametersLookSafeForSvpLocked()) {
                deferredAnimeSvpFilter = requestedVideoFilters;
                return;
            }
        } else if (!enabled) {
            deferredAnimeSvpFilter.clear();
            svpAwaitingVideoParameters = false;
        }
        // hqdn3d also needs CPU frames when interpolation is bypassed.
        const bool copyBack = enabled || filters.find("lavfi") != std::string::npos;
        if (changed) {
            setChangedPropertyLocked("vd-queue-enable", enabled ? "yes" : "no");
            if (enabled) {
                setChangedPropertyLocked("vd-queue-max-bytes", "512MiB");
                setChangedPropertyLocked("vd-queue-max-samples", "35");
                setChangedPropertyLocked("vd-queue-max-secs", "60");
            } else {
                // Effective immediately even if a remaining denoise filter keeps the decoder.
                setChangedPropertyLocked("vd-queue-max-samples", "1");
            }
            setChangedPropertyLocked("hr-seek-framedrop", enabled ? "no" : "yes");
            setChangedPropertyLocked("video-latency-hacks", enabled ? "yes" : "no");
            setChangedPropertyLocked("mc", enabled ? "0" : "0.1");
            setChangedPropertyLocked("autosync", enabled ? "30" : "0");
        }
        // Queue mode must be set before a decoder reinitialization can observe it.
        setChangedPropertyLocked("hwdec", copyBack ? "d3d11va-copy" : "d3d11va");
        setVideoFiltersPropertyLocked(filters);
        effectiveSvpActive = containsVapourSynthFilter(appliedVideoFilters);
        if (changed || bypass != svpBypassedForSpeed) {
            nuvioBridgeLog("pipeline speed=" + std::to_string(speed) +
                " svp=" + (effectiveSvpActive ? "active" : bypass ? "bypassed" : "off") +
                " decoder=" + (copyBack ? "d3d11va-copy" : "d3d11va"));
            sendPlayerEvent("svpState", effectiveSvpActive ? 1.0 : 0.0);
        }
        svpBypassedForSpeed = bypass;
    }

    void finishSvpPreroll(const std::string &reason) {
        bool resumed = false;
        {
            std::lock_guard<std::mutex> lock(mpvMutex);
            if (!svpPrerollPending) return;
            svpPrerollPending = false;
            startupPlaybackReady = startupPlaybackReady || svpFilteredOutputReady;
            // Pre-roll is the whole budget SVP gets at startup. If it expired while still waiting
            // for usable video parameters, abandon the filter rather than inserting it later into
            // a running pipeline.
            if (!deferredAnimeSvpFilter.empty()) {
                nuvioMpvLogAppend("[nuvio] anime SVP abandoned: video parameters never resolved\n");
                deferredAnimeSvpFilter.clear();
                svpAwaitingVideoParameters = false;
            }
            if (mpv && svpPrerollResumeRequested && !initialResumeTransactionPending.load() && !profileOwnsPause) {
                int paused = 0;
                mpvApi().setProperty(mpv, "pause", MPV_FORMAT_FLAG, &paused);
                resumed = true;
            }
            // Verbose was requested only so pre-roll could see SVP's runtime-ready log line; the
            // warm-up is over, so drop back to "info" to stop paying verbose delivery for the rest
            // of the episode. Skip when an explicit diagnostic request wants the full stream.
            if (mpv && !mpvLogVerboseToFile && mpvApi().requestLogMessages) {
                mpvApi().requestLogMessages(mpv, "info");
            }
        }
        nuvioMpvLogAppend("[nuvio] SVP pre-roll completed reason=" + reason +
            " resumed=" + (resumed ? std::string("yes") : std::string("no")) + "\n");
        // Backstop for the timeout path, where filtered output never arrived to release it.
        releaseSvpGraphInitLatch("preroll-finished");
    }

    bool ensureSvpPrerollStartPosition() {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return false;
        double currentPosition = -1.0;
        if (!tryGetDoubleLocked("time-pos", currentPosition) || currentPosition < 0.0) return false;
        // Startup uses the saved resume target. Restoring SVP during playback anchors to the
        // position where the graph was paused, never the original start-of-session position.
        if (!svpPrerollUsesInitialPosition && svpPrerollTargetSeconds < 0.0) {
            svpPrerollTargetSeconds = currentPosition;
        }
        double targetPosition = svpPrerollUsesInitialPosition ? initialStartSeconds.load() : svpPrerollTargetSeconds;
        if (svpPrerollUsesInitialPosition && targetPosition <= 0.0 && initialStartProgressFraction > 0.0) {
            double duration = 0.0;
            targetPosition = tryGetDoubleLocked("duration", duration) && duration > 0.0
                ? duration * initialStartProgressFraction
                : currentPosition;
            initialStartSeconds.store(targetPosition);
        }
        const double drift = std::abs(currentPosition - targetPosition);
        if (drift <= 0.35) {
            svpPrerollRewindPending = false;
            return true;
        }
        if (!svpPrerollRewindPending) {
            std::string target = std::to_string(targetPosition);
            const char *command[] = {"seek", target.c_str(), "absolute+exact", nullptr};
            if (mpvApi().command(mpv, command) >= 0) {
                pendingSeekTargetSeconds = targetPosition;
                pendingSeekIssuedAt = std::chrono::steady_clock::now();
                svpPrerollRewindPending = true;
                svpPrerollDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(4);
                nuvioMpvLogAppend("[nuvio] SVP pre-roll rewinding from=" +
                    std::to_string(currentPosition) + " to=" + target + "\n");
            }
        }
        return false;
    }

    // Promotes SVP pre-roll from "runtime ready" to "filtered output ready" once VapourSynth is
    // actually emitting filtered frames at the target position. Deliberately NOT tied to a single
    // event: when playback starts at position 0 there is no rewind-seek, so the only PLAYBACK_RESTART
    // fires *before* the VapourSynth worker pool announces itself (svpRuntimeReady), and no further
    // restart arrives to re-check — leaving pre-roll to expire on its worst-case timeout. Calling
    // this from the drain loop's periodic tick as well as on PLAYBACK_RESTART closes that race.
    // Returns true once the filtered-output hand-off has been requested. Event-thread only.
    bool tryPromoteSvpFilteredOutput() {
        if (!svpPrerollPending || svpFilteredOutputReady || !svpRuntimeReady) return false;
        const std::string activeVf = stringProperty("vf", "");
        const std::string activeHwdec = stringProperty("hwdec-current", "");
        const std::string outputFormat = stringProperty("video-out-params/pixelformat", "");
        const bool softwareOutputReady = !outputFormat.empty() &&
            outputFormat.find("d3d11") == std::string::npos;
        const bool svpOutputReady = containsVapourSynthFilter(activeVf) &&
            (activeHwdec.find("copy") != std::string::npos || softwareOutputReady);
        if (!svpOutputReady) return false;
        if (!ensureSvpPrerollStartPosition()) return false;
        svpFilteredOutputReady = true;
        // The graph is now emitting filtered frames, so held vf/hwdec writes are safe to replay.
        // This must happen before svpPrerollReady is sent: that event is what releases Kotlin's
        // own startup profile pass, which pre-roll then waits on.
        releaseSvpGraphInitLatch("filtered-output-ready");
        if (!svpStartupProfileRequested) {
            svpStartupProfileRequested = true;
            svpPrerollDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
            nuvioMpvLogAppend(
                "[nuvio] SVP filtered output ready at requested position; awaiting startup profile\n"
            );
            sendPlayerEvent("svpPrerollReady", 1.0);
        }
        return true;
    }

    void acknowledgeSvpStartupProfile() {
        svpStartupProfileApplied.store(true);
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (mpv) mpvApi().wakeup(mpv);
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
        // Between handing mpv a VapourSynth chain and that graph producing filtered output, any
        // further vf write makes mpv destroy the half-built graph — the assert(!p->in_node_active)
        // abort described above. Hold the write and replay it once the graph is up.
        if (svpGraphInitInFlight) {
            svpPendingVideoFilters = filters;
            svpPendingVideoFiltersValid = true;
            nuvioMpvLogAppend("[nuvio] held vf write while VapourSynth graph initialises: " +
                filters + "\n");
            return;
        }
        if (containsVapourSynthFilter(filters)) {
#ifndef NUVIO_PLAYBACK_STARTUP_TEST
            preloadBundledVapourSynthRuntime();
#endif
        }
        int result = mpvApi().setPropertyString(mpv, "vf", filters.c_str());
        if (result >= 0) {
            appliedVideoFilters = filters;
            // Only the startup graph is latched: pre-roll owns the window that ends in
            // releaseSvpGraphInitLatch, and a mid-playback insert has no such release point.
            if (containsVapourSynthFilter(filters) && svpPrerollPending) {
                svpGraphInitInFlight = true;
            }
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

    void issueSeekLocked(double targetSeconds, bool exact = false) {
        std::string seconds = std::to_string(targetSeconds);
        const char *command[] = {"seek", seconds.c_str(), exact ? "absolute+exact" : "absolute+keyframes", nullptr};
        if (mpvApi().command(mpv, command) >= 0) {
            pendingSeekTargetSeconds = targetSeconds;
            pendingSeekIssuedAt = std::chrono::steady_clock::now();
            lastSeekIssuedAt = pendingSeekIssuedAt;
            deferredSeekTargetSeconds = -1.0;
        }
    }

    bool deferredSeekPending() {
        std::lock_guard<std::mutex> lock(mpvMutex);
        return deferredSeekTargetSeconds >= 0.0;
    }

    // Issues a coalesced relative seek once its burst has settled. No-op when nothing is
    // deferred or the window is still open. Called from the event-drain tick.
    void flushDeferredSeek() {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return;
        if (deferredSeekTargetSeconds < 0.0) return;
        if (std::chrono::steady_clock::now() < deferredSeekDueAt) return;
        const double target = deferredSeekTargetSeconds;
        // Cleared up front so a rejected seek command cannot re-arm the flush every tick.
        deferredSeekTargetSeconds = -1.0;
        // seekByMilliseconds is the only producer of a deferred target, so this is always a
        // relative step and takes the same exact-seek treatment — see the note there.
        issueSeekLocked(target, true);
    }

    void seekToMilliseconds(long long positionMs, bool exact = false) {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return;
        issueSeekLocked(clampSeekSecondsLocked((double)positionMs / 1000.0), exact);
    }

    void seekByMilliseconds(long long offsetMs) {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return;
        // Base relative seeks on the most recent seek target while a prior seek is still
        // settling. time-pos lags (or is briefly unavailable) until mpv finishes seeking,
        // so reading it raw makes rapid repeated seeks collapse into a single step — and
        // when the property read fails outright, "current position" used to become 0 and
        // playback jumped to the start of the file.
        const auto now = std::chrono::steady_clock::now();
        bool seeking = flagPropertyLocked("seeking", false);
        bool pendingFresh = pendingSeekTargetSeconds >= 0.0 &&
            (now - pendingSeekIssuedAt) < std::chrono::milliseconds(800);
        double base;
        double timePos = 0.0;
        if (deferredSeekTargetSeconds >= 0.0) {
            // Chain off the not-yet-issued target so a held key keeps stepping while coalescing.
            base = deferredSeekTargetSeconds;
        } else if (pendingSeekTargetSeconds >= 0.0 && (seeking || pendingFresh)) {
            base = pendingSeekTargetSeconds;
        } else if (tryGetDoubleLocked("time-pos", timePos)) {
            base = std::max(0.0, timePos);
        } else if (pendingSeekTargetSeconds >= 0.0) {
            base = pendingSeekTargetSeconds;
        } else {
            // No playable position yet; dropping the seek beats jumping to 0:00.
            return;
        }
        const double target = clampSeekSecondsLocked(base + (double)offsetMs / 1000.0);
        // Steps that follow a recent seek are folded into one trailing request; the first step
        // of a burst (and any isolated press) still goes out immediately.
        if (lastSeekIssuedAt != std::chrono::steady_clock::time_point{} &&
            now - lastSeekIssuedAt < seekCoalesceWindow) {
            if (deferredSeekTargetSeconds < 0.0) deferredSeekStartedAt = now;
            deferredSeekTargetSeconds = target;
            // Each step pushes the deadline out, so a *held* key would otherwise defer forever
            // and never show video at the new position. Cap the total wait from the first
            // deferred step: a long hold then seeks periodically instead of only on release.
            deferredSeekDueAt = std::min(now + seekCoalesceWindow,
                deferredSeekStartedAt + seekCoalesceMaxWait);
            return;
        }
        // Exact, not keyframes: the step is user-configurable from 1s up, and a keyframe seek
        // resolves to the index entry at or before the target. Whenever the step is shorter than
        // the file's keyframe interval — 10s GOPs are routine for web/debrid encodes — that entry
        // is the one the current position already sits in, so a "forward" press lands *behind*
        // where playback was. Exact costs at most one extra GOP of decoding, bounded regardless of
        // step size. Timeline scrubbing keeps keyframe seeks (see seekToMilliseconds): there the
        // jump is large enough that a keyframe is always crossed, and the speed is worth more than
        // sub-second accuracy.
        issueSeekLocked(target, true);
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
        {
            // While steps are being coalesced the HUD must track the accumulated target:
            // time-pos still sits at the pre-burst position until the seek is actually issued,
            // which would otherwise make a held arrow key look like it had stopped responding.
            std::lock_guard<std::mutex> lock(mpvMutex);
            if (deferredSeekTargetSeconds >= 0.0) {
                return (long long)std::llround(deferredSeekTargetSeconds * 1000.0);
            }
        }
        return (long long)std::llround(doubleProperty("time-pos", 0.0) * 1000.0);
    }


    long long bufferedPositionMs() {
        double buffered = rawPositionSeconds() + cacheAheadSeconds();
        return (long long)std::llround(std::max(buffered, 0.0) * 1000.0);
    }

    bool isLoading() {
        if (initialResumeTransactionPending.load()) return true;
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
        if (playbackFailureDetected.load()) return false;
        if (initialResumeTransactionPending.load()) return false;
        if (std::chrono::steady_clock::now() < eofSuppressedUntil.load()) return false;
        return flagProperty("eof-reached", false);
    }

    std::string audioTracksJson() {
        return tracksJsonForType("audio");
    }

    std::string subtitleTracksJson() {
        return tracksJsonForType("sub");
    }

    std::string chaptersJson() {
        long long count = int64Property("chapter-list/count", 0);
        std::ostringstream json;
        json << "[";
        bool first = true;
        for (long long index = 0; index < count; index++) {
            std::string prefix = "chapter-list/" + std::to_string(index);
            double startTime = doubleProperty((prefix + "/time").c_str(), -1.0);
            if (!std::isfinite(startTime) || startTime < 0.0) continue;
            if (!first) json << ",";
            first = false;
            json << "{\"startTime\":" << startTime
                 << ",\"title\":\"" << jsonEscape(trim(stringProperty((prefix + "/title").c_str()))) << "\"}";
        }
        json << "]";
        return json.str();
    }

    bool selectAudioTrackId(int trackId) {
        if (int64Property("aid", -1) == trackId) return true;
        if (std::chrono::steady_clock::now() < audioTrackSwitchBlockedUntil.load()) {
            nuvioMpvLogAppend("[nuvio] audio track switch deferred after HTTP 429\n");
            return false;
        }
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return false;
        int64_t id = trackId;
        const int result = mpvApi().setProperty(mpv, "aid", MPV_FORMAT_INT64, &id);
        if (result < 0) {
            nuvioMpvLogAppend("[nuvio] audio selection rejected aid=" +
                std::to_string(trackId) + " (" + mpvApi().errorText(result) + ")\n");
            return false;
        }
        return true;
    }

    bool selectSubtitleTrackId(int trackId) {
        // Switching subtitle tracks makes mpv issue an internal refresh seek. Some containers
        // briefly expose eof-reached during that refresh; suppress it so the Kotlin binge logic
        // cannot mistake a subtitle change for the end of the episode.
        eofSuppressedUntil.store(std::chrono::steady_clock::now() + std::chrono::seconds(2));
        {
            std::lock_guard<std::mutex> lock(mpvMutex);
            if (!mpv) return false;
            if (trackId < 0) {
                return mpvApi().setPropertyString(mpv, "sid", "no") >= 0;
            }
            int64_t id = trackId;
            const int result = mpvApi().setProperty(mpv, "sid", MPV_FORMAT_INT64, &id);
            if (result < 0) {
                nuvioMpvLogAppend("[nuvio] subtitle selection rejected sid=" +
                    std::to_string(trackId) + " (" + mpvApi().errorText(result) + ")\n");
                return false;
            }
        }
        // Re-derive sub-ass-override for whatever track just became selected — the helper
        // functions above each take mpvMutex themselves, so this must run after it's released.
        refreshSubtitleAssOverrideMode();
        return true;
    }

    void addSubtitleUrl(const std::string &url) {
        if (url.empty()) return;
        {
            std::lock_guard<std::mutex> lock(appAddedSubtitleUrlsMutex);
            appAddedSubtitleUrls.insert(url);
        }
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

    // Sets the ASS/SSA override level and re-applies it to whatever track is selected right now, so
    // changing the setting mid-playback takes effect on the frame the user is looking at rather
    // than at the next track switch.
    void setSubtitleAssStyleMode(const std::string &mode, double scale) {
        if (mode != "no" && mode != "yes" && mode != "scale" && mode != "force") return;
        double clampedScale = std::max(0.1, std::min(5.0, scale));
        if (mode == requestedSubAssStyleMode && clampedScale == requestedSubAssScale) return;
        requestedSubAssStyleMode = mode;
        requestedSubAssScale = clampedScale;
        refreshSubtitleAssOverrideMode(/*force=*/true);
        forceVideoRedraw();
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

    bool setChangedPropertyLocked(const std::string &key, const std::string &value) {
        char *current = nullptr;
        const int readResult = mpvApi().getProperty(mpv, key.c_str(), MPV_FORMAT_STRING, &current);
        bool same = readResult >= 0 && current && value == current;
        if (!same && readResult >= 0 && current && *current && !value.empty()) {
            char *oldEnd = nullptr;
            char *newEnd = nullptr;
            const double oldNumber = std::strtod(current, &oldEnd);
            const double newNumber = std::strtod(value.c_str(), &newEnd);
            same = oldEnd != current && newEnd != value.c_str() && *oldEnd == '\0' && *newEnd == '\0' &&
                std::isfinite(oldNumber) && std::isfinite(newNumber) && oldNumber == newNumber;
        }
        if (current) mpvApi().freeValue(current);
        if (same) return false;
        const int result = mpvApi().setPropertyString(mpv, key.c_str(), value.c_str());
        if (result < 0) {
            nuvioBridgeLog("property rejected key=" + key + " error=" + mpvApi().errorText(result));
            return false;
        }
        profileChanged = true;
        return true;
    }

    // Canonicalize map order: libmpv makes no guarantee about the order of node-map keys.
    static std::string renderNodeSignature(const mpv_node &node) {
        if (node.format == MPV_FORMAT_INT64) return std::to_string(node.u.int64) + ";";
        if (node.format == MPV_FORMAT_DOUBLE) return std::to_string(node.u.double_) + ";";
        if (node.format == MPV_FORMAT_STRING) return node.u.string ? node.u.string : "";
        if ((node.format != MPV_FORMAT_NODE_ARRAY && node.format != MPV_FORMAT_NODE_MAP) || !node.u.list) return "";
        std::map<std::string, std::string> entries;
        for (int i = 0; i < node.u.list->num; ++i) {
            const std::string key = node.format == MPV_FORMAT_NODE_MAP ? node.u.list->keys[i] : std::to_string(i);
            entries[key] = renderNodeSignature(node.u.list->values[i]);
        }
        std::string result;
        for (const auto &entry : entries) result += entry.first + "=" + entry.second + "|";
        return result;
    }

    static std::string renderTimingSignature(const mpv_node &node) {
        if ((node.format != MPV_FORMAT_NODE_ARRAY && node.format != MPV_FORMAT_NODE_MAP) || !node.u.list) return "";
        std::map<std::string, std::string> entries;
        for (int i = 0; i < node.u.list->num; ++i) {
            const std::string key = node.format == MPV_FORMAT_NODE_MAP ? node.u.list->keys[i] : std::to_string(i);
            const mpv_node &child = node.u.list->values[i];
            std::string value;
            if (key == "samples" && child.format == MPV_FORMAT_NODE_ARRAY && child.u.list && child.u.list->num > 0) {
                bool measured = false;
                for (int j = 0; j < child.u.list->num; ++j) {
                    const mpv_node &sample = child.u.list->values[j];
                    measured |= sample.format == MPV_FORMAT_INT64 && sample.u.int64 > 0;
                }
                if (measured) value = renderNodeSignature(child);
            } else {
                value = renderTimingSignature(child);
            }
            if (!value.empty()) entries[key] = value;
        }
        std::string result;
        for (const auto &entry : entries) result += entry.first + "=" + entry.second + "|";
        return result;
    }

    std::string renderPassSignatureLocked() {
        mpv_node passes{};
        if (mpvApi().getProperty(mpv, "vo-passes", MPV_FORMAT_NODE, &passes) < 0) return "";
        const std::string signature = renderTimingSignature(passes);
        mpvApi().freeNodeContents(&passes);
        return signature;
    }

    void beginVideoProfile() {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return;
        profileChanged = false;
        if (!firstProfileApplied && !svpPrerollPending) {
            profileResumeRequested = initialResumeTransactionPending.load()
                ? initialResumeShouldPlay.load() : !flagPropertyLocked("pause", true);
            profileOwnsPause = true;
            int paused = 1;
            mpvApi().setProperty(mpv, "pause", MPV_FORMAT_FLAG, &paused);
        }
        profileTransactionActive.store(true);
    }

    void endVideoProfile() {
        bool redraw = false;
        {
            std::lock_guard<std::mutex> lock(mpvMutex);
            if (!mpv) return;
            if (profileChanged || !firstProfileApplied) {
                firstProfileApplied = true;
                profileRenderReady.store(false);
                profileRenderBaseline = renderPassSignatureLocked();
                profileRedrawIssued.store(false);
                profileRenderPending.store(true);
                char *shaders = nullptr;
                mpvApi().getProperty(mpv, "glsl-shaders", MPV_FORMAT_STRING, &shaders);
                const bool heavy = shaders && *shaders;
                if (shaders) mpvApi().freeValue(shaders);
                profileRenderBudget = std::chrono::milliseconds(heavy ? 1800 : 900);
                profileRenderDeadline = std::chrono::steady_clock::now() + profileRenderBudget;
                redraw = true;
                nuvioBridgeLog("profile applied; awaiting render heavy=" + std::string(heavy ? "yes" : "no"));
            }
            profileTransactionActive.store(false);
        }
        if (redraw) forceVideoRedraw();
        if (mpv) mpvApi().wakeup(mpv);
    }

    void pollProfileRender() {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv || !profileRenderPending.load() || profileTransactionActive.load()) return;
        const auto now = std::chrono::steady_clock::now();
        // The initial resume seek keeps the VO from drawing a fresh frame, so the render budget
        // must not burn down while it is in flight: on a high-bitrate 4K remux the seek alone
        // outlasts the deadline and every start reported fallback-timeout even though the render
        // passes showed up a moment later. Holding here cannot delay startup, because
        // publishPlaybackRestartIfReady() already waits on the same transaction.
        if (initialResumeTransactionPending.load()) profileRenderDeadline = now + profileRenderBudget;
        const bool expired = now >= profileRenderDeadline;
        bool rendered = false;
        if (profileRedrawIssued.load() && !flagPropertyLocked("seeking", false) && flagPropertyLocked("vo-configured", false)) {
            const std::string signature = renderPassSignatureLocked();
            rendered = !signature.empty() && signature != profileRenderBaseline;
        }
        if (!rendered && !expired) return;
        profileRenderPending.store(false);
        profileRenderReady.store(true);
        if (profileOwnsPause) {
            profileOwnsPause = false;
            if (profileResumeRequested && !svpPrerollPending && !initialResumeTransactionPending.load()) {
                int paused = 0;
                mpvApi().setProperty(mpv, "pause", MPV_FORMAT_FLAG, &paused);
            }
        }
        nuvioBridgeLog(std::string("profile ready evidence=") + (rendered ? "render-passes" : "fallback-timeout"));
        sendPlayerEvent("profileReady", rendered ? 1.0 : 0.0);
    }

    void setMpvPropertyString(const std::string &key, const std::string &value) {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return;
        // The runtime video profile (applyDesktopVideoProfile / applyDesktopAnimeProfile) owns these
        // keys and re-asserts them on every file load and settings change, which would put the
        // source-sized allocations back a frame after startMpv stripped them out. Pin them instead
        // of letting the write through: this is the one place every profile rebuild passes through.
        if (lowVramRenderingActive) {
            const bool isScalerKey = key == "scale" || key == "dscale" || key == "cscale";
            const bool isPipelineKey = key == "deband" || key == "dither" ||
                key == "temporal-dither" || key == "sigmoid-upscaling" ||
                key == "correct-downscaling" || key == "hdr-compute-peak";
            if (isScalerKey || isPipelineKey) {
                const std::string pinned = isScalerKey ? "bilinear" : "no";
                if (value != pinned) {
                    // Once per key: a profile rebuild writes the whole set every time, and this
                    // would otherwise be several lines per file load for the life of the session.
                    if (lowVramClampLogged.insert(key).second) {
                        nuvioMpvLogAppend("[nuvio] low-VRAM pipeline pinned " + key + "=" + value +
                            " -> " + pinned + "\n");
                    }
                    mpvApi().setPropertyString(mpv, key.c_str(), pinned.c_str());
                    return;
                }
            }
        }
        // A decoder switch reconfigures the whole video chain, which is just as fatal to a
        // half-built VapourSynth graph as a vf write. Hold it on the same latch.
        if (key == "hwdec" && svpGraphInitInFlight) {
            svpPendingHwdec = value;
            nuvioMpvLogAppend("[nuvio] held hwdec write while VapourSynth graph initialises: " +
                value + "\n");
            return;
        }
        if (key == "vf") {
            videoPipelineManaged = true;
            requestedVideoFilters = value;
            double currentSpeed = 1.0;
            tryGetDoubleLocked("speed", currentSpeed);
            applySpeedSensitiveVideoFiltersLocked(currentSpeed);
            return;
        }
        setChangedPropertyLocked(key, value);
    }

    void toggleStatsOverlay() {
        std::lock_guard<std::mutex> lock(mpvMutex);
        if (!mpv) return;
        const char *command[] = {"script-binding", "stats/display-stats-toggle", nullptr};
        int result = mpvApi().command(mpv, command);
        if (result < 0) {
            nuvioMpvLogAppend("[nuvio] unable to toggle mpv stats overlay: " +
                std::string(mpvApi().errorText(result)) + "\n");
        }
    }

    // mpv only repaints the wid-embedded video surface on a size change while otherwise
    // idle, so equalizer / colorspace property changes (HDR + colour presets) don't show
    // until the window is resized — which is why minimize/restore "fixes" it. Nudge the
    // container window size by 1px and back on the UI thread to force a VO reconfigure and
    // redraw immediately.
    void forceVideoRedraw() {
        if (redrawQueued.exchange(true)) return;
        auto self = shared_from_this();
        postUiTask([self]() {
            self->redrawQueued.store(false);
            if (!self->containerHwnd || !IsWindow(self->containerHwnd)) return;
            if (!self->isPaused() && !self->svpPrerollPending) {
                InvalidateRect(self->containerHwnd, nullptr, FALSE);
                self->profileRedrawIssued.store(true);
                return;
            }
            self->nuvioBridgeLog("paused video redraw");
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
            self->profileRedrawIssued.store(true);
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

    void requestSeekThumbnail(long long positionMs) {
        {
            std::lock_guard<std::mutex> lock(thumbnailMutex);
            if (thumbnailStopping.load() || shuttingDown.load()) return;
            thumbnailRequestedPositionMs = positionMs;
            ++thumbnailRequestGeneration;
            // The preview decoder owns a second libmpv instance and opens the media independently.
            // Do not create or load it until the controls actually request their first thumbnail.
            startThumbnailWorker();
        }
        thumbnailCv.notify_one();
    }

    void startThumbnailWorker() {
        if (passiveSurface || thumbnailSourceUrl.empty() || thumbnailThread.joinable()) return;
        auto self = shared_from_this();
        thumbnailThread = std::thread([self]() { self->runThumbnailWorker(); });
    }

    void runThumbnailWorker() {
        if (thumbnailStopping.load() || shuttingDown.load()) return;
        MpvApi &api = mpvApi();
        mpv_handle *thumbnailMpv = api.create();
        if (!thumbnailMpv) return;
        auto destroyThumbnailMpv = [&]() {
            if (thumbnailMpv) {
                api.terminateDestroy(thumbnailMpv);
                thumbnailMpv = nullptr;
            }
        };
        api.setOptionString(thumbnailMpv, "config", "no");
        api.setOptionString(thumbnailMpv, "osc", "no");
        api.setOptionString(thumbnailMpv, "audio", "no");
        api.setOptionString(thumbnailMpv, "vo", "null");
        api.setOptionString(thumbnailMpv, "pause", "yes");
        api.setOptionString(thumbnailMpv, "hwdec", "no");
        // Thumbnail seeks should read only what they need. A cache here is separate from the main
        // player, invisible to its buffer bar, and can otherwise continue downloading while paused.
        api.setOptionString(thumbnailMpv, "cache", "no");
        // Preview hovers use keyframe seeks. Avoid the costly frame-accurate decode path and keep
        // the decoded frame close to its rendered size so the first preview is available sooner.
        api.setOptionString(thumbnailMpv, "hr-seek", "no");
        api.setOptionString(thumbnailMpv, "vf", "lavfi=[scale=256:-2]");
        api.setOptionString(thumbnailMpv, "screenshot-format", "jpg");
        api.setOptionString(thumbnailMpv, "screenshot-jpeg-quality", "64");
        if (!thumbnailHeaderLines.empty()) {
            std::string headers;
            for (size_t index = 0; index < thumbnailHeaderLines.size(); ++index) {
                if (index > 0) headers.push_back(',');
                for (char character : thumbnailHeaderLines[index]) {
                    if (character == '\\' || character == ',') headers.push_back('\\');
                    headers.push_back(character);
                }
            }
            api.setOptionString(thumbnailMpv, "http-header-fields", headers.c_str());
        }
        if (api.initialize(thumbnailMpv) < 0) {
            destroyThumbnailMpv();
            return;
        }
        const char *loadCommand[] = {"loadfile", thumbnailSourceUrl.c_str(), nullptr};
        if (api.command(thumbnailMpv, loadCommand) < 0) {
            destroyThumbnailMpv();
            return;
        }
        const auto loadDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(8);
        while (std::chrono::steady_clock::now() < loadDeadline && !thumbnailStopping.load()) {
            mpv_event *event = api.waitEvent(thumbnailMpv, 0.05);
            if (event && event->event_id == MPV_EVENT_FILE_LOADED) break;
        }

        uint64_t processedGeneration = 0;
        while (!thumbnailStopping.load() && !shuttingDown.load()) {
            long long positionMs = 0;
            uint64_t generation = 0;
            {
                std::unique_lock<std::mutex> lock(thumbnailMutex);
                thumbnailCv.wait(lock, [&]() {
                    return thumbnailStopping.load() || thumbnailRequestGeneration.load() > processedGeneration;
                });
                if (thumbnailStopping.load()) break;
                positionMs = thumbnailRequestedPositionMs;
                generation = thumbnailRequestGeneration.load();
            }
            while (api.waitEvent(thumbnailMpv, 0.0)->event_id != MPV_EVENT_NONE) {}
            std::string seconds = std::to_string((double)positionMs / 1000.0);
            const char *seekCommand[] = {"seek", seconds.c_str(), "absolute+keyframes", nullptr};
            if (api.command(thumbnailMpv, seekCommand) < 0) {
                processedGeneration = generation;
                continue;
            }
            bool frameReady = false;
            const auto seekDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
            while (std::chrono::steady_clock::now() < seekDeadline && !thumbnailStopping.load()) {
                if (thumbnailRequestGeneration.load() != generation) break;
                mpv_event *event = api.waitEvent(thumbnailMpv, 0.04);
                if (event && event->event_id == MPV_EVENT_PLAYBACK_RESTART) {
                    frameReady = true;
                    break;
                }
            }
            processedGeneration = generation;
            if (!frameReady || thumbnailRequestGeneration.load() != generation) continue;

            wchar_t tempDirectory[MAX_PATH] = {};
            DWORD tempLength = GetTempPathW(MAX_PATH, tempDirectory);
            if (tempLength == 0 || tempLength >= MAX_PATH) continue;
            std::wstring thumbnailPath = std::wstring(tempDirectory) +
                L"nuvio-seek-" + std::to_wstring(generation) + L".jpg";
            DeleteFileW(thumbnailPath.c_str());
            std::string thumbnailPathUtf8 = toUtf8(thumbnailPath);
            const char *screenshotCommand[] = {
                "screenshot-to-file", thumbnailPathUtf8.c_str(), "video", nullptr,
            };
            if (api.command(thumbnailMpv, screenshotCommand) < 0) continue;
            std::ifstream input(thumbnailPathUtf8, std::ios::binary);
            std::vector<unsigned char> bytes(
                (std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>()
            );
            input.close();
            DeleteFileW(thumbnailPath.c_str());
            if (bytes.empty() || thumbnailRequestGeneration.load() != generation) continue;
            const std::string dataUrl = "data:image/jpeg;base64," + base64Encode(bytes);
            runJavaScript(
                "window.nuvioSeekThumbnailReady && window.nuvioSeekThumbnailReady(" +
                std::to_string(positionMs) + ",'" + dataUrl + "')"
            );
        }
        destroyThumbnailMpv();
    }

private:
    HWND hostHwnd = nullptr;
    HWND containerHwnd = nullptr;
    HWND messageHwnd = nullptr;
    DWORD uiThreadId = 0;
    bool didOleInitialize = false;
    ComPtr<WMedia::ISystemMediaTransportControls> systemMediaControls;
    EventRegistrationToken systemMediaButtonToken = {};
    int lastSystemMediaStatus = -1;
    HWND mediaSessionWindow = nullptr;
    bool mediaKeySubclassInstalled = false;
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
    double controlsZoomFactor = 0.0;
    double controlsUiScaleFactor = 1.0;

    std::mutex uiTaskMutex;
    std::deque<std::function<void()>> uiTasks;
    std::shared_ptr<WindowsMpvWebPlayer> detachedLifetimeHold;

    std::mutex mpvMutex;
    mpv_handle *mpv = nullptr;
    std::thread eventThread;
    std::atomic_bool stopping = false;
    std::atomic_bool shuttingDown = false;
    std::atomic<uint64_t> thumbnailRequestGeneration = 0;
    std::atomic_bool thumbnailStopping = false;
    std::thread thumbnailThread;
    std::mutex thumbnailMutex;
    std::condition_variable thumbnailCv;
    long long thumbnailRequestedPositionMs = 0;
    std::string thumbnailSourceUrl;
    std::vector<std::string> thumbnailHeaderLines;

    JavaVM *javaVm = nullptr;
    jobject eventSink = nullptr;
    jmethodID eventMethod = nullptr;
    std::mutex eventSinkMutex;

    std::atomic_bool controlsWebReady = false;
    std::mutex controlsMutex;
    std::string pendingControlsJson;
    // Scripts queued by runJavaScript before the controls page reported ready. UI thread only.
    std::vector<std::string> pendingControlsScripts;
    std::atomic<double> initialStartSeconds{0.0};
    double initialStartProgressFraction = 0.0;
    bool initialResumeApplied = false;
    std::atomic_bool initialResumeTransactionPending{false};
    std::atomic_bool initialResumeShouldPlay{false};
    // One-shot fail-safe: set once the transaction has re-issued an exact seek after a
    // restart settled outside the position tolerance. A second out-of-tolerance restart
    // is then accepted as-is — an off-by-seconds resume beats a permanent forced pause.
    std::atomic_bool initialResumeSeekRetried{false};

    // Last seek target issued (seconds), used to accumulate rapid relative seeks while a
    // prior seek is still in flight. Guarded by mpvMutex; -1 when no seek is pending.
    double pendingSeekTargetSeconds = -1.0;
    std::chrono::steady_clock::time_point pendingSeekIssuedAt{};

    // Rapid relative seeks (a held arrow key) each land outside the demuxer cache on a
    // high-bitrate remux, so one step means one HTTP range request. Hosts that throttle
    // connection opens — debrid proxies especially — answer a burst of those with 429. Only
    // steps arriving inside the coalesce window are folded into a single trailing seek, so an
    // isolated press still seeks with no added latency. Guarded by mpvMutex; -1 when idle.
    static constexpr std::chrono::milliseconds seekCoalesceWindow{350};
    static constexpr std::chrono::milliseconds seekCoalesceMaxWait{1000};
    double deferredSeekTargetSeconds = -1.0;
    std::chrono::steady_clock::time_point deferredSeekDueAt{};
    std::chrono::steady_clock::time_point deferredSeekStartedAt{};
    std::chrono::steady_clock::time_point lastSeekIssuedAt{};


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
    std::string deferredAnimeSvpFilter;
    std::string appTraceId = "none";
    std::string playbackSessionId;
    std::wstring playbackLogPath;
    std::chrono::steady_clock::time_point startupStartedAt{};
    std::chrono::steady_clock::time_point startupMetricsDue{};
    friend struct PlaybackStartupTest;
    bool videoPipelineManaged = false;
    std::atomic_bool effectiveSvpActive{false};
    bool svpPendingPipelineUpdate = false;
    bool svpBypassedForSpeed = false;
    bool svpPrerollPending = false;
    bool svpPrerollResumeRequested = false;
    bool svpRuntimeReady = false;
    bool svpPrerollRewindPending = false;
    bool svpPrerollUsesInitialPosition = true;
    double svpPrerollTargetSeconds = -1.0;
    bool svpFilteredOutputReady = false;
    bool svpStartupProfileRequested = false;
    // Set once the deferred SVP filter has hit an unresolved-parameters bail, so the wait is
    // logged once rather than on every tick.
    bool svpAwaitingVideoParameters = false;
    // Held vf/hwdec writes: see setVideoFiltersPropertyLocked and releaseSvpGraphInitLatch.
    bool svpGraphInitInFlight = false;
    bool svpPendingVideoFiltersValid = false;
    std::string svpPendingVideoFilters;
    std::string svpPendingHwdec;
    std::chrono::steady_clock::time_point initialResumeWaitDeadline{};
    bool profileChanged = false; // mpvMutex
    bool firstProfileApplied = false;
    bool profileOwnsPause = false;
    bool profileResumeRequested = false;
    std::atomic_bool profileTransactionActive{false};
    std::atomic_bool profileRenderPending{false};
    std::atomic_bool profileRenderReady{false};
    std::atomic_bool profileRedrawIssued{false};
    std::string profileRenderBaseline; // mpvMutex
    std::chrono::steady_clock::time_point profileRenderDeadline{};
    std::chrono::milliseconds profileRenderBudget{0}; // mpvMutex
    std::atomic_bool redrawQueued{false};
    std::atomic_bool svpStartupProfileApplied{false};
    std::chrono::steady_clock::time_point svpProfileSettleDeadline{};
    std::chrono::steady_clock::time_point svpPrerollDeadline{};
    // Resolved once in startMpv from the "@nuvio-low-vram" marker (see lowVramProfileActive). While
    // set, the gpu-next quality knobs are pinned to their cheap values: the runtime profile rebuilds
    // in PlayerEngine would otherwise put spline36/ewa_lanczos back on the next file load.
    bool lowVramRenderingActive = false;
    std::unordered_set<std::string> lowVramClampLogged;
    bool initialRtxSuperResolutionEnabled = false;
    bool initialRtxHdrEnabled = false;
    bool initialAnimeContent = false;
    int lastReportedHdrState = -1;
    // Last "videoSourceSize" value handed to Kotlin, so a re-observation of unchanged dimensions
    // does not restart the video-profile pass. -1 means nothing reported for this source yet.
    int64_t lastReportedVideoSizePacked = -1;
    // Arms the one-shot "playbackRestart" notification below: set on FILE_LOADED, cleared by
    // the first PLAYBACK_RESTART, so the app learns when the first frame of a load rendered
    // without hearing about every post-seek restart. Only touched on the mpv event thread.
    bool playbackRestartPendingForFile = false;
    bool startupPlaybackReady = false;
    bool fileLoadedForCurrentSource = false;
    bool startupFailureReported = false;
    std::atomic<bool> playbackFailureDetected{false};
    std::string lastHttpPlaybackError;
    std::chrono::steady_clock::time_point lastHttpPlaybackErrorAt{};
    // Switching embedded MKV audio tracks makes mpv refresh-seek the remote file. If that host has
    // just returned 429, issuing the seek destroys the otherwise-playing demuxer and turns a user
    // preference change into automatic stream failover. Refuse only during the known throttle
    // window; the existing track keeps playing and the user can retry after the host recovers.
    std::atomic<std::chrono::steady_clock::time_point> audioTrackSwitchBlockedUntil{
        std::chrono::steady_clock::time_point{}
    };
    int hlsSegmentFailureCount = 0;
    std::chrono::steady_clock::time_point hlsSegmentFailureWindowStartedAt{};

    // Empty until the first refresh actually applies a mode, so it never matches and the
    // first call always sets sub-ass-override explicitly rather than assuming mpv's default.
    std::string appliedSubAssOverrideMode;
    // What the user asked for on ASS/SSA tracks specifically: mpv's sub-ass-override value, one of
    // "no" (default, the script decides everything), "scale" (script styling plus the user's
    // sub-scale) or "force" (every sub-* option applied, which is what makes the size and position
    // controls reach an ASS track at all). Plain-text tracks are unaffected and keep using "force"
    // — there is no original styling there to lose.
    std::string requestedSubAssStyleMode = "no";
    // The ASS/SSA font-size factor (mpv sub-scale). It lives beside the mode rather than being
    // written from Kotlin because sub-scale is not ASS-specific: plain-text tracks run at override
    // level "force", where it scales them too. Applying it unconditionally would have made the
    // ASS size control silently resize every SRT track as well, so it is applied only when the
    // selected track actually is ASS/SSA, and reset to 1.0 otherwise.
    double requestedSubAssScale = 1.0;
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
    // Subtitle URLs the app itself injected via addSubtitleUrl (addon / network subtitles). mpv
    // exposes both these and its own sibling-file autoloads (sub-auto) as "external" tracks, but
    // only the app-injected ones should be kept out of the built-in track list — a local-library
    // file's auto-loaded sibling .srt is a legitimate built-in-style track. Matching a track's
    // external-filename against this set is how the two are told apart. Guarded by its own mutex
    // because addSubtitleUrl (UI thread) and subtitleTracksJson (timer thread) race.
    std::unordered_set<std::string> appAddedSubtitleUrls;
    std::mutex appAddedSubtitleUrlsMutex;
    // User's desktop mpv options ("key=value"), applied just before mpv_initialize so they
    // override Nuvio's built-in options. Carries the audio-passthrough and custom-options settings.
    std::vector<std::string> extraMpvOptions;
    std::unordered_set<std::string> nuvioConfiguredOptions;
    bool recordNuvioConfiguredOptions = false;
    bool vsrLogActive = false;
    // Whether verbose-severity mpv log lines are written to disk. SVP needs verbose messages
    // *delivered* to detect its runtime-ready signal, but writing the whole verbose flood stalls
    // the event thread (open/append/close per line), so the SVP-only case delivers verbose while
    // keeping the file quiet. Only an explicit diagnostic request (env var / RTX pipeline) writes
    // the full verbose stream. See the log-level setup in startMpv and the write gate in drainMpvEvents.
    bool mpvLogVerboseToFile = false;

    friend LRESULT CALLBACK messageWindowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam);
    friend LRESULT CALLBACK containerWindowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam);
    friend LRESULT CALLBACK mediaKeyRootSubclassProc(
        HWND hwnd,
        UINT message,
        WPARAM wParam,
        LPARAM lParam,
        UINT_PTR idSubclass,
        DWORD_PTR refData
    );

    void runNativeUiThread(
        std::string sourceUrl,
        std::vector<std::string> headerLines,
        bool playWhenReady,
        long long initialPositionMs,
        double initialProgressFraction,
        std::string controlsUrl,
        bool nvidiaRtxSuperResolutionEnabled,
        bool nvidiaRtxHdrEnabled,
        std::string animeSvpFilter,
        std::shared_ptr<InitializationState> initState
    ) {
        std::string failure;
        try {
            gPlaybackThreadLog = playbackLogPath;
        nuvioBridgeLog("native ui thread entered");
            initializeOnNativeUiThread(sourceUrl, headerLines, playWhenReady, initialPositionMs, initialProgressFraction, controlsUrl, nvidiaRtxSuperResolutionEnabled, nvidiaRtxHdrEnabled, animeSvpFilter);
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
        double initialProgressFraction,
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
        startMpv(sourceUrl, headerLines, playWhenReady, initialPositionMs, initialProgressFraction, nvidiaRtxSuperResolutionEnabled, nvidiaRtxHdrEnabled, animeSvpFilter);
        nuvioBridgeLog("init layout");
        layoutNativeSubviews();
        if (!SetTimer(messageHwnd, NUVIO_TIMER_ID, 500, nullptr)) {
            throw std::runtime_error("Unable to start native player timer.");
        }
        nuvioBridgeLog("init timer started");
        // Non-fatal by design: if the media session can't be claimed the player still works,
        // it just doesn't answer media keys.
        nuvioBridgeLog("init smtc");
        initializeSystemMediaControls(playWhenReady);
    }

    void cleanupUiResources() {
        nuvioBridgeLog("cleanup begin");
        // Before OleUninitialize, and before the window this session is bound to goes away.
        teardownSystemMediaControls();
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
                                    // The persisted player setting owns page zoom; prevent Ctrl+wheel
                                    // from stacking an untracked second zoom factor on top of it.
                                    settings->put_IsZoomControlEnabled(FALSE);
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
        double initialProgressFraction,
        bool nvidiaRtxSuperResolutionEnabled,
        bool nvidiaRtxHdrEnabled,
        const std::string &animeSvpFilter
    ) {
        nuvioBridgeLog("mpv create");
        fileLoadedForCurrentSource = false;
        startupPlaybackReady = false;
        {
            // A fresh load starts with no app-injected subtitles; mpv drops the previous file's
            // external tracks on loadfile, so forget the URLs that tracked them.
            std::lock_guard<std::mutex> lock(appAddedSubtitleUrlsMutex);
            appAddedSubtitleUrls.clear();
        }
        startupFailureReported = false;
        playbackFailureDetected.store(false);
        lastHttpPlaybackError.clear();
        lastHttpPlaybackErrorAt = {};
        audioTrackSwitchBlockedUntil.store(std::chrono::steady_clock::time_point{});
        hlsSegmentFailureCount = 0;
        hlsSegmentFailureWindowStartedAt = {};
        {
            // A coalesced seek aimed at the previous file must not fire against the new one.
            std::lock_guard<std::mutex> lock(mpvMutex);
            deferredSeekTargetSeconds = -1.0;
            deferredSeekDueAt = {};
            deferredSeekStartedAt = {};
            lastSeekIssuedAt = {};
        }
        videoParamsPrimaries.clear();
        videoParamsGamma.clear();
        videoParamsPrimariesReceived = false;
        videoParamsGammaReceived = false;
        lastReportedHdrState = -1;
        lastReportedVideoSizePacked = -1;
        svpPrerollPending = !deferredAnimeSvpFilter.empty();
        svpPrerollResumeRequested = playWhenReady;
        svpPrerollUsesInitialPosition = true;
        svpPrerollTargetSeconds = -1.0;
        svpRuntimeReady = false;
        svpPrerollRewindPending = false;
        svpFilteredOutputReady = false;
        svpStartupProfileRequested = false;
        svpAwaitingVideoParameters = false;
        svpGraphInitInFlight = false;
        svpPendingVideoFiltersValid = false;
        svpPendingVideoFilters.clear();
        svpPendingHwdec.clear();
        initialResumeApplied = false;
        initialResumeWaitDeadline = {};
        svpStartupProfileApplied.store(false);
        svpProfileSettleDeadline = {};
        svpPrerollDeadline = {};
        setPythonHomeEnvironmentVariable();
        MpvApi &api = mpvApi();
        {
            std::lock_guard<std::mutex> lock(mpvMutex);
            mpv = api.create();
            if (!mpv) {
                throw std::runtime_error("mpv_create failed.");
            }
            initialStartSeconds.store(initialPositionMs > 0 ? (double)initialPositionMs / 1000.0 : 0.0);
            initialStartProgressFraction = initialPositionMs <= 0 ? initialProgressFraction : 0.0;
            initialResumeTransactionPending.store(initialPositionMs > 0 || initialProgressFraction > 0.0);
            initialResumeShouldPlay.store(playWhenReady);
            initialResumeSeekRetried.store(false);

            // Capture mpv's own log beside nuvio.log so filter/hwdec issues are visible.
            // Verbose ("v") level is only requested when diagnosing the RTX pipeline or when
            // NUVIO_MPV_VERBOSE is set: at "v" mpv emits hundreds of messages during open/probe
            // alone, and each one costs an open/append/close of the log file on the same event
            // thread that dispatches playback events to the app — measurably delaying startup
            // event handling. Normal playback keeps warnings/errors only.
            if (mpvApi().requestLogMessages) {
                vsrLogActive = true;
                const char *verboseEnv = std::getenv("NUVIO_MPV_VERBOSE");
                const bool verboseEnvSet = (verboseEnv && *verboseEnv && std::string(verboseEnv) != "0");
                // SVP's runtime-ready signal is scraped from a verbose-level VapourSynth log line
                // ("using N concurrent requests"). If verbose is not delivered, pre-roll never sees
                // that signal and can only end on the worst-case timeout — a long paused startup and
                // an unclean resume (black frame / audio underrun). So verbose must be DELIVERED
                // whenever SVP is active.
                const bool svpActive = !animeSvpFilter.empty();
                // RTX VSR / True HDR are suppressed on the anime enhancement layer (see
                // applyDesktopAnimeProfile), so their verbose pipeline logging has nothing to
                // diagnose there; only pay for it on non-anime content.
                const bool animeEnhancementActive = initialAnimeContent || svpActive;
                const bool rtxVerbose = (nvidiaRtxSuperResolutionEnabled || nvidiaRtxHdrEnabled) &&
                    !animeEnhancementActive;
                const bool deliverVerbose = verboseEnvSet || rtxVerbose || svpActive;
                // Writing the verbose flood to disk is the actual startup-jank source, so keep it
                // off for the SVP-only case: verbose is delivered for detection but the file stays
                // quiet unless we are explicitly diagnosing (env var or the RTX pipeline).
                mpvLogVerboseToFile = verboseEnvSet || rtxVerbose;
                const char *logLevel = deliverVerbose ? "v" : (svpActive ? "info" : "warn");
                nuvioMpvLogAppend(std::string("[nuvio] Logging enabled (level=") + logLevel +
                    (deliverVerbose && !mpvLogVerboseToFile ? std::string(" file=quiet") : std::string()) +
                    ")\n");
                mpvApi().requestLogMessages(mpv, logLevel);
            }

            nuvioBridgeLog("mpv set options");
            std::string configMode = "off";
            std::string lowVramMode = "auto";
            for (const std::string &option : extraMpvOptions) {
                const std::string prefix = "@nuvio-config-mode=";
                if (option.rfind(prefix, 0) == 0) configMode = option.substr(prefix.size());
                const std::string lowVramPrefix = "@nuvio-low-vram=";
                if (option.rfind(lowVramPrefix, 0) == 0) lowVramMode = option.substr(lowVramPrefix.size());
            }
            const bool fullUserConfig = configMode == "full";

            // Low-VRAM pipeline (DesktopLowVramMode). libplacebo sizes its intermediate render
            // targets from the *source* resolution — one RGBA16F surface for a 4K frame is ~63 MB,
            // and deband + linear light + the separable scalers + peak detection each want one at
            // the same time. When the driver refuses an allocation libplacebo disables FBOs
            // mid-frame, skips the main scaler, and then aborts the process on an internal size
            // assertion (renderer.c, inter_pass.img.w == out_w) — there is no point after that
            // where this can be caught, so the memory must never be asked for in the first place.
            // Reported on an Intel UHD 630 with a 2 GB shared pool playing 4K.
            //
            // 2 GiB of *dedicated* memory is the cut. Integrated graphics report a token pool far
            // below it; a card that reports more has room for the full pipeline. When DXGI cannot
            // answer, the full pipeline stays: one machine crashing is a smaller failure than every
            // machine silently losing scaler quality to a broken probe.
            uint64_t adapterVideoMemory = 0;
            std::string adapterName;
            const bool adapterQueried = lowVramMode == "auto" &&
                queryLargestAdapterVideoMemory(&adapterVideoMemory, &adapterName);
            constexpr uint64_t lowVramThresholdBytes = 2ull * 1024 * 1024 * 1024;
            lowVramRenderingActive = lowVramMode == "on" ||
                (lowVramMode == "auto" && adapterQueried && adapterVideoMemory < lowVramThresholdBytes);
            nuvioMpvLogAppend("[nuvio] low-VRAM pipeline " +
                std::string(lowVramRenderingActive ? "active" : "inactive") + " mode=" + lowVramMode +
                " adapter=" + (adapterQueried ? adapterName : std::string("(not queried)")) +
                " dedicatedVideoMemory=" +
                (adapterQueried ? std::to_string(adapterVideoMemory / (1024 * 1024)) + "MiB"
                                : std::string("unknown")) + "\n");
            nuvioConfiguredOptions.clear();
            recordNuvioConfiguredOptions = true;

            // Required for safe ownership of an embedded mpv surface in Nuvio.
            setMpvOptionStringLocked("config", "no");
            setMpvOptionStringLocked("osc", "no");
            setMpvOptionStringLocked("input-vo-keyboard", "no");
            setMpvOptionStringLocked("keep-open", "yes");
            // Start with no subtitle instead of allowing mpv's default/forced-track heuristics to
            // briefly select an arbitrary language. Nuvio applies the saved/preferred language
            // once the native track list is available.
            setMpvOptionStringLocked("sid", "no");
            setMpvOptionStringLocked("vo", "gpu-next");
            setMpvOptionStringLocked("gpu-api", "d3d11");

            if (!fullUserConfig) {
            setMpvOptionStringLocked("input-default-bindings", "yes");
            // mpv's bundled stats overlay defaults to 20px text / 1.65px border. Keep diagnostics
            // readable but less dominant over the video by rendering it at roughly 75% size.
            setMpvOptionStringLocked("script-opts", "stats-font_size=15,stats-border_size=1.25");
            // Persist compiled gpu-next shaders across launches. config=no (above) leaves mpv with
            // no default cache location, so without an explicit dir every launch recompiles the
            // whole shader pipeline (see nuvioMpvShaderCacheDirectoryUtf8). This turns the
            // HLSL->DXBC "(slow!)" startup burst — worst with the Anime4K / custom GLSL chains —
            // into a one-time cost.
            setMpvOptionStringLocked("gpu-shader-cache", "yes");
            const std::string shaderCacheDir = nuvioMpvShaderCacheDirectoryUtf8();
            if (!shaderCacheDir.empty()) {
                setMpvOptionStringLocked("gpu-shader-cache-dir", shaderCacheDir.c_str());
            }
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

            // Streaming cache (1x baseline; setSpeed scales these with playback rate)
            setMpvOptionStringLocked("cache", "yes");
            setMpvOptionStringLocked("cache-pause", "yes");
            setMpvOptionStringLocked("cache-pause-initial", "yes");
            // How much media must be buffered before (re)starting playback — this directly
            // sets time-to-first-frame and post-seek resume latency. The large readahead
            // above provides the actual stall resilience once playing, so keep this small.
            setMpvOptionStringLocked("cache-pause-wait", "0.25");
            setMpvOptionStringLocked("cache-secs", std::to_string((long long)baseCacheSecs).c_str());
            setMpvOptionStringLocked("demuxer-readahead-secs", std::to_string((long long)baseReadaheadSecs).c_str());
            // Separate YouTube video/audio streams can exhaust the byte ceiling well before
            // the requested time-based readahead at 2x, especially for high-bitrate trailers.
            setMpvOptionStringLocked("demuxer-max-bytes", "1GiB");
            setMpvOptionStringLocked("demuxer-max-back-bytes", "128MiB");
            // This is the low-level demuxer/I/O ring, not the media cache. A huge value can make
            // forward seeks read and discard the intervening bytes instead of issuing an HTTP
            // range request. The Kotlin preset options override this before mpv_initialize for
            // main playback; keep the fallback modest for auxiliary players as well.
            setMpvOptionStringLocked("stream-buffer-size", "1MiB");
            // HTTP reconnect options — skip for local file paths (no scheme = local file).
            bool isLocalFile = sourceUrl.find("://") == std::string::npos ||
                               sourceUrl.rfind("file://", 0) == 0;
            if (!isLocalFile) {
                // Deliberately NOT setting reconnect_on_http_error. Adding 429 to it was tried and
                // reverted (2026-07-25): FFmpeg then retries a throttled request four more times
                // (0s/1s/3s backoff) *inside* a single source attempt, which is invisible to the
                // Kotlin rate-limit guard in PlaybackSourceFailure.kt. On a provider that is
                // actively rate-limiting, a failover walk therefore multiplies the request volume
                // against the very host that is refusing, and adds ~4-5s to every failed start —
                // the "429-looping the debrid provider" behaviour that guard exists to prevent.
                // Failing fast here lets the guard scope the failover and move on.
                setMpvOptionStringLocked("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5");
            }

            setMpvOptionStringLocked("hr-seek", "no");

            // Permit software amplification above 100% so quiet content can be boosted.
            // setVolume() clamps to this ceiling; the shared volume model caps at 200%.
            setMpvOptionStringLocked("volume-max", "200");
            }

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
                if (option.rfind("@nuvio-trace-id=", 0) == 0) continue;
                if (option.rfind("@nuvio-config-mode=", 0) == 0) continue;
                if (option.rfind("@nuvio-low-vram=", 0) == 0) continue;
                const bool userOption = option.rfind("@nuvio-user:", 0) == 0;
                const std::string optionText = userOption ? option.substr(12) : option;
                std::string::size_type equals = optionText.find('=');
                if (equals == std::string::npos) continue;
                std::string key = optionText.substr(0, equals);
                std::string value = optionText.substr(equals + 1);
                if (key.empty()) continue;
                if (userOption && value.size() >= 2 &&
                    ((value.front() == '"' && value.back() == '"') ||
                     (value.front() == '\'' && value.back() == '\''))) {
                    // The advanced box deliberately accepts mpv.conf syntax. mpv's option API,
                    // unlike the config-file parser, does not remove surrounding quotes itself.
                    value = value.substr(1, value.size() - 2);
                }
                if (userOption &&
                    ((configMode == "add" && nuvioConfiguredOptions.count(key) > 0) ||
                     (fullUserConfig && nuvioConfiguredOptions.count(key) > 0))) {
                    nuvioMpvLogAppend("[nuvio] custom mpv option kept below embedding requirement: " + key + "\n");
                    continue;
                }
                recordNuvioConfiguredOptions = !userOption;
                if (!setMpvOptionStringLocked(key.c_str(), value.c_str())) {
                    nuvioMpvLogAppend("[nuvio] ignored custom mpv option: " + key + "\n");
                }
            }
            recordNuvioConfiguredOptions = false;

            // Low VRAM: strip the pipeline back to what a small pool can allocate. Bilinear needs no
            // intermediate FBO at all (libplacebo samples it directly); deband, sigmoid/linear light
            // and peak detection are the other source-sized allocations.
            //
            // Applied after everything else, including the user's own options and whatever config
            // mode they are in, because this is a crash guard rather than a quality preference: an
            // "Upscaling" choice from the Advanced (mpv) menu that reintroduced spline36 here would
            // abort the process on the first frame, before any of the runtime pinning below could
            // see it. Users who would rather have the quality set Low VRAM Mode to Off, which is
            // what that option is for.
            //
            // Anime4K and custom GLSL chains are deliberately untouched: they are an explicit,
            // visible choice, and dropping them silently would be the bigger surprise.
            if (lowVramRenderingActive) {
                setMpvOptionStringLocked("scale", "bilinear");
                setMpvOptionStringLocked("dscale", "bilinear");
                setMpvOptionStringLocked("cscale", "bilinear");
                setMpvOptionStringLocked("sigmoid-upscaling", "no");
                setMpvOptionStringLocked("correct-downscaling", "no");
                setMpvOptionStringLocked("deband", "no");
                setMpvOptionStringLocked("dither", "no");
                setMpvOptionStringLocked("temporal-dither", "no");
                setMpvOptionStringLocked("hdr-compute-peak", "no");
            }

            nuvioBridgeLog("mpv initialize");
            int initResult = api.initialize(mpv);
            if (initResult < 0) {
                throw std::runtime_error(std::string("mpv_initialize failed: ") + api.errorText(initResult));
            }
            nuvioBridgeLog("mpv initialized");

            api.observeProperty(mpv, 0, "video-params/primaries", MPV_FORMAT_STRING);
            api.observeProperty(mpv, 0, "video-params/gamma", MPV_FORMAT_STRING);
            // An adaptive stream can switch resolution mid-file; the shader decision has to follow
            // it, so the size is observed rather than only sampled at FILE_LOADED.
            api.observeProperty(mpv, 0, "video-params/w", MPV_FORMAT_STRING);
            api.observeProperty(mpv, 0, "video-params/h", MPV_FORMAT_STRING);
            for (const char *property : {"hwdec-current", "paused-for-cache", "audio-params/format", "speed"}) {
                api.observeProperty(mpv, 0, property, MPV_FORMAT_STRING);
            }

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

            // Resume is applied at FILE_LOADED, once mpv exposes the real duration. Passing a
            // start option here can seek directly to (or a few milliseconds beyond) EOF before
            // Nuvio has enough information to apply its near-end reset policy.
            std::vector<const char *> loadCommand = {"loadfile", playbackSource.c_str()};
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
            // Normalize to the HUD's 192-DPI baseline, then apply the saved UI setting as actual
            // browser zoom. This makes breakpoints and every visual dimension rescale together.
            HWND dpiWindow = containerHwnd && IsWindow(containerHwnd) ? containerHwnd : hostHwnd;
            UINT dpi = GetDpiForWindow(dpiWindow);
            if (dpi == 0) dpi = USER_DEFAULT_SCREEN_DPI;
            double desiredZoomFactor = (192.0 / static_cast<double>(dpi)) * controlsUiScaleFactor;
            if (std::abs(desiredZoomFactor - controlsZoomFactor) > 0.001) {
                if (SUCCEEDED(controller->put_ZoomFactor(desiredZoomFactor))) {
                    controlsZoomFactor = desiredZoomFactor;
                }
            }
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
        // Keep the media session's status honest — Windows picks the app it routes media keys
        // to by playback status, so a stale value costs us the keys.
        updateSystemMediaPlaybackStatus(paused);
        // Track metadata (titles, languages, codecs) is immutable per track; the lists only
        // change when tracks appear/disappear or the selection moves, so key the cache on
        // count + selected audio/subtitle ids instead of re-reading every track each tick.
        const long long trackCount = int64Property("track-list/count", 0);
        std::string selectedTracksKey;
        for (long long index = 0; index < trackCount; index++) {
            const std::string selectedProperty =
                "track-list/" + std::to_string(index) + "/selected";
            if (flagProperty(selectedProperty.c_str(), false)) {
                if (!selectedTracksKey.empty()) selectedTracksKey += ",";
                selectedTracksKey += std::to_string(index);
            }
        }
        std::string tracksKey = std::to_string(trackCount) +
            "|" + stringProperty("aid", "") + "|" + stringProperty("sid", "") +
            "|" + stringProperty("current-tracks/sub/id", "") +
            "|" + selectedTracksKey;
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
            if (webView) {
                for (const std::string &script : pendingControlsScripts) {
                    webView->ExecuteScript(toWide(script).c_str(), nullptr);
                }
            }
            pendingControlsScripts.clear();
            syncControls();
            return;
        }
        if (type == "setControlsUiScalePercent") {
            controlsUiScaleFactor = std::max(0.5, std::min(1.5, 1.0 + value / 100.0));
            layoutNativeSubviews();
            return;
        }
        // Audio rows send the app's zero-based logical track index. Forward that index to
        // Kotlin, which resolves it against the live track list before calling
        // selectAudioTrackId with mpv's (usually one-based, potentially sparse) track id.
        // Handling it here treated index 1 as aid=1, so clicking the second row simply
        // reselected the first audio track and also bypassed preference persistence.
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

    /**
     * Whether the display this window sits on currently has Windows HDR switched on.
     *
     * RTX True HDR can only reach the screen through an HDR swapchain, so with the Windows toggle
     * off the filter runs and changes nothing visible. Reporting it as active would be a lie, hence
     * this second opinion on top of the app's own setting.
     *
     * dxgi.dll is resolved on demand so the bridge gains no import on it: a failure to load, or a
     * driver that cannot answer, reports "not HDR" — the conservative direction, since the cost of
     * a false negative is one missing status line and the cost of a false positive is the exact
     * misinformation this exists to stop.
     */
    static bool isDisplayHdrEnabled(HWND hwnd) {
        using CreateDXGIFactory1Fn = HRESULT(WINAPI *)(REFIID, void **);
        static CreateDXGIFactory1Fn createFactory = [] () -> CreateDXGIFactory1Fn {
            HMODULE dxgi = LoadLibraryW(L"dxgi.dll");
            if (!dxgi) return nullptr;
            return reinterpret_cast<CreateDXGIFactory1Fn>(GetProcAddress(dxgi, "CreateDXGIFactory1"));
        }();
        if (!createFactory) return false;

        ComPtr<IDXGIFactory1> factory;
        if (FAILED(createFactory(IID_PPV_ARGS(&factory)))) return false;

        const HMONITOR target = hwnd ? MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST) : nullptr;
        bool anyOutputIsHdr = false;
        for (UINT adapterIndex = 0;; ++adapterIndex) {
            ComPtr<IDXGIAdapter1> adapter;
            if (FAILED(factory->EnumAdapters1(adapterIndex, &adapter))) break;
            for (UINT outputIndex = 0;; ++outputIndex) {
                ComPtr<IDXGIOutput> output;
                if (FAILED(adapter->EnumOutputs(outputIndex, &output))) break;
                ComPtr<IDXGIOutput6> output6;
                if (FAILED(output.As(&output6))) continue;
                DXGI_OUTPUT_DESC1 desc{};
                if (FAILED(output6->GetDesc1(&desc))) continue;
                const bool outputIsHdr =
                    desc.ColorSpace == DXGI_COLOR_SPACE_RGB_FULL_G2084_NONE_P2020;
                // The window's own monitor is the authoritative answer — a second, HDR display
                // elsewhere on the desktop says nothing about what this window is showing on.
                if (target && desc.Monitor == target) return outputIsHdr;
                anyOutputIsHdr = anyOutputIsHdr || outputIsHdr;
            }
        }
        // The window's monitor was never enumerated (no window yet, or a hybrid-GPU quirk); the
        // desktop-wide answer is the best remaining evidence.
        return anyOutputIsHdr;
    }

    /**
     * Writes the largest dedicated video-memory pool reported by any hardware DXGI adapter, plus
     * that adapter's name. Returns false when DXGI cannot be asked or enumerated nothing usable.
     *
     * Integrated graphics report a token dedicated pool (typically 128 MB) and take everything else
     * from system RAM, so this doubles as an "is there a real GPU in this machine" probe. The maximum
     * across adapters is deliberate: on a hybrid laptop the presence of a discrete card means the
     * machine is not the one the low-VRAM pipeline exists for, whichever adapter D3D11 ends up on.
     *
     * dxgi.dll is resolved on demand, matching isDisplayHdrEnabled, so the bridge gains no import.
     */
    static bool queryLargestAdapterVideoMemory(uint64_t *bytes, std::string *adapterName) {
        using CreateDXGIFactory1Fn = HRESULT(WINAPI *)(REFIID, void **);
        static CreateDXGIFactory1Fn createFactory = [] () -> CreateDXGIFactory1Fn {
            HMODULE dxgi = LoadLibraryW(L"dxgi.dll");
            if (!dxgi) return nullptr;
            return reinterpret_cast<CreateDXGIFactory1Fn>(GetProcAddress(dxgi, "CreateDXGIFactory1"));
        }();
        if (!createFactory) return false;

        ComPtr<IDXGIFactory1> factory;
        if (FAILED(createFactory(IID_PPV_ARGS(&factory)))) return false;

        bool found = false;
        for (UINT adapterIndex = 0;; ++adapterIndex) {
            ComPtr<IDXGIAdapter1> adapter;
            if (FAILED(factory->EnumAdapters1(adapterIndex, &adapter))) break;
            DXGI_ADAPTER_DESC1 desc{};
            if (FAILED(adapter->GetDesc1(&desc))) continue;
            // WARP / the Basic Render Driver rasterise on the CPU; they have no video memory to
            // report and are exactly the case the low-VRAM pipeline should cover, not exclude.
            // Counted rather than skipped: skipping made a software-only machine report *no*
            // adapter, which the caller reads as "probe failed" and answers with the full pipeline
            // — the opposite of the intent stated here. A 0-byte pool cannot displace a real card's
            // maximum, so this is free on every machine that has one.
            const uint64_t dedicated = static_cast<uint64_t>(desc.DedicatedVideoMemory);
            if (found && dedicated < *bytes) continue;
            found = true;
            *bytes = dedicated;
            if (adapterName) *adapterName = toUtf8(std::wstring(desc.Description));
        }
        return found;
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
        gPlaybackThreadLog = playbackLogPath;
        while (!stopping.load()) {
            mpv_handle *current = nullptr;
            {
                std::lock_guard<std::mutex> lock(mpvMutex);
                current = mpv;
            }
            if (!current) {
                return;
            }

            // While a coalesced seek is waiting on its deadline the loop needs a finer tick than
            // the idle 0.5s, which while paused (few spontaneous events) would otherwise let it
            // land hundreds of ms late.
            mpv_event *event = mpvApi().waitEvent(current, (deferredSeekPending() || initialResumeTransactionPending.load() || svpPrerollPending || profileRenderPending.load()) ? 0.05 : 0.5);
            // The deadline is driven by wall clock, not by events, so it must be serviced even on
            // a wait that produced nothing to dispatch.
            flushDeferredSeek();
            // Both of these wait on the demuxer resolving real stream parameters, which can land
            // after FILE_LOADED with no event of its own — so they are retried on the tick rather
            // than applied once at load. Each is a cheap no-op after it has run.
            tryApplyInitialResume("params-retry");
            tryApplyDeferredAnimeSvp("params-retry");
            if (!event) continue;
            // Poll the SVP filtered-output promotion here (not just on PLAYBACK_RESTART): at a
            // position-0 start the runtime-ready signal can arrive after the only restart event,
            // and this periodic tick is what then advances pre-roll instead of the worst-case timeout.
            tryCompleteInitialResume(false);
            tryPromoteSvpFilteredOutput();
            if (svpPrerollPending && !effectiveSvpActive && deferredAnimeSvpFilter.empty() && !svpGraphInitInFlight) {
                finishSvpPreroll("svp-disabled-or-bypassed");
                publishPlaybackRestartIfReady();
            }
            pollProfileRender();
            if (startupMetricsDue != std::chrono::steady_clock::time_point{} &&
                std::chrono::steady_clock::now() >= startupMetricsDue) {
                startupMetricsDue = {};
                nuvioBridgeLog("startup health pause=" + stringProperty("pause", "unknown") +
                    " seeking=" + stringProperty("seeking", "unknown") +
                    " resumePending=" + (initialResumeTransactionPending.load() ? "yes" : "no") +
                    " svpPending=" + (svpPrerollPending ? "yes" : "no") +
                    " position=" + std::to_string(doubleProperty("time-pos", -1.0)) +
                    " avsync=" + std::to_string(doubleProperty("avsync", 0.0)) +
                    " dropped=" + std::to_string(int64Property("frame-drop-count", 0)) +
                    " decoderDropped=" + std::to_string(int64Property("decoder-frame-drop-count", 0)) +
                    " cacheAhead=" + std::to_string(cacheAheadSeconds()));
            }
            if (svpPrerollPending && svpFilteredOutputReady && svpStartupProfileApplied.load()) {
                const auto now = std::chrono::steady_clock::now();
                if (svpProfileSettleDeadline == std::chrono::steady_clock::time_point{}) {
                    // Render-pass samples release cached profiles as soon as final rendering is
                    // observed. Retain the previous bounded wait only for VOs without usable
                    // measurements; requesting a shader or receiving VIDEO_RECONFIG alone is
                    // not evidence that its GPU work has completed.
                    const bool heavyShaders = !stringProperty("glsl-shaders", "").empty();
                    svpProfileSettleDeadline = now +
                        std::chrono::milliseconds(heavyShaders ? 1800 : 900);
                    svpPrerollDeadline = now + std::chrono::seconds(heavyShaders ? 5 : 3);
                    nuvioMpvLogAppend(std::string("[nuvio] SVP startup profile acknowledged; "
                        "settling shaders heavy=") + (heavyShaders ? "yes" : "no") + "\n");
                } else if (profileRenderReady.load() || now >= svpProfileSettleDeadline) {
                    finishSvpPreroll("profile-applied-at-start-position");
                    publishPlaybackRestartIfReady();
                }
            }
            if (svpPrerollPending && svpPrerollDeadline != std::chrono::steady_clock::time_point{} &&
                std::chrono::steady_clock::now() >= svpPrerollDeadline) {
                finishSvpPreroll("timeout");
                publishPlaybackRestartIfReady();
            }
            publishPlaybackRestartIfReady();
            if (event->event_id == MPV_EVENT_SHUTDOWN) {
                return;
            }
            if (event->event_id == MPV_EVENT_LOG_MESSAGE && event->data) {
                auto *msg = static_cast<mpv_event_log_message *>(event->data);
                if (msg && msg->prefix && msg->level && msg->text) {
                    std::string level(msg->level);
                    std::string prefix(msg->prefix);
                    std::string message(msg->text);
                    // Verbose-severity lines are delivered (SVP readiness detection below needs
                    // them) but only written to disk when explicitly diagnosing — see
                    // mpvLogVerboseToFile. This keeps the event thread off the log file during the
                    // verbose SVP/RTX startup burst while preserving the signals we parse.
                    const bool isVerboseSeverity = (level == "v" || level == "debug" || level == "trace");
                    if (vsrLogActive && (mpvLogVerboseToFile || !isVerboseSeverity)) {
                        std::string line = std::string("[") + level + "] " + prefix + ": " + message;
                        if (shouldWriteMpvLogLine(line)) {
                            nuvioMpvLogAppend(line);
                        }
                    }
                    while (!message.empty() && (message.back() == '\n' || message.back() == '\r')) {
                        message.pop_back();
                    }
                    // `vf` and `hwdec-current` describe the requested/input path and become
                    // observable before VapourSynth has constructed its processing graph. Its
                    // own worker-pool announcement is the first unambiguous runtime-ready point;
                    // the following PLAYBACK_RESTART then represents filtered output.
                    if (svpPrerollPending && prefix == "vapoursynth" &&
                        message.find("concurrent requests") != std::string::npos) {
                        svpRuntimeReady = true;
                        nuvioMpvLogAppend("[nuvio] SVP runtime ready; awaiting filtered playback restart\n");
                        // Filtered output is usually already flowing by the time the worker pool
                        // announces itself, so promote right away rather than waiting for the next
                        // restart/tick — this is the common position-0 path.
                        tryPromoteSvpFilteredOutput();
                    }
                    if (prefix == "ffmpeg" && message.find("HTTP error") != std::string::npos) {
                        lastHttpPlaybackError = message;
                        lastHttpPlaybackErrorAt = std::chrono::steady_clock::now();
                        if (message.find("HTTP error 429") != std::string::npos) {
                            audioTrackSwitchBlockedUntil.store(
                                lastHttpPlaybackErrorAt + std::chrono::seconds(10));
                        }
                    }
                    const bool isHlsSegmentFailure = prefix == "ffmpeg/demuxer" &&
                        message.find("failed too many times, skipping") != std::string::npos;
                    if (isHlsSegmentFailure && !startupFailureReported) {
                        const auto now = std::chrono::steady_clock::now();
                        if (hlsSegmentFailureWindowStartedAt == std::chrono::steady_clock::time_point{} ||
                            now - hlsSegmentFailureWindowStartedAt > std::chrono::seconds(12)) {
                            hlsSegmentFailureWindowStartedAt = now;
                            hlsSegmentFailureCount = 0;
                        }
                        ++hlsSegmentFailureCount;
                        if (hlsSegmentFailureCount >= 8) {
                            startupFailureReported = true;
                            playbackFailureDetected.store(true);
                            const bool recentHttpError = !lastHttpPlaybackError.empty() &&
                                lastHttpPlaybackErrorAt != std::chrono::steady_clock::time_point{} &&
                                now - lastHttpPlaybackErrorAt < std::chrono::seconds(15);
                            const std::string failureMessage = recentHttpError
                                ? lastHttpPlaybackError + "; HLS media segments repeatedly failed to load"
                                : "HLS media segments repeatedly failed to load";
                            nuvioBridgeLog("sustained HLS segment failure reported: " + failureMessage);
                            sendPlayerEvent("mpvPlaybackError:" + failureMessage, 0.0);
                            const char *stopCommand[] = {"stop", nullptr};
                            mpvApi().command(mpv, stopCommand);
                        }
                    }
                    const bool isMajorStartupError = !fileLoadedForCurrentSource &&
                        (level == "error" || level == "fatal") &&
                        prefix == "cplayer" &&
                        (message.find("Failed to recognize file format") != std::string::npos ||
                            message.find("Failed to open") != std::string::npos ||
                            message.find("Cannot open") != std::string::npos);
                    if (isMajorStartupError && !startupFailureReported) {
                        startupFailureReported = true;
                        playbackFailureDetected.store(true);
                        sendPlayerEvent("mpvStartupError:" + message, 0.0);
                    }
                    const bool recentHttpError = !lastHttpPlaybackError.empty() &&
                        lastHttpPlaybackErrorAt != std::chrono::steady_clock::time_point{} &&
                        std::chrono::steady_clock::now() - lastHttpPlaybackErrorAt < std::chrono::seconds(10);
                    // A "Seek failed" is terminal for the source, before or after FILE_LOADED: the
                    // HTTP stream is left at EOF and the demuxer reports EOF on the very next read,
                    // so re-seeking within this mpv instance cannot recover it — only a fresh
                    // demuxer can. It is also not a blip. stream-lavf-o sets reconnect_on_http_error,
                    // so FFmpeg has already retried a throttled range request on its own backoff
                    // ("Will reconnect ... in N second(s)") and given up; by the time this surfaces
                    // the host has been refusing for seconds, which is what failover is for.
                    //
                    // Do not let the dead demuxer continue into EOF/restart events while the Kotlin
                    // layer resolves a failover source: its time-pos jumps to duration and gets
                    // mistaken for a legitimate completion, ending a half-watched file.
                    const bool isDefinitiveSeekFailure = !startupFailureReported &&
                        (level == "error" || level == "fatal") &&
                        prefix == "ffmpeg" &&
                        message.find("Seek failed") != std::string::npos &&
                        (fileLoadedForCurrentSource || recentHttpError);
                    if (isDefinitiveSeekFailure) {
                        startupFailureReported = true;
                        playbackFailureDetected.store(true);
                        const std::string failureMessage = recentHttpError
                            ? lastHttpPlaybackError + "; " + message
                            : "Playback seek failed: " + message;
                        nuvioBridgeLog("definitive seek failure reported: " + failureMessage);
                        sendPlayerEvent("mpvPlaybackError:" + failureMessage, 0.0);
                        const char *stopCommand[] = {"stop", nullptr};
                        mpvApi().command(mpv, stopCommand);
                    }
                }
            }
            if (event->event_id == MPV_EVENT_END_FILE && event->data) {
                auto *endFile = static_cast<mpv_event_end_file *>(event->data);
                if (endFile && endFile->reason == MPV_END_FILE_REASON_ERROR && !startupFailureReported) {
                    startupFailureReported = true;
                    playbackFailureDetected.store(true);
                    const bool recentHttpError = !lastHttpPlaybackError.empty() &&
                        lastHttpPlaybackErrorAt != std::chrono::steady_clock::time_point{} &&
                        std::chrono::steady_clock::now() - lastHttpPlaybackErrorAt < std::chrono::seconds(10);
                    std::string message = recentHttpError ? lastHttpPlaybackError : std::string();
                    if (message.empty()) {
                        message = "Playback loading failed";
                        if (endFile->error < 0) {
                            message += ": " + mpvApi().errorText(endFile->error);
                        }
                    }
                    sendPlayerEvent("mpvStartupError:" + message, 0.0);
                }
            }
            if (event->event_id == MPV_EVENT_PLAYBACK_RESTART && playbackRestartPendingForFile) {
                startupPlaybackReady = true;
                const bool initialResumeReady = tryCompleteInitialResume(true);
                if (!initialResumeReady) {
                    // Ignore the pre-seek restart/EOF. A later PLAYBACK_RESTART at the validated
                    // target completes the transaction and is the only startup exposed to Kotlin.
                } else if (svpPrerollPending) {
                    if (!tryPromoteSvpFilteredOutput() && !svpFilteredOutputReady) {
                        nuvioMpvLogAppend("[nuvio] SVP pre-roll waiting vf=" +
                            stringProperty("vf", "") +
                            " hwdec-current=" + stringProperty("hwdec-current", "") +
                            " output=" + stringProperty("video-out-params/pixelformat", "") + "\n");
                    }
                } else {
                    publishPlaybackRestartIfReady();
                }
            }
            if (event->event_id == MPV_EVENT_START_FILE) nuvioBridgeLog("media open started");
            if (event->event_id == MPV_EVENT_VIDEO_RECONFIG) nuvioBridgeLog("video reconfigured");
            if (event->event_id == MPV_EVENT_AUDIO_RECONFIG) nuvioBridgeLog("audio reconfigured");
            if (event->event_id == MPV_EVENT_FILE_LOADED) {
                startupMetricsDue = std::chrono::steady_clock::now() + std::chrono::seconds(5);
                nuvioBridgeLog("file loaded");
                fileLoadedForCurrentSource = true;
                playbackRestartPendingForFile = true;
                tryApplyInitialResume("fileLoaded");
                // Ahead of the HDR event on purpose: both land in the same burst, and Kotlin holds
                // the anime shader chain back while the size is unknown, so reporting the size
                // second would cost an extra full video-profile pass on every load.
                reportVideoSourceSize();
                const std::string resolvedPrimaries = stringProperty("video-params/primaries", "");
                const std::string resolvedGamma = stringProperty("video-params/gamma", "");
                const bool hdrStateKnown = !resolvedPrimaries.empty() && !resolvedGamma.empty();
                const bool resolvedHdr = hdrStateKnown && isHdrContent(resolvedPrimaries, resolvedGamma);
                if (hdrStateKnown) {
                    const int nextHdrState = resolvedHdr ? 1 : 0;
                    if (lastReportedHdrState != nextHdrState) {
                        lastReportedHdrState = nextHdrState;
                        sendPlayerEvent("videoParams", resolvedHdr ? 1.0 : 0.0);
                    }
                }

                // Re-read per file rather than once per session: the user can flip the Windows HDR
                // toggle (or drag the app to another display) between titles.
                sendPlayerEvent("displayHdr", isDisplayHdrEnabled(containerHwnd) ? 1.0 : 0.0);

                int64_t videoWidth = int64Property("video-params/w", 0);
                int64_t videoHeight = int64Property("video-params/h", 0);
                double vsrScale = 1.0;
                RECT surfaceBounds{};
                if (videoWidth > 0 && videoHeight > 0 && containerHwnd &&
                    GetClientRect(containerHwnd, &surfaceBounds)) {
                    double surfaceWidth = (double)(surfaceBounds.right - surfaceBounds.left);
                    double surfaceHeight = (double)(surfaceBounds.bottom - surfaceBounds.top);
                    double widthScale = surfaceWidth / (double)videoWidth;
                    double heightScale = surfaceHeight / (double)videoHeight;
                    const double requestedVsrScale =
                        std::max(1.0, std::min(4.0, std::min(widthScale, heightScale)));
                    vsrScale = cropSafeD3d11vppScale(
                        videoWidth,
                        videoHeight,
                        requestedVsrScale
                    );
                    sendPlayerEvent("videoVsrScale", vsrScale);
                    if (vsrLogActive) {
                        nuvioMpvLogAppend("[nuvio] dynamic VSR scale=" + std::to_string(vsrScale) +
                            " requested=" + std::to_string(requestedVsrScale) +
                            " source=" + std::to_string(videoWidth) + "x" + std::to_string(videoHeight) +
                            " surface=" + std::to_string((long long)surfaceWidth) + "x" +
                            std::to_string((long long)surfaceHeight) + "\n");
                    }
                }

                // Install the final live-action RTX chain before the first playback restart.
                // Kotlin constructs the same canonical string later, so its profile refresh is a
                // no-op instead of tearing down and rebuilding an active D3D11 video processor.
                if (!initialAnimeContent && hdrStateKnown) {
                    // NVIDIA's d3d11vpp VSR path can output a solid green plane for native HDR,
                    // particularly Dolby Vision P010. VSR is an SDR upscale enhancement here;
                    // HDR remains on gpu-next's normal colour-managed path.
                    const bool vsrActive = initialRtxSuperResolutionEnabled &&
                        !resolvedHdr && vsrScale > 1.01;
                    const bool trueHdrActive = initialRtxHdrEnabled && !resolvedHdr;
                    std::string initialRtxFilters;
                    if (vsrActive || trueHdrActive) {
                        initialRtxFilters = "d3d11vpp=";
                        if (vsrActive) {
                            std::string scaleText = roundTripFloatText(static_cast<float>(vsrScale));
                            initialRtxFilters += "scale=" + scaleText + ":scaling-mode=nvidia";
                            if (trueHdrActive) initialRtxFilters += ":";
                        }
                        if (trueHdrActive) initialRtxFilters += "nvidia-true-hdr=yes";
                    }
                    std::lock_guard<std::mutex> lock(mpvMutex);
                    if (mpv) {
                        requestedVideoFilters = initialRtxFilters;
                        setVideoFiltersPropertyLocked(initialRtxFilters);
                        nuvioMpvLogAppend("[nuvio] initial RTX filter handled at fileLoaded hdr=" +
                            std::string(resolvedHdr ? "yes" : "no") + " vf=" + initialRtxFilters + "\n");
                    }
                }

                // A known 1x anime session can prepare SVP here: playback has not restarted yet,
                // which avoids both unsafe pre-probe VapourSynth initialization and the old
                // post-first-frame graph rebuild that produced a black dummy frame plus an
                // audio-device underrun. FILE_LOADED does not guarantee the demuxer has resolved
                // the real video stream, though, so the apply itself re-checks and retries.
                tryApplyDeferredAnimeSvp("fileLoaded");
                {
                    // A stale seek target from the previous file must not seed relative
                    // seeks issued right after a source switch.
                    std::lock_guard<std::mutex> lock(mpvMutex);
                    pendingSeekTargetSeconds = -1.0;
                    deferredSeekTargetSeconds = -1.0;
                    deferredSeekDueAt = {};
                    deferredSeekStartedAt = {};
                    lastSeekIssuedAt = {};
                }
                sendPlayerEvent("fileLoaded", 1.0);
                subtitleAssOverrideCheckDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
                subtitleAssOverrideInitialCheckPending = true;
                eofSuppressedUntil.store(std::chrono::steady_clock::now() + std::chrono::seconds(3));
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
                if (hasValue && (propName == "hwdec-current" || propName == "paused-for-cache" ||
                    propName == "audio-params/format" || propName == "speed")) {
                    nuvioBridgeLog("property " + propName + "=" + propValue);
                }

                if (propName == "speed" && hasValue) {
                    std::lock_guard<std::mutex> lock(mpvMutex);
                    double currentSpeed = 1.0;
                    if (mpv && tryGetDoubleLocked("speed", currentSpeed)) applySpeedSensitiveVideoFiltersLocked(currentSpeed);
                }

                // mpv first emits unavailable/empty observations for both properties. Those mean
                // "not known yet", not SDR; treating them as a complete pair briefly enabled RTX
                // Video HDR on native HDR/Dolby Vision content until the real values arrived.
                if (propName == "video-params/w" || propName == "video-params/h") {
                    // Read both back rather than trusting one observation: the pair is only
                    // meaningful together, and reportVideoSourceSize dedups an unchanged size.
                    reportVideoSourceSize();
                }

                if (propName == "video-params/primaries" && hasValue && !propValue.empty()) {
                    videoParamsPrimaries = propValue;
                    videoParamsPrimariesReceived = true;
                } else if (propName == "video-params/gamma" && hasValue && !propValue.empty()) {
                    videoParamsGamma = propValue;
                    videoParamsGammaReceived = true;
                }

                if (videoParamsPrimariesReceived && videoParamsGammaReceived) {
                    bool hdr = isHdrContent(videoParamsPrimaries, videoParamsGamma);
                    videoParamsPrimariesReceived = false;
                    videoParamsGammaReceived = false;
                    const int nextHdrState = hdr ? 1 : 0;
                    if (lastReportedHdrState != nextHdrState) {
                        lastReportedHdrState = nextHdrState;
                        sendPlayerEvent("videoParams", hdr ? 1.0 : 0.0);
                    }
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
        if (type == "playbackRestart" || type == "svpPrerollReady" || type == "profileReady") {
            nuvioBridgeLog("event " + type);
        }

        std::lock_guard<std::mutex> eventLock(eventSinkMutex);
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

    // --- System Media Transport Controls -------------------------------------------------
    // All of these run on the native UI thread (the STA that called OleInitialize and owns the
    // message loop), except the ButtonPressed callback, which Windows raises on a thread-pool
    // thread and which only touches sendPlayerEvent (JNI-attach safe).

    void initializeSystemMediaControls(bool playWhenReady) {
        // Hero-trailer surfaces are muted background previews. They must never claim the
        // media session or the volume flyout would show a trailer instead of the real playback.
        if (passiveSurface) return;

        HWND sessionWindow = GetAncestor(hostHwnd, GA_ROOT);
        if (!sessionWindow) sessionWindow = hostHwnd;
        if (!sessionWindow) return;
        mediaSessionWindow = sessionWindow;

        {
            std::lock_guard<std::mutex> lock(gSystemMediaOwnerMutex);
            gSystemMediaOwner = this;
        }

        // Focus-path handler. Installed even if the SMTC session below fails, since this alone
        // is enough to make the keys work whenever Nuvio is the foreground window.
        if (SetWindowSubclass(
                sessionWindow,
                mediaKeyRootSubclassProc,
                kMediaKeySubclassId,
                reinterpret_cast<DWORD_PTR>(this)
            )) {
            mediaKeySubclassInstalled = true;
        } else {
            nuvioBridgeLog("smtc WM_APPCOMMAND subclass failed");
        }

        ComPtr<ISystemMediaTransportControlsInterop> interop;
        HRESULT hr = RoGetActivationFactory(
            HStringReference(RuntimeClass_Windows_Media_SystemMediaTransportControls).Get(),
            IID_PPV_ARGS(&interop)
        );
        if (FAILED(hr)) {
            nuvioBridgeLog("smtc activation factory unavailable, media keys disabled");
            return;
        }

        hr = interop->GetForWindow(sessionWindow, IID_PPV_ARGS(&systemMediaControls));
        if (FAILED(hr) || !systemMediaControls) {
            nuvioBridgeLog("smtc GetForWindow failed, media keys disabled");
            systemMediaControls.Reset();
            return;
        }

        systemMediaControls->put_IsPlayEnabled(TRUE);
        systemMediaControls->put_IsPauseEnabled(TRUE);
        systemMediaControls->put_IsStopEnabled(TRUE);
        systemMediaControls->put_IsNextEnabled(TRUE);
        systemMediaControls->put_IsPreviousEnabled(TRUE);

        std::weak_ptr<WindowsMpvWebPlayer> weakSelf = weak_from_this();
        hr = systemMediaControls->add_ButtonPressed(
            Callback<SystemMediaButtonPressedHandler>(
                [weakSelf](
                    WMedia::ISystemMediaTransportControls *,
                    WMedia::ISystemMediaTransportControlsButtonPressedEventArgs *args
                ) -> HRESULT {
                    auto self = weakSelf.lock();
                    if (!self || !args) return S_OK;
                    WMedia::SystemMediaTransportControlsButton button;
                    if (FAILED(args->get_Button(&button))) return S_OK;
                    switch (button) {
                        case WMedia::SystemMediaTransportControlsButton_Play:
                            self->sendPlayerEvent("mediaPlay", 0.0);
                            break;
                        case WMedia::SystemMediaTransportControlsButton_Pause:
                            self->sendPlayerEvent("mediaPause", 0.0);
                            break;
                        case WMedia::SystemMediaTransportControlsButton_Stop:
                            self->sendPlayerEvent("mediaStop", 0.0);
                            break;
                        case WMedia::SystemMediaTransportControlsButton_Next:
                            self->sendPlayerEvent("mediaNext", 0.0);
                            break;
                        case WMedia::SystemMediaTransportControlsButton_Previous:
                            self->sendPlayerEvent("mediaPrevious", 0.0);
                            break;
                        default:
                            break;
                    }
                    return S_OK;
                }
            ).Get(),
            &systemMediaButtonToken
        );
        if (FAILED(hr)) {
            nuvioBridgeLog("smtc add_ButtonPressed failed, media keys disabled");
            systemMediaButtonToken.value = 0;
            systemMediaControls.Reset();
            return;
        }

        // Windows only routes media keys to a session that is enabled AND reports a live
        // playback status. Both are required; an enabled session still reporting Closed is
        // skipped in favour of whatever else is playing.
        systemMediaControls->put_IsEnabled(TRUE);
        // Deliberately NOT MediaPlaybackStatus_Changing. Windows treats Changing as "this
        // session isn't ready" and will hand the current-session slot to another app; during a
        // failover walk the player re-initialises every few seconds, so a Changing window on
        // each one is a repeated opportunity to lose the keys. Publish the intended status
        // straight away and let the 500ms timer correct it.
        lastSystemMediaStatus = -1;
        updateSystemMediaPlaybackStatus(!playWhenReady);
        nuvioBridgeLog("smtc session registered");
    }

    // `loading` deliberately does not map to Changing — see initializeSystemMediaControls.
    void updateSystemMediaPlaybackStatus(bool paused) {
        if (!systemMediaControls) return;
        const WMedia::MediaPlaybackStatus status =
            paused ? WMedia::MediaPlaybackStatus_Paused : WMedia::MediaPlaybackStatus_Playing;
        if ((int)status == lastSystemMediaStatus) return;
        lastSystemMediaStatus = (int)status;
        systemMediaControls->put_PlaybackStatus(status);
    }

    // WM_APPCOMMAND arrives on the thread owning the top-level window (not the native UI
    // thread). It only reads mpv state and posts a JNI event, both of which are safe there.
    bool handleMediaAppCommand(int command) {
        switch (command) {
            case APPCOMMAND_MEDIA_PLAY_PAUSE:
                // No SMTC status to consult on this path, so resolve the toggle ourselves.
                sendPlayerEvent(isPaused() ? "mediaPlay" : "mediaPause", 0.0);
                return true;
            case APPCOMMAND_MEDIA_PLAY:
                sendPlayerEvent("mediaPlay", 0.0);
                return true;
            case APPCOMMAND_MEDIA_PAUSE:
                sendPlayerEvent("mediaPause", 0.0);
                return true;
            case APPCOMMAND_MEDIA_STOP:
                sendPlayerEvent("mediaStop", 0.0);
                return true;
            case APPCOMMAND_MEDIA_NEXTTRACK:
                sendPlayerEvent("mediaNext", 0.0);
                return true;
            case APPCOMMAND_MEDIA_PREVIOUSTRACK:
                sendPlayerEvent("mediaPrevious", 0.0);
                return true;
            default:
                return false;
        }
    }

public:
    // Called from the JVM thread; hops to the UI thread because the session was created there.
    void setSystemMediaMetadata(
        const std::string &title,
        const std::string &subtitle,
        const std::string &artworkUrl
    ) {
        auto self = shared_from_this();
        postUiTask([self, title, subtitle, artworkUrl]() {
            if (!self->systemMediaControls) return;
            ComPtr<WMedia::ISystemMediaTransportControlsDisplayUpdater> updater;
            if (FAILED(self->systemMediaControls->get_DisplayUpdater(&updater)) || !updater) return;
            updater->put_Type(WMedia::MediaPlaybackType_Video);
            ComPtr<WMedia::IVideoDisplayProperties> video;
            if (FAILED(updater->get_VideoProperties(&video)) || !video) return;
            const std::wstring wideTitle = toWide(title);
            const std::wstring wideSubtitle = toWide(subtitle);
            video->put_Title(HStringReference(wideTitle.c_str()).Get());
            video->put_Subtitle(HStringReference(wideSubtitle.c_str()).Get());
            self->applySystemMediaThumbnail(updater.Get(), artworkUrl);
            updater->Update();
        });
    }

private:
    // Handed to Windows as a URI reference rather than downloaded here: the flyout fetches and
    // caches it itself, so a poster that never loads costs nothing on the playback path.
    void applySystemMediaThumbnail(
        WMedia::ISystemMediaTransportControlsDisplayUpdater *updater,
        const std::string &artworkUrl
    ) {
        if (!updater) return;
        if (artworkUrl.empty()) {
            updater->put_Thumbnail(nullptr);
            return;
        }

        ComPtr<ABI::Windows::Foundation::IUriRuntimeClassFactory> uriFactory;
        if (FAILED(RoGetActivationFactory(
                HStringReference(RuntimeClass_Windows_Foundation_Uri).Get(),
                IID_PPV_ARGS(&uriFactory)
            ))) {
            return;
        }
        const std::wstring wideUrl = toWide(artworkUrl);
        ComPtr<ABI::Windows::Foundation::IUriRuntimeClass> uri;
        if (FAILED(uriFactory->CreateUri(HStringReference(wideUrl.c_str()).Get(), &uri)) || !uri) {
            return;
        }

        ComPtr<ABI::Windows::Storage::Streams::IRandomAccessStreamReferenceStatics> streamStatics;
        if (FAILED(RoGetActivationFactory(
                HStringReference(RuntimeClass_Windows_Storage_Streams_RandomAccessStreamReference).Get(),
                IID_PPV_ARGS(&streamStatics)
            ))) {
            return;
        }
        ComPtr<ABI::Windows::Storage::Streams::IRandomAccessStreamReference> streamRef;
        if (FAILED(streamStatics->CreateFromUri(uri.Get(), &streamRef)) || !streamRef) return;
        updater->put_Thumbnail(streamRef.Get());
    }

public:

private:
    void teardownSystemMediaControls() {
        // A player that has already been superseded must not disable the session or unhook the
        // subclass its replacement installed on the same window.
        bool stillOwner = false;
        {
            std::lock_guard<std::mutex> lock(gSystemMediaOwnerMutex);
            stillOwner = gSystemMediaOwner == this;
            if (stillOwner) gSystemMediaOwner = nullptr;
        }
        if (!stillOwner) {
            nuvioBridgeLog("cleanup smtc skipped, session reclaimed by a newer player");
            systemMediaControls.Reset();
            mediaKeySubclassInstalled = false;
            lastSystemMediaStatus = -1;
            return;
        }

        if (mediaKeySubclassInstalled && mediaSessionWindow) {
            RemoveWindowSubclass(mediaSessionWindow, mediaKeyRootSubclassProc, kMediaKeySubclassId);
        }
        mediaKeySubclassInstalled = false;
        mediaSessionWindow = nullptr;

        if (!systemMediaControls) return;
        nuvioBridgeLog("cleanup smtc session");
        if (systemMediaButtonToken.value != 0) {
            systemMediaControls->remove_ButtonPressed(systemMediaButtonToken);
            systemMediaButtonToken.value = 0;
        }
        systemMediaControls->put_PlaybackStatus(WMedia::MediaPlaybackStatus_Closed);
        systemMediaControls->put_IsEnabled(FALSE);
        systemMediaControls.Reset();
        lastSystemMediaStatus = -1;
    }

    bool setMpvOptionStringLocked(const char *name, const char *value) {
        int result = mpvApi().setOptionString(mpv, name, value);
        if (result < 0) {
            std::string message = std::string("[nuvio] mpv option rejected: ") + name + "=" + value +
                " (" + mpvApi().errorText(result) + ")\n";
            OutputDebugStringA(message.c_str());
            return false;
        }
        if (recordNuvioConfiguredOptions) nuvioConfiguredOptions.insert(name);
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
        const double requestedStartSeconds = initialStartSeconds.load();
        if (requestedStartSeconds > 0.0 && position + 5.0 < requestedStartSeconds) {
            return requestedStartSeconds;
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
        {
            std::lock_guard<std::mutex> lock(appAddedSubtitleUrlsMutex);
            appAddedSubtitleUrls.clear();
        }
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
        // `sid=auto` can remain the public option value after mpv has resolved a concrete track.
        // current-tracks/sub/id is the actual primary subtitle used for playback.
        long long primarySubtitleId = wantedType == "sub"
            ? int64Property("current-tracks/sub/id", int64Property("sid", -1))
            : -1;
        std::ostringstream json;
        json << "[";
        int logicalIndex = 0;
        bool first = true;
        for (long long index = 0; index < count; index++) {
            std::string prefix = "track-list/" + std::to_string(index);
            std::string type = stringProperty((prefix + "/type").c_str(), "");
            if (type != wantedType) continue;

            // Hide subtitles the app itself injected (addon / network subs added via
            // addSubtitleUrl) from the built-in list — they own the dedicated "Addons" tab, and
            // leaving them here is what let selected-then-stale addon subs leak into "Built-in".
            // mpv's own sibling-file autoloads are external too but are NOT app-added, so they
            // stay listed (a local-library file's adjacent .srt is a legitimate built-in track).
            if (wantedType == "sub") {
                std::string externalFilename = trackStringAtIndex(index, "external-filename");
                if (!externalFilename.empty()) {
                    std::lock_guard<std::mutex> lock(appAddedSubtitleUrlsMutex);
                    if (appAddedSubtitleUrls.count(externalFilename) > 0) continue;
                }
            }

            long long trackId = int64Property((prefix + "/id").c_str(), logicalIndex + 1);
            std::string title = trackStringAtIndex(index, "title");
            std::string language = trackStringAtIndex(index, "lang");
            std::string codec = trackStringAtIndex(index, "codec");
            std::string decoderDescription = trackStringAtIndex(index, "decoder-desc");
            std::string channels = trackStringAtIndex(index, "demux-channels");
            long long channelCount = int64Property((prefix + "/demux-channel-count").c_str(), 0);
            // mpv marks both sid and secondary-sid tracks as selected. The app's existing
            // selected flag represents the primary/bottom track, so compare against sid
            // directly once dual subtitles are active. Falling back to mpv's own flag when the
            // primary id is unknown is safe (nothing else can be selected then), but ORing the two
            // is not: with dual subtitles on it hands the app the secondary track as its selection.
            const bool trackSelected = flagProperty((prefix + "/selected").c_str(), false);
            // main-selection is 0 for the primary track and 1 for the secondary one; defaulting to
            // 0 keeps the old behaviour if the property is ever unavailable.
            const long long mainSelection = int64Property((prefix + "/main-selection").c_str(), 0);
            bool selected = wantedType == "sub"
                ? (primarySubtitleId >= 0
                       ? trackId == primarySubtitleId
                       : (trackSelected && mainSelection == 0))
                : trackSelected;
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
    void refreshSubtitleAssOverrideMode(bool force = false) {
        std::string codec = selectedSubtitleCodec();
        bool isAssSubtitle = codec.find("ass") != std::string::npos || codec.find("ssa") != std::string::npos;
        std::string mode = isAssSubtitle ? requestedSubAssStyleMode : "force";
        // sub-scale belongs to the ASS control, so a plain-text track is always left at 1.0 and
        // keeps being sized by sub-font-size alone, exactly as before this control existed.
        double scale = isAssSubtitle ? requestedSubAssScale : 1.0;
        nuvioBridgeLog("refreshSubtitleAssOverrideMode: codec=\"" + codec + "\" mode=" + mode +
            " scale=" + std::to_string(scale) +
            (!force && mode == appliedSubAssOverrideMode ? " (unchanged)" : " (applying)"));
        if (!force && mode == appliedSubAssOverrideMode) return;
        appliedSubAssOverrideMode = mode;
        setStringProperty("sub-ass-override", mode);
        {
            std::lock_guard<std::mutex> lock(mpvMutex);
            if (!mpv) return;
            mpvApi().setProperty(mpv, "sub-scale", MPV_FORMAT_DOUBLE, &scale);
        }
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

LRESULT CALLBACK mediaKeyRootSubclassProc(
    HWND hwnd,
    UINT message,
    WPARAM wParam,
    LPARAM lParam,
    UINT_PTR idSubclass,
    DWORD_PTR refData
) {
    if (message == WM_APPCOMMAND) {
        // refData is only dereferenced while this player still owns the subclass; teardown
        // removes the hook, and a superseded player leaves the newer owner's hook in place.
        std::lock_guard<std::mutex> lock(gSystemMediaOwnerMutex);
        auto *player = reinterpret_cast<WindowsMpvWebPlayer *>(refData);
        if (player && gSystemMediaOwner == player) {
            if (player->handleMediaAppCommand(GET_APPCOMMAND_LPARAM(lParam))) {
                // Consume it. Not forwarding to DefSubclassProc keeps the shell from turning the
                // same press into an SMTC ButtonPressed, which would double-toggle playback.
                return TRUE;
            }
        }
    }
    if (message == WM_NCDESTROY) {
        RemoveWindowSubclass(hwnd, mediaKeyRootSubclassProc, idSubclass);
    }
    return DefSubclassProc(hwnd, message, wParam, lParam);
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
    jdouble initialProgressFraction,
    jstring controlsPageUrl,
    jboolean nvidiaRtxSuperResolutionEnabled,
    jboolean nvidiaRtxHdrEnabled,
    jboolean isAnimeContent,
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
            initialProgressFraction,
            controlsPageUrlText,
            javaVm,
            nvidiaRtxSuperResolutionEnabled == JNI_TRUE,
            nvidiaRtxHdrEnabled == JNI_TRUE,
            isAnimeContent == JNI_TRUE,
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

void setBorderlessFullscreenSuspended(HWND hwnd, bool suspended) {
    if (!hwnd || !IsWindow(hwnd) || !g_borderlessFullscreenSaved.valid) return;

    if (suspended) {
        SetWindowLongPtrW(hwnd, GWL_STYLE, g_borderlessFullscreenSaved.style);
        const RECT &savedRect = g_borderlessFullscreenSaved.rect;
        SetWindowPos(
            hwnd,
            nullptr,
            savedRect.left,
            savedRect.top,
            savedRect.right - savedRect.left,
            savedRect.bottom - savedRect.top,
            SWP_NOZORDER | SWP_NOOWNERZORDER | SWP_FRAMECHANGED
        );
        return;
    }

    HMONITOR monitor = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
    MONITORINFO monitorInfo{};
    monitorInfo.cbSize = sizeof(monitorInfo);
    if (!GetMonitorInfoW(monitor, &monitorInfo)) return;
    // Same style mask as setBorderlessFullscreen — including WS_MAXIMIZE, or resuming from PiP
    // into a session that was maximized before fullscreen lands on the work area, not the monitor.
    LONG_PTR style = g_borderlessFullscreenSaved.style &
        ~(WS_CAPTION | WS_THICKFRAME | WS_MINIMIZEBOX | WS_MAXIMIZEBOX | WS_SYSMENU | WS_MAXIMIZE);
    SetWindowLongPtrW(hwnd, GWL_STYLE, style);
    const RECT &monitorRect = monitorInfo.rcMonitor;
    SetWindowPos(
        hwnd,
        HWND_TOP,
        monitorRect.left,
        monitorRect.top,
        monitorRect.right - monitorRect.left,
        monitorRect.bottom - monitorRect.top,
        SWP_NOOWNERZORDER | SWP_FRAMECHANGED
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_requestSeekThumbnail(
    JNIEnv *, jobject, jlong handle, jlong positionMs
) {
    auto player = playerFromHandle(handle);
    if (player) player->requestSeekThumbnail(positionMs);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setCursorHidden(JNIEnv *, jobject, jlong handle, jboolean hidden) {
    auto player = playerFromHandle(handle);
    if (player) player->setCursorHidden(hidden == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setMediaSessionMetadata(
    JNIEnv *env,
    jobject,
    jlong handle,
    jstring title,
    jstring subtitle,
    jstring artworkUrl
) {
    auto player = playerFromHandle(handle);
    if (!player) return;
    player->setSystemMediaMetadata(
        title ? jstringToUtf8(env, title) : std::string(),
        subtitle ? jstringToUtf8(env, subtitle) : std::string(),
        artworkUrl ? jstringToUtf8(env, artworkUrl) : std::string()
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_initializeAppIdentity(JNIEnv *, jobject) {
    initializeAppIdentity();
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
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_beginVideoProfile(JNIEnv *, jobject, jlong handle) {
    auto player = playerFromHandle(handle);
    if (player) player->beginVideoProfile();
}
extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_endVideoProfile(JNIEnv *, jobject, jlong handle) {
    auto player = playerFromHandle(handle);
    if (player) player->endVideoProfile();
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_completeSvpStartupProfile(JNIEnv *, jobject, jlong handle) {
    auto player = playerFromHandle(handle);
    if (player) player->acknowledgeSvpStartupProfile();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_chaptersJson(JNIEnv *env, jobject, jlong handle) {
    auto player = playerFromHandle(handle);
    return newJavaStringUtf8(env, player ? player->chaptersJson() : "[]");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_selectAudioTrack(JNIEnv *, jobject, jlong handle, jint trackId) {
    auto player = playerFromHandle(handle);
    return (player && player->selectAudioTrackId(trackId)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_selectSubtitleTrack(JNIEnv *, jobject, jlong handle, jint trackId) {
    auto player = playerFromHandle(handle);
    return (player && player->selectSubtitleTrackId(trackId)) ? JNI_TRUE : JNI_FALSE;
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
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setBorderlessFullscreenSuspended(JNIEnv *, jobject, jlong windowHwnd, jboolean suspended) {
    setBorderlessFullscreenSuspended((HWND)(intptr_t)windowHwnd, suspended == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setCompactPlayerWindow(JNIEnv *, jobject, jlong windowHwnd, jboolean enabled) {
    setCompactPlayerWindow((HWND)(intptr_t)windowHwnd, enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_beginCompactPlayerWindowMove(JNIEnv *, jobject, jlong windowHwnd) {
    beginCompactPlayerWindowMove((HWND)(intptr_t)windowHwnd);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_beginCompactPlayerWindowResize(JNIEnv *, jobject, jlong windowHwnd, jint edge) {
    beginCompactPlayerWindowResize((HWND)(intptr_t)windowHwnd, edge);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_updateCompactPlayerWindowInteraction(JNIEnv *, jobject, jlong windowHwnd) {
    updateCompactPlayerWindowInteraction((HWND)(intptr_t)windowHwnd);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_endCompactPlayerWindowInteraction(JNIEnv *, jobject, jlong windowHwnd) {
    endCompactPlayerWindowInteraction((HWND)(intptr_t)windowHwnd);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setSubtitleAssStyleMode(
    JNIEnv *env,
    jobject,
    jlong handle,
    jstring mode,
    jdouble scale
) {
    auto player = playerFromHandle(handle);
    if (!player) return;
    player->setSubtitleAssStyleMode(mode ? jstringToUtf8(env, mode) : std::string("no"), scale);
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
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_toggleStatsOverlay(
    JNIEnv *,
    jobject,
    jlong handle
) {
    auto player = playerFromHandle(handle);
    if (!player) return;
    player->toggleStatsOverlay();
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
