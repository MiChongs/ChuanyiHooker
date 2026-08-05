"""Finding and rewriting string literals inside a Dart AOT snapshot.

`libapp.so` carries `_kDartIsolateSnapshotData`, which is a *serialized cluster
stream* rather than a heap image: a string payload is preceded by a
`(len<<1)|0x80` varint length marker instead of an object header. Two things
follow, and the whole static patch rests on them:

  * patching the file works — the isolate deserializes whatever bytes are there
    at startup, so unlike a running process there is no second copy to chase;
  * an equal-length overwrite never touches the length marker, so no offset, no
    object-pool walk, and `--obfuscate` is irrelevant.

Boundaries are exact here, unlike in the heap. The byte after a payload is the
next cluster's length marker and always has its top bit set, so a run of
printable ASCII ends precisely where the string does.
"""

from __future__ import annotations

import re

# Printable ASCII plus the whitespace a PEM body carries.
_TEXT = re.compile(rb"[\x20-\x7e\r\n\t]")

PEM_BEGIN = b"-----BEGIN PUBLIC KEY-----"
PEM_END = b"-----END PUBLIC KEY-----"

# Words that mark a URL as the purchase-verification endpoint, and their weight.
# Scoring rather than matching: the app talks to several endpoints on the same
# host, so "belongs to the backend" does not narrow it down. Only one is about a
# purchase *and* about verifying it, and a rename inside that theme still wins.
_URL_SIGNALS = (
    (b"verify", 3),
    (b"validate", 3),
    (b"purchase", 3),
    (b"receipt", 2),
    (b"subscription", 1),
    (b"google", 1),
    (b"billing", 1),
    (b"iap", 1),
)
_MIN_SCORE = 4


def _run_at(data: bytes, start: int, limit: int = 4096) -> bytes:
    """The printable run beginning at `start`."""
    end = start
    stop = min(len(data), start + limit)
    while end < stop and _TEXT.match(data[end:end + 1]):
        end += 1
    return data[start:end]


def find_urls(data: bytes) -> list[tuple[int, bytes]]:
    """Every `https://` literal, as (offset, value)."""
    out = []
    for match in re.finditer(rb"https://", data):
        run = _run_at(data, match.start(), limit=512)
        # A URL cannot contain a space or a quote; anything past one belongs to
        # a neighbour that happened to start with text.
        run = re.split(rb"[^A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]", run, 1)[0]
        if len(run) < 12 or b"/" not in run.split(b"://", 1)[1]:
            continue
        out.append((match.start(), run))
    return out


def _score(url: bytes) -> int:
    path = url.lower().split(b"://", 1)[1]
    slash = path.find(b"/")
    if slash < 0:
        return 0
    path = path[slash:]
    return sum(weight for word, weight in _URL_SIGNALS if word in path)


def find_verify_url(data: bytes, must_contain: bytes | None = None):
    """(offset, url) of the purchase-verification endpoint, or None."""
    candidates = find_urls(data)
    if must_contain:
        pinned = [c for c in candidates if must_contain in c[1]]
        if pinned:
            return min(pinned, key=lambda c: len(c[1]))
        return None

    scored = [(offset, url, _score(url)) for offset, url in candidates]
    scored = [entry for entry in scored if entry[2] >= _MIN_SCORE]
    if not scored:
        return None
    best = max(entry[2] for entry in scored)
    # Shortest among equals: the run can only ever overshoot, so the shortest
    # member of a family is the one whose length is certainly right.
    winner = min((e for e in scored if e[2] == best), key=lambda e: len(e[1]))
    return winner[0], winner[1]


def find_public_key(data: bytes):
    """(offset, pem) of the response-signing public key, or None."""
    hits = []
    for match in re.finditer(re.escape(PEM_BEGIN), data):
        run = _run_at(data, match.start())
        end = run.find(PEM_END)
        if end < 0:
            continue
        hits.append((match.start(), run[:end + len(PEM_END)]))
    if not hits:
        return None
    return hits[0]


def replace_at(data: bytearray, offset: int, old: bytes, new: bytes) -> None:
    """Equal-length overwrite, refusing anything that would move a boundary."""
    if len(old) != len(new):
        raise ValueError(f"replacement is {len(new)}B against {len(old)}B; lengths must match")
    if bytes(data[offset:offset + len(old)]) != old:
        raise ValueError(f"expected value not present at {offset:#x}")
    data[offset:offset + len(new)] = new
