#!/usr/bin/env python3
"""Patches the original Hills APK set so Pro unlocks without Xposed.

    python patcher/patch_hills.py --from-device
    python patcher/patch_hills.py --input <dir of base.apk + split_*.apk> --install

What this produces is the same unlock the `:hookers:hills` module performs, made
permanent inside the app: no framework, no root, nothing to keep enabled. The
mechanics are identical because the app has not changed — what changes is where
the code lives and when the edits happen.

Two halves.

**The Dart half is edited statically.** `libapp.so` carries the isolate snapshot,
which is a serialized cluster stream deserialized at startup, so the bytes in the
file *are* what the isolate ends up holding. The module has to race the isolate
and rewrite the heap copy; here the same two strings — the verification endpoint
and the public key its answers are checked against — are simply different before
the app ever runs. The race disappears.

**The Java half is edited as smali**, seven sites, each a two-instruction insert
or a whole-method replacement. All the real work sits behind `Rt` in the injected
dex, in ordinary Java.

What is *not* free: re-signing changes the certificate, and the app reports
`SHA-256(apkContentsSigners[0])` to its backend. The original certificate is
lifted out of the input's v2/v3 signing block and handed back at that call site,
so the wire traffic is unchanged.

Nothing here is baked in. The endpoint, the key and the certificate are all read
out of the input APKs at patch time, and the signing key is generated per run.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "tools"))

import apk_signing_block  # noqa: E402
import smali_patches as sp  # noqa: E402
import snapshot  # noqa: E402
import zipedit  # noqa: E402

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
BUILD = ROOT / "build"

TARGET_PACKAGE = "com.mountains.hills"
NATIVE_LIB = "lib/arm64-v8a/libapp.so"
CONFIG_ASSET = "assets/chuanyi_hillspatch.json"
RUNTIME_DEX_NAME = "classes3.dex"

# High enough to bind unprivileged, and deliberately not the module's 45871 so
# that a device running both does not have them fight over the port.
LOOPBACK_PORT = 45872

# Where the injected runtime's compiled classes land.
RUNTIME_AAR = REPO / "patcher" / "runtime" / "build" / "outputs" / "aar" / "runtime-release.aar"


# ---------------------------------------------------------------------------
# Tools
# ---------------------------------------------------------------------------

def _sdk_build_tools() -> Path:
    root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not root:
        local = os.environ.get("LOCALAPPDATA")
        if local:
            root = str(Path(local) / "Android" / "Sdk")
    if not root or not Path(root, "build-tools").is_dir():
        raise SystemExit("set ANDROID_HOME; build-tools were not found")
    versions = sorted(Path(root, "build-tools").iterdir(), key=lambda p: p.name)
    if not versions:
        raise SystemExit("no build-tools installed")
    return versions[-1]


BUILD_TOOLS = None
APKTOOL = os.environ.get("APKTOOL", r"C:\Users\Chuanyi\Tools\apktool\apktool.bat")
ADB = None


def tool(name: str) -> str:
    global BUILD_TOOLS
    if BUILD_TOOLS is None:
        BUILD_TOOLS = _sdk_build_tools()
    for suffix in (".bat", ".exe", ""):
        candidate = BUILD_TOOLS / (name + suffix)
        if candidate.is_file():
            return str(candidate)
    raise SystemExit(f"{name} not found in {BUILD_TOOLS}")


def adb() -> str:
    global ADB
    if ADB is None:
        sdk = _sdk_build_tools().parent.parent
        for suffix in (".exe", ""):
            candidate = sdk / "platform-tools" / ("adb" + suffix)
            if candidate.is_file():
                ADB = str(candidate)
                break
        else:
            raise SystemExit("adb not found")
    return ADB


def run(command: list[str], **kwargs) -> subprocess.CompletedProcess:
    # errors="replace": keytool writes in the console codepage, which is not
    # UTF-8 on a Chinese Windows install and would otherwise raise here.
    result = subprocess.run(command, capture_output=True, text=True,
                            encoding="utf-8", errors="replace", **kwargs)
    if result.returncode != 0:
        sys.stderr.write(result.stdout or "")
        sys.stderr.write(result.stderr or "")
        raise SystemExit(f"failed ({result.returncode}): {' '.join(command[:3])} …")
    return result


def say(message: str) -> None:
    print(f"  {message}", flush=True)


def step(message: str) -> None:
    print(f"\n== {message}", flush=True)


# ---------------------------------------------------------------------------
# Inputs
# ---------------------------------------------------------------------------

def pull_from_device(into: Path) -> list[Path]:
    step("pulling the installed APK set")
    listing = run([adb(), "shell", "pm", "path", TARGET_PACKAGE]).stdout
    paths = [line.strip()[len("package:"):] for line in listing.splitlines()
             if line.strip().startswith("package:")]
    if not paths:
        raise SystemExit(f"{TARGET_PACKAGE} is not installed")

    into.mkdir(parents=True, exist_ok=True)
    pulled = []
    for remote in paths:
        local = into / Path(remote).name
        run([adb(), "pull", remote, str(local)])
        say(f"{local.name}  {local.stat().st_size // 1024} KiB")
        pulled.append(local)
    return pulled


def collect(directory: Path) -> list[Path]:
    apks = sorted(directory.glob("*.apk"))
    if not any(a.name == "base.apk" for a in apks):
        raise SystemExit(f"{directory} has no base.apk")
    return apks


# ---------------------------------------------------------------------------
# Keys
# ---------------------------------------------------------------------------

def generate_keypair(work: Path) -> tuple[bytes, bytes]:
    """(PKCS#8 private DER, public PEM) with the PEM in the app's exact layout.

    Generated per run rather than shipped: the public half is written over the
    app's own key, and a fixed pair in a patcher anyone can read would let any
    other build sign responses for this one.

    OpenSSL's default public PEM is already the layout the app uses — a 26-byte
    header, 64-character body lines, a 24-byte footer, `\\n` throughout — so the
    only adjustment is dropping the trailing newline. That makes an RSA-2048
    SPKI PEM exactly 450 bytes, which is what it has to be.
    """
    step("generating the response-signing key")
    private_pem = work / "patch-key.pem"
    private_der = work / "patch-key.pk8"
    public_pem = work / "patch-key.pub"

    run(["openssl", "genrsa", "-out", str(private_pem), "2048"])
    run(["openssl", "pkcs8", "-topk8", "-nocrypt", "-in", str(private_pem),
         "-outform", "DER", "-out", str(private_der)])
    run(["openssl", "rsa", "-in", str(private_pem), "-pubout", "-out", str(public_pem)])

    pem = public_pem.read_bytes().replace(b"\r\n", b"\n").rstrip(b"\n")
    say(f"RSA-2048, public PEM {len(pem)}B")
    return private_der.read_bytes(), pem


def signing_keystore(work: Path) -> tuple[Path, str, str]:
    """A keystore for the patched APKs, created once and reused.

    Deliberately not the repository's release key: the patched app is a
    different artifact with a different lifetime, and mixing them would mean a
    patch could not be installed alongside anything else signed with it.
    """
    store = BUILD / "patch.jks"
    alias, password = "hillspatch", "hillspatch"
    if store.is_file():
        return store, alias, password

    step("creating the patch signing key")
    store.parent.mkdir(parents=True, exist_ok=True)
    run(["keytool", "-genkeypair", "-v",
         "-keystore", str(store), "-storepass", password, "-keypass", password,
         "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
         "-dname", "CN=Chuanyi Hills Patch, OU=Patch, O=Chuanyi, C=CN"])
    say(str(store))
    return store, alias, password


# ---------------------------------------------------------------------------
# The native half
# ---------------------------------------------------------------------------

def patch_native(split: Path, replacement_url: bytes, public_pem: bytes,
                 pin: bytes | None) -> tuple[str, str]:
    """Rewrites the endpoint and the public key inside `libapp.so`.

    Returns (original endpoint, chosen replacement) so the runtime can be told
    where to forward anything it cannot answer.
    """
    step(f"rewriting the snapshot in {split.name}")

    with open(split, "rb") as handle:
        data = handle.read()
    info = zipedit.entry_info(data, NATIVE_LIB)
    if info is None:
        raise SystemExit(f"{split.name} has no {NATIVE_LIB}")
    offset, size, method, _, _ = info
    if method != zipedit.STORED:
        raise SystemExit(f"{NATIVE_LIB} is compressed; the app declares extractNativeLibs=false")

    library = data[offset:offset + size]

    url_hit = snapshot.find_verify_url(library, pin)
    if url_hit is None:
        raise SystemExit("no purchase-verification endpoint found in the snapshot")
    url_offset, original_url = url_hit
    say(f"endpoint  {original_url.decode()}  ({len(original_url)}B @ {url_offset:#x})")

    key_hit = snapshot.find_public_key(library)
    if key_hit is None:
        raise SystemExit("no public key found in the snapshot")
    key_offset, original_pem = key_hit
    say(f"public key  {len(original_pem)}B @ {key_offset:#x}")

    if len(public_pem) != len(original_pem):
        raise SystemExit(
            f"generated PEM is {len(public_pem)}B but the app's is {len(original_pem)}B; "
            "the key size or PEM layout differs and an unequal overwrite would "
            "corrupt the neighbouring object")

    replacement = pad_url(replacement_url, len(original_url))
    say(f"redirecting to  {replacement.decode()}")

    def mutate(payload: bytearray) -> None:
        snapshot.replace_at(payload, url_offset, original_url, replacement)
        snapshot.replace_at(payload, key_offset, original_pem, public_pem)

    zipedit.patch_stored_entry(str(split), NATIVE_LIB, mutate)
    say("written in place; every zip offset and the page alignment are unchanged")
    return original_url.decode(), replacement.decode()


def pad_url(origin: bytes, length: int) -> bytes:
    """`origin` padded with path characters to exactly `length` bytes.

    The replacement is written over the live string, so it has to be the same
    size as what it replaces — and that size comes from the app. Padding goes in
    the path because a path absorbs any surplus down to a single byte, while a
    query string needs two; the endpoint ignores the path either way.
    """
    if length == len(origin):
        return origin
    if length < len(origin):
        raise SystemExit(f"the endpoint is only {length}B; {len(origin)}B are needed")
    return origin + b"/" + b"p" * (length - len(origin) - 1)


# ---------------------------------------------------------------------------
# The Java half
# ---------------------------------------------------------------------------

PATCHES = (
    ("Application entry point", "smali/com/mountains/hills/App.smali", sp.patch_application),
    ("purchase list", "smali_classes2/io/flutter/plugins/inapppurchase/Translator.smali",
     sp.patch_translator),
    ("capability probe", "smali_classes2/io/flutter/plugins/inapppurchase/"
                         "MethodCallHandlerImpl.smali", sp.patch_feature_supported),
    ("billing readiness", "smali_classes2/io/flutter/plugins/inapppurchase/"
                          "MethodCallHandlerImpl.smali", sp.patch_is_ready),
    ("catalogue query", "smali_classes2/io/flutter/plugins/inapppurchase/"
                        "MethodCallHandlerImpl.smali", sp.patch_query_products),
    ("purchase sheet", "smali_classes2/io/flutter/plugins/inapppurchase/"
                       "MethodCallHandlerImpl.smali", sp.patch_launch_flow),
    ("player entitlement", "smali/com/mountains/player/models/PlayerConfig.smali",
     sp.patch_player_pro),
)


def apply_smali(decoded: Path) -> None:
    step("patching the app's code")
    for name, relative, patch in PATCHES:
        path = decoded / relative
        if not path.is_file():
            raise SystemExit(f"{relative} is missing; the build has moved")
        text = path.read_text(encoding="utf-8")
        text, applied = patch(text)
        path.write_text(text, encoding="utf-8")
        say(f"{name:<24} {applied} site(s)  {Path(relative).name}")

    # The signature channel's owner is found by content: it is the only class
    # that digests a Signature, and its name is a single obfuscated letter that
    # will not survive the next build.
    holder = sp.find_signature_holder(decoded)
    text = holder.read_text(encoding="utf-8")
    text, applied = sp.patch_signature(text)
    holder.write_text(text, encoding="utf-8")
    say(f"{'signature channel':<24} {applied} site(s)  {holder.name}")


def build_runtime_dex(work: Path, min_sdk: int) -> Path:
    """Compiles the injected runtime to a single dex."""
    step("building the injected runtime")
    run(["cmd", "/c", str(REPO / "gradlew.bat"), ":patcher:runtime:assembleRelease",
         "--console=plain"], cwd=str(REPO))
    if not RUNTIME_AAR.is_file():
        raise SystemExit(f"{RUNTIME_AAR} was not produced")

    import zipfile
    classes = work / "runtime-classes.jar"
    with zipfile.ZipFile(RUNTIME_AAR) as aar:
        classes.write_bytes(aar.read("classes.jar"))

    out = work / "dex"
    out.mkdir(exist_ok=True)
    run([tool("d8"), "--release", "--min-api", str(min_sdk),
         "--output", str(out), str(classes)])
    produced = out / "classes.dex"
    if not produced.is_file():
        raise SystemExit("d8 produced no dex")
    say(f"{produced.stat().st_size} bytes")
    return produced


# ---------------------------------------------------------------------------
# Assembly
# ---------------------------------------------------------------------------

def min_sdk_of(apk: Path) -> int:
    """The app's own minSdkVersion, read out of the base APK.

    apksigner needs telling: without it, it assumes API 1 and then refuses an
    APK that has no v1 signature, which is exactly what these are. Passing the
    real value is also what decides that v1 can stay off — v2 covers API 24 and
    up on its own, and the config splits inherit the base's floor.
    """
    output = run([tool("aapt2"), "dump", "badging", str(apk)]).stdout
    for line in output.splitlines():
        # aapt2 prints "minSdkVersion:'24'"; aapt1 printed "sdkVersion:'24'".
        if line.startswith(("minSdkVersion:", "sdkVersion:")):
            return int(line.split("'")[1])
    raise SystemExit("could not read minSdkVersion from the base APK")


def sign(apk: Path, store: Path, alias: str, password: str, min_sdk: int) -> None:
    aligned = apk.with_suffix(".aligned.apk")
    # -P 16 page-aligns uncompressed .so to 16 KiB, which extractNativeLibs=false
    # requires and which Android 15 devices need. It is mutually exclusive with
    # -p, whose page size is fixed at 4 KiB.
    run([tool("zipalign"), "-f", "-P", "16", "4", str(apk), str(aligned)])
    aligned.replace(apk)
    run([tool("apksigner"), "sign",
         "--ks", str(store), "--ks-pass", f"pass:{password}",
         "--ks-key-alias", alias, "--key-pass", f"pass:{password}",
         "--min-sdk-version", str(min_sdk),
         "--v1-signing-enabled", "false",
         "--v2-signing-enabled", "true",
         "--v3-signing-enabled", "true",
         str(apk)])


def main() -> int:
    parser = argparse.ArgumentParser(description="Patch the Hills APK set.")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--from-device", action="store_true",
                        help="pull the installed APK set over adb")
    source.add_argument("--input", type=Path, help="directory holding base.apk and its splits")
    parser.add_argument("--out", type=Path, default=BUILD / "out")
    parser.add_argument("--sku", help="pin the product id instead of learning it")
    parser.add_argument("--endpoint-match", help="substring the verification endpoint must contain")
    parser.add_argument("--install", action="store_true", help="adb install-multiple when done")
    parser.add_argument("--keep-work", action="store_true", help="leave the scratch directory")
    args = parser.parse_args()

    BUILD.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="hillspatch-", dir=str(BUILD)))
    out = args.out
    # Clearing the contents rather than the directory itself: on Windows any
    # shell sitting in it holds a handle, and removing the directory then fails
    # with a permission error that has nothing to do with the patch.
    out.mkdir(parents=True, exist_ok=True)
    for stale in out.iterdir():
        shutil.rmtree(stale) if stale.is_dir() else stale.unlink()

    try:
        sources = pull_from_device(work / "input") if args.from_device else collect(args.input)
        for apk in sources:
            shutil.copy2(apk, out / apk.name)
        apks = sorted(out.glob("*.apk"))
        base = out / "base.apk"

        step("reading the original signing certificate")
        certificate = apk_signing_block.first_certificate(str(base))
        import hashlib
        say(f"SHA-256 {hashlib.sha256(certificate).hexdigest()}")
        say("handed back at the signature channel so the repack is invisible on the wire")

        private_key, public_pem = generate_keypair(work)

        native = next((a for a in apks if "arm64" in a.name), None)
        if native is None:
            raise SystemExit("no arm64 split; this patch targets arm64-v8a")
        pin = args.endpoint_match.encode() if args.endpoint_match else None
        upstream, redirect = patch_native(
            native, f"http://127.0.0.1:{LOOPBACK_PORT}".encode(), public_pem, pin)

        step("decoding the base APK")
        decoded = work / "decoded"
        run(["cmd", "/c", APKTOOL, "d", "-r", "-f", "-o", str(decoded), str(base)])
        say(f"{sum(1 for _ in decoded.rglob('*.smali'))} smali files")

        apply_smali(decoded)

        step("writing the patch configuration")
        config = {
            "port": LOOPBACK_PORT,
            "upstream": upstream,
            "privateKey": base64.b64encode(private_key).decode("ascii"),
            "certificate": base64.b64encode(certificate).decode("ascii"),
            "translator": "io.flutter.plugins.inapppurchase.Translator",
            "purchaseClass": "com.android.billingclient.api.Purchase",
        }
        if args.sku:
            config["sku"] = args.sku
        asset = decoded / CONFIG_ASSET
        asset.parent.mkdir(parents=True, exist_ok=True)
        asset.write_text(json.dumps(config, indent=2), encoding="utf-8")
        say(f"{CONFIG_ASSET}  redirect {redirect}")

        step("rebuilding the base APK")
        rebuilt = work / "base-rebuilt.apk"
        run(["cmd", "/c", APKTOOL, "b", str(decoded), "-o", str(rebuilt)])
        say(f"{rebuilt.stat().st_size // 1024} KiB")

        dex = build_runtime_dex(work, min_sdk=24)
        zipedit.add_entries(str(rebuilt), {RUNTIME_DEX_NAME: dex.read_bytes()})
        say(f"injected as {RUNTIME_DEX_NAME}")
        rebuilt.replace(base)

        step("aligning and signing")
        store, alias, password = signing_keystore(work)
        floor = min_sdk_of(base)
        say(f"minSdkVersion {floor}; v2/v3 only, v1 is not needed above API 23")
        for apk in sorted(out.glob("*.apk")):
            sign(apk, store, alias, password, floor)
            say(apk.name)

        if args.install:
            step("installing")
            run([adb(), "install-multiple", "-r", *[str(a) for a in sorted(out.glob("*.apk"))]])
            say("installed")

        step("done")
        for apk in sorted(out.glob("*.apk")):
            print(f"  {apk}")
        return 0
    finally:
        if args.keep_work:
            print(f"\nscratch kept at {work}")
        else:
            shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
