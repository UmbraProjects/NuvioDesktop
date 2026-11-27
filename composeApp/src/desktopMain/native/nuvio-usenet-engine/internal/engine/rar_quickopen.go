package engine

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"hash/crc32"
	"io"
	"sync"
)

// RAR5 Quick Open: rar stores copies of a volume's file headers in a service
// block near the volume's end, which the main header's locator record points
// to. A walk that needs many headers in one volume, such as a Blu-ray's menu
// and extra clips before its feature, otherwise reads one article per header,
// one after another. After a few headers from a volume, its Quick Open block
// is read once and serves the rest.
//
// The copies only stand in for header reads. rar leaves some files out (small
// ones), so a header without a copy is read from the volume as before. Each
// copy must pass its record CRC and its own header CRC and parse as a file
// header at its position. No copy is served until one equals the header it
// copies, read from the volume, and any copy that differs from a header read
// later stops their use. The selected entry's header is read and compared
// before playback (verifyQuickOpenCopies). Encrypted headers and RAR4 have no
// usable copies.

// rarQuickOpenAfter is how many file headers are read from a volume before
// its Quick Open block is: a volume that holds one or two of the headers a
// walk needs (a long file's continuation) never pays for it.
const rarQuickOpenAfter = 2

// rarQuickOpenMax bounds the Quick Open data read for one volume: about
// twenty thousand header copies. A disc's volume holds a few hundred.
const rarQuickOpenMax = 4 << 20

// rarQuickOpenChecks bounds the headers kept to check a volume's copies.
const rarQuickOpenChecks = 8

const (
	rarQuickOpenPending = iota
	rarQuickOpenLoaded
	rarQuickOpenUnusable
)

type rarQuickOpen struct {
	mu      sync.Mutex
	load    sync.Mutex // Serializes the read of the block itself.
	main    int64      // Main header position; 0 until it is read.
	pos     int64      // Quick Open service header; 0 when none is named.
	state   int
	trusted bool             // A copy equalled the header read from the volume.
	stale   bool             // A copy differed from its header after copies were served.
	read    []rarHeaderSpan  // File headers read from the volume, to check the copies.
	headers map[int64][]byte // Header copies by position, once loaded.
	served  map[int64]int64  // Data offset of each copy served, to its header's position.
}

type rarHeaderSpan struct{ pos, size int64 }

// saw records a header read from the volume itself. Once the copies are
// loaded, it compares the header with its copy: from the store, at no cost.
func (q *rarQuickOpen) saw(r io.ReaderAt, pos int64, b rarBlock, volumeSize int64) {
	q.mu.Lock()
	switch {
	case q.state == rarQuickOpenPending && b.main:
		if q.main == 0 && b.quickOpen > 0 && pos+b.quickOpen > b.next && pos+b.quickOpen < volumeSize {
			q.main, q.pos = pos, pos+b.quickOpen
		}
	case q.state == rarQuickOpenPending && b.file && q.pos != 0 && pos > q.main && pos < q.pos && len(q.read) < rarQuickOpenChecks:
		for _, s := range q.read {
			if s.pos == pos {
				q.mu.Unlock()
				return
			}
		}
		q.read = append(q.read, rarHeaderSpan{pos, b.header})
	case q.state == rarQuickOpenLoaded && b.file:
		copied := q.headers[pos]
		q.mu.Unlock()
		if copied == nil {
			return
		}
		real := make([]byte, b.header)
		err := readFullAt(r, real, pos)
		q.mu.Lock()
		if q.state == rarQuickOpenLoaded && err == nil {
			if bytes.Equal(real, copied) {
				q.trusted = true
			} else {
				// Copies already served may have misled the walk: it runs again.
				q.state, q.stale = rarQuickOpenUnusable, len(q.served) > 0
			}
		}
	}
	q.mu.Unlock()
}

// cached returns the copy of the file header at pos, reading the volume's
// Quick Open block first when enough of its headers were read one by one. It
// returns nil whenever the header must be read from the volume.
func (q *rarQuickOpen) cached(ctx context.Context, f *File, pos int64) []byte {
	q.mu.Lock()
	state, main, qo, reads := q.state, q.main, q.pos, len(q.read)
	if state == rarQuickOpenLoaded {
		var h []byte
		if q.trusted {
			h = q.headers[pos]
		}
		q.mu.Unlock()
		return h
	}
	q.mu.Unlock()
	if state != rarQuickOpenPending || qo == 0 || pos <= main || pos >= qo || reads < rarQuickOpenAfter {
		return nil
	}
	q.load.Lock()
	defer q.load.Unlock()
	q.mu.Lock()
	if q.state == rarQuickOpenPending {
		spans := append([]rarHeaderSpan(nil), q.read...)
		q.mu.Unlock()
		headers, matched, err := readRARQuickOpen(ctx, f, main, qo, spans)
		q.mu.Lock()
		switch {
		case err == nil:
			q.state, q.headers, q.trusted = rarQuickOpenLoaded, headers, matched
		case ctx.Err() == nil:
			q.state = rarQuickOpenUnusable // Never retried: the volume reads its headers.
		}
	}
	var h []byte
	if q.state == rarQuickOpenLoaded && q.trusted {
		h = q.headers[pos]
	}
	q.mu.Unlock()
	return h
}

var errRARQuickOpen = errors.New("unusable RAR Quick Open data")

// readRARQuickOpen reads a volume's Quick Open block at qo and returns its
// file header copies by position. The headers already read from the volume
// must equal their copies; matched reports whether any had one.
func readRARQuickOpen(ctx context.Context, f *File, main, qo int64, read []rarHeaderSpan) (map[int64][]byte, bool, error) {
	r := f.headerReader(ctx)
	defer r.Close()
	size := f.Size()
	head, err := rarRawHeader(r, qo, size)
	if err != nil {
		return nil, false, err
	}
	data, dataCRC, hasCRC, err := rarQuickOpenService(head)
	if err != nil {
		return nil, false, err
	}
	start := qo + int64(len(head))
	if data <= 0 || data > rarQuickOpenMax || data > size-start {
		return nil, false, errRARQuickOpen
	}
	body := make([]byte, data)
	if err := readFullAt(r, body, start); err != nil {
		return nil, false, err
	}
	if hasCRC && crc32.ChecksumIEEE(body) != dataCRC {
		return nil, false, errRARQuickOpen
	}
	headers, err := parseRARQuickOpen(body, main, qo)
	if err != nil {
		return nil, false, err
	}
	// The headers read one by one are already in the store: comparing them
	// costs no article and proves the copies describe this volume.
	matched := false
	for _, s := range read {
		copied := headers[s.pos]
		if copied == nil {
			continue
		}
		real := make([]byte, s.size)
		if err := readFullAt(r, real, s.pos); err != nil {
			return nil, false, err
		}
		if !bytes.Equal(real, copied) {
			return nil, false, errRARQuickOpen
		}
		matched = true
	}
	return headers, matched, nil
}

// rarRawHeader reads the RAR5 header at pos as stored: CRC, size and body.
func rarRawHeader(r io.ReaderAt, pos, volumeSize int64) ([]byte, error) {
	var lead [14]byte
	if pos < 0 || volumeSize-pos < 5 {
		return nil, errRARQuickOpen
	}
	if err := readFullAt(r, lead[:min(len(lead), int(min(volumeSize-pos, int64(len(lead)))))], pos); err != nil {
		return nil, err
	}
	size, k := binary.Uvarint(lead[4:])
	if k <= 0 || size == 0 || size > 1<<20 || int64(4+k)+int64(size) > volumeSize-pos {
		return nil, errRARQuickOpen
	}
	h := make([]byte, 4+k+int(size))
	if err := readFullAt(r, h, pos); err != nil {
		return nil, err
	}
	if crc32.ChecksumIEEE(h[4:]) != binary.LittleEndian.Uint32(h) {
		return nil, errRARQuickOpen
	}
	return h, nil
}

// rarQuickOpenService checks that head is the stored "QO" service header and
// returns its data size and data CRC.
func rarQuickOpenService(head []byte) (int64, uint32, bool, error) {
	_, k := binary.Uvarint(head[4:])
	x := vintReader{b: head[4+k:]}
	kind := x.num()
	flags := x.num()
	extra := uint64(0)
	if flags&1 != 0 {
		extra = x.num()
	}
	if kind != 3 || flags&2 == 0 {
		return 0, 0, false, errRARQuickOpen
	}
	data := x.num()
	ff := x.num()
	x.num() // Unpacked size.
	x.num() // Attributes.
	if ff&2 != 0 {
		x.skip(4)
	}
	var crc uint32
	hasCRC := ff&4 != 0
	if hasCRC && x.err == nil && len(x.b)-x.pos >= 4 {
		crc = binary.LittleEndian.Uint32(x.b[x.pos:])
	}
	if hasCRC {
		x.skip(4)
	}
	comp := x.num()
	x.num() // Host OS.
	n := x.num()
	if x.err != nil || comp&0x380 != 0 || ff&8 != 0 || n != 2 || n > uint64(len(x.b)-x.pos) ||
		string(x.b[x.pos:x.pos+2]) != "QO" || extra > uint64(len(x.b)-x.pos) || data > 1<<62 {
		return 0, 0, false, errRARQuickOpen
	}
	return int64(data), crc, hasCRC, nil
}

// parseRARQuickOpen lists the cached headers in a Quick Open block's data:
// records of CRC, size, flags, offset back from the block, length and the
// header itself. Only headers between the main header and the block count.
func parseRARQuickOpen(data []byte, main, qo int64) (map[int64][]byte, error) {
	headers := map[int64][]byte{}
	for pos := 0; pos < len(data); {
		if len(data)-pos < 5 {
			return nil, errRARQuickOpen
		}
		crc := binary.LittleEndian.Uint32(data[pos:])
		size, k := binary.Uvarint(data[pos+4:])
		if k <= 0 || size == 0 || size > uint64(len(data)-pos-4-k) {
			return nil, errRARQuickOpen
		}
		record := data[pos+4 : pos+4+k+int(size)]
		if crc32.ChecksumIEEE(record) != crc {
			return nil, errRARQuickOpen
		}
		x := vintReader{b: record[k:]}
		x.num() // Flags.
		back := x.num()
		length := x.num()
		if x.err != nil || length > uint64(len(x.b)-x.pos) || back == 0 || back > uint64(qo-main) {
			return nil, errRARQuickOpen
		}
		at := qo - int64(back)
		if at <= main || at+int64(length) > qo {
			return nil, errRARQuickOpen
		}
		headers[at] = append([]byte(nil), x.b[x.pos:x.pos+int(length)]...)
		pos += 4 + k + int(size)
	}
	if len(headers) == 0 {
		return nil, errRARQuickOpen
	}
	return headers, nil
}

// rarHeaderCopy serves one cached header at its position in the volume.
type rarHeaderCopy struct {
	pos int64
	b   []byte
}

func (c rarHeaderCopy) ReadAt(p []byte, off int64) (int, error) {
	if off < c.pos || off-c.pos > int64(len(c.b)) {
		return 0, io.ErrUnexpectedEOF
	}
	n := copy(p, c.b[off-c.pos:])
	if n < len(p) {
		return n, io.ErrUnexpectedEOF
	}
	return n, nil
}

// use records that the copy at pos stood in for a header whose data starts at data.
func (q *rarQuickOpen) use(pos, data int64) {
	q.mu.Lock()
	defer q.mu.Unlock()
	if q.served == nil {
		q.served = map[int64]int64{}
	}
	q.served[data] = pos
}

// disable makes the volume read every header itself from now on.
func (q *rarQuickOpen) disable() {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.state, q.headers, q.served, q.trusted, q.stale = rarQuickOpenUnusable, nil, nil, false, false
}

// misled reports whether copies served to a walk were later found to differ.
func (q *rarQuickOpen) misled() bool {
	q.mu.Lock()
	defer q.mu.Unlock()
	return q.stale
}

var errRARQuickOpenStale = errors.New("RAR Quick Open copy differs from its header")

// verify reads the real header of the entry whose data starts at data, when
// a copy stood in for it, and checks that the copy is that header.
func (q *rarQuickOpen) verify(ctx context.Context, f *File, data int64) error {
	q.mu.Lock()
	pos, ok := q.served[data]
	copied := q.headers[pos]
	q.mu.Unlock()
	if !ok {
		return nil
	}
	r := f.headerReader(ctx)
	defer r.Close()
	real, err := rarRawHeader(r, pos, f.Size())
	if err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return errRARQuickOpenStale
	}
	if !bytes.Equal(real, copied) {
		return errRARQuickOpenStale
	}
	return nil
}

// verifyQuickOpenCopies checks the headers behind a selection's first part and
// its CLPI: the entries playback will read. Other copies served a walk only.
func verifyQuickOpenCopies(ctx context.Context, c *Content) error {
	var check []extent
	for _, x := range []*Content{c, c.clipInfo} {
		if x == nil {
			continue
		}
		x.mu.RLock()
		if len(x.parts) > 0 && x.parts[0].file != nil {
			check = append(check, x.parts[0])
		}
		x.mu.RUnlock()
	}
	errs := make([]error, len(check))
	var wg sync.WaitGroup
	for i, e := range check {
		wg.Add(1)
		go func() {
			defer wg.Done()
			errs[i] = e.file.quickOpen.verify(ctx, e.file, e.offset)
		}()
	}
	wg.Wait()
	return errors.Join(errs...)
}

// rarQuickOpenOffset returns the Quick Open offset a RAR5 main header's
// locator record gives, or 0. Malformed records name none.
func rarQuickOpenOffset(extra []byte) int64 {
	x := vintReader{b: extra}
	for x.pos < len(x.b) && x.err == nil {
		length := x.num()
		start := x.pos
		if x.err != nil || length == 0 || length > uint64(len(x.b)-start) {
			return 0
		}
		if x.num() == 1 {
			rec := vintReader{b: x.b[x.pos : start+int(length)]}
			if rec.num()&1 == 0 {
				return 0
			}
			off := rec.num()
			if rec.err != nil || off == 0 || off > 1<<62 {
				return 0
			}
			return int64(off)
		}
		x.pos = start + int(length)
	}
	return 0
}
