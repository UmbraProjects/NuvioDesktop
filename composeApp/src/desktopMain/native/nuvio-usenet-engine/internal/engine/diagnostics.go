package engine

import (
	"io"
	"sync"
	"time"
)

// Bounded, session-local timings. Never record URLs, credentials, message IDs,
// filenames or payloads. These clocks measure overlapping work, not an additive
// CPU breakdown. HTTP read time includes both layout discovery and NNTP waits.
type startupTrace struct {
	mu        sync.Mutex
	start     time.Time
	marks     map[string]float64
	durations map[string]float64
	ranges    []*rangeTiming
	// The player's most recent Range request, kept for the whole session (ranges stops after
	// startup): a seek's wait splits into the engine's first byte and the player's own reading.
	latest *rangeTiming
	nzbCache  *nzbCacheDiagnostic
	// The NZB cache entry's name, for the app's open answer only (it marks releases it already
	// keeps); never part of the diagnostics.
	cacheKey string
}

func (t *startupTrace) recordCacheKey(key string) {
	if t == nil {
		return
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	t.cacheKey = key
}

func (t *startupTrace) nzbCacheKey() string {
	if t == nil {
		return ""
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.cacheKey
}

// Only fixed outcomes and a byte count; never include cache keys or URLs.
type nzbCacheDiagnostic struct {
	Lookup string `json:"lookup"`
	Reason string `json:"reason,omitempty"`
	Write  string `json:"write,omitempty"`
	Format string `json:"format,omitempty"`
	Bytes  int64  `json:"bytes"`
}

func (t *startupTrace) recordNZBCache(v nzbCacheDiagnostic) {
	if t == nil {
		return
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	t.nzbCache = &v
}

func (s *Session) diagnostics() map[string]any {
	v := s.trace.snapshot()
	if v == nil {
		v = make(map[string]any)
	}
	s.store.mu.Lock()
	v["store"] = s.store.stats
	v["articleSlabsBytes"] = s.store.allocated
	s.store.mu.Unlock()
	v["streamFailure"] = s.failure.get()
	if holes := s.store.holes; holes != nil {
		holes.mu.Lock()
		v["filledArticles"] = holes.count
		holes.mu.Unlock()
	}
	if layout := s.content; layout != nil {
		if n := layout.nested; n != nil && len(n.vols) == 1 {
			layout = n.vols[0] // A disc image's clip: the archive maps the image.
		}
		v["archiveDiscoveryMs"] = float64(layout.layoutNS.Load()) / 1e6
		v["archiveWaitMs"] = float64(layout.layoutWaitNS.Load()) / 1e6
		layout.mu.RLock()
		v["archiveExtents"] = len(layout.parts)
		if layout.decoded != nil {
			v["compressed7z"] = true
			v["compressed7zStarts"] = layout.decodedStarts()
			v["compressed7zDecodedBytes"] = layout.decoded.decodedTo.Load()
			v["compressed7zWaitingFor"] = layout.decoded.waitingFor.Load()
			v["compressed7zSize"] = layout.Size
		}
		v["archiveSuffixExtents"] = len(layout.suffix)
		layout.mu.RUnlock()
	}
	if reports := s.providerReports(); len(reports) > 0 {
		v["providers"] = reports
	}
	return v
}

type providerReporter interface{ providerReports() []providerReport }

// providerReports says what each provider did for this session so far; nil for
// a pool that does not count, such as a test's.
func (s *Session) providerReports() []providerReport {
	if pool, ok := s.pool.(providerReporter); ok {
		return pool.providerReports()
	}
	return nil
}

type rangeTiming struct {
	StartMS     float64 `json:"startMs"`
	Offset      int64   `json:"offset"`
	FirstByteMS float64 `json:"firstByteMs"`
	ReadMS      float64 `json:"readMs"`
	Bytes       int64   `json:"bytes"`
	Failure     string  `json:"failure,omitempty"`
	// When the first 4 MiB had been served (ms on the trace clock, -1 not yet): about a 4K
	// keyframe plus the quarter second of buffer the player waits for after a seek.
	FourMiBMS float64 `json:"fourMiBMs"`
}

func newStartupTrace() *startupTrace {
	return &startupTrace{start: time.Now(), marks: make(map[string]float64), durations: make(map[string]float64)}
}

func (t *startupTrace) duration(name string, elapsed time.Duration) {
	if t == nil {
		return
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	t.durations[name] = float64(elapsed.Microseconds()) / 1000
}

// Measure blocking reads without buffering the document again. Parsing and
// download overlap: parse work is wall time outside Body.Read, not CPU time.
type nzbTimingReader struct {
	r       io.Reader
	elapsed time.Duration
}

func (r *nzbTimingReader) Read(p []byte) (int, error) {
	start := time.Now()
	n, err := r.r.Read(p)
	r.elapsed += time.Since(start)
	return n, err
}

func parseNZBTraced(r io.Reader, store *Store, trace *startupTrace, spill nzbSpill) ([]*File, bool, error) {
	if trace == nil {
		return parseNZB(r, store, spill)
	}
	trace.mark("nzb_parse_started")
	start := time.Now()
	body := &nzbTimingReader{r: r}
	files, spilled, err := parseNZB(body, store, spill)
	trace.duration("nzb_body_read", body.elapsed)
	trace.duration("nzb_parse_work", time.Since(start)-body.elapsed)
	trace.mark("nzb_parse_finished")
	return files, spilled, err
}

func (t *startupTrace) mark(name string) {
	if t == nil {
		return
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	if _, exists := t.marks[name]; !exists {
		t.marks[name] = float64(time.Since(t.start).Microseconds()) / 1000
	}
}

func (t *startupTrace) snapshot() map[string]any {
	if t == nil {
		return nil
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	marks := make(map[string]float64, len(t.marks))
	for k, v := range t.marks {
		marks[k] = v
	}
	ranges := make([]rangeTiming, len(t.ranges))
	for i, v := range t.ranges {
		ranges[i] = *v
	}
	durations := make(map[string]float64, len(t.durations))
	for k, d := range t.durations {
		durations[k] = d
	}
	v := map[string]any{"marksMs": marks, "durationsMs": durations, "ranges": ranges}
	if t.latest != nil {
		v["latestRange"] = *t.latest
	}
	if t.nzbCache != nil {
		v["nzbCache"] = *t.nzbCache
	}
	return v
}

type timingReader struct {
	*ContentReader
	trace  *startupTrace
	timing *rangeTiming
}

func (t *startupTrace) reader(r *ContentReader) io.ReadSeeker {
	if t == nil {
		return r
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	record := &rangeTiming{StartMS: float64(time.Since(t.start).Microseconds()) / 1000, FirstByteMS: -1, FourMiBMS: -1}
	t.latest = record
	if len(t.ranges) < 24 && time.Since(t.start) <= 30*time.Second {
		t.ranges = append(t.ranges, record)
	}
	return &timingReader{ContentReader: r, trace: t, timing: record}
}

func (r *timingReader) Read(p []byte) (int, error) {
	off := r.ContentReader.pos
	start := time.Now()
	n, err := r.ContentReader.Read(p)
	r.trace.mu.Lock()
	if r.timing.FirstByteMS < 0 {
		r.timing.Offset = off
	}
	if n > 0 && r.timing.FirstByteMS < 0 {
		r.timing.Offset = off
		r.timing.FirstByteMS = float64(time.Since(r.trace.start).Microseconds()) / 1000
		if _, exists := r.trace.marks["first_media_bytes"]; !exists {
			r.trace.marks["first_media_bytes"] = r.timing.FirstByteMS
		}
	}
	r.timing.ReadMS += float64(time.Since(start).Microseconds()) / 1000
	r.timing.Bytes += int64(n)
	if r.timing.FourMiBMS < 0 && r.timing.Bytes >= 4<<20 {
		r.timing.FourMiBMS = float64(time.Since(r.trace.start).Microseconds()) / 1000
	}
	if err != nil && err != io.EOF {
		r.timing.Failure = permanentStreamFailure(err)
		if r.timing.Failure == "" {
			r.timing.Failure = "temporary-read-error"
		}
	}
	r.trace.mu.Unlock()
	return n, err
}
