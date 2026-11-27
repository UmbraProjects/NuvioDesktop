package engine

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math/rand"
	"testing"
)

// rar5Reserved writes v in the nine bytes rar reserves for a locator offset.
func rar5Reserved(v uint64) []byte {
	b := make([]byte, 9)
	for i := range 8 {
		b[i] = byte(v>>(7*i))&0x7f | 0x80
	}
	b[8] = byte(v>>56) & 0x7f
	return b
}

// withReservedRecoveryRecord announces a recovery record in a RAR5 volume as
// rar writes it: the archive flag, and unless locator is 0 a locator record
// whose offsets are reserved nine bytes wide. The recovery record offset
// differs from volume to volume; a Quick Open offset of 0 names no block.
func withReservedRecoveryRecord(v []byte, index int, locator byte) []byte {
	mainSize, mainWidth := binary.Uvarint(v[12:])
	filePos := 12 + mainWidth + int(mainSize)
	main := []byte{1, 0, 9}
	if index > 0 {
		main[2] = 0x0b
		main = binary.AppendUvarint(main, uint64(index))
	}
	if locator != 0 {
		record := []byte{1, locator}
		if locator&1 != 0 {
			record = append(record, rar5Reserved(0)...)
		}
		if locator&2 != 0 {
			record = append(record, rar5Reserved(uint64(len(v)+1000*index))...)
		}
		extra := append(binary.AppendUvarint(nil, uint64(len(record))), record...)
		main = append([]byte{1, 1, byte(len(extra))}, main[2:]...)
		main = append(main, extra...)
	}
	out := append(append([]byte(nil), v[:8]...), rar5TestHeader(main)...)
	return append(out, v[filePos:]...)
}

var recoveryRecordLocators = []struct {
	name    string
	locator byte
}{
	{"flag only", 0},
	{"quick open and recovery record offsets", 3},
	{"recovery record offset", 2},
}

// A recovery record follows each volume's data, so a set made with one keeps
// the continuation layout and is predicted like any other.
func TestRARRecoveryRecordKeepsPrediction(t *testing.T) {
	for _, l := range recoveryRecordLocators {
		for _, count := range []int{50, 132} {
			t.Run(fmt.Sprintf("%s/%d", l.name, count), func(t *testing.T) {
				in, want, starts := regularRARSet(5, count, 2048, 31)
				for i := range in {
					in[i].data = withReservedRecoveryRecord(in[i].data, i, l.locator)
				}
				files, _, server := setup(t, in, 0, 4, 4)
				c, err := Select(context.Background(), files, Selection{})
				if err != nil {
					t.Fatal(err)
				}
				r := c.Reader(context.Background(), 0)
				defer r.Close()
				if _, err := r.Seek(-17, io.SeekEnd); err != nil {
					t.Fatal(err)
				}
				got, err := io.ReadAll(r)
				if err != nil || !bytes.Equal(got, want[len(want)-17:]) {
					t.Fatalf("EOF seek bytes differ: %v", err)
				}
				if got := server.Counters().Bodies; got != 3 {
					t.Fatalf("EOF seek fetched %d articles, want 3 regardless of volume count", got)
				}
				if len(c.parts) != count || c.predicted == nil {
					t.Fatal("continuation extents were not predicted")
				}
				buf := make([]byte, 18)
				for i := count - 1; i > 0; i-- {
					off := starts[i] - 9
					r.Seek(int64(off), io.SeekStart)
					if _, err := io.ReadFull(r, buf); err != nil || !bytes.Equal(buf, want[off:off+18]) {
						t.Fatalf("boundary %d differs: %v", i, err)
					}
				}
				if c.predicted == nil {
					t.Fatal("a correct prediction was rejected")
				}
			})
		}
	}
}

// A disc's feature inside a set made with a recovery record: its end is found
// from two headers, and selection passes the clip before it the same way.
func TestRARRecoveryRecordClipInsideTheSet(t *testing.T) {
	for _, l := range recoveryRecordLocators {
		t.Run(l.name, func(t *testing.T) {
			reserve := func(in []inputFile) {
				for i := range in {
					in[i].data = withReservedRecoveryRecord(in[i].data, i, l.locator)
				}
			}
			in, want, clip := discRARSet(t, 5, func(int) int { return 2048 })
			reserve(in)
			files, _, server := setup(t, in, 0, 4, 4)
			c, err := Select(context.Background(), files, Selection{})
			if err != nil || c.Name != discClip {
				t.Fatalf("selected %v, %v", c, err)
			}
			selected := server.Counters().Bodies
			r := c.Reader(context.Background(), 0)
			defer r.Close()
			r.Seek(-17, io.SeekEnd)
			if got, err := io.ReadAll(r); err != nil || !bytes.Equal(got, want[len(want)-17:]) {
				t.Fatalf("EOF seek bytes differ: %v", err)
			}
			if fetched := server.Counters().Bodies - selected; fetched > 2 {
				t.Fatalf("EOF seek fetched %d articles for a clip over %d volumes, want 2", fetched, len(clip))
			}
			if c.predicted == nil || c.predicted.base != clip[0].volume {
				t.Fatalf("continuation extents were not predicted from volume %d", clip[0].volume)
			}
			if got := readAllContent(t, c); !bytes.Equal(got, want) {
				t.Fatal("feature bytes differ")
			}

			in, want, _ = discRARSetLead(t, 5, func(int) int { return 2048 }, 40)
			reserve(in)
			files, _, server = setup(t, in, 0, 4, 4)
			c, err = Select(context.Background(), files, Selection{})
			if err != nil || c.Name != discClip {
				t.Fatalf("selected %v, %v", c, err)
			}
			if bodies := server.Counters().Bodies; bodies > 4 {
				t.Fatalf("selection read %d articles to pass a 40-volume clip", bodies)
			}
			if got := readAllContent(t, c); !bytes.Equal(got, want) {
				t.Fatal("feature bytes differ")
			}
		})
	}
}

// The checks behind a prediction still hold with a recovery record: a volume
// that breaks the layout is read from its headers.
func TestRARRecoveryRecordPredictionIsChecked(t *testing.T) {
	_, _, clip := discRARSet(t, 5, func(int) int { return 2048 })
	k := clip[20].volume
	size := func(i int) int {
		switch i {
		case k:
			return 2048 - 7
		case k + 1:
			return 2048 + 7
		}
		return 2048
	}
	in, want, clip := discRARSet(t, 5, size)
	for i := range in {
		in[i].data = withReservedRecoveryRecord(in[i].data, i, 3)
	}
	files, _, _ := setup(t, in, 0, 4, 4)
	c, err := Select(context.Background(), files, Selection{})
	if err != nil {
		t.Fatal(err)
	}
	r := c.Reader(context.Background(), 0)
	defer r.Close()
	r.Seek(-17, io.SeekEnd)
	if got, err := io.ReadAll(r); err != nil || !bytes.Equal(got, want[len(want)-17:]) {
		t.Fatalf("EOF bytes differ: %v", err)
	}
	from := clip[20].offset
	r.Seek(int64(from), io.SeekStart)
	if got, err := io.ReadAll(r); err != nil || !bytes.Equal(got, want[from:]) {
		t.Fatalf("fallback bytes differ: %v", err)
	}
	if c.predicted != nil {
		t.Fatal("irregular layout retained prediction")
	}
}

// The recovery record flag keeps a main header a template, locked or not; a
// solid archive is still not one.
func TestRAR5RecoveryRecordMainHeaderIsATemplate(t *testing.T) {
	for _, tc := range []struct {
		flags    uint64
		template bool
	}{{0x0b, true}, {0x1b, true}, {0x0f, false}, {0x2b, false}} {
		h := rar5TestHeader(binary.AppendUvarint(binary.AppendUvarint([]byte{1, 0}, tc.flags), 5))
		b, err := rar5Block(bytes.NewReader(h), int64(len(h)), 0)
		if err != nil || !b.main || !b.multiVolume || b.volume != 5 || b.template != tc.template {
			t.Fatalf("archive flags %#x: main=%t volume=%t template=%t, want template=%t (%v)", tc.flags, b.main, b.multiVolume, b.template, tc.template, err)
		}
	}
}

func withReservedRecoveryRecords(in []inputFile) []inputFile {
	out := make([]inputFile, len(in))
	for i, f := range in {
		f.data = withReservedRecoveryRecord(f.data, i, 3)
		out[i] = f
	}
	return out
}

// A scene release wrapped in an obfuscated set, either set made with a
// recovery record: the outer set is listed by its arithmetic, and every read
// still returns the movie's bytes.
func TestRARInRARRecoveryRecord(t *testing.T) {
	for _, where := range []string{"outer", "inner", "both"} {
		t.Run(where, func(t *testing.T) {
			movie := payload(400000)[3:]
			inner, _ := packedRARSet(t, 5, func(int) int { return 30000 }, rar4Entry{"Movie.2026.1080p-GRP.mkv", movie, len(movie), 0})
			if where != "outer" {
				inner = withReservedRecoveryRecords(inner)
			}
			in := wrapRARSet(t, 5, inner, func(int) int { return 7000 }, releaseFiles()...)
			if where != "inner" {
				in = withReservedRecoveryRecords(in)
			}
			files, _, server := setup(t, in, 0, 8, 4)
			c, err := Select(context.Background(), files, Selection{})
			if err != nil {
				t.Fatal(err)
			}
			if c.Name != "Movie.2026.1080p-GRP.mkv" || c.Size != int64(len(movie)) || c.nested == nil {
				t.Fatalf("selected %q (%d bytes, nested=%t)", c.Name, c.Size, c.nested != nil)
			}
			if bodies := server.Counters().Bodies; bodies >= int64(len(in)) {
				t.Fatalf("selection fetched %d articles from %d volumes", bodies, len(in))
			}
			listed := 0
			for _, v := range c.nested.vols {
				if v.predicted != nil {
					listed++
				}
			}
			if listed == 0 {
				t.Fatal("no inner volume was mapped by the listing's arithmetic")
			}
			rng := rand.New(rand.NewSource(11))
			r := c.Reader(context.Background(), 1)
			defer r.Close()
			for i := 0; i < 200; i++ {
				off := rng.Int63n(c.Size)
				buf := make([]byte, 1+rng.Intn(20000))
				if _, err := r.Seek(off, io.SeekStart); err != nil {
					t.Fatal(err)
				}
				n, err := io.ReadFull(r, buf)
				if err != nil && !(errors.Is(err, io.ErrUnexpectedEOF) && off+int64(n) == c.Size) {
					t.Fatalf("read at %d: %v", off, err)
				}
				if !bytes.Equal(buf[:n], movie[off:off+int64(n)]) {
					t.Fatalf("read at %d (+%d) differs", off, n)
				}
			}
			if got := readAllContent(t, c); !bytes.Equal(got, movie) {
				t.Fatal("sequential read differs")
			}
		})
	}
}

// A disc image in a wrapped set made with a recovery record keeps its clip
// and the clip's disc files.
func TestRARInRARDiscImageRecoveryRecord(t *testing.T) {
	feature, extra := payload(70000)[1000:], payload(9000)[77:]
	image := buildUDF(blurayTree(feature, extra), false, false, false)
	inner, _ := packedRARSet(t, 5, func(int) int { return 64 << 10 }, rar4Entry{"MOVIE.iso", image, len(image), 0})
	in := withReservedRecoveryRecords(wrapRARSet(t, 5, withReservedRecoveryRecords(inner), func(int) int { return 12 << 10 }, releaseFiles()...))
	files, _, _ := setup(t, in, 0, 8, 4)
	c, err := Select(context.Background(), files, Selection{})
	if err != nil {
		t.Fatal(err)
	}
	if c.Name != "MOVIE.iso/BDMV/STREAM/00800.m2ts" || c.Size != int64(len(feature)) {
		t.Fatalf("selected %q (%d bytes)", c.Name, c.Size)
	}
	if got := readAllContent(t, c); !bytes.Equal(got, feature) {
		t.Fatal("feature bytes differ")
	}
	if c.disc == nil || c.clipInfo == nil {
		t.Fatal("the image's clip lost its disc files")
	}
}

func TestRARStableMainExtra(t *testing.T) {
	locator := func(fields ...[]byte) []byte {
		var record []byte
		for _, f := range fields {
			record = append(record, f...)
		}
		return append(binary.AppendUvarint(nil, uint64(len(record))), record...)
	}
	for _, tc := range []struct {
		name  string
		extra []byte
		want  bool
	}{
		{"quick open", locator([]byte{1, 1}, rar5Reserved(4096)), true},
		{"quick open, unreserved", locator([]byte{1, 1}, binary.AppendUvarint(nil, 4096)), true},
		{"reserved recovery record", locator([]byte{1, 3}, rar5Reserved(4096), rar5Reserved(1<<30)), true},
		{"reserved recovery record alone", locator([]byte{1, 2}, rar5Reserved(1<<30)), true},
		{"unreserved recovery record", locator([]byte{1, 3}, rar5Reserved(4096), binary.AppendUvarint(nil, 1<<30)), false},
		{"recovery record filling its width", locator([]byte{1, 2}, binary.AppendUvarint(nil, 1<<62)), false},
		{"no offsets", locator([]byte{1, 0}), false},
		{"unknown locator flag", locator([]byte{1, 5}, rar5Reserved(4096)), false},
		{"other record", locator([]byte{2, 0}), false},
	} {
		if got := rarStableMainExtra(tc.extra); got != tc.want {
			t.Errorf("%s: got %t, want %t", tc.name, got, tc.want)
		}
	}
}
