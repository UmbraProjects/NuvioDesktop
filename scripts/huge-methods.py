#!/usr/bin/env python3
"""
fork:huge-methods - lists the JVM methods too big for HotSpot to compile.

HotSpot never JIT-compiles a method whose bytecode is over 8000 bytes (-XX:+DontCompileHugeMethods, on by default), so such a
method runs in the interpreter for the whole session. A composable that is thousands of lines long can cross that line, and it
runs again on every recomposition. This reads the compiled .class files directly (no JDK needed) and prints every method over
the limit, biggest first, as a markdown table.

    python scripts/huge-methods.py composeApp/build/classes/kotlin/desktop/main [limit] [rows]
"""
import os
import struct
import sys


def methods_of(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"\xca\xfe\xba\xbe":
        return
    pos = 8
    (count,) = struct.unpack_from(">H", data, pos)
    pos += 2
    utf8 = {}
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            (n,) = struct.unpack_from(">H", data, pos)
            utf8[i] = data[pos + 2 : pos + 2 + n].decode("utf-8", "replace")
            pos += 2 + n
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            pos += 2
        elif tag == 15:
            pos += 3
        else:
            return
        i += 1
    pos += 6  # access, this, super
    (n_if,) = struct.unpack_from(">H", data, pos)
    pos += 2 + 2 * n_if

    def attributes(p):
        (n,) = struct.unpack_from(">H", data, p)
        p += 2
        found = []
        for _ in range(n):
            name, length = struct.unpack_from(">HI", data, p)
            found.append((utf8.get(name, ""), p + 6, length))
            p += 6 + length
        return found, p

    (n_fields,) = struct.unpack_from(">H", data, pos)
    pos += 2
    for _ in range(n_fields):
        _, pos = attributes(pos + 6)
    (n_methods,) = struct.unpack_from(">H", data, pos)
    pos += 2
    for _ in range(n_methods):
        _, name, desc = struct.unpack_from(">HHH", data, pos)
        attrs, pos = attributes(pos + 6)
        for attr, start, _length in attrs:
            if attr == "Code":
                (code_length,) = struct.unpack_from(">I", data, start + 4)
                yield utf8.get(name, "?"), code_length


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 8000
    rows = int(sys.argv[3]) if len(sys.argv) > 3 else 60
    found = []
    total = 0
    for dirpath, _, files in os.walk(root):
        for name in files:
            if not name.endswith(".class"):
                continue
            path = os.path.join(dirpath, name)
            cls = os.path.relpath(path, root)[:-6].replace(os.sep, ".")
            for method, size in methods_of(path):
                total += 1
                if size > limit:
                    found.append((size, cls, method))
    found.sort(reverse=True)
    print(f"### Methods too big for the JIT (over {limit} bytes of bytecode): {len(found)} of {total}")
    if not found:
        print("\nNone.")
        return
    print("\n| bytecode | method |\n|---:|---|")
    for size, cls, method in found[:rows]:
        print(f"| {size:,} | `{cls}.{method}` |")


if __name__ == "__main__":
    main()
