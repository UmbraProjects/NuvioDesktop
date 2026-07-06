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
const seek = document.getElementById("seek");
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
const subtitlesLabel = document.getElementById("subtitlesLabel");
const audioLabel = document.getElementById("audioLabel");
const sourcesLabel = document.getElementById("sourcesLabel");
const episodesLabel = document.getElementById("episodesLabel");
const submitIntroButton = document.getElementById("submitIntroButton");
const lockButton = document.getElementById("lockButton");
const videoSettingsButton = document.getElementById("videoSettingsButton");
const backButton = document.getElementById("backButton");
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
const lockedLabel = document.getElementById("lockedLabel");
const audioModal = document.getElementById("audioModal");
const subtitleModal = document.getElementById("subtitleModal");
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
  audioTracks: [],
  subtitleTracks: [],
  sourceIsLoading: false,
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
let nativeViewportTimer = 0;
const prefersReducedMotion = window.matchMedia &&
  window.matchMedia("(prefers-reduced-motion: reduce)").matches;
const modalTransitionMs = prefersReducedMotion ? 1 : 240;
const chromeAutoHideDelayMs = 3500;
const chromeActivityThrottleMs = 300;
const chromeInteractionSelector = [
  "button",
  "input",
  "textarea",
  "select",
  "[contenteditable='true']",
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
  positionLabel.textContent = formatTime(positionMs);
  durationLabel.textContent = formatTime(durationMs);
};

const setText = (element, text) => {
  element.textContent = text || "";
  element.hidden = !text;
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
  const episodeInfo = String(state.pauseOverlayEpisodeInfo || state.providerName || "").trim();
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
};

const normalizedOpeningProgress = () => {
  const progress = Number(state.openingProgress);
  return Number.isFinite(progress) ? Math.max(0, Math.min(1, progress)) : null;
};

const playbackErrorText = () => String(state.playbackErrorMessage || "").trim();

const rangePositionMs = () => {
  const durationMs = Math.max(0, Number(state.durationMs) || 0);
  return durationMs > 0 ? Math.round(durationMs * Number(seek.value) / 1000) : 0;
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
  const tracks = normalizeTracks(state.subtitleTracks);
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
  autoSyncCueList.textContent = "";
  if (!state.hasSelectedAddonSubtitle) {
    autoSyncStatus.textContent = state.selectAddonSubtitleFirstLabel || "Select an addon subtitle first";
    return;
  }
  if (state.subtitleAutoSyncIsLoading) {
    autoSyncStatus.textContent = state.loadingSubtitleLinesLabel || "Loading subtitle lines...";
  } else {
    autoSyncStatus.textContent = state.subtitleAutoSyncErrorMessage || "";
  }
  normalizeItems(state.subtitleAutoSyncCues).forEach(cue => {
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
  });
};

const renderSubtitleStylePanel = () => {
  const style = state.subtitleStyle || {};
  subtitleDelayLabel.textContent = state.subtitleDelayLabel || "Subtitle Delay";
  subtitleDelayValue.textContent = formatDelay(state.subtitleDelayMs);
  subtitleDelayReset.textContent = state.resetLabel || "Reset";
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
  copy.appendChild(top);

  if (item.subtitle) {
    const subtitle = document.createElement("span");
    subtitle.className = "stream-subtitle";
    subtitle.textContent = item.subtitle;
    copy.appendChild(subtitle);
  }

  if (item.addonName) {
    const addon = document.createElement("span");
    addon.className = "stream-addon";
    addon.textContent = item.addonName;
    copy.appendChild(addon);
  }
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
    let changed = false;
    rendered.forEach(({ index, wrapper }) => {
      const measured = Math.ceil(wrapper.getBoundingClientRect().height);
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

  if (item.thumbnail) {
    const thumb = document.createElement("span");
    thumb.className = "episode-thumb";
    const image = document.createElement("img");
    image.alt = "";
    image.loading = "lazy";
    setImageSource(image, item.thumbnail);
    thumb.appendChild(image);
    row.appendChild(thumb);
  }

  const copy = document.createElement("span");
  copy.className = "episode-copy";
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
  copy.appendChild(top);
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
      renderEpisodeList();
    },
  );

  let items = normalizeItems(state.episodeItems);
  if (selectedSeason != null) {
    items = items.filter(item => Number(item.season) === Number(selectedSeason));
  }
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
    window.requestAnimationFrame(() => focusKeyboardEpisodeRow());
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
  episodeList.querySelectorAll("[data-keyboard-episode-index]").forEach((candidate, index) => {
    candidate.classList.toggle("keyboard-focused", index === keyboardEpisodeIndex);
  });
  const row = episodeList.querySelector(`[data-keyboard-episode-index="${keyboardEpisodeIndex}"]`);
  if (!row) return;
  row.focus({ preventScroll: true });
  row.scrollIntoView({ block: "center", inline: "nearest" });
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

  const shouldShowSkip = Boolean(state.skipPromptVisible && (!state.skipPromptDismissed || state.controlsVisible));
  const showSkip = Boolean(shouldShowSkip && (!skipPromptAutoHidden || state.controlsVisible));
  const showSkipProgress = Boolean(showSkip && !state.controlsVisible && !skipPromptAutoHidden && !state.skipPromptDismissed);
  skipPromptLabel.textContent = state.skipPromptLabel || "Skip";
  skipPrompt.setAttribute("aria-label", state.skipPromptLabel || "Skip");
  skipPrompt.setAttribute("aria-hidden", showSkip ? "false" : "true");
  skipPrompt.classList.toggle("visible", showSkip);
  skipPrompt.classList.toggle("show-progress", showSkipProgress);
  if (showSkipProgress) {
    startSkipPromptAutoHide();
  } else if (!showSkip || state.controlsVisible || state.skipPromptDismissed) {
    window.clearTimeout(skipPromptAutoHideTimer);
    skipPromptAutoHideTimer = 0;
    skipPromptAutoHideActive = false;
  }

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
  noteChromeActivity(true);
};

const finishChromePointerInteraction = event => {
  isChromePointerDown = false;
  if (event && event.type !== "pointercancel") {
    isChromePointerInside = isChromeInteractionTarget(event.target);
  } else {
    isChromePointerInside = false;
  }
  clearPressedButton();
  noteChromeActivity(true);
};

const renderChrome = () => {
  const durationMs = Math.max(0, Number(state.durationMs) || 0);
  const positionMs = isScrubbing ? scrubPositionMs : Math.max(0, Number(state.positionMs) || 0);
  const isPlaying = Boolean(state.isPlaying);
  const showError = renderPlaybackError();
  root.classList.toggle("locked", Boolean(state.isLocked));
  root.classList.toggle("locked-visible", Boolean(state.isLocked && state.lockedOverlayVisible));
  const isChromeHidden = Boolean(showError || (!state.controlsVisible && !(state.isLocked && state.lockedOverlayVisible)));
  root.classList.toggle("chrome-hidden", isChromeHidden);
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
  setText(episode, state.episodeText);
  setText(streamTitle, state.streamTitle);
  setText(providerName, state.providerName);
  resizeLabel.textContent = state.resizeModeLabel || "Fit";
  speedLabel.textContent = state.playbackSpeedLabel || "1x";
  subtitlesLabel.textContent = state.subtitlesLabel || "Subs";
  audioLabel.textContent = state.audioLabel || "Audio";
  sourcesLabel.textContent = state.sourcesLabel || "Sources";
  episodesLabel.textContent = state.episodesLabel || "Episodes";
  lockedLabel.textContent = state.tapToUnlockLabel || "Tap to unlock";
  const showBuffering = Boolean(!showError && state.isLoading && !state.isLocked && !activeModal && !showOpening);
  bufferingStatus.classList.toggle("visible", showBuffering);
  bufferingStatus.setAttribute("aria-hidden", showBuffering ? "false" : "true");

  setVisible(submitIntroButton, Boolean(state.showSubmitIntro));
  setVisible(videoSettingsButton, Boolean(state.showVideoSettings));
  setVisible(sourcesButton, Boolean(state.showSources));
  setVisible(episodesButton, Boolean(state.showEpisodes));

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
  seek.disabled = Boolean(state.isLocked);
  setProgress(positionMs, durationMs);
  if (showError) {
    skipPrompt.classList.remove("visible", "show-progress");
    skipPrompt.setAttribute("aria-hidden", "true");
    nextEpisodeCard.classList.remove("visible");
    nextEpisodeCard.setAttribute("aria-hidden", "true");
  } else {
    renderNativePlaybackPrompts();
  }
  syncChromeAutoHideTimer(showOpening);
};

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
}
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

const shortcutCommandForEvent = event => {
  if (event.metaKey || event.ctrlKey || event.altKey) return "";
  switch (event.code) {
    case "Space":
    case "KeyK":
      return "keyboardToggle";
    case "ArrowLeft":
    case "KeyJ":
      return "keyboardSeekBack";
    case "ArrowRight":
    case "KeyL":
      return "keyboardSeekForward";
    case "ArrowUp":
      return "volumeUp";
    case "ArrowDown":
      return "volumeDown";
    default:
      return "";
  }
};

let localVolume = 100;
let volumePillHideTimer = null;
let lastCursorHidden = null;

const showVolumePill = () => {
  if (!volumePill) return;
  const percentage = Math.round(localVolume);
  volumePillLabel.textContent = `${percentage}%`;
  if (volumePillIcon) {
    volumePillIcon.setAttribute("href", percentage <= 0 ? "#icon-volume-mute" : "#icon-volume");
  }
  volumePill.classList.add("visible");
  window.clearTimeout(volumePillHideTimer);
  volumePillHideTimer = window.setTimeout(() => {
    volumePill.classList.remove("visible");
  }, 900);
};

const adjustLocalVolume = deltaPercent => {
  localVolume = Math.max(0, Math.min(200, localVolume + deltaPercent));
  showVolumePill();
};

window.nuvioShowVolumePill = percentage => {
  localVolume = Math.max(0, Math.min(200, Number(percentage) || 0));
  showVolumePill();
};

const presetPill = document.getElementById("presetPill");
const presetPillTitle = document.getElementById("presetPillTitle");
const presetPillValue = document.getElementById("presetPillValue");
let presetPillHideTimer = null;

window.nuvioShowPresetPill = (title, value) => {
  if (!presetPill) return;
  if (presetPillTitle) presetPillTitle.textContent = String(title == null ? "" : title);
  if (presetPillValue) presetPillValue.textContent = String(value == null ? "" : value);
  presetPill.classList.add("visible");
  window.clearTimeout(presetPillHideTimer);
  presetPillHideTimer = window.setTimeout(() => {
    presetPill.classList.remove("visible");
  }, 1400);
};

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
  if (state.mouseMoveRevealsControlsEnabled && !state.isLocked) {
    if (!state.controlsVisible) {
      chromeAutoHideActivity += 1;
      state = { ...state, controlsVisible: true };
      renderChrome();
      send("revealChrome", 0);
    } else {
      noteChromeActivity();
    }
  } else if (inside) {
    noteChromeActivity();
  }
}, true);

document.addEventListener("pointerup", finishChromePointerInteraction, true);
document.addEventListener("pointercancel", finishChromePointerInteraction, true);
document.addEventListener("dragend", clearPressedButton, true);
document.addEventListener("pointerleave", () => {
  updateChromePointerInside(false);
}, true);
document.addEventListener("focusin", event => {
  isChromeFocusInside = isChromeInteractionTarget(event.target);
  if (isChromeFocusInside) {
    noteChromeActivity(true);
  }
}, true);
document.addEventListener("focusout", () => {
  window.setTimeout(() => {
    isChromeFocusInside = isChromeInteractionTarget(document.activeElement);
    noteChromeActivity(true);
  }, 0);
}, true);
window.addEventListener("blur", () => {
  isChromePointerInside = false;
  isChromePointerDown = false;
  isChromeFocusInside = false;
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
    if (code === "ArrowUp") moveKeyboardEpisode(-1);
    if (code === "ArrowDown") moveKeyboardEpisode(1);
    if (code === "ArrowLeft") moveKeyboardSeason(-1);
    if (code === "ArrowRight") moveKeyboardSeason(1);
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
  renderChrome();
  if ((audioTracksChanged && activeModal === "audio") ||
      (subtitleTracksChanged && activeModal === "subtitles")) {
    renderActiveModal();
  }
};

window.playerControls = nextState => {
  const previousCloseToken = Number(state.closeModalsToken) || 0;
  state = { ...state, ...nextState };
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

root.addEventListener("click", event => {
  if (playbackErrorText()) return;
  if (event.target.closest("button,input")) return;
  const onVideoSurface = !isChromeInteractionTarget(event.target);
  window.clearTimeout(tapTimer);
  tapTimer = window.setTimeout(() => {
    if (onVideoSurface && !state.isLocked) {
      send("toggle", 0);
    }
    toggleChrome();
  }, 220);
});

root.addEventListener("wheel", event => {
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
  event.preventDefault();
  window.clearTimeout(tapTimer);
  send("toggleFullscreen", 0);
});

document.addEventListener("keydown", event => {
  // WebView is a native child HWND, so keys do not always reach AWT while it owns OS focus.
  // Forward home navigation through the native event bridge instead of swallowing it.
  if (isHeroTrailerSurface || state.heroTrailerMode) {
    if (event.metaKey || event.ctrlKey || event.altKey) return;
    const homeCommand = {
      ArrowUp: "homeKeyUp",
      ArrowDown: "homeKeyDown",
      ArrowLeft: "homeKeyLeft",
      ArrowRight: "homeKeyRight",
      Enter: "homeKeySelect",
      NumpadEnter: "homeKeySelect",
      KeyT: "homeKeyToggleTrailer",
      Escape: "homeKeyDismiss",
      KeyS: "homeKeySearch",
      KeyL: "homeKeyLibrary",
    }[event.code];
    if (homeCommand) {
      event.preventDefault();
      send(homeCommand, 0);
    }
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
  if (event.code === "F11" || isMacFullscreenShortcut) {
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
  if (event.code === "Tab") {
    // Tab skips the intro/outro while the skip prompt is showing (matches the official client).
    if (skipPrompt.classList.contains("visible")) {
      event.preventDefault();
      noteChromeActivity();
      send("skipInterval", 0);
    }
    return;
  }
  if (!event.metaKey && !event.ctrlKey && !event.altKey) {
    const directKeybind = {
      KeyC: () => send("resize", 0),
      BracketLeft: () => send("keyboardSpeedStep", -1),
      BracketRight: () => send("keyboardSpeedStep", 1),
      KeyS: () => send("keyboardNextSubtitle", 0),
      KeyA: () => send("keyboardNextAudio", 0),
      KeyO: () => window.nuvioOpenKeyboardPanel("sources"),
      KeyE: () => window.nuvioOpenKeyboardPanel("episodes"),
      F8: () => send("keyboardCycleHdrMode", 0),
      F9: () => send("keyboardCycleColorProfile", 0),
      F10: () => send("keyboardCycleAnimeMode", 0),
    }[event.code];
    if (directKeybind) {
      event.preventDefault();
      noteChromeActivity();
      directKeybind();
      return;
    }
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
  }
  send(command, 0);
});

setProgress(0, 0);
focusShortcutRoot();
render();
send("controlsReady", 0);
