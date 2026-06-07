#import <Cocoa/Cocoa.h>
#import <OpenGL/gl3.h>
#import <QuartzCore/QuartzCore.h>
#import <WebKit/WebKit.h>

#include <jni.h>

#include <cmath>
#include <dlfcn.h>
#include <string>
#include <vector>

extern "C" {
typedef struct mpv_handle mpv_handle;
typedef struct mpv_render_context mpv_render_context;
typedef void *(*mpv_render_opengl_get_proc_address_fn)(void *ctx, const char *name);

typedef enum mpv_format {
    MPV_FORMAT_NONE = 0,
    MPV_FORMAT_STRING = 1,
    MPV_FORMAT_OSD_STRING = 2,
    MPV_FORMAT_FLAG = 3,
    MPV_FORMAT_INT64 = 4,
    MPV_FORMAT_DOUBLE = 5,
} mpv_format;

typedef enum mpv_render_param_type {
    MPV_RENDER_PARAM_INVALID = 0,
    MPV_RENDER_PARAM_API_TYPE = 1,
    MPV_RENDER_PARAM_OPENGL_INIT_PARAMS = 2,
    MPV_RENDER_PARAM_OPENGL_FBO = 3,
    MPV_RENDER_PARAM_FLIP_Y = 4,
    MPV_RENDER_PARAM_DEPTH = 5,
    MPV_RENDER_PARAM_ICC_PROFILE = 6,
    MPV_RENDER_PARAM_AMBIENT_LIGHT = 7,
    MPV_RENDER_PARAM_X11_DISPLAY = 8,
    MPV_RENDER_PARAM_WL_DISPLAY = 9,
    MPV_RENDER_PARAM_ADVANCED_CONTROL = 10,
    MPV_RENDER_PARAM_NEXT_FRAME_INFO = 11,
    MPV_RENDER_PARAM_BLOCK_FOR_TARGET_TIME = 12,
    MPV_RENDER_PARAM_SKIP_RENDERING = 13,
} mpv_render_param_type;

typedef struct mpv_render_param {
    mpv_render_param_type type;
    void *data;
} mpv_render_param;

typedef struct mpv_opengl_init_params {
    mpv_render_opengl_get_proc_address_fn get_proc_address;
    void *get_proc_address_ctx;
    const char *extra_exts;
} mpv_opengl_init_params;

typedef struct mpv_opengl_fbo {
    int fbo;
    int w;
    int h;
    int internal_format;
} mpv_opengl_fbo;

mpv_handle *mpv_create(void);
int mpv_initialize(mpv_handle *ctx);
void mpv_terminate_destroy(mpv_handle *ctx);
int mpv_set_option(mpv_handle *ctx, const char *name, mpv_format format, void *data);
int mpv_set_option_string(mpv_handle *ctx, const char *name, const char *data);
int mpv_set_property(mpv_handle *ctx, const char *name, mpv_format format, void *data);
int mpv_get_property(mpv_handle *ctx, const char *name, mpv_format format, void *data);
int mpv_command(mpv_handle *ctx, const char **args);
const char *mpv_error_string(int error);
int mpv_render_context_create(mpv_render_context **res, mpv_handle *mpv, mpv_render_param *params);
void mpv_render_context_free(mpv_render_context *ctx);
void mpv_render_context_render(mpv_render_context *ctx, mpv_render_param *params);
void mpv_render_context_report_swap(mpv_render_context *ctx);
void mpv_render_context_set_update_callback(
    mpv_render_context *ctx,
    void (*callback)(void *callback_ctx),
    void *callback_ctx
);
uint64_t mpv_render_context_update(mpv_render_context *ctx);
}

@class PlayerOpenGLView;
@class MpvWebPlayer;

@interface PlayerOpenGLView : NSOpenGLView
@property(nonatomic, assign) mpv_render_context *renderContext;
- (void)requestRender;
@end

@interface PlayerScriptHandler : NSObject <WKScriptMessageHandler>
@property(nonatomic, weak) MpvWebPlayer *player;
@end

@interface MpvWebPlayer : NSObject
- (instancetype)initWithHostView:(NSView *)hostView
                       sourceUrl:(NSString *)sourceUrl
                     headerLines:(NSArray<NSString *> *)headerLines
                    playWhenReady:(BOOL)playWhenReady;
- (void)shutdown;
- (void)setPaused:(BOOL)paused;
- (BOOL)isPaused;
- (void)seekToMilliseconds:(long long)positionMs;
- (void)seekByMilliseconds:(long long)offsetMs;
- (void)setSpeed:(double)speed;
- (double)speed;
- (long long)durationMs;
- (long long)positionMs;
- (void)handleScriptMessage:(NSDictionary *)message;
@end

static void *getOpenGLProcAddress(void * /* ctx */, const char *name) {
    static void *openGlHandle = dlopen(
        "/System/Library/Frameworks/OpenGL.framework/OpenGL",
        RTLD_LAZY | RTLD_LOCAL
    );
    return openGlHandle ? dlsym(openGlHandle, name) : nullptr;
}

static void renderUpdateCallback(void *callbackContext) {
    PlayerOpenGLView *view = (__bridge PlayerOpenGLView *)callbackContext;
    dispatch_async(dispatch_get_main_queue(), ^{
        [view requestRender];
    });
}

@implementation PlayerOpenGLView

+ (NSOpenGLPixelFormat *)defaultPixelFormat {
    NSOpenGLPixelFormatAttribute attributes[] = {
        NSOpenGLPFAOpenGLProfile, NSOpenGLProfileVersion3_2Core,
        NSOpenGLPFAAccelerated,
        NSOpenGLPFADoubleBuffer,
        NSOpenGLPFAColorSize, 24,
        NSOpenGLPFAAlphaSize, 8,
        NSOpenGLPFADepthSize, 0,
        0
    };
    return [[NSOpenGLPixelFormat alloc] initWithAttributes:attributes];
}

- (instancetype)initWithFrame:(NSRect)frameRect {
    self = [super initWithFrame:frameRect pixelFormat:[PlayerOpenGLView defaultPixelFormat]];
    if (!self) {
        return nil;
    }
    self.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    self.wantsBestResolutionOpenGLSurface = YES;
    return self;
}

- (BOOL)isOpaque {
    return YES;
}

- (void)prepareOpenGL {
    [super prepareOpenGL];
    [[self openGLContext] makeCurrentContext];
    GLint swapInterval = 1;
    [[self openGLContext] setValues:&swapInterval forParameter:NSOpenGLContextParameterSwapInterval];
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
}

- (void)reshape {
    [super reshape];
    [[self openGLContext] update];
    [self requestRender];
}

- (void)requestRender {
    if (self.window) {
        self.needsDisplay = YES;
    }
}

- (void)drawRect:(NSRect)dirtyRect {
    (void)dirtyRect;
    [[self openGLContext] makeCurrentContext];

    if (!self.renderContext) {
        glClear(GL_COLOR_BUFFER_BIT);
        [[self openGLContext] flushBuffer];
        return;
    }

    NSRect backingBounds = [self convertRectToBacking:self.bounds];
    GLint currentFbo = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &currentFbo);

    mpv_opengl_fbo fbo = {
        (int)currentFbo,
        (int)backingBounds.size.width,
        (int)backingBounds.size.height,
        GL_RGBA8
    };
    int flipY = 1;
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
        {MPV_RENDER_PARAM_FLIP_Y, &flipY},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };

    mpv_render_context_update(self.renderContext);
    mpv_render_context_render(self.renderContext, params);
    [[self openGLContext] flushBuffer];
    mpv_render_context_report_swap(self.renderContext);
}

@end

@implementation PlayerScriptHandler
- (void)userContentController:(WKUserContentController *)userContentController
      didReceiveScriptMessage:(WKScriptMessage *)message {
    if ([message.body isKindOfClass:[NSDictionary class]]) {
        [self.player handleScriptMessage:(NSDictionary *)message.body];
    }
}
@end

@implementation MpvWebPlayer {
    NSView *_hostView;
    PlayerOpenGLView *_videoView;
    WKWebView *_webView;
    PlayerScriptHandler *_scriptHandler;
    mpv_handle *_mpv;
    mpv_render_context *_renderContext;
    NSTimer *_timer;
}

- (instancetype)initWithHostView:(NSView *)hostView
                       sourceUrl:(NSString *)sourceUrl
                     headerLines:(NSArray<NSString *> *)headerLines
                    playWhenReady:(BOOL)playWhenReady {
    self = [super init];
    if (!self) {
        return nil;
    }

    _hostView = hostView;
    _hostView.wantsLayer = YES;
    _hostView.layer.backgroundColor = NSColor.blackColor.CGColor;

    _videoView = [[PlayerOpenGLView alloc] initWithFrame:_hostView.bounds];
    _videoView.wantsLayer = YES;
    _videoView.layer.backgroundColor = NSColor.blackColor.CGColor;
    [_hostView addSubview:_videoView];

    _scriptHandler = [PlayerScriptHandler new];
    _scriptHandler.player = self;
    WKUserContentController *contentController = [WKUserContentController new];
    [contentController addScriptMessageHandler:_scriptHandler name:@"player"];

    WKWebViewConfiguration *configuration = [WKWebViewConfiguration new];
    configuration.userContentController = contentController;
    _webView = [[WKWebView alloc] initWithFrame:_hostView.bounds configuration:configuration];
    _webView.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    _webView.wantsLayer = YES;
    [_webView setValue:@NO forKey:@"drawsBackground"];
    [_hostView addSubview:_webView positioned:NSWindowAbove relativeTo:_videoView];
    [_webView loadHTMLString:[self controlsHtml] baseURL:nil];

    [self startMpvWithSource:sourceUrl headerLines:headerLines playWhenReady:playWhenReady];
    _timer = [NSTimer scheduledTimerWithTimeInterval:0.5
                                             target:self
                                           selector:@selector(syncControls)
                                           userInfo:nil
                                            repeats:YES];
    return self;
}

- (void)startMpvWithSource:(NSString *)sourceUrl
               headerLines:(NSArray<NSString *> *)headerLines
              playWhenReady:(BOOL)playWhenReady {
    _mpv = mpv_create();
    if (!_mpv) {
        @throw [NSException exceptionWithName:@"PlayerBridgeError"
                                       reason:@"mpv_create failed"
                                     userInfo:nil];
    }

    mpv_set_option_string(_mpv, "config", "no");
    mpv_set_option_string(_mpv, "osc", "no");
    mpv_set_option_string(_mpv, "input-default-bindings", "yes");
    mpv_set_option_string(_mpv, "input-vo-keyboard", "no");
    mpv_set_option_string(_mpv, "keep-open", "yes");
    mpv_set_option_string(_mpv, "vo", "libmpv");

    if (headerLines.count > 0) {
        NSString *headers = [headerLines componentsJoinedByString:@","];
        mpv_set_option_string(_mpv, "http-header-fields", headers.UTF8String);
    }

    int initResult = mpv_initialize(_mpv);
    if (initResult < 0) {
        NSString *reason = [NSString stringWithFormat:@"mpv_initialize failed: %s", mpv_error_string(initResult)];
        @throw [NSException exceptionWithName:@"PlayerBridgeError" reason:reason userInfo:nil];
    }

    [[_videoView openGLContext] makeCurrentContext];
    mpv_opengl_init_params glInit = {
        getOpenGLProcAddress,
        nullptr,
        nullptr
    };
    const char *apiType = "opengl";
    int advancedControl = 1;
    mpv_render_param renderParams[] = {
        {MPV_RENDER_PARAM_API_TYPE, (void *)apiType},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAMS, &glInit},
        {MPV_RENDER_PARAM_ADVANCED_CONTROL, &advancedControl},
        {MPV_RENDER_PARAM_INVALID, nullptr},
    };
    int renderResult = mpv_render_context_create(&_renderContext, _mpv, renderParams);
    if (renderResult < 0) {
        NSString *reason = [NSString stringWithFormat:@"mpv render context failed: %s", mpv_error_string(renderResult)];
        @throw [NSException exceptionWithName:@"PlayerBridgeError" reason:reason userInfo:nil];
    }
    _videoView.renderContext = _renderContext;
    mpv_render_context_set_update_callback(_renderContext, renderUpdateCallback, (__bridge void *)_videoView);

    const char *command[] = {"loadfile", sourceUrl.UTF8String, NULL};
    int commandResult = mpv_command(_mpv, command);
    if (commandResult < 0) {
        NSString *reason = [NSString stringWithFormat:@"mpv loadfile failed: %s", mpv_error_string(commandResult)];
        @throw [NSException exceptionWithName:@"PlayerBridgeError" reason:reason userInfo:nil];
    }

    [self setPaused:!playWhenReady];
}

- (NSString *)controlsHtml {
    return @"<!doctype html>"
    "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
    "<style>"
    "html,body{margin:0;width:100%;height:100%;background:transparent;color:#fff;font-family:-apple-system,BlinkMacSystemFont,Inter,sans-serif;overflow:hidden;}"
    "#shade{position:absolute;inset:auto 0 0 0;height:132px;background:linear-gradient(transparent,rgba(0,0,0,.78));pointer-events:none;}"
    "#bar{position:absolute;left:22px;right:22px;bottom:20px;display:flex;align-items:center;gap:14px;padding:12px 14px;border-radius:14px;background:rgba(10,10,12,.66);backdrop-filter:blur(22px);box-shadow:0 10px 30px rgba(0,0,0,.35);}"
    "button{appearance:none;border:0;border-radius:999px;background:#fff;color:#09090b;width:42px;height:42px;font-size:16px;font-weight:800;cursor:pointer;}"
    "#time{font-variant-numeric:tabular-nums;font-size:12px;min-width:92px;text-align:right;color:rgba(255,255,255,.82);}"
    "input[type=range]{flex:1;accent-color:#fff;cursor:pointer;}"
    "#badge{position:absolute;left:18px;top:14px;padding:7px 10px;border-radius:999px;background:rgba(0,0,0,.45);font-size:12px;color:rgba(255,255,255,.78);}"
    "</style></head><body>"
    "<div id='badge'>Native macOS player</div><div id='shade'></div>"
    "<div id='bar'><button id='toggle'>||</button><input id='seek' type='range' min='0' max='1000' value='0'><span id='time'>0:00 / 0:00</span></div>"
    "<script>"
    "const send=(type,value)=>window.webkit.messageHandlers.player.postMessage({type,value});"
    "const fmt=s=>{if(!isFinite(s)||s<0)s=0;const h=Math.floor(s/3600),m=Math.floor((s%3600)/60),x=Math.floor(s%60);return h?`${h}:${String(m).padStart(2,'0')}:${String(x).padStart(2,'0')}`:`${m}:${String(x).padStart(2,'0')}`};"
    "const seek=document.getElementById('seek'),toggle=document.getElementById('toggle'),time=document.getElementById('time');"
    "toggle.onclick=()=>send('toggle',0);"
    "seek.oninput=()=>send('seekPercent',Number(seek.value)/1000);"
    "window.playerUpdate=s=>{toggle.textContent=s.paused?'▶':'||';seek.value=s.duration>0?Math.max(0,Math.min(1000,(s.position/s.duration)*1000)):0;time.textContent=`${fmt(s.position)} / ${fmt(s.duration)}`;};"
    "</script></body></html>";
}

- (void)syncControls {
    if (!_webView || !_mpv) {
        return;
    }
    double duration = [self doubleProperty:"duration" fallback:0.0];
    double position = [self doubleProperty:"time-pos" fallback:0.0];
    BOOL paused = [self isPaused];
    NSString *script = [NSString stringWithFormat:
        @"window.playerUpdate({duration:%0.3f,position:%0.3f,paused:%@})",
        duration,
        position,
        paused ? @"true" : @"false"];
    [_webView evaluateJavaScript:script completionHandler:nil];
}

- (void)shutdown {
    [_timer invalidate];
    _timer = nil;
    if (_renderContext) {
        mpv_render_context_set_update_callback(_renderContext, nullptr, nullptr);
        _videoView.renderContext = nullptr;
        mpv_render_context_free(_renderContext);
        _renderContext = nullptr;
    }
    if (_mpv) {
        mpv_terminate_destroy(_mpv);
        _mpv = NULL;
    }
    [_webView.configuration.userContentController removeScriptMessageHandlerForName:@"player"];
    [_webView removeFromSuperview];
    [_videoView removeFromSuperview];
    _webView = nil;
    _videoView = nil;
    _scriptHandler = nil;
}

- (void)setPaused:(BOOL)paused {
    if (!_mpv) return;
    int flag = paused ? 1 : 0;
    mpv_set_property(_mpv, "pause", MPV_FORMAT_FLAG, &flag);
}

- (BOOL)isPaused {
    if (!_mpv) return YES;
    int flag = 1;
    mpv_get_property(_mpv, "pause", MPV_FORMAT_FLAG, &flag);
    return flag != 0;
}

- (void)seekToMilliseconds:(long long)positionMs {
    if (!_mpv) return;
    std::string seconds = std::to_string((double)positionMs / 1000.0);
    const char *command[] = {"seek", seconds.c_str(), "absolute", NULL};
    mpv_command(_mpv, command);
}

- (void)seekByMilliseconds:(long long)offsetMs {
    if (!_mpv) return;
    std::string seconds = std::to_string((double)offsetMs / 1000.0);
    const char *command[] = {"seek", seconds.c_str(), "relative", NULL};
    mpv_command(_mpv, command);
}

- (void)setSpeed:(double)speed {
    if (!_mpv) return;
    double clamped = fmax(0.25, fmin(4.0, speed));
    mpv_set_property(_mpv, "speed", MPV_FORMAT_DOUBLE, &clamped);
}

- (double)speed {
    return [self doubleProperty:"speed" fallback:1.0];
}

- (long long)durationMs {
    return (long long)llround([self doubleProperty:"duration" fallback:0.0] * 1000.0);
}

- (long long)positionMs {
    return (long long)llround([self doubleProperty:"time-pos" fallback:0.0] * 1000.0);
}

- (double)doubleProperty:(const char *)name fallback:(double)fallback {
    if (!_mpv) return fallback;
    double value = fallback;
    if (mpv_get_property(_mpv, name, MPV_FORMAT_DOUBLE, &value) < 0) {
        return fallback;
    }
    return value;
}

- (void)handleScriptMessage:(NSDictionary *)message {
    NSString *type = message[@"type"];
    if ([type isEqualToString:@"toggle"]) {
        [self setPaused:![self isPaused]];
        [self syncControls];
    } else if ([type isEqualToString:@"seekPercent"]) {
        NSNumber *value = message[@"value"];
        double duration = [self doubleProperty:"duration" fallback:0.0];
        if (duration > 0.0 && value) {
            [self seekToMilliseconds:(long long)llround(duration * value.doubleValue * 1000.0)];
        }
    }
}

@end

static void runOnMainSync(dispatch_block_t block) {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_sync(dispatch_get_main_queue(), block);
    }
}

static void runOnMainAsync(dispatch_block_t block) {
    if ([NSThread isMainThread]) {
        block();
    } else {
        dispatch_async(dispatch_get_main_queue(), block);
    }
}

static void throwJavaError(JNIEnv *env, NSString *message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (exceptionClass) {
        env->ThrowNew(exceptionClass, message.UTF8String);
    }
}

static std::string jstringToString(JNIEnv *env, jstring value) {
    if (!value) return std::string();
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars ? chars : "";
    if (chars) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return result;
}

static NSArray<NSString *> *jstringArrayToNSArray(JNIEnv *env, jobjectArray values) {
    NSMutableArray<NSString *> *result = [NSMutableArray array];
    if (!values) {
        return result;
    }
    jsize count = env->GetArrayLength(values);
    for (jsize index = 0; index < count; index++) {
        jstring item = (jstring)env->GetObjectArrayElement(values, index);
        std::string value = jstringToString(env, item);
        if (!value.empty()) {
            [result addObject:[NSString stringWithUTF8String:value.c_str()]];
        }
        env->DeleteLocalRef(item);
    }
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_create(
    JNIEnv *env,
    jobject /* bridge */,
    jlong hostViewPtr,
    jstring sourceUrl,
    jobjectArray headerLines,
    jboolean playWhenReady
) {
    NSView *hostView = (__bridge NSView *)(void *)(intptr_t)hostViewPtr;
    if (!hostView) {
        throwJavaError(env, @"Unable to resolve the AWT host NSView for native playback.");
        return 0;
    }

    std::string source = jstringToString(env, sourceUrl);
    NSArray<NSString *> *headers = jstringArrayToNSArray(env, headerLines);
    __block MpvWebPlayer *player = nil;
    __block NSString *error = nil;
    runOnMainSync(^{
        @try {
            player = [[MpvWebPlayer alloc]
                initWithHostView:hostView
                      sourceUrl:[NSString stringWithUTF8String:source.c_str()]
                    headerLines:headers
                   playWhenReady:playWhenReady == JNI_TRUE];
        } @catch (NSException *exception) {
            error = exception.reason ?: exception.name;
        }
    });

    if (error) {
        throwJavaError(env, error);
        return 0;
    }

    return (jlong)(intptr_t)CFBridgingRetain(player);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_dispose(
    JNIEnv * /* env */,
    jobject /* bridge */,
    jlong handle
) {
    if (handle == 0) return;
    MpvWebPlayer *player = (__bridge_transfer MpvWebPlayer *)(void *)(intptr_t)handle;
    runOnMainSync(^{
        [player shutdown];
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setPaused(
    JNIEnv * /* env */,
    jobject /* bridge */,
    jlong handle,
    jboolean paused
) {
    if (handle == 0) return;
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    runOnMainAsync(^{
        [player setPaused:paused == JNI_TRUE];
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_seekTo(
    JNIEnv * /* env */,
    jobject /* bridge */,
    jlong handle,
    jlong positionMs
) {
    if (handle == 0) return;
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    runOnMainAsync(^{
        [player seekToMilliseconds:positionMs];
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_seekBy(
    JNIEnv * /* env */,
    jobject /* bridge */,
    jlong handle,
    jlong offsetMs
) {
    if (handle == 0) return;
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    runOnMainAsync(^{
        [player seekByMilliseconds:offsetMs];
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setSpeed(
    JNIEnv * /* env */,
    jobject /* bridge */,
    jlong handle,
    jfloat speed
) {
    if (handle == 0) return;
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    runOnMainAsync(^{
        [player setSpeed:speed];
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_durationMs(
    JNIEnv * /* env */,
    jobject /* bridge */,
    jlong handle
) {
    if (handle == 0) return 0;
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    return [player durationMs];
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_positionMs(
    JNIEnv * /* env */,
    jobject /* bridge */,
    jlong handle
) {
    if (handle == 0) return 0;
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    return [player positionMs];
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_isPaused(
    JNIEnv * /* env */,
    jobject /* bridge */,
    jlong handle
) {
    if (handle == 0) return JNI_TRUE;
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    return [player isPaused] ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_speed(
    JNIEnv * /* env */,
    jobject /* bridge */,
    jlong handle
) {
    if (handle == 0) return 1.0f;
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    return (jfloat)[player speed];
}
