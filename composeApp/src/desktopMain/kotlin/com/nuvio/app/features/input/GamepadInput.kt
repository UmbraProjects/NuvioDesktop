package com.nuvio.app.features.input

import com.nuvio.app.features.voice.VoiceControlRepository

import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.TextInputFocusTracker
import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import com.nuvio.app.features.panelring.PanelRingController
import com.nuvio.app.features.panelring.PanelRingMath
import com.nuvio.app.features.panelring.PanelRingSource
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Whether the native player currently owns the screen. The player installs its own global
 * KeyEventDispatcher only while it is mounted, so routing already works either way — but a pad
 * wants A to mean "select" in a grid and "play/pause" during playback, and that is a difference of
 * intent the key path cannot infer. Set from the dispatcher lifecycle in PlayerEngine.desktop.
 */
internal object GamepadContext {
    @Volatile
    var playerActive: Boolean = false

    /**
     * Whether a shader chain is currently running on the picture. Published by the player's
     * playback-info pass, which is the only thing that knows — the settings alone do not say, since
     * an anime preset does nothing to live action. Decides what the right stick's down direction
     * cycles, because shaders and HDR both apply at once.
     */
    @Volatile
    var animeShaderActive: Boolean = false
}

/** A stick pushed past its dead zone, before it is attributed to the left or right stick. */
internal enum class StickDirection { Up, Down, Left, Right }

/**
 * Reads XInput on a background thread and replays what it finds as keystrokes.
 *
 * Windows-only, and off unless the user turns it on. Start/stop is driven from the window
 * lifecycle in Main; the thread itself parks on a monitor while disabled, so a disabled pad — or a
 * machine with no pad at all — costs nothing beyond one idle thread.
 */
object GamepadInput {
    private val log = Logger.withTag("Gamepad")

    // XInput wButtons bits (Xinput.h). Mirrored rather than imported so the poll loop stays pure
    // Kotlin — the native side hands back the raw word untouched.
    private const val BIT_DPAD_UP = 0x0001
    private const val BIT_DPAD_DOWN = 0x0002
    private const val BIT_DPAD_LEFT = 0x0004
    private const val BIT_DPAD_RIGHT = 0x0008
    private const val BIT_START = 0x0010
    private const val BIT_BACK = 0x0020
    private const val BIT_LEFT_THUMB = 0x0040
    private const val BIT_RIGHT_THUMB = 0x0080
    private const val BIT_LEFT_SHOULDER = 0x0100
    private const val BIT_RIGHT_SHOULDER = 0x0200
    private const val BIT_MIC = 0x0800 // DualSense only; see player_bridge.cpp kBtnMic
    private const val BIT_A = 0x1000
    private const val BIT_B = 0x2000
    private const val BIT_X = 0x4000
    private const val BIT_Y = 0x8000

    // Slots 0-3 are XInput pads; slots 4-7 are DualSense pads the native side reads over raw HID and
    // hands back in the same XInput-shaped layout. Nothing here needs to know which kind a slot is.
    private const val SLOT_COUNT = 8
    private const val SLOT_STRIDE = 8
    private const val ALL_SLOTS = 0xFF

    private const val POLL_INTERVAL_MS = 16L

    /**
     * Querying an empty XInput slot is the slow case, and re-enumerating HID devices is slower
     * still, so disconnected slots are rechecked on this cadence rather than every tick.
     */
    private const val RESCAN_INTERVAL_MS = 2_000L

    /** Tick length while no pad is connected: there is nothing to miss until one appears. */
    private const val IDLE_POLL_INTERVAL_MS = 250L

    /** Trigger travel (of 255) treated as a press. Well above XInput's own 30-unit noise floor. */
    private const val TRIGGER_PRESS_THRESHOLD = 60

    private const val THUMB_RANGE = 32_767f

    /**
     * Floor for the right stick, independent of the user's navigation dead zone. Its directions fire
     * one-shot actions — a speed step, a shader cycle — so a light brush past a 30% threshold while
     * reaching for a face button would be a visible mistake rather than a harmless cursor nudge.
     */
    private const val RIGHT_STICK_MIN_DEAD_ZONE = 0.55f

    /**
     * Stick travel that aims the panel ring. Higher than the navigation dead zone: aiming is a
     * deliberate push, and a light one would pick a neighbour of the slot meant.
     */
    private const val RING_AIM_DEAD_ZONE = 0.45f

    private val buttonBits = listOf(
        GamepadButton.DpadUp to BIT_DPAD_UP,
        GamepadButton.DpadDown to BIT_DPAD_DOWN,
        GamepadButton.DpadLeft to BIT_DPAD_LEFT,
        GamepadButton.DpadRight to BIT_DPAD_RIGHT,
        GamepadButton.Start to BIT_START,
        GamepadButton.Back to BIT_BACK,
        GamepadButton.LeftThumb to BIT_LEFT_THUMB,
        GamepadButton.RightThumb to BIT_RIGHT_THUMB,
        GamepadButton.LeftShoulder to BIT_LEFT_SHOULDER,
        GamepadButton.RightShoulder to BIT_RIGHT_SHOULDER,
        GamepadButton.A to BIT_A,
        GamepadButton.B to BIT_B,
        GamepadButton.X to BIT_X,
        GamepadButton.Y to BIT_Y,
        GamepadButton.Mic to BIT_MIC,
    )

    private val directions = setOf(
        GamepadButton.DpadUp,
        GamepadButton.DpadDown,
        GamepadButton.DpadLeft,
        GamepadButton.DpadRight,
    )

    private val started = AtomicBoolean(false)
    // A lock/condition pair rather than wait/notify: Kotlin has no Object monitor to sit on, and
    // parking here is what keeps a disabled pad from costing anything.
    private val wakeLock = ReentrantLock()
    private val wakeCondition: Condition = wakeLock.newCondition()

    /**
     * Bumped by every [stop]. A poll thread runs only while its own generation is still current, so
     * a stop/start pair — the window being recreated, say — cannot leave the outgoing thread alive
     * alongside its replacement, which would inject every press twice.
     */
    private val generation = AtomicInteger(0)

    /** Set once XInput turns out to be missing, so the thread parks instead of retrying forever. */
    @Volatile
    private var unavailable = false

    private var thread: Thread? = null

    /**
     * When the pad was last actually used. A connected controller sitting on the sofa is not the
     * same as a controller in someone's hands, and the on-screen keyboard should not ambush a mouse
     * user who clicked into a settings field.
     */
    @Volatile
    private var lastInputAtMs = 0L

    /** True if the pad has been touched recently enough to assume the user is holding it. */
    fun usedRecently(withinMs: Long = 60_000L): Boolean {
        val last = lastInputAtMs
        return last != 0L && System.currentTimeMillis() - last <= withinMs
    }

    /**
     * Milliseconds since the pad was last used, or null if it has not been this session. The
     * screensaver folds this into the system idle time, which knows nothing about controllers.
     */
    fun idleMs(): Long? {
        val last = lastInputAtMs
        return if (last == 0L) null else (System.currentTimeMillis() - last).coerceAtLeast(0L)
    }

    /**
     * Installs the poll thread. Safe to call when gamepad support is off or the host is not
     * Windows — both cases no-op, and the native library is never touched.
     */
    fun start() {
        if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return
        if (!started.compareAndSet(false, true)) return
        val myGeneration = generation.get()
        thread = Thread({ pollLoop(myGeneration) }, "nuvio-gamepad").apply {
            isDaemon = true
            // Below the UI: this thread only synthesizes events, it must never compete with drawing.
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        generation.incrementAndGet()
        wakeLock.withLock { wakeCondition.signalAll() }
        thread = null
    }

    private fun isCurrent(myGeneration: Int): Boolean = generation.get() == myGeneration

    /** Wakes the parked poll thread when the user flips the setting on. */
    internal fun onEnabledChanged(enabled: Boolean) {
        if (!enabled) GamepadSettingsRepository.setConnected(false)
        wakeLock.withLock { wakeCondition.signalAll() }
    }

    private fun pollLoop(myGeneration: Int) {
        val state = IntArray(SLOT_COUNT * SLOT_STRIDE)
        var pressed = emptySet<GamepadButton>()
        val repeatDueAt = HashMap<GamepadButton, Long>()
        // Buttons whose key press is still down, waiting for the button to come up to release it.
        val heldKeys = HashMap<GamepadButton, Int>()
        var connectedMask = 0
        var nextRescanAt = 0L

        while (isCurrent(myGeneration)) {
            if (!GamepadSettingsRepository.enabled || unavailable) {
                // Parked: no polling, no native call, no wakeups until the setting changes.
                pressed = emptySet()
                repeatDueAt.clear()
                releaseHeldKeys(heldKeys)
                connectedMask = 0
                nextRescanAt = 0L
                GamepadSettingsRepository.setConnected(false)
                wakeLock.withLock {
                    if (isCurrent(myGeneration) && (!GamepadSettingsRepository.enabled || unavailable)) {
                        runCatching { wakeCondition.await(1, TimeUnit.SECONDS) }
                    }
                }
                continue
            }

            if (!ensureNativeReady()) continue

            val now = System.currentTimeMillis()
            // A narrow tick asks only about slots already known to be connected; the periodic full
            // tick is what discovers a pad that was plugged in since. Either way the answer is the
            // whole truth about the slots that were asked, so it replaces the mask outright.
            val slotMask = if (now >= nextRescanAt) {
                nextRescanAt = now + RESCAN_INTERVAL_MS
                ALL_SLOTS
            } else {
                connectedMask
            }

            if (slotMask != 0) {
                connectedMask = runCatching { NativePlayerBridge.pollGamepads(slotMask, state) }
                    .onFailure {
                        log.w(it) { "XInput poll failed; gamepad input disabled for this session" }
                        unavailable = true
                    }
                    .getOrDefault(0)
                GamepadSettingsRepository.setConnected(connectedMask != 0)
            }

            val current = if (connectedMask == 0) emptySet() else readPressed(state, connectedMask)
            dispatch(current, pressed, repeatDueAt, heldKeys, now)
            pressed = current

            // With nothing plugged in there is no input to miss, so idle until the next rescan
            // rather than waking sixty times a second on a machine that has no controller.
            sleep(if (connectedMask == 0) IDLE_POLL_INTERVAL_MS else POLL_INTERVAL_MS)
        }
        releaseHeldKeys(heldKeys)
        GamepadSettingsRepository.setConnected(false)
    }

    private fun releaseHeldKeys(heldKeys: MutableMap<GamepadButton, Int>) {
        heldKeys.values.forEach(GamepadKeyInjector::release)
        heldKeys.clear()
    }

    /**
     * Loads the native bridge lazily, on this thread. player_bridge.dll carries the JNI entry
     * point, and its load is deliberately paid off the UI thread — see
     * [NativePlayerBridge.ensureNativeLibraryLoaded]. On a dev tree with no native build, or a
     * machine without the runtime, this fails once and parks the thread for good.
     */
    private fun ensureNativeReady(): Boolean {
        val loaded = runCatching { NativePlayerBridge.ensureNativeLibraryLoaded() }
            .onFailure { log.w(it) { "native bridge unavailable; gamepad input disabled for this session" } }
            .isSuccess
        if (!loaded) unavailable = true
        return loaded
    }

    /**
     * Collapses every connected pad into one set of held controls. Merging rather than picking a
     * "primary" pad means a second controller just works, and costs only the OR.
     */
    private fun readPressed(state: IntArray, connectedMask: Int): Set<GamepadButton> {
        val deadZone = GamepadSettingsRepository.deadZone
        val held = HashSet<GamepadButton>()
        val dpad = HashSet<GamepadButton>()
        var aimX = 0f
        var aimY = 0f
        for (slot in 0 until SLOT_COUNT) {
            if (connectedMask and (1 shl slot) == 0) continue
            val base = slot * SLOT_STRIDE
            val buttons = state[base]
            buttonBits.forEach { (button, bit) -> if (buttons and bit != 0) held += button }
            buttonBits.forEach { (button, bit) -> if (button in directions && buttons and bit != 0) dpad += button }
            // The ring aims with whichever stick, on whichever pad, is pushed furthest.
            for (stickBase in intArrayOf(base + 3, base + 5)) {
                val x = state[stickBase] / THUMB_RANGE
                val y = state[stickBase + 1] / THUMB_RANGE
                if (hypot(x, y) > hypot(aimX, aimY)) {
                    aimX = x
                    aimY = y
                }
            }
            if (state[base + 1] >= TRIGGER_PRESS_THRESHOLD) held += GamepadButton.LeftTrigger
            if (state[base + 2] >= TRIGGER_PRESS_THRESHOLD) held += GamepadButton.RightTrigger
            stickDirection(state[base + 3], state[base + 4], deadZone)?.let { held += leftStickButton(it) }
            stickDirection(
                state[base + 5],
                state[base + 6],
                deadZone.coerceAtLeast(RIGHT_STICK_MIN_DEAD_ZONE),
            )?.let { held += rightStickButton(it) }
        }
        dpadHeld = dpad
        stickX = aimX
        stickY = aimY
        return held
    }

    // The last poll's D-pad (buttons only, no stick) and strongest stick, for the panel ring. Poll
    // thread only.
    private var dpadHeld: Set<GamepadButton> = emptySet()
    private var previousDpad: Set<GamepadButton> = emptySet()
    private var stickX = 0f
    private var stickY = 0f

    private fun leftStickButton(direction: StickDirection): GamepadButton = when (direction) {
        StickDirection.Up -> GamepadButton.DpadUp
        StickDirection.Down -> GamepadButton.DpadDown
        StickDirection.Left -> GamepadButton.DpadLeft
        StickDirection.Right -> GamepadButton.DpadRight
    }

    private fun rightStickButton(direction: StickDirection): GamepadButton = when (direction) {
        StickDirection.Up -> GamepadButton.RightStickUp
        StickDirection.Down -> GamepadButton.RightStickDown
        StickDirection.Left -> GamepadButton.RightStickLeft
        StickDirection.Right -> GamepadButton.RightStickRight
    }

    /**
     * Folds the left stick into a single D-pad direction. Only the dominant axis counts, so a
     * diagonal push moves one way instead of firing two navigation keys at once.
     */
    internal fun stickDirection(rawX: Int, rawY: Int, deadZone: Float): StickDirection? {
        val x = rawX / THUMB_RANGE
        val y = rawY / THUMB_RANGE
        if (abs(x) < deadZone && abs(y) < deadZone) return null
        return if (abs(x) >= abs(y)) {
            if (x > 0) StickDirection.Right else StickDirection.Left
        } else {
            // XInput's Y is positive upward; screen coordinates are not.
            if (y > 0) StickDirection.Up else StickDirection.Down
        }
    }

    /**
     * Decides which buttons fire on this tick and updates [repeatDueAt] in place. Pure apart from
     * that map, so the press/hold/repeat timing is testable without a controller or an event queue
     * — the part of this feature most likely to feel wrong is exactly the part hardware cannot be
     * assumed for.
     *
     * A button fires on the tick it is first seen. Only directions then repeat, after
     * [GamepadSettingsRepository.DEFAULT_REPEAT_DELAY_MS] and every `repeatIntervalMs` after that:
     * a held face button repeating would fire its action over and over, and "back" would walk the
     * whole navigation stack on a single press.
     */
    internal fun resolveFiring(
        current: Set<GamepadButton>,
        previous: Set<GamepadButton>,
        repeatDueAt: MutableMap<GamepadButton, Long>,
        now: Long,
        repeatIntervalMs: Int,
    ): List<GamepadButton> {
        val firing = ArrayList<GamepadButton>(current.size)
        current.forEach { button ->
            if (button !in previous) {
                firing += button
                if (button in directions) {
                    repeatDueAt[button] = now + GamepadSettingsRepository.DEFAULT_REPEAT_DELAY_MS
                }
                return@forEach
            }
            val dueAt = repeatDueAt[button] ?: return@forEach
            if (now >= dueAt) {
                firing += button
                repeatDueAt[button] = now + repeatIntervalMs
            }
        }
        previous.forEach { button -> if (button !in current) repeatDueAt.remove(button) }
        return firing
    }

    private fun dispatch(
        current: Set<GamepadButton>,
        previous: Set<GamepadButton>,
        repeatDueAt: MutableMap<GamepadButton, Long>,
        heldKeys: MutableMap<GamepadButton, Int>,
        now: Long,
    ) {
        previous.forEach { button ->
            if (button !in current) heldKeys.remove(button)?.let(GamepadKeyInjector::release)
        }
        val inPlayer = GamepadContext.playerActive
        if (panelRing(current, previous, inPlayer, heldKeys, now)) return
        val ringButton = ringDeciding
        // fork:voice-control - with voice control ready, View is held for push-to-talk, so its own
        // action waits for the release instead of firing on the press.
        val holdForVoice = VoiceControlRepository.uiState.value.ready || backHold.pending
        if (holdForVoice) {
            when (backHold.update(GamepadButton.Back in current, now)) {
                BackHold.Result.Talk -> VoiceControlRepository.toggleListening()
                BackHold.Result.Tap -> send(GamepadButton.Back, inPlayer, heldKeys)
                BackHold.Result.None -> Unit
            }
        }
        resolveFiring(current, previous, repeatDueAt, now, GamepadSettingsRepository.repeatIntervalMs)
            .forEach { button ->
                if (holdForVoice && button == GamepadButton.Back) return@forEach
                if (button == ringButton) return@forEach
                send(button, inPlayer, heldKeys)
            }
    }

    private val ringHold = RingHold()

    /** The ring button while its press is being decided, so the ordinary dispatch skips it. */
    private var ringDeciding: GamepadButton? = null

    /**
     * The panel ring's share of a tick. Holding the ring button ([GamepadSettingsRepository.ringButton],
     * LB by default) opens it, tilting either stick aims, letting go switches; a tap does the
     * button's own action instead. While the ring is open (from any input) the pad drives it: stick
     * aims, D-pad steps round, A switches, B closes, and nothing else gets through.
     *
     * Returns true when the ring took the whole tick. Sets [ringDeciding].
     */
    private fun panelRing(
        current: Set<GamepadButton>,
        previous: Set<GamepadButton>,
        inPlayer: Boolean,
        heldKeys: MutableMap<GamepadButton, Int>,
        now: Long,
    ): Boolean {
        val dpad = dpadHeld
        val freshDpad = dpad - previousDpad
        previousDpad = dpad
        val ringButton = GamepadSettingsRepository.ringButton
        // Not over the player (its native surface covers the app) or the on-screen keyboard, and
        // not while another application is in front.
        val canRing = !inPlayer && !OnScreenKeyboard.visible.value && GamepadKeyInjector.appFocused()
        val aimDeadZone = GamepadSettingsRepository.deadZone.coerceAtLeast(RING_AIM_DEAD_ZONE)
        val stickPushed = hypot(stickX, stickY) >= aimDeadZone
        ringDeciding = null
        if (ringButton != null && (canRing || ringHold.pending)) {
            when (ringHold.update(ringButton in current, stickPushed, now)) {
                RingHold.Result.Open -> {
                    lastInputAtMs = System.currentTimeMillis()
                    if (!PanelRingController.isOpen) PanelRingController.open(PanelRingSource.Gamepad)
                }
                RingHold.Result.Tap -> if (!PanelRingController.isOpen) send(ringButton, inPlayer, heldKeys)
                RingHold.Result.Release -> PanelRingController.commit()
                RingHold.Result.None -> Unit
            }
            if (ringHold.pending) ringDeciding = ringButton
        }
        if (!PanelRingController.isOpen) return false
        if (inPlayer) {
            PanelRingController.close()
            return false
        }
        lastInputAtMs = System.currentTimeMillis()
        val count = PanelRingController.slots.size
        // The aim stays where it was when the stick springs back: letting go of the stick a moment
        // before the button must still switch. B is the way out.
        PanelRingMath.slotFor(
            dx = stickX,
            dy = -stickY, // XInput's y is up, the ring's is down
            count = count,
            deadZone = aimDeadZone,
            current = PanelRingController.state.value?.hovered,
        )?.let(PanelRingController::aim)
        val fresh = current - previous
        when {
            GamepadButton.A in fresh -> PanelRingController.commit()
            GamepadButton.B in fresh -> PanelRingController.close()
            GamepadButton.DpadRight in freshDpad || GamepadButton.DpadDown in freshDpad -> PanelRingController.step(1)
            GamepadButton.DpadLeft in freshDpad || GamepadButton.DpadUp in freshDpad -> PanelRingController.step(-1)
        }
        return true
    }

    /**
     * The ring button's press: held [holdMs], or held with a stick pushed, it opens the ring
     * ([Result.Open], once); let go after that it switches ([Result.Release]); let go sooner with no
     * stick and it was an ordinary press ([Result.Tap]), sent on the release. Pure, for the test.
     */
    internal class RingHold(private val holdMs: Long = 220) {
        enum class Result { None, Open, Tap, Release }

        private var pressedAt = -1L
        private var opened = false

        val pending: Boolean get() = pressedAt >= 0

        fun update(down: Boolean, stickPushed: Boolean, now: Long): Result {
            if (down) {
                if (pressedAt < 0) {
                    pressedAt = now
                    opened = false
                }
                if (!opened && (stickPushed || now - pressedAt >= holdMs)) {
                    opened = true
                    return Result.Open
                }
                return Result.None
            }
            if (pressedAt < 0) return Result.None
            pressedAt = -1L
            return if (opened) Result.Release else Result.Tap
        }
    }

    private val backHold = BackHold()

    /**
     * View pressed and held [holdMs] is push-to-talk ([Result.Talk], once per press); let go sooner
     * and it is an ordinary press of View ([Result.Tap]), sent on the release. Pure, for the test.
     */
    internal class BackHold(private val holdMs: Long = 600) {
        enum class Result { None, Tap, Talk }

        private var pressedAt = -1L
        private var talked = false

        /** True from the press until it is resolved, so a press in progress finishes the way it started. */
        val pending: Boolean get() = pressedAt >= 0

        fun update(down: Boolean, now: Long): Result {
            if (down) {
                if (pressedAt < 0) {
                    pressedAt = now
                    talked = false
                }
                if (!talked && now - pressedAt >= holdMs) {
                    talked = true
                    return Result.Talk
                }
                return Result.None
            }
            if (pressedAt < 0) return Result.None
            pressedAt = -1L
            return if (talked) Result.None else Result.Tap
        }
    }

    /**
     * Whether [target] keeps its key down for as long as the button is held, instead of a press and
     * release on the same tick. Select needs that so holding A can mean hold-to-select, exactly as
     * holding Enter does; every other button keeps the instant pair.
     */
    internal fun holdsUntilRelease(target: GamepadTarget): Boolean =
        target is GamepadTarget.AppAction && target.action == AppShortcutAction.SelectFocused

    private fun send(button: GamepadButton, inPlayer: Boolean, heldKeys: MutableMap<GamepadButton, Int>) {
        lastInputAtMs = System.currentTimeMillis()
        // Picking up the pad while a text field already holds focus summons the keyboard, for the
        // case where the field was reached with the mouse.
        if (OnScreenKeyboard.onPadInputWhileTextFocused(TextInputFocusTracker.active.value)) return
        // While the on-screen keyboard is up it owns the pad entirely, including the buttons it does
        // not use. A press falling through to the screen behind would fire a browsing shortcut in
        // the middle of a word.
        if (OnScreenKeyboard.consume(button)) return
        val binding = GamepadSettingsRepository.binding(button)
        val target = if (inPlayer) binding.player else binding.browsing
        // A button bound to the ring's own shortcut opens it for the pad (no numbers), not as a key.
        if (target == GamepadTarget.AppAction(AppShortcutAction.OpenPanelRing)) {
            if (!inPlayer && !PanelRingController.isOpen) PanelRingController.open(PanelRingSource.Gamepad)
            return
        }
        // fork:voice-control - straight to the voice session rather than a synthesized key, so it
        // also works over the player, whose HUD has the keyboard.
        if (target == GamepadTarget.AppAction(AppShortcutAction.VoiceCommand)) {
            VoiceControlRepository.toggleListening()
            return
        }
        if (holdsUntilRelease(target)) {
            GamepadKeyInjector.press(target)?.let { keyCode -> heldKeys[button] = keyCode }
        } else {
            GamepadKeyInjector.send(target)
        }
    }

    private fun sleep(millis: Long) {
        runCatching { Thread.sleep(millis) }.onFailure { Thread.currentThread().interrupt() }
    }
}
