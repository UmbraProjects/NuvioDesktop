package engine

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/NuvioMedia/nuvio-usenet-engine/internal/testsupport/nntpserver"
	"github.com/javi11/nntppool/v4"
)

func brokenStreamFixture(t *testing.T, sizes []int, missing []int, cfg Config) (*Session, *nntpserver.Server, *httptest.Server, []byte) {
	t.Helper()
	total := 0
	for _, n := range sizes {
		total += n
	}
	return brokenStreamWithPayload(t, payload(total), sizes, missing, cfg)
}

func brokenStreamWithPayload(t *testing.T, want []byte, sizes, missing []int, cfg Config) (*Session, *nntpserver.Server, *httptest.Server, []byte) {
	t.Helper()
	xml, articles := fixture([]inputFile{{"movie.mkv", want, sizes}})
	misses := make(map[string]struct{})
	for _, i := range missing {
		misses[fmt.Sprintf("f0-s%d@test", i)] = struct{}{}
	}
	provider, err := nntpserver.New(nntpserver.Config{Articles: articles, Missing: misses})
	if err != nil {
		t.Fatal(err)
	}
	pool, err := nntppool.NewClient(context.Background(), []nntppool.Provider{{Host: provider.Addr(), Connections: 4, Inflight: 2, StreamInflight: 2, SkipPing: true}}, nntppool.WithStatProbe(false))
	if err != nil {
		provider.Close()
		t.Fatal(err)
	}
	store := NewStore(context.Background(), pool, 8<<20)
	store.holes = newHolePolicy(cfg)
	files, err := ParseNZB(strings.NewReader(xml), store)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	sess := &Session{ctx: ctx, cancel: cancel, pool: pool, store: store, content: &Content{Name: "movie.mkv", Size: int64(len(want)), direct: files[0]}, trace: newStartupTrace()}
	app := NewServer(ctx, "secret", nil, &http.Client{})
	app.sessions["test"] = sess
	srv := httptest.NewServer(app)
	t.Cleanup(func() { srv.Close(); app.Close(); provider.Close() })
	return sess, provider, srv, want
}

func requestRange(t *testing.T, srv *httptest.Server, value string) (*http.Response, []byte, error) {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/stream/test/movie.mkv", nil)
	if value != "" {
		req.Header.Set("Range", value)
	}
	resp, err := srv.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	b, err := io.ReadAll(resp.Body)
	resp.Body.Close()
	return resp, b, err
}

// NZB wire sizes include yEnc escapes. With only the first article's decoded
// extent known, a byte near a boundary can be estimated into a missing neighbour.
func TestSeekBesideMissingArticles(t *testing.T) {
	const size = 2048
	for _, tc := range []struct {
		name    string
		escaped int
		missing []int
		start   int
	}{
		{"before gap", 4, []int{2}, 2*size - 10},
		{"after gap", 1, []int{1}, 2*size + 5},
		{"after consecutive misses", 2, []int{1, 2}, 3*size + 5},
	} {
		for _, fill := range []bool{false, true} {
			t.Run(fmt.Sprintf("%s/fill=%t", tc.name, fill), func(t *testing.T) {
				sizes := []int{size, size, size, size, size}
				want := payload(size * len(sizes))
				// This byte needs escaping on the wire and occupies one decoded byte.
				copy(want[tc.escaped*size:], bytes.Repeat([]byte{214}, size))
				cfg := Config{HoleFilling: fill, MaxMissingArticles: 1, MaxConsecutiveMissing: 1}
				session, _, server, _ := brokenStreamWithPayload(t, want, sizes, tc.missing, cfg)
				resp, head, err := requestRange(t, server, "bytes=0-15")
				if err != nil || resp.StatusCode != 206 || !bytes.Equal(head, want[:16]) {
					t.Fatalf("initial range: status=%d err=%v", resp.StatusCode, err)
				}
				f := session.content.direct
				guess := f.locate(int64(tc.start), 0, len(sizes)-1)
				if !slices.Contains(tc.missing, guess) {
					t.Fatalf("fixture estimated article %d, want a miss in %v", guess, tc.missing)
				}
				for _, start := range []int{tc.start, 0} {
					resp, b, err := requestRange(t, server, fmt.Sprintf("bytes=%d-%d", start, start+5))
					if err != nil || resp.StatusCode != 206 || !bytes.Equal(b, want[start:start+6]) {
						t.Fatalf("range at %d: status=%d failure=%q bytes=%x err=%v", start, resp.StatusCode, resp.Header.Get("X-Usenet-Failure"), b, err)
					}
				}
				if fill && session.store.holes.count != 0 {
					t.Fatal("healthy range used the hole-filling budget")
				}
			})
		}
	}
}

func TestSeekRetriesAnUnresolvedMissingRun(t *testing.T) {
	const size = 512
	sizes := make([]int, 160)
	for i := range sizes {
		sizes[i] = size
	}
	want := payload(len(sizes) * size)
	missing := make([]int, 100)
	for i := range missing {
		missing[i] = i + 1
	}
	copy(want[size:], bytes.Repeat([]byte{214}, len(missing)*size))
	sess, provider, server, _ := brokenStreamWithPayload(t, want, sizes, missing, Config{})
	resp, _, err := requestRange(t, server, "bytes=0-15")
	if err != nil || resp.StatusCode != 206 {
		t.Fatalf("initial range: status=%d err=%v", resp.StatusCode, err)
	}
	start := (len(missing) + 1) * size
	rng := fmt.Sprintf("bytes=%d-%d", start, start+5)
	before := provider.Counters().Bodies
	resp, _, err = requestRange(t, server, rng)
	if err != nil || resp.StatusCode != 502 || sess.failure.get() != "" {
		t.Fatalf("unresolved range: status=%d err=%v failure=%q", resp.StatusCode, err, sess.failure.get())
	}
	if requests := provider.Counters().Bodies - before; requests > maxMissingLayoutProbes+1 {
		t.Fatalf("first range made %d BODY requests", requests)
	}
	resp, b, err := requestRange(t, server, rng)
	if err != nil || resp.StatusCode != 206 || !bytes.Equal(b, want[start:start+6]) {
		t.Fatalf("retry: status=%d bytes=%x err=%v", resp.StatusCode, b, err)
	}
}

func TestSeekPastMissingArticleWithStaleHint(t *testing.T) {
	const size = 4096
	sess, _, server, want := brokenStreamFixture(t, []int{size, size, size, size, size}, []int{1, 2}, Config{})
	resp, _, err := requestRange(t, server, "bytes=0-15")
	if err != nil || resp.StatusCode != 206 {
		t.Fatalf("initial range: status=%d err=%v", resp.StatusCode, err)
	}
	f := sess.content.direct
	// A sparse cached anchor puts the seek in article 1. Article 3 can
	// establish the real range without ever verifying the intervening articles.
	f.mu.Lock()
	f.segments[1].known, f.segments[1].hint = true, true
	f.segments[1].begin, f.segments[1].end = size, 3*size+100
	f.known = append(f.known, 1)
	f.mu.Unlock()
	start := 3*size + 5
	server.Client().Timeout = 5 * time.Second
	resp, b, err := requestRange(t, server, fmt.Sprintf("bytes=%d-%d", start, start+5))
	if err != nil || resp.StatusCode != 206 || !bytes.Equal(b, want[start:start+6]) {
		t.Fatalf("range past stale hint: status=%d bytes=%x err=%v", resp.StatusCode, b, err)
	}
}

func TestSecondaryClipFirstReadFailureKeepsFeaturePlayable(t *testing.T) {
	const size = 20000
	sess, _, server, want := brokenStreamFixture(t, []int{size, size}, []int{1}, Config{})
	const feature = "BDMV/STREAM/00001.m2ts"
	const secondary = "BDMV/STREAM/00002.m2ts"
	file := sess.content.direct
	clip := func(name string, offset int64) *Content {
		return &Content{Name: name, Size: size, complete: true, parts: []extent{{file: file, offset: offset, length: size}}}
	}
	sess.content = clip(feature, 0)
	sess.content.disc = newDiscFiles()
	sess.content.disc.add(secondary, clip(secondary, size))
	for _, tc := range []struct {
		path, ranges string
		status       int
	}{
		{secondary, "bytes=0-99", http.StatusGone},
		{secondary, "bytes=0-9,20-29", http.StatusGone},
		{feature, "bytes=0-99", http.StatusPartialContent},
	} {
		req, err := http.NewRequest(http.MethodGet, server.URL+"/stream/test/"+tc.path, nil)
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Range", tc.ranges)
		resp, err := server.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		body, err := io.ReadAll(resp.Body)
		resp.Body.Close()
		if err != nil || resp.StatusCode != tc.status {
			t.Fatalf("%s %s: status=%d read error=%v", tc.path, tc.ranges, resp.StatusCode, err)
		}
		if tc.path == secondary {
			if resp.Header.Get("X-Usenet-Failure") != "missing-article" || resp.Header.Get("Content-Range") != "" {
				t.Fatalf("secondary clip failure headers: %v", resp.Header)
			}
		} else if !bytes.Equal(body, want[:100]) {
			t.Fatalf("feature returned %d incorrect bytes", len(body))
		}
	}
}

func TestMissingRangeFailsWithoutAdvertisingEmptySuccess(t *testing.T) {
	sess, provider, srv, _ := brokenStreamFixture(t, []int{716800, 716800, 716800}, []int{1}, Config{})
	resp, b, err := requestRange(t, srv, "bytes=716800-")
	if err != nil || resp.StatusCode != http.StatusGone || resp.Header.Get("X-Usenet-Failure") != "missing-article" {
		t.Fatalf("empty range: status=%d body=%s err=%v", resp.StatusCode, b, err)
	}
	if resp.Header.Get("Content-Range") != "" || sess.failure.get() != "missing-article" {
		t.Fatal("failure retained success headers or did not retire session")
	}
	calls := provider.Counters().Bodies
	for _, value := range []string{"bytes=716800-", "bytes=0-", "bytes=-10"} {
		resp, _, err = requestRange(t, srv, value)
		if err != nil || resp.StatusCode != http.StatusGone {
			t.Fatalf("reopen: %d %v", resp.StatusCode, err)
		}
	}
	if provider.Counters().Bodies != calls {
		t.Fatal("retired session repeated NNTP fetch")
	}
}

func TestMidResponseFailureRetiresSessionForReopen(t *testing.T) {
	sess, _, srv, want := brokenStreamFixture(t, []int{716800, 716800, 716800}, []int{1}, Config{})
	resp, b, err := requestRange(t, srv, "bytes=100-")
	if resp.StatusCode != http.StatusPartialContent || !errors.Is(err, io.ErrUnexpectedEOF) || !bytes.Equal(b, want[100:716800]) {
		t.Fatalf("mid-response: status=%d bytes=%d err=%v", resp.StatusCode, len(b), err)
	}
	if sess.failure.get() != "missing-article" {
		t.Fatal("mid-response failure lost")
	}
	resp, _, err = requestRange(t, srv, "bytes=716800-")
	if resp.StatusCode != http.StatusGone || err != nil {
		t.Fatalf("reopen: %d %v", resp.StatusCode, err)
	}
}

func TestMissingFirstMultipartRangeReturnsHTTPFailure(t *testing.T) {
	_, _, srv, _ := brokenStreamFixture(t, []int{20000, 17000, 23000}, []int{1}, Config{})
	resp, _, err := requestRange(t, srv, "bytes=20000-20009,40000-40009")
	if err != nil || resp.StatusCode != http.StatusGone {
		t.Fatalf("multipart failure: status=%d err=%v", resp.StatusCode, err)
	}
}

func TestHoleFillingLimitsAndExactVariableBoundaries(t *testing.T) {
	for _, tc := range []struct {
		name       string
		missing    []int
		cfg        Config
		wantStatus int
		wantFilled int
	}{
		{"off", []int{1}, Config{}, 206, 0},
		{"isolated", []int{1}, Config{HoleFilling: true, MaxMissingArticles: 1, MaxConsecutiveMissing: 1}, 206, 1},
		{"consecutive", []int{1, 2}, Config{HoleFilling: true, MaxMissingArticles: 2, MaxConsecutiveMissing: 2}, 206, 2},
		{"run-limit", []int{1, 2}, Config{HoleFilling: true, MaxMissingArticles: 5, MaxConsecutiveMissing: 1}, 206, 0},
		{"total-limit", []int{1, 3}, Config{HoleFilling: true, MaxMissingArticles: 1, MaxConsecutiveMissing: 1}, 206, 1},
		{"first", []int{0}, Config{HoleFilling: true}, 410, 0},
		{"last", []int{4}, Config{HoleFilling: true}, 206, 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			sizes := []int{20000, 17000, 23000, 19000, 13000}
			sess, _, srv, want := brokenStreamFixture(t, sizes, tc.missing, tc.cfg)
			resp, b, err := requestRange(t, srv, "bytes=0-")
			if resp.StatusCode != tc.wantStatus {
				t.Fatalf("status=%d", resp.StatusCode)
			}
			filled := 0
			if sess.store.holes != nil {
				sess.store.holes.mu.Lock()
				filled = sess.store.holes.count
				sess.store.holes.mu.Unlock()
			}
			if filled != tc.wantFilled {
				t.Fatalf("filled=%d want=%d", filled, tc.wantFilled)
			}
			complete := tc.name == "isolated" || tc.name == "consecutive" || tc.name == "last"
			if complete {
				for _, index := range tc.missing {
					start := 0
					for _, n := range sizes[:index] {
						start += n
					}
					clear(want[start : start+sizes[index]])
				}
				if err != nil || !bytes.Equal(b, want) || sess.failure.get() != "" {
					t.Fatalf("filled stream shifted or failed: bytes=%d err=%v", len(b), err)
				}
			} else if tc.wantStatus == 206 && err == nil {
				t.Fatal("damaged stream unexpectedly succeeded")
			}
		})
	}
}

func TestOverlappingRangesShareHoleBudget(t *testing.T) {
	sess, _, srv, want := brokenStreamFixture(t, []int{20000, 17000, 23000}, []int{1}, Config{HoleFilling: true, MaxMissingArticles: 1, MaxConsecutiveMissing: 1})
	clear(want[20000:37000])
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			resp, b, err := requestRange(t, srv, "bytes=25000-45000")
			if err != nil || resp.StatusCode != 206 || !bytes.Equal(b, want[25000:45001]) {
				t.Errorf("overlapping range: status=%d bytes=%d err=%v", resp.StatusCode, len(b), err)
			}
		}()
	}
	wg.Wait()
	if sess.store.holes.count != 1 {
		t.Fatalf("hole counted %d times", sess.store.holes.count)
	}
}

func TestStreamHTTPRangeSemanticsRemainIntact(t *testing.T) {
	_, _, srv, want := brokenStreamFixture(t, []int{20000, 17000, 23000}, nil, Config{})
	for _, value := range []string{"bytes=0-9", "bytes=-10", "bytes=60000-", "bytes=0-9,30000-30009"} {
		resp, b, err := requestRange(t, srv, value)
		if err != nil {
			t.Fatal(err)
		}
		if value == "bytes=60000-" {
			if resp.StatusCode != 416 {
				t.Fatalf("unsatisfiable=%d", resp.StatusCode)
			}
		} else if resp.StatusCode != 206 {
			t.Fatalf("range=%s status=%d", value, resp.StatusCode)
		}
		if value == "bytes=-10" && !bytes.Equal(b, want[len(want)-10:]) {
			t.Fatal("suffix bytes differ")
		}
	}
	req, _ := http.NewRequest(http.MethodHead, srv.URL+"/stream/test/movie.mkv", nil)
	resp, err := srv.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 200 || resp.ContentLength != int64(len(want)) {
		t.Fatalf("HEAD=%d length=%d", resp.StatusCode, resp.ContentLength)
	}
}

func TestTransientErrorsDoNotRetireSession(t *testing.T) {
	for _, err := range []error{context.Canceled, context.DeadlineExceeded, io.ErrUnexpectedEOF, errPrefetchYield} {
		f := &streamFailure{}
		f.record(err)
		if f.get() != "" {
			t.Fatalf("transient error retired session: %v", err)
		}
	}
	if _, err := (Config{HoleFilling: true, MaxMissingArticles: 1, MaxConsecutiveMissing: 2}).Tuning(); err == nil {
		t.Fatal("invalid limits accepted")
	}
}

type transientRetryClient struct {
	bodyClient
	mu        sync.Mutex
	remaining int
}

func (c *transientRetryClient) BodyStreamPriority(ctx context.Context, id string, w io.Writer, callbacks ...func(nntppool.YEncMeta)) (*nntppool.ArticleBody, error) {
	c.mu.Lock()
	fail := c.remaining > 0
	if fail {
		c.remaining--
	}
	c.mu.Unlock()
	if fail {
		return nil, context.DeadlineExceeded
	}
	return c.bodyClient.BodyStreamPriority(ctx, id, w, callbacks...)
}

func TestTemporaryRangeFailureCanRetryWithoutFilling(t *testing.T) {
	sess, _, srv, want := brokenStreamFixture(t, []int{20000, 17000, 23000}, nil, Config{HoleFilling: true})
	sess.store.client = &transientRetryClient{bodyClient: sess.store.client, remaining: 3}
	resp, _, err := requestRange(t, srv, "bytes=0-9")
	if err != nil || resp.StatusCode != 502 || resp.Header.Get("X-Usenet-Failure") != "temporary-read-error" {
		t.Fatalf("temporary failure: status=%d err=%v", resp.StatusCode, err)
	}
	if sess.failure.get() != "" || sess.store.holes.count != 0 {
		t.Fatal("timeout retired or filled the session")
	}
	resp, b, err := requestRange(t, srv, "bytes=0-9")
	if err != nil || resp.StatusCode != 206 || !bytes.Equal(b, want[:10]) {
		t.Fatalf("retry: status=%d bytes=%d err=%v", resp.StatusCode, len(b), err)
	}
}

func TestHeaderAndBoundaryReadersCannotSpendHoleBudget(t *testing.T) {
	sess, _, srv, _ := brokenStreamFixture(t, []int{20000, 17000, 23000}, []int{1}, Config{HoleFilling: true, MaxMissingArticles: 1, MaxConsecutiveMissing: 1})
	f := sess.content.direct
	header := f.headerReader(sess.ctx)
	var one [1]byte
	_, err := header.ReadAt(one[:], 20000)
	header.Close()
	if !errors.Is(err, nntppool.ErrArticleNotFound) || sess.store.holes.count != 0 {
		t.Fatalf("header reader filled data: err=%v count=%d", err, sess.store.holes.count)
	}
	// Model a stored media file spanning extents; priming is speculative even
	// though its target is selected media and will be eligible during playback.
	sess.content.direct = nil
	sess.content.parts = []extent{{file: f, length: 20000}, {file: f, offset: 20000, length: 40000, start: 20000}}
	sess.content.complete = true
	r := sess.content.Reader(sess.ctx, 1)
	r.pos = 19999
	r.primeBoundary(sess.content.parts[0], r.pos)
	if r.boundary == nil {
		t.Fatal("boundary was not primed")
	}
	<-r.boundary.done
	if !errors.Is(r.boundary.err, nntppool.ErrArticleNotFound) || sess.store.holes.count != 0 {
		t.Fatalf("boundary spent budget: err=%v count=%d", r.boundary.err, sess.store.holes.count)
	}
	r.Close()
	resp, b, err := requestRange(t, srv, "bytes=20000-20009")
	if err != nil || resp.StatusCode != 206 || !bytes.Equal(b, make([]byte, 10)) || sess.store.holes.count != 1 {
		t.Fatalf("playback did not fill: status=%d bytes=%d err=%v", resp.StatusCode, len(b), err)
	}
}
