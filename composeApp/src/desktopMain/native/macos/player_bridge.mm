#import <Cocoa/Cocoa.h>
#import <IOKit/IOKitLib.h>
#import <Metal/Metal.h>
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
} mpv_event_id;

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
int mpv_request_log_messages(mpv_handle *ctx, const char *min_level);
mpv_event *mpv_wait_event(mpv_handle *ctx, double timeout);
void mpv_set_wakeup_callback(mpv_handle *ctx, void (*cb)(void *d), void *d);
}

@class PlayerMetalView;
@class MpvWebPlayer;

@interface PlayerMetalView : NSView
@property(nonatomic, readonly) CAMetalLayer *metalLayer;
- (int64_t)wid;
- (double)edrMax;
- (double)hdrTargetPeakNits;
- (CGSize)drawableSize;
- (NSString *)diagnosticSummary;
- (void)updateMetalLayerLayout;
- (void)refreshMetalLayerEdrState;
- (void)configureExtendedDynamicRange:(BOOL)enabled primaries:(NSString *)primaries;
@end

@interface NuvioPlayerMetalLayer : CAMetalLayer
@property(nonatomic, assign, getter=isNuvioLiveResize) BOOL nuvioLiveResize;
@end

@interface PlayerScriptHandler : NSObject <WKScriptMessageHandler>
@property(nonatomic, weak) MpvWebPlayer *player;
@end

@interface MpvWebPlayer : NSObject
- (instancetype)initWithHostView:(NSView *)hostView
                       sourceUrl:(NSString *)sourceUrl
                     headerLines:(NSArray<NSString *> *)headerLines
                    playWhenReady:(BOOL)playWhenReady
                 initialPositionMs:(long long)initialPositionMs
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
- (void)focusControlsWebViewIfNeeded;
- (void)layoutNativeSubviews;
- (void)hostViewBoundsDidChange:(NSNotification *)notification;
- (void)hostViewFrameDidChange:(NSNotification *)notification;
- (void)schedulePostResizeRefreshWithReason:(NSString *)reason;
- (void)handleResizeSettleTimer:(NSTimer *)timer;
- (void)configureHdrForCurrentScreenWithReason:(NSString *)reason force:(BOOL)force;
- (void)logResizeStateWithReason:(NSString *)reason;
- (void)scheduleMpvEventDrain;
@end

typedef CFDictionaryRef (*CoreDisplayCreateInfoDictionaryFn)(CGDirectDisplayID displayId);

static void setBoolWithSelector(id target, NSString *selectorName, BOOL value) {
    SEL selector = NSSelectorFromString(selectorName);
    if (!target || !selector || ![target respondsToSelector:selector]) {
        return;
    }
    typedef void (*Setter)(id, SEL, BOOL);
    Setter setter = (Setter)[target methodForSelector:selector];
    setter(target, selector, value);
}

static double doubleWithSelector(id target, NSString *selectorName, double fallback) {
    SEL selector = NSSelectorFromString(selectorName);
    if (!target || !selector || ![target respondsToSelector:selector]) {
        return fallback;
    }
    typedef double (*Getter)(id, SEL);
    Getter getter = (Getter)[target methodForSelector:selector];
    return getter(target, selector);
}

static BOOL setLayerColorSpace(CALayer *layer, CGColorSpaceRef colorSpace) {
    SEL selector = NSSelectorFromString(@"setColorspace:");
    if (!layer || !colorSpace || ![layer respondsToSelector:selector]) {
        return NO;
    }
    typedef void (*Setter)(id, SEL, CGColorSpaceRef);
    Setter setter = (Setter)[layer methodForSelector:selector];
    setter(layer, selector, colorSpace);
    return YES;
}

static BOOL setLayerEdrMetadata(CALayer *layer, double minNits, double maxNits, double opticalOutputScale) {
    if (!layer || maxNits <= 0.0) {
        return NO;
    }

    Class edrMetadataClass = NSClassFromString(@"CAEDRMetadata");
    SEL selector = NSSelectorFromString(@"HDR10MetadataWithMinLuminance:maxLuminance:opticalOutputScale:");
    if (!edrMetadataClass || ![edrMetadataClass respondsToSelector:selector]) {
        return NO;
    }

    typedef id (*Factory)(id, SEL, float, float, float);
    Factory factory = (Factory)[edrMetadataClass methodForSelector:selector];
    id metadata = factory(edrMetadataClass, selector, (float)minNits, (float)maxNits, (float)opticalOutputScale);
    if (!metadata) {
        return NO;
    }

    SEL setterSelector = NSSelectorFromString(@"setEDRMetadata:");
    if (![layer respondsToSelector:setterSelector]) {
        return NO;
    }
    typedef void (*Setter)(id, SEL, id);
    Setter setter = (Setter)[layer methodForSelector:setterSelector];
    setter(layer, setterSelector, metadata);
    return YES;
}

static void clearLayerEdrMetadata(CALayer *layer) {
    SEL selector = NSSelectorFromString(@"setEDRMetadata:");
    if (!layer || ![layer respondsToSelector:selector]) {
        return;
    }
    typedef void (*Setter)(id, SEL, id);
    Setter setter = (Setter)[layer methodForSelector:selector];
    setter(layer, selector, nil);
}

static NSString *diagnosticSize(CGSize size) {
    return [NSString stringWithFormat:@"%0.0fx%0.0f", size.width, size.height];
}

static NSString *diagnosticRect(NSRect rect) {
    return [NSString stringWithFormat:
        @"%0.0fx%0.0f@%0.0f,%0.0f",
        rect.size.width,
        rect.size.height,
        rect.origin.x,
        rect.origin.y
    ];
}

static NSString *metalPixelFormatName(MTLPixelFormat format) {
    switch (format) {
        case MTLPixelFormatRGBA16Float: return @"rgba16Float";
        case MTLPixelFormatBGRA8Unorm: return @"bgra8Unorm";
        case MTLPixelFormatBGRA8Unorm_sRGB: return @"bgra8Unorm_srgb";
        case MTLPixelFormatRGBA8Unorm: return @"rgba8Unorm";
        case MTLPixelFormatRGBA8Unorm_sRGB: return @"rgba8Unorm_srgb";
        case MTLPixelFormatRGB10A2Unorm: return @"rgb10a2Unorm";
        case MTLPixelFormatBGR10A2Unorm: return @"bgr10a2Unorm";
        default: return [NSString stringWithFormat:@"%lu", (unsigned long)format];
    }
}

static NSString *layerColorSpaceName(CALayer *layer) {
    SEL selector = NSSelectorFromString(@"colorspace");
    if (!layer || ![layer respondsToSelector:selector]) {
        return @"unavailable";
    }
    typedef CGColorSpaceRef (*Getter)(id, SEL);
    Getter getter = (Getter)[layer methodForSelector:selector];
    CGColorSpaceRef colorSpace = getter(layer, selector);
    if (!colorSpace) {
        return @"nil";
    }
    NSString *name = (__bridge_transfer NSString *)CGColorSpaceCopyName(colorSpace);
    return name.length > 0 ? name : @"unnamed";
}

static NSString *layerEdrMetadataName(CALayer *layer) {
    SEL selector = NSSelectorFromString(@"EDRMetadata");
    if (!layer || ![layer respondsToSelector:selector]) {
        return @"unavailable";
    }
    typedef id (*Getter)(id, SEL);
    Getter getter = (Getter)[layer methodForSelector:selector];
    id metadata = getter(layer, selector);
    return metadata ? NSStringFromClass([metadata class]) : @"none";
}

static CGDirectDisplayID displayIdForScreen(NSScreen *screen) {
    NSNumber *screenNumber = screen.deviceDescription[@"NSScreenNumber"];
    return screenNumber ? (CGDirectDisplayID)screenNumber.unsignedIntValue : CGMainDisplayID();
}

static double edrMaxForScreen(NSScreen *screen) {
    NSScreen *targetScreen = screen ?: NSScreen.mainScreen;
    double maxValue = doubleWithSelector(
        targetScreen,
        @"maximumPotentialExtendedDynamicRangeColorComponentValue",
        1.0
    );
    if (!std::isfinite(maxValue) || maxValue <= 0.0) {
        return 1.0;
    }
    return maxValue;
}

static CoreDisplayCreateInfoDictionaryFn coreDisplayInfoDictionaryFn(void) {
    static BOOL didLookup = NO;
    static CoreDisplayCreateInfoDictionaryFn fn = nullptr;
    if (!didLookup) {
        didLookup = YES;
        void *handle = dlopen(
            "/System/Library/Frameworks/CoreDisplay.framework/CoreDisplay",
            RTLD_LAZY | RTLD_LOCAL
        );
        fn = (CoreDisplayCreateInfoDictionaryFn)dlsym(
            handle ?: RTLD_DEFAULT,
            "CoreDisplay_DisplayCreateInfoDictionary"
        );
    }
    return fn;
}

static double doubleFromCoreDisplayValue(CFTypeRef value) {
    if (!value) {
        return 0.0;
    }
    CFTypeID typeId = CFGetTypeID(value);
    if (typeId == CFNumberGetTypeID()) {
        double result = 0.0;
        if (CFNumberGetValue((CFNumberRef)value, kCFNumberDoubleType, &result)) {
            return result;
        }
        return 0.0;
    }
    if (typeId == CFDictionaryGetTypeID()) {
        CFDictionaryRef dictionary = (CFDictionaryRef)value;
        double directPeak = doubleFromCoreDisplayValue(
            CFDictionaryGetValue(dictionary, CFSTR("NonReferencePeakHDRLuminance"))
        );
        if (directPeak > 0.0) {
            return directPeak;
        }
        return doubleFromCoreDisplayValue(
            CFDictionaryGetValue(dictionary, CFSTR("DisplayBacklight"))
        );
    }
    return 0.0;
}

static double coreDisplayPeakNitsForScreen(NSScreen *screen) {
    CoreDisplayCreateInfoDictionaryFn fn = coreDisplayInfoDictionaryFn();
    if (!fn) {
        return 0.0;
    }
    CFDictionaryRef info = fn(displayIdForScreen(screen ?: NSScreen.mainScreen));
    if (!info) {
        return 0.0;
    }
    double peak = doubleFromCoreDisplayValue(info);
    CFRelease(info);
    if (!std::isfinite(peak) || peak < 100.0 || peak > 10000.0) {
        return 0.0;
    }
    return peak;
}

static double fixedPointNitsFromRegistryValue(CFTypeRef value) {
    if (!value || CFGetTypeID(value) != CFNumberGetTypeID()) {
        return 0.0;
    }
    double raw = 0.0;
    if (!CFNumberGetValue((CFNumberRef)value, kCFNumberDoubleType, &raw) || raw <= 0.0) {
        return 0.0;
    }
    double nits = raw / 65536.0;
    if (!std::isfinite(nits) || nits < 100.0 || nits > 10000.0) {
        return 0.0;
    }
    return nits;
}

static double ioRegistryPeakNits(void) {
    CFMutableDictionaryRef matching = IOServiceMatching("IOMobileFramebufferShim");
    if (!matching) {
        return 0.0;
    }

    io_iterator_t iterator = IO_OBJECT_NULL;
    kern_return_t result = IOServiceGetMatchingServices(MACH_PORT_NULL, matching, &iterator);
    if (result != KERN_SUCCESS || iterator == IO_OBJECT_NULL) {
        return 0.0;
    }

    double peak = 0.0;
    io_object_t service = IO_OBJECT_NULL;
    while ((service = IOIteratorNext(iterator)) != IO_OBJECT_NULL) {
        CFTypeRef indicatorCap = IORegistryEntryCreateCFProperty(
            service,
            CFSTR("IOMFBIndicatorNitsCap"),
            kCFAllocatorDefault,
            0
        );
        peak = fmax(peak, fixedPointNitsFromRegistryValue(indicatorCap));
        if (indicatorCap) {
            CFRelease(indicatorCap);
        }

        CFTypeRef rtplcCap = IORegistryEntryCreateCFProperty(
            service,
            CFSTR("RTPLCBLNitsCap"),
            kCFAllocatorDefault,
            0
        );
        peak = fmax(peak, fixedPointNitsFromRegistryValue(rtplcCap));
        if (rtplcCap) {
            CFRelease(rtplcCap);
        }

        CFTypeRef backlightCap = IORegistryEntryCreateCFProperty(
            service,
            CFSTR("BLNitsCap"),
            kCFAllocatorDefault,
            0
        );
        peak = fmax(peak, fixedPointNitsFromRegistryValue(backlightCap));
        if (backlightCap) {
            CFRelease(backlightCap);
        }

        IOObjectRelease(service);
    }
    IOObjectRelease(iterator);
    return peak;
}

static double hdrTargetPeakNitsForScreen(NSScreen *screen) {
    double coreDisplayPeak = coreDisplayPeakNitsForScreen(screen);
    if (coreDisplayPeak > 0.0) {
        return coreDisplayPeak;
    }
    double registryPeak = ioRegistryPeakNits();
    if (registryPeak > 0.0) {
        return registryPeak;
    }
    double edrMax = edrMaxForScreen(screen);
    if (edrMax > 1.0) {
        return fmin(fmax(edrMax * 100.0, 100.0), 10000.0);
    }
    return 100.0;
}

static double hdrMetadataMaxNits(void) {
    return 1000.0;
}

@implementation NuvioPlayerMetalLayer
- (void)setDrawableSize:(CGSize)drawableSize {
    // MoltenVK temporarily writes 1x1 during swapchain churn. Letting that
    // reach CAMetalLayer produces a visible black flash during playback resize.
    if (drawableSize.width > 1.0 && drawableSize.height > 1.0) {
        [super setDrawableSize:drawableSize];
    }
}
@end

@implementation PlayerMetalView {
    CAMetalLayer *_metalLayer;
    BOOL _hasConfiguredEdr;
    BOOL _edrEnabled;
}

- (instancetype)initWithFrame:(NSRect)frameRect {
    self = [super initWithFrame:frameRect];
    if (!self) {
        return nil;
    }

    self.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    self.wantsLayer = YES;

    _metalLayer = [NuvioPlayerMetalLayer layer];
    _metalLayer.device = MTLCreateSystemDefaultDevice();
    _metalLayer.frame = self.bounds;
    _metalLayer.contentsGravity = kCAGravityResize;
    _metalLayer.framebufferOnly = YES;
    _metalLayer.pixelFormat = MTLPixelFormatRGBA16Float;
    _metalLayer.opaque = YES;
    _metalLayer.backgroundColor = NSColor.blackColor.CGColor;
    setBoolWithSelector(_metalLayer, @"setWantsExtendedDynamicRangeContent:", YES);
    self.layer = _metalLayer;
    [self updateMetalLayerLayout];

    NSLog(
        @"[NuvioDesktopPlayer] render: MPVKit Metal surface attached (%0.0fx%0.0f) device=%@ pixelFormat=rgba16Float",
        frameRect.size.width,
        frameRect.size.height,
        _metalLayer.device.name ?: @"none"
    );
    return self;
}

- (BOOL)isOpaque {
    return YES;
}

- (CALayer *)makeBackingLayer {
    return _metalLayer ?: [NuvioPlayerMetalLayer layer];
}

- (CAMetalLayer *)metalLayer {
    return _metalLayer;
}

- (int64_t)wid {
    return (int64_t)(intptr_t)_metalLayer;
}

- (void)layout {
    [super layout];
    [self updateMetalLayerLayout];
}

- (void)setFrameSize:(NSSize)newSize {
    [super setFrameSize:newSize];
    [self updateMetalLayerLayout];
}

- (void)setBoundsSize:(NSSize)newSize {
    [super setBoundsSize:newSize];
    [self updateMetalLayerLayout];
}

- (void)viewDidMoveToWindow {
    [super viewDidMoveToWindow];
    [self updateMetalLayerLayout];
    setBoolWithSelector(self, @"setWantsExtendedDynamicRangeContent:", YES);
    setBoolWithSelector(self.window, @"setWantsExtendedDynamicRangeContent:", YES);
    setBoolWithSelector(self.window.contentView, @"setWantsExtendedDynamicRangeContent:", YES);
    setBoolWithSelector(_metalLayer, @"setWantsExtendedDynamicRangeContent:", YES);
}

- (void)viewWillStartLiveResize {
    [super viewWillStartLiveResize];
    ((NuvioPlayerMetalLayer *)_metalLayer).nuvioLiveResize = YES;
}

- (void)viewDidEndLiveResize {
    [super viewDidEndLiveResize];
    ((NuvioPlayerMetalLayer *)_metalLayer).nuvioLiveResize = NO;
    [self updateMetalLayerLayout];
}

- (void)updateMetalLayerLayout {
    if (!_metalLayer) {
        return;
    }

    NSSize boundsSize = self.bounds.size;
    if (boundsSize.width <= 1.0 || boundsSize.height <= 1.0) {
        return;
    }

    CGFloat scale = self.window.backingScaleFactor > 0.0
        ? self.window.backingScaleFactor
        : NSScreen.mainScreen.backingScaleFactor;
    CGSize drawableSize = CGSizeMake(
        llround(boundsSize.width * scale),
        llround(boundsSize.height * scale)
    );
    if (drawableSize.width <= 1.0 || drawableSize.height <= 1.0) {
        return;
    }

    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    ((NuvioPlayerMetalLayer *)_metalLayer).nuvioLiveResize = self.inLiveResize;
    _metalLayer.contentsScale = scale;
    _metalLayer.frame = self.bounds;
    CGSize previousDrawableSize = _metalLayer.drawableSize;
    if (!CGSizeEqualToSize(previousDrawableSize, drawableSize)) {
        _metalLayer.drawableSize = drawableSize;
        NSLog(
            @"[NuvioDesktopPlayer] resize: drawable old=%@ new=%@ viewBounds=%@ scale=%.2f layer=%@",
            diagnosticSize(previousDrawableSize),
            diagnosticSize(drawableSize),
            diagnosticRect(self.bounds),
            scale,
            [self diagnosticSummary]
        );
    }
    [self refreshMetalLayerEdrState];
    [CATransaction commit];
}

- (void)refreshMetalLayerEdrState {
    if (!_hasConfiguredEdr || !_metalLayer) {
        return;
    }

    setBoolWithSelector(self, @"setWantsExtendedDynamicRangeContent:", YES);
    setBoolWithSelector(self.window, @"setWantsExtendedDynamicRangeContent:", YES);
    setBoolWithSelector(self.window.contentView, @"setWantsExtendedDynamicRangeContent:", YES);
    setBoolWithSelector(_metalLayer, @"setWantsExtendedDynamicRangeContent:", YES);

    CGColorSpaceRef colorSpace = _edrEnabled
        ? CGColorSpaceCreateWithName(kCGColorSpaceITUR_2100_PQ)
        : CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    if (colorSpace) {
        setLayerColorSpace(_metalLayer, colorSpace);
        CGColorSpaceRelease(colorSpace);
    }

    if (_edrEnabled) {
        setLayerEdrMetadata(_metalLayer, 0.0, hdrMetadataMaxNits(), 1.0);
    } else {
        clearLayerEdrMetadata(_metalLayer);
    }
}

- (void)configureExtendedDynamicRange:(BOOL)enabled primaries:(NSString *)primaries {
    BOOL stateChanged = !_hasConfiguredEdr || _edrEnabled != enabled;
    _hasConfiguredEdr = YES;
    _edrEnabled = enabled;

    setBoolWithSelector(self, @"setWantsExtendedDynamicRangeContent:", YES);
    setBoolWithSelector(self.window, @"setWantsExtendedDynamicRangeContent:", YES);
    setBoolWithSelector(self.window.contentView, @"setWantsExtendedDynamicRangeContent:", YES);
    setBoolWithSelector(_metalLayer, @"setWantsExtendedDynamicRangeContent:", YES);

    CGColorSpaceRef colorSpace = enabled
        ? CGColorSpaceCreateWithName(kCGColorSpaceITUR_2100_PQ)
        : CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
    BOOL didSetColorSpace = colorSpace ? setLayerColorSpace(_metalLayer, colorSpace) : NO;
    if (colorSpace) {
        CGColorSpaceRelease(colorSpace);
    }
    if (enabled && !didSetColorSpace) {
        NSLog(@"[NuvioDesktopPlayer] macos_edr: CAMetalLayer colorspace selector unavailable");
    }

    BOOL didSetMetadata = NO;
    if (enabled) {
        didSetMetadata = setLayerEdrMetadata(_metalLayer, 0.0, hdrMetadataMaxNits(), 1.0);
    } else {
        clearLayerEdrMetadata(_metalLayer);
    }

    NSLog(
        @"[NuvioDesktopPlayer] macos_edr: CAMetalLayer EDR %@ primaries=%@ edrMax=%.2f pixelFormat=%@ layerCS=%@ metadata=%@",
        stateChanged ? (enabled ? @"enabled" : @"disabled") : (enabled ? @"refreshed-enabled" : @"refreshed-disabled"),
        primaries ?: @"unknown",
        [self edrMax],
        metalPixelFormatName(_metalLayer.pixelFormat),
        layerColorSpaceName(_metalLayer),
        didSetMetadata ? layerEdrMetadataName(_metalLayer) : @"none"
    );
}

- (double)edrMax {
    return edrMaxForScreen(self.window.screen ?: NSScreen.mainScreen);
}

- (double)hdrTargetPeakNits {
    return hdrTargetPeakNitsForScreen(self.window.screen ?: NSScreen.mainScreen);
}

- (CGSize)drawableSize {
    return _metalLayer ? _metalLayer.drawableSize : CGSizeZero;
}

- (NSString *)diagnosticSummary {
    if (!_metalLayer) {
        return @"layer=none";
    }
    return [NSString stringWithFormat:
        @"frame=%@ bounds=%@ drawable=%@ scale=%.2f liveResize=%@ pixelFormat=%@ edr=%@ layerCS=%@ metadata=%@ edrMax=%.2f screenPeak=%.0f",
        diagnosticRect(self.frame),
        diagnosticRect(self.bounds),
        diagnosticSize(_metalLayer.drawableSize),
        _metalLayer.contentsScale,
        ((NuvioPlayerMetalLayer *)_metalLayer).nuvioLiveResize ? @"yes" : @"no",
        metalPixelFormatName(_metalLayer.pixelFormat),
        _edrEnabled ? @"on" : @"off",
        layerColorSpaceName(_metalLayer),
        layerEdrMetadataName(_metalLayer),
        [self edrMax],
        [self hdrTargetPeakNits]
    ];
}

@end

static void mpvWakeupCallback(void *callbackContext) {
    MpvWebPlayer *player = (__bridge MpvWebPlayer *)callbackContext;
    [player scheduleMpvEventDrain];
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
    NSError *error = nil;
    NSData *data = [NSJSONSerialization dataWithJSONObject:array options:0 error:&error];
    if (!data) {
        NSLog(
            @"[NuvioDesktopControls][native] failed to encode JS string literal length=%lu error=%@",
            (unsigned long)(value ? value.length : 0),
            error.localizedDescription ?: @"unknown"
        );
        return @"\"\"";
    }
    NSString *jsonArray = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
    if (jsonArray.length < 2) {
        return @"\"\"";
    }
    return [jsonArray substringWithRange:NSMakeRange(1, jsonArray.length - 2)];
}

static void setMpvOptionString(mpv_handle *mpv, const char *name, const char *value) {
    int result = mpv_set_option_string(mpv, name, value);
    if (result < 0) {
        NSLog(
            @"[NuvioDesktopPlayer] mpv option failed %s=%s error=%s",
            name,
            value,
            mpv_error_string(result)
        );
    }
}

static NSString *redactUrlsInText(NSString *text) {
    if (text.length == 0 || ([text rangeOfString:@"http://"].location == NSNotFound
        && [text rangeOfString:@"https://"].location == NSNotFound)) {
        return text ?: @"";
    }

    NSError *error = nil;
    NSRegularExpression *regex = [NSRegularExpression regularExpressionWithPattern:@"https?://[^\\s\\]\"]+"
                                                                           options:0
                                                                             error:&error];
    if (!regex || error) {
        return @"<redacted-url>";
    }
    NSRange range = NSMakeRange(0, text.length);
    return [regex stringByReplacingMatchesInString:text
                                           options:0
                                             range:range
                                      withTemplate:@"<redacted-url>"];
}

static BOOL isControlsDiagnosticMessage(NSString *type) {
    return [type isEqualToString:@"toggle"]
        || [type isEqualToString:@"seekBack"]
        || [type isEqualToString:@"seekForward"]
        || [type isEqualToString:@"doubleTapSeekBack"]
        || [type isEqualToString:@"doubleTapSeekForward"]
        || [type isEqualToString:@"scrubFinish"]
        || [type isEqualToString:@"sources"]
        || [type isEqualToString:@"reloadSources"]
        || [type isEqualToString:@"selectSource"]
        || [type isEqualToString:@"episodes"]
        || [type isEqualToString:@"reloadEpisodeStreams"]
        || [type isEqualToString:@"selectEpisode"]
        || [type isEqualToString:@"selectEpisodeStream"]
        || [type isEqualToString:@"backToEpisodes"];
}

static NSString *limitDiagnosticText(NSString *text) {
    NSString *safeText = redactUrlsInText(text ?: @"");
    if (safeText.length <= 600) {
        return safeText;
    }
    return [[safeText substringToIndex:600] stringByAppendingString:@"..."];
}

@implementation MpvWebPlayer {
    NSView *_hostView;
    PlayerMetalView *_videoView;
    WKWebView *_webView;
    PlayerScriptHandler *_scriptHandler;
    mpv_handle *_mpv;
    NSTimer *_timer;
    NSTimer *_resizeSettleTimer;
    JavaVM *_javaVm;
    jobject _eventSink;
    jmethodID _eventMethod;
    NSString *_lastLoggedHwdec;
    NSString *_lastConfiguredHdrKey;
    NSString *_lastLoggedHdrTargetKey;
    NSString *_lastResizeDiagnosticKey;
    NSMutableSet<NSString *> *_loggedMpvDiagnostics;
    dispatch_queue_t _mpvEventQueue;
    std::atomic_bool _eventDrainScheduled;
    BOOL _didLogSoftwareDecodeWarning;
    BOOL _didFocusControlsWebView;
    BOOL _controlsWebReady;
    NSString *_pendingControlsJson;
    double _initialStartSeconds;
}

- (instancetype)initWithHostView:(NSView *)hostView
                       sourceUrl:(NSString *)sourceUrl
                     headerLines:(NSArray<NSString *> *)headerLines
                    playWhenReady:(BOOL)playWhenReady
                 initialPositionMs:(long long)initialPositionMs
                     controlsHtml:(NSString *)controlsHtml
                           javaVm:(JavaVM *)javaVm
                        eventSink:(jobject)eventSink
                      eventMethod:(jmethodID)eventMethod {
    self = [super init];
    if (!self) {
        return nil;
    }

    _eventDrainScheduled.store(false);
    _mpvEventQueue = dispatch_queue_create("com.nuvio.desktop.mpv-events", DISPATCH_QUEUE_SERIAL);
    _loggedMpvDiagnostics = [NSMutableSet set];
    _javaVm = javaVm;
    _eventSink = eventSink;
    _eventMethod = eventMethod;

    _hostView = hostView;
    _hostView.wantsLayer = YES;
    _hostView.layer.backgroundColor = NSColor.blackColor.CGColor;
    [_hostView setPostsFrameChangedNotifications:YES];
    [_hostView setPostsBoundsChangedNotifications:YES];

    _videoView = [[PlayerMetalView alloc] initWithFrame:_hostView.bounds];
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
    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(hostViewFrameDidChange:)
                                                 name:NSViewFrameDidChangeNotification
                                               object:_hostView];
    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(hostViewBoundsDidChange:)
                                                 name:NSViewBoundsDidChangeNotification
                                               object:_hostView];
    [self layoutNativeSubviews];
    dispatch_async(dispatch_get_main_queue(), ^{
        [self focusControlsWebViewIfNeeded];
    });

    [self startMpvWithSource:sourceUrl
                 headerLines:headerLines
                playWhenReady:playWhenReady
             initialPositionMs:initialPositionMs];
    _timer = [NSTimer scheduledTimerWithTimeInterval:0.5
                                             target:self
                                           selector:@selector(syncControls)
                                           userInfo:nil
                                            repeats:YES];
    return self;
}

- (void)focusControlsWebViewIfNeeded {
    if (_didFocusControlsWebView || !_webView || !_webView.window) {
        return;
    }
    _didFocusControlsWebView = YES;
    [_webView.window makeFirstResponder:_webView];
}

- (void)layoutNativeSubviews {
    if (!_hostView) {
        return;
    }
    NSRect bounds = _hostView.bounds;
    if (bounds.size.width <= 0.0 || bounds.size.height <= 0.0) {
        return;
    }

    CGSize previousDrawableSize = _videoView ? [_videoView drawableSize] : CGSizeZero;
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    if (_videoView) {
        _videoView.frame = bounds;
        [_videoView setNeedsLayout:YES];
        [_videoView layoutSubtreeIfNeeded];
        [_videoView updateMetalLayerLayout];
    }
    if (_webView) {
        _webView.frame = bounds;
        [_webView setNeedsLayout:YES];
        [_webView layoutSubtreeIfNeeded];
    }
    [CATransaction commit];

    CGSize currentDrawableSize = _videoView ? [_videoView drawableSize] : CGSizeZero;
    NSString *resizeKey = [NSString stringWithFormat:
        @"%@:%@:%@:%@",
        diagnosticRect(bounds),
        _videoView ? diagnosticRect(_videoView.frame) : @"none",
        _webView ? diagnosticRect(_webView.frame) : @"none",
        diagnosticSize(currentDrawableSize)
    ];
    if (!_lastResizeDiagnosticKey || ![_lastResizeDiagnosticKey isEqualToString:resizeKey]) {
        _lastResizeDiagnosticKey = resizeKey;
        NSLog(
            @"[NuvioDesktopPlayer] resize: layout host=%@ video=%@ web=%@ drawableOld=%@ drawableNow=%@",
            diagnosticRect(bounds),
            _videoView ? diagnosticRect(_videoView.frame) : @"none",
            _webView ? diagnosticRect(_webView.frame) : @"none",
            diagnosticSize(previousDrawableSize),
            diagnosticSize(currentDrawableSize)
        );
        if (_mpv) {
            [self schedulePostResizeRefreshWithReason:@"layout"];
        }
    }
}

- (void)hostViewFrameDidChange:(NSNotification *)notification {
    [self layoutNativeSubviews];
}

- (void)hostViewBoundsDidChange:(NSNotification *)notification {
    [self layoutNativeSubviews];
}

- (void)schedulePostResizeRefreshWithReason:(NSString *)reason {
    if (!_mpv || !_videoView) {
        return;
    }
    [_resizeSettleTimer invalidate];
    _resizeSettleTimer = [NSTimer scheduledTimerWithTimeInterval:0.20
                                                          target:self
                                                        selector:@selector(handleResizeSettleTimer:)
                                                        userInfo:reason ?: @"unknown"
                                                         repeats:NO];
}

- (void)handleResizeSettleTimer:(NSTimer *)timer {
    _resizeSettleTimer = nil;
    if (!_mpv || !_videoView) {
        return;
    }
    NSString *reason = [NSString stringWithFormat:@"resize-settled/%@", timer.userInfo ?: @"unknown"];
    [self configureHdrForCurrentScreenWithReason:reason force:YES];
    [self logResizeStateWithReason:@"settled"];
}

- (void)startMpvWithSource:(NSString *)sourceUrl
               headerLines:(NSArray<NSString *> *)headerLines
              playWhenReady:(BOOL)playWhenReady
           initialPositionMs:(long long)initialPositionMs {
    _mpv = mpv_create();
    if (!_mpv) {
        @throw [NSException exceptionWithName:@"PlayerBridgeError"
                                       reason:@"mpv_create failed"
                                     userInfo:nil];
    }
    _initialStartSeconds = initialPositionMs > 0 ? (double)initialPositionMs / 1000.0 : 0.0;

    setMpvOptionString(_mpv, "config", "no");
    setMpvOptionString(_mpv, "osc", "no");
    setMpvOptionString(_mpv, "input-default-bindings", "yes");
    setMpvOptionString(_mpv, "input-vo-keyboard", "no");
    setMpvOptionString(_mpv, "keep-open", "yes");
    setMpvOptionString(_mpv, "vo", "gpu-next");
    setMpvOptionString(_mpv, "gpu-api", "vulkan");
    setMpvOptionString(_mpv, "gpu-context", "moltenvk");
    setMpvOptionString(_mpv, "msg-level", "ffmpeg/video=warn,vd=warn,vo=info,gpu=warn,vulkan=warn");
    setMpvOptionString(_mpv, "hwdec", "videotoolbox");
    setMpvOptionString(_mpv, "hwdec-codecs", "all");
    setMpvOptionString(_mpv, "vd-lavc-software-fallback", "no");
    setMpvOptionString(_mpv, "vd-lavc-threads", "4");
    setMpvOptionString(_mpv, "target-colorspace-hint", "yes");
    setMpvOptionString(_mpv, "target-colorspace-hint-mode", "source");
    setMpvOptionString(_mpv, "target-colorspace-hint-strict", "no");
    setMpvOptionString(_mpv, "tone-mapping", "auto");
    setMpvOptionString(_mpv, "hdr-compute-peak", "no");
    setMpvOptionString(_mpv, "dither-depth", "auto");
    setMpvOptionString(_mpv, "deband", "yes");
    setMpvOptionString(_mpv, "scale", "spline36");
    setMpvOptionString(_mpv, "cscale", "spline36");
    setMpvOptionString(_mpv, "vulkan-swap-mode", "fifo");
    setMpvOptionString(_mpv, "vulkan-queue-count", "1");
    setMpvOptionString(_mpv, "vulkan-async-compute", "no");
    setMpvOptionString(_mpv, "vulkan-async-transfer", "no");
    setMpvOptionString(_mpv, "demuxer-max-bytes", "64MiB");
    setMpvOptionString(_mpv, "demuxer-max-back-bytes", "16MiB");
    setMpvOptionString(_mpv, "demuxer-seekable-cache", "no");
    setMpvOptionString(_mpv, "cache-secs", "30");
    setMpvOptionString(_mpv, "hr-seek", "no");
    NSLog(
        @"[NuvioDesktopPlayer] render: MPVKit gpu-next vulkan/moltenvk wid=%lld hwdec=videotoolbox softwareFallback=no layerFormat=rgba16Float",
        (long long)[_videoView wid]
    );
    int64_t wid = [_videoView wid];
    int widResult = mpv_set_option(_mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (widResult < 0) {
        NSLog(@"[NuvioDesktopPlayer] mpv option failed wid error=%s", mpv_error_string(widResult));
    }

    int logResult = mpv_request_log_messages(_mpv, "info");
    if (logResult < 0) {
        NSLog(@"[NuvioDesktopPlayer] mpv log request failed error=%s", mpv_error_string(logResult));
    }
    mpv_set_wakeup_callback(_mpv, mpvWakeupCallback, (__bridge void *)self);

    if (headerLines.count > 0) {
        NSString *headers = [headerLines componentsJoinedByString:@","];
        setMpvOptionString(_mpv, "http-header-fields", headers.UTF8String);
    }

    int initResult = mpv_initialize(_mpv);
    if (initResult < 0) {
        NSString *reason = [NSString stringWithFormat:@"mpv_initialize failed: %s", mpv_error_string(initResult)];
        @throw [NSException exceptionWithName:@"PlayerBridgeError" reason:reason userInfo:nil];
    }

    std::vector<const char *> command = {"loadfile", sourceUrl.UTF8String};
    std::string loadOptions;
    if (initialPositionMs > 0) {
        char startBuffer[64];
        snprintf(startBuffer, sizeof(startBuffer), "start=%.3f", (double)initialPositionMs / 1000.0);
        loadOptions = startBuffer;
        command.push_back("replace");
        command.push_back("-1");
        command.push_back(loadOptions.c_str());
        NSLog(@"[NuvioDesktopPlayer] loadfile start=%.3fs mode=keyframe", (double)initialPositionMs / 1000.0);
    }
    command.push_back(NULL);
    int commandResult = mpv_command(_mpv, command.data());
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
    [self layoutNativeSubviews];
    [self focusControlsWebViewIfNeeded];
    double duration = [self doubleProperty:"duration" fallback:0.0];
    double position = [self doubleProperty:"time-pos" fallback:0.0];
    BOOL paused = [self isPaused];
    BOOL loading = [self isLoading];
    NSString *audioTracks = [self audioTracksJson] ?: @"[]";
    NSString *subtitleTracks = [self subtitleTracksJson] ?: @"[]";
    [self scheduleMpvEventDrain];
    [self configureHdrForCurrentScreenWithReason:@"sync" force:NO];
    [self logHdrTargetIfNeeded];
    [self logHwdecIfNeeded];
    NSString *script = [NSString stringWithFormat:
        @"window.playerUpdate({duration:%0.3f,position:%0.3f,paused:%@,loading:%@,audioTracks:%@,subtitleTracks:%@})",
        duration,
        position,
        paused ? @"true" : @"false",
        loading ? @"true" : @"false",
        audioTracks,
        subtitleTracks];
    [_webView evaluateJavaScript:script completionHandler:nil];
}

- (void)configureHdrForCurrentScreenIfNeeded {
    [self configureHdrForCurrentScreenWithReason:@"legacy" force:NO];
}

- (void)configureHdrForCurrentScreenWithReason:(NSString *)reason force:(BOOL)force {
    if (!_mpv || !_videoView) {
        return;
    }
    NSString *gamma = [[self stringProperty:"video-params/gamma" fallback:@""] lowercaseString];
    NSString *primaries = [[self stringProperty:"video-params/primaries" fallback:@""] lowercaseString];
    if (gamma.length == 0 && primaries.length == 0) {
        return;
    }

    BOOL isPq = [gamma containsString:@"pq"]
        || [gamma containsString:@"2084"]
        || [gamma containsString:@"st2084"];
    BOOL isHlg = [gamma containsString:@"hlg"];
    double edrMax = [_videoView edrMax];
    double screenPeak = [_videoView hdrTargetPeakNits];
    BOOL hdrDetected = (isPq || isHlg) && edrMax > 1.0;
    NSString *targetPrimaries = hdrDetected && primaries.length > 0 ? primaries : @"auto";
    NSString *stateKey = [NSString stringWithFormat:
        @"%@:%@:%@:%0.2f:%0.0f",
        hdrDetected ? @"native-metal-edr" : @"sdr",
        gamma ?: @"",
        targetPrimaries,
        edrMax,
        screenPeak
    ];
    if (!force && _lastConfiguredHdrKey && [_lastConfiguredHdrKey isEqualToString:stateKey]) {
        return;
    }
    _lastConfiguredHdrKey = stateKey;

    [_videoView configureExtendedDynamicRange:hdrDetected primaries:targetPrimaries];
    [self setStringProperty:"target-colorspace-hint" value:@"yes"];
    [self setStringProperty:"target-colorspace-hint-mode" value:hdrDetected ? @"source" : @"target"];
    [self setStringProperty:"target-colorspace-hint-strict" value:@"no"];
    [self setStringProperty:"tone-mapping" value:@"auto"];
    [self setStringProperty:"hdr-compute-peak" value:@"no"];
    [self setStringProperty:"target-prim" value:hdrDetected ? @"bt.2020" : @"auto"];
    [self setStringProperty:"target-trc" value:isPq ? @"pq" : (isHlg ? @"hlg" : @"auto")];
    [self setStringProperty:"target-peak" value:@"auto"];

    if (hdrDetected) {
        NSString *targetGamma = [[self stringProperty:"video-target-params/gamma" fallback:@""] lowercaseString];
        NSString *targetPrimariesLog = [[self stringProperty:"video-target-params/primaries" fallback:@""] lowercaseString];
        NSString *targetSigPeak = [self stringProperty:"video-target-params/sig-peak" fallback:@""];
        NSLog(
            @"[NuvioDesktopPlayer] macos_edr: HDR enabled via MPVKit Metal reason=%@%@ primaries=%@ gamma=%@ edrMax=%.2f screenPeak=%.0f metadataPeak=%.0f targetPeak=auto toneMapping=auto hdrComputePeak=no hint=source targetPrimaries=%@ targetGamma=%@ targetSigPeak=%@",
            reason ?: @"unknown",
            force ? @" force=true" : @"",
            targetPrimaries,
            gamma.length > 0 ? gamma : @"unknown",
            edrMax,
            screenPeak,
            hdrMetadataMaxNits(),
            targetPrimariesLog.length > 0 ? targetPrimariesLog : @"unknown",
            targetGamma.length > 0 ? targetGamma : @"unknown",
            targetSigPeak.length > 0 ? targetSigPeak : @"unknown"
        );
    } else {
        NSString *sdrReason = isHlg
            ? @"display-edr-unavailable-for-hlg"
            : (isPq ? @"display-edr-unavailable" : @"video-sdr");
        NSLog(
            @"[NuvioDesktopPlayer] macos_edr: SDR mode configureReason=%@%@ primaries=%@ gamma=%@ edrMax=%.2f screenPeak=%.0f reason=%@",
            reason ?: @"unknown",
            force ? @" force=true" : @"",
            primaries.length > 0 ? primaries : @"unknown",
            gamma.length > 0 ? gamma : @"unknown",
            edrMax,
            screenPeak,
            sdrReason
        );
    }
}

- (void)logResizeStateWithReason:(NSString *)reason {
    if (!_mpv || !_videoView) {
        return;
    }

    NSString *sourcePrimaries = [[self stringProperty:"video-params/primaries" fallback:@""] lowercaseString];
    NSString *sourceGamma = [[self stringProperty:"video-params/gamma" fallback:@""] lowercaseString];
    NSString *sourceFormat = [self stringProperty:"video-params/pixelformat" fallback:@""];
    NSString *targetPrimaries = [[self stringProperty:"video-target-params/primaries" fallback:@""] lowercaseString];
    NSString *targetGamma = [[self stringProperty:"video-target-params/gamma" fallback:@""] lowercaseString];
    NSString *targetSigPeak = [self stringProperty:"video-target-params/sig-peak" fallback:@""];
    NSString *targetFormat = [self stringProperty:"video-target-params/pixelformat" fallback:@""];
    NSString *targetHint = [self stringProperty:"target-colorspace-hint" fallback:@""];
    NSString *targetHintMode = [self stringProperty:"target-colorspace-hint-mode" fallback:@""];
    NSString *targetPrim = [self stringProperty:"target-prim" fallback:@""];
    NSString *targetTrc = [self stringProperty:"target-trc" fallback:@""];
    NSString *targetPeak = [self stringProperty:"target-peak" fallback:@""];
    NSString *toneMapping = [self stringProperty:"tone-mapping" fallback:@""];
    NSString *hdrComputePeak = [self stringProperty:"hdr-compute-peak" fallback:@""];
    NSString *hwdec = [self stringProperty:"hwdec-current" fallback:@""];
    NSString *hwPixelformat = [self stringProperty:"video-params/hw-pixelformat" fallback:@""];

    NSLog(
        @"[NuvioDesktopPlayer] resize: state reason=%@ host=%@ web=%@ video={%@} source=%@/%@/%@ target=%@/%@ sigPeak=%@ format=%@ hint=%@/%@ mpvTarget=%@/%@ peak=%@ tone=%@ hdrComputePeak=%@ hwdec=%@ hwPixfmt=%@",
        reason ?: @"unknown",
        _hostView ? diagnosticRect(_hostView.bounds) : @"none",
        _webView ? diagnosticRect(_webView.frame) : @"none",
        [_videoView diagnosticSummary],
        sourcePrimaries.length > 0 ? sourcePrimaries : @"unknown",
        sourceGamma.length > 0 ? sourceGamma : @"unknown",
        sourceFormat.length > 0 ? sourceFormat : @"unknown",
        targetPrimaries.length > 0 ? targetPrimaries : @"unknown",
        targetGamma.length > 0 ? targetGamma : @"unknown",
        targetSigPeak.length > 0 ? targetSigPeak : @"unknown",
        targetFormat.length > 0 ? targetFormat : @"unknown",
        targetHint.length > 0 ? targetHint : @"unknown",
        targetHintMode.length > 0 ? targetHintMode : @"unknown",
        targetPrim.length > 0 ? targetPrim : @"unknown",
        targetTrc.length > 0 ? targetTrc : @"unknown",
        targetPeak.length > 0 ? targetPeak : @"unknown",
        toneMapping.length > 0 ? toneMapping : @"unknown",
        hdrComputePeak.length > 0 ? hdrComputePeak : @"unknown",
        hwdec.length > 0 ? hwdec : @"unknown",
        hwPixelformat.length > 0 ? hwPixelformat : @"unknown"
    );
}

- (void)logHdrTargetIfNeeded {
    if (!_mpv) {
        return;
    }
    NSString *sourceGamma = [[self stringProperty:"video-params/gamma" fallback:@""] lowercaseString];
    BOOL isHdr = [sourceGamma containsString:@"pq"]
        || [sourceGamma containsString:@"2084"]
        || [sourceGamma containsString:@"st2084"]
        || [sourceGamma containsString:@"hlg"];
    if (!isHdr) {
        return;
    }

    NSString *targetPrimaries = [[self stringProperty:"video-target-params/primaries" fallback:@""] lowercaseString];
    NSString *targetGamma = [[self stringProperty:"video-target-params/gamma" fallback:@""] lowercaseString];
    NSString *targetSigPeak = [self stringProperty:"video-target-params/sig-peak" fallback:@""];
    NSString *targetFormat = [self stringProperty:"video-target-params/pixelformat" fallback:@""];
    if (targetPrimaries.length == 0 && targetGamma.length == 0 && targetSigPeak.length == 0) {
        return;
    }

    NSString *diagnosticKey = [NSString stringWithFormat:
        @"%@:%@:%@:%@",
        targetPrimaries,
        targetGamma,
        targetSigPeak,
        targetFormat
    ];
    if (_lastLoggedHdrTargetKey && [_lastLoggedHdrTargetKey isEqualToString:diagnosticKey]) {
        return;
    }
    _lastLoggedHdrTargetKey = diagnosticKey;

    NSLog(
        @"[NuvioDesktopPlayer] macos_edr: mpv target primaries=%@ gamma=%@ sigPeak=%@ format=%@",
        targetPrimaries.length > 0 ? targetPrimaries : @"unknown",
        targetGamma.length > 0 ? targetGamma : @"unknown",
        targetSigPeak.length > 0 ? targetSigPeak : @"unknown",
        targetFormat.length > 0 ? targetFormat : @"unknown"
    );
}

- (void)logHwdecIfNeeded {
    if (!_mpv) {
        return;
    }
    NSString *hwdec = [self stringProperty:"hwdec-current" fallback:@""];
    if (hwdec.length == 0) {
        return;
    }
    NSString *requestedHwdec = [self stringProperty:"hwdec" fallback:@"unknown"];
    NSString *directRendering = [self stringProperty:"vd-lavc-dr" fallback:@"unknown"];
    NSString *codec = [self stringProperty:"video-codec" fallback:@"unknown"];
    NSString *pixelformat = [self stringProperty:"video-params/pixelformat" fallback:@"n/a"];
    NSString *hwPixelformat = [self stringProperty:"video-params/hw-pixelformat" fallback:@"n/a"];
    long long width = [self int64Property:"width" fallback:0];
    long long height = [self int64Property:"height" fallback:0];
    NSString *diagnosticKey = [NSString stringWithFormat:
        @"%@:%@:%@:%@:%@:%lldx%lld",
        hwdec,
        requestedHwdec,
        directRendering,
        codec,
        pixelformat,
        width,
        height
    ];
    if (_lastLoggedHwdec && [_lastLoggedHwdec isEqualToString:diagnosticKey]) {
        return;
    }
    _lastLoggedHwdec = diagnosticKey;
    double position = [self doubleProperty:"time-pos" fallback:0.0];
    double cacheRaw = [self doubleProperty:"demuxer-cache-time" fallback:0.0];
    NSLog(
        @"[NuvioDesktopPlayer] hwdec-current=%@ requested=%@ directRendering=%@ codec=%@ size=%lldx%lld pixfmt=%@ hwPixfmt=%@ pos=%.1fs cacheRaw=%.1fs cacheAhead=%.1fs",
        hwdec,
        requestedHwdec,
        directRendering,
        codec,
        width,
        height,
        pixelformat.length > 0 ? pixelformat : @"n/a",
        hwPixelformat.length > 0 ? hwPixelformat : @"n/a",
        position,
        cacheRaw,
        [self cacheAheadSeconds]
    );

    if (!_didLogSoftwareDecodeWarning && [hwdec isEqualToString:@"no"] && [requestedHwdec containsString:@"videotoolbox"]) {
        _didLogSoftwareDecodeWarning = YES;
        NSLog(
            @"[NuvioDesktopPlayer] decode: hardware decode fell back to software requested=%@ pixfmt=%@ cacheAhead=%.1fs",
            requestedHwdec,
            pixelformat.length > 0 ? pixelformat : @"n/a",
            [self cacheAheadSeconds]
        );
    }
}

- (void)scheduleMpvEventDrain {
    if (_eventDrainScheduled.exchange(true)) {
        return;
    }
    dispatch_async(_mpvEventQueue, ^{
        [self drainMpvEvents];
    });
}

- (void)drainMpvEvents {
    @autoreleasepool {
        mpv_handle *mpv = _mpv;
        if (!mpv) {
            _eventDrainScheduled.store(false);
            return;
        }

        while (true) {
            mpv_event *event = mpv_wait_event(mpv, 0.0);
            if (!event || event->event_id == MPV_EVENT_NONE) {
                break;
            }
            if (event->event_id == MPV_EVENT_SHUTDOWN) {
                break;
            }
            if (event->event_id == MPV_EVENT_LOG_MESSAGE && event->data) {
                mpv_event_log_message *message = (mpv_event_log_message *)event->data;
                [self logMpvMessage:message];
            }
        }

        _eventDrainScheduled.store(false);
    }
}

- (void)logMpvMessage:(mpv_event_log_message *)message {
    if (!message || !message->text) {
        return;
    }
    NSString *prefix = message->prefix ? [NSString stringWithUTF8String:message->prefix] : @"unknown";
    NSString *level = message->level ? [NSString stringWithUTF8String:message->level] : @"unknown";
    NSString *rawText = [NSString stringWithUTF8String:message->text] ?: @"";
    NSString *text = [rawText stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    if (text.length == 0) {
        return;
    }

    NSString *lowerPrefix = [prefix.lowercaseString copy];
    NSString *lowerText = [text.lowercaseString copy];
    BOOL videoPipelinePrefix = [lowerPrefix containsString:@"vd"]
        || [lowerPrefix containsString:@"vo"]
        || [lowerPrefix containsString:@"libmpv_render"]
        || [lowerPrefix containsString:@"gpu"];
    BOOL decoderErrorPrefix = [lowerPrefix containsString:@"ffmpeg/video"];
    BOOL relevantText = [lowerText containsString:@"hwdec"]
        || [lowerText containsString:@"videotoolbox"]
        || [lowerText containsString:@"hardware"]
        || [lowerText containsString:@"software"]
        || [lowerText containsString:@"fallback"]
        || [lowerText containsString:@"decoder"]
        || [lowerText containsString:@"format"]
        || [lowerText containsString:@"p010"]
        || [lowerText containsString:@"yuv420p10"]
        || [lowerText containsString:@"dovi"]
        || [lowerText containsString:@"rpu"]
        || [lowerText containsString:@"poc"]
        || [lowerText containsString:@"moltenvk hdr"]
        || [lowerText containsString:@"target colorspace"];
    if (!(videoPipelinePrefix || decoderErrorPrefix) || !relevantText) {
        return;
    }

    NSString *safeText = redactUrlsInText(text);
    NSString *diagnosticCategory = nil;
    if ([lowerText containsString:@"dovi"] || [lowerText containsString:@"rpu"]) {
        diagnosticCategory = @"dovi-rpu";
    } else if ([lowerText containsString:@"could not find ref with poc"]) {
        diagnosticCategory = @"hevc-missing-reference";
    } else if ([lowerText containsString:@"output image buffer is null"]) {
        diagnosticCategory = @"videotoolbox-null-buffer";
    } else if ([lowerText containsString:@"hardware accelerator failed"]) {
        diagnosticCategory = @"videotoolbox-hardware-decode-failed";
    } else if ([lowerText containsString:@"hardware decoding"]) {
        diagnosticCategory = @"hardware-decode-warning";
    }
    NSString *diagnosticKey = diagnosticCategory
        ? [NSString stringWithFormat:@"%@:%@", prefix, diagnosticCategory]
        : [NSString stringWithFormat:@"%@:%@:%@", prefix, level, safeText];
    @synchronized (_loggedMpvDiagnostics) {
        if ([_loggedMpvDiagnostics containsObject:diagnosticKey]) {
            return;
        }
        if (_loggedMpvDiagnostics.count > 128) {
            [_loggedMpvDiagnostics removeAllObjects];
        }
        [_loggedMpvDiagnostics addObject:diagnosticKey];
    }

    NSLog(@"[NuvioDesktopPlayer][mpv:%@] %@: %@", prefix, level, safeText);
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
    if (!controlsJson) {
        NSLog(@"[NuvioDesktopControls][native] updateControls ignored nil controls JSON");
        return;
    }
    _pendingControlsJson = [controlsJson copy];
    NSLog(
        @"[NuvioDesktopControls][native] updateControls queued ready=%@ jsonBytes=%lu",
        _controlsWebReady ? @"true" : @"false",
        (unsigned long)[_pendingControlsJson lengthOfBytesUsingEncoding:NSUTF8StringEncoding]
    );
    [self flushPendingControlsJsonIfReady:@"updateControls"];
}

- (void)flushPendingControlsJsonIfReady:(NSString *)reason {
    if (!_webView || !_pendingControlsJson) {
        return;
    }
    if (!_controlsWebReady) {
        NSLog(
            @"[NuvioDesktopControls][native] controls flush deferred reason=%@ ready=false jsonBytes=%lu",
            reason ?: @"unknown",
            (unsigned long)[_pendingControlsJson lengthOfBytesUsingEncoding:NSUTF8StringEncoding]
        );
        return;
    }
    NSString *controlsJson = [_pendingControlsJson copy];
    NSString *jsonString = javaScriptStringLiteral(controlsJson);
    NSString *script = [NSString stringWithFormat:
        @"(function(){if(!window.playerControls)return 'missing';window.playerControls(JSON.parse(%@));return 'applied';})()",
        jsonString];
    [_webView evaluateJavaScript:script completionHandler:^(id _Nullable jsResult, NSError * _Nullable error) {
        if (error) {
            NSDictionary *userInfo = error.userInfo ?: @{};
            id message = userInfo[@"WKJavaScriptExceptionMessage"] ?: error.localizedDescription;
            id line = userInfo[@"WKJavaScriptExceptionLineNumber"] ?: @"?";
            id column = userInfo[@"WKJavaScriptExceptionColumnNumber"] ?: @"?";
            NSLog(
                @"[NuvioDesktopControls][native] updateControls JS error: %@ line=%@ column=%@",
                message,
                line,
                column
            );
            return;
        }
        NSString *resultText = [jsResult isKindOfClass:[NSString class]] ? (NSString *)jsResult : @"unknown";
        NSLog(
            @"[NuvioDesktopControls][native] controls flush reason=%@ result=%@ jsonBytes=%lu",
            reason ?: @"unknown",
            resultText,
            (unsigned long)[controlsJson lengthOfBytesUsingEncoding:NSUTF8StringEncoding]
        );
        if ([resultText isEqualToString:@"missing"]) {
            _controlsWebReady = NO;
            return;
        }
        if (_pendingControlsJson && [_pendingControlsJson isEqualToString:controlsJson]) {
            _pendingControlsJson = nil;
        }
    }];
}

- (void)shutdown {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    [_timer invalidate];
    _timer = nil;
    [_resizeSettleTimer invalidate];
    _resizeSettleTimer = nil;
    _controlsWebReady = NO;
    _pendingControlsJson = nil;
    if (_mpv) {
        mpv_set_wakeup_callback(_mpv, nullptr, nullptr);
    }
    if (_mpvEventQueue) {
        dispatch_sync(_mpvEventQueue, ^{});
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
    NSLog(@"[NuvioDesktopPlayer] seek absolute+keyframes target=%.3fs", (double)positionMs / 1000.0);
    const char *command[] = {"seek", seconds.c_str(), "absolute+keyframes", NULL};
    mpv_command(_mpv, command);
}

- (void)seekByMilliseconds:(long long)offsetMs {
    if (!_mpv) return;
    std::string seconds = std::to_string((double)offsetMs / 1000.0);
    NSLog(@"[NuvioDesktopPlayer] seek relative+keyframes offset=%.3fs", (double)offsetMs / 1000.0);
    const char *command[] = {"seek", seconds.c_str(), "relative+keyframes", NULL};
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

- (double)rawPositionSeconds {
    double position = [self doubleProperty:"time-pos" fallback:0.0];
    return std::isfinite(position) ? fmax(position, 0.0) : 0.0;
}

- (double)effectiveCachePositionSeconds {
    double position = [self rawPositionSeconds];
    if (_initialStartSeconds > 0.0 && position + 5.0 < _initialStartSeconds) {
        return _initialStartSeconds;
    }
    return position;
}

- (double)cacheAheadSeconds {
    double effectivePosition = [self effectiveCachePositionSeconds];
    double cacheTime = [self doubleProperty:"demuxer-cache-time" fallback:0.0];
    if (std::isfinite(cacheTime) && cacheTime > 0.0) {
        if (cacheTime >= effectivePosition - 5.0) {
            return fmax(cacheTime - effectivePosition, 0.0);
        }
        return cacheTime;
    }

    double cacheDuration = [self doubleProperty:"demuxer-cache-duration" fallback:0.0];
    if (std::isfinite(cacheDuration) && cacheDuration > 0.0) {
        return cacheDuration;
    }

    return 0.0;
}

- (long long)bufferedPositionMs {
    double bufferedPosition = [self rawPositionSeconds] + [self cacheAheadSeconds];
    return (long long)llround(fmax(bufferedPosition, 0.0) * 1000.0);
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

    id rawValue = message[@"value"];
    NSNumber *value = [rawValue isKindOfClass:[NSNumber class]] ? rawValue : nil;
    if ([type isEqualToString:@"controlsReady"]) {
        _controlsWebReady = YES;
        NSLog(
            @"[NuvioDesktopControls][native] controlsReady pending=%@",
            _pendingControlsJson ? @"true" : @"false"
        );
        [self flushPendingControlsJsonIfReady:@"controlsReady"];
        [self syncControls];
        return;
    }
    if ([type isEqualToString:@"diagnostic"]) {
        id rawText = message[@"message"];
        if ([rawText isKindOfClass:[NSString class]]) {
            NSLog(@"[NuvioDesktopControls][html] %@", limitDiagnosticText((NSString *)rawText));
        }
        return;
    }
    if (isControlsDiagnosticMessage(type)) {
        NSLog(@"[NuvioDesktopControls][native] script event type=%@ value=%@", type, value ?: @0);
    }
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
    jsize length = env->GetStringLength(value);
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (!chars) {
        return std::string();
    }
    NSString *string = [NSString stringWithCharacters:(const unichar *)chars length:(NSUInteger)length];
    env->ReleaseStringChars(value, chars);

    NSData *data = [string dataUsingEncoding:NSUTF8StringEncoding];
    if (!data) {
        return std::string();
    }
    std::string result((const char *)data.bytes, data.length);
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
    jlong initialPositionMs,
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
                initialPositionMs:initialPositionMs
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
