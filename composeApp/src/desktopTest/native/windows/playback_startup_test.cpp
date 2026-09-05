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
        std::cout << "22 native playback startup scenarios passed\n";
    }
};
}
int main() { PlaybackStartupTest::run(); }
