#import <Cocoa/Cocoa.h>
#import <CoreVideo/CoreVideo.h>
#import <OpenGL/OpenGL.h>
#import <OpenGL/gl3.h>
#import <QuartzCore/QuartzCore.h>
#import <WebKit/WebKit.h>

#include <jni.h>

#include <atomic>
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
int mpv_set_property_string(mpv_handle *ctx, const char *name, const char *data);
int mpv_get_property(mpv_handle *ctx, const char *name, mpv_format format, void *data);
int mpv_command(mpv_handle *ctx, const char **args);
const char *mpv_error_string(int error);
void mpv_free(void *data);
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
- (void)stopRendering;
- (void)clearRenderContext;
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
- (void)setResizeMode:(int)mode;
- (long long)durationMs;
- (long long)positionMs;
- (long long)bufferedPositionMs;
- (BOOL)isLoading;
- (BOOL)isEnded;
- (NSString *)audioTracksJson;
- (NSString *)subtitleTracksJson;
- (void)selectAudioTrackId:(int)trackId;
- (void)selectSubtitleTrackId:(int)trackId;
- (void)addSubtitleUrl:(NSString *)url;
- (void)removeExternalSubtitles;
- (void)removeExternalSubtitlesAndSelect:(int)trackId;
- (void)setSubtitleDelayMs:(int)delayMs;
- (void)applySubtitleStyleWithTextColor:(NSString *)textColor
                        backgroundColor:(NSString *)backgroundColor
                            outlineColor:(NSString *)outlineColor
                             outlineSize:(double)outlineSize
                                    bold:(BOOL)bold
                                fontSize:(double)fontSize
                                  subPos:(int)subPos;
- (void)handleScriptMessage:(NSDictionary *)message;
@end

static void *getOpenGLProcAddress(void * /* ctx */, const char *name) {
    static void *openGlHandle = dlopen(
        "/System/Library/Frameworks/OpenGL.framework/OpenGL",
        RTLD_LAZY | RTLD_LOCAL
    );
    return openGlHandle ? dlsym(openGlHandle, name) : nullptr;
}

static CVReturn displayLinkCallback(
    CVDisplayLinkRef /* displayLink */,
    const CVTimeStamp * /* now */,
    const CVTimeStamp * /* outputTime */,
    CVOptionFlags /* flagsIn */,
    CVOptionFlags * /* flagsOut */,
    void *displayLinkContext
);

static void renderUpdateCallback(void *callbackContext) {
    PlayerOpenGLView *view = (__bridge PlayerOpenGLView *)callbackContext;
    [view requestRender];
}

@implementation PlayerOpenGLView {
    CVDisplayLinkRef _displayLink;
    CGLContextObj _cglContext;
    std::atomic_bool _renderRequested;
    std::atomic_bool _drawableReady;
    std::atomic_int _backingWidth;
    std::atomic_int _backingHeight;
    mpv_render_context *_renderContext;
}

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
    _renderRequested.store(false);
    _drawableReady.store(false);
    _backingWidth.store(0);
    _backingHeight.store(0);
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
    _cglContext = [[self openGLContext] CGLContextObj];
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    [self updateBackingSize];
    [self startDisplayLinkIfNeeded];
}

- (void)reshape {
    [super reshape];
    CGLContextObj context = _cglContext ?: [[self openGLContext] CGLContextObj];
    if (context) {
        CGLLockContext(context);
        [[self openGLContext] update];
        CGLUnlockContext(context);
    } else {
        [[self openGLContext] update];
    }
    [self updateBackingSize];
    [self requestRender];
}

- (void)viewDidMoveToWindow {
    [super viewDidMoveToWindow];
    [self updateBackingSize];
    [self requestRender];
}

- (void)setRenderContext:(mpv_render_context *)renderContext {
    @synchronized (self) {
        _renderContext = renderContext;
    }
    [self requestRender];
}

- (mpv_render_context *)renderContext {
    @synchronized (self) {
        return _renderContext;
    }
}

- (void)requestRender {
    _renderRequested.store(true);
}

- (void)startDisplayLinkIfNeeded {
    if (_displayLink || !_cglContext) {
        return;
    }

    CVDisplayLinkRef displayLink = NULL;
    CVReturn createResult = CVDisplayLinkCreateWithActiveCGDisplays(&displayLink);
    if (createResult != kCVReturnSuccess || !displayLink) {
        return;
    }

    CVDisplayLinkSetOutputCallback(displayLink, displayLinkCallback, (__bridge void *)self);
    CVDisplayLinkSetCurrentCGDisplayFromOpenGLContext(
        displayLink,
        _cglContext,
        [[self pixelFormat] CGLPixelFormatObj]
    );
    CVReturn startResult = CVDisplayLinkStart(displayLink);
    if (startResult != kCVReturnSuccess) {
        CVDisplayLinkRelease(displayLink);
        return;
    }
    _displayLink = displayLink;
}

- (void)stopRendering {
    CVDisplayLinkRef displayLink = _displayLink;
    _displayLink = NULL;
    if (displayLink) {
        CVDisplayLinkStop(displayLink);
        CVDisplayLinkRelease(displayLink);
    }
    [self clearRenderContext];
}

- (void)clearRenderContext {
    @synchronized (self) {
        _renderContext = nullptr;
    }
    _renderRequested.store(false);
}

- (void)updateBackingSize {
    NSRect backingBounds = [self convertRectToBacking:self.bounds];
    int width = (int)llround(backingBounds.size.width);
    int height = (int)llround(backingBounds.size.height);
    _backingWidth.store(MAX(width, 0));
    _backingHeight.store(MAX(height, 0));
    _drawableReady.store(self.window != nil && width > 0 && height > 0);
}

- (void)drawRect:(NSRect)dirtyRect {
    (void)dirtyRect;
    [self requestRender];
    if (!self.renderContext) {
        CGLContextObj context = _cglContext ?: [[self openGLContext] CGLContextObj];
        if (!context) {
            return;
        }
        CGLLockContext(context);
        CGLSetCurrentContext(context);
        glClear(GL_COLOR_BUFFER_BIT);
        CGLFlushDrawable(context);
        CGLUnlockContext(context);
    }
}

- (void)renderFrameFromDisplayLink {
    if (!_renderRequested.exchange(false)) {
        return;
    }
    if (!_drawableReady.load()) {
        return;
    }

    @synchronized (self) {
        mpv_render_context *context = _renderContext;
        CGLContextObj glContext = _cglContext;
        int width = _backingWidth.load();
        int height = _backingHeight.load();
        if (!context || !glContext || width <= 0 || height <= 0) {
            return;
        }

        CGLLockContext(glContext);
        CGLSetCurrentContext(glContext);

        GLint currentFbo = 0;
        glGetIntegerv(GL_FRAMEBUFFER_BINDING, &currentFbo);

        mpv_opengl_fbo fbo = {
            (int)currentFbo,
            width,
            height,
            GL_RGBA8
        };
        int flipY = 1;
        mpv_render_param params[] = {
            {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
            {MPV_RENDER_PARAM_FLIP_Y, &flipY},
            {MPV_RENDER_PARAM_INVALID, nullptr},
        };

        // MPV frame rendering is intentionally display-linked and off AppKit's
        // main thread so WKWebView controls stay responsive during playback.
        mpv_render_context_update(context);
        mpv_render_context_render(context, params);
        CGLFlushDrawable(glContext);
        mpv_render_context_report_swap(context);

        CGLUnlockContext(glContext);
    }
}

- (void)dealloc {
    [self stopRendering];
}

@end

static CVReturn displayLinkCallback(
    CVDisplayLinkRef /* displayLink */,
    const CVTimeStamp * /* now */,
    const CVTimeStamp * /* outputTime */,
    CVOptionFlags /* flagsIn */,
    CVOptionFlags * /* flagsOut */,
    void *displayLinkContext
) {
    @autoreleasepool {
        PlayerOpenGLView *view = (__bridge PlayerOpenGLView *)displayLinkContext;
        [view renderFrameFromDisplayLink];
    }
    return kCVReturnSuccess;
}

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
    NSString *audioTracks = [self audioTracksJson] ?: @"[]";
    NSString *subtitleTracks = [self subtitleTracksJson] ?: @"[]";
    NSString *script = [NSString stringWithFormat:
        @"window.playerUpdate({duration:%0.3f,position:%0.3f,paused:%@,audioTracks:%@,subtitleTracks:%@})",
        duration,
        position,
        paused ? @"true" : @"false",
        audioTracks,
        subtitleTracks];
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
        [_videoView stopRendering];
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

- (void)setResizeMode:(int)mode {
    if (!_mpv) return;
    switch (mode) {
        case 1:
            [self setStringProperty:"panscan" value:@"1.0"];
            [self setStringProperty:"video-unscaled" value:@"no"];
            break;
        case 2:
            [self setStringProperty:"panscan" value:@"1.0"];
            [self setStringProperty:"video-unscaled" value:@"no"];
            break;
        default:
            [self setStringProperty:"panscan" value:@"0.0"];
            [self setStringProperty:"video-unscaled" value:@"no"];
            break;
    }
}

- (long long)durationMs {
    return (long long)llround([self doubleProperty:"duration" fallback:0.0] * 1000.0);
}

- (long long)positionMs {
    return (long long)llround([self doubleProperty:"time-pos" fallback:0.0] * 1000.0);
}

- (long long)bufferedPositionMs {
    double position = [self doubleProperty:"time-pos" fallback:0.0];
    double cached = [self doubleProperty:"demuxer-cache-time" fallback:0.0];
    return (long long)llround(fmax(position + cached, 0.0) * 1000.0);
}

- (BOOL)isLoading {
    BOOL paused = [self isPaused];
    BOOL eofReached = [self isEnded];
    BOOL idle = [self flagProperty:"core-idle" fallback:YES];
    BOOL seeking = [self flagProperty:"seeking" fallback:NO];
    BOOL bufferingCache = [self flagProperty:"paused-for-cache" fallback:NO];
    BOOL fileReady = [self doubleProperty:"duration" fallback:0.0] > 0.0
        || [self int64Property:"track-list/count" fallback:0] > 0;
    return !fileReady || (idle && !paused && !eofReached) || seeking || bufferingCache;
}

- (BOOL)isEnded {
    return [self flagProperty:"eof-reached" fallback:NO];
}

- (NSString *)audioTracksJson {
    return [self tracksJsonForType:@"audio"];
}

- (NSString *)subtitleTracksJson {
    return [self tracksJsonForType:@"sub"];
}

- (void)selectAudioTrackId:(int)trackId {
    if (!_mpv) return;
    int64_t id = trackId;
    mpv_set_property(_mpv, "aid", MPV_FORMAT_INT64, &id);
}

- (void)selectSubtitleTrackId:(int)trackId {
    if (!_mpv) return;
    if (trackId < 0) {
        [self setStringProperty:"sid" value:@"no"];
        return;
    }
    int64_t id = trackId;
    mpv_set_property(_mpv, "sid", MPV_FORMAT_INT64, &id);
}

- (void)addSubtitleUrl:(NSString *)url {
    if (!_mpv || url.length == 0) return;
    [self command:@[@"sub-add", url, @"select"]];
}

- (void)removeExternalSubtitles {
    if (!_mpv) return;
    [self removeExternalSubtitleTracks];
    [self setStringProperty:"sid" value:@"no"];
}

- (void)removeExternalSubtitlesAndSelect:(int)trackId {
    if (!_mpv) return;
    [self removeExternalSubtitleTracks];
    if (trackId >= 0) {
        [self selectSubtitleTrackId:trackId];
    } else {
        [self setStringProperty:"sid" value:@"no"];
    }
}

- (void)setSubtitleDelayMs:(int)delayMs {
    if (!_mpv) return;
    int clamped = MAX(-60000, MIN(60000, delayMs));
    double delaySeconds = (double)clamped / 1000.0;
    mpv_set_property(_mpv, "sub-delay", MPV_FORMAT_DOUBLE, &delaySeconds);
}

- (void)applySubtitleStyleWithTextColor:(NSString *)textColor
                        backgroundColor:(NSString *)backgroundColor
                            outlineColor:(NSString *)outlineColor
                             outlineSize:(double)outlineSize
                                    bold:(BOOL)bold
                                fontSize:(double)fontSize
                                  subPos:(int)subPos {
    if (!_mpv) return;
    [self setStringProperty:"sub-ass-override" value:@"force"];
    [self setStringProperty:"sub-color" value:textColor ?: @"#FFFFFFFF"];
    [self setStringProperty:"sub-back-color" value:backgroundColor ?: @"#00000000"];
    [self setStringProperty:"sub-outline-color" value:outlineColor ?: @"#FF000000"];
    [self setStringProperty:"sub-border-style"
                      value:[(backgroundColor ?: @"") hasPrefix:@"#00"] ? @"outline-and-shadow" : @"opaque-box"];
    [self setStringProperty:"sub-bold" value:bold ? @"yes" : @"no"];

    double outline = MAX(0.0, MIN(8.0, outlineSize));
    mpv_set_property(_mpv, "sub-outline-size", MPV_FORMAT_DOUBLE, &outline);

    double size = MAX(24.0, MIN(96.0, fontSize));
    mpv_set_property(_mpv, "sub-font-size", MPV_FORMAT_DOUBLE, &size);

    int64_t position = MAX(0, MIN(150, subPos));
    mpv_set_property(_mpv, "sub-pos", MPV_FORMAT_INT64, &position);
}

- (double)doubleProperty:(const char *)name fallback:(double)fallback {
    if (!_mpv) return fallback;
    double value = fallback;
    if (mpv_get_property(_mpv, name, MPV_FORMAT_DOUBLE, &value) < 0) {
        return fallback;
    }
    return value;
}

- (long long)int64Property:(const char *)name fallback:(long long)fallback {
    if (!_mpv) return fallback;
    int64_t value = fallback;
    if (mpv_get_property(_mpv, name, MPV_FORMAT_INT64, &value) < 0) {
        return fallback;
    }
    return value;
}

- (BOOL)flagProperty:(const char *)name fallback:(BOOL)fallback {
    if (!_mpv) return fallback;
    int flag = fallback ? 1 : 0;
    if (mpv_get_property(_mpv, name, MPV_FORMAT_FLAG, &flag) < 0) {
        return fallback;
    }
    return flag != 0;
}

- (NSString *)stringProperty:(const char *)name fallback:(NSString *)fallback {
    if (!_mpv) return fallback ?: @"";
    char *value = nullptr;
    if (mpv_get_property(_mpv, name, MPV_FORMAT_STRING, &value) < 0 || !value) {
        return fallback ?: @"";
    }
    NSString *result = [NSString stringWithUTF8String:value] ?: (fallback ?: @"");
    mpv_free(value);
    return result;
}

- (void)setStringProperty:(const char *)name value:(NSString *)value {
    if (!_mpv) return;
    mpv_set_property_string(_mpv, name, (value ?: @"").UTF8String);
}

- (void)command:(NSArray<NSString *> *)args {
    if (!_mpv || args.count == 0) return;
    std::vector<const char *> cargs;
    cargs.reserve(args.count + 1);
    for (NSString *arg in args) {
        cargs.push_back((arg ?: @"").UTF8String);
    }
    cargs.push_back(nullptr);
    mpv_command(_mpv, cargs.data());
}

- (void)removeExternalSubtitleTracks {
    long long count = [self int64Property:"track-list/count" fallback:0];
    if (count <= 0) return;
    for (long long index = count - 1; index >= 0; index--) {
        NSString *typeKey = [NSString stringWithFormat:@"track-list/%lld/type", index];
        NSString *externalKey = [NSString stringWithFormat:@"track-list/%lld/external", index];
        NSString *idKey = [NSString stringWithFormat:@"track-list/%lld/id", index];
        NSString *type = [self stringProperty:typeKey.UTF8String fallback:@""];
        BOOL external = [self flagProperty:externalKey.UTF8String fallback:NO];
        if ([type isEqualToString:@"sub"] && external) {
            long long trackId = [self int64Property:idKey.UTF8String fallback:-1];
            if (trackId >= 0) {
                [self command:@[@"sub-remove", [NSString stringWithFormat:@"%lld", trackId]]];
            }
        }
    }
}

- (NSString *)tracksJsonForType:(NSString *)wantedType {
    if (!_mpv) return @"[]";
    NSMutableArray<NSDictionary *> *tracks = [NSMutableArray array];
    long long count = [self int64Property:"track-list/count" fallback:0];
    int logicalIndex = 0;

    for (long long index = 0; index < count; index++) {
        NSString *prefix = [NSString stringWithFormat:@"track-list/%lld", index];
        NSString *type = [self stringProperty:[[prefix stringByAppendingString:@"/type"] UTF8String] fallback:@""];
        if (![type isEqualToString:wantedType]) {
            continue;
        }

        long long trackId = [self int64Property:[[prefix stringByAppendingString:@"/id"] UTF8String] fallback:logicalIndex + 1];
        NSString *title = [self trackStringAtIndex:index field:@"title"];
        NSString *language = [self trackStringAtIndex:index field:@"lang"];
        NSString *codec = [self trackStringAtIndex:index field:@"codec"];
        NSString *decoderDescription = [self trackStringAtIndex:index field:@"decoder-desc"];
        NSString *channels = [self trackStringAtIndex:index field:@"demux-channels"];
        long long channelCount = [self int64Property:[[prefix stringByAppendingString:@"/demux-channel-count"] UTF8String] fallback:0];
        BOOL selected = [self flagProperty:[[prefix stringByAppendingString:@"/selected"] UTF8String] fallback:NO];
        BOOL forced = [self flagProperty:[[prefix stringByAppendingString:@"/forced"] UTF8String] fallback:NO];
        NSString *label = [self formatTrackTitleWithType:type
                                                   index:logicalIndex
                                                   title:title
                                                language:language
                                                   codec:codec
                                      decoderDescription:decoderDescription
                                                channels:channels
                                            channelCount:(int)channelCount];
        [tracks addObject:@{
            @"index": @(logicalIndex),
            @"id": [NSString stringWithFormat:@"%lld", trackId],
            @"label": label ?: @"",
            @"language": language ?: @"",
            @"selected": @(selected),
            @"forced": @(forced),
        }];
        logicalIndex += 1;
    }

    NSData *data = [NSJSONSerialization dataWithJSONObject:tracks options:0 error:nil];
    if (!data) return @"[]";
    return [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] ?: @"[]";
}

- (NSString *)trackStringAtIndex:(long long)index field:(NSString *)field {
    NSString *key = [NSString stringWithFormat:@"track-list/%lld/%@", index, field];
    return [[self stringProperty:key.UTF8String fallback:@""] stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
}

- (NSString *)formatTrackTitleWithType:(NSString *)type
                                 index:(int)index
                                 title:(NSString *)title
                              language:(NSString *)language
                                 codec:(NSString *)codec
                    decoderDescription:(NSString *)decoderDescription
                              channels:(NSString *)channels
                          channelCount:(int)channelCount {
    NSString *base = [self ifNotBlank:title]
        ?: [self localizedLanguageName:language]
        ?: ([type isEqualToString:@"sub"]
            ? [NSString stringWithFormat:@"Subtitle %d", index + 1]
            : [NSString stringWithFormat:@"Track %d", index + 1]);
    NSString *codecName = [self codecDisplayName:codec] ?: [self codecDisplayName:decoderDescription];
    NSString *channelName = [type isEqualToString:@"audio"]
        ? [self channelLayoutNameWithChannels:channels channelCount:channelCount]
        : nil;
    NSMutableArray<NSString *> *details = [NSMutableArray array];
    for (NSString *detail in @[channelName ?: @"", codecName ?: @""]) {
        if (detail.length == 0) continue;
        if ([base rangeOfString:detail options:NSCaseInsensitiveSearch].location == NSNotFound) {
            [details addObject:detail];
        }
    }
    return details.count == 0
        ? base
        : [NSString stringWithFormat:@"%@ (%@)", base, [details componentsJoinedByString:@", "]];
}

- (NSString *)ifNotBlank:(NSString *)value {
    NSString *trimmed = [(value ?: @"") stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    return trimmed.length == 0 ? nil : trimmed;
}

- (NSString *)localizedLanguageName:(NSString *)languageCode {
    NSString *code = [self ifNotBlank:languageCode];
    if (!code) return nil;
    return [[NSLocale currentLocale] displayNameForKey:NSLocaleLanguageCode value:code] ?: code;
}

- (NSString *)channelLayoutNameWithChannels:(NSString *)channels channelCount:(int)channelCount {
    NSString *normalized = [self ifNotBlank:channels];
    if (normalized && ![normalized isEqualToString:@"unknown"]) {
        NSString *lower = normalized.lowercaseString;
        if ([lower isEqualToString:@"mono"]) return @"Mono";
        if ([lower isEqualToString:@"stereo"]) return @"Stereo";
        return normalized;
    }
    switch (channelCount) {
        case 1: return @"Mono";
        case 2: return @"Stereo";
        case 6: return @"5.1";
        case 8: return @"7.1";
        default:
            return channelCount > 0 ? [NSString stringWithFormat:@"%dch", channelCount] : nil;
    }
}

- (NSString *)codecDisplayName:(NSString *)value {
    NSString *raw = [self ifNotBlank:value];
    if (!raw) return nil;
    NSString *codec = raw.lowercaseString;
    if ([codec containsString:@"eac3"] || [codec containsString:@"e-ac-3"] || [codec containsString:@"e ac-3"]) {
        return ([codec containsString:@"joc"] || [codec containsString:@"atmos"]) ? @"E-AC-3-JOC" : @"E-AC-3";
    }
    if ([codec containsString:@"truehd"] || [codec containsString:@"true hd"]) return @"TrueHD";
    if ([codec containsString:@"ac3"] || [codec containsString:@"ac-3"]) return @"AC-3";
    if ([codec containsString:@"dts-hd"] || [codec containsString:@"dtshd"] || [codec containsString:@"dts hd"]) return @"DTS-HD";
    if ([codec containsString:@"dts"] || [codec isEqualToString:@"dca"]) return @"DTS";
    if ([codec containsString:@"aac"]) return @"AAC";
    if ([codec containsString:@"mp3"] || [codec containsString:@"mpeg audio"]) return @"MP3";
    if ([codec containsString:@"mp2"]) return @"MP2";
    if ([codec containsString:@"opus"]) return @"Opus";
    if ([codec containsString:@"vorbis"]) return @"Vorbis";
    if ([codec containsString:@"flac"]) return @"FLAC";
    if ([codec containsString:@"alac"]) return @"ALAC";
    if ([codec containsString:@"pcm"] || [codec containsString:@"wav"]) return @"WAV";
    if ([codec containsString:@"amr_wb"] || [codec containsString:@"amr-wb"]) return @"AMR-WB";
    if ([codec containsString:@"amr_nb"] || [codec containsString:@"amr-nb"]) return @"AMR-NB";
    if ([codec containsString:@"amr"]) return @"AMR";
    if ([codec containsString:@"iamf"]) return @"IAMF";
    if ([codec containsString:@"mpegh"] || [codec containsString:@"mpeg-h"]) return @"MPEG-H";
    if ([codec containsString:@"pgs"] || [codec containsString:@"hdmv"]) return @"PGS";
    if ([codec containsString:@"subrip"] || [codec isEqualToString:@"srt"]) return @"SRT";
    if ([codec containsString:@"ass"] || [codec containsString:@"ssa"]) return @"SSA";
    if ([codec containsString:@"webvtt"] || [codec isEqualToString:@"vtt"]) return @"VTT";
    if ([codec containsString:@"ttml"]) return @"TTML";
    if ([codec containsString:@"mov_text"] || [codec containsString:@"tx3g"]) return @"TX3G";
    if ([codec containsString:@"dvb"]) return @"DVB";
    return raw;
}

- (void)handleScriptMessage:(NSDictionary *)message {
    NSString *type = message[@"type"];
    if (![type isKindOfClass:[NSString class]]) {
        return;
    }

    NSNumber *value = message[@"value"];
    if ([type isEqualToString:@"selectAudioTrack"] && value) {
        [self selectAudioTrackId:(int)llround(value.doubleValue)];
        [self syncControls];
        return;
    }
    if ([type isEqualToString:@"selectSubtitleTrack"] && value) {
        [self selectSubtitleTrackId:(int)llround(value.doubleValue)];
        [self syncControls];
        return;
    }

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

extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_bufferedPositionMs(
    JNIEnv * /* env */,
    jobject /* bridge */,
    jlong handle
) {
    if (handle == 0) return 0;
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    return [player bufferedPositionMs];
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_isLoading(
    JNIEnv * /* env */,
    jobject /* bridge */,
    jlong handle
) {
    if (handle == 0) return JNI_TRUE;
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    return [player isLoading] ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_isEnded(
    JNIEnv * /* env */,
    jobject /* bridge */,
    jlong handle
) {
    if (handle == 0) return JNI_FALSE;
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    return [player isEnded] ? JNI_TRUE : JNI_FALSE;
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

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setResizeMode(
    JNIEnv * /* env */,
    jobject /* bridge */,
    jlong handle,
    jint mode
) {
    if (handle == 0) return;
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    runOnMainAsync(^{
        [player setResizeMode:(int)mode];
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_audioTracksJson(
    JNIEnv *env,
    jobject /* bridge */,
    jlong handle
) {
    if (handle == 0) return env->NewStringUTF("[]");
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    NSString *json = [player audioTracksJson] ?: @"[]";
    return env->NewStringUTF(json.UTF8String);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_subtitleTracksJson(
    JNIEnv *env,
    jobject /* bridge */,
    jlong handle
) {
    if (handle == 0) return env->NewStringUTF("[]");
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    NSString *json = [player subtitleTracksJson] ?: @"[]";
    return env->NewStringUTF(json.UTF8String);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_selectAudioTrack(
    JNIEnv * /* env */,
    jobject /* bridge */,
    jlong handle,
    jint trackId
) {
    if (handle == 0) return;
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    runOnMainAsync(^{
        [player selectAudioTrackId:(int)trackId];
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_selectSubtitleTrack(
    JNIEnv * /* env */,
    jobject /* bridge */,
    jlong handle,
    jint trackId
) {
    if (handle == 0) return;
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    runOnMainAsync(^{
        [player selectSubtitleTrackId:(int)trackId];
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_addSubtitleUrl(
    JNIEnv *env,
    jobject /* bridge */,
    jlong handle,
    jstring url
) {
    if (handle == 0) return;
    std::string subtitleUrl = jstringToString(env, url);
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    runOnMainAsync(^{
        [player addSubtitleUrl:[NSString stringWithUTF8String:subtitleUrl.c_str()]];
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_clearExternalSubtitles(
    JNIEnv * /* env */,
    jobject /* bridge */,
    jlong handle
) {
    if (handle == 0) return;
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    runOnMainAsync(^{
        [player removeExternalSubtitles];
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_clearExternalSubtitlesAndSelect(
    JNIEnv * /* env */,
    jobject /* bridge */,
    jlong handle,
    jint trackId
) {
    if (handle == 0) return;
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    runOnMainAsync(^{
        [player removeExternalSubtitlesAndSelect:(int)trackId];
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_setSubtitleDelayMs(
    JNIEnv * /* env */,
    jobject /* bridge */,
    jlong handle,
    jint delayMs
) {
    if (handle == 0) return;
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    runOnMainAsync(^{
        [player setSubtitleDelayMs:(int)delayMs];
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_applySubtitleStyle(
    JNIEnv *env,
    jobject /* bridge */,
    jlong handle,
    jstring textColor,
    jstring backgroundColor,
    jstring outlineColor,
    jfloat outlineSize,
    jboolean bold,
    jfloat fontSize,
    jint subPos
) {
    if (handle == 0) return;
    std::string text = jstringToString(env, textColor);
    std::string background = jstringToString(env, backgroundColor);
    std::string outline = jstringToString(env, outlineColor);
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)(void *)(intptr_t)handle;
    runOnMainAsync(^{
        [player applySubtitleStyleWithTextColor:[NSString stringWithUTF8String:text.c_str()]
                                backgroundColor:[NSString stringWithUTF8String:background.c_str()]
                                    outlineColor:[NSString stringWithUTF8String:outline.c_str()]
                                     outlineSize:(double)outlineSize
                                            bold:bold == JNI_TRUE
                                        fontSize:(double)fontSize
                                          subPos:(int)subPos];
    });
}
