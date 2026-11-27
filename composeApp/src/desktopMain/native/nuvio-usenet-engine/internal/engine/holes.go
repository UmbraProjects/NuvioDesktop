package engine

import (
	"context"
	"errors"
	"fmt"
	"sync"

	"github.com/javi11/nntppool/v4"
)

var errInvalidArticle = errors.New("invalid Usenet article or layout")
var errHoleLimit = errors.New("Usenet hole filling limit exceeded")

// All spans are bracketed by fresh yEnc metadata, never NZB wire-size estimates.
// The policy belongs to the selected playback, so RAR volumes, overlapping HTTP
// requests and seeks share a budget. Discovery and speculative readers cannot
// spend it. Payloads are generated directly into the caller's buffer.
type holeSpan struct {
	first, last int
	begin, end  int64
}

type holePolicy struct {
	mu                      sync.Mutex
	maxTotal, maxRun, count int
	spans                   map[*File][]holeSpan
}

func newHolePolicy(c Config) *holePolicy {
	if !c.HoleFilling {
		return nil
	}
	total, run := c.MaxMissingArticles, c.MaxConsecutiveMissing
	if total == 0 {
		total = 5
	}
	if run == 0 {
		run = 2
	}
	return &holePolicy{maxTotal: total, maxRun: min(run, total), spans: make(map[*File][]holeSpan)}
}

func (p *holePolicy) lookup(f *File, off int64) (holeSpan, bool) {
	if p == nil {
		return holeSpan{}, false
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	for _, span := range p.spans[f] {
		if off >= span.begin && off < span.end {
			return span, true
		}
	}
	return holeSpan{}, false
}

func (p *holePolicy) accept(f *File, span holeSpan) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	for _, old := range p.spans[f] {
		if old.first == span.first && old.last == span.last {
			if old.begin != span.begin || old.end != span.end {
				return errInvalidArticle
			}
			return nil
		}
		if span.first <= old.last && span.last >= old.first {
			return errInvalidArticle
		}
	}
	n := span.last - span.first + 1
	if n > p.maxRun || p.count+n > p.maxTotal {
		return errHoleLimit
	}
	p.spans[f] = append(p.spans[f], span)
	p.count += n
	return nil
}

// A cold seek into a large missing run can take many BODY requests to locate.
// Bound new probes per lookup. The store remembers confirmed misses, so a retry
// can continue from them. An unresolved estimate is a temporary read error.
const maxMissingLayoutProbes = 32

func (r *FileReader) probeLayout(i int, probes *int) (segment, error) {
	if err := r.ctx.Err(); err != nil {
		return segment{}, err
	}
	f := r.f
	f.mu.RLock()
	seg := f.segments[i]
	f.mu.RUnlock()
	f.store.mu.Lock()
	missing := f.store.missing[seg.id]
	f.store.mu.Unlock()
	if missing {
		return segment{}, nntppool.ErrArticleNotFound
	}
	if seg.known && !seg.hint {
		return seg, nil // Already verified from yEnc in this session.
	}
	if *probes >= maxMissingLayoutProbes {
		return segment{}, errors.New("could not locate byte range among missing articles")
	}
	*probes++
	a := f.store.acquire(seg.id, false)
	if a == nil {
		return segment{}, context.Canceled
	}
	defer f.store.releaseHeader(a)
	m, err := a.metadata(r.ctx)
	if err != nil {
		return segment{}, err
	}
	if err := f.learn(i, m); err != nil {
		return segment{}, fmt.Errorf("%w: %v", errInvalidArticle, err)
	}
	f.mu.RLock()
	defer f.mu.RUnlock()
	return f.segments[i], nil
}

// missingSpanAt resolves a missing estimate against decoded offsets. As soon
// as one neighbour puts off outside the run, its boundary suffices to resume
// the seek. Only a read inside the run is subject to the hole-filling policy.
func (r *FileReader) missingSpanAt(i int, off int64) (holeSpan, error) {
	f := r.f
	policy := f.store.holes
	fill := r.fill && policy != nil
	f.mu.RLock()
	last := len(f.segments) - 1
	seg := f.segments[i]
	f.mu.RUnlock()
	if !fill && seg.known && !seg.hint && off >= seg.begin && off < seg.end {
		return holeSpan{}, nntppool.ErrArticleNotFound
	}

	r.Close() // Release the old read-ahead window before acquiring probe articles.
	span := holeSpan{first: i, last: i, begin: -1, end: -1}
	probes := 0
	leftNext := true
	for {
		if span.first == 0 {
			span.begin = 0
		}
		if span.last == last {
			if span.first == 0 {
				return span, nntppool.ErrArticleNotFound // Every article is missing.
			}
			f.mu.RLock()
			if f.exact && !f.hintSize {
				span.end = f.size
			}
			f.mu.RUnlock()
		}
		if off < span.begin || (span.end >= 0 && off >= span.end) {
			return span, nil // Resume the seek on the far side of this boundary.
		}
		n := int64(span.last - span.first + 1)
		bracketed := span.begin >= 0 && span.end >= 0
		// Each missing article occupies at least one decoded byte. An offset
		// at a verified edge can therefore fail promptly even for a long run.
		atEdge := (span.begin >= 0 && off >= span.begin && off-span.begin < n) ||
			(span.end >= 0 && off < span.end && span.end-off <= n)
		if bracketed || atEdge {
			if !fill || span.first == 0 {
				return span, nntppool.ErrArticleNotFound
			}
			if n > int64(policy.maxRun) {
				return span, errHoleLimit
			}
			if bracketed {
				if span.end <= span.begin || span.end-span.begin > n*maxArticleBytes {
					return span, errInvalidArticle
				}
				return span, nil
			}
		}
		// Alternate sides while both are unknown so a long missing run on
		// one side cannot hide an immediately available article on the other.
		left := span.begin < 0 && (leftNext || span.end >= 0 || span.last == last)
		j := span.last + 1
		if left {
			j = span.first - 1
		}
		neighbour, err := r.probeLayout(j, &probes)
		if err != nil && !errors.Is(err, nntppool.ErrArticleNotFound) {
			return span, err
		}
		if left {
			if err == nil {
				span.begin = neighbour.end
			} else {
				span.first = j
			}
		} else if err == nil {
			span.end = neighbour.begin
		} else {
			span.last = j
		}
		leftNext = !left
	}
}

func fillHole(p []byte, off int64, span holeSpan) (int, error) {
	n := min(int64(len(p)), span.end-off)
	clear(p[:n])
	return int(n), nil
}
