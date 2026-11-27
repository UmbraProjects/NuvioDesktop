package engine

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/NuvioMedia/nuvio-usenet-engine/internal/testsupport/nntpserver"
	"github.com/javi11/nntppool/v4"
)

// A "?preview=1" read (seek thumbnails) gets the bytes it asks for with only a few articles of
// read-ahead, and is not the session's latest range; a plain read queues the full window.
func TestPreviewReadsHaveSmallReadAhead(t *testing.T) {
	data := payload(64 * 64 << 10)
	sizes := make([]int, 64)
	for i := range sizes {
		sizes[i] = 64 << 10
	}
	xml, articles := fixture([]inputFile{{"movie.mkv", data, sizes}})
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
		Config: Config{AllowPrivateNetwork: true, ReadAhead: 16}})
	req, _ := http.NewRequest(http.MethodPost, srv.URL+"/sessions", bytes.NewReader(b))
	req.Header.Set("Authorization", "Bearer secret")
	resp, err := srv.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	var session struct {
		ID   string
		Path string
	}
	if resp.StatusCode != 200 || json.Unmarshal(body, &session) != nil {
		t.Fatalf("open %d: %s", resp.StatusCode, body)
	}
	app.mu.Lock()
	s := app.sessions[session.ID]
	app.mu.Unlock()
	downloads := func() int64 {
		s.store.mu.Lock()
		defer s.store.mu.Unlock()
		return int64(s.store.stats.Downloads)
	}
	get := func(query string, from int64) []byte {
		req, _ := http.NewRequest(http.MethodGet, srv.URL+session.Path+query, nil)
		req.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", from, from+999))
		resp, err := srv.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		got, _ := io.ReadAll(resp.Body)
		return got
	}
	before := downloads()
	at := int64(40 * 64 << 10)
	if got := get("?preview=1", at); !bytes.Equal(got, data[at:at+1000]) {
		t.Fatalf("preview read wrong bytes (%d)", len(got))
	}
	if n := downloads() - before; n > 1+previewReadAhead {
		t.Fatalf("a preview read downloaded %d articles; want at most %d", n, 1+previewReadAhead)
	}
	if latest := s.trace.snapshot()["latestRange"]; latest != nil {
		t.Fatalf("a preview read became the latest range: %+v", latest)
	}
	before = downloads()
	at = int64(10 * 64 << 10)
	if got := get("", at); !bytes.Equal(got, data[at:at+1000]) {
		t.Fatal("plain read wrong bytes")
	}
	if n := downloads() - before; n <= 1+previewReadAhead {
		t.Fatalf("a plain read downloaded %d articles; the full window expected", n)
	}
}

// A compressed 7z has one decoder shared with playback: previews are refused.
func TestPreviewRefusedForCompressed7z(t *testing.T) {
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
	b, _ := json.Marshal(OpenRequest{NZBURL: nzb.URL, Servers: []string{"nntp://" + provider.Addr() + "/4"}, Config: Config{AllowPrivateNetwork: true}})
	req, _ := http.NewRequest(http.MethodPost, srv.URL+"/sessions", bytes.NewReader(b))
	req.Header.Set("Authorization", "Bearer secret")
	resp, err := srv.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	var session struct{ Path string }
	json.Unmarshal(body, &session)
	resp, err = srv.Client().Get(srv.URL + session.Path + "?preview=1")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("preview of a compressed 7z: status %d, want 409", resp.StatusCode)
	}
}

// With Config.ReadAheadRamp the player's request starts with a small window and grows to the
// full one as it is read; without it the full window starts at once.
func TestReadAheadRamp(t *testing.T) {
	r := (&Content{Size: 1 << 30}).Reader(context.Background(), 32)
	if r.window() != 32 {
		t.Fatalf("no ramp: window %d", r.window())
	}
	r.startRamp(4)
	want := []int{4, 8, 16, 32, 32}
	for i, w := range want {
		if r.window() != w {
			t.Fatalf("step %d: window %d, want %d", i, r.window(), w)
		}
		r.grow(int(readAheadRampStep))
	}
	small := (&Content{Size: 1 << 30}).Reader(context.Background(), 4)
	small.startRamp(4)
	if small.window() != 4 || small.rampAhead != 0 {
		t.Fatal("a window no larger than the ramp's start must not ramp")
	}
}

// A file of fixed-size articles locks its grid once an article past the first is learned; a
// seek then computes its article. An article off the grid turns the lock off again.
func TestPartGridLock(t *testing.T) {
	const part = 1000
	f := &File{segments: make([]segment, 10), prefix: make([]int64, 11), size: 9500}
	for i := range f.segments {
		f.prefix[i+1] = f.prefix[i] + 1020 // wire sizes, a little off the decoded ones
	}
	learn := func(i int, begin, size int64) error {
		return f.learn(i, nntppool.YEncMeta{Part: int64(i + 1), Total: 10, PartBegin: begin, PartSize: size, FileSize: 9500})
	}
	if err := learn(0, 0, part); err != nil {
		t.Fatal(err)
	}
	if f.partSize != 0 {
		t.Fatal("the first article alone must not lock the grid")
	}
	if err := learn(4, 4*part, part); err != nil {
		t.Fatal(err)
	}
	if f.partSize != part {
		t.Fatalf("grid not locked: %d", f.partSize)
	}
	if i := f.locate(7*part+10, 0, 9); i != 7 {
		t.Fatalf("locate = %d, want 7", i)
	}
	if err := learn(9, 9*part, 500); err != nil { // the shorter last article fits the grid
		t.Fatal(err)
	}
	if f.partSize != part {
		t.Fatal("a short last article broke the grid")
	}
	g := &File{segments: make([]segment, 10), prefix: f.prefix, size: 9500}
	g.learn(3, nntppool.YEncMeta{Part: 4, Total: 10, PartBegin: 3000, PartSize: 1000, FileSize: 9500})
	g.learn(5, nntppool.YEncMeta{Part: 6, Total: 10, PartBegin: 5100, PartSize: 900, FileSize: 9500})
	if g.partSize != -1 {
		t.Fatalf("an article off the grid must unlock it: %d", g.partSize)
	}
}

func TestReadAheadSize(t *testing.T) {
	s := NewStore(context.Background(), nil, 128<<20)
	if got := s.readAheadLimit(); got != 32<<20 {
		t.Fatalf("default: %d", got)
	}
	s.readAheadCap = 48 << 20
	if got := s.readAheadLimit(); got != 48<<20 {
		t.Fatalf("48 MiB asked: %d", got)
	}
	s.readAheadCap = 100 << 20
	if got := s.readAheadLimit(); got != 48<<20 {
		t.Fatalf("never past 3/8 of 128 MiB: %d", got)
	}
}

// A password the app passes (from "Name{{password}}") opens an encrypted 7z whose NZB has none.
func TestPasswordFromTheApp(t *testing.T) {
	vols := splitVolumes(sevenZipFixture(t, "aes_hdr.7z"), func(i int) string { return fmt.Sprintf("Release.7z.%03d", i+1) }, 7000, repeat(16384, 10)...)
	xml, articles := fixtureWithPassword(vols, "") // No <meta type="password">.
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
		password string
		status   int
	}{{"", 422}, {sevenZipTestPassword, 200}} {
		b, _ := json.Marshal(OpenRequest{NZBURL: nzb.URL, Servers: []string{"nntp://" + provider.Addr() + "/4"},
			Password: tc.password, Config: Config{AllowPrivateNetwork: true}})
		req, _ := http.NewRequest(http.MethodPost, srv.URL+"/sessions", bytes.NewReader(b))
		req.Header.Set("Authorization", "Bearer secret")
		resp, err := srv.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode != tc.status {
			t.Fatalf("password %q: status %d (%s), want %d", tc.password, resp.StatusCode, body, tc.status)
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
