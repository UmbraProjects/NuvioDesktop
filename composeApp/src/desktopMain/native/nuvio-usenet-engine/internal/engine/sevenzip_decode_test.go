package engine

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/NuvioMedia/nuvio-usenet-engine/internal/testsupport/nntpserver"
)

// lzma.7z is `7z a -mx1 -mhe=on -p...`: an LZMA-compressed, AES-encrypted
// movie.mkv. It used to be refused; it now plays through the decoder.
func shrinkDecodeWindows(t *testing.T, back, tail int64) {
	t.Helper()
	oldBack, oldTail := decodedBackBuffer, decodedTailSkip
	decodedBackBuffer, decodedTailSkip = back, tail
	t.Cleanup(func() { decodedBackBuffer, decodedTailSkip = oldBack, oldTail })
}

func compressedContent(t *testing.T) *Content {
	t.Helper()
	data := sevenZipFixture(t, "lzma.7z")
	files := setupWithPassword(t, splitVolumes(data, func(i int) string { return fmt.Sprintf("Release.7z.%03d", i+1) }, 3000, repeat(8192, 8)...), sevenZipTestPassword)
	c, err := Select(context.Background(), files, Selection{})
	if err != nil {
		t.Fatal(err)
	}
	if c.decoded == nil || c.Name != "movie.mkv" || c.Size != int64(len(moviePayload())) {
		t.Fatalf("selected %q (%d bytes, decoded=%v)", c.Name, c.Size, c.decoded != nil)
	}
	return c
}

func TestSevenZipCompressedPlaysSequentially(t *testing.T) {
	c := compressedContent(t)
	if got := readAllContent(t, c); !bytes.Equal(got, moviePayload()) {
		t.Fatalf("decoded payload differs (%d bytes)", len(got))
	}
}

// Backward reads inside the buffer come from memory, before it the decoder
// starts over, forward reads decode on: every byte must still be right.
func TestSevenZipCompressedSeeks(t *testing.T) {
	shrinkDecodeWindows(t, 4096, 1<<30) // No tail refusal here.
	want := moviePayload()
	c := compressedContent(t)
	r := c.Reader(context.Background(), 2)
	defer r.Close()
	rng := rand.New(rand.NewSource(7))
	for i := 0; i < 150; i++ {
		off := rng.Int63n(c.Size)
		buf := make([]byte, 1+rng.Intn(9000))
		if _, err := r.Seek(off, io.SeekStart); err != nil {
			t.Fatal(err)
		}
		n, err := io.ReadFull(r, buf)
		if err != nil && !(errors.Is(err, io.ErrUnexpectedEOF) && off+int64(n) == c.Size) {
			t.Fatalf("read at %d: %v", off, err)
		}
		if !bytes.Equal(buf[:n], want[off:off+int64(n)]) {
			t.Fatalf("read mismatch at %d (+%d)", off, n)
		}
	}
	if starts := c.decodedStarts(); starts < 2 {
		t.Fatalf("decoder started %d times; the seeks never went behind its buffer", starts)
	}
}

func TestSevenZipCompressedBackBufferAvoidsRestart(t *testing.T) {
	shrinkDecodeWindows(t, 20000, 1<<30)
	want := moviePayload()
	c := compressedContent(t)
	r := c.Reader(context.Background(), 2)
	defer r.Close()
	buf := make([]byte, 6000)
	for _, off := range []int64{0, 20000, 15000, 40000, 30000} {
		r.Seek(off, io.SeekStart)
		if _, err := io.ReadFull(r, buf); err != nil || !bytes.Equal(buf, want[off:off+6000]) {
			t.Fatalf("read at %d: %v", off, err)
		}
	}
	// One start (the probe at selection); 0, 15000 and 30000 are in the buffer.
	if starts := c.decodedStarts(); starts != 1 {
		t.Fatalf("decoder started %d times, want 1", starts)
	}
}

// A jump far ahead into the file's end (a player's index read) is refused at
// once with a temporary error, which does not fail the session; reaching the
// end by reading on still works.
func TestSevenZipCompressedRefusesFarTailRead(t *testing.T) {
	shrinkDecodeWindows(t, 4096, 10000)
	want := moviePayload()
	c := compressedContent(t)
	r := c.Reader(context.Background(), 2)
	defer r.Close()
	head := make([]byte, 1000)
	if _, err := io.ReadFull(r, head); err != nil {
		t.Fatal(err)
	}
	r.Seek(c.Size-500, io.SeekStart)
	if _, err := r.Read(make([]byte, 500)); !errors.Is(err, errDecodeAhead) {
		t.Fatalf("far tail read: err = %v, want errDecodeAhead", err)
	}
	if kind := permanentStreamFailure(errDecodeAhead); kind != "" {
		t.Fatalf("a refused tail read must not retire the session (kind %q)", kind)
	}
	// A middle seek is honoured (decoded through), and from there the tail is near.
	mid := c.Size - 15000
	r.Seek(mid, io.SeekStart)
	got, err := io.ReadAll(r)
	if err != nil || !bytes.Equal(got, want[mid:]) {
		t.Fatalf("read on to the end: %d bytes, %v", len(got), err)
	}
}

// The player's view through HTTP, as for the stored fixtures.
func TestSevenZipCompressedSessionRanges(t *testing.T) {
	want := moviePayload()
	vols := splitVolumes(sevenZipFixture(t, "lzma.7z"), func(i int) string { return fmt.Sprintf("Release.7z.%03d", i+1) }, 3000, repeat(8192, 8)...)
	xml, articles := fixtureWithPassword(vols, sevenZipTestPassword)
	provider, err := nntpserver.New(nntpserver.Config{Articles: articles})
	if err != nil {
		t.Fatal(err)
	}
	defer provider.Close()
	nzb := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, xml) }))
	defer nzb.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	app := NewServer(ctx, "secret", nil, nzb.Client())
	defer app.Close()
	srv := httptest.NewServer(app)
	defer srv.Close()
	b, _ := json.Marshal(OpenRequest{NZBURL: nzb.URL, Servers: []string{"nntp://" + provider.Addr() + "/4"},
		Config: Config{AllowPrivateNetwork: true, FastMKVStartup: true}})
	req, _ := http.NewRequest(http.MethodPost, srv.URL+"/sessions", bytes.NewReader(b))
	req.Header.Set("Authorization", "Bearer secret")
	resp, err := srv.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("open status %d: %s", resp.StatusCode, body)
	}
	if !strings.Contains(string(body), `"compressed7z":true`) {
		t.Fatalf("open answer does not say the release is a compressed 7z: %s", body)
	}
	var session struct {
		Path string
		Size int64
	}
	if err := json.Unmarshal(body, &session); err != nil || session.Size != int64(len(want)) || !strings.HasSuffix(session.Path, "/movie.mkv") {
		t.Fatalf("session %s: %v", body, err)
	}
	for _, rng := range []struct {
		header   string
		from, to int64
	}{{"", 0, int64(len(want))}, {"bytes=1000-2000", 1000, 2001}, {"bytes=50001-", 50001, int64(len(want))}, {"bytes=-777", int64(len(want)) - 777, int64(len(want))}} {
		req, _ := http.NewRequest(http.MethodGet, srv.URL+session.Path, nil)
		if rng.header != "" {
			req.Header.Set("Range", rng.header)
		}
		resp, err := srv.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		got, err := io.ReadAll(resp.Body)
		resp.Body.Close()
		if err != nil || !bytes.Equal(got, want[rng.from:rng.to]) {
			t.Fatalf("range %q: status=%d len=%d err=%v", rng.header, resp.StatusCode, len(got), err)
		}
	}
}

// A volume reader answers one article per call; the archive view the decoder
// reads through must keep going across article boundaries.
func TestSevenZipArchiveReadsAcrossArticles(t *testing.T) {
	data := sevenZipFixture(t, "store.7z")
	files := setupWithPassword(t, splitVolumes(data, func(i int) string { return fmt.Sprintf("x.7z.%03d", i+1) }, 1000, repeat(16384, 10)...), "")
	vols, err := sevenZipLayout(context.Background(), files, true)
	if err != nil {
		t.Fatal(err)
	}
	for _, ahead := range []int{0, 2} {
		a := newSevenZipArchive(context.Background(), vols)
		a.ahead = ahead
		got := make([]byte, len(data)-100)
		if n, err := a.ReadAt(got, 50); err != nil || n != len(got) || !bytes.Equal(got, data[50:len(data)-50]) {
			t.Fatalf("ahead %d: read %d of %d: %v", ahead, n, len(got), err)
		}
		a.Close()
	}
}

// Over the app's size limit, a compressed 7z is refused at open as an unplayable release.
func TestSevenZipCompressedOverLimitIsRefused(t *testing.T) {
	vols := splitVolumes(sevenZipFixture(t, "lzma.7z"), func(i int) string { return fmt.Sprintf("Release.7z.%03d", i+1) }, 3000, repeat(8192, 8)...)
	xml, articles := fixtureWithPassword(vols, sevenZipTestPassword)
	provider, err := nntpserver.New(nntpserver.Config{Articles: articles})
	if err != nil {
		t.Fatal(err)
	}
	defer provider.Close()
	nzb := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, xml) }))
	defer nzb.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	app := NewServer(ctx, "secret", nil, nzb.Client())
	defer app.Close()
	srv := httptest.NewServer(app)
	defer srv.Close()
	for _, tc := range []struct {
		limit  int64
		status int
	}{{1000, 422}, {1 << 30, 200}, {0, 200}} {
		b, _ := json.Marshal(OpenRequest{NZBURL: nzb.URL, Servers: []string{"nntp://" + provider.Addr() + "/4"},
			Config: Config{AllowPrivateNetwork: true, MaxCompressedBytes: tc.limit}})
		req, _ := http.NewRequest(http.MethodPost, srv.URL+"/sessions", bytes.NewReader(b))
		req.Header.Set("Authorization", "Bearer secret")
		resp, err := srv.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode != tc.status {
			t.Fatalf("limit %d: status %d (%s), want %d", tc.limit, resp.StatusCode, body, tc.status)
		}
		if tc.status == 422 && (resp.Header.Get("X-Usenet-Failure") != "archive-compressed" || !strings.Contains(string(body), "compressed 7z")) {
			t.Fatalf("limit %d: failure %q, body %q", tc.limit, resp.Header.Get("X-Usenet-Failure"), body)
		}
		if tc.status == 200 {
			var session struct{ ID string }
			json.Unmarshal(body, &session)
			del, _ := http.NewRequest(http.MethodDelete, srv.URL+"/sessions/"+session.ID, nil)
			del.Header.Set("Authorization", "Bearer secret")
			if r, err := srv.Client().Do(del); err == nil {
				r.Body.Close()
			}
		}
	}
}

// The app's drain request reaches the provider; 0 keeps the engine's 64 KiB.
func TestAbortDrainRequest(t *testing.T) {
	for _, tc := range []struct{ requested, want int64 }{{0, 64 << 10}, {1 << 20, 1 << 20}, {1 << 30, 64 << 20}} {
		providers, err := Providers([]string{"nntp://news.example:119/4"}, Config{AbortDrainBytes: tc.requested}, nil)
		if err != nil {
			t.Fatal(err)
		}
		if providers[0].AbortDrainBytes != tc.want {
			t.Fatalf("requested %d: drain %d, want %d", tc.requested, providers[0].AbortDrainBytes, tc.want)
		}
	}
}

func TestPipelineRequest(t *testing.T) {
	for _, tc := range []struct{ requested, want int }{{0, 4}, {1, 1}} {
		providers, err := Providers([]string{"nntp://news.example:119/40"}, Config{Profile: "throughput", Pipeline: tc.requested}, nil)
		if err != nil {
			t.Fatal(err)
		}
		if providers[0].Inflight != tc.want || providers[0].StreamInflight != tc.want {
			t.Fatalf("requested %d: inflight %d/%d, want %d", tc.requested, providers[0].Inflight, providers[0].StreamInflight, tc.want)
		}
	}
	if _, err := (Config{Pipeline: 17}).Tuning(); err == nil {
		t.Fatal("pipeline 17 accepted")
	}
}
