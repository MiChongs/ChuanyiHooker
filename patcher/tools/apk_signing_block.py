"""Reads the signing certificate out of an APK Signature Scheme v2/v3 block.

The patched APK is re-signed with a different key, and the app reports
`SHA-256(apkContentsSigners[0].toByteArray())` to its backend. Handing that call
the *original* certificate is what keeps a repack invisible on the wire, and for
that the original DER has to come out of the untouched input APK.

It cannot come from `META-INF/*.RSA`: this APK is v2/v3 only, with no JAR
signature at all, so the certificate exists nowhere except the APK Signing Block.
`apksigner` prints its digests but never the certificate itself, hence this.

Layout, from the platform's `ApkSigningBlockUtils`:

    …zip entries…
    ┌ size-of-block (u64, excludes this field)
    │ ┌ pair: length (u64) │ id (u32) │ value (length-4 bytes)
    │ └ …more pairs…
    │ size-of-block (u64, repeated)
    └ "APK Sig Block 42" (16 bytes)
    …central directory…

and inside a v2/v3 pair, every list is a u32 length followed by that many bytes:

    signers → signer → signed-data → digests, certificates, …

Only the first certificate of the first signer is wanted; that is the one
`getApkContentsSigners()[0]` returns.
"""

from __future__ import annotations

import struct
import sys

APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
BLOCK_ID_V2 = 0x7109871A
BLOCK_ID_V3 = 0xF05368C0
# v3.1 carries rotated keys; the original signer is still the v3/v2 one.
BLOCK_ID_V31 = 0x1B93AD61

EOCD_MAGIC = b"PK\x05\x06"
EOCD_MIN = 22


def _find_eocd(data: bytes) -> int:
    """Offset of the end-of-central-directory record."""
    # The comment field is at most 64 KiB, so the record starts within that of
    # the end. Search backwards so a comment that happens to contain the magic
    # does not win over the real record.
    start = max(0, len(data) - EOCD_MIN - 0xFFFF)
    for offset in range(len(data) - EOCD_MIN, start - 1, -1):
        if data[offset:offset + 4] == EOCD_MAGIC:
            return offset
    raise ValueError("no end-of-central-directory record; not a zip")


def _central_directory_offset(data: bytes) -> int:
    eocd = _find_eocd(data)
    (offset,) = struct.unpack_from("<I", data, eocd + 16)
    if offset == 0xFFFFFFFF:
        raise ValueError("zip64 central directory is not supported")
    return offset


def _signing_block(data: bytes) -> bytes:
    """The pairs region of the APK Signing Block."""
    cd = _central_directory_offset(data)
    if cd < 24:
        raise ValueError("no room for a signing block before the central directory")
    if data[cd - 16:cd] != APK_SIG_BLOCK_MAGIC:
        raise ValueError("no APK Signing Block; the APK is unsigned or v1 only")

    (size_at_end,) = struct.unpack_from("<Q", data, cd - 24)
    block_start = cd - 8 - size_at_end
    if block_start < 0:
        raise ValueError("signing block size runs past the start of the file")
    (size_at_start,) = struct.unpack_from("<Q", data, block_start)
    if size_at_start != size_at_end:
        raise ValueError("signing block size fields disagree")
    # Between the leading size field and the trailing size+magic.
    return data[block_start + 8:cd - 24]


def _pairs(block: bytes):
    offset = 0
    while offset + 12 <= len(block):
        (length,) = struct.unpack_from("<Q", block, offset)
        if length < 4 or offset + 8 + length > len(block):
            break
        (pair_id,) = struct.unpack_from("<I", block, offset + 8)
        yield pair_id, block[offset + 12:offset + 8 + length]
        offset += 8 + length


def _take(buffer: bytes, offset: int) -> tuple[bytes, int]:
    """One u32-length-prefixed chunk, and where the next one starts."""
    if offset + 4 > len(buffer):
        raise ValueError("truncated length-prefixed field")
    (length,) = struct.unpack_from("<I", buffer, offset)
    end = offset + 4 + length
    if end > len(buffer):
        raise ValueError("length-prefixed field runs past the buffer")
    return buffer[offset + 4:end], end


def first_certificate(apk_path: str) -> bytes:
    """DER of the certificate `getApkContentsSigners()[0]` reports."""
    with open(apk_path, "rb") as handle:
        data = handle.read()

    found = dict(_pairs(_signing_block(data)))
    # v3 is preferred: on a rotated key it names the signer actually in force,
    # and it is what the platform hands back on API 28+.
    for block_id in (BLOCK_ID_V3, BLOCK_ID_V31, BLOCK_ID_V2):
        value = found.get(block_id)
        if value is None:
            continue
        signers, _ = _take(value, 0)
        signer, _ = _take(signers, 0)
        signed_data, _ = _take(signer, 0)
        _digests, offset = _take(signed_data, 0)
        certificates, _ = _take(signed_data, offset)
        certificate, _ = _take(certificates, 0)
        if certificate:
            return certificate
    raise ValueError("signing block has no v2/v3 certificate")


if __name__ == "__main__":
    import base64
    import hashlib

    if len(sys.argv) < 2:
        raise SystemExit("usage: apk_signing_block.py <apk> [--base64|--sha256]")
    der = first_certificate(sys.argv[1])
    mode = sys.argv[2] if len(sys.argv) > 2 else "--sha256"
    if mode == "--base64":
        print(base64.b64encode(der).decode("ascii"))
    else:
        print(hashlib.sha256(der).hexdigest())
