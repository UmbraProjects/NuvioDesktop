package engine

import (
	"context"
	"fmt"
	"io"
	"os"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/NuvioMedia/nuvio-usenet-engine/internal/testsupport/nntpserver"
	"github.com/javi11/nntppool/v4"
)

// Opt-in (NUVIO_SEEK_BENCH=1): a seek in the middle of steady playback, when the read-ahead
// window is full and every connection carries bodies for the old position. Measures the
// time to the first byte at the new position and to 24 MiB (about what mpv reads before a
// 4K remux resumes). Simulated link close to a real one measured on Nov 17: ~30 MB/s total,
// ~350 ms to first byte, 40 connections. No TLS, so reconnects cost less than for real.
func TestSeekUnderLoad(t *testing.T) {
	if os.Getenv("NUVIO_SEEK_BENCH") != "1" {
		t.Skip("set NUVIO_SEEK_BENCH=1")
	}
	data := payload(192 << 20)
	sizes := make([]int, len(data)/(768<<10))
	for i := range sizes {
		sizes[i] = 768 << 10
	}
	xml, articles := fixture([]inputFile{{"movie.mkv", data, sizes}})
	type variant struct {
		name            string
		pipeline        int
		drain           int64
		ahead           int
	}
	// ramp: the reader's read-ahead ramp (readAheadRampStart); off: the full window at once.
	variants := []variant{
		{"interpolate (before)", 4, 1 << 20, 32},
		{"grid lock (now)", 4, 1 << 20, 32},
	}
	const trials = 5
	results := map[string][]time.Duration{}
	firsts := map[string][]time.Duration{}
	threes := map[string][]time.Duration{}
	twelves := map[string][]time.Duration{}
	sixes := map[string][]time.Duration{}
	misses := map[string][]int{}
	for trial := 0; trial < trials; trial++ {
		for _, v := range variants {
			s, err := nntpserver.New(nntpserver.Config{Articles: articles, RTT: 350 * time.Millisecond, BandwidthPerConn: 2 << 20, AggregateBandwidth: 30 << 20})
			if err != nil {
				t.Fatal(err)
			}
			providers, err := Providers([]string{fmt.Sprintf("nntp://%s/40", s.Addr())}, Config{Profile: "throughput", AllowPrivateNetwork: true, AbortDrainBytes: v.drain}, nil)
			if err != nil {
				t.Fatal(err)
			}
			providers[0].Inflight, providers[0].StreamInflight = v.pipeline, v.pipeline
			pool, err := nntppool.NewClient(context.Background(), providers, nntppool.WithStatProbe(false))
			if err != nil {
				t.Fatal(err)
			}
			store := NewStore(context.Background(), pool, 128<<20)
			files, err := ParseNZB(strings.NewReader(xml), store)
			if err != nil {
				t.Fatal(err)
			}
			content, err := Select(context.Background(), files, Selection{})
			if err != nil {
				t.Fatal(err)
			}
			oldStep, oldGrid := readAheadRampStep, partGridLock
			partGridLock = !strings.Contains(v.name, "interpolate")
			rampStart := 0
			if strings.Contains(v.name, "ramp") {
				var kib int64
				fmt.Sscanf(v.name, "ramp %d step %dK", &rampStart, &kib)
				readAheadRampStep = kib << 10
			}
			// Play at ~10 MB/s for 4 s so the window fills and connections are busy.
			// A negative ahead plays with the full window and seeks with |ahead|.
			playAhead, seekAhead := v.ahead, v.ahead
			if v.ahead < 0 {
				playAhead, seekAhead = 32, -v.ahead
			}
			r := content.Reader(context.Background(), playAhead)
			buf := make([]byte, 256<<10)
			deadline := time.Now().Add(4 * time.Second)
			for time.Now().Before(deadline) {
				if _, err := io.ReadFull(r, buf); err != nil {
					t.Fatal(err)
				}
				time.Sleep(25 * time.Millisecond)
			}
			r.Close() // mpv drops the old range...
			start := time.Now()
			r = content.Reader(context.Background(), seekAhead) // ...and opens one far away.
			r.startRamp(rampStart)
			r.Seek(int64(len(data))*2/3, io.SeekStart)
			if _, err := io.ReadFull(r, buf[:32<<10]); err != nil {
				t.Fatal(err)
			}
			first := time.Since(start)
			if _, err := io.CopyN(io.Discard, r, 3<<20); err != nil {
				t.Fatal(err)
			}
			threes[v.name] = append(threes[v.name], time.Since(start))
			if _, err := io.CopyN(io.Discard, r, 3<<20); err != nil {
				t.Fatal(err)
			}
			sixes[v.name] = append(sixes[v.name], time.Since(start))
			if _, err := io.CopyN(io.Discard, r, 6<<20); err != nil {
				t.Fatal(err)
			}
			twelves[v.name] = append(twelves[v.name], time.Since(start))
			if _, err := io.CopyN(io.Discard, r, 12<<20); err != nil {
				t.Fatal(err)
			}
			results[v.name] = append(results[v.name], time.Since(start))
			firsts[v.name] = append(firsts[v.name], first)
			r.Close()
			readAheadRampStep, partGridLock = oldStep, oldGrid
			store.mu.Lock()
			misses[v.name] = append(misses[v.name], store.stats.LocateMisses)
			store.mu.Unlock()
			store.Close()
			pool.Close()
			s.Close()
		}
	}
	median := func(d []time.Duration) time.Duration {
		c := append([]time.Duration(nil), d...)
		sort.Slice(c, func(i, j int) bool { return c[i] < c[j] })
		return c[len(c)/2]
	}
	for _, v := range variants {
		t.Logf("%-22s first byte %4d ms   3 MiB %5d ms   6 MiB %5d ms   12 MiB %5d ms   24 MiB %5d ms (medians of %d)", v.name,
			median(firsts[v.name]).Milliseconds(), median(threes[v.name]).Milliseconds(), median(sixes[v.name]).Milliseconds(), median(twelves[v.name]).Milliseconds(),
			median(results[v.name]).Milliseconds(), len(results[v.name]))
		t.Logf("%-22s wrong article guesses per run: %v", v.name, misses[v.name])
	}
}
