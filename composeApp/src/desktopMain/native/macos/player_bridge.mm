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
                    playWhenReady:(BOOL)playWhenReady
                     controlsHtml:(NSString *)controlsHtml
                           javaVm:(JavaVM *)javaVm
                        eventSink:(jobject)eventSink
                      eventMethod:(jmethodID)eventMethod;
- (void)shutdown;
- (void)updateControlsJson:(NSString *)controlsJson;
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

static NSString *javaScriptStringLiteral(NSString *value) {
    NSArray *array = @[value ?: @""];
    NSData *data = [NSJSONSerialization dataWithJSONObject:array options:0 error:nil];
    if (!data) {
        return @"\"\"";
    }
    NSString *jsonArray = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
    if (jsonArray.length < 2) {
        return @"\"\"";
    }
    return [jsonArray substringWithRange:NSMakeRange(1, jsonArray.length - 2)];
}

@implementation MpvWebPlayer {
    NSView *_hostView;
    PlayerOpenGLView *_videoView;
    WKWebView *_webView;
    PlayerScriptHandler *_scriptHandler;
    mpv_handle *_mpv;
    mpv_render_context *_renderContext;
    NSTimer *_timer;
    JavaVM *_javaVm;
    jobject _eventSink;
    jmethodID _eventMethod;
}

- (instancetype)initWithHostView:(NSView *)hostView
                       sourceUrl:(NSString *)sourceUrl
                     headerLines:(NSArray<NSString *> *)headerLines
                    playWhenReady:(BOOL)playWhenReady
                     controlsHtml:(NSString *)controlsHtml
                           javaVm:(JavaVM *)javaVm
                        eventSink:(jobject)eventSink
                      eventMethod:(jmethodID)eventMethod {
    self = [super init];
    if (!self) {
        return nil;
    }

    _javaVm = javaVm;
    _eventSink = eventSink;
    _eventMethod = eventMethod;

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
    [_webView loadHTMLString:controlsHtml baseURL:nil];

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

- (JNIEnv *)jniEnvDidAttach:(BOOL *)didAttach {
    if (didAttach) {
        *didAttach = NO;
    }
    if (!_javaVm) {
        return nullptr;
    }

    JNIEnv *env = nullptr;
    jint status = _javaVm->GetEnv((void **)&env, JNI_VERSION_1_6);
    if (status == JNI_OK) {
        return env;
    }
    if (status != JNI_EDETACHED) {
        return nullptr;
    }
    if (_javaVm->AttachCurrentThread((void **)&env, nullptr) != JNI_OK) {
        return nullptr;
    }
    if (didAttach) {
        *didAttach = YES;
    }
    return env;
}

- (void)sendPlayerEvent:(NSString *)type value:(double)value {
    if (!_eventSink || !_eventMethod) {
        return;
    }

    BOOL didAttach = NO;
    JNIEnv *env = [self jniEnvDidAttach:&didAttach];
    if (!env) {
        return;
    }

    jstring eventType = env->NewStringUTF(type.UTF8String);
    env->CallVoidMethod(_eventSink, _eventMethod, eventType, (jdouble)value);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (eventType) {
        env->DeleteLocalRef(eventType);
    }
    if (didAttach) {
        _javaVm->DetachCurrentThread();
    }
}

- (void)updateControlsJson:(NSString *)controlsJson {
    if (!_webView) {
        return;
    }
    NSString *jsonString = javaScriptStringLiteral(controlsJson);
    NSString *script = [NSString stringWithFormat:
        @"if (window.playerControls) window.playerControls(JSON.parse(%@))",
        jsonString];
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
    if (_eventSink) {
        BOOL didAttach = NO;
        JNIEnv *env = [self jniEnvDidAttach:&didAttach];
        if (env) {
            env->DeleteGlobalRef(_eventSink);
        }
        if (didAttach) {
            _javaVm->DetachCurrentThread();
        }
        _eventSink = nullptr;
    }
    _eventMethod = nullptr;
    _javaVm = nullptr;
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
    if (![type isKindOfClass:[NSString class]]) {
        return;
    }

    NSNumber *value = message[@"value"];
    if (_eventSink && _eventMethod) {
        [self sendPlayerEvent:type value:value ? value.doubleValue : 0.0];
        return;
    }

    if ([type isEqualToString:@"toggle"]) {
        [self setPaused:![self isPaused]];
        [self syncControls];
    } else if ([type isEqualToString:@"seekPercent"]) {
        double duration = [self doubleProperty:"duration" fallback:0.0];
        if (duration > 0.0 && value) {
            [self seekToMilliseconds:(long long)llround(duration * value.doubleValue * 1000.0)];
        }
    } else if ([type isEqualToString:@"scrubFinish"] && value) {
        [self seekToMilliseconds:(long long)llround(value.doubleValue)];
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
    jboolean playWhenReady,
    jstring controlsHtml,
    jobject eventSink
) {
    NSView *hostView = (__bridge NSView *)(void *)(intptr_t)hostViewPtr;
    if (!hostView) {
        throwJavaError(env, @"Unable to resolve the AWT host NSView for native playback.");
        return 0;
    }

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
            if (eventSinkRef) {
                env->DeleteGlobalRef(eventSinkRef);
            }
            throwJavaError(env, @"Native player event sink is missing onPlayerEvent(String, Double).");
            return 0;
        }
    }

    std::string source = jstringToString(env, sourceUrl);
    std::string controls = jstringToString(env, controlsHtml);
    NSArray<NSString *> *headers = jstringArrayToNSArray(env, headerLines);
    __block MpvWebPlayer *player = nil;
    __block NSString *error = nil;
    runOnMainSync(^{
        @try {
            player = [[MpvWebPlayer alloc]
                initWithHostView:hostView
                    sourceUrl:[NSString stringWithUTF8String:source.c_str()]
                    headerLines:headers
                   playWhenReady:playWhenReady == JNI_TRUE
                    controlsHtml:[NSString stringWithUTF8String:controls.c_str()]
                          javaVm:javaVm
                       eventSink:eventSinkRef
                     eventMethod:eventMethod];
        } @catch (NSException *exception) {
            error = exception.reason ?: exception.name;
        }
    });

    if (error) {
        if (eventSinkRef) {
            env->DeleteGlobalRef(eventSinkRef);
        }
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
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_updateControls(
    JNIEnv *env,
    jobject /* bridge */,
    jlong handle,
    jstring controlsJson
) {
    if (handle == 0) return;
    std::string controls = jstringToString(env, controlsJson);
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    runOnMainAsync(^{
        [player updateControlsJson:[NSString stringWithUTF8String:controls.c_str()]];
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
