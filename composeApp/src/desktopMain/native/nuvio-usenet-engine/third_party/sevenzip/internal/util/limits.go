package util

import "errors"

// Shared so header codecs and the directory parser report the same category.
var ErrResourceLimit = errors.New("sevenzip: archive metadata exceeds resource limit")

// MaxDictionary is the largest LZMA/LZMA2 dictionary any decoder allocates: 7-Zip's Ultra
// preset (-mx9) uses 64 MiB. Header parsing is held to HeaderDictionary by the caller.
const MaxDictionary = 64 << 20

// HeaderDictionary bounds the decoders that read archive metadata from untrusted input.
const HeaderDictionary = 8 << 20
