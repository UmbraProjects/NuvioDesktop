package engine

// Compressed 7z entries (LZMA, LZMA2, BCJ/BCJ2 filters, Deflate, BZip2 ...,
// optionally AES-encrypted) cannot be mapped onto NZB volumes the way stored
// entries are: their bytes only exist after decoding the folder from its first
// packed byte. They are played through one shared decoder per selected entry,
// read forward like a tape:
//
//   - reads at or after the decoder's position decode forward to them, keeping
//     the most recent decodedBackBuffer bytes, so a player's small backward
//     re-reads (and readers taking turns) are served from memory;
//   - a read before that window starts the decoder again from the folder's
//     start: the release is downloaded and decoded again up to that point;
//   - a read that would decode more than decodedTailSkip bytes only to reach
//     the last decodedTailSkip bytes of the file is refused at once (a
//     temporary read error). That is a player reading its index at the end
//     (MKV Cues, Tags, an MP4 moov): mpv carries on without it. Everything
//     else is honoured, so a forward seek costs downloading and decoding
//     everything between the decoder and the target.
//
// Seeking therefore costs what a download would: forward seeks run at download
// speed, backward seeks past the buffer start over. No decoded bytes are kept
// on disk; memory is the buffer plus the decoder's own dictionary.

import (
	"context"
	"errors"
	"fmt"
	"io"
	"sync/atomic"

	"github.com/javi11/sevenzip"
)

// Variables only so tests can shrink them to their small fixtures.
var (
	decodedBackBuffer int64 = 16 << 20
	decodedTailSkip   int64 = 64 << 20
)

const (
	decodedChunk = 1 << 20
	// Read-ahead (articles) for the packed volumes while decoding.
	decodedReadAhead = 8
)

var errDecodeAhead = errors.New("compressed 7z: that part is not decoded yet")

// errCompressedTooLarge refuses a compressed 7z over the app's size limit (Config.MaxCompressedBytes).
var errCompressedTooLarge = errors.New("compressed 7z release, too large to seek in")

type sevenZipDecoder struct {
	ctx       context.Context // The session's: decoding outlives one HTTP request.
	vols      []sevenZipVolume
	password  string
	fileIndex int // In sevenzip.Reader.File.
	size      int64

	gate chan struct{} // Serialises readers; a waiting request can still give up.

	archive *sevenZipArchive
	rc      io.Reader
	pos     int64
	ring    []byte
	filled  int64 // Bytes in ring since the last start (at most len(ring)).
	starts  atomic.Int32
	// For the app's progress display: how far decoding got, and the offset a read is waiting for
	// (0 when none waits). Readable without the gate.
	decodedTo  atomic.Int64
	waitingFor atomic.Int64
}

func newSevenZipDecoder(ctx context.Context, z *sevenZipSet, e sevenZipEntry) *sevenZipDecoder {
	return &sevenZipDecoder{
		ctx:       ctx,
		vols:      append([]sevenZipVolume(nil), z.vols...),
		password:  z.password,
		fileIndex: e.fileIndex,
		size:      int64(e.info.Size),
		gate:      make(chan struct{}, 1),
	}
}

func (d *sevenZipDecoder) lock(ctx context.Context) error {
	select {
	case d.gate <- struct{}{}:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	case <-d.ctx.Done():
		return d.ctx.Err()
	}
}

func (d *sevenZipDecoder) unlock() { <-d.gate }

// start opens the entry again from its folder's first byte. The previous
// archive readers are closed; the previous 7z reader and its decoders are
// dropped rather than closed, since closing parks them in the library's pool.
func (d *sevenZipDecoder) start() error {
	if d.archive != nil {
		d.archive.Close()
	}
	d.archive, d.rc, d.pos, d.filled = nil, nil, 0, 0
	d.decodedTo.Store(0)
	archive := newSevenZipArchive(d.ctx, d.vols)
	archive.ahead = decodedReadAhead
	r, err := sevenzip.NewReaderWithContext(d.ctx, archive, archive.size, d.password)
	if err != nil {
		archive.Close()
		return d.translate(err)
	}
	if d.fileIndex < 0 || d.fileIndex >= len(r.File) {
		archive.Close()
		return errors.New("invalid 7z archive: entry not found")
	}
	rc, err := r.File[d.fileIndex].Open()
	if err != nil {
		archive.Close()
		return d.translate(err)
	}
	d.archive, d.rc = archive, rc
	d.starts.Add(1)
	if d.ring == nil {
		d.ring = make([]byte, decodedBackBuffer)
	}
	return nil
}

// translate keeps article and cancellation errors as they are, so session
// failure kinds still apply; anything else from the decoder is bad data.
func (d *sevenZipDecoder) translate(err error) error {
	if err == nil || err == io.EOF {
		return err
	}
	if d.ctx.Err() != nil {
		return d.ctx.Err()
	}
	if d.archive != nil && d.archive.layout != nil {
		return d.archive.layout
	}
	for e := err; e != nil; e = errors.Unwrap(e) {
		if errors.Is(e, errInvalidArticle) || errors.Is(e, context.Canceled) || errors.Is(e, context.DeadlineExceeded) ||
			permanentStreamFailure(e) != "" || errors.Is(e, err7zVolumeOrder) {
			return e
		}
	}
	var readErr *sevenzip.ReadError
	if errors.As(err, &readErr) && readErr.Encrypted {
		if d.password == "" {
			return Err7zPasswordMissing
		}
		return Err7zWrongPassword
	}
	return fmt.Errorf("%w: 7z decode: %v", errInvalidArticle, err)
}

// remember appends decoded bytes to the back buffer.
func (d *sevenZipDecoder) remember(p []byte) {
	n := int64(len(d.ring))
	if int64(len(p)) > n {
		p = p[int64(len(p))-n:]
	}
	at := (d.pos - int64(len(p))) % n
	k := copy(d.ring[at:], p)
	copy(d.ring, p[k:])
	d.filled = min(n, d.filled+int64(len(p)))
}

// decode reads up to len(p) decoded bytes at the decoder's position.
func (d *sevenZipDecoder) decode(p []byte) (int, error) {
	p = p[:min(int64(len(p)), d.size-d.pos)]
	n, err := io.ReadFull(d.rc, p)
	d.pos += int64(n)
	d.decodedTo.Store(d.pos)
	d.remember(p[:n])
	if errors.Is(err, io.ErrUnexpectedEOF) || (err == io.EOF && len(p) > 0) {
		err = fmt.Errorf("%w: 7z entry ended early", errInvalidArticle)
	}
	return n, d.translate(err)
}

func (d *sevenZipDecoder) readAt(ctx context.Context, p []byte, off int64) (int, error) {
	if off >= d.size {
		return 0, io.EOF
	}
	if len(p) == 0 {
		return 0, nil
	}
	if err := d.lock(ctx); err != nil {
		return 0, err
	}
	defer d.unlock()
	if d.rc != nil && off < d.pos && off >= d.pos-d.filled {
		n := min(int64(len(p)), d.pos-off)
		ringSize := int64(len(d.ring))
		at := off % ringSize
		k := copy(p[:n], d.ring[at:])
		copy(p[k:n], d.ring)
		return int(n), nil
	}
	if d.rc != nil && off > d.pos && off >= d.size-decodedTailSkip && off-d.pos > decodedTailSkip {
		return 0, errDecodeAhead
	}
	if d.rc == nil || off < d.pos {
		if d.rc == nil && off >= d.size-decodedTailSkip && off > decodedTailSkip {
			return 0, errDecodeAhead
		}
		if err := d.start(); err != nil {
			return 0, err
		}
	}
	// Skip forward under the session's context: the progress is kept for
	// the next request even if this one gives up.
	scratch := make([]byte, min(decodedChunk, max(int64(len(p)), 64<<10)))
	if off > d.pos {
		d.waitingFor.Store(off)
		defer d.waitingFor.Store(0)
	}
	for d.pos < off {
		if _, err := d.decode(scratch[:min(int64(len(scratch)), off-d.pos)]); err != nil {
			return 0, err
		}
	}
	return d.decode(p)
}

// probe decodes the entry's first bytes when it is selected, so an
// unsupported method, a wrong password or damaged data fails the open.
func (d *sevenZipDecoder) probe(ctx context.Context) error {
	head := make([]byte, min(d.size, 16<<10))
	_, err := d.readAt(ctx, head, 0)
	return err
}

func (d *sevenZipDecoder) close() {
	select {
	case d.gate <- struct{}{}:
	case <-d.ctx.Done():
		return
	}
	defer d.unlock()
	if d.archive != nil {
		d.archive.Close()
	}
	d.archive, d.rc, d.ring, d.filled = nil, nil, nil, 0
}

// decodedStarts reports how often the decoder had to start from the folder's
// beginning (once for playback, once more per seek before the back buffer).
func (c *Content) decodedStarts() int {
	if c.decoded == nil {
		return 0
	}
	return int(c.decoded.starts.Load())
}

// isDecoded reports whether this content, or the RAR set it reads through, is a compressed 7z
// entry played by decoding: the app marks such releases as slow to seek.
func (c *Content) isDecoded() bool {
	if c == nil {
		return false
	}
	if c.decoded != nil {
		return true
	}
	if n := c.nested; n != nil {
		for _, v := range n.vols {
			if v.decoded != nil {
				return true
			}
		}
	}
	return false
}
