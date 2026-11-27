package engine

import (
	"bytes"
	"context"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
)

// Real 7-Zip multi-volume archives, too large to commit, from the folder in NUVIO_7Z_REPRO:
// movie.mkv (any tens of MB, mostly incompressible) and, from it,
//
//	7z a -mx1 -v7m lzma2_mx1.7z movie.mkv   (likewise -mx5, -mx9: 16 and 64 MiB dictionaries)
//	7z a -m0=LZMA -mx5 -v7m -ppw -mhe=on lzma_aes.7z movie.mkv
//
// It caught decoding stopping at the first article boundary and the 8 MiB dictionary cap.
func TestSevenZipRealArchivesRepro(t *testing.T) {
	dir := os.Getenv("NUVIO_7Z_REPRO")
	if dir == "" {
		t.Skip("NUVIO_7Z_REPRO not set")
	}
	want, err := os.ReadFile(filepath.Join(dir, "movie.mkv"))
	if err != nil {
		t.Fatal(err)
	}
	for _, set := range []string{"lzma2_mx1", "lzma2_mx5", "lzma2_mx9", "lzma_aes"} {
		t.Run(set, func(t *testing.T) {
			names, _ := filepath.Glob(filepath.Join(dir, set+".7z.*"))
			sort.Strings(names)
			var in []inputFile
			for _, n := range names {
				data, _ := os.ReadFile(n)
				f := inputFile{name: filepath.Base(n), data: data}
				for left := len(data); left > 0; left -= 768000 {
					f.sizes = append(f.sizes, min(left, 768000))
				}
				in = append(in, f)
			}
			password := ""
			if strings.Contains(set, "aes") {
				password = "pw"
			}
			files := setupWithPassword(t, in, password)
			c, err := Select(context.Background(), files, Selection{})
			if err != nil {
				t.Fatal(err)
			}
			if c.decoded == nil {
				t.Fatal("not decoded")
			}
			r := c.Reader(context.Background(), 4)
			defer r.Close()
			got := make([]byte, 0, len(want))
			buf := make([]byte, 32<<10)
			for {
				n, err := r.Read(buf)
				got = append(got, buf[:n]...)
				if err == io.EOF {
					break
				}
				if err != nil {
					t.Fatalf("read at %d: %v", len(got), err)
				}
			}
			if !bytes.Equal(got, want) {
				t.Fatalf("mismatch: %d of %d bytes", len(got), len(want))
			}
		})
	}
}
