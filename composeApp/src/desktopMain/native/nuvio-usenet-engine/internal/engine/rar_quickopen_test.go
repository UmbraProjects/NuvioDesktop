package engine

import (
	"bytes"
	"context"
	"encoding/binary"
	"fmt"
	"hash/crc32"
	"io"
	"strings"
	"testing"
)

// qoVolume options. The zero value writes a volume as rar does: a main header
// whose locator names the Quick Open block, which copies every file header.
type qoVolume struct {
	noLocator bool                         // A main header without a locator and no block.
	mutate    func(i int, h []byte) []byte // Changes the i-th copy (CRCs are rewritten).
	badRecord int                          // 1-based record whose record CRC is broken.
	drop      func(i int) bool             // Leaves the i-th header without a copy, as rar does for small files.
	offset    func(int64) int64            // Changes the locator's offset.
}

// rarPadded5 is a five-byte vint, as rar reserves for the locator's offset.
func rarPadded5(v int64) []byte {
	out := make([]byte, 5)
	for i := range out {
		out[i] = byte(v & 127)
		v >>= 7
		if i < 4 {
			out[i] |= 128
		}
	}
	return out
}

// rar5QuickOpenVolume writes one RAR5 volume of entries (rar4Entry flags: 1
// continues from the previous volume, 2 in the next) followed by its Quick
// Open block and the end header.
func rar5QuickOpenVolume(index int, opt qoVolume, entries ...rar4Entry) []byte {
	af := uint64(1)
	if index > 0 {
		af = 3
	}
	mainBody := func(offset int64) []byte {
		if opt.noLocator {
			return append([]byte{1, 0}, rar5Main(index)[2:]...)
		}
		extra := append([]byte{7, 1, 1}, rarPadded5(offset)...)
		body := []byte{1, 5, byte(len(extra))}
		body = binary.AppendUvarint(body, af)
		if index > 0 {
			body = binary.AppendUvarint(body, uint64(index))
		}
		return append(body, extra...)
	}
	const mainPos = 8
	main := rar5TestHeader(mainBody(0))
	var files bytes.Buffer
	var copies [][]byte
	var at []int64
	for _, e := range entries {
		flags := uint64(0)
		if e.flags&1 != 0 {
			flags |= 8
		}
		if e.flags&2 != 0 {
			flags |= 16
		}
		h := rar5TestHeader(rar5FileBody(e.name, len(e.data), e.total, flags))
		at = append(at, int64(mainPos+len(main)+files.Len()))
		copies = append(copies, h)
		files.Write(h)
		files.Write(e.data)
	}
	qo := int64(mainPos + len(main) + files.Len())
	out := []byte("Rar!\x1a\x07\x01\x00")
	if opt.noLocator {
		out = append(out, main...)
		out = append(out, files.Bytes()...)
		return append(out, rar5TestHeader([]byte{5, 0, 0})...)
	}
	offset := qo - mainPos
	if opt.offset != nil {
		offset = opt.offset(offset)
	}
	out = append(out, rar5TestHeader(mainBody(offset))...)
	out = append(out, files.Bytes()...)
	var data []byte
	for i, h := range copies {
		if opt.drop != nil && opt.drop(i) {
			continue
		}
		if opt.mutate != nil {
			if m := opt.mutate(i, append([]byte(nil), h...)); m != nil {
				binary.LittleEndian.PutUint32(m, crc32.ChecksumIEEE(m[4:]))
				h = m
			}
		}
		rec := binary.AppendUvarint([]byte{0}, uint64(qo-at[i]))
		rec = binary.AppendUvarint(rec, uint64(len(h)))
		rec = append(rec, h...)
		sized := append(binary.AppendUvarint(nil, uint64(len(rec))), rec...)
		crc := crc32.ChecksumIEEE(sized)
		if opt.badRecord == i+1 {
			crc++
		}
		data = append(binary.LittleEndian.AppendUint32(data, crc), sized...)
	}
	service := []byte{3, 2}
	service = binary.AppendUvarint(service, uint64(len(data)))
	service = append(service, 0)
	service = binary.AppendUvarint(service, uint64(len(data)))
	service = append(service, 0, 0, 1, 2, 'Q', 'O')
	out = append(out, rar5TestHeader(service)...)
	out = append(out, data...)
	return append(out, rar5TestHeader([]byte{5, 0, 0})...)
}

// qoDisc is a disc posted in two volumes: its CLPI, playlists and menu clips,
// then the feature, which ends in the second volume before one more clip.
func qoDisc(opt func(volume int) qoVolume) ([]inputFile, []byte, []byte) {
	feature := payload(300 << 10)
	clpi := bytes.Repeat([]byte{0xC1}, 900)
	var lead []rar4Entry
	lead = append(lead, rar4Entry{"Disc/BDMV/CLIPINF/00800.clpi", clpi, len(clpi), 0})
	for i := 0; i < 4; i++ {
		lead = append(lead, rar4Entry{fmt.Sprintf("Disc/BDMV/PLAYLIST/%05d.mpls", i), bytes.Repeat([]byte{byte(i)}, 300), 300, 0})
	}
	for i := 0; i < 12; i++ {
		clip := bytes.Repeat([]byte{byte(0x40 + i)}, 6000+i*97)
		lead = append(lead, rar4Entry{fmt.Sprintf("Disc/BDMV/STREAM/%05d.m2ts", i), clip, len(clip), 0})
	}
	split := 200 << 10
	first := append(lead, rar4Entry{"Disc/BDMV/STREAM/00800.m2ts", feature[:split], len(feature), 2})
	second := []rar4Entry{
		{"Disc/BDMV/STREAM/00800.m2ts", feature[split:], len(feature), 1},
		{"Disc/BDMV/STREAM/00900.m2ts", bytes.Repeat([]byte{9}, 5000), 5000, 0},
	}
	// Small articles put most headers in articles of their own.
	sizes := func(n int) []int {
		var out []int
		for n > 0 {
			out = append(out, min(n, 4096))
			n -= 4096
		}
		return out
	}
	v0 := rar5QuickOpenVolume(0, opt(0), first...)
	v1 := rar5QuickOpenVolume(1, opt(1), second...)
	return []inputFile{
		{"disc.part1.rar", v0, sizes(len(v0))},
		{"disc.part2.rar", v1, sizes(len(v1))},
	}, feature, clpi
}

type qoResult struct {
	name     string
	size     int64
	clipInfo []byte
	bodies   int64
	states   []int
	served   []int // Copies still standing in for headers after selection.
}

func runQuickOpenDisc(t *testing.T, opt func(volume int) qoVolume) qoResult {
	t.Helper()
	in, feature, clpi := qoDisc(opt)
	files, _, server := setup(t, in, 0, 4, 4)
	ctx := context.Background()
	c, err := Select(ctx, files, Selection{})
	if err != nil {
		t.Fatal(err)
	}
	bodies := server.Counters().Bodies
	r := c.Reader(ctx, 0)
	got, err := io.ReadAll(r)
	r.Close()
	if err != nil || !bytes.Equal(got, feature) {
		t.Fatalf("feature read: %d bytes, err=%v, equal=%v", len(got), err, bytes.Equal(got, feature))
	}
	res := qoResult{name: c.Name, size: c.Size, bodies: bodies}
	if c.clipInfo != nil {
		r := c.clipInfo.Reader(ctx, 0)
		res.clipInfo, err = io.ReadAll(r)
		r.Close()
		if err != nil || !bytes.Equal(res.clipInfo, clpi) {
			t.Fatalf("clip info read: err=%v equal=%v", err, bytes.Equal(res.clipInfo, clpi))
		}
	}
	for _, f := range files {
		f.quickOpen.mu.Lock()
		res.states = append(res.states, f.quickOpen.state)
		res.served = append(res.served, len(f.quickOpen.served))
		f.quickOpen.mu.Unlock()
	}
	return res
}

func TestRARQuickOpenServesDiscWalk(t *testing.T) {
	plain := runQuickOpenDisc(t, func(int) qoVolume { return qoVolume{noLocator: true} })
	quick := runQuickOpenDisc(t, func(int) qoVolume { return qoVolume{} })
	if quick.name != plain.name || quick.size != plain.size || !bytes.Equal(quick.clipInfo, plain.clipInfo) {
		t.Fatalf("Quick Open selected %q/%d, headers selected %q/%d", quick.name, quick.size, plain.name, plain.size)
	}
	if !strings.HasSuffix(quick.name, "00800.m2ts") || quick.clipInfo == nil {
		t.Fatalf("selected %q, clip info %v", quick.name, quick.clipInfo != nil)
	}
	if quick.states[0] != rarQuickOpenLoaded {
		t.Fatalf("first volume Quick Open state %d, want loaded", quick.states[0])
	}
	// The second volume holds only the feature's continuation: one header.
	if quick.states[1] != rarQuickOpenPending {
		t.Fatalf("second volume Quick Open state %d, want untouched", quick.states[1])
	}
	if quick.bodies >= plain.bodies {
		t.Fatalf("selection fetched %d articles with Quick Open, %d without", quick.bodies, plain.bodies)
	}
	t.Logf("selection articles: %d with Quick Open, %d without", quick.bodies, plain.bodies)
}

// Copies that disagree with the headers, broken records or a wrong locator
// leave the walk on the headers themselves, with the same result.
func TestRARQuickOpenFallsBack(t *testing.T) {
	plain := runQuickOpenDisc(t, func(int) qoVolume { return qoVolume{noLocator: true} })
	rename := func(at int) func(int, []byte) []byte {
		return func(i int, h []byte) []byte {
			if i != at {
				return nil
			}
			j := bytes.LastIndex(h, []byte(".m"))
			if j < 0 {
				j = bytes.LastIndex(h, []byte(".c"))
			}
			h[j-1] ^= 1
			return h
		}
	}
	cases := map[string]func(int) qoVolume{
		"copy of a header already read differs": func(int) qoVolume { return qoVolume{mutate: rename(0)} },
		"record CRC":                            func(int) qoVolume { return qoVolume{badRecord: 7} },
		"locator points elsewhere":              func(int) qoVolume { return qoVolume{offset: func(o int64) int64 { return o - 3 }} },
		"locator past the volume":               func(int) qoVolume { return qoVolume{offset: func(o int64) int64 { return o + 1<<30 }} },
		// A copy read only from the block: the selected entry's own header is
		// checked before it is served, and selection runs again from headers.
		"selected entry's copy differs": func(int) qoVolume {
			return qoVolume{mutate: func(i int, h []byte) []byte {
				if i != 17 {
					return nil
				}
				// Same name and length, data size one byte shorter.
				return rar5TestHeader(rar5FileBody("Disc/BDMV/STREAM/00800.m2ts", 200<<10-1, 300<<10, 16))
			}}
		},
	}
	// No header read before the block has a copy, so none vouches for the
	// copies: the first copy is compared with its header, read as before.
	cases["first copy compared differs"] = func(int) qoVolume {
		return qoVolume{drop: func(i int) bool { return i < 3 }, mutate: rename(3)}
	}
	// A copy that does not parse sends its header to the volume; when that
	// header differs from the copy, copies served before it are not trusted
	// either, and selection runs again from the headers.
	cases["copy found to differ after others were served"] = func(int) qoVolume {
		return qoVolume{mutate: func(i int, h []byte) []byte {
			if i != 10 {
				return nil
			}
			body := []byte{2, 2, 0, 0, 0, 0, 0x80, 1}
			body = binary.AppendUvarint(body, uint64(len("x")))
			return rar5TestHeader(append(body, 'x')) // A compressed entry: rar5Block refuses it.
		}}
	}
	for name, opt := range cases {
		t.Run(name, func(t *testing.T) {
			got := runQuickOpenDisc(t, opt)
			if got.name != plain.name || got.size != plain.size || !bytes.Equal(got.clipInfo, plain.clipInfo) {
				t.Fatalf("selected %q/%d, want %q/%d", got.name, got.size, plain.name, plain.size)
			}
			if got.states[0] == rarQuickOpenLoaded {
				t.Fatalf("Quick Open stayed in use")
			}
			// Copies that served the walk were dropped and the walk ran again.
			if got.served[0] != 0 {
				t.Fatalf("selection kept %d copies", got.served[0])
			}
		})
	}
}

// rar copies only some headers: the others are read from the volume, and the
// first copy compared with its header lets the rest be served.
func TestRARQuickOpenWithoutSomeCopies(t *testing.T) {
	plain := runQuickOpenDisc(t, func(int) qoVolume { return qoVolume{noLocator: true} })
	for name, drop := range map[string]func(int) bool{
		"headers read first have none": func(i int) bool { return i < 3 },
		"every other header has none":  func(i int) bool { return i%2 == 0 && i != 17 },
		"the feature has none":         func(i int) bool { return i == 17 },
	} {
		t.Run(name, func(t *testing.T) {
			got := runQuickOpenDisc(t, func(int) qoVolume { return qoVolume{drop: drop} })
			if got.name != plain.name || got.size != plain.size || !bytes.Equal(got.clipInfo, plain.clipInfo) {
				t.Fatalf("selected %q/%d, want %q/%d", got.name, got.size, plain.name, plain.size)
			}
			if got.states[0] != rarQuickOpenLoaded || got.bodies >= plain.bodies {
				t.Fatalf("state %d, %d articles with Quick Open, %d without", got.states[0], got.bodies, plain.bodies)
			}
		})
	}
}

func TestRARQuickOpenParsesRarLayout(t *testing.T) {
	v := rar5QuickOpenVolume(0, qoVolume{}, rar4Entry{"a.mkv", []byte("abc"), 3, 0}, rar4Entry{"b.mkv", []byte("defg"), 4, 0})
	r := bytes.NewReader(v)
	main, err := rar5Block(r, int64(len(v)), 8)
	if err != nil || main.quickOpen == 0 {
		t.Fatalf("main header: %+v err=%v", main, err)
	}
	head, err := rarRawHeader(r, 8+main.quickOpen, int64(len(v)))
	if err != nil {
		t.Fatal(err)
	}
	size, _, _, err := rarQuickOpenService(head)
	if err != nil {
		t.Fatal(err)
	}
	start := 8 + main.quickOpen + int64(len(head))
	headers, err := parseRARQuickOpen(v[start:start+size], 8, 8+main.quickOpen)
	if err != nil || len(headers) != 2 {
		t.Fatalf("headers=%d err=%v", len(headers), err)
	}
	for pos, h := range headers {
		if !bytes.Equal(v[pos:pos+int64(len(h))], h) {
			t.Fatalf("copy at %d is not the header there", pos)
		}
	}
	// A truncated block never yields every copy.
	for n := 1; n < int(size); n++ {
		if hs, err := parseRARQuickOpen(v[start:start+int64(n)], 8, 8+main.quickOpen); err == nil && len(hs) == 2 {
			t.Fatalf("truncated block of %d bytes parsed every header", n)
		}
	}
	if rarQuickOpenOffset([]byte{7, 1, 0, 1, 2, 3, 4, 5}) != 0 {
		t.Fatal("a locator without the Quick Open flag named an offset")
	}
	if rarQuickOpenOffset([]byte{9, 1}) != 0 {
		t.Fatal("a truncated locator named an offset")
	}
}
