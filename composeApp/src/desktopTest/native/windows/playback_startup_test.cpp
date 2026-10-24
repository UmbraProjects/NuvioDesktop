// Exercise the production native transition code against an in-memory libmpv property API.
#define NUVIO_PLAYBACK_STARTUP_TEST
#include "../../../desktopMain/native/windows/player_bridge.cpp"
#include <cassert>
#include <iostream>

namespace {
struct PlaybackStartupTest {
    inline static std::map<std::string, std::string> properties;
    inline static std::vector<std::string> writes;
    inline static std::vector<std::vector<std::string>> commands;

    static int setString(mpv_handle *, const char *key, const char *value) {
        properties[key] = value;
        writes.emplace_back(key);
        return 0;
    }
    static int get(mpv_handle *, const char *key, mpv_format format, void *out) {
        auto it = properties.find(key);
        if (it == properties.end()) return -10;
        if (format == MPV_FORMAT_STRING) *static_cast<char **>(out) = _strdup(it->second.c_str());
        else if (format == MPV_FORMAT_DOUBLE) *static_cast<double *>(out) = std::stod(it->second);
        else if (format == MPV_FORMAT_FLAG) *static_cast<int *>(out) = it->second == "yes";
        else if (format == MPV_FORMAT_INT64) *static_cast<int64_t *>(out) = std::stoll(it->second);
        else return -9;
        return 0;
    }
    static int set(mpv_handle *, const char *key, mpv_format format, void *value) {
        if (format == MPV_FORMAT_FLAG) return setString(nullptr, key, *static_cast<int *>(value) ? "yes" : "no");
        if (format == MPV_FORMAT_DOUBLE) return setString(nullptr, key, std::to_string(*static_cast<double *>(value)).c_str());
        return -9;
    }
    static std::shared_ptr<WindowsMpvWebPlayer> player() {
        properties = {{"speed","1"},{"pause","no"},{"hwdec","d3d11va"},{"vf",""},
            {"mc","0.1"},{"autosync","0"},{"seeking","no"},{"vo-configured","yes"},
            {"video-params/w","1920"},{"video-params/h","1080"},{"container-fps","24"},{"duration","1500"}};
        writes.clear();
        commands.clear();
        auto &api = mpvApi();
        api.getProperty = get;
        api.setPropertyString = setString;
        api.setProperty = set;
        api.freeValue = [](void *value) { free(value); };
        api.errorString = [](int) -> const char * { return "test error"; };
        api.requestLogMessages = [](mpv_handle *, const char *) { return 0; };
        api.wakeup = [](mpv_handle *) {};
        api.command = [](mpv_handle *, const char **args) {
            std::vector<std::string> command;
            for (int i = 0; args[i]; ++i) command.emplace_back(args[i]);
            commands.push_back(command);
            return 0;
        };
        auto p = std::make_shared<WindowsMpvWebPlayer>();
        p->mpv = reinterpret_cast<mpv_handle *>(1);
        p->playbackSessionId = "startup-test";
        p->playbackLogPath = L"playback-startup-test.log";
        p->startupStartedAt = std::chrono::steady_clock::now();
        return p;
    }
    static void run() {
        // Full mpv configuration retains its own decoder on any speed change.
        {
            auto p = player(); properties["hwdec"] = "no";
            p->setSpeed(2.0);
            assert(properties["hwdec"] == "no");
            assert(properties["mc"] == "0.1");
        }
        // Requested SVP at 2x bypasses the entire profile, without installing a graph.
        {
            auto p = player(); properties["speed"] = "2";
            p->setMpvPropertyString("vf", "vapoursynth=file=test.vpy");
            assert(properties["vf"].empty());
            assert(properties["hwdec"] == "d3d11va");
            assert(properties["autosync"] == "0");
            assert(!p->effectiveSvpActive && !p->svpPrerollPending);
        }
        // Bypass removes interpolation but retains denoise, which still requires copy-back.
        {
            auto p = player(); properties["speed"] = "2";
            p->setMpvPropertyString("vf", "@HQ:lavfi=[hqdn3d=luma_spatial=5],vapoursynth=file=test.vpy");
            assert(properties["vf"] == "@HQ:lavfi=[hqdn3d=luma_spatial=5]");
            assert(properties["hwdec"] == "d3d11va-copy");
            assert(properties["mc"] == "0.1");
        }
        // An active graph is held intact during construction, then bypass restores all timing.
        {
            auto p = player();
            p->setMpvPropertyString("vf", "vapoursynth=file=test.vpy");
            assert(p->effectiveSvpActive && p->svpGraphInitInFlight);
            p->setSpeed(2.0);
            assert(p->svpPendingPipelineUpdate && !properties["vf"].empty());
            p->releaseSvpGraphInitLatch("test-filtered-output");
            assert(properties["vf"].empty());
            assert(properties["hwdec"] == "d3d11va");
            assert(properties["mc"] == "0.1" && properties["autosync"] == "0");
            assert(properties["vd-queue-enable"] == "no");
            // Restore hysteresis: 1.4x stays bypassed, 1.25x restores interpolation.
            p->setSpeed(1.4); assert(!p->effectiveSvpActive);
            p->setSpeed(1.25); assert(p->effectiveSvpActive);
        }
        // Play intent survives overlapping resume/SVP gates; an explicit pause cancels it.
        {
            auto p = player();
            p->initialResumeTransactionPending.store(true);
            p->svpPrerollPending = true;
            p->setPaused(false);
            assert(p->initialResumeShouldPlay.load() && p->svpPrerollResumeRequested);
            assert(properties["pause"] == "yes");
            p->setPaused(true);
            assert(!p->initialResumeShouldPlay.load() && !p->svpPrerollResumeRequested);
        }
        // Shader-only startup hands its original play intent over to a late SVP graph.
        {
            auto p = player();
            p->beginVideoProfile();
            assert(p->profileOwnsPause && p->profileResumeRequested);
            p->setMpvPropertyString("vf", "vapoursynth=file=test.vpy");
            assert(!p->profileOwnsPause && p->svpPrerollResumeRequested);
        }
        // Unresolved media and provider notice videos cannot initialize VapourSynth.
        {
            auto p = player(); properties["video-params/w"] = "0";
            p->setMpvPropertyString("vf", "vapoursynth=file=test.vpy");
            assert(!p->effectiveSvpActive && !p->deferredAnimeSvpFilter.empty());
            p->setSpeed(2.0);
            assert(p->deferredAnimeSvpFilter.empty() && properties["vf"].empty());
        }
        {
            auto p = player(); properties["duration"] = "120";
            p->setMpvPropertyString("vf", "vapoursynth=file=test.vpy");
            assert(!p->effectiveSvpActive && !p->deferredAnimeSvpFilter.empty());
        }
        // A resume finishing before shader setup retains the original play intent and stays paused.
        {
            auto p = player();
            p->initialResumeTransactionPending.store(true);
            p->initialResumeShouldPlay.store(true);
            properties["pause"] = "yes";
            p->beginVideoProfile();
            assert(p->profileOwnsPause && p->profileResumeRequested);
            p->initialResumeTransactionPending.store(false);
            p->setPaused(false);
            assert(properties["pause"] == "yes" && p->profileResumeRequested);
            p->setPaused(true);
            assert(!p->profileResumeRequested);
        }
        // Canonical numeric readback is not a reason to rebuild a profile.
        {
            auto p = player(); properties["gamma"] = "0.000000";
            p->setMpvPropertyString("gamma", "0");
            assert(writes.empty());
        }
        // A changed render-pass description with no measured samples is not readiness evidence.
        {
            mpv_node sample{}; sample.format = MPV_FORMAT_INT64; sample.u.int64 = 0;
            mpv_node_list sampleList{1, &sample, nullptr};
            mpv_node children[2]{};
            children[0].format = MPV_FORMAT_STRING; children[0].u.string = const_cast<char *>("shader");
            children[1].format = MPV_FORMAT_NODE_ARRAY; children[1].u.list = &sampleList;
            char *keys[] = {const_cast<char *>("desc"), const_cast<char *>("samples")};
            mpv_node_list passList{2, children, keys};
            mpv_node pass{}; pass.format = MPV_FORMAT_NODE_MAP; pass.u.list = &passList;
            assert(WindowsMpvWebPlayer::renderTimingSignature(pass).empty());
            sample.u.int64 = 1200;
            const auto first = WindowsMpvWebPlayer::renderTimingSignature(pass);
            assert(!first.empty());
            children[0].u.string = const_cast<char *>("renamed");
            assert(first == WindowsMpvWebPlayer::renderTimingSignature(pass));
            sample.u.int64 = 1400;
            assert(first != WindowsMpvWebPlayer::renderTimingSignature(pass));
        }
        // Real 1x traces: filtered output/profile settle without a further restart event.
        // Test both possible orders, including a user pause while setup is pending.
        for (bool svpFinishesFirst : {false, true}) {
            for (bool userPaused : {false, true}) {
                auto p = player();
                p->fileLoadedForCurrentSource = true;
                p->playbackRestartPendingForFile = true;
                p->initialResumeApplied = true;
                p->initialResumeTransactionPending.store(true);
                p->initialStartSeconds.store(29.305);
                p->initialResumeShouldPlay.store(true);
                p->svpPrerollPending = true;
                p->svpPrerollResumeRequested = true;
                p->svpFilteredOutputReady = true;
                properties["pause"] = "yes";
                properties["time-pos"] = "29.305";
                if (userPaused) p->setPaused(true);
                if (svpFinishesFirst) {
                    p->finishSvpPreroll("test-profile-ready");
                    p->publishPlaybackRestartIfReady();
                    assert(p->playbackRestartPendingForFile); // cannot consume the only notification
                    assert(properties["pause"] == "yes");
                }
                assert(p->tryCompleteInitialResume(false));
                assert(!p->initialResumeTransactionPending.load());
                if (!svpFinishesFirst) {
                    assert(properties["pause"] == "yes");
                    p->finishSvpPreroll("test-profile-ready");
                }
                p->publishPlaybackRestartIfReady();
                assert(!p->playbackRestartPendingForFile);
                assert(properties["pause"] == (userPaused ? "yes" : "no"));
                assert(commands.empty());
            }
        }
        // Neither a seek target alone nor EOF/unconfigured output is a settled resume.
        {
            auto p = player();
            p->fileLoadedForCurrentSource = true;
            p->initialResumeApplied = true;
            p->initialResumeTransactionPending.store(true);
            p->initialStartSeconds.store(30.0);
            properties["time-pos"] = "30";
            properties["seeking"] = "yes";
            assert(!p->tryCompleteInitialResume(false));
            assert(!p->tryCompleteInitialResume(true));
            properties["seeking"] = "no"; properties["eof-reached"] = "yes";
            assert(!p->tryCompleteInitialResume(false));
            properties["eof-reached"] = "no"; properties["vo-configured"] = "no";
            assert(!p->tryCompleteInitialResume(false));
            properties["vo-configured"] = "yes"; properties["time-pos"] = "10";
            assert(!p->tryCompleteInitialResume(false));
            assert(commands.empty()); // polling cannot repeatedly seek a remote source
            assert(p->initialResumeTransactionPending.load());
        }
        // Office took 13.7s to open: the five-second parameter timeout cannot consume its
        // saved percentage as zero before duration exists, even if video parameters appear.
        {
            auto p = player();
            p->initialStartProgressFraction = 0.0356;
            p->initialResumeTransactionPending.store(true);
            p->initialResumeWaitDeadline = std::chrono::steady_clock::now() - std::chrono::seconds(9);
            properties["duration"] = "0";
            p->tryApplyInitialResume("test-slow-open");
            assert(!p->initialResumeApplied && commands.empty());
            assert(p->initialStartProgressFraction == 0.0356);
            p->fileLoadedForCurrentSource = true;
            properties["duration"] = "1500";
            p->tryApplyInitialResume("test-file-loaded");
            assert(p->initialResumeApplied);
            assert(std::abs(p->initialStartSeconds.load() - 53.4) < 0.001);
            assert(commands.size() == 1 && commands[0][0] == "seek");
            assert(std::abs(std::stod(commands[0][1]) - 53.4) < 0.001);
            assert(commands[0][2] == "absolute+exact");
            p->tryApplyInitialResume("test-next-tick");
            assert(commands.size() == 1);
        }
        // A late SVP graph inherits play intent from the resume gate, not its forced pause.
        {
            auto p = player();
            p->initialResumeTransactionPending.store(true);
            p->initialResumeShouldPlay.store(true);
            properties["pause"] = "yes";
            p->beginSvpPrerollLocked();
            assert(p->svpPrerollResumeRequested);
        }
        // Profile rendering can finish after the resume event without losing the notification.
        {
            auto p = player();
            p->playbackRestartPendingForFile = true;
            p->startupPlaybackReady = true;
            p->profileRenderPending.store(true);
            p->publishPlaybackRestartIfReady();
            assert(p->playbackRestartPendingForFile);
            p->profileRenderPending.store(false);
            p->publishPlaybackRestartIfReady();
            assert(!p->playbackRestartPendingForFile);
        }
        // Restoring SVP after 2x playback must keep 78s, not rewind to the 39s launch resume.
        {
            auto p = player();
            p->fileLoadedForCurrentSource = true;
            p->initialStartSeconds.store(39.548);
            properties["time-pos"] = "78.412";
            p->beginSvpPrerollLocked();
            assert(p->ensureSvpPrerollStartPosition());
            assert(commands.empty());
            // If filter initialization drifts, rewind only to the transition's position.
            properties["time-pos"] = "80";
            assert(!p->ensureSvpPrerollStartPosition());
            assert(commands.size() == 1);
            assert(std::abs(std::stod(commands[0][1]) - 78.412) < 0.001);
            assert(std::abs(p->initialStartSeconds.load() - 39.548) < 0.001);
            properties["time-pos"] = "78.412";
            p->finishSvpPreroll("test-speed-restore");
            // A subsequent speed restore captures a new anchor and clears the old rewind latch.
            properties["time-pos"] = "120";
            p->beginSvpPrerollLocked();
            assert(!p->svpPrerollRewindPending);
            assert(p->ensureSvpPrerollStartPosition());
            assert(commands.size() == 1);
        }
        // A still-pending initial resume retains its saved target even on late SVP insertion.
        {
            auto p = player();
            p->fileLoadedForCurrentSource = true;
            p->initialResumeTransactionPending.store(true);
            p->initialStartSeconds.store(39.548);
            properties["time-pos"] = "0";
            p->beginSvpPrerollLocked();
            assert(!p->ensureSvpPrerollStartPosition());
            assert(commands.size() == 1);
            assert(std::abs(std::stod(commands[0][1]) - 39.548) < 0.001);
        }
        // Missing current position during a speed restore cannot fall back to the launch target.
        {
            auto p = player();
            p->fileLoadedForCurrentSource = true;
            p->initialStartSeconds.store(39.548);
            properties.erase("time-pos");
            p->beginSvpPrerollLocked();
            assert(!p->ensureSvpPrerollStartPosition());
            properties["time-pos"] = "78.412";
            assert(p->ensureSvpPrerollStartPosition());
            assert(commands.empty());
        }
        // Passthrough rejected by the output device (wasapi exclusive open fails): the audio
        // chain is rebuilt as PCM by clearing audio-spdif and reselecting the same track — in
        // that order, so the reselected decoder never sees the spdif list. One shot per load.
        {
            auto p = player();
            p->audioPassthroughRequested = true;
            properties["aid"] = "2";
            properties["audio-spdif"] = "ac3,dts,eac3";
            assert(p->tryRecoverFromPassthroughAoFailure("error", "ao", "Failed to initialize audio driver 'wasapi'"));
            assert(properties["audio-spdif"].empty());
            assert(writes.size() == 3);
            assert(writes[0] == "audio-spdif" && writes[1] == "aid" && writes[2] == "aid");
            assert(properties["aid"] == "2");
            assert(p->audioPassthroughFallbackApplied);
            // A second AO failure on the same load (PCM itself broken) must not cycle again.
            assert(!p->tryRecoverFromPassthroughAoFailure("error", "ao", "Failed to initialize audio driver 'wasapi'"));
            assert(writes.size() == 3);
        }
        // Without passthrough requested, an AO failure is mpv's own problem — no track cycling,
        // and the same for AO lines that are not the init failure.
        {
            auto p = player();
            properties["aid"] = "1";
            assert(!p->tryRecoverFromPassthroughAoFailure("error", "ao", "Failed to initialize audio driver 'wasapi'"));
            p->audioPassthroughRequested = true;
            assert(!p->tryRecoverFromPassthroughAoFailure("warn", "ao", "Failed to initialize audio driver 'wasapi'"));
            assert(!p->tryRecoverFromPassthroughAoFailure("error", "ao/wasapi", "Received failure from audio thread"));
            assert(writes.empty());
        }
        // No selected track (aid=no) still drops the spdif list so a later manual track pick
        // starts as PCM, but there is nothing to reselect.
        {
            auto p = player();
            p->audioPassthroughRequested = true;
            properties["aid"] = "no";
            assert(p->tryRecoverFromPassthroughAoFailure("fatal", "ao", "Failed to initialize audio driver 'wasapi'"));
            assert(writes.size() == 1 && writes[0] == "audio-spdif");
            assert(properties["aid"] == "no");
        }
        std::cout << "25 native playback startup scenarios passed\n";
    }
};
}

// Exercises the DualSense report decoder against synthetic reports.
//
// Byte offsets, the hat table, the inverted Y axes and the face-button bit order are the parts of
// controller support most likely to be silently wrong, and the only parts that can be checked
// without the hardware in hand — a mistake here shows up as "the pad works but up goes down", which
// is far cheaper to catch as an assertion than in the living room.
namespace {
using nuvio_gamepad_hid::PadState;

constexpr USHORT kUsbLength = nuvio_gamepad_hid::kUsbInputReportLength;
constexpr USHORT kBtLength = nuvio_gamepad_hid::kBluetoothInputReportLength;

// A centred, nothing-pressed report in each of the three layouts the decoder understands.
std::vector<unsigned char> usbReport() {
    std::vector<unsigned char> report(kUsbLength, 0);
    report[0] = 0x01;
    report[1] = report[2] = report[3] = report[4] = 128; // axes
    report[5] = report[6] = 0;                           // triggers
    report[8] = 0x08;                                    // hat centred, no face buttons
    return report;
}

std::vector<unsigned char> btFullReport() {
    std::vector<unsigned char> report(kBtLength, 0);
    report[0] = 0x31;
    report[2] = report[3] = report[4] = report[5] = 128;
    report[6] = report[7] = 0;
    report[9] = 0x08;
    return report;
}

std::vector<unsigned char> btSimpleReport() {
    std::vector<unsigned char> report(kBtLength, 0);
    report[0] = 0x01;
    report[1] = report[2] = report[3] = report[4] = 128;
    report[5] = 0x08;
    report[8] = report[9] = 0;
    return report;
}

using nuvio_gamepad_hid::PadModel;

PadState decode(
    const std::vector<unsigned char> &report,
    USHORT inputReportLength,
    PadModel model = PadModel::DualSense
) {
    PadState state;
    const bool ok = nuvio_gamepad_hid::decodeReport(
        report.data(), static_cast<DWORD>(report.size()), inputReportLength, model, state);
    assert(ok);
    return state;
}

void runDualSenseDecodeTests() {
    using namespace nuvio_gamepad_hid;

    // A resting pad must read as dead centre in every layout. If this drifts, the UI scrolls on its
    // own before the user has touched anything.
    for (auto &&[report, length] : {
             std::pair{usbReport(), kUsbLength},
             std::pair{btFullReport(), kBtLength},
             std::pair{btSimpleReport(), kBtLength},
         }) {
        const PadState state = decode(report, length);
        assert(state.buttons == 0);
        assert(state.leftTrigger == 0 && state.rightTrigger == 0);
        assert(std::abs(static_cast<int>(state.thumbLX)) <= 257);
        assert(std::abs(static_cast<int>(state.thumbLY)) <= 257);
        assert(std::abs(static_cast<int>(state.thumbRX)) <= 257);
        assert(std::abs(static_cast<int>(state.thumbRY)) <= 257);
    }

    // Face buttons. Cross must land on A and Circle on B, or "select" and "back" are swapped.
    {
        auto report = usbReport();
        report[8] = 0x08 | 0x20; // Cross
        assert(decode(report, kUsbLength).buttons == kBtnA);
        report[8] = 0x08 | 0x40; // Circle
        assert(decode(report, kUsbLength).buttons == kBtnB);
        report[8] = 0x08 | 0x10; // Square
        assert(decode(report, kUsbLength).buttons == kBtnX);
        report[8] = 0x08 | 0x80; // Triangle
        assert(decode(report, kUsbLength).buttons == kBtnY);
    }

    // Shoulders, Create/Options and the stick clicks.
    {
        auto report = usbReport();
        report[9] = 0x01; assert(decode(report, kUsbLength).buttons == kBtnLeftShoulder);
        report[9] = 0x02; assert(decode(report, kUsbLength).buttons == kBtnRightShoulder);
        report[9] = 0x10; assert(decode(report, kUsbLength).buttons == kBtnBack);   // Create
        report[9] = 0x20; assert(decode(report, kUsbLength).buttons == kBtnStart);  // Options
        report[9] = 0x40; assert(decode(report, kUsbLength).buttons == kBtnLeftThumb);
        report[9] = 0x80; assert(decode(report, kUsbLength).buttons == kBtnRightThumb);
    }

    // The PS button, touchpad click and mute are deliberately unbound, and must stay that way:
    // a touchpad press is easy to trigger just by holding the pad.
    {
        auto report = usbReport();
        report[10] = 0x01 | 0x02 | 0x04;
        assert(decode(report, kUsbLength).buttons == 0);
    }

    // The hat, all eight positions plus centre. Diagonals must report both axes.
    {
        auto report = usbReport();
        const std::pair<unsigned char, USHORT> expected[] = {
            {0, kBtnDpadUp},
            {1, static_cast<USHORT>(kBtnDpadUp | kBtnDpadRight)},
            {2, kBtnDpadRight},
            {3, static_cast<USHORT>(kBtnDpadDown | kBtnDpadRight)},
            {4, kBtnDpadDown},
            {5, static_cast<USHORT>(kBtnDpadDown | kBtnDpadLeft)},
            {6, kBtnDpadLeft},
            {7, static_cast<USHORT>(kBtnDpadUp | kBtnDpadLeft)},
            {8, 0},
        };
        for (const auto &[hat, buttons] : expected) {
            report[8] = hat;
            assert(decode(report, kUsbLength).buttons == buttons);
        }
    }

    // Stick direction. HID counts Y downward and XInput counts it upward, so pushing the stick up
    // must come back positive — this is the inversion that makes menus scroll backwards if missed.
    {
        auto report = usbReport();
        report[1] = 255; // full right
        report[2] = 0;   // full up
        const PadState state = decode(report, kUsbLength);
        assert(state.thumbLX > 30000);
        assert(state.thumbLY > 30000);

        report[1] = 0;   // full left
        report[2] = 255; // full down
        const PadState flipped = decode(report, kUsbLength);
        assert(flipped.thumbLX < -30000);
        assert(flipped.thumbLY < -30000);
    }

    // Triggers pass through as 0..255, matching what XInput reports.
    {
        auto report = usbReport();
        report[5] = 200;
        report[6] = 17;
        const PadState state = decode(report, kUsbLength);
        assert(state.leftTrigger == 200);
        assert(state.rightTrigger == 17);
    }

    // The two Bluetooth layouts put the same controls in different places. Decoding a BT report
    // with the USB offsets (or the reverse) is the single most likely way this breaks, so each
    // layout is checked to produce the same answer for the same physical input.
    {
        auto full = btFullReport();
        full[2] = 255;      // LX full right
        full[6] = 200;      // L2
        full[9] = 0x08 | 0x20; // Cross
        full[10] = 0x02;    // R1
        const PadState state = decode(full, kBtLength);
        assert(state.thumbLX > 30000);
        assert(state.leftTrigger == 200);
        assert(state.buttons == (kBtnA | kBtnRightShoulder));
    }
    {
        auto simple = btSimpleReport();
        simple[1] = 255;        // LX full right
        simple[8] = 200;        // L2
        simple[5] = 0x08 | 0x20; // Cross
        simple[6] = 0x02;       // R1
        const PadState state = decode(simple, kBtLength);
        assert(state.thumbLX > 30000);
        assert(state.leftTrigger == 200);
        assert(state.buttons == (kBtnA | kBtnRightShoulder));
    }

    // Reports the decoder does not recognise are refused rather than parsed as garbage. A stray
    // feature or audio report must not be read as a fistful of held buttons.
    {
        PadState state;
        auto report = usbReport();
        report[0] = 0x05; // not an input report this code knows
        assert(!decodeReport(report.data(), static_cast<DWORD>(report.size()), kUsbLength, PadModel::DualSense, state));

        std::vector<unsigned char> truncated(6, 0);
        truncated[0] = 0x01;
        assert(!decodeReport(truncated.data(), static_cast<DWORD>(truncated.size()), kUsbLength, PadModel::DualSense, state));
        assert(state.buttons == 0);
    }

    std::cout << "16 native DualSense decode scenarios passed\n";
}
} // namespace


// Exercises the DualShock 4 report layouts.
//
// A DS4 puts its buttons where a DualSense puts its triggers, so decoding one as the other reads
// held buttons out of trigger travel and vice versa. Nobody here has a DS4 to plug in, which makes
// these assertions the only thing standing between "supported" and "confidently wrong".
namespace {

// Nothing pressed, sticks centred, in each DualShock 4 layout.
std::vector<unsigned char> ds4UsbReport() {
    std::vector<unsigned char> report(kUsbLength, 0);
    report[0] = 0x01;
    report[1] = report[2] = report[3] = report[4] = 128; // axes
    report[5] = 0x08;                                    // hat centred, no face buttons
    report[8] = report[9] = 0;                           // L2 / R2
    return report;
}

std::vector<unsigned char> ds4BluetoothReport() {
    std::vector<unsigned char> report(kBtLength, 0);
    report[0] = 0x11;
    report[3] = report[4] = report[5] = report[6] = 128;
    report[7] = 0x08;
    report[10] = report[11] = 0;
    return report;
}

void runDualShock4DecodeTests() {
    using namespace nuvio_gamepad_hid;

    // Resting pad reads as dead centre on both transports.
    for (auto &&[report, length] : {
             std::pair{ds4UsbReport(), kUsbLength},
             std::pair{ds4BluetoothReport(), kBtLength},
         }) {
        const PadState state = decode(report, length, PadModel::DualShock4);
        assert(state.buttons == 0);
        assert(state.leftTrigger == 0 && state.rightTrigger == 0);
        assert(std::abs(static_cast<int>(state.thumbLX)) <= 257);
        assert(std::abs(static_cast<int>(state.thumbLY)) <= 257);
        assert(std::abs(static_cast<int>(state.thumbRX)) <= 257);
        assert(std::abs(static_cast<int>(state.thumbRY)) <= 257);
    }

    // Face buttons sit in the same bits as the DualSense, one byte earlier.
    {
        auto report = ds4UsbReport();
        report[5] = 0x08 | 0x20; // Cross
        assert(decode(report, kUsbLength, PadModel::DualShock4).buttons == kBtnA);
        report[5] = 0x08 | 0x40; // Circle
        assert(decode(report, kUsbLength, PadModel::DualShock4).buttons == kBtnB);
        report[5] = 0x08 | 0x10; // Square
        assert(decode(report, kUsbLength, PadModel::DualShock4).buttons == kBtnX);
        report[5] = 0x08 | 0x80; // Triangle
        assert(decode(report, kUsbLength, PadModel::DualShock4).buttons == kBtnY);
    }

    // Shoulders, Share/Options and the stick clicks.
    {
        auto report = ds4UsbReport();
        report[6] = 0x01; assert(decode(report, kUsbLength, PadModel::DualShock4).buttons == kBtnLeftShoulder);
        report[6] = 0x02; assert(decode(report, kUsbLength, PadModel::DualShock4).buttons == kBtnRightShoulder);
        report[6] = 0x10; assert(decode(report, kUsbLength, PadModel::DualShock4).buttons == kBtnBack);  // Share
        report[6] = 0x20; assert(decode(report, kUsbLength, PadModel::DualShock4).buttons == kBtnStart); // Options
        report[6] = 0x40; assert(decode(report, kUsbLength, PadModel::DualShock4).buttons == kBtnLeftThumb);
        report[6] = 0x80; assert(decode(report, kUsbLength, PadModel::DualShock4).buttons == kBtnRightThumb);
    }

    // PS and touchpad stay unbound, as on the DualSense.
    {
        auto report = ds4UsbReport();
        report[7] = 0x01 | 0x02;
        assert(decode(report, kUsbLength, PadModel::DualShock4).buttons == 0);
    }

    // The hat, including diagonals.
    {
        auto report = ds4UsbReport();
        const std::pair<unsigned char, USHORT> expected[] = {
            {0, kBtnDpadUp},
            {2, kBtnDpadRight},
            {4, kBtnDpadDown},
            {6, kBtnDpadLeft},
            {1, static_cast<USHORT>(kBtnDpadUp | kBtnDpadRight)},
            {5, static_cast<USHORT>(kBtnDpadDown | kBtnDpadLeft)},
            {8, 0},
        };
        for (const auto &[hat, buttons] : expected) {
            report[5] = hat;
            assert(decode(report, kUsbLength, PadModel::DualShock4).buttons == buttons);
        }
    }

    // Sticks, including the Y inversion that makes menus scroll the right way.
    {
        auto report = ds4UsbReport();
        report[1] = 255; // full right
        report[2] = 0;   // full up
        const PadState state = decode(report, kUsbLength, PadModel::DualShock4);
        assert(state.thumbLX > 30000);
        assert(state.thumbLY > 30000);
    }

    // Triggers live after the buttons on a DS4, which is the whole reason the model matters.
    {
        auto report = ds4UsbReport();
        report[8] = 200;
        report[9] = 17;
        const PadState state = decode(report, kUsbLength, PadModel::DualShock4);
        assert(state.leftTrigger == 200);
        assert(state.rightTrigger == 17);
    }

    // The Bluetooth report is the same payload behind two extra header bytes: the same physical
    // input must decode identically on both transports.
    {
        auto bt = ds4BluetoothReport();
        bt[3] = 255;            // LX full right
        bt[7] = 0x08 | 0x20;    // Cross
        bt[8] = 0x02;           // R1
        bt[10] = 200;           // L2
        const PadState state = decode(bt, kBtLength, PadModel::DualShock4);
        assert(state.thumbLX > 30000);
        assert(state.leftTrigger == 200);
        assert(state.buttons == (kBtnA | kBtnRightShoulder));
    }

    // Reading a DS4 report with the DualSense layout must not quietly produce the same answer —
    // if it did, the model plumbing would be untested decoration. Trigger travel would be read as
    // a fistful of held buttons.
    {
        auto report = ds4UsbReport();
        report[8] = 255; // L2 fully pressed
        report[9] = 255; // R2 fully pressed
        const PadState correct = decode(report, kUsbLength, PadModel::DualShock4);
        const PadState wrong = decode(report, kUsbLength, PadModel::DualSense);
        assert(correct.leftTrigger == 255 && correct.rightTrigger == 255);
        assert(correct.buttons == 0);
        assert(wrong.buttons != correct.buttons);
    }

    // A DualSense Bluetooth report id is not a DualShock 4 one, and must be refused rather than
    // parsed at whatever offsets happen to be in range.
    {
        PadState state;
        auto report = ds4BluetoothReport();
        report[0] = 0x31; // DualSense full-Bluetooth id
        assert(!decodeReport(report.data(), static_cast<DWORD>(report.size()), kBtLength, PadModel::DualShock4, state));
    }

    std::cout << "10 native DualShock 4 decode scenarios passed\n";
}
} // namespace

int main() {
    PlaybackStartupTest::run();
    runDualSenseDecodeTests();
    runDualShock4DecodeTests();
}


