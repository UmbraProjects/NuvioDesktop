// Known at first script run (from the page URL): this controls page is a passive hero
// trailer surface, so it must never take keyboard focus or hide the cursor.
const isHeroTrailerSurface = (() => {
  try {
    return new URLSearchParams(location.search).get("heroTrailer") === "1";
  } catch (_err) {
    return false;
  }
})();

const root = document.getElementById("playerRoot");
const contextMenu = document.getElementById("contextMenu");
const seek = document.getElementById("seek");
const pipSeek = document.getElementById("pipSeek");
const timeline = document.getElementById("timeline");
const chapterMarkers = document.getElementById("chapterMarkers");
const chapterTooltip = document.getElementById("chapterTooltip");
const seekThumbnail = document.getElementById("seekThumbnail");
const seekThumbnailImage = document.getElementById("seekThumbnailImage");
const seekThumbnailChapter = document.getElementById("seekThumbnailChapter");
const seekThumbnailTime = document.getElementById("seekThumbnailTime");
const positionLabel = document.getElementById("position");
const durationLabel = document.getElementById("duration");
const bufferingStatus = document.getElementById("bufferingStatus");
const playbackError = document.getElementById("playbackError");
const playbackErrorTitle = document.getElementById("playbackErrorTitle");
const playbackErrorMessage = document.getElementById("playbackErrorMessage");
const playbackErrorAction = document.getElementById("playbackErrorAction");
const playbackErrorActionLabel = document.getElementById("playbackErrorActionLabel");
const pauseMetadataOverlay = document.getElementById("pauseMetadataOverlay");
const pauseWatchingLabel = document.getElementById("pauseWatchingLabel");
const pauseLogo = document.getElementById("pauseLogo");
const pauseTitle = document.getElementById("pauseTitle");
const pauseEpisodeInfo = document.getElementById("pauseEpisodeInfo");
const pauseEpisodeTitle = document.getElementById("pauseEpisodeTitle");
const pauseDescription = document.getElementById("pauseDescription");
const toggle = document.getElementById("toggle");
const toggleIcon = document.getElementById("toggleIcon");
const lockIcon = document.getElementById("lockIcon");
const title = document.getElementById("title");
const episode = document.getElementById("episode");
const streamTitle = document.getElementById("streamTitle");
const providerName = document.getElementById("providerName");
const resizeLabel = document.getElementById("resizeLabel");
const speedLabel = document.getElementById("speedLabel");
const speedButton = document.getElementById("speedButton");
const playerVolumeSlider = document.getElementById("playerVolumeSlider");
const playerVolumeIcon = document.getElementById("playerVolumeIcon");
const controlTooltip = document.getElementById("controlTooltip");
const actionRow = document.querySelector(".action-row");
const subtitlesLabel = document.getElementById("subtitlesLabel");
const audioLabel = document.getElementById("audioLabel");
const sourcesLabel = document.getElementById("sourcesLabel");
const episodesLabel = document.getElementById("episodesLabel");
const submitIntroButton = document.getElementById("submitIntroButton");
const lockButton = document.getElementById("lockButton");
const videoSettingsButton = document.getElementById("videoSettingsButton");
const pictureInPictureButton = document.getElementById("pictureInPictureButton");
const pictureInPictureExitButton = document.getElementById("pictureInPictureExitButton");
const pictureInPicturePlayButton = document.getElementById("pictureInPicturePlayButton");
const pictureInPictureToggleIcon = document.getElementById("pictureInPictureToggleIcon");
const pictureInPictureResizeHandles = document.querySelectorAll("[data-pip-resize]");
const backButton = document.getElementById("backButton");
const playerClockTime = document.getElementById("playerClockTime");
const playerEndTime = document.getElementById("playerEndTime");
const openingOverlay = document.getElementById("openingOverlay");
const openingArtwork = document.getElementById("openingArtwork");
const openingBackButton = document.getElementById("openingBackButton");
const openingLogoSlot = document.getElementById("openingLogoSlot");
const openingLogoBase = document.getElementById("openingLogoBase");
const openingLogoFillClip = document.getElementById("openingLogoFillClip");
const openingLogoFill = document.getElementById("openingLogoFill");
const openingTitle = document.getElementById("openingTitle");
const openingSpinner = document.getElementById("openingSpinner");
const openingStatus = document.getElementById("openingStatus");
const openingMessage = document.getElementById("openingMessage");
const openingProgressTrack = document.getElementById("openingProgressTrack");
const openingProgressBar = document.getElementById("openingProgressBar");
const parentalGuide = document.getElementById("parentalGuide");
const parentalGuideLine = document.getElementById("parentalGuideLine");
const parentalGuideList = document.getElementById("parentalGuideList");
const volumePill = document.getElementById("volumePill");
const volumePillIcon = document.getElementById("volumePillIcon");
const volumePillLabel = document.getElementById("volumePillLabel");
const skipPrompt = document.getElementById("skipPrompt");
const skipPromptLabel = document.getElementById("skipPromptLabel");
const skipPromptProgress = document.getElementById("skipPromptProgress");
const nextEpisodeCard = document.getElementById("nextEpisodeCard");
const nextEpisodeThumb = document.getElementById("nextEpisodeThumb");
const nextEpisodeHeader = document.getElementById("nextEpisodeHeader");
const nextEpisodeTitle = document.getElementById("nextEpisodeTitle");
const nextEpisodeStatus = document.getElementById("nextEpisodeStatus");
const nextEpisodeAction = document.getElementById("nextEpisodeAction");
const sourcesButton = document.getElementById("sourcesButton");
const episodesButton = document.getElementById("episodesButton");
const episodeNotch = document.getElementById("episodeNotch");
const episodeNotchLabel = document.getElementById("episodeNotchLabel");
const sourceNotch = document.getElementById("sourceNotch");
const lockedLabel = document.getElementById("lockedLabel");
const audioModal = document.getElementById("audioModal");
const subtitleModal = document.getElementById("subtitleModal");
const audioPanel = audioModal ? audioModal.querySelector(".track-panel") : null;
const audioTrackList = document.getElementById("audioTrackList");
const subtitleTrackList = document.getElementById("subtitleTrackList");
const subtitlePanelTitle = document.getElementById("subtitlePanelTitle");
const subtitleBuiltInTab = document.getElementById("subtitleBuiltInTab");
const subtitleAddonsTab = document.getElementById("subtitleAddonsTab");
const subtitleStyleTab = document.getElementById("subtitleStyleTab");
const addonSubtitleList = document.getElementById("addonSubtitleList");
const subtitleStylePanel = document.getElementById("subtitleStylePanel");
const subtitleDelayLabel = document.getElementById("subtitleDelayLabel");
const subtitleDelayMinus = document.getElementById("subtitleDelayMinus");
const subtitleDelayValue = document.getElementById("subtitleDelayValue");
const subtitleDelayPlus = document.getElementById("subtitleDelayPlus");
const subtitleDelayReset = document.getElementById("subtitleDelayReset");
const autoSyncLabel = document.getElementById("autoSyncLabel");
const autoSyncReload = document.getElementById("autoSyncReload");
const autoSyncCapture = document.getElementById("autoSyncCapture");
const autoSyncStatus = document.getElementById("autoSyncStatus");
const autoSyncCueList = document.getElementById("autoSyncCueList");
const fontSizeLabel = document.getElementById("fontSizeLabel");
const fontSizeMinus = document.getElementById("fontSizeMinus");
const fontSizeValue = document.getElementById("fontSizeValue");
const fontSizePlus = document.getElementById("fontSizePlus");
const fontFamilySelect = document.getElementById("fontFamilySelect");
const outlineLabel = document.getElementById("outlineLabel");
const outlineToggle = document.getElementById("outlineToggle");
const shadowLabel = document.getElementById("shadowLabel");
const shadowToggle = document.getElementById("shadowToggle");
const boldLabel = document.getElementById("boldLabel");
const boldToggle = document.getElementById("boldToggle");
const bottomOffsetLabel = document.getElementById("bottomOffsetLabel");
const bottomOffsetMinus = document.getElementById("bottomOffsetMinus");
const bottomOffsetValue = document.getElementById("bottomOffsetValue");
const bottomOffsetPlus = document.getElementById("bottomOffsetPlus");
const subtitleColorLabel = document.getElementById("subtitleColorLabel");
const subtitleColorSwatches = document.getElementById("subtitleColorSwatches");
const textOpacityLabel = document.getElementById("textOpacityLabel");
const textOpacityMinus = document.getElementById("textOpacityMinus");
const textOpacityValue = document.getElementById("textOpacityValue");
const textOpacityPlus = document.getElementById("textOpacityPlus");
const outlineColorLabel = document.getElementById("outlineColorLabel");
const outlineColorSwatches = document.getElementById("outlineColorSwatches");
const subtitleStyleReset = document.getElementById("subtitleStyleReset");
const sourceModal = document.getElementById("sourceModal");
const sourcePanel = sourceModal ? sourceModal.querySelector(".track-panel") : null;
const sourcePanelTitle = document.getElementById("sourcePanelTitle");
const sourceReloadButton = document.getElementById("sourceReloadButton");
const sourceCloseButton = document.getElementById("sourceCloseButton");
const sourceFilterList = document.getElementById("sourceFilterList");
const sourceList = document.getElementById("sourceList");
const episodesModal = document.getElementById("episodesModal");
const episodeListView = document.getElementById("episodeListView");
const episodeStreamsView = document.getElementById("episodeStreamsView");
const episodesPanelTitle = document.getElementById("episodesPanelTitle");
const episodesCloseButton = document.getElementById("episodesCloseButton");
const seasonFilterList = document.getElementById("seasonFilterList");
const episodeList = document.getElementById("episodeList");
const streamsPanelTitle = document.getElementById("streamsPanelTitle");
const episodeBackButton = document.getElementById("episodeBackButton");
const episodeReloadButton = document.getElementById("episodeReloadButton");
const episodeStreamsCloseButton = document.getElementById("episodeStreamsCloseButton");
const episodeStreamFilterList = document.getElementById("episodeStreamFilterList");
const episodeStreamList = document.getElementById("episodeStreamList");
const submitIntroModal = document.getElementById("submitIntroModal");
const submitIntroPanelTitle = document.getElementById("submitIntroPanelTitle");
const submitIntroCloseButton = document.getElementById("submitIntroCloseButton");
const segmentTypeLabel = document.getElementById("segmentTypeLabel");
const segmentIntroButton = document.getElementById("segmentIntroButton");
const segmentRecapButton = document.getElementById("segmentRecapButton");
const segmentOutroButton = document.getElementById("segmentOutroButton");
const startTimeLabel = document.getElementById("startTimeLabel");
const endTimeLabel = document.getElementById("endTimeLabel");
const submitIntroStartInput = document.getElementById("submitIntroStartInput");
const submitIntroEndInput = document.getElementById("submitIntroEndInput");
const captureStartButton = document.getElementById("captureStartButton");
const captureEndButton = document.getElementById("captureEndButton");
const submitIntroStatus = document.getElementById("submitIntroStatus");
const submitIntroCancelButton = document.getElementById("submitIntroCancelButton");
const submitIntroSubmitButton = document.getElementById("submitIntroSubmitButton");
const p2pConsentModal = document.getElementById("p2pConsentModal");
const p2pConsentTitle = document.getElementById("p2pConsentTitle");
const p2pConsentCloseButton = document.getElementById("p2pConsentCloseButton");
const p2pConsentBody = document.getElementById("p2pConsentBody");
const p2pConsentCancelButton = document.getElementById("p2pConsentCancelButton");
const p2pConsentEnableButton = document.getElementById("p2pConsentEnableButton");

let state = {
  title: "",
  episodeText: "",
  streamTitle: "",
  providerName: "",
  pauseOverlayWatchingLabel: "You're watching",
  pauseOverlayLogo: "",
  pauseOverlayEpisodeInfo: "",
  pauseOverlayEpisodeTitle: "",
  pauseOverlayDescription: "",
  resizeModeLabel: "Fit",
  playbackSpeedLabel: "1x",
  playbackSpeedFineIncrementsEnabled: false,
  subtitlesLabel: "Subs",
  audioLabel: "Audio",
  sourcesLabel: "Sources",
  episodesLabel: "Episodes",
  externalPlayerLabel: "External",
  playLabel: "Play",
  pauseLabel: "Pause",
  closeLabel: "Close player",
  lockLabel: "Lock player controls",
  unlockLabel: "Unlock player controls",
  submitIntroLabel: "Submit Intro",
  videoSettingsLabel: "Video settings",
  pictureInPictureLabel: "Picture in picture",
  pictureInPictureActive: false,
  desktopHdrModeLabel: "Auto",
  desktopColorProfileLabel: "Neutral",
  desktopAnimeModeLabel: "Off",
  desktopAnimeSvpEnabled: false,
  tapToUnlockLabel: "Tap to unlock",
  playbackErrorTitle: "Playback error",
  playbackErrorMessage: "",
  playbackErrorActionLabel: "Go back",
  sourcesPanelTitle: "Sources",
  episodesPanelTitle: "Episodes",
  streamsPanelTitle: "Streams",
  allFilterLabel: "All",
  reloadLabel: "Reload",
  backLabel: "Back",
  panelCloseLabel: "Close",
  cancelLabel: "Cancel",
  playingLabel: "Playing",
  noStreamsLabel: "No streams found",
  noEpisodesLabel: "No episodes available",
  submitIntroPanelTitle: "Submit Timestamps",
  submitIntroSegmentTypeLabel: "SEGMENT TYPE",
  submitIntroSegmentIntroLabel: "Intro",
  submitIntroSegmentRecapLabel: "Recap",
  submitIntroSegmentOutroLabel: "Outro",
  submitIntroStartTimeLabel: "START TIME (MM:SS)",
  submitIntroEndTimeLabel: "END TIME (MM:SS)",
  submitIntroCaptureLabel: "Capture",
  submitIntroSubmitLabel: "Submit",
  p2pConsentTitle: "P2P Streaming",
  p2pConsentBody: "",
  p2pConsentEnableLabel: "Enable P2P",
  p2pConsentCancelLabel: "Cancel",
  subtitlesPanelTitle: "Subtitles",
  subtitleBuiltInTabLabel: "Built-in",
  subtitleAddonsTabLabel: "Addons",
  subtitleStyleTabLabel: "Style",
  noneLabel: "None",
  fetchSubtitlesLabel: "Tap to fetch subtitles",
  subtitleDelayLabel: "Subtitle Delay",
  resetLabel: "Reset",
  autoSyncLabel: "Auto Sync",
  reloadSmallLabel: "Reload",
  captureLineLabel: "Capture",
  selectAddonSubtitleFirstLabel: "Select an addon subtitle first",
  loadingSubtitleLinesLabel: "Loading subtitle lines...",
  fontSizeLabel: "Font Size",
  outlineLabel: "Outline",
  shadowLabel: "Shadow",
  boldLabel: "Bold",
  bottomOffsetLabel: "Bottom Offset",
  colorLabel: "Color",
  textOpacityLabel: "Text Opacity",
  outlineColorLabel: "Outline Color",
  resetDefaultsLabel: "Reset Defaults",
  onLabel: "On",
  offLabel: "Off",
  themeAccentColor: "#2f6fed",
  themeAccentStrongColor: "#3c7bff",
  themeOnAccentColor: "#fff",
  themeFocusColor: "#9ecaff",
  themeSelectedSurfaceColor: "#26384f",
  themeSelectedSurfaceHoverColor: "#2d4565",
  themeSelectedRingColor: "rgba(47, 111, 237, .35)",
  themeTimelineFillColor: "#fff",
  themeTimelineTrackColor: "rgba(255, 255, 255, .28)",
  themeBufferingColor: "#fff",
  themeBufferingTrackColor: "rgba(255, 255, 255, .28)",
  themeControlForegroundColor: "#fff",
  isPlaying: false,
  isLoading: true,
  isLocked: false,
  lockedOverlayVisible: false,
  controlsVisible: true,
  mouseMoveRevealsControlsEnabled: false,
  legacyHudEnabled: false,
  alwaysShowClock: false,
  appFullscreenKeyCode: 122,
  playerShortcutKeyCodes: {},
  uiScalePercent: 0,
  parentalWarnings: [],
  showParentalGuide: false,
  showOpeningOverlay: false,
  openingArtwork: "",
  openingLogo: "",
  openingTitle: "",
  openingMessage: "",
  openingProgress: null,
  skipPromptVisible: false,
  skipPromptLabel: "Skip",
  skipPromptStartMs: 0,
  skipPromptEndMs: 0,
  skipPromptDismissed: false,
  nextEpisodeVisible: false,
  nextEpisodeHeaderLabel: "Next episode",
  nextEpisodeTitle: "",
  nextEpisodeThumbnail: "",
  nextEpisodeStatus: "",
  nextEpisodeActionLabel: "Play",
  nextEpisodePlayable: false,
  showSubmitIntro: false,
  showVideoSettings: false,
  showSources: false,
  showEpisodes: false,
  showExternalPlayer: false,
  durationMs: 0,
  positionMs: 0,
  chapters: [],
  audioTracks: [],
  subtitleTracks: [],
  sourceIsLoading: false,
  sourceBadgePlacement: "bottom",
  sourceFilters: [],
  sourceItems: [],
  episodeItems: [],
  episodeSeasons: [],
  episodeStreamsVisible: false,
  episodeStreamsIsLoading: false,
  selectedEpisodeLabel: "",
  episodeStreamFilters: [],
  episodeStreamItems: [],
  submitIntroSegmentType: "intro",
  submitIntroStartTime: "00:00",
  submitIntroEndTime: "00:00",
  isSubmitIntroSubmitting: false,
  submitIntroStatusMessage: "",
  showP2pConsent: false,
  subtitleActiveTab: "BuiltIn",
  addonSubtitleItems: [],
  // When true, render the app-pushed (preferred-language-filtered) built-in list instead of the
  // live native track list, so "Show Only Preferred Languages" applies to embedded subs too.
  builtInSubtitleFilterActive: false,
  builtInSubtitleItems: [],
  isLoadingAddonSubtitles: false,
  selectedAddonSubtitleId: "",
  useCustomSubtitles: false,
  subtitleDelayMs: 0,
  hasSelectedAddonSubtitle: false,
  subtitleAutoSyncCapturedPositionMs: -1,
  subtitleAutoSyncCues: [],
  subtitleAutoSyncIsLoading: false,
  subtitleAutoSyncErrorMessage: "",
  subtitleStyle: {
    textColor: "#FFFFFFFF",
    outlineColor: "#FF000000",
    outlineEnabled: true,
    bold: false,
    fontSizeSp: 18,
    bottomOffset: 20,
    fontFamily: "",
  },
  subtitleFontFamilies: [],
  subtitleColorSwatches: [],
  closeModalsToken: 0,
};
let isScrubbing = false;
let scrubPositionMs = 0;
let tapTimer = 0;
let activeModal = "";
let pressedButton = null;
let sourceFilterId = "";
let sourceVirtualKey = "";
let sourceVirtualItems = [];
let sourceVirtualHeights = [];
let sourceVirtualOffsets = [];
let sourceVirtualTotalHeight = 0;
let sourceVirtualSpacer = null;
let sourceVirtualRenderRaf = 0;
let selectedEpisodeSeason = null;
let episodeStreamFilterId = "";
let keyboardPanelMode = "";
let keyboardSourceIndex = 0;
let keyboardEpisodeIndex = 0;
let keyboardEpisodeStreamIndex = 0;
let keyboardEpisodeShowingStreams = false;
let episodeListRenderKey = "";
let episodeFocusPositionKey = "";
const episodeArtworkPreloads = new Map();
let submitIntroDraft = {
  segmentType: "intro",
  startTime: "00:00",
  endTime: "00:00",
  status: "",
};
let hasReceivedPlayerControls = false;
let parentalGuideRunId = 0;
let parentalGuideStartedKey = "";
let parentalGuideCompletedKey = "";
let skipPromptKey = "";
let skipPromptWasDismissed = false;
let skipPromptAutoHidden = false;
let skipPromptAutoHideTimer = 0;
let skipPromptAutoHideActive = false;
let pauseMetadataReady = false;
let pauseMetadataTimer = 0;
let pauseMetadataEligibilityKey = "";
let chromeAutoHideTimer = 0;
let chromeAutoHideKey = "";
let chromeAutoHideActivity = 0;
let chromeInteractionLastNotedAt = 0;
let isChromePointerInside = false;
let isChromePointerDown = false;
let isChromeFocusInside = false;
let hostChromeInteractionActive = false;
let nativeViewportTimer = 0;

// User UI-scale knob (desktopUiScalePercent, -50..+50). Native player hosts apply it as browser
// page zoom so viewport reflow, media queries, text, spacing, and pointer coordinates scale as one.
// The CSS scale variables remain at 1 for legacy measurement sites.
let appliedUiScalePercent = null;
let appliedCombinedUserScale = null;
// Cache the last emitted scale-variable signature. renderChrome calls applyUserUiScale on every
// state push (including position ticks), so guard the setProperty writes behind a change check to
// avoid needless style invalidation when nothing scale-related moved.
let appliedScaleSignature = "";
function applyUserUiScale(percent) {
  const nextPercent = Math.max(-50, Math.min(50, Math.round(Number(percent) || 0)));
  if (nextPercent !== appliedUiScalePercent) {
    appliedUiScalePercent = nextPercent;
    send("setControlsUiScalePercent", nextPercent);
  }
  updateViewportUiScale();
}

function updateViewportUiScale() {
  const userScale = 1;
  // The native WebView zoom is now the only scale. Keeping these multipliers neutral avoids the
  // old double-scaling path while preserving the variables consumed throughout the stylesheet.
  const autoScale = 1;
  const combined = userScale * autoScale;

  const panelScale = 1;

  const feedbackViewportScale = 1;
  const feedbackScale = feedbackViewportScale * userScale;

  const signature = `${combined}|${panelScale}|${feedbackScale}`;
  if (signature === appliedScaleSignature) return;
  appliedScaleSignature = signature;
  appliedCombinedUserScale = combined;

  const rootStyle = document.documentElement.style;
  rootStyle.setProperty("--user-scale", String(combined));
  rootStyle.setProperty("--panel-scale", String(panelScale));
  rootStyle.setProperty("--feedback-scale", String(feedbackScale));
  rootStyle.setProperty("--feedback-hidden-scale", String(feedbackScale * 0.85));
}

const prefersReducedMotion = window.matchMedia &&
  window.matchMedia("(prefers-reduced-motion: reduce)").matches;
const modalTransitionMs = prefersReducedMotion ? 1 : 240;
const chromeAutoHideDelayMs = 5500;
const chromeActivityThrottleMs = 300;
const chromeInteractionSelector = [
  "button",
  "input",
  "textarea",
  "select",
  "[contenteditable='true']",
  // The whole top band counts as chrome so clicks on the title/metadata/empty header space don't
  // fall through to the video surface (toggling playback or, on a double-click, fullscreen).
  // .metadata is position:fixed in the legacy HUD but is still a DOM descendant of .header.
  ".header",
  ".metadata",
  ".header-actions",
  ".center-controls",
  ".progress",
  ".locked-overlay",
  ".modal-layer",
  ".skip-prompt",
  ".next-episode-card",
  "#heroTrailerChrome",
].join(",");

const send = (type, value = 0) => {
  const bridge = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.player;
  if (bridge) {
    bridge.postMessage({ type, value });
    return;
  }
  const webViewBridge = window.chrome && window.chrome.webview;
  if (webViewBridge) webViewBridge.postMessage({ type, value });
};

const animationDelay = ms => new Promise(resolve => {
  window.setTimeout(resolve, prefersReducedMotion ? 1 : ms);
});

const normalizedParentalWarnings = () =>
  Array.isArray(state.parentalWarnings)
    ? state.parentalWarnings
        .map(warning => ({
          label: String(warning && warning.label || "").trim(),
          severity: String(warning && warning.severity || "").trim(),
        }))
        .filter(warning => warning.label || warning.severity)
        .slice(0, 5)
    : [];

const parentalWarningKey = warnings =>
  warnings.map(warning => `${warning.label}\u0000${warning.severity}`).join("\u0001");

const hideParentalGuide = () => {
  root.classList.remove("parental-visible", "parental-line-visible");
  parentalGuide.setAttribute("aria-hidden", "true");
  parentalGuideList.querySelectorAll(".parental-guide-row").forEach(row => {
    row.classList.remove("visible");
  });
};

const renderParentalGuideRows = warnings => {
  parentalGuideList.innerHTML = "";
  const rowHeight = 18;
  const rowGap = 2;
  const totalHeight = warnings.length > 0
    ? (rowHeight * warnings.length) + (rowGap * (warnings.length - 1))
    : 0;
  parentalGuideLine.style.height = `${totalHeight}px`;

  warnings.forEach(warning => {
    const row = document.createElement("div");
    row.className = "parental-guide-row";

    const label = document.createElement("span");
    label.className = "parental-guide-label";
    label.textContent = warning.label;

    const separator = document.createElement("span");
    separator.className = "parental-guide-separator";
    separator.textContent = " · ";

    const severity = document.createElement("span");
    severity.className = "parental-guide-severity";
    severity.textContent = warning.severity;

    row.appendChild(label);
    row.appendChild(separator);
    row.appendChild(severity);
    parentalGuideList.appendChild(row);
  });
};

const runParentalGuideAnimation = async (warnings, key, runId) => {
  renderParentalGuideRows(warnings);
  parentalGuide.setAttribute("aria-hidden", "false");
  root.classList.add("parental-visible");
  await animationDelay(300);
  if (runId !== parentalGuideRunId) return;

  root.classList.add("parental-line-visible");
  await animationDelay(400);
  if (runId !== parentalGuideRunId) return;

  const rows = Array.from(parentalGuideList.querySelectorAll(".parental-guide-row"));
  for (const row of rows) {
    await animationDelay(80);
    if (runId !== parentalGuideRunId) return;
    row.classList.add("visible");
    await animationDelay(200);
    if (runId !== parentalGuideRunId) return;
  }

  await animationDelay(5000);
  if (runId !== parentalGuideRunId) return;

  for (const row of rows.slice().reverse()) {
    await animationDelay(60);
    if (runId !== parentalGuideRunId) return;
    row.classList.remove("visible");
    await animationDelay(150);
    if (runId !== parentalGuideRunId) return;
  }

  await animationDelay(100);
  if (runId !== parentalGuideRunId) return;
  root.classList.remove("parental-line-visible");

  await animationDelay(300);
  if (runId !== parentalGuideRunId) return;

  await animationDelay(200);
  if (runId !== parentalGuideRunId) return;
  root.classList.remove("parental-visible");
  await animationDelay(300);
  if (runId !== parentalGuideRunId) return;
  parentalGuide.setAttribute("aria-hidden", "true");
  parentalGuideCompletedKey = key;
  send("parentalGuideComplete", 0);
};

const syncParentalGuide = showOpening => {
  const warnings = normalizedParentalWarnings();
  const shouldShow = Boolean(state.showParentalGuide && warnings.length && !showOpening && !state.isLocked);
  if (!shouldShow) {
    parentalGuideRunId += 1;
    parentalGuideStartedKey = "";
    if (!state.showParentalGuide) {
      parentalGuideCompletedKey = "";
    }
    hideParentalGuide();
    return;
  }

  const key = parentalWarningKey(warnings);
  if (parentalGuideStartedKey === key || parentalGuideCompletedKey === key) return;
  parentalGuideStartedKey = key;
  parentalGuideRunId += 1;
  runParentalGuideAnimation(warnings, key, parentalGuideRunId);
};

const cssColorOrFallback = (value, fallback) => {
  const text = String(value || "").trim();
  return /^(#[0-9a-fA-F]{3,8}|rgba?\([^)]+\))$/.test(text) ? text : fallback;
};

const applyTheme = () => {
  const style = document.documentElement.style;
  const setColor = (name, value, fallback) => {
    style.setProperty(name, cssColorOrFallback(value, fallback));
  };
  setColor("--theme-accent", state.themeAccentColor, "#2f6fed");
  setColor("--theme-accent-strong", state.themeAccentStrongColor, "#3c7bff");
  setColor("--theme-on-accent", state.themeOnAccentColor, "#fff");
  setColor("--theme-focus", state.themeFocusColor, "#9ecaff");
  setColor("--theme-selected-surface", state.themeSelectedSurfaceColor, "#26384f");
  setColor("--theme-selected-surface-hover", state.themeSelectedSurfaceHoverColor, "#2d4565");
  setColor("--theme-selected-ring", state.themeSelectedRingColor, "rgba(47, 111, 237, .35)");
  setColor("--theme-timeline-fill", state.themeTimelineFillColor, "#fff");
  setColor("--theme-timeline-track", state.themeTimelineTrackColor, "rgba(255, 255, 255, .28)");
  setColor("--theme-buffering", state.themeBufferingColor, "#fff");
  setColor("--theme-buffering-track", state.themeBufferingTrackColor, "rgba(255, 255, 255, .28)");
  setColor("--theme-control-foreground", state.themeControlForegroundColor, "#fff");
};

const formatTime = milliseconds => {
  if (!Number.isFinite(milliseconds) || milliseconds < 0) milliseconds = 0;
  const total = Math.floor(milliseconds / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return h > 0
    ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`
    : `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
};

const setProgress = (positionMs, durationMs) => {
  const percent = durationMs > 0 ? Math.max(0, Math.min(100, positionMs / durationMs * 100)) : 0;
  seek.value = Math.round(percent * 10);
  seek.style.setProperty("--progress", `${percent}%`);
  // Buffered (demuxer-cached ahead) end, never drawn behind the played fill.
  const bufferedMs = Math.max(0, Number(state.bufferedMs) || 0);
  const bufferedPercent = durationMs > 0
    ? Math.max(percent, Math.min(100, bufferedMs / durationMs * 100))
    : 0;
  seek.style.setProperty("--buffered", `${bufferedPercent}%`);
  if (pipSeek) {
    pipSeek.value = seek.value;
    pipSeek.style.setProperty("--progress", `${percent}%`);
    pipSeek.style.setProperty("--buffered", `${bufferedPercent}%`);
  }
  positionLabel.textContent = formatTime(positionMs);
  durationLabel.textContent = formatTime(durationMs);
};

let chapterMarkersSignature = "";
const normalizedChapters = () => (Array.isArray(state.chapters) ? state.chapters : [])
  .map(chapter => ({
    startTime: Number(chapter?.startTime),
    title: String(chapter?.title || "").trim(),
  }))
  .filter(chapter => Number.isFinite(chapter.startTime) && chapter.startTime >= 0 && chapter.title)
  .sort((a, b) => a.startTime - b.startTime);

// A single chapter conveys no navigational meaning, so only surface chapter names
// (seek-preview label and hover tooltip) when the file actually has multiple chapters.
const displayChapters = () => {
  const chapters = normalizedChapters();
  return chapters.length > 1 ? chapters : [];
};

const renderChapterMarkers = durationMs => {
  const chapters = normalizedChapters();
  const signature = `${Math.round(durationMs)}:${chapters.map(chapter => `${chapter.startTime}:${chapter.title}`).join("|")}`;
  if (signature === chapterMarkersSignature) return;
  chapterMarkersSignature = signature;
  chapterMarkers.textContent = "";
  if (durationMs <= 0) return;
  chapters.forEach(chapter => {
    if (chapter.startTime <= 0 || chapter.startTime * 1000 >= durationMs) return;
    const marker = document.createElement("span");
    marker.className = "chapter-marker";
    marker.style.left = `${Math.max(0, Math.min(100, chapter.startTime * 1000 / durationMs * 100))}%`;
    chapterMarkers.appendChild(marker);
  });
};

const hideChapterTooltip = () => {
  chapterTooltip.hidden = true;
  chapterTooltip.textContent = "";
};

const seekThumbnailCache = new Map();
let seekThumbnailRequestTimer = 0;
let pendingSeekThumbnailPosition = -1;

const hideSeekThumbnail = () => {
  window.clearTimeout(seekThumbnailRequestTimer);
  seekThumbnailRequestTimer = 0;
  pendingSeekThumbnailPosition = -1;
  seekThumbnail.hidden = true;
};

const showSeekThumbnailAt = event => {
  const durationMs = Math.max(0, Number(state.durationMs) || 0);
  const rect = seek.getBoundingClientRect();
  if (durationMs <= 0 || rect.width <= 0) return hideSeekThumbnail();
  const progress = Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width));
  const exactPositionMs = Math.round(durationMs * progress);
  const thumbnailPositionMs = Math.round(exactPositionMs / 5000) * 5000;
  const localX = Math.max(112, Math.min(rect.width - 112, event.clientX - rect.left));
  seekThumbnail.style.left = `${rect.left - timeline.getBoundingClientRect().left + localX}px`;
  seekThumbnailTime.textContent = formatTime(exactPositionMs);
  const positionSeconds = exactPositionMs / 1000;
  const chapters = displayChapters();
  const chapter = chapters.find((candidate, index) => {
    const nextStart = chapters[index + 1]?.startTime ?? Number.POSITIVE_INFINITY;
    return positionSeconds >= candidate.startTime && positionSeconds < nextStart;
  });
  seekThumbnailChapter.textContent = chapter?.title || "";
  seekThumbnailChapter.hidden = !chapter;
  const cached = seekThumbnailCache.get(thumbnailPositionMs);
  if (cached) {
    seekThumbnailImage.src = cached;
    seekThumbnail.hidden = false;
    return;
  }
  seekThumbnailImage.removeAttribute("src");
  seekThumbnail.hidden = false;
  if (pendingSeekThumbnailPosition === thumbnailPositionMs) return;
  pendingSeekThumbnailPosition = thumbnailPositionMs;
  window.clearTimeout(seekThumbnailRequestTimer);
  seekThumbnailRequestTimer = window.setTimeout(() => {
    send("seekThumbnail", thumbnailPositionMs);
  }, 24);
};

window.nuvioSeekThumbnailReady = (positionMs, dataUrl) => {
  const position = Number(positionMs) || 0;
  const url = String(dataUrl || "");
  if (!url) return;
  seekThumbnailCache.set(position, url);
  while (seekThumbnailCache.size > 36) {
    seekThumbnailCache.delete(seekThumbnailCache.keys().next().value);
  }
  if (pendingSeekThumbnailPosition === position) {
    seekThumbnailImage.src = url;
    seekThumbnail.hidden = false;
  }
};

const showChapterTooltipAt = event => {
  const durationMs = Math.max(0, Number(state.durationMs) || 0);
  const chapters = displayChapters();
  if (durationMs <= 0 || chapters.length === 0) return hideChapterTooltip();
  const rect = seek.getBoundingClientRect();
  if (rect.width <= 0) return hideChapterTooltip();
  const progress = Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width));
  const positionSeconds = durationMs / 1000 * progress;
  let chapter = null;
  for (let index = 0; index < chapters.length; index += 1) {
    const candidate = chapters[index];
    const nextStart = chapters[index + 1]?.startTime ?? Number.POSITIVE_INFINITY;
    if (positionSeconds >= candidate.startTime && positionSeconds < nextStart) {
      chapter = candidate;
      break;
    }
  }
  if (!chapter) return hideChapterTooltip();
  const timelineRect = timeline.getBoundingClientRect();
  const left = Math.max(4, Math.min(96, (event.clientX - timelineRect.left) / timelineRect.width * 100));
  chapterTooltip.textContent = chapter.title;
  chapterTooltip.style.left = `${left}%`;
  chapterTooltip.hidden = false;
};

const setText = (element, text) => {
  element.textContent = text || "";
  element.hidden = !text;
};

const normalizeEpisodeDisplayText = value => {
  const text = String(value || "").trim();
  let firstCode = null;
  const cleaned = text.replace(
    /(?:S(?:eason)?\s*0*(\d+)\s*E(?:pisode)?\s*0*(\d+)|0*(\d+)\s*x\s*0*(\d+))/gi,
    (match, seasonA, episodeA, seasonB, episodeB) => {
      const code = `${Number(seasonA || seasonB)}:${Number(episodeA || episodeB)}`;
      if (!firstCode) {
        firstCode = code;
        return match;
      }
      return code === firstCode ? "" : match;
    },
  );
  return cleaned
    .replace(/([•·|\-])\s*([•·|\-])/g, "$1")
    .replace(/\s{2,}/g, " ")
    .replace(/\s+([•·|])/g, " $1")
    .replace(/([•·|])\s*$/g, "")
    .trim();
};

const setVisible = (element, visible) => {
  element.hidden = !visible;
};

const setImageVisualState = (element, stateName) => {
  const frame = element.parentElement;
  [element, frame].filter(Boolean).forEach(target => {
    target.classList.remove("image-loading", "image-loaded", "image-error");
    if (stateName) target.classList.add(`image-${stateName}`);
  });
};

const setImageSource = (element, source) => {
  const url = String(source || "").trim();
  if (!url) {
    element.removeAttribute("src");
    element.removeAttribute("data-loaded-src");
    setImageVisualState(element, "");
    return "";
  }
  const currentUrl = element.getAttribute("src") || "";
  const loadedUrl = element.getAttribute("data-loaded-src") || "";
  if (currentUrl !== url) {
    element.setAttribute("decoding", "async");
    setImageVisualState(element, "loading");
    element.onload = () => {
      if (element.getAttribute("src") !== url) return;
      element.setAttribute("data-loaded-src", url);
      window.requestAnimationFrame(() => setImageVisualState(element, "loaded"));
    };
    element.onerror = () => {
      if (element.getAttribute("src") !== url) return;
      element.removeAttribute("data-loaded-src");
      setImageVisualState(element, "error");
      if (element.closest(".episode-thumb") && element.getAttribute("data-retried-src") !== url) {
        element.setAttribute("data-retried-src", url);
        window.setTimeout(() => {
          if (element.getAttribute("src") !== url) return;
          element.removeAttribute("src");
          setImageSource(element, url);
        }, 700);
      }
    };
    element.setAttribute("src", url);
    if (element.complete && element.naturalWidth > 0) {
      element.onload();
    }
  } else if (loadedUrl === url) {
    setImageVisualState(element, "loaded");
  }
  return url;
};

const resetPauseMetadataTimer = () => {
  window.clearTimeout(pauseMetadataTimer);
  pauseMetadataTimer = 0;
  pauseMetadataReady = false;
  pauseMetadataEligibilityKey = "";
};

const syncPauseMetadataTimer = showOpening => {
  const durationMs = Math.max(0, Number(state.durationMs) || 0);
  const eligible = Boolean(!state.isPlaying && !state.isLoading && durationMs > 0 && !showOpening);
  const key = eligible ? `${Math.round(durationMs)}:${state.title || ""}:${state.pauseOverlayEpisodeInfo || ""}` : "";
  if (!eligible) {
    resetPauseMetadataTimer();
    return;
  }
  if (pauseMetadataEligibilityKey === key) return;
  window.clearTimeout(pauseMetadataTimer);
  pauseMetadataReady = false;
  pauseMetadataEligibilityKey = key;
  pauseMetadataTimer = window.setTimeout(() => {
    pauseMetadataTimer = 0;
    pauseMetadataReady = true;
    renderChrome();
  }, prefersReducedMotion ? 1 : 5000);
};

const renderPauseMetadataOverlay = showOpening => {
  syncPauseMetadataTimer(showOpening);

  const logoUrl = setImageSource(pauseLogo, state.pauseOverlayLogo);
  const titleText = String(state.title || "").trim();
  const episodeInfo = String(state.pauseOverlayEpisodeInfo || "").trim();
  const episodeTitleText = String(state.pauseOverlayEpisodeTitle || "").trim();
  const descriptionText = String(state.pauseOverlayDescription || "").trim();
  const showOverlay = Boolean(
    pauseMetadataReady &&
    !state.controlsVisible &&
    !state.isLocked &&
    !activeModal &&
    !showOpening,
  );

  pauseWatchingLabel.textContent = state.pauseOverlayWatchingLabel || "You're watching";
  pauseLogo.hidden = !logoUrl;
  pauseTitle.textContent = titleText;
  pauseTitle.hidden = Boolean(logoUrl || !titleText);
  pauseEpisodeInfo.textContent = episodeInfo;
  pauseEpisodeInfo.hidden = !episodeInfo;
  pauseEpisodeTitle.textContent = episodeTitleText;
  pauseEpisodeTitle.hidden = !episodeTitleText;
  pauseDescription.textContent = descriptionText;
  pauseDescription.hidden = !descriptionText;
  pauseMetadataOverlay.classList.toggle("visible", showOverlay);
  pauseMetadataOverlay.setAttribute("aria-hidden", showOverlay ? "false" : "true");
  // The clock is part of the chrome that hides when the pause overlay appears; flag the overlay so
  // CSS keeps the clock (top-right) visible above it instead of fading out with everything else.
  root.classList.toggle("pause-overlay-visible", showOverlay);
};

const suppressPauseMetadataForPlaybackInteraction = () => {
  resetPauseMetadataTimer();
  pauseMetadataOverlay.classList.remove("visible");
  pauseMetadataOverlay.setAttribute("aria-hidden", "true");
  root.classList.remove("pause-overlay-visible");
};

const normalizedOpeningProgress = () => {
  const progress = Number(state.openingProgress);
  return Number.isFinite(progress) ? Math.max(0, Math.min(1, progress)) : null;
};

const playbackErrorText = () => String(state.playbackErrorMessage || "").trim();

const rangePositionMs = (input = seek) => {
  const durationMs = Math.max(0, Number(state.durationMs) || 0);
  return durationMs > 0 ? Math.round(durationMs * Number(input.value) / 1000) : 0;
};

const modalByName = {
  audio: audioModal,
  subtitles: subtitleModal,
  sources: sourceModal,
  episodes: episodesModal,
  submitIntro: submitIntroModal,
  p2pConsent: p2pConsentModal,
};
const modalElements = Object.values(modalByName);
const modalCloseTimers = new Map();

const setModalVisibility = (modal, visible, animated = true) => {
  const pendingTimer = modalCloseTimers.get(modal);
  if (pendingTimer) {
    window.clearTimeout(pendingTimer);
    modalCloseTimers.delete(modal);
  }
  if (visible) {
    modal.dataset.modalState = "open";
    modal.hidden = false;
    modal.classList.remove("modal-closing");
    window.requestAnimationFrame(() => {
      if (modal.dataset.modalState === "open") {
        modal.classList.add("modal-visible");
      }
    });
    return;
  }

  modal.dataset.modalState = "closed";
  modal.classList.remove("modal-visible");
  if (!animated || modal.hidden) {
    modal.hidden = true;
    modal.classList.remove("modal-closing");
    return;
  }

  modal.classList.add("modal-closing");
  const timer = window.setTimeout(() => {
    modalCloseTimers.delete(modal);
    if (modal.dataset.modalState === "closed") {
      modal.hidden = true;
      modal.classList.remove("modal-closing");
    }
  }, modalTransitionMs);
  modalCloseTimers.set(modal, timer);
};

const closePlayerModal = (notifyDismiss = false, animated = true) => {
  const closingModal = activeModal;
  activeModal = "";
  modalElements.forEach(modal => {
    setModalVisibility(modal, false, animated);
  });
  if (notifyDismiss && closingModal === "p2pConsent") {
    send("cancelP2pForPlayerControls", 0);
  }
  if (keyboardPanelMode && closingModal === keyboardPanelMode) {
    keyboardPanelMode = "";
    send("keyboardPanelClosed", 0);
  }
  renderChrome();
};

const openPlayerModal = modal => {
  const targetModal = modalByName[modal];
  if (!targetModal) {
    closePlayerModal(false);
    return;
  }
  if (keyboardPanelMode && modal !== keyboardPanelMode) {
    keyboardPanelMode = "";
    send("keyboardPanelClosed", 0);
  }
  activeModal = modal;
  if (modal === "submitIntro") {
    submitIntroDraft = {
      segmentType: state.submitIntroSegmentType || "intro",
      startTime: state.submitIntroStartTime || "00:00",
      endTime: state.submitIntroEndTime || "00:00",
      status: "",
    };
  }
  renderActiveModal();
  modalElements.forEach(modalElement => {
    setModalVisibility(modalElement, modalElement === targetModal);
  });
  renderChrome();
};

const parsedPlaybackSpeed = () => {
  const parsed = Number.parseFloat(String(state.playbackSpeedLabel || "1").replace(/[^0-9.]/g, ""));
  return Number.isFinite(parsed) ? parsed : 1;
};

const playbackSpeedMenuItem = (speed, fineSpeeds = []) => ({
  label: `${speed.toFixed(1).replace(/\.0$/, "")}x`,
  action: `speed:${speed.toFixed(1)}`,
  selectedField: "playbackSpeedValue",
  selectedValue: speed.toFixed(1),
  ...(fineSpeeds.length > 0 ? {
    children: fineSpeeds.map(fineSpeed => ({
      label: `${fineSpeed.toFixed(1).replace(/\.0$/, "")}x`,
      action: `speed:${fineSpeed.toFixed(1)}`,
      selectedField: "playbackSpeedValue",
      selectedValue: fineSpeed.toFixed(1),
    })),
  } : {}),
});

// Standard half-step speeds are immediate click targets. Hovering opens only the nearby tenths,
// avoiding the former range-of-ranges spiderweb while retaining precise control when wanted.
const playbackSpeedMenuItems = [
  playbackSpeedMenuItem(1.0, [1.1, 1.2, 1.3, 1.4]),
  playbackSpeedMenuItem(1.5, [1.6, 1.7, 1.8, 1.9]),
  playbackSpeedMenuItem(2.0, [2.1, 2.2, 2.3, 2.4]),
  playbackSpeedMenuItem(2.5, [2.6, 2.7, 2.8, 2.9]),
  playbackSpeedMenuItem(3.0, [3.1, 3.2, 3.3, 3.4]),
  playbackSpeedMenuItem(3.5, [3.6, 3.7, 3.8, 3.9]),
  playbackSpeedMenuItem(4.0),
];

// Player HUD UI-scale presets (-50..+50%, matching the Playback settings slider). Exposed in the
// right-click menu for quick testing; each preset checkmarks against the live uiScalePercent and
// the two nudge rows step by the same 5% as the slider.
const uiScalePresetItems = [{ label: "Increase (+5%)", action: "uiScaleDelta:5" },
  { label: "Decrease (−5%)", action: "uiScaleDelta:-5" }];
for (let pct = 50; pct >= -50; pct -= 10) {
  uiScalePresetItems.push({
    label: pct === 0 ? "Default (0%)" : `${pct > 0 ? "+" : ""}${pct}%`,
    action: `uiScaleSet:${pct}`,
    selectedField: "uiScalePercentValue",
    selectedValue: String(pct),
  });
}

const contextMenuItems = [
  {
    label: "Playback",
    children: [
      { label: "Play / Pause", action: "send:toggle", shortcut: "K" },
      { label: "Seek back 10 seconds", action: "send:seekBack", shortcut: "←" },
      { label: "Seek forward 10 seconds", action: "send:seekForward", shortcut: "→" },
      { label: "Playback speed", children: playbackSpeedMenuItems },
      { label: "Previous episode", action: "send:previousEpisode" },
      { label: "Next episode", action: "send:nextEpisode" },
      { label: "Picture in picture", action: "send:pictureInPicture" },
    ],
  },
  {
    label: "Subtitles",
    children: [
      { label: "Built-in", dynamicKey: "subtitleTracks", children: [] },
      { label: "Addon", dynamicKey: "addonSubtitles", children: [] },
      {
        label: "Style",
        children: [
          { label: "Outline", action: "subtitleStyle:outline", toggleKey: "outlineEnabled" },
          { label: "Shadow", action: "subtitleStyle:shadow", toggleKey: "shadowEnabled" },
          { label: "Bold", action: "subtitleStyle:bold", toggleKey: "bold" },
          { label: "Font", dynamicKey: "fontFamilies", children: [] },
          { label: "Text color", dynamicKey: "subtitleColors", children: [] },
          { label: "Outline color", dynamicKey: "outlineColors", children: [] },
          { label: "Font size", children: [
            { label: "Increase", action: "send:subtitleFontSizeDelta:2" },
            { label: "Decrease", action: "send:subtitleFontSizeDelta:-2" },
          ] },
          { label: "Bottom offset", children: [
            { label: "Increase", action: "send:subtitleBottomOffsetDelta:5" },
            { label: "Decrease", action: "send:subtitleBottomOffsetDelta:-5" },
          ] },
          { label: "Text opacity", children: [
            { label: "Increase", action: "subtitleOpacity:10" },
            { label: "Decrease", action: "subtitleOpacity:-10" },
          ] },
          { label: "Open style panel", action: "subtitleTab:2" },
          { label: "Reset style defaults", action: "send:subtitleStyleReset" },
        ],
      },
      // Auto sync needs its full card (status text + cue list) to be usable, so the menu
      // entry opens the style panel where it lives instead of firing blind Reload/Capture.
      { label: "Auto sync", action: "subtitleTab:2" },
      { label: "Subtitle delay", children: [
        { label: "Increase", action: "send:subtitleDelayDelta:100" },
        { label: "Decrease", action: "send:subtitleDelayDelta:-100" },
        { label: "Reset", action: "send:subtitleDelayReset" },
      ] },
    ],
  },
  {
    label: "Audio",
    children: [
      { label: "Audio tracks", dynamicKey: "audioTracks", children: [] },
      { label: "Mute", action: "send:keyboardToggleMute", toggleKey: "localMuted" },
    ],
  },
  {
    label: "Video",
    children: [
      { label: "Aspect ratio", children: [
        { label: "Fit", action: "resizeMode:0", selectedField: "resizeModeLabel" },
        { label: "Fill", action: "resizeMode:1", selectedField: "resizeModeLabel" },
        { label: "Zoom", action: "resizeMode:2", selectedField: "resizeModeLabel" },
      ] },
      { label: "Video settings", children: [
        { label: "Auto", action: "send:selectDesktopHdrMode:0", selectedField: "desktopHdrModeLabel" },
        { label: "Always Tonemap", action: "send:selectDesktopHdrMode:1", selectedField: "desktopHdrModeLabel" },
        { label: "Always Passthrough", action: "send:selectDesktopHdrMode:2", selectedField: "desktopHdrModeLabel" },
      ] },
      { label: "Color profile", children: [
        { label: "Neutral", action: "send:selectDesktopColorProfile:0", selectedField: "desktopColorProfileLabel" },
        { label: "Cinematic", action: "send:selectDesktopColorProfile:1", selectedField: "desktopColorProfileLabel" },
        { label: "Vivid", action: "send:selectDesktopColorProfile:2", selectedField: "desktopColorProfileLabel" },
      ] },
      { label: "Anime shader", dynamicKey: "animeShaders", children: [] },
      { label: "SVP interpolation", action: "send:keyboardCycleAnimeSvp", toggleKey: "desktopAnimeSvpEnabled" },
      { label: "Advanced (mpv)", dynamicKey: "mpvOptions", children: [] },
      { label: "UI scale", children: uiScalePresetItems },
    ],
  },
  {
    label: "Tools",
    children: [
      { label: "Sources", action: "modal:sources" },
      { label: "Episodes", action: "modal:episodes" },
      { label: "Submit intro / outro", action: "modal:submitIntro" },
    ],
  },
  {
    label: "Window",
    children: [
      { label: "Fullscreen", action: "send:toggleFullscreen", shortcut: "F11" },
      { label: "Close playback", action: "send:back", shortcut: "Esc" },
    ],
  },
  { label: "Copy stream link", action: "copyStreamUrl" },
  { label: "Stream failover", action: "toggleStreamFailover", toggleKey: "streamFailoverEnabled" },
  { label: "MPV diagnostics", action: "toggleMpvDiagnostics", toggleKey: "mpvDiagnosticsEnabled" },
  { label: "Close menu after selecting", action: "toggleCloseOnSelect", toggleKey: "closeMenuOnSelect" },
];

let contextMenuOpen = false;
let consumeContextMenuDismissalClick = false;
let contextMenuDismissalClickTimer = 0;
let localVolume = 100;
let localMuted = false;
let mpvDiagnosticsEnabled = false;
const contextMenuDynamicSubmenus = new Map();

// Single toggle path for the MPV diagnostics overlay: used by the context-menu entry and
// invoked directly from Kotlin for the rebindable keyboard shortcut, so the menu indicator,
// chrome class, pill, and the Kotlin-side stats overlay all stay in sync.
window.nuvioToggleMpvDiagnostics = () => {
  mpvDiagnosticsEnabled = !mpvDiagnosticsEnabled;
  renderChrome();
  refreshContextMenuIndicators();
  window.nuvioShowPresetPill("MPV diagnostics", mpvDiagnosticsEnabled ? "On" : "Off");
  send("toggleMpvDiagnostics", mpvDiagnosticsEnabled ? 1 : 0);
};

// When false, selecting an in-menu adjustment (toggle, colour, size, track…) keeps the
// context menu open so several tweaks can be made without re-opening it each time.
// Actions that navigate to another surface (a modal, fullscreen, closing playback) always
// close the menu regardless of this preference. Persisted so the choice survives restarts.
let closeMenuOnSelect = (() => {
  try {
    return window.localStorage.getItem("nuvioContextMenuCloseOnSelect") !== "false";
  } catch (error) {
    return true;
  }
})();

const setCloseMenuOnSelect = value => {
  closeMenuOnSelect = Boolean(value);
  try {
    window.localStorage.setItem("nuvioContextMenuCloseOnSelect", closeMenuOnSelect ? "true" : "false");
  } catch (error) {
    /* storage unavailable — keep the in-memory value */
  }
};

const contextMenuValue = key => {
  if (key === "localMuted") return localMuted;
  if (key === "mpvDiagnosticsEnabled") return mpvDiagnosticsEnabled;
  if (key === "closeMenuOnSelect") return closeMenuOnSelect;
  if (key === "uiScalePercentValue") return String(Number(state.uiScalePercent) || 0);
  if (key === "playbackSpeedValue") return parsedPlaybackSpeed().toFixed(1);
  if (key === "outlineEnabled" || key === "shadowEnabled" || key === "bold") {
    return Boolean(state.subtitleStyle && state.subtitleStyle[key]);
  }
  return state[key];
};

// Immediately reflects a menu selection in local state so the indicator updates without waiting
// for the value to round-trip back from the player (which may be stalled while paused).
const applyOptimisticContextSelection = button => {
  const selectedField = button.dataset.selectedField;
  if (selectedField) {
    state = { ...state, [selectedField]: button.dataset.selectedValue };
    return;
  }
  const toggleKey = button.dataset.toggleKey;
  if (!toggleKey || toggleKey === "closeMenuOnSelect") return;
  if (toggleKey === "localMuted") {
    localMuted = !localMuted;
  } else if (toggleKey === "outlineEnabled" || toggleKey === "shadowEnabled" || toggleKey === "bold") {
    const style = { ...(state.subtitleStyle || {}) };
    style[toggleKey] = !style[toggleKey];
    state = { ...state, subtitleStyle: style };
  } else {
    state = { ...state, [toggleKey]: !contextMenuValue(toggleKey) };
  }
};

const refreshContextMenuIndicators = () => {
  if (!contextMenu) return;
  contextMenu.querySelectorAll(".context-menu-item[data-toggle-key], .context-menu-item[data-selected-field]")
    .forEach(button => {
      const toggleKey = button.dataset.toggleKey;
      const selectedField = button.dataset.selectedField;
      const indicator = button.querySelector(".context-menu-state");
      if (!indicator) return;
      if (toggleKey) {
        const enabled = Boolean(contextMenuValue(toggleKey));
        indicator.textContent = enabled ? (state.onLabel || "On") : (state.offLabel || "Off");
        indicator.classList.toggle("is-on", enabled);
      } else if (selectedField) {
        indicator.textContent = contextMenuValue(selectedField) === button.dataset.selectedValue ? "✓" : "";
      }
    });
};

const setContextMenuDynamicItems = (key, items) => {
  const submenu = contextMenuDynamicSubmenus.get(key);
  if (!submenu) return;
  submenu.textContent = "";
  const normalized = Array.isArray(items) ? items : [];
  const entries = key === "subtitleTracks"
    ? [{
        label: state.noneLabel || "None",
        action: "send:selectBuiltInSubtitleTrack:-1",
        selected: !normalized.some(item => Boolean(item.selected)),
      }, ...normalized]
    : normalized;
  buildContextMenu(entries.map((item, index) => ({
    label: String(item.label || item.display || item.languageLabel || item.title || "Option"),
    action: item.action || (key === "subtitleTracks"
      ? `send:selectBuiltInSubtitleTrack:${Number(item.index ?? index)}`
      : key === "addonSubtitles"
        ? `send:selectAddonSubtitle:${index}`
          : key === "audioTracks"
          ? `send:selectAudioTrack:${Number(item.index ?? index)}`
          : key === "fontFamilies"
            ? `send:subtitleFontIndex:${index}`
            : key === "subtitleColors"
              ? `send:subtitleTextColor:${index}`
              : key === "outlineColors"
                ? `send:subtitleOutlineColor:${index}`
          : `send:selectAnimeShader:${index}`),
    selected: Boolean(item.selected),
    swatch: item.swatch,
  })), submenu);
  if (normalized.length === 0) {
    const empty = document.createElement("div");
    empty.className = "context-menu-empty";
    empty.textContent = key === "addonSubtitles" ? "No addon subtitles loaded" : "No tracks available";
    submenu.appendChild(empty);
  }
  refreshContextMenuIndicators();
};

const closeContextMenu = () => {
  if (!contextMenuOpen) return;
  contextMenuOpen = false;
  if (contextMenu) contextMenu.hidden = true;
  // The menu suppressed chrome auto-hide while open; resume normal fading now.
  renderChrome();
  noteChromeActivity(true);
};

const openSubtitleContextTab = tab => {
  openPlayerModal("subtitles");
  send("subtitleTab", tab);
};

const cycleAspectFromControls = () => {
  const modes = ["Fit", "Fill", "Zoom"];
  const currentIndex = Math.max(0, modes.findIndex(mode => mode.toLowerCase() === String(state.resizeModeLabel || "Fit").toLowerCase()));
  const next = modes[(currentIndex + 1) % modes.length];
  state = { ...state, resizeModeLabel: next };
  if (resizeLabel) resizeLabel.textContent = next;
  window.nuvioShowPresetPill("Aspect ratio", next);
  send("resize", 0);
};

// Actions that navigate to a different surface should always dismiss the menu, even when the
// "close after selecting" preference is off (that preference only governs in-place tweaks).
const contextActionOpensSurface = action => {
  const kind = String(action || "").split(":")[0];
  if (kind === "modal" || kind === "subtitleTab") return true;
  return action === "send:back" ||
    action === "send:toggleFullscreen" ||
    action === "send:pictureInPicture" ||
    action === "send:videoSettings" ||
    action === "send:reloadSources";
};

const executeContextAction = action => {
  const parts = String(action || "").split(":");
  const kind = parts.shift();
  if (kind === "toggleCloseOnSelect") {
    setCloseMenuOnSelect(!closeMenuOnSelect);
    refreshContextMenuIndicators();
    return;
  }
  if (closeMenuOnSelect || contextActionOpensSurface(action)) {
    closeContextMenu();
  }
  if (kind === "send") {
    send(parts.shift(), Number(parts.shift() || 0));
    return;
  }
  if (kind === "resizeMode") {
    const index = Number(parts.shift() || 0);
    const label = ["Fit", "Fill", "Zoom"][index] || "Fit";
    state = { ...state, resizeModeLabel: label };
    if (resizeLabel) resizeLabel.textContent = label;
    refreshContextMenuIndicators();
    window.nuvioShowPresetPill("Aspect ratio", label);
    send("selectResizeMode", index);
    return;
  }
  if (kind === "copyStreamUrl") {
    window.nuvioShowPresetPill("Stream link", "Copied");
    send("copyStreamUrl", 0);
    return;
  }
  if (kind === "toggleMpvDiagnostics") {
    window.nuvioToggleMpvDiagnostics();
    return;
  }
  if (kind === "toggleStreamFailover") {
    // state.streamFailoverEnabled was already flipped optimistically; forward it to Kotlin, which
    // persists the setting and pushes the corrected value back.
    const enabled = Boolean(state.streamFailoverEnabled);
    refreshContextMenuIndicators();
    window.nuvioShowPresetPill("Stream failover", enabled ? "On" : "Off");
    send("toggleStreamFailover", enabled ? 1 : 0);
    return;
  }
  if (kind === "uiScaleSet" || kind === "uiScaleDelta") {
    const current = Number(state.uiScalePercent) || 0;
    const raw = kind === "uiScaleDelta" ? current + Number(parts.shift() || 0) : Number(parts.shift() || 0);
    const clamped = Math.max(-50, Math.min(50, Math.round(raw / 5) * 5));
    state = { ...state, uiScalePercent: clamped };
    applyUserUiScale(clamped);
    refreshContextMenuIndicators();
    window.nuvioShowPresetPill("UI scale", `${clamped > 0 ? "+" : ""}${clamped}%`);
    send("setDesktopUiScalePercent", clamped);
    return;
  }
  if (kind === "speed") {
    const next = Math.max(0.5, Math.min(4, Number(parts.shift() || 1)));
    const label = `${next.toFixed(1).replace(/\.0$/, "")}x`;
    state = { ...state, playbackSpeedLabel: label };
    speedLabel.textContent = label;
    refreshContextMenuIndicators();
    window.nuvioShowPresetPill("Playback speed", label);
    send("setPlaybackSpeed", next);
    return;
  }
  if (kind === "modal") {
    const modal = parts.shift();
    if (modal === "sources") {
      sourceFilterId = "";
      openPlayerModal("sources");
      send("sources", 0);
    } else if (modal === "episodes") {
      openPlayerModal("episodes");
      send("episodes", 0);
    } else {
      openPlayerModal(modal);
    }
    return;
  }
  if (kind === "subtitleTab") {
    openSubtitleContextTab(Number(parts.shift() || 0));
    return;
  }
  if (kind === "subtitleStyle") {
    // Toggle the style attribute in place — do not pop the style panel open.
    const command = parts.shift();
    if (command === "outline") send("subtitleOutlineToggle", 0);
    if (command === "shadow") send("subtitleShadowToggle", 0);
    if (command === "bold") send("subtitleBoldToggle", 0);
    return;
  }
  if (kind === "subtitleOpacity") {
    const currentAlpha = parseArgb((state.subtitleStyle || {}).textColor).alpha;
    const next = Math.max(0, Math.min(100, Math.round(currentAlpha / 255 * 100) + Number(parts.shift() || 0)));
    send("subtitleTextOpacity", next);
  }
};

// Positions a submenu flyout so it stays inside the viewport. Leaf submenus (plain lists with
// no nested flyouts of their own) may scroll when taller than the screen; container submenus are
// only shifted upward so their own nested flyouts are never clipped.
const positionSubmenu = group => {
  const submenu = group.querySelector(":scope > .context-menu-submenu");
  if (!submenu) return;
  const margin = 8;
  const hasNested = Boolean(submenu.querySelector(".context-menu-submenu"));
  submenu.style.top = "";
  submenu.style.maxHeight = "";
  submenu.style.overflowY = "";
  if (!hasNested) {
    submenu.style.maxHeight = `${Math.max(120, window.innerHeight - margin * 2)}px`;
    submenu.style.overflowY = "auto";
  }
  const rect = submenu.getBoundingClientRect();
  if (rect.height <= 0) return;
  if (rect.bottom > window.innerHeight - margin) {
    const groupRect = group.getBoundingClientRect();
    const shift = rect.bottom - (window.innerHeight - margin);
    const newTopViewport = Math.max(margin, rect.top - shift);
    submenu.style.top = `${newTopViewport - groupRect.top}px`;
  }
};

const buildContextMenu = (items, parent) => {
  items.forEach(item => {
    const group = document.createElement("div");
    group.className = "context-menu-group";
    const button = document.createElement("button");
    button.type = "button";
    button.className = "context-menu-item";
    button.setAttribute("role", "menuitem");
    if (item.toggleKey) button.dataset.toggleKey = item.toggleKey;
    if (item.selectedField) {
      button.dataset.selectedField = item.selectedField;
      button.dataset.selectedValue = String(item.selectedValue ?? item.label);
    }
    if (item.swatch) {
      const dot = document.createElement("span");
      dot.className = "context-menu-swatch";
      dot.style.background = item.swatch;
      button.appendChild(dot);
    }
    const label = document.createElement("span");
    label.className = "context-menu-label";
    label.textContent = item.label;
    button.appendChild(label);
    if (item.toggleKey || item.selectedField || item.selected) {
      const indicator = document.createElement("span");
      indicator.className = "context-menu-state";
      if (item.selected) indicator.textContent = "✓";
      button.appendChild(indicator);
    }
    if (item.action) {
      button.addEventListener("click", event => {
        event.preventDefault();
        event.stopPropagation();
        // Optimistically reflect the choice so the checkmark/On-Off moves immediately, even while
        // paused (some options only reach mpv once playback resumes); the real state sync corrects
        // it later if needed.
        applyOptimisticContextSelection(button);
        executeContextAction(item.action);
        if (button.dataset.selectedField || button.dataset.toggleKey) refreshContextMenuIndicators();
        // Release focus so a kept-open menu doesn't stay pinned via :focus-within once the
        // pointer moves away (otherwise e.g. the Audio submenu lingers after clicking Mute).
        button.blur();
      });
    }
    if (item.children) {
      const arrow = document.createElement("span");
      arrow.className = "context-menu-arrow";
      arrow.textContent = "›";
      button.appendChild(arrow);
      const submenu = document.createElement("div");
      submenu.className = "context-menu-submenu";
      submenu.setAttribute("role", "menu");
      if (item.dynamicKey) contextMenuDynamicSubmenus.set(item.dynamicKey, submenu);
      buildContextMenu(item.children, submenu);
      group.appendChild(button);
      group.appendChild(submenu);
      // Keep the flyout on-screen: flip it upward near the bottom edge and let long lists
      // scroll instead of running off the viewport.
      group.addEventListener("mouseenter", () => positionSubmenu(group));
      group.addEventListener("focusin", () => positionSubmenu(group));
    } else {
      if (item.shortcut) {
        const shortcut = document.createElement("span");
        shortcut.className = "context-menu-shortcut";
        shortcut.textContent = item.shortcut;
        button.appendChild(shortcut);
      }
      group.appendChild(button);
    }
    parent.appendChild(group);
  });
};

if (contextMenu) {
  buildContextMenu(contextMenuItems, contextMenu);
  refreshContextMenuIndicators();
  contextMenu.addEventListener("contextmenu", event => event.preventDefault());
}

window.nuvioOpenAnimeShaderContextMenu = items => {
  setContextMenuDynamicItems("animeShaders", items);
};

// Builds the nested "Advanced (mpv)" submenu from a catalog pushed by Kotlin. Each option becomes a
// flyout of choices; each choice carries a flat `index` the Kotlin side maps back to a property/value.
window.nuvioSetMpvOptionsMenu = options => {
  const submenu = contextMenuDynamicSubmenus.get("mpvOptions");
  if (!submenu) return;
  submenu.textContent = "";
  const list = Array.isArray(options) ? options : [];
  if (list.length === 0) {
    const empty = document.createElement("div");
    empty.className = "context-menu-empty";
    empty.textContent = "No options available";
    submenu.appendChild(empty);
    return;
  }
  buildContextMenu(list.map(option => ({
    label: String(option.label || "Option"),
    children: (Array.isArray(option.choices) ? option.choices : []).map(choice => ({
      label: String(choice.label || "Option"),
      action: `send:selectMpvOption:${Number(choice.index) || 0}`,
      selected: Boolean(choice.selected),
    })),
  })), submenu);
};

// Friendly names for the built-in subtitle colour swatches, keyed by their RRGGBB value so the
// menu shows "Gold" / "Cyan" rather than "Color 2". Anything unrecognised falls back to a swatch dot.
const SUBTITLE_COLOR_NAMES = {
  FFFFFF: "White",
  FFD700: "Gold",
  "00E5FF": "Cyan",
  FF5C5C: "Red",
  "00FF88": "Green",
  "9B59B6": "Purple",
  F97316: "Orange",
  "22C55E": "Emerald",
  "3B82F6": "Blue",
  "000000": "Black",
};

const describeSubtitleColor = value => {
  const clean = String(value || "").replace(/^#/, "").toUpperCase();
  const rgb = clean.length >= 6 ? clean.slice(-6) : clean.padStart(6, "0");
  return { css: `#${rgb}`, name: SUBTITLE_COLOR_NAMES[rgb] || null };
};

const subtitleColorContextEntries = field => {
  const style = state.subtitleStyle || {};
  return (state.subtitleColorSwatches || []).map((value, index) => {
    const info = describeSubtitleColor(value);
    return {
      label: info.name || `Color ${index + 1}`,
      swatch: info.css,
      selected: value === style[field],
    };
  });
};

const refreshSubtitleStyleContextSubmenus = () => {
  const style = state.subtitleStyle || {};
  setContextMenuDynamicItems(
    "fontFamilies",
    (state.subtitleFontFamilies || []).map(value => ({
      value,
      label: value || "Default",
      selected: value === style.fontFamily,
    })),
  );
  setContextMenuDynamicItems("subtitleColors", subtitleColorContextEntries("textColor"));
  setContextMenuDynamicItems("outlineColors", subtitleColorContextEntries("outlineColor"));
};

const openContextMenu = event => {
  if (state.pictureInPictureActive) {
    event.preventDefault();
    event.stopPropagation();
    closeContextMenu();
    return;
  }
  if (!contextMenu || isHeroTrailerSurface || openingOverlay?.classList.contains("visible")) return;
  event.preventDefault();
  event.stopPropagation();
  closePlayerModal(false, false);
  contextMenu.hidden = false;
  contextMenuOpen = true;
  // Keep the chrome (and cursor) up while the menu is open so the fade timer can't
  // collapse the submenus mid-interaction.
  clearChromeAutoHideTimer();
  renderChrome();
  contextMenu.classList.toggle("context-menu-left", event.clientX > window.innerWidth * 0.58);
  if (!state.isLoadingAddonSubtitles && normalizeTracks(state.addonSubtitleItems).length === 0) {
    send("fetchAddonSubtitles", 0);
  }
  refreshSubtitleStyleContextSubmenus();
  send("openAnimeShaderContextMenu", 0);
  send("openMpvOptionsContextMenu", 0);
  const margin = 8;
  const x = Math.min(event.clientX, Math.max(margin, window.innerWidth - contextMenu.offsetWidth - margin));
  const y = Math.min(event.clientY, Math.max(margin, window.innerHeight - contextMenu.offsetHeight - margin));
  contextMenu.style.left = `${Math.max(margin, x)}px`;
  contextMenu.style.top = `${Math.max(margin, y)}px`;
};

root.addEventListener("contextmenu", openContextMenu);
document.addEventListener("pointerdown", event => {
  if (!contextMenuOpen || event.target.closest("#contextMenu")) return;
  // Dismissing the context menu is the complete action for this pointer gesture. Without this
  // one-shot guard, the click generated after pointerup reaches the video surface and toggles
  // playback as an unintended second action.
  if (event.button === 0) {
    consumeContextMenuDismissalClick = true;
    window.clearTimeout(contextMenuDismissalClickTimer);
    contextMenuDismissalClickTimer = window.setTimeout(() => {
      consumeContextMenuDismissalClick = false;
    }, 1000);
  }
  closeContextMenu();
});

const normalizeTracks = tracks =>
  Array.isArray(tracks) ? tracks.filter(track => track && typeof track === "object") : [];

const trackIdValue = track => {
  const parsed = Number(track && track.id);
  return Number.isFinite(parsed) ? parsed : -1;
};

const buildCheckIcon = () => {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("class", "track-check");
  const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
  use.setAttribute("href", "#icon-check");
  svg.appendChild(use);
  return svg;
};

const appendTrackRow = (container, label, selected, onSelect, closeAfterSelect = true) => {
  const row = document.createElement("button");
  row.type = "button";
  row.className = `track-row${selected ? " selected" : ""}`;
  row.addEventListener("click", event => {
    event.stopPropagation();
    onSelect();
    if (closeAfterSelect) window.setTimeout(closePlayerModal, 120);
  });

  const text = document.createElement("span");
  text.className = "track-label";
  text.textContent = label;
  row.appendChild(text);
  row.appendChild(buildCheckIcon());
  container.appendChild(row);
};

const appendEmptyTrackState = (container, label) => {
  const empty = document.createElement("div");
  empty.className = "track-empty";
  empty.textContent = label;
  container.appendChild(empty);
};

const renderAudioTrackList = () => {
  audioTrackList.textContent = "";
  const tracks = normalizeTracks(state.audioTracks);
  if (audioPanel) {
    const canvas = renderAudioTrackList.canvas || (renderAudioTrackList.canvas = document.createElement("canvas"));
    const context = canvas.getContext("2d");
    if (context) {
      context.font = '700 15px "Nuvio JetBrains Sans", "Segoe UI", sans-serif';
      const widest = tracks.reduce((width, track, index) => {
        const label = track.label || track.language || `Track ${Number(track.index || index) + 1}`;
        return Math.max(width, context.measureText(String(label)).width);
      }, context.measureText("Audio tracks").width);
      const viewportLimit = Math.max(320, window.innerWidth * .70);
      audioPanel.style.width = `${Math.round(Math.min(viewportLimit, Math.max(360, (widest + 104) * 1.15)))}px`;
    }
  }
  if (tracks.length === 0) {
    appendEmptyTrackState(audioTrackList, "No audio tracks available");
    return;
  }
  tracks.forEach(track => {
    appendTrackRow(
      audioTrackList,
      track.label || track.language || `Track ${Number(track.index || 0) + 1}`,
      Boolean(track.selected),
      () => send("selectAudioTrack", trackIdValue(track)),
    );
  });
};

const renderSubtitleTrackList = () => {
  subtitleTrackList.textContent = "";
  // With "Show Only Preferred Languages" on, the app pushes a pre-filtered built-in list (index +
  // label + isSelected); otherwise fall back to the live native track list. Either way each entry
  // carries the native track index that selectBuiltInSubtitleTrack expects.
  const tracks = state.builtInSubtitleFilterActive
    ? normalizeItems(state.builtInSubtitleItems).map(item => ({
        index: Number(item.index) || 0,
        label: item.label || "",
        language: "",
        selected: Boolean(item.isSelected),
      }))
    : normalizeTracks(state.subtitleTracks);
  const hasSelected = tracks.some(track => Boolean(track.selected));
  appendTrackRow(
    subtitleTrackList,
    state.noneLabel || "None",
    !hasSelected,
    () => send("selectBuiltInSubtitleTrack", -1),
    false,
  );
  tracks.forEach(track => {
    appendTrackRow(
      subtitleTrackList,
      track.label || track.language || `Subtitle ${Number(track.index || 0) + 1}`,
      Boolean(track.selected),
      () => send("selectBuiltInSubtitleTrack", Number(track.index) || 0),
      false,
    );
  });
};

const renderAddonSubtitleList = () => {
  addonSubtitleList.textContent = "";
  if (state.isLoadingAddonSubtitles) {
    appendEmptyTrackState(addonSubtitleList, "Loading subtitles...");
    return;
  }
  const items = normalizeItems(state.addonSubtitleItems);
  if (items.length === 0) {
    const row = document.createElement("button");
    row.type = "button";
    row.className = "track-row";
    row.addEventListener("click", event => {
      event.stopPropagation();
      send("fetchAddonSubtitles", 0);
    });
    const text = document.createElement("span");
    text.className = "track-label";
    text.textContent = state.fetchSubtitlesLabel || "Tap to fetch subtitles";
    row.appendChild(text);
    addonSubtitleList.appendChild(row);
    return;
  }
  items.forEach(item => {
    const label = item.display || item.languageLabel || "Subtitle";
    const secondary = [item.languageLabel, item.addonName].filter(Boolean).join(" • ");
    const row = document.createElement("button");
    row.type = "button";
    row.className = `track-row stream-row${item.isSelected ? " selected" : ""}`;
    row.addEventListener("click", event => {
      event.stopPropagation();
      send("selectAddonSubtitle", Number(item.index) || 0);
    });
    const copy = document.createElement("span");
    copy.className = "track-copy";
    const name = document.createElement("span");
    name.className = "stream-name";
    name.textContent = label;
    copy.appendChild(name);
    if (secondary) {
      const detail = document.createElement("span");
      detail.className = "stream-addon";
      detail.textContent = secondary;
      copy.appendChild(detail);
    }
    row.appendChild(copy);
    row.appendChild(buildCheckIcon());
    addonSubtitleList.appendChild(row);
  });
};

const formatDelay = delayMs => {
  const value = Number(delayMs) || 0;
  const sign = value >= 0 ? "+" : "-";
  const absolute = Math.abs(value);
  const seconds = Math.floor(absolute / 1000);
  const millis = absolute % 1000;
  return `${sign}${seconds}.${String(millis).padStart(3, "0")}s`;
};

const parseArgb = value => {
  const raw = String(value || "").replace("#", "");
  const hex = raw.length === 8 ? raw : `FF${raw.padStart(6, "0")}`;
  const alpha = parseInt(hex.slice(0, 2), 16);
  const red = parseInt(hex.slice(2, 4), 16);
  const green = parseInt(hex.slice(4, 6), 16);
  const blue = parseInt(hex.slice(6, 8), 16);
  return { alpha, red, green, blue, css: `rgba(${red}, ${green}, ${blue}, ${(alpha / 255).toFixed(3)})` };
};

const sameRgb = (left, right) => {
  const a = parseArgb(left);
  const b = parseArgb(right);
  return a.red === b.red && a.green === b.green && a.blue === b.blue;
};

const renderSwatches = (container, selectedColor, eventType) => {
  container.textContent = "";
  const colors = Array.isArray(state.subtitleColorSwatches) ? state.subtitleColorSwatches : [];
  colors.forEach((color, index) => {
    const parsed = parseArgb(color);
    const swatch = document.createElement("button");
    swatch.type = "button";
    swatch.className = `swatch${parsed.alpha === 0 ? " transparent" : ""}${sameRgb(color, selectedColor) ? " selected" : ""}`;
    swatch.style.setProperty("--swatch", parsed.css);
    swatch.addEventListener("click", event => {
      event.stopPropagation();
      send(eventType, index);
    });
    container.appendChild(swatch);
  });
};

const renderAutoSyncCues = () => {
  if (!state.hasSelectedAddonSubtitle) {
    autoSyncStatus.textContent = state.selectAddonSubtitleFirstLabel || "Select an addon subtitle first";
    autoSyncCueList.textContent = "";
    autoSyncCueList.dataset.renderKey = "";
    return;
  }
  if (state.subtitleAutoSyncIsLoading) {
    autoSyncStatus.textContent = state.loadingSubtitleLinesLabel || "Loading subtitle lines...";
  } else {
    autoSyncStatus.textContent = state.subtitleAutoSyncErrorMessage || "";
  }
  const cues = normalizeItems(state.subtitleAutoSyncCues);
  const capturedMs = Number(state.subtitleAutoSyncCapturedPositionMs);
  // This runs on every controls update while the panel is open. Rebuilding an unchanged
  // list would snap the user's scroll position back every tick, so skip when nothing moved.
  const renderKey = `${capturedMs}|${cues.map(cue => `${cue.index}:${cue.timeMs}`).join(",")}`;
  if (autoSyncCueList.dataset.renderKey === renderKey) return;
  autoSyncCueList.dataset.renderKey = renderKey;
  autoSyncCueList.textContent = "";
  let nearestRow = null;
  let nearestDistance = Infinity;
  cues.forEach(cue => {
    const row = document.createElement("button");
    row.type = "button";
    row.className = "sync-cue";
    row.addEventListener("click", event => {
      event.stopPropagation();
      send("subtitleAutoSyncCue", Number(cue.index) || 0);
    });
    const time = document.createElement("span");
    time.className = "sync-time";
    time.textContent = cue.timeLabel || "";
    const text = document.createElement("span");
    text.className = "sync-text";
    text.textContent = cue.text || "";
    row.appendChild(time);
    row.appendChild(text);
    autoSyncCueList.appendChild(row);
    const distance = Math.abs(Number(cue.timeMs) - capturedMs);
    if (Number.isFinite(distance) && distance < nearestDistance) {
      nearestDistance = distance;
      nearestRow = row;
    }
  });
  // Center the view on the line closest to the captured moment; heavily desynced subs are
  // reachable by scrolling up (earlier) or down (later) from there.
  if (nearestRow) {
    nearestRow.classList.add("nearest");
    nearestRow.scrollIntoView({ block: "center" });
  }
};

const renderSubtitleStylePanel = () => {
  const style = state.subtitleStyle || {};
  subtitleDelayLabel.textContent = state.subtitleDelayLabel || "Subtitle Delay";
  subtitleDelayValue.textContent = formatDelay(state.subtitleDelayMs);
  subtitleDelayReset.setAttribute("aria-label", state.resetLabel || "Reset");
  subtitleDelayReset.title = state.resetLabel || "Reset";
  autoSyncLabel.textContent = state.autoSyncLabel || "Auto Sync";
  autoSyncReload.textContent = state.reloadSmallLabel || "Reload";
  autoSyncCapture.textContent = state.captureLineLabel || "Capture";
  fontSizeLabel.textContent = state.fontSizeLabel || "Font Size";
  fontSizeValue.textContent = `${Number(style.fontSizeSp) || 18}sp`;
  if (fontFamilySelect) {
    const fonts = Array.isArray(state.subtitleFontFamilies) ? state.subtitleFontFamilies : [];
    // Rebuild the option list only when it actually changes, so the dropdown isn't clobbered
    // (or closed) on every unrelated controls update.
    if (fontFamilySelect.dataset.count !== String(fonts.length)) {
      fontFamilySelect.innerHTML = "";
      fonts.forEach((family, index) => {
        const option = document.createElement("option");
        option.value = String(index);
        option.textContent = family === "" ? "Default" : family;
        fontFamilySelect.appendChild(option);
      });
      fontFamilySelect.dataset.count = String(fonts.length);
    }
    const current = style.fontFamily || "";
    const selectedIndex = fonts.indexOf(current);
    fontFamilySelect.value = String(selectedIndex >= 0 ? selectedIndex : 0);
  }
  outlineLabel.textContent = state.outlineLabel || "Outline";
  outlineToggle.textContent = style.outlineEnabled ? (state.onLabel || "On") : (state.offLabel || "Off");
  outlineToggle.classList.toggle("primary", Boolean(style.outlineEnabled));
  shadowLabel.textContent = state.shadowLabel || "Shadow";
  shadowToggle.textContent = style.shadowEnabled ? (state.onLabel || "On") : (state.offLabel || "Off");
  shadowToggle.classList.toggle("primary", Boolean(style.shadowEnabled));
  boldLabel.textContent = state.boldLabel || "Bold";
  boldToggle.textContent = style.bold ? (state.onLabel || "On") : (state.offLabel || "Off");
  boldToggle.classList.toggle("primary", Boolean(style.bold));
  bottomOffsetLabel.textContent = state.bottomOffsetLabel || "Bottom Offset";
  bottomOffsetValue.textContent = String(Number(style.bottomOffset) || 0);
  subtitleColorLabel.textContent = state.colorLabel || "Color";
  textOpacityLabel.textContent = state.textOpacityLabel || "Text Opacity";
  const textAlpha = Math.round((parseArgb(style.textColor).alpha / 255) * 100);
  textOpacityValue.textContent = `${textAlpha}%`;
  outlineColorLabel.textContent = state.outlineColorLabel || "Outline Color";
  subtitleStyleReset.textContent = state.resetDefaultsLabel || "Reset Defaults";
  renderSwatches(subtitleColorSwatches, style.textColor, "subtitleTextColor");
  renderSwatches(outlineColorSwatches, style.outlineColor, "subtitleOutlineColor");
  renderAutoSyncCues();
};

const renderSubtitleModal = () => {
  const tab = state.subtitleActiveTab || "BuiltIn";
  subtitlePanelTitle.textContent = state.subtitlesPanelTitle || "Subtitles";
  subtitleBuiltInTab.textContent = state.subtitleBuiltInTabLabel || "Built-in";
  subtitleAddonsTab.textContent = state.subtitleAddonsTabLabel || "Addons";
  subtitleStyleTab.textContent = state.subtitleStyleTabLabel || "Style";
  subtitleBuiltInTab.classList.toggle("selected", tab === "BuiltIn");
  subtitleAddonsTab.classList.toggle("selected", tab === "Addons");
  subtitleStyleTab.classList.toggle("selected", tab === "Style");
  subtitleTrackList.hidden = tab !== "BuiltIn";
  addonSubtitleList.hidden = tab !== "Addons";
  subtitleStylePanel.hidden = tab !== "Style";
  if (tab === "BuiltIn") renderSubtitleTrackList();
  if (tab === "Addons") renderAddonSubtitleList();
  if (tab === "Style") renderSubtitleStylePanel();
};

const normalizeItems = items =>
  Array.isArray(items) ? items.filter(item => item && typeof item === "object") : [];

const appendFilterChip = (container, label, selected, onSelect) => {
  const chip = document.createElement("button");
  chip.type = "button";
  chip.className = `filter-chip${selected ? " selected" : ""}`;
  chip.textContent = label;
  chip.addEventListener("click", event => {
    event.stopPropagation();
    onSelect();
  });
  container.appendChild(chip);
};

const renderFilterRow = (container, filters, selectedId, onSelect) => {
  container.textContent = "";
  const list = normalizeItems(filters);
  container.hidden = list.length === 0;
  list.forEach(filter => {
    appendFilterChip(
      container,
      filter.label || state.allFilterLabel || "All",
      String(filter.id || "") === String(selectedId || ""),
      () => onSelect(String(filter.id || "")),
    );
  });
};

const SourceRowEstimatedHeight = 116;
const SourceRowGap = 10;
const SourceRowOverscanPx = 720;

// The source panel scales via `zoom: var(--user-scale)` on .track-panel. getBoundingClientRect()
// reports zoomed (screen) pixels, but the virtual list's offsets/translateY/scrollTop math all run
// in the panel's own unzoomed coordinate space — so measured row heights must be divided back into
// that space or they drift by the scale factor and rows overlap (scaled down) / gap (scaled up).
const currentSourcePanelScale = () => {
  const raw = parseFloat(getComputedStyle(document.documentElement).getPropertyValue("--panel-scale"));
  return Number.isFinite(raw) && raw > 0 ? raw : 1;
};

const sourceKeyForItems = items => {
  const first = items[0] || {};
  const last = items[items.length - 1] || {};
  return [
    sourceFilterId || "",
    items.length,
    first.index == null ? "" : first.index,
    first.label || "",
    last.index == null ? "" : last.index,
    last.label || "",
  ].join("\u0001");
};

const resetSourceVirtualState = (items, key) => {
  sourceVirtualKey = key;
  sourceVirtualItems = items;
  sourceVirtualHeights = new Array(items.length).fill(SourceRowEstimatedHeight);
  sourceVirtualOffsets = [];
  sourceVirtualTotalHeight = 0;
  sourceVirtualSpacer = null;
  window.cancelAnimationFrame(sourceVirtualRenderRaf);
  sourceVirtualRenderRaf = 0;
};

const rebuildSourceVirtualLayout = () => {
  let offset = 0;
  sourceVirtualOffsets = sourceVirtualItems.map((_, index) => {
    const current = offset;
    offset += sourceVirtualHeights[index] || SourceRowEstimatedHeight;
    if (index < sourceVirtualItems.length - 1) offset += SourceRowGap;
    return current;
  });
  sourceVirtualTotalHeight = offset;
  if (sourceVirtualSpacer) {
    sourceVirtualSpacer.style.height = `${sourceVirtualTotalHeight}px`;
  }
};

const sourceIndexForOffset = offset => {
  let low = 0;
  let high = sourceVirtualOffsets.length - 1;
  let result = 0;
  while (low <= high) {
    const mid = Math.floor((low + high) / 2);
    const rowBottom = sourceVirtualOffsets[mid] + (sourceVirtualHeights[mid] || SourceRowEstimatedHeight);
    if (rowBottom < offset) {
      low = mid + 1;
    } else {
      result = mid;
      high = mid - 1;
    }
  }
  return result;
};

const buildSourceRow = (item, onSelect) => {
  const row = document.createElement("button");
  row.type = "button";
  row.className = `track-row stream-row source-row${item.isCurrent ? " selected" : ""}${item.isEnabled === false ? " disabled" : ""}`;
  row.disabled = item.isEnabled === false;
  row.addEventListener("click", event => {
    event.stopPropagation();
    onSelect(item);
  });

  const copy = document.createElement("span");
  copy.className = "track-copy";

  const top = document.createElement("span");
  top.className = "track-row-top";
  const name = document.createElement("span");
  name.className = "stream-name";
  name.textContent = item.label || "Stream";
  top.appendChild(name);
  if (item.isCurrent) {
    const chip = document.createElement("span");
    chip.className = "status-chip";
    chip.textContent = state.playingLabel || "Playing";
    top.appendChild(chip);
  }
  let subtitle = null;
  if (item.subtitle) {
    subtitle = document.createElement("span");
    subtitle.className = "stream-subtitle";
    subtitle.textContent = item.subtitle;
  }

  const badges = Array.isArray(item.badges) ? item.badges : [];
  let badgeRail = null;
  if (badges.length > 0 || item.addonName) {
    badgeRail = document.createElement("span");
    badgeRail.className = "stream-badges";
    badges.forEach(badge => {
      const badgeElement = document.createElement("span");
      badgeElement.className = "stream-badge";
      badgeElement.title = String(badge.name || "");
      if (badge.backgroundColor) badgeElement.style.backgroundColor = badge.backgroundColor;
      if (badge.textColor) badgeElement.style.color = badge.textColor;
      if (badge.borderColor) badgeElement.style.borderColor = badge.borderColor;
      if (badge.imageUrl) {
        const image = document.createElement("img");
        image.alt = String(badge.name || "");
        image.loading = "eager";
        image.decoding = "async";
        image.src = badge.imageUrl;
        // A badge image's width (and therefore whether the badge rail wraps to another line) is
        // only known once it loads, which happens after the row's first height measurement. Re-run
        // the virtual layout when a not-yet-cached image finishes so the stored height catches the
        // reflow. Guarded on !complete so cached images don't trigger an endless re-render loop.
        if (!image.complete) {
          image.addEventListener("load", requestSourceVirtualRender, { once: true });
        }
        badgeElement.appendChild(image);
      } else {
        badgeElement.textContent = String(badge.name || "");
      }
      badgeRail.appendChild(badgeElement);
    });
    if (item.addonName) {
      const addon = document.createElement("span");
      addon.className = "stream-addon-inline";
      addon.textContent = item.addonName;
      badgeRail.appendChild(addon);
    }
  }
  if (badgeRail && state.sourceBadgePlacement === "top") copy.appendChild(badgeRail);
  copy.appendChild(top);
  if (subtitle) copy.appendChild(subtitle);
  if (badgeRail && state.sourceBadgePlacement !== "top") copy.appendChild(badgeRail);
  row.appendChild(copy);
  return row;
};

const renderSourceVirtualRows = () => {
  sourceVirtualRenderRaf = 0;
  if (!sourceVirtualSpacer) return;
  const count = sourceVirtualItems.length;
  if (count === 0) return;

  const viewportTop = Math.max(0, sourceList.scrollTop - SourceRowOverscanPx);
  const viewportBottom = sourceList.scrollTop + sourceList.clientHeight + SourceRowOverscanPx;
  const start = sourceIndexForOffset(viewportTop);
  let end = start;
  while (end < count && sourceVirtualOffsets[end] <= viewportBottom) {
    end += 1;
  }
  end = Math.min(count, Math.max(end + 1, start + 1));

  sourceVirtualSpacer.textContent = "";
  const fragment = document.createDocumentFragment();
  const rendered = [];
  for (let index = start; index < end; index += 1) {
    const item = sourceVirtualItems[index];
    const wrapper = document.createElement("div");
    wrapper.className = "source-virtual-row";
    wrapper.style.transform = `translateY(${sourceVirtualOffsets[index]}px)`;
    const row = buildSourceRow(item, selected => {
      send("selectSource", Number(selected.index) || 0);
      window.setTimeout(closePlayerModal, 120);
    });
    row.dataset.keyboardSourceIndex = String(index);
    row.classList.toggle("keyboard-focused", keyboardPanelMode === "sources" && index === keyboardSourceIndex);
    wrapper.appendChild(row);
    fragment.appendChild(wrapper);
    rendered.push({ index, wrapper });
  }
  sourceVirtualSpacer.appendChild(fragment);
  if (keyboardPanelMode === "sources") {
    window.requestAnimationFrame(() => focusKeyboardSourceRow());
  }

  window.requestAnimationFrame(() => {
    const scale = currentSourcePanelScale();
    let changed = false;
    rendered.forEach(({ index, wrapper }) => {
      const measured = Math.ceil(wrapper.getBoundingClientRect().height / scale);
      if (measured > 0 && Math.abs((sourceVirtualHeights[index] || SourceRowEstimatedHeight) - measured) > 1) {
        sourceVirtualHeights[index] = measured;
        changed = true;
      }
    });
    if (changed) {
      rebuildSourceVirtualLayout();
      requestSourceVirtualRender();
    }
  });
};

const requestSourceVirtualRender = () => {
  if (sourceVirtualRenderRaf) return;
  sourceVirtualRenderRaf = window.requestAnimationFrame(renderSourceVirtualRows);
};

const updateSourcePanelWidth = items => {
  if (!sourcePanel) return;
  const canvas = updateSourcePanelWidth.canvas || (updateSourcePanelWidth.canvas = document.createElement("canvas"));
  const context = canvas.getContext("2d");
  if (!context) return;
  const measureLines = (value, font) => {
    context.font = font;
    return String(value || "")
      .split(/\r?\n/)
      .reduce((widest, line) => Math.max(widest, context.measureText(line).width), 0);
  };
  const fontFamily = '"Nuvio JetBrains Sans", "Segoe UI", sans-serif';
  const contentWidth = items.reduce((widest, item) => {
    const primary = Math.max(
      measureLines(item.label || "Stream", `700 14px ${fontFamily}`),
      Math.min(620, measureLines(item.subtitle, `400 12px ${fontFamily}`)),
    );
    // The addon is absolutely right-aligned inside the row and must not dictate panel width.
    return Math.max(widest, primary + 92);
  }, 400);
  const viewportLimit = Math.max(500, Math.min(760, window.innerWidth * .62));
  sourcePanel.style.width = `${Math.round(Math.min(viewportLimit, Math.max(540, contentWidth * 1.15)))}px`;
};

const renderSourceModal = () => {
  sourcePanelTitle.textContent = state.sourcesPanelTitle || "Sources";
  sourceReloadButton.textContent = state.reloadLabel || "Reload";
  sourceCloseButton.textContent = state.panelCloseLabel || "Close";

  const filters = normalizeItems(state.sourceFilters);
  if (sourceFilterId && !filters.some(filter => String(filter.id || "") === sourceFilterId)) {
    sourceFilterId = "";
  }
  renderFilterRow(sourceFilterList, filters, sourceFilterId, id => {
    sourceFilterId = id;
    sourceList.scrollTop = 0;
    renderSourceModal();
  });

  sourceList.textContent = "";
  sourceList.classList.remove("virtualized");
  let items = normalizeItems(state.sourceItems);
  if (sourceFilterId) {
    items = items.filter(item => String(item.filterId || "") === sourceFilterId);
  }
  updateSourcePanelWidth(items);
  if (items.length === 0) {
    sourceVirtualItems = [];
    sourceVirtualSpacer = null;
    window.cancelAnimationFrame(sourceVirtualRenderRaf);
    sourceVirtualRenderRaf = 0;
    appendEmptyTrackState(
      sourceList,
      state.sourceIsLoading ? "Loading streams..." : (state.noStreamsLabel || "No streams found"),
    );
    return;
  }
  const nextKey = sourceKeyForItems(items);
  if (nextKey !== sourceVirtualKey) {
    resetSourceVirtualState(items, nextKey);
    sourceList.scrollTop = 0;
  } else {
    sourceVirtualItems = items;
  }
  sourceList.classList.add("virtualized");
  sourceVirtualSpacer = document.createElement("div");
  sourceVirtualSpacer.className = "source-virtual-spacer";
  sourceList.appendChild(sourceVirtualSpacer);
  rebuildSourceVirtualLayout();
  renderSourceVirtualRows();
};

const focusKeyboardSourceRow = () => {
  const row = sourceList.querySelector(`[data-keyboard-source-index="${keyboardSourceIndex}"]`);
  if (!row) return;
  row.focus({ preventScroll: true });
};

const moveKeyboardSource = delta => {
  if (sourceVirtualItems.length === 0) return;
  keyboardSourceIndex = Math.max(0, Math.min(sourceVirtualItems.length - 1, keyboardSourceIndex + delta));
  const offset = sourceVirtualOffsets[keyboardSourceIndex] || 0;
  const height = sourceVirtualHeights[keyboardSourceIndex] || SourceRowEstimatedHeight;
  sourceList.scrollTop = Math.max(0, offset - Math.max(0, sourceList.clientHeight - height) / 2);
  renderSourceVirtualRows();
};

const appendEpisodeRow = (container, item, keyboardIndex) => {
  const row = document.createElement("button");
  row.type = "button";
  row.className = `track-row episode-row${item.isCurrent ? " selected" : ""}`;
  row.dataset.keyboardEpisodeIndex = String(keyboardIndex);
  row.classList.toggle("keyboard-focused", keyboardPanelMode === "episodes" && !keyboardEpisodeShowingStreams && keyboardIndex === keyboardEpisodeIndex);
  row.addEventListener("click", event => {
    event.stopPropagation();
    send("selectEpisode", Number(item.index) || 0);
  });

  const thumb = document.createElement("span");
  thumb.className = "episode-thumb";
  if (item.thumbnail) {
    const image = document.createElement("img");
    image.alt = "";
    image.loading = "eager";
    image.decoding = "async";
    setImageSource(image, item.thumbnail);
    thumb.appendChild(image);
  }
  row.appendChild(thumb);

  const top = document.createElement("span");
  top.className = "episode-row-top";
  if (item.code) {
    const code = document.createElement("span");
    code.className = "episode-code";
    code.textContent = item.code;
    top.appendChild(code);
  }
  if (item.isCurrent) {
    const chip = document.createElement("span");
    chip.className = "status-chip";
    chip.textContent = state.playingLabel || "Playing";
    top.appendChild(chip);
  }
  row.appendChild(top);
  const copy = document.createElement("span");
  copy.className = "episode-copy";
  const name = document.createElement("span");
  name.className = "episode-name";
  name.textContent = item.title || item.code || "Episode";
  copy.appendChild(name);
  if (item.overview) {
    const overview = document.createElement("span");
    overview.className = "episode-overview";
    overview.textContent = item.overview;
    copy.appendChild(overview);
  }
  row.appendChild(copy);
  container.appendChild(row);
};

const ensureEpisodeSeason = () => {
  const seasons = normalizeItems(state.episodeSeasons);
  if (seasons.length === 0) {
    selectedEpisodeSeason = null;
    return null;
  }
  if (!seasons.some(season => Number(season.season) === Number(selectedEpisodeSeason))) {
    const preferred = seasons.find(season => Boolean(season.isSelected)) || seasons[0];
    selectedEpisodeSeason = Number(preferred.season) || 0;
  }
  return selectedEpisodeSeason;
};

const preloadEpisodeArtwork = items => {
  items.forEach(item => {
    const url = String(item && item.thumbnail || "").trim();
    if (!url || episodeArtworkPreloads.has(url)) return;
    const preload = new Image();
    preload.decoding = "async";
    preload.loading = "eager";
    episodeArtworkPreloads.set(url, preload);
    let retried = false;
    preload.onload = () => {
      episodeList.querySelectorAll(".episode-thumb img").forEach(image => {
        if (image.getAttribute("src") !== url || !image.classList.contains("image-error")) return;
        image.removeAttribute("src");
        setImageSource(image, url);
      });
    };
    preload.onerror = () => {
      if (retried) return;
      retried = true;
      window.setTimeout(() => { preload.src = url; }, 600);
    };
    preload.src = url;
  });
};

const renderEpisodeList = () => {
  episodesPanelTitle.textContent = state.episodesPanelTitle || "Episodes";
  episodesCloseButton.textContent = state.panelCloseLabel || "Close";

  const selectedSeason = ensureEpisodeSeason();
  const seasons = normalizeItems(state.episodeSeasons);
  renderFilterRow(
    seasonFilterList,
    seasons.map(season => ({ id: String(season.season), label: season.label })),
    selectedSeason == null ? "" : String(selectedSeason),
    id => {
      selectedEpisodeSeason = Number(id);
      keyboardEpisodeIndex = 0;
      renderEpisodeList();
      window.requestAnimationFrame(() => { episodeList.scrollLeft = 0; });
    },
  );

  let items = normalizeItems(state.episodeItems);
  if (selectedSeason != null) {
    items = items.filter(item => Number(item.season) === Number(selectedSeason));
  }
  preloadEpisodeArtwork(items);
  const nextRenderKey = JSON.stringify([
    selectedSeason,
    state.noEpisodesLabel || "",
    items.map(item => [
      item.index,
      item.id,
      item.title,
      item.code,
      item.overview,
      item.thumbnail,
      Boolean(item.isCurrent),
      Boolean(item.isWatched),
    ]),
  ]);
  if (nextRenderKey !== episodeListRenderKey) {
    episodeListRenderKey = nextRenderKey;
    episodeList.textContent = "";
    if (items.length === 0) {
      appendEmptyTrackState(episodeList, state.noEpisodesLabel || "No episodes available");
    } else {
      items.forEach((item, index) => appendEpisodeRow(episodeList, item, index));
    }
  }
  keyboardEpisodeIndex = Math.max(0, Math.min(Math.max(0, items.length - 1), keyboardEpisodeIndex));
  episodeList.querySelectorAll("[data-keyboard-episode-index]").forEach((row, index) => {
    row.classList.toggle("keyboard-focused", keyboardPanelMode === "episodes" && index === keyboardEpisodeIndex);
  });
  if (keyboardPanelMode === "episodes") {
    const focusKey = `${selectedSeason}:${keyboardEpisodeIndex}`;
    if (focusKey !== episodeFocusPositionKey) {
      episodeFocusPositionKey = focusKey;
      window.requestAnimationFrame(() => focusKeyboardEpisodeRow());
    }
  }
};

const renderEpisodeStreams = () => {
  streamsPanelTitle.textContent = state.selectedEpisodeLabel || state.streamsPanelTitle || "Streams";
  episodeBackButton.textContent = state.backLabel || "Back";
  episodeReloadButton.textContent = state.reloadLabel || "Reload";
  episodeStreamsCloseButton.textContent = state.panelCloseLabel || "Close";

  const filters = normalizeItems(state.episodeStreamFilters);
  if (episodeStreamFilterId && !filters.some(filter => String(filter.id || "") === episodeStreamFilterId)) {
    episodeStreamFilterId = "";
  }
  renderFilterRow(episodeStreamFilterList, filters, episodeStreamFilterId, id => {
    episodeStreamFilterId = id;
    renderEpisodeStreams();
  });

  episodeStreamList.textContent = "";
  let items = normalizeItems(state.episodeStreamItems);
  if (episodeStreamFilterId) {
    items = items.filter(item => String(item.filterId || "") === episodeStreamFilterId);
  }
  if (items.length === 0) {
    appendEmptyTrackState(
      episodeStreamList,
      state.episodeStreamsIsLoading ? "Loading streams..." : (state.noStreamsLabel || "No streams found"),
    );
    return;
  }
  items.forEach((item, index) => {
    const row = buildSourceRow(item, selected => {
      send("selectEpisodeStream", Number(selected.index) || 0);
      window.setTimeout(closePlayerModal, 120);
    });
    row.dataset.keyboardEpisodeStreamIndex = String(index);
    row.classList.toggle("keyboard-focused", keyboardPanelMode === "episodes" && keyboardEpisodeShowingStreams && index === keyboardEpisodeStreamIndex);
    episodeStreamList.appendChild(row);
  });
  if (keyboardPanelMode === "episodes") {
    window.requestAnimationFrame(() => focusKeyboardEpisodeStreamRow());
  }
};

const renderEpisodesModal = () => {
  const showStreams = Boolean(state.episodeStreamsVisible);
  if (keyboardPanelMode === "episodes" && showStreams !== keyboardEpisodeShowingStreams) {
    keyboardEpisodeStreamIndex = 0;
  }
  keyboardEpisodeShowingStreams = showStreams;
  episodeListView.hidden = showStreams;
  episodeStreamsView.hidden = !showStreams;
  if (showStreams) {
    renderEpisodeStreams();
  } else {
    renderEpisodeList();
  }
};

const visibleKeyboardEpisodes = () => {
  const selectedSeason = ensureEpisodeSeason();
  const items = normalizeItems(state.episodeItems);
  return selectedSeason == null
    ? items
    : items.filter(item => Number(item.season) === Number(selectedSeason));
};

const visibleKeyboardEpisodeStreams = () => {
  const items = normalizeItems(state.episodeStreamItems);
  return episodeStreamFilterId
    ? items.filter(item => String(item.filterId || "") === episodeStreamFilterId)
    : items;
};

const focusKeyboardEpisodeRow = () => {
  episodeFocusPositionKey = `${ensureEpisodeSeason()}:${keyboardEpisodeIndex}`;
  episodeList.querySelectorAll("[data-keyboard-episode-index]").forEach((candidate, index) => {
    candidate.classList.toggle("keyboard-focused", index === keyboardEpisodeIndex);
  });
  const row = episodeList.querySelector(`[data-keyboard-episode-index="${keyboardEpisodeIndex}"]`);
  if (!row) return;
  row.focus({ preventScroll: true });
  const safeInset = 20;
  const rowLeft = row.offsetLeft;
  const rowRight = rowLeft + row.offsetWidth;
  const visibleLeft = episodeList.scrollLeft + safeInset;
  const visibleRight = episodeList.scrollLeft + episodeList.clientWidth - safeInset;
  if (rowLeft < visibleLeft) {
    episodeList.scrollLeft = Math.max(0, rowLeft - safeInset);
  } else if (rowRight > visibleRight) {
    episodeList.scrollLeft = rowRight - episodeList.clientWidth + safeInset;
  }
};

const focusKeyboardEpisodeStreamRow = () => {
  const row = episodeStreamList.querySelector(`[data-keyboard-episode-stream-index="${keyboardEpisodeStreamIndex}"]`);
  if (!row) return;
  row.focus({ preventScroll: true });
  row.scrollIntoView({ block: "center", inline: "nearest" });
};

const moveKeyboardEpisode = delta => {
  const items = visibleKeyboardEpisodes();
  if (items.length === 0) return;
  keyboardEpisodeIndex = Math.max(0, Math.min(items.length - 1, keyboardEpisodeIndex + delta));
  focusKeyboardEpisodeRow();
};

const moveKeyboardEpisodeStream = delta => {
  const items = visibleKeyboardEpisodeStreams();
  if (items.length === 0) return;
  keyboardEpisodeStreamIndex = Math.max(0, Math.min(items.length - 1, keyboardEpisodeStreamIndex + delta));
  renderEpisodeStreams();
};

const moveKeyboardSeason = delta => {
  const seasons = normalizeItems(state.episodeSeasons);
  if (seasons.length === 0) return;
  const currentSeason = ensureEpisodeSeason();
  const currentIndex = Math.max(0, seasons.findIndex(season => Number(season.season) === Number(currentSeason)));
  const nextIndex = Math.max(0, Math.min(seasons.length - 1, currentIndex + delta));
  if (nextIndex === currentIndex) return;
  selectedEpisodeSeason = Number(seasons[nextIndex].season) || 0;
  keyboardEpisodeIndex = 0;
  renderEpisodeList();
  window.requestAnimationFrame(() => { episodeList.scrollLeft = 0; });
};

const setInputValue = (input, value) => {
  if (document.activeElement !== input && input.value !== value) {
    input.value = value;
  }
};

const renderSubmitIntroModal = () => {
  submitIntroPanelTitle.textContent = state.submitIntroPanelTitle || "Submit Timestamps";
  submitIntroCloseButton.textContent = state.panelCloseLabel || "Close";
  segmentTypeLabel.textContent = state.submitIntroSegmentTypeLabel || "SEGMENT TYPE";
  segmentIntroButton.textContent = state.submitIntroSegmentIntroLabel || "Intro";
  segmentRecapButton.textContent = state.submitIntroSegmentRecapLabel || "Recap";
  segmentOutroButton.textContent = state.submitIntroSegmentOutroLabel || "Outro";
  startTimeLabel.textContent = state.submitIntroStartTimeLabel || "START TIME (MM:SS)";
  endTimeLabel.textContent = state.submitIntroEndTimeLabel || "END TIME (MM:SS)";
  captureStartButton.textContent = state.submitIntroCaptureLabel || "Capture";
  captureEndButton.textContent = state.submitIntroCaptureLabel || "Capture";
  submitIntroCancelButton.textContent = state.cancelLabel || "Cancel";
  submitIntroSubmitButton.textContent = state.isSubmitIntroSubmitting
    ? `${state.submitIntroSubmitLabel || "Submit"}...`
    : (state.submitIntroSubmitLabel || "Submit");
  submitIntroSubmitButton.disabled = Boolean(state.isSubmitIntroSubmitting);

  [segmentIntroButton, segmentRecapButton, segmentOutroButton].forEach(button => {
    button.classList.toggle("selected", button.dataset.segment === submitIntroDraft.segmentType);
  });
  setInputValue(submitIntroStartInput, submitIntroDraft.startTime);
  setInputValue(submitIntroEndInput, submitIntroDraft.endTime);
  submitIntroStatus.textContent = submitIntroDraft.status || state.submitIntroStatusMessage || "";
};

const renderP2pConsentModal = () => {
  p2pConsentTitle.textContent = state.p2pConsentTitle || "P2P Streaming";
  p2pConsentBody.textContent = state.p2pConsentBody || "";
  p2pConsentCloseButton.textContent = state.p2pConsentCancelLabel || "Cancel";
  p2pConsentCancelButton.textContent = state.p2pConsentCancelLabel || "Cancel";
  p2pConsentEnableButton.textContent = state.p2pConsentEnableLabel || "Enable P2P";
};

const renderActiveModal = () => {
  if (activeModal === "audio") renderAudioTrackList();
  if (activeModal === "subtitles") renderSubtitleModal();
  if (activeModal === "sources") renderSourceModal();
  if (activeModal === "episodes") renderEpisodesModal();
  if (activeModal === "submitIntro") renderSubmitIntroModal();
  if (activeModal === "p2pConsent") renderP2pConsentModal();
};

window.nuvioNativeViewportChanged = () => {
  root.classList.add("native-resizing");
  window.clearTimeout(nativeViewportTimer);
  nativeViewportTimer = window.setTimeout(() => {
    root.classList.remove("native-resizing");
  }, 180);
  if (activeModal) renderActiveModal();
};

const trackListSignature = tracks =>
  normalizeTracks(tracks)
    .map(track => [
      track.id == null ? "" : String(track.id),
      track.index == null ? "" : String(track.index),
      track.label == null ? "" : String(track.label),
      track.language == null ? "" : String(track.language),
      Boolean(track.selected) ? "1" : "0",
    ].join(":"))
    .join("|");

const renderOpeningOverlay = suppress => {
  const progress = normalizedOpeningProgress();
  const artworkUrl = setImageSource(openingArtwork, state.openingArtwork);
  const logoUrl = setImageSource(openingLogoBase, state.openingLogo);
  setImageSource(openingLogoFill, state.openingLogo);

  const hasProgress = progress !== null;
  const openingBootstrap = !hasReceivedPlayerControls;
  const wantsOpening = Boolean(openingBootstrap || state.showOpeningOverlay);
  const showOpening = Boolean(!suppress && wantsOpening && state.isLoading);
  const titleText = String(state.openingTitle || state.title || "").trim();
  const messageText = String(state.openingMessage || "").trim();
  const showHorizontalProgress = hasProgress && !logoUrl;

  root.classList.toggle("opening-active", showOpening);
  openingOverlay.classList.toggle("visible", showOpening);
  openingOverlay.classList.toggle("has-artwork", Boolean(artworkUrl));
  openingOverlay.classList.toggle("has-progress", hasProgress);
  openingOverlay.setAttribute("aria-hidden", showOpening ? "false" : "true");
  openingBackButton.setAttribute("aria-label", state.closeLabel || "Close player");

  openingLogoSlot.hidden = !logoUrl;
  openingLogoFillClip.style.width = `${(progress || 0) * 100}%`;

  openingTitle.textContent = titleText;
  openingTitle.hidden = Boolean(logoUrl || !titleText);
  openingSpinner.hidden = Boolean(logoUrl || titleText);

  openingMessage.textContent = messageText;
  openingStatus.hidden = !(messageText || showHorizontalProgress);
  openingProgressTrack.hidden = !showHorizontalProgress;
  openingProgressBar.style.width = `${(progress || 0) * 100}%`;

  return showOpening;
};

const renderPlaybackError = () => {
  const messageText = playbackErrorText();
  const showError = Boolean(messageText);
  const titleText = String(state.playbackErrorTitle || "Playback error").trim();
  const actionText = String(state.playbackErrorActionLabel || "Go back").trim();

  root.classList.toggle("error-active", showError);
  playbackError.classList.toggle("visible", showError);
  playbackError.setAttribute("aria-hidden", showError ? "false" : "true");
  playbackError.setAttribute("aria-label", titleText || "Playback error");
  playbackErrorTitle.textContent = titleText || "Playback error";
  playbackErrorMessage.textContent = messageText;
  playbackErrorActionLabel.textContent = actionText || "Go back";
  playbackErrorAction.setAttribute("aria-label", actionText || "Go back");

  return showError;
};

const resetSkipPromptAutoHide = () => {
  window.clearTimeout(skipPromptAutoHideTimer);
  skipPromptAutoHideTimer = 0;
  skipPromptAutoHideActive = false;
  skipPromptAutoHidden = false;
  skipPromptProgress.style.transition = "none";
  skipPromptProgress.style.width = "0%";
};

const startSkipPromptAutoHide = () => {
  if (skipPromptAutoHideActive || skipPromptAutoHidden) return;
  skipPromptAutoHideActive = true;
  skipPromptProgress.style.transition = "none";
  skipPromptProgress.style.width = "0%";
  window.requestAnimationFrame(() => {
    window.requestAnimationFrame(() => {
      skipPromptProgress.style.transition = `width ${prefersReducedMotion ? 1 : 10000}ms linear`;
      skipPromptProgress.style.width = "100%";
    });
  });
  skipPromptAutoHideTimer = window.setTimeout(() => {
    skipPromptAutoHideActive = false;
    skipPromptAutoHidden = true;
    renderNativePlaybackPrompts();
  }, prefersReducedMotion ? 1 : 10000);
};

const renderNativePlaybackPrompts = () => {
  const nextSkipKey = [
    state.skipPromptStartMs || 0,
    state.skipPromptEndMs || 0,
    state.skipPromptLabel || "",
  ].join(":");
  if (
    nextSkipKey !== skipPromptKey ||
    !state.skipPromptVisible ||
    (skipPromptWasDismissed && !state.skipPromptDismissed)
  ) {
    skipPromptKey = nextSkipKey;
    resetSkipPromptAutoHide();
  }
  skipPromptWasDismissed = Boolean(state.skipPromptDismissed);

  // This prompt belongs to the active interval rather than to the transient player chrome.
  const showSkip = Boolean(state.skipPromptVisible && !state.skipPromptDismissed);
  const showSkipProgress = false;
  skipPromptLabel.textContent = state.skipPromptLabel || "Skip";
  skipPrompt.setAttribute("aria-label", state.skipPromptLabel || "Skip");
  skipPrompt.setAttribute("aria-hidden", showSkip ? "false" : "true");
  skipPrompt.classList.toggle("visible", showSkip);
  skipPrompt.classList.toggle("show-progress", showSkipProgress);
  window.clearTimeout(skipPromptAutoHideTimer);
  skipPromptAutoHideTimer = 0;
  skipPromptAutoHideActive = false;

  const showNextEpisode = Boolean(state.nextEpisodeVisible);
  const nextThumbUrl = setImageSource(nextEpisodeThumb, state.nextEpisodeThumbnail);
  nextEpisodeHeader.textContent = state.nextEpisodeHeaderLabel || "Next episode";
  nextEpisodeTitle.textContent = state.nextEpisodeTitle || "";
  nextEpisodeStatus.textContent = state.nextEpisodeStatus || "";
  nextEpisodeStatus.hidden = !state.nextEpisodeStatus;
  nextEpisodeAction.textContent = state.nextEpisodeActionLabel || "Play";
  nextEpisodeCard.setAttribute("aria-hidden", showNextEpisode ? "false" : "true");
  nextEpisodeCard.classList.toggle("visible", showNextEpisode);
  nextEpisodeCard.classList.toggle("playable", Boolean(state.nextEpisodePlayable));
  nextEpisodeCard.classList.toggle("has-thumb", Boolean(nextThumbUrl));
};

const isOpeningOverlayActive = () =>
  Boolean((!hasReceivedPlayerControls || state.showOpeningOverlay) && state.isLoading);

const isChromeInteractionTarget = target =>
  Boolean(target && target.closest && target.closest(chromeInteractionSelector));

const isInteractingWithChrome = () =>
  Boolean(isChromePointerInside || isChromePointerDown || isChromeFocusInside);

const canAutoHideChrome = showOpening => Boolean(
  state.controlsVisible &&
  state.isPlaying &&
  !state.isLoading &&
  !state.isLocked &&
  !activeModal &&
  !contextMenuOpen &&
  !isScrubbing &&
  !isInteractingWithChrome() &&
  !playbackErrorText() &&
  !showOpening,
);

const currentChromeAutoHideKey = showOpening => {
  if (!canAutoHideChrome(showOpening)) return "";
  return [
    chromeAutoHideActivity,
    state.controlsVisible ? "visible" : "hidden",
    state.isPlaying ? "playing" : "paused",
    state.isLoading ? "loading" : "ready",
    state.isLocked ? "locked" : "unlocked",
    activeModal || "none",
    isScrubbing ? "scrubbing" : "idle",
    isInteractingWithChrome() ? "interacting" : "idle-controls",
    showOpening ? "opening" : "ready",
  ].join(":");
};

const clearChromeAutoHideTimer = () => {
  window.clearTimeout(chromeAutoHideTimer);
  chromeAutoHideTimer = 0;
  chromeAutoHideKey = "";
};

const hideChromeFromAutoTimer = () => {
  if (!canAutoHideChrome(isOpeningOverlayActive())) return;
  state = { ...state, controlsVisible: false };
  renderChrome();
  send("hideChrome", 0);
};

const syncChromeAutoHideTimer = showOpening => {
  const key = currentChromeAutoHideKey(showOpening);
  if (!key) {
    clearChromeAutoHideTimer();
    return;
  }
  if (chromeAutoHideKey === key) return;

  window.clearTimeout(chromeAutoHideTimer);
  chromeAutoHideKey = key;
  chromeAutoHideTimer = window.setTimeout(() => {
    chromeAutoHideTimer = 0;
    if (currentChromeAutoHideKey(isOpeningOverlayActive()) !== key) return;
    chromeAutoHideKey = "";
    hideChromeFromAutoTimer();
  }, chromeAutoHideDelayMs);
};

const noteChromeActivity = (force = false) => {
  if (!state.controlsVisible || state.isLocked) return;
  const now = window.performance ? window.performance.now() : Date.now();
  if (!force && now - chromeInteractionLastNotedAt < chromeActivityThrottleMs) {
    syncChromeAutoHideTimer(isOpeningOverlayActive());
    return;
  }
  chromeInteractionLastNotedAt = now;
  chromeAutoHideActivity += 1;
  syncChromeAutoHideTimer(isOpeningOverlayActive());
};

const updateChromePointerInside = inside => {
  if (isChromePointerInside === inside) return;
  isChromePointerInside = inside;
  syncChromeInteractionToHost();
  noteChromeActivity(true);
};

const syncChromeInteractionToHost = () => {
  const active = isInteractingWithChrome();
  if (hostChromeInteractionActive === active) return;
  hostChromeInteractionActive = active;
  send("chromeInteraction", active ? 1 : 0);
};

const finishChromePointerInteraction = event => {
  isChromePointerDown = false;
  if (event && event.type !== "pointercancel") {
    isChromePointerInside = isChromeInteractionTarget(event.target);
  } else {
    isChromePointerInside = false;
  }
  syncChromeInteractionToHost();
  clearPressedButton();
  noteChromeActivity(true);
};

const renderChrome = () => {
  const durationMs = Math.max(0, Number(state.durationMs) || 0);
  const positionMs = isScrubbing ? scrubPositionMs : Math.max(0, Number(state.positionMs) || 0);
  const isPlaying = Boolean(state.isPlaying);
  const showError = renderPlaybackError();
  const pictureInPictureActive = Boolean(state.pictureInPictureActive);
  root.classList.toggle("pip-active", pictureInPictureActive);
  root.classList.toggle("locked", Boolean(state.isLocked));
  root.classList.toggle("episode-panel-open", activeModal === "episodes");
  root.classList.toggle("source-panel-open", activeModal === "sources");
  root.classList.toggle("legacy-hud", Boolean(state.legacyHudEnabled));
  root.classList.toggle("clock-always-visible", Boolean(state.alwaysShowClock));
  root.classList.toggle("mpv-diagnostics", Boolean(mpvDiagnosticsEnabled));
  applyUserUiScale(state.uiScalePercent);
  root.classList.toggle("locked-visible", Boolean(state.isLocked && state.lockedOverlayVisible));
  // Playback failures are a compact notification now. Keep the normal chrome and cursor visible
  // so Back remains immediately available instead of turning the error into a modal takeover.
  const isChromeHidden = Boolean(!pictureInPictureActive && !showError && (!activeModal && !contextMenuOpen && !state.controlsVisible && !(state.isLocked && state.lockedOverlayVisible)));
  root.classList.toggle("chrome-hidden", isChromeHidden);
  if (isChromeHidden || activeModal) hideControlTooltip();
  // Never hide the cursor in hero-trailer mode — it's a background surface, not the
  // focused player, so the OS/app cursor must behave normally.
  if (!isHeroTrailerSurface && !state.heroTrailerMode && isChromeHidden !== lastCursorHidden) {
    lastCursorHidden = isChromeHidden;
    send("cursorVisibility", isChromeHidden ? 0 : 1);
  }
  root.classList.toggle("source-visible", Boolean(!showError && !isPlaying && !state.isLoading && (state.streamTitle || state.providerName)));
  const showOpening = renderOpeningOverlay(showError);
  renderPauseMetadataOverlay(showOpening || showError);
  syncParentalGuide(showOpening || showError);

  title.textContent = state.title || "";
  setText(episode, normalizeEpisodeDisplayText(state.episodeText));
  setText(streamTitle, state.streamTitle);
  setText(providerName, state.providerName);
  resizeLabel.textContent = state.resizeModeLabel || "Fit";
  speedLabel.textContent = state.playbackSpeedLabel || "1x";
  subtitlesLabel.textContent = state.subtitlesLabel || "Subs";
  audioLabel.textContent = state.audioLabel || "Audio";
  sourcesLabel.textContent = state.sourcesLabel || "Sources";
  episodesLabel.textContent = state.episodesLabel || "Episodes";
  episodeNotchLabel.textContent = state.episodesLabel || "Episodes";
  lockedLabel.textContent = state.tapToUnlockLabel || "Tap to unlock";
  const showBuffering = Boolean(!showError && state.isLoading && !state.isLocked && !activeModal && !showOpening);
  bufferingStatus.classList.toggle("visible", showBuffering);
  bufferingStatus.setAttribute("aria-hidden", showBuffering ? "false" : "true");

  setVisible(submitIntroButton, Boolean(state.showSubmitIntro));
  setVisible(videoSettingsButton, Boolean(state.showVideoSettings));
  setVisible(sourcesButton, Boolean(state.showSources));
  setVisible(episodesButton, Boolean(state.showEpisodes));
  setVisible(episodeNotch, Boolean(state.showEpisodes));
  setVisible(sourceNotch, Boolean(state.showSources));
  document.querySelectorAll(".episode-skip").forEach(button => setVisible(button, Boolean(state.showEpisodes)));

  const playPauseLabel = isPlaying ? state.pauseLabel : state.playLabel;
  if (toggle) {
    toggle.setAttribute("aria-label", playPauseLabel || (isPlaying ? "Pause" : "Play"));
  }
  if (toggleIcon) {
    toggleIcon.setAttribute("href", isPlaying ? "#icon-pause" : "#icon-play");
  }
  lockButton.setAttribute("aria-label", state.isLocked ? state.unlockLabel : state.lockLabel);
  lockIcon.setAttribute("href", state.isLocked ? "#icon-lock-open" : "#icon-lock");
  backButton.setAttribute("aria-label", state.closeLabel || "Close player");
  submitIntroButton.setAttribute("aria-label", state.submitIntroLabel || "Submit Intro");
  videoSettingsButton.setAttribute("aria-label", state.videoSettingsLabel || "Video settings");
  const pictureInPictureLabel = state.pictureInPictureActive
    ? "Exit picture in picture"
    : (state.pictureInPictureLabel || "Picture in picture");
  pictureInPictureButton.setAttribute("aria-label", pictureInPictureLabel);
  pictureInPictureButton.setAttribute("title", pictureInPictureLabel);
  pictureInPictureButton.classList.toggle("selected", Boolean(state.pictureInPictureActive));
  pictureInPictureExitButton.setAttribute("aria-label", "Exit picture in picture");
  const pictureInPicturePlaybackLabel = isPlaying ? (state.pauseLabel || "Pause") : (state.playLabel || "Play");
  pictureInPicturePlayButton.setAttribute("aria-label", pictureInPicturePlaybackLabel);
  pictureInPicturePlayButton.setAttribute("title", pictureInPicturePlaybackLabel);
  pictureInPictureToggleIcon.setAttribute("href", isPlaying ? "#icon-pause" : "#icon-play");
  seek.disabled = Boolean(state.isLocked);
  setProgress(positionMs, durationMs);
  renderChapterMarkers(durationMs);
  if (showError) {
    skipPrompt.classList.remove("visible", "show-progress");
    skipPrompt.setAttribute("aria-hidden", "true");
    nextEpisodeCard.classList.remove("visible");
    nextEpisodeCard.setAttribute("aria-hidden", "true");
  } else {
    renderNativePlaybackPrompts();
  }
  syncChromeAutoHideTimer(showOpening);
  updatePlayerClock();
};

const playerClockFormatter = new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit" });
const updatePlayerClock = () => {
  if (!playerClockTime || !playerEndTime) return;
  const now = new Date();
  playerClockTime.textContent = playerClockFormatter.format(now);
  const durationMs = Math.max(0, Number(state.durationMs) || 0);
  const positionMs = Math.max(0, Number(state.positionMs) || 0);
  const parsedSpeed = Number.parseFloat(String(state.playbackSpeedLabel || "1").replace(/[^0-9.]/g, ""));
  const speed = Number.isFinite(parsedSpeed) && parsedSpeed > 0 ? parsedSpeed : 1;
  const remainingWallMs = Math.max(0, durationMs - positionMs) / speed;
  const endTime = new Date(now.getTime() + remainingWallMs);
  const contentLabel = String(state.episodeText || "").trim() ? "Episode" : "Movie";
  playerEndTime.textContent = `${contentLabel} ends at ${playerClockFormatter.format(endTime)}`;
};
window.setInterval(updatePlayerClock, 1000);

const heroTrailerFade = document.createElement("div");
heroTrailerFade.id = "heroTrailerFade";
heroTrailerFade.style.display = "none";
root.appendChild(heroTrailerFade);

const heroTrailerContent = document.createElement("div");
heroTrailerContent.id = "heroTrailerContent";
heroTrailerContent.innerHTML =
  '<img id="heroTrailerLogo" alt="">' +
  '<div id="heroTrailerTitle"></div>' +
  '<div id="heroTrailerMeta"></div>' +
  '<div id="heroTrailerDescription"></div>';
heroTrailerContent.style.display = "none";
root.appendChild(heroTrailerContent);

const heroTrailerChrome = document.createElement("div");
heroTrailerChrome.id = "heroTrailerChrome";
heroTrailerChrome.innerHTML =
  '<button class="hero-trailer-button" type="button" data-command="back" aria-label="Stop trailer">' +
  '<svg><use href="#icon-close"></use></svg>' +
  '</button>' +
  '<button class="hero-trailer-button" type="button" data-command="heroTrailerMute" aria-label="Mute trailer">' +
  '<svg><use id="heroTrailerMuteIcon" href="#icon-volume-mute"></use></svg>' +
  '</button>' +
  '<input id="heroTrailerVolumeSlider" class="hero-trailer-volume" type="range" min="0" max="100" step="1" value="0" aria-label="Trailer volume">';
heroTrailerChrome.style.display = "none";
root.appendChild(heroTrailerChrome);

const heroTrailerLogo = heroTrailerContent.querySelector("#heroTrailerLogo");
const heroTrailerTitle = heroTrailerContent.querySelector("#heroTrailerTitle");
const heroTrailerMeta = heroTrailerContent.querySelector("#heroTrailerMeta");
const heroTrailerDescription = heroTrailerContent.querySelector("#heroTrailerDescription");
const heroTrailerMuteIcon = heroTrailerChrome.querySelector("#heroTrailerMuteIcon");
const heroTrailerVolumeSlider = heroTrailerChrome.querySelector("#heroTrailerVolumeSlider");
const clampHeroVolume = value => Math.max(0, Math.min(100, Math.round(Number(value) || 0)));
// A passive trailer surface must never keep OS keyboard focus. Clicking the WebView2 chrome can
// still focus it, and Compose can't pull that focus back off a live native child on its own — so
// once an interaction ends we blur the element and ask native to move OS focus back to the app
// window (see player_bridge.cpp's reclaimHostKeyboardFocus). Buttons additionally preventDefault on
// mousedown so they never take focus in the first place (the click still fires); the slider keeps
// its native drag (which uses pointer capture, not focus) and reclaims once the drag ends.
function reclaimHeroTrailerFocus() {
  const active = document.activeElement;
  if (active && typeof active.blur === "function") active.blur();
  send("heroTrailerReclaimFocus", 0);
}
heroTrailerChrome.querySelectorAll(".hero-trailer-button").forEach(button => {
  button.addEventListener("mousedown", event => event.preventDefault());
  button.addEventListener("click", () => reclaimHeroTrailerFocus());
});
if (heroTrailerVolumeSlider) {
  const onVolumeInput = event => {
    event.stopPropagation();
    const v = clampHeroVolume(heroTrailerVolumeSlider.value);
    state.heroTrailerVolume = v;
    state.heroTrailerMuted = v <= 0;
    heroTrailerMuteIcon.setAttribute("href", v <= 0 ? "#icon-volume-mute" : "#icon-volume");
    send("heroTrailerVolume", v);
  };
  heroTrailerVolumeSlider.addEventListener("input", onVolumeInput);
  heroTrailerVolumeSlider.addEventListener("change", onVolumeInput);
  // Keep drags/clicks on the slider from bubbling to the surface (which would
  // toggle/dismiss the trailer).
  ["click", "pointerdown", "mousedown"].forEach(type => {
    heroTrailerVolumeSlider.addEventListener(type, event => event.stopPropagation());
  });
  // The slider's native drag needs focus while dragging, so reclaim once it ends rather than
  // fighting the drag mid-gesture.
  ["pointerup", "lostpointercapture", "change"].forEach(type => {
    heroTrailerVolumeSlider.addEventListener(type, () => reclaimHeroTrailerFocus());
  });
}
// A home hero trailer's heavyweight video surface paints over the Compose navbar. When the
// pointer enters the edge band where the navbar lives (top for the floating top bar, left for the
// sidebar — pushed via state.heroTrailerNavDismissEdge), tell Kotlin to stop the trailer so the
// navbar is uncovered and usable. Bands are intentionally generous (that region is empty video,
// with the trailer's own chrome kept to the bottom corners) and can be tuned later.
let heroTrailerNavDismissArmed = true;
const heroTrailerNavDismissBand = () => {
  const edge = String(state.heroTrailerNavDismissEdge || "none");
  // The overlay surface matches the hero region, so a fraction of innerHeight scales with the
  // hero size. The fraction is pushed per-mode from Kotlin (adaptive hero is a small strip and
  // needs a tighter band than TV mode's full-viewport hero).
  const topFrac = Number(state.heroTrailerNavDismissBandFraction);
  if (edge === "top") return { edge, size: Math.round(window.innerHeight * (topFrac > 0 ? topFrac : 0.22)) };
  if (edge === "left") return { edge, size: Math.min(120, Math.round(window.innerWidth * 0.10)) };
  return { edge: "none", size: 0 };
};
// The floating top bar is centered and narrow, so the top trigger is limited to the central
// slice of the width (not the whole top edge). The sidebar spans the full left edge height.
const heroTrailerNavTopCenterFraction = 0.40;
window.addEventListener("mousemove", event => {
  if (!isHeroTrailerSurface && !state.heroTrailerMode) return;
  const { edge, size } = heroTrailerNavDismissBand();
  if (edge === "none" || size <= 0) return;
  let inBand;
  if (edge === "top") {
    const halfSpan = (window.innerWidth * heroTrailerNavTopCenterFraction) / 2;
    inBand = event.clientY <= size && Math.abs(event.clientX - window.innerWidth / 2) <= halfSpan;
  } else {
    inBand = event.clientX <= size;
  }
  if (!inBand) {
    heroTrailerNavDismissArmed = true;
    return;
  }
  // Fire once per entry; the surface unmounts on dismissal so this only matters transiently.
  if (heroTrailerNavDismissArmed) {
    heroTrailerNavDismissArmed = false;
    send("heroTrailerNavChromeDismiss", 1);
  }
}, { passive: true });
let heroTrailerLogoFailed = false;
heroTrailerLogo.addEventListener("error", () => {
  heroTrailerLogoFailed = true;
  applyHeroTrailerContent();
});

const parseHeroTrailerRgb = value => {
  const raw = String(value || "").trim();
  const match = /^#?([0-9a-fA-F]{6})$/.exec(raw);
  if (!match) return { r: 0, g: 0, b: 0 };
  const int = parseInt(match[1], 16);
  return { r: (int >> 16) & 255, g: (int >> 8) & 255, b: int & 255 };
};

const setHeroTrailerLine = (element, text) => {
  const value = String(text || "").trim();
  element.textContent = value;
  element.style.display = value ? "" : "none";
};

function applyHeroTrailerContent() {
  const logoUrl = String(state.heroTrailerLogoUrl || "").trim();
  const title = String(state.heroTrailerTitle || "").trim();
  if (heroTrailerLogo.getAttribute("src") !== logoUrl) {
    heroTrailerLogoFailed = false;
    if (logoUrl) {
      heroTrailerLogo.setAttribute("src", logoUrl);
    } else {
      heroTrailerLogo.removeAttribute("src");
    }
  }
  const useLogo = Boolean(logoUrl) && !heroTrailerLogoFailed;
  heroTrailerLogo.style.display = useLogo ? "block" : "none";
  setHeroTrailerLine(heroTrailerTitle, useLogo ? "" : title);
  setHeroTrailerLine(heroTrailerMeta, state.heroTrailerMeta);
  setHeroTrailerLine(heroTrailerDescription, state.heroTrailerDescription);
}

const applyHeroTrailer = () => {
  const active = Boolean(state.heroTrailerMode);
  root.classList.toggle("hero-trailer-active", active);
  if (!active) {
    heroTrailerFade.style.display = "none";
    heroTrailerContent.style.display = "none";
    heroTrailerChrome.style.display = "none";
    return;
  }
  // Make sure the cursor is restored if it had been hidden before entering hero mode.
  if (lastCursorHidden) {
    lastCursorHidden = false;
    send("cursorVisibility", 1);
  }
  const { r, g, b } = parseHeroTrailerRgb(state.heroTrailerBackgroundColor);
  const opaque = `rgb(${r}, ${g}, ${b})`;
  const soft = `rgba(${r}, ${g}, ${b}, 0.55)`;
  const clear = `rgba(${r}, ${g}, ${b}, 0)`;
  // Netflix-style scrim: a tall bottom gradient for text legibility, a left-edge fade so
  // the title column reads cleanly, and a light top fade — all blending to the hero bg.
  heroTrailerFade.style.backgroundImage =
    `linear-gradient(to top, ${opaque} 0%, ${soft} 26%, ${clear} 58%), ` +
    `linear-gradient(to right, ${opaque} 0%, ${soft} 18%, ${clear} 46%), ` +
    `linear-gradient(to bottom, ${soft} 0%, ${clear} 22%)`;
  heroTrailerFade.style.display = "block";
  applyHeroTrailerContent();
  heroTrailerContent.style.display = "flex";
  heroTrailerChrome.style.display = "flex";
  const heroVol = clampHeroVolume(state.heroTrailerVolume);
  heroTrailerMuteIcon.setAttribute("href", heroVol <= 0 ? "#icon-volume-mute" : "#icon-volume");
  // Don't fight the user mid-drag.
  if (heroTrailerVolumeSlider && document.activeElement !== heroTrailerVolumeSlider) {
    const next = String(heroVol);
    if (heroTrailerVolumeSlider.value !== next) heroTrailerVolumeSlider.value = next;
  }
};

// Lightweight volume push for programmatic changes (the [ / ] keyboard shortcuts). Volume is
// excluded from the controls "structure key" so a slider drag doesn't re-send the whole controls
// JSON every frame — which means a keyboard volume change never reaches applyHeroTrailer. This
// setter updates just the slider + mute icon so the overlay reflects [ / ] immediately.
window.nuvioSetHeroTrailerVolume = function (value) {
  const vol = clampHeroVolume(value);
  state.heroTrailerVolume = vol;
  state.heroTrailerMuted = vol <= 0;
  if (heroTrailerMuteIcon) {
    heroTrailerMuteIcon.setAttribute("href", vol <= 0 ? "#icon-volume-mute" : "#icon-volume");
  }
  if (heroTrailerVolumeSlider && document.activeElement !== heroTrailerVolumeSlider) {
    const next = String(vol);
    if (heroTrailerVolumeSlider.value !== next) heroTrailerVolumeSlider.value = next;
  }
};

const render = () => {
  applyTheme();
  applyHeroTrailer();
  renderChrome();
  renderActiveModal();
};

const focusShortcutRoot = () => {
  // Hero-trailer mode is a passive background surface; never steal keyboard focus from
  // the host app (otherwise home navigation breaks).
  if (isHeroTrailerSurface || state.heroTrailerMode) return;
  if (document.activeElement !== root) {
    root.focus({ preventScroll: true });
  }
};

const isTextEntryTarget = target => {
  const element = target && target.closest && target.closest("input, textarea, select, [contenteditable='true']");
  return Boolean(element);
};

// Kotlin sends key bindings as java.awt `VK_` codes. Browser `KeyboardEvent.keyCode` agrees for
// letters/digits/space/F-keys but diverges for punctuation and a few specials, so translate the
// event to its AWT equivalent before matching — otherwise punctuation bindings (e.g. the default
// [ / ] speed keys) silently stop working whenever this overlay holds OS focus (it takes focus on
// any HUD click, e.g. the seek bar), while letter bindings keep working. The 91/92/93 rows also
// stop the OS/context-menu keys from colliding with AWT's bracket/backslash codes.
const JS_TO_AWT_KEYCODE = {
  13: 10, // Enter
  45: 155, // Insert (raw 45 collides with AWT VK_MINUS)
  46: 127, // Delete (raw 46 collides with AWT VK_PERIOD)
  91: 524, // Left OS/Meta (raw 91 collides with AWT VK_OPEN_BRACKET)
  92: 524, // Right OS/Meta (raw 92 collides with AWT VK_BACK_SLASH)
  93: 525, // Context menu (raw 93 collides with AWT VK_CLOSE_BRACKET)
  186: 59, // ;
  187: 61, // =
  188: 44, // ,
  189: 45, // -
  190: 46, // .
  191: 47, // /
  219: 91, // [
  220: 92, // \
  221: 93, // ]
};
const awtKeyCodeForEvent = event =>
  Object.prototype.hasOwnProperty.call(JS_TO_AWT_KEYCODE, event.keyCode)
    ? JS_TO_AWT_KEYCODE[event.keyCode]
    : event.keyCode;

const shortcutCommandForEvent = event => {
  if (event.metaKey || event.ctrlKey || event.altKey) return "";
  const eventKeyCode = awtKeyCodeForEvent(event);
  const configuredBindings = state.playerShortcutKeyCodes || {};
  const configuredAction = Object.keys(configuredBindings)
    .find(action => Number(configuredBindings[action]) === eventKeyCode);
  const configuredCommand = {
    play_pause: "keyboardToggle",
    alternate_play_pause: "keyboardToggle",
    toggle_mute: "keyboardToggleMute",
    seek_backward: "keyboardSeekBack",
    seek_forward: "keyboardSeekForward",
    speed_up: "keyboardSpeedUp",
    speed_down: "keyboardSpeedDown",
    next_subtitle: "keyboardNextSubtitle",
    next_audio: "keyboardNextAudio",
    open_sources: "keyboardOpenSources",
    open_episodes: "keyboardOpenEpisodes",
    cycle_zoom: "resize",
    skip_interval: "keyboardSkipInterval",
    cycle_svp: "keyboardCycleAnimeSvp",
    cycle_hdr: "keyboardCycleHdrMode",
    cycle_color_profile: "keyboardCycleColorProfile",
    cycle_anime: "keyboardCycleAnimeMode",
    toggle_mpv_diagnostics: "keyboardToggleMpvDiagnostics",
  }[configuredAction];
  if (configuredCommand) return configuredCommand;
  switch (event.code) {
    case "ArrowLeft":
      return "keyboardSeekBack";
    case "ArrowRight":
      return "keyboardSeekForward";
    case "ArrowUp":
      return "volumeUp";
    case "ArrowDown":
      return "volumeDown";
    default:
      return "";
  }
};

let volumePillHideTimer = null;
let lastCursorHidden = null;

const volumeIconHref = () => {
  // Volume can exceed 100% (up to 200%), so the wave count is scaled for that range:
  // one wave 1-60, two waves 61-119, three waves 120+.
  if (localMuted || localVolume <= 0) return "#icon-volume-mute";
  if (localVolume <= 60) return "#icon-volume-low";
  if (localVolume <= 119) return "#icon-volume";
  return "#icon-volume-high";
};

const syncPlayerVolumeControl = () => {
  if (playerVolumeSlider) {
    playerVolumeSlider.value = String(Math.round(localVolume));
  }
  playerVolumeIcon?.setAttribute("href", volumeIconHref());
};

const showVolumePill = () => {
  if (!volumePill) return;
  const percentage = Math.round(localVolume);
  volumePillLabel.textContent = `${percentage}%`;
  if (volumePillIcon) {
    volumePillIcon.setAttribute("href", volumeIconHref());
  }
  volumePill.classList.add("visible");
  window.clearTimeout(volumePillHideTimer);
  volumePillHideTimer = window.setTimeout(() => {
    volumePill.classList.remove("visible");
  }, 900);
};

const adjustLocalVolume = deltaPercent => {
  localVolume = Math.max(0, Math.min(200, localVolume + deltaPercent));
  syncPlayerVolumeControl();
  showVolumePill();
};

window.nuvioShowVolumePill = (percentage, muted = false) => {
  localVolume = Math.max(0, Math.min(200, Number(percentage) || 0));
  localMuted = Boolean(muted);
  refreshContextMenuIndicators();
  syncPlayerVolumeControl();
  showVolumePill();
};

// Silently align the overlay's tracked volume with mpv's actual volume, without flashing the pill.
// Each episode spins up a fresh mpv instance whose volume is restored from the previous episode
// (e.g. 0%), but this page reloads with localVolume defaulting to 100 — so the next keyboard nudge
// would render "105%" instead of "5%". Native pushes the real value on fileLoaded to keep them synced.
window.nuvioSyncVolume = (percentage, muted = false) => {
  localVolume = Math.max(0, Math.min(200, Number(percentage) || 0));
  localMuted = Boolean(muted);
  refreshContextMenuIndicators();
  syncPlayerVolumeControl();
};

window.nuvioSyncMute = muted => {
  localMuted = Boolean(muted);
  refreshContextMenuIndicators();
  syncPlayerVolumeControl();
};

const presetPill = document.getElementById("presetPill");
const presetPillTitle = document.getElementById("presetPillTitle");
const presetPillValue = document.getElementById("presetPillValue");
let presetPillHideTimer = null;

window.nuvioShowPresetPill = (title, value, durationMs) => {
  if (!presetPill) return;
  if (presetPillTitle) presetPillTitle.textContent = String(title == null ? "" : title);
  if (presetPillValue) presetPillValue.textContent = String(value == null ? "" : value);
  presetPill.classList.add("visible");
  window.clearTimeout(presetPillHideTimer);
  const holdMs = Number.isFinite(durationMs) && durationMs > 0 ? durationMs : 1400;
  presetPillHideTimer = window.setTimeout(() => {
    presetPill.classList.remove("visible");
  }, holdMs);
};

const hideControlTooltip = () => {
  if (!controlTooltip) return;
  controlTooltip.hidden = true;
  controlTooltip.textContent = "";
};

const showControlTooltip = button => {
  if (!controlTooltip || !button) return;
  const label = String(button.dataset.tooltip || button.getAttribute("aria-label") || "").trim();
  if (!label) return hideControlTooltip();
  const buttonRect = button.getBoundingClientRect();
  const rootRect = root.getBoundingClientRect();
  const tooltipScale = appliedCombinedUserScale || (1 + (appliedUiScalePercent || 0) / 100);
  controlTooltip.textContent = label;
  controlTooltip.style.left = `${Math.max(70, Math.min(rootRect.width - 70, buttonRect.left - rootRect.left + buttonRect.width / 2))}px`;
  controlTooltip.style.top = `${buttonRect.top - rootRect.top - 8 * tooltipScale}px`;
  controlTooltip.hidden = false;
};

if (actionRow && controlTooltip) {
  actionRow.querySelectorAll("button").forEach(button => {
    button.dataset.tooltip = button.getAttribute("title") || button.getAttribute("aria-label") || "";
    button.removeAttribute("title");
  });
  actionRow.addEventListener("pointerover", event => {
    const button = event.target.closest("button");
    if (button && actionRow.contains(button)) showControlTooltip(button);
  });
  actionRow.addEventListener("pointerout", event => {
    const button = event.target.closest("button");
    if (!button || button.contains(event.relatedTarget)) return;
    hideControlTooltip();
  });
  actionRow.addEventListener("pointerleave", hideControlTooltip);
}

const toggleChrome = () => {
  if (playbackErrorText()) return;
  if (state.isLocked) {
    send("revealLockedOverlay", 0);
    return;
  }
  const nextControlsVisible = !state.controlsVisible;
  if (nextControlsVisible) {
    chromeAutoHideActivity += 1;
  } else {
    clearChromeAutoHideTimer();
  }
  state = { ...state, controlsVisible: nextControlsVisible };
  renderChrome();
  send("toggleChrome", 0);
};

const revealChromeForPlaybackInteraction = () => {
  if (state.isLocked) return;
  if (state.controlsVisible) {
    noteChromeActivity(true);
    return;
  }
  chromeAutoHideActivity += 1;
  state = { ...state, controlsVisible: true };
  renderChrome();
  send("revealChrome", 0);
};

const clearPressedButton = () => {
  if (!pressedButton) return;
  pressedButton.classList.remove("is-pressed");
  pressedButton = null;
};

document.addEventListener("pointerdown", event => {
  const interactingWithChrome = isChromeInteractionTarget(event.target);
  if (interactingWithChrome) {
    isChromePointerDown = true;
    isChromePointerInside = true;
    syncChromeInteractionToHost();
    noteChromeActivity(true);
  }
  if (!isTextEntryTarget(event.target)) {
    focusShortcutRoot();
    if (!interactingWithChrome) {
      noteChromeActivity(true);
    }
  }
  const button = event.target.closest("button");
  if (!button || button.disabled) return;
  clearPressedButton();
  pressedButton = button;
  button.classList.add("is-pressed");
}, true);

document.addEventListener("pointermove", event => {
  const inside = isChromeInteractionTarget(event.target);
  updateChromePointerInside(inside);
  if (!state.isLocked) {
    if (state.mouseMoveRevealsControlsEnabled && !state.controlsVisible) {
      chromeAutoHideActivity += 1;
      state = { ...state, controlsVisible: true };
      renderChrome();
      send("revealChrome", 0);
    } else if (state.controlsVisible) {
      // Every movement postpones auto-hide, including movement over buttons, their SVG children,
      // the seek bar, and other interactive chrome.
      noteChromeActivity();
    }
  }
}, true);

document.addEventListener("pointerup", finishChromePointerInteraction, true);
document.addEventListener("pointercancel", finishChromePointerInteraction, true);
document.addEventListener("dragend", clearPressedButton, true);
// pointerleave does not bubble, but a capture listener on document sees it for every descendant.
// That used to mark the pointer outside while merely moving between an icon and its button.
root.addEventListener("pointerleave", () => {
  updateChromePointerInside(false);
});
document.addEventListener("focusin", event => {
  isChromeFocusInside = isChromeInteractionTarget(event.target);
  syncChromeInteractionToHost();
  if (isChromeFocusInside) {
    noteChromeActivity(true);
  }
}, true);
document.addEventListener("focusout", () => {
  window.setTimeout(() => {
    isChromeFocusInside = isChromeInteractionTarget(document.activeElement);
    syncChromeInteractionToHost();
    noteChromeActivity(true);
  }, 0);
}, true);
window.addEventListener("blur", () => {
  isChromePointerInside = false;
  isChromePointerDown = false;
  isChromeFocusInside = false;
  syncChromeInteractionToHost();
  clearPressedButton();
  syncChromeAutoHideTimer(isOpeningOverlayActive());
});

document.querySelectorAll("[data-command]").forEach(button => {
  button.addEventListener("click", event => {
    event.stopPropagation();
    noteChromeActivity(true);
    const command = button.dataset.command;
    if (command === "audio") {
      openPlayerModal("audio");
      return;
    }
    if (command === "speed") {
      stepPlaybackSpeed(1);
      return;
    }
    if (command === "resize") {
      cycleAspectFromControls();
      return;
    }
    if (command === "subtitles") {
      openPlayerModal("subtitles");
      return;
    }
    if (command === "sources") {
      sourceFilterId = "";
      openPlayerModal("sources");
      send("sources", 0);
      return;
    }
    if (command === "episodes") {
      episodeStreamFilterId = "";
      keyboardPanelMode = "episodes";
      send("keyboardPanelOpened", 0);
      keyboardEpisodeShowingStreams = false;
      episodeFocusPositionKey = "";
      const currentEpisode = normalizeItems(state.episodeItems).find(item => Boolean(item.isCurrent));
      const currentSeason = normalizeItems(state.episodeSeasons).find(season => Boolean(season.isSelected));
      selectedEpisodeSeason = currentEpisode
        ? Number(currentEpisode.season)
        : (currentSeason ? Number(currentSeason.season) : null);
      const seasonItems = visibleKeyboardEpisodes();
      const currentIndex = seasonItems.findIndex(item => Boolean(item.isCurrent));
      keyboardEpisodeIndex = currentIndex >= 0 ? currentIndex : 0;
      openPlayerModal("episodes");
      send("episodes", 0);
      return;
    }
    if (command === "submitIntro") {
      openPlayerModal("submitIntro");
      return;
    }
    send(command, 0);
  });
});

openingOverlay.addEventListener("click", event => {
  event.stopPropagation();
  if (!event.target.closest("button,input")) {
    toggleChrome();
  }
});

modalElements.forEach(modal => {
  modal.addEventListener("click", event => {
    event.stopPropagation();
    if (event.target === modal) closePlayerModal(true);
  });
});

const selectSubtitleTab = (tab, index) => {
  state = { ...state, subtitleActiveTab: tab };
  renderSubtitleModal();
  send("subtitleTab", index);
};

subtitleBuiltInTab.addEventListener("click", event => {
  event.stopPropagation();
  selectSubtitleTab("BuiltIn", 0);
});
subtitleAddonsTab.addEventListener("click", event => {
  event.stopPropagation();
  selectSubtitleTab("Addons", 1);
});
subtitleStyleTab.addEventListener("click", event => {
  event.stopPropagation();
  selectSubtitleTab("Style", 2);
});
subtitleDelayMinus.addEventListener("click", event => {
  event.stopPropagation();
  send("subtitleDelayDelta", -100);
});
subtitleDelayPlus.addEventListener("click", event => {
  event.stopPropagation();
  send("subtitleDelayDelta", 100);
});
subtitleDelayReset.addEventListener("click", event => {
  event.stopPropagation();
  send("subtitleDelayReset", 0);
});
autoSyncReload.addEventListener("click", event => {
  event.stopPropagation();
  send("subtitleAutoSyncReload", 0);
});
autoSyncCapture.addEventListener("click", event => {
  event.stopPropagation();
  send("subtitleAutoSyncCapture", 0);
});
fontSizeMinus.addEventListener("click", event => {
  event.stopPropagation();
  send("subtitleFontSizeDelta", -2);
});
fontSizePlus.addEventListener("click", event => {
  event.stopPropagation();
  send("subtitleFontSizeDelta", 2);
});
if (fontFamilySelect) {
  fontFamilySelect.addEventListener("change", event => {
    event.stopPropagation();
    send("subtitleFontIndex", Number(fontFamilySelect.value) || 0);
  });
  // Keep clicks from bubbling up to the overlay (which would dismiss the panel).
  fontFamilySelect.addEventListener("click", event => event.stopPropagation());
}
outlineToggle.addEventListener("click", event => {
  event.stopPropagation();
  send("subtitleOutlineToggle", 0);
});
shadowToggle.addEventListener("click", event => {
  event.stopPropagation();
  send("subtitleShadowToggle", 0);
});
boldToggle.addEventListener("click", event => {
  event.stopPropagation();
  send("subtitleBoldToggle", 0);
});
bottomOffsetMinus.addEventListener("click", event => {
  event.stopPropagation();
  send("subtitleBottomOffsetDelta", -5);
});
bottomOffsetPlus.addEventListener("click", event => {
  event.stopPropagation();
  send("subtitleBottomOffsetDelta", 5);
});
textOpacityMinus.addEventListener("click", event => {
  event.stopPropagation();
  const style = state.subtitleStyle || {};
  const next = Math.max(0, Math.round((parseArgb(style.textColor).alpha / 255) * 100) - 10);
  send("subtitleTextOpacity", next);
});
textOpacityPlus.addEventListener("click", event => {
  event.stopPropagation();
  const style = state.subtitleStyle || {};
  const next = Math.min(100, Math.round((parseArgb(style.textColor).alpha / 255) * 100) + 10);
  send("subtitleTextOpacity", next);
});
subtitleStyleReset.addEventListener("click", event => {
  event.stopPropagation();
  send("subtitleStyleReset", 0);
});

sourceReloadButton.addEventListener("click", event => {
  event.stopPropagation();
  send("reloadSources", 0);
});
sourceCloseButton.addEventListener("click", event => {
  event.stopPropagation();
  closePlayerModal();
});
sourceList.addEventListener("scroll", () => {
  if (activeModal === "sources") {
    requestSourceVirtualRender();
  }
}, { passive: true });
episodesCloseButton.addEventListener("click", event => {
  event.stopPropagation();
  closePlayerModal();
});
episodeStreamsCloseButton.addEventListener("click", event => {
  event.stopPropagation();
  closePlayerModal();
});
episodeBackButton.addEventListener("click", event => {
  event.stopPropagation();
  episodeStreamFilterId = "";
  send("backToEpisodes", 0);
});
episodeReloadButton.addEventListener("click", event => {
  event.stopPropagation();
  send("reloadEpisodeStreams", 0);
});

window.nuvioOpenKeyboardPanel = panel => {
  if (panel === "sources") {
    keyboardPanelMode = "sources";
    send("keyboardPanelOpened", 0);
    keyboardSourceIndex = 0;
    sourceFilterId = "";
    openPlayerModal("sources");
    send("sources", 0);
    return;
  }
  if (panel === "episodes") {
    keyboardPanelMode = "episodes";
    send("keyboardPanelOpened", 0);
    keyboardEpisodeShowingStreams = false;
    keyboardEpisodeStreamIndex = 0;
    episodeStreamFilterId = "";
    const currentEpisode = normalizeItems(state.episodeItems).find(item => Boolean(item.isCurrent));
    const currentSeason = normalizeItems(state.episodeSeasons).find(season => Boolean(season.isSelected));
    selectedEpisodeSeason = currentEpisode
      ? Number(currentEpisode.season)
      : (currentSeason ? Number(currentSeason.season) : null);
    const items = visibleKeyboardEpisodes();
    const currentIndex = items.findIndex(item => Boolean(item.isCurrent));
    keyboardEpisodeIndex = currentIndex >= 0 ? currentIndex : 0;
    openPlayerModal("episodes");
    send("episodes", 0);
  }
};

window.nuvioHandleKeyboardPanelKey = code => {
  if (!keyboardPanelMode) return;
  if (code === "Escape") {
    closePlayerModal(true);
    return;
  }
  if (keyboardPanelMode === "sources") {
    if (code === "ArrowUp") moveKeyboardSource(-1);
    if (code === "ArrowDown") moveKeyboardSource(1);
    if (code === "Enter") {
      const item = sourceVirtualItems[keyboardSourceIndex];
      if (item) send("selectSource", Number(item.index) || 0);
    }
    return;
  }
  if (keyboardPanelMode === "episodes") {
    if (keyboardEpisodeShowingStreams) {
      if (code === "ArrowUp") moveKeyboardEpisodeStream(-1);
      if (code === "ArrowDown") moveKeyboardEpisodeStream(1);
      if (code === "ArrowLeft") send("backToEpisodes", 0);
      if (code === "Enter") {
        const item = visibleKeyboardEpisodeStreams()[keyboardEpisodeStreamIndex];
        if (item) send("selectEpisodeStream", Number(item.index) || 0);
      }
      return;
    }
    if (code === "ArrowLeft") moveKeyboardEpisode(-1);
    if (code === "ArrowRight") moveKeyboardEpisode(1);
    if (code === "ArrowUp") moveKeyboardSeason(1);
    if (code === "ArrowDown") moveKeyboardSeason(-1);
    if (code === "Enter") {
      const item = visibleKeyboardEpisodes()[keyboardEpisodeIndex];
      if (item) send("selectEpisode", Number(item.index) || 0);
    }
  }
};

const updateSubmitSegment = segment => {
  submitIntroDraft.segmentType = segment;
  submitIntroDraft.status = "";
  renderSubmitIntroModal();
};

[segmentIntroButton, segmentRecapButton, segmentOutroButton].forEach(button => {
  button.addEventListener("click", event => {
    event.stopPropagation();
    updateSubmitSegment(button.dataset.segment || "intro");
  });
});

const currentTimeText = () => formatTime(isScrubbing ? scrubPositionMs : state.positionMs);

captureStartButton.addEventListener("click", event => {
  event.stopPropagation();
  submitIntroDraft.startTime = currentTimeText();
  submitIntroDraft.status = "";
  renderSubmitIntroModal();
});
captureEndButton.addEventListener("click", event => {
  event.stopPropagation();
  submitIntroDraft.endTime = currentTimeText();
  submitIntroDraft.status = "";
  renderSubmitIntroModal();
});

submitIntroCloseButton.addEventListener("click", event => {
  event.stopPropagation();
  closePlayerModal();
});
submitIntroCancelButton.addEventListener("click", event => {
  event.stopPropagation();
  closePlayerModal();
});

const parseIntroTime = raw => {
  const value = String(raw || "").trim();
  if (!value) return null;
  const separator = value.includes(":") ? ":" : (value.includes(".") ? "." : "");
  if (separator) {
    const parts = value.split(separator);
    if (parts.length !== 2) return null;
    const minutes = Number(parts[0]);
    const seconds = Number(parts[1]);
    if (!Number.isFinite(minutes) || !Number.isFinite(seconds) || seconds < 0 || seconds >= 60) return null;
    return minutes * 60 + seconds;
  }
  const seconds = Number(value);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : null;
};

submitIntroSubmitButton.addEventListener("click", event => {
  event.stopPropagation();
  submitIntroDraft.startTime = submitIntroStartInput.value;
  submitIntroDraft.endTime = submitIntroEndInput.value;
  const start = parseIntroTime(submitIntroDraft.startTime);
  const end = parseIntroTime(submitIntroDraft.endTime);
  if (start == null || end == null || end <= start) {
    submitIntroDraft.status = "Check the start and end times.";
    renderSubmitIntroModal();
    return;
  }
  const segmentIndex = submitIntroDraft.segmentType === "recap" ? 1 : (submitIntroDraft.segmentType === "outro" ? 2 : 0);
  submitIntroDraft.status = "";
  send("submitIntroSegment", segmentIndex);
  send("submitIntroStart", start);
  send("submitIntroEnd", end);
  send("submitIntroCommit", 0);
});

const cancelP2pConsent = () => {
  send("cancelP2pForPlayerControls", 0);
  closePlayerModal();
};

p2pConsentCloseButton.addEventListener("click", event => {
  event.stopPropagation();
  cancelP2pConsent();
});
p2pConsentCancelButton.addEventListener("click", event => {
  event.stopPropagation();
  cancelP2pConsent();
});
p2pConsentEnableButton.addEventListener("click", event => {
  event.stopPropagation();
  send("enableP2pForPlayerControls", 0);
});

skipPrompt.addEventListener("click", event => {
  event.stopPropagation();
  send("skipInterval", 0);
});

nextEpisodeCard.addEventListener("click", event => {
  event.stopPropagation();
  if (state.nextEpisodePlayable) {
    send("playNextEpisode", 0);
  }
});

seek.addEventListener("input", () => {
  noteChromeActivity();
  isScrubbing = true;
  scrubPositionMs = rangePositionMs();
  setProgress(scrubPositionMs, state.durationMs);
  send("scrubChange", scrubPositionMs);
});

seek.addEventListener("change", () => {
  noteChromeActivity();
  scrubPositionMs = rangePositionMs();
  isScrubbing = false;
  send("scrubFinish", scrubPositionMs);
  state.positionMs = scrubPositionMs;
  render();
  // Clicking the range leaves DOM focus on the slider, which makes it swallow Space/arrows like a
  // touch UI (the keydown handler treats a focused input as text entry and bails). Hand focus back
  // to the shortcut root so Space keeps toggling playback.
  focusShortcutRoot();
});

// PiP shows a stripped-down seek bar (the full timeline row is hidden in compact mode). It drives
// the same scrub pipeline as the main scrubber and shares its buffered/progress fill via setProgress.
if (pipSeek) {
  pipSeek.addEventListener("input", () => {
    noteChromeActivity();
    isScrubbing = true;
    scrubPositionMs = rangePositionMs(pipSeek);
    setProgress(scrubPositionMs, state.durationMs);
    send("scrubChange", scrubPositionMs);
  });
  pipSeek.addEventListener("change", () => {
    noteChromeActivity();
    scrubPositionMs = rangePositionMs(pipSeek);
    isScrubbing = false;
    send("scrubFinish", scrubPositionMs);
    state.positionMs = scrubPositionMs;
    render();
    focusShortcutRoot();
  });
}

let episodeRailPointerId = null;
let episodeRailStartX = 0;
let episodeRailStartScroll = 0;
let episodeRailDragged = false;
episodeList.addEventListener("wheel", event => {
  const delta = Math.abs(event.deltaX) > Math.abs(event.deltaY) ? event.deltaX : event.deltaY;
  if (delta === 0) return;
  event.preventDefault();
  episodeList.scrollLeft += Math.sign(delta) * 240;
}, { passive: false });
episodeList.addEventListener("pointerenter", () => {
  keyboardPanelMode = "episodes";
  focusShortcutRoot();
});
episodeNotch.addEventListener("pointerenter", () => {
  if (activeModal !== "episodes") episodeNotch.click();
});
sourceNotch.addEventListener("pointerenter", () => {
  if (activeModal !== "sources") sourceNotch.click();
});
episodeList.addEventListener("pointerdown", event => {
  if (event.button !== 0) return;
  episodeRailPointerId = event.pointerId;
  episodeRailStartX = event.clientX;
  episodeRailStartScroll = episodeList.scrollLeft;
  episodeRailDragged = false;
});
episodeList.addEventListener("pointermove", event => {
  if (event.pointerId !== episodeRailPointerId) return;
  const distance = event.clientX - episodeRailStartX;
  if (!episodeRailDragged && Math.abs(distance) > 8) {
    episodeRailDragged = true;
    episodeList.setPointerCapture(event.pointerId);
    episodeList.classList.add("is-dragging");
  }
  if (!episodeRailDragged) return;
  event.preventDefault();
  episodeList.scrollLeft = episodeRailStartScroll - distance;
});
const finishEpisodeRailDrag = event => {
  if (event.pointerId !== episodeRailPointerId) return;
  if (episodeList.hasPointerCapture(event.pointerId)) episodeList.releasePointerCapture(event.pointerId);
  episodeRailPointerId = null;
  episodeList.classList.remove("is-dragging");
};
episodeList.addEventListener("pointerup", finishEpisodeRailDrag);
episodeList.addEventListener("pointercancel", finishEpisodeRailDrag);
episodeList.addEventListener("click", event => {
  if (episodeRailDragged) {
    event.preventDefault();
    event.stopPropagation();
    episodeRailDragged = false;
  }
}, true);

const playbackSpeedStages = [1, 1.25, 1.5, 2, 3, 4];
// Fine mode steps by a flat 0.1 over a continuous range instead of hopping between the coarse
// preset stages. Kept as 0.1-multiples with a rounding guard so repeated steps don't drift.
const PLAYBACK_SPEED_FINE_STEP = 0.1;
const PLAYBACK_SPEED_FINE_MIN = 0.1;
const PLAYBACK_SPEED_FINE_MAX = 4;
const stepPlaybackSpeed = direction => {
  const current = parsedPlaybackSpeed();
  let next;
  if (state.playbackSpeedFineIncrementsEnabled) {
    const stepped = current + direction * PLAYBACK_SPEED_FINE_STEP;
    next = Math.min(PLAYBACK_SPEED_FINE_MAX, Math.max(PLAYBACK_SPEED_FINE_MIN, Math.round(stepped * 10) / 10));
  } else {
    const currentIndex = playbackSpeedStages.reduce((best, speed, index) =>
      Math.abs(speed - current) < Math.abs(playbackSpeedStages[best] - current) ? index : best, 0);
    const nextIndex = Math.max(0, Math.min(playbackSpeedStages.length - 1, currentIndex + direction));
    next = playbackSpeedStages[nextIndex];
  }
  const label = `${String(next).replace(/\.0$/, "")}x`;
  state = { ...state, playbackSpeedLabel: label };
  speedLabel.textContent = label;
  window.nuvioShowPresetPill("Playback speed", label);
  send("setPlaybackSpeed", next);
};
speedButton.addEventListener("contextmenu", event => {
  event.preventDefault();
  event.stopPropagation();
  noteChromeActivity(true);
  stepPlaybackSpeed(-1);
});

playerVolumeSlider.addEventListener("input", event => {
  event.stopPropagation();
  localVolume = Math.max(0, Math.min(200, Number(playerVolumeSlider.value) || 0));
  send("volumeSet", localVolume);
  noteChromeActivity(true);
});
playerVolumeSlider.addEventListener("click", event => event.stopPropagation());

timeline.addEventListener("pointermove", showSeekThumbnailAt);
timeline.addEventListener("pointerleave", () => {
  hideChapterTooltip();
  hideSeekThumbnail();
});

window.playerUpdate = update => {
  const durationMs = Math.round((Number(update.duration) || 0) * 1000);
  const positionMs = Math.round((Number(update.position) || 0) * 1000);
  const bufferedMs = Math.round((Number(update.buffered) || 0) * 1000);
  const audioTracks = normalizeTracks(update.audioTracks);
  const subtitleTracks = normalizeTracks(update.subtitleTracks);
  const audioTracksChanged = trackListSignature(audioTracks) !== trackListSignature(state.audioTracks);
  const subtitleTracksChanged = trackListSignature(subtitleTracks) !== trackListSignature(state.subtitleTracks);
  state = {
    ...state,
    durationMs,
    positionMs,
    bufferedMs,
    isPlaying: !Boolean(update.paused),
    isLoading: Boolean(update.loading || update.isLoading),
    audioTracks,
    subtitleTracks,
  };
  setContextMenuDynamicItems("subtitleTracks", subtitleTracks);
  setContextMenuDynamicItems("audioTracks", audioTracks);
  renderChrome();
  if ((audioTracksChanged && activeModal === "audio") ||
      (subtitleTracksChanged && activeModal === "subtitles")) {
    renderActiveModal();
  }
};

window.playerControls = nextState => {
  const previousCloseToken = Number(state.closeModalsToken) || 0;
  state = { ...state, ...nextState };
  refreshContextMenuIndicators();
  if (contextMenuOpen) refreshSubtitleStyleContextSubmenus();
  setContextMenuDynamicItems("addonSubtitles", state.addonSubtitleItems);
  hasReceivedPlayerControls = true;
  const closeToken = Number(state.closeModalsToken) || 0;
  if (closeToken !== previousCloseToken) {
    closePlayerModal();
  }
  if (state.showP2pConsent && activeModal !== "p2pConsent") {
    openPlayerModal("p2pConsent");
  } else if (!state.showP2pConsent && activeModal === "p2pConsent") {
    closePlayerModal();
  }
  render();
};

let activePipPointerId = null;

const beginPipPointerInteraction = (event, mode) => {
  activePipPointerId = event.pointerId;
  try {
    root.setPointerCapture(event.pointerId);
  } catch (_) {
    // Pointer capture can fail if WebView has already cancelled the pointer. The native side
    // still receives the initial interaction and any subsequent events that remain in-view.
  }
  send("beginPictureInPictureInteraction", mode);
};

const endPipPointerInteraction = event => {
  if (activePipPointerId === null || (event && event.pointerId !== activePipPointerId)) return;
  const pointerId = activePipPointerId;
  activePipPointerId = null;
  send("endPictureInPictureInteraction", 0);
  try {
    if (root.hasPointerCapture(pointerId)) root.releasePointerCapture(pointerId);
  } catch (_) {}
};

root.addEventListener("pointermove", event => {
  if (event.pointerId !== activePipPointerId) return;
  event.preventDefault();
  send("updatePictureInPictureInteraction", 0);
}, true);

root.addEventListener("pointerup", endPipPointerInteraction, true);
root.addEventListener("pointercancel", endPipPointerInteraction, true);
root.addEventListener("lostpointercapture", endPipPointerInteraction, true);

root.addEventListener("pointerdown", event => {
  if (!state.pictureInPictureActive || event.button !== 0) return;
  if (event.target.closest("button,input,.pip-resize-handle")) return;
  event.preventDefault();
  event.stopImmediatePropagation();
  beginPipPointerInteraction(event, 1);
}, true);

pictureInPictureResizeHandles.forEach(handleElement => {
  handleElement.addEventListener("pointerdown", event => {
    if (!state.pictureInPictureActive || event.button !== 0) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    beginPipPointerInteraction(event, (Number(handleElement.dataset.pipResize) || 0) + 1);
  }, true);
});

root.addEventListener("click", event => {
  if (consumeContextMenuDismissalClick) {
    consumeContextMenuDismissalClick = false;
    window.clearTimeout(contextMenuDismissalClickTimer);
    event.preventDefault();
    event.stopPropagation();
    window.clearTimeout(tapTimer);
    return;
  }
  if (state.pictureInPictureActive) return;
  if (playbackErrorText()) return;
  if (event.target.closest("button,input")) return;
  const onVideoSurface = !isChromeInteractionTarget(event.target);
  window.clearTimeout(tapTimer);
  tapTimer = window.setTimeout(() => {
    if (state.isLocked) {
      // Locked: any tap just reveals the locked overlay (handled inside toggleChrome).
      toggleChrome();
      return;
    }
    if (onVideoSurface) {
      // Native playback state follows the click asynchronously. Hide/reset the paused card now so
      // the stale paused frame cannot flash during the resume handoff.
      suppressPauseMetadataForPlaybackInteraction();
      send("toggle", 0);
      // A surface click is also a playback interaction. Always leave the controls visible and
      // restart their timeout; toggling chrome here made the result depend on whether a preceding
      // mousemove happened to reveal it first.
      revealChromeForPlaybackInteraction();
    } else {
      // Clicked the controls themselves — a gap between/around buttons, the control-bar
      // background, or the header band. Keep the chrome up and just restart the fade timer
      // instead of hiding it out from under the pointer.
      noteChromeActivity(true);
    }
  }, 220);
});

root.addEventListener("wheel", event => {
  if (state.pictureInPictureActive) return;
  if (playbackErrorText()) return;
  if (state.isLocked) return;
  if (isChromeInteractionTarget(event.target)) return;
  event.preventDefault();
  const direction = event.deltaY > 0 ? -1 : event.deltaY < 0 ? 1 : 0;
  if (direction === 0) return;
  adjustLocalVolume(direction * 5);
  send("volumeDelta", direction * 0.05);
}, { passive: false });

root.addEventListener("dblclick", event => {
  if (playbackErrorText()) return;
  if (event.target.closest("button,input")) return;
  // Only the bare video surface toggles fullscreen — double-clicking the control bar or the top
  // navbar band shouldn't fling in/out of fullscreen.
  if (isChromeInteractionTarget(event.target)) return;
  event.preventDefault();
  window.clearTimeout(tapTimer);
  send("toggleFullscreen", 0);
});

// Mouse "Back" side button (X1 / button 3) closes the player, mirroring Esc. WebView2 mouse events
// don't reach the app-level AWT listener that handles Back on every other screen, so the button is
// wired here in the HUD instead. (Button 4 / Forward is intentionally left alone.)
document.addEventListener("mousedown", event => {
  if (isHeroTrailerSurface || state.heroTrailerMode) return;
  if (event.button !== 3) return;
  event.preventDefault();
  if (contextMenuOpen) {
    closeContextMenu();
    return;
  }
  if (activeModal) {
    closePlayerModal(true);
    focusShortcutRoot();
    return;
  }
  send("back", 0);
});

document.addEventListener("keydown", event => {
  // The hero-trailer surface is passive: it never holds OS keyboard focus (the native container
  // refuses mouse activation and the WebView2 bounces any focus it grabs back to the Compose UI),
  // so all navigation is owned by Compose. Don't forward keys through the native bridge (that
  // parallel path fought Compose's own handling and caused stuck-key bugs) and don't run any of
  // the full-screen player key logic below for this surface.
  if (isHeroTrailerSurface || state.heroTrailerMode) {
    return;
  }
  if (event.key === "Escape" && contextMenuOpen) {
    event.preventDefault();
    closeContextMenu();
    return;
  }
  if (event.key === "Escape" && playbackErrorText()) {
    event.preventDefault();
    send("back", 0);
    return;
  }
  if (event.key === "Escape" && activeModal) {
    event.preventDefault();
    closePlayerModal(true);
    focusShortcutRoot();
    return;
  }
  if (event.key === "Escape") {
    event.preventDefault();
    send("back", 0);
    return;
  }
  if (playbackErrorText()) return;
  const isMacFullscreenShortcut = event.code === "KeyF" && event.metaKey && event.ctrlKey && !event.altKey;
  const configuredFullscreenKey = Math.max(0, Number(state.appFullscreenKeyCode) || 0);
  if (event.code === "F11" || (configuredFullscreenKey > 0 && awtKeyCodeForEvent(event) === configuredFullscreenKey) || isMacFullscreenShortcut) {
    event.preventDefault();
    focusShortcutRoot();
    send("toggleFullscreen", 0);
    return;
  }
  if (keyboardPanelMode && ["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Enter", "NumpadEnter"].includes(event.code)) {
    event.preventDefault();
    window.nuvioHandleKeyboardPanelKey(event.code === "NumpadEnter" ? "Enter" : event.code);
    return;
  }
  if (activeModal || isTextEntryTarget(event.target)) {
    return;
  }
  const command = shortcutCommandForEvent(event);
  if (!command) {
    return;
  }
  event.preventDefault();
  focusShortcutRoot();
  noteChromeActivity();
  if (command === "volumeUp") {
    adjustLocalVolume(5);
  } else if (command === "volumeDown") {
    adjustLocalVolume(-5);
  } else if (command === "keyboardToggleMute") {
    localMuted = !localMuted;
    syncPlayerVolumeControl();
    showVolumePill();
  } else if (command === "keyboardSpeedUp") {
    stepPlaybackSpeed(1);
    return;
  } else if (command === "keyboardSpeedDown") {
    stepPlaybackSpeed(-1);
    return;
  } else if (command === "keyboardOpenSources") {
    window.nuvioOpenKeyboardPanel("sources");
    return;
  } else if (command === "keyboardOpenEpisodes") {
    window.nuvioOpenKeyboardPanel("episodes");
    return;
  } else if (command === "keyboardSkipInterval") {
    if (skipPrompt.classList.contains("visible")) {
      send("skipInterval", 0);
    } else if (state.nextEpisodeVisible && state.nextEpisodePlayable) {
      send("playNextEpisode", 0);
    }
    return;
  } else if (command === "keyboardToggleMpvDiagnostics") {
    window.nuvioToggleMpvDiagnostics();
    return;
  }
  send(command, 0);
});

setProgress(0, 0);
window.addEventListener("resize", updateViewportUiScale, { passive: true });
if (window.visualViewport) {
  window.visualViewport.addEventListener("resize", updateViewportUiScale, { passive: true });
}
// A window can cross to a monitor with a different OS scale while retaining the same outer
// dimensions. Some WebView versions report that as a DPR media-query change without emitting a
// normal window resize, so re-arm the query after every transition and recalculate explicitly.
let displayScaleMediaQuery = null;
const watchDisplayScaleChanges = () => {
  const nextQuery = window.matchMedia(`(resolution: ${window.devicePixelRatio || 1}dppx)`);
  const onDisplayScaleChanged = () => {
    if (displayScaleMediaQuery && displayScaleMediaQuery.removeEventListener) {
      displayScaleMediaQuery.removeEventListener("change", onDisplayScaleChanged);
    }
    updateViewportUiScale();
    watchDisplayScaleChanges();
  };
  displayScaleMediaQuery = nextQuery;
  if (nextQuery.addEventListener) nextQuery.addEventListener("change", onDisplayScaleChanged);
  else if (nextQuery.addListener) nextQuery.addListener(onDisplayScaleChanged);
};
watchDisplayScaleChanges();
updateViewportUiScale();
focusShortcutRoot();
render();
send("controlsReady", 0);
