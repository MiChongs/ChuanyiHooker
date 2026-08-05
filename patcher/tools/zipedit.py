"""In-place edits to a zip entry whose size does not change.

Rebuilding the split APK with a zip library would be simpler to write and wrong
to use: `extractNativeLibs=false` requires every `.so` to be stored uncompressed
*and* page-aligned, and a rebuild loses the padding that alignment depends on.
Re-aligning afterwards would work, but only by moving every entry — a lot of
churn for a change that is byte-for-byte the same size.

So the bytes are overwritten where they lie and only the CRC is repaired, in
both the local file header and the central directory. Every offset, and
therefore every alignment, survives untouched.
"""

from __future__ import annotations

import struct
import zlib

_EOCD_MAGIC = b"PK\x05\x06"
_CD_MAGIC = b"PK\x01\x02"
_LFH_MAGIC = b"PK\x03\x04"
_EOCD_MIN = 22

STORED = 0


def _find_eocd(data: bytes) -> int:
    start = max(0, len(data) - _EOCD_MIN - 0xFFFF)
    for offset in range(len(data) - _EOCD_MIN, start - 1, -1):
        if data[offset:offset + 4] == _EOCD_MAGIC:
            return offset
    raise ValueError("no end-of-central-directory record")


def _central_entries(data: bytes):
    """(name, central-header-offset) for every entry."""
    eocd = _find_eocd(data)
    count = struct.unpack_from("<H", data, eocd + 10)[0]
    offset = struct.unpack_from("<I", data, eocd + 16)[0]
    for _ in range(count):
        if data[offset:offset + 4] != _CD_MAGIC:
            raise ValueError(f"bad central directory entry at {offset:#x}")
        name_len, extra_len, comment_len = struct.unpack_from("<HHH", data, offset + 28)
        name = data[offset + 46:offset + 46 + name_len].decode("utf-8", "replace")
        yield name, offset
        offset += 46 + name_len + extra_len + comment_len


def entry_info(data: bytes, name: str):
    """(data_offset, size, compression) for `name`, or None."""
    for entry_name, central in _central_entries(data):
        if entry_name != name:
            continue
        method = struct.unpack_from("<H", data, central + 10)[0]
        compressed = struct.unpack_from("<I", data, central + 20)[0]
        local = struct.unpack_from("<I", data, central + 42)[0]
        if data[local:local + 4] != _LFH_MAGIC:
            raise ValueError(f"bad local header for {name}")
        name_len, extra_len = struct.unpack_from("<HH", data, local + 26)
        return local + 30 + name_len + extra_len, compressed, method, central, local
    return None


def patch_stored_entry(path: str, name: str, mutate) -> None:
    """Rewrites `name` in place; `mutate(bytearray) -> None` must not resize it.

    Refuses a compressed entry outright rather than silently producing a file
    the linker cannot mmap.
    """
    with open(path, "rb") as handle:
        data = bytearray(handle.read())

    found = entry_info(bytes(data), name)
    if found is None:
        raise ValueError(f"{name} is not in the archive")
    offset, size, method, central, local = found
    if method != STORED:
        raise ValueError(f"{name} is compressed; it must be stored for in-place editing")

    payload = bytearray(data[offset:offset + size])
    before = len(payload)
    mutate(payload)
    if len(payload) != before:
        raise ValueError("the mutation changed the entry size")

    data[offset:offset + size] = payload

    crc = zlib.crc32(bytes(payload)) & 0xFFFFFFFF
    struct.pack_into("<I", data, local + 14, crc)
    struct.pack_into("<I", data, central + 16, crc)

    with open(path, "wb") as handle:
        handle.write(data)


def add_entries(path: str, entries: dict[str, bytes]) -> None:
    """Appends files to a zip, rewriting the central directory.

    Used for the injected dex. Deliberately not `zipfile` in append mode: that
    is fine here, but doing the header writing explicitly keeps this file the
    single place that understands the archive layout.
    """
    import zipfile

    with zipfile.ZipFile(path, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        existing = set(archive.namelist())
        for name, content in entries.items():
            if name in existing:
                raise ValueError(f"{name} is already in the archive")
            archive.writestr(name, content)
