"""The seven edits made to the app's own code.

Every one of them is either a two-instruction insert or a whole-method
replacement, and none of them changes register pressure: the inserts reuse a
register the surrounding code already holds, and the replacements declare fewer
locals than they replace. That is what keeps this reviewable — no `.locals`
arithmetic, no register renumbering, nothing that depends on the shape of the
code around it.

Everything substantial lives behind `Rt`, in ordinary Java. Hand-written smali
for an HTTP server or RS256 signing would be unmaintainable and untestable.

Each patch declares what it expects to find and fails loudly when it does not:
a patch that silently matched nothing produces an APK that installs, runs, and
does not unlock — the worst possible outcome to debug.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

RT = "Lcom/chuanyi/hillspatch/Rt;"


class PatchError(Exception):
    pass


@dataclass
class Result:
    name: str
    path: str
    applied: int


def _method_body_span(text: str, signature: str) -> tuple[int, int, int]:
    """(body_start, end_of_method, header_end) for the method whose line is `signature`."""
    start = text.find(signature)
    if start < 0:
        raise PatchError(f"method not found: {signature.strip()}")
    end = text.find(".end method", start)
    if end < 0:
        raise PatchError(f"unterminated method: {signature.strip()}")
    # The body begins after `.locals`; annotations sit between it and the code.
    locals_match = re.search(r"^\s*\.locals \d+\s*$", text[start:end], re.MULTILINE)
    if not locals_match:
        raise PatchError(f"no .locals in {signature.strip()}")
    return start, end, start + locals_match.end()


def replace_method(text: str, signature: str, body: str) -> tuple[str, int]:
    """Swaps a whole method body, keeping the signature line."""
    start, end, _ = _method_body_span(text, signature)
    return text[:start] + signature + body + text[end:], 1


def insert_after(text: str, signature: str, anchor: str, insert: str) -> tuple[str, int]:
    """Puts `insert` right after the first `anchor` inside the method."""
    start, end, _ = _method_body_span(text, signature)
    region = text[start:end]
    index = region.find(anchor)
    if index < 0:
        raise PatchError(f"anchor not found in {signature.strip()}: {anchor.strip()}")
    cut = start + index + len(anchor)
    return text[:cut] + insert + text[cut:], 1


def insert_at_body_start(text: str, signature: str, insert: str) -> tuple[str, int]:
    """Puts `insert` at the top of the method body, after any annotations."""
    start, end, header_end = _method_body_span(text, signature)
    region = text[header_end:end]
    # Skip past the annotation block, which must stay attached to the header.
    annotation = re.search(r"\.end annotation\s*\n", region)
    cut = header_end + (annotation.end() if annotation else 0)
    return text[:cut] + insert + text[cut:], 1


def wrap_returns(text: str, signature: str, call: str) -> tuple[str, int]:
    """Routes every `return-object vX` in the method through `call`."""
    start, end, _ = _method_body_span(text, signature)
    region = text[start:end]

    applied = 0

    def rewrite(match: re.Match) -> str:
        nonlocal applied
        applied += 1
        indent, register = match.group(1), match.group(2)
        return (
            f"{indent}invoke-static {{{register}}}, {call}\n"
            f"{indent}move-result-object {register}\n"
            f"{indent}return-object {register}"
        )

    region = re.sub(r"^([ \t]*)return-object (\w+)$", rewrite, region, flags=re.MULTILINE)
    if applied == 0:
        raise PatchError(f"no return-object in {signature.strip()}")
    return text[:start] + region + text[end:], applied


# ---------------------------------------------------------------------------
# The patches themselves.
# ---------------------------------------------------------------------------

def patch_application(text: str) -> tuple[str, int]:
    """`App.onCreate` — the one point guaranteed to run once per process.

    Far earlier than anything that matters: the Flutter engine, the billing
    client and the first verification are all later, so the loopback endpoint is
    listening long before the rewritten URL is used.
    """
    return insert_after(
        text,
        ".method public final onCreate()V\n",
        "invoke-super {p0}, Landroid/app/Application;->onCreate()V\n",
        f"\n    invoke-static {{p0}}, {RT}->init(Landroid/content/Context;)V\n",
    )


def patch_translator(text: str) -> tuple[str, int]:
    """`Translator.fromPurchasesList` — the single funnel every purchase takes.

    Both `queryPurchasesAsync` and the `onPurchasesUpdated` callback converge
    here, so one site covers every path. Wrapping the *return* leaves the app's
    own conversion in charge of every genuine entry.
    """
    return wrap_returns(
        text,
        ".method public static fromPurchasesList(Ljava/util/List;)Ljava/util/List;\n",
        f"{RT}->purchases(Ljava/util/List;)Ljava/util/List;",
    )


def patch_feature_supported(text: str) -> tuple[str, int]:
    """`isFeatureSupported` — the probe behind "设备不支持 Google Play 订阅".

    Replaced whole rather than wrapped: with no billing client it *throws*
    `FlutterError("UNAVAILABLE")` instead of returning false, so there is no
    return value to intercept. It is also upstream of everything else — an app
    that has decided the device cannot buy never queries purchases at all.
    """
    return replace_method(
        text,
        ".method public isFeatureSupported(Lio/flutter/plugins/inapppurchase/"
        "Messages$PlatformBillingClientFeature;)Ljava/lang/Boolean;\n",
        f"""    .locals 1

    invoke-static {{}}, {RT}->supported()Z

    move-result v0

    invoke-static {{v0}}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object v0

    return-object v0

""",
    )


def patch_is_ready(text: str) -> tuple[str, int]:
    """`isReady` — the other half of the same question.

    Dart stops before it ever queries when this says false, which happens
    whenever the billing client is null or disconnected.
    """
    return replace_method(
        text,
        ".method public isReady()Ljava/lang/Boolean;\n",
        f"""    .locals 1

    invoke-static {{}}, {RT}->supported()Z

    move-result v0

    invoke-static {{v0}}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object v0

    return-object v0

""",
    )


def patch_query_products(text: str) -> tuple[str, int]:
    """`queryProductDetailsAsync` — where the catalogue crosses into Java.

    The product id is a Dart constant and is not in the dex at all, so it cannot
    be read out at patch time; this is the app telling us what it sells.
    """
    return insert_at_body_start(
        text,
        ".method public queryProductDetailsAsync(Ljava/util/List;"
        "Lio/flutter/plugins/inapppurchase/Messages$Result;)V\n",
        f"\n    invoke-static {{p1}}, {RT}->products(Ljava/util/List;)V\n",
    )


def patch_launch_flow(text: str) -> tuple[str, int]:
    """`launchBillingFlow` — the strongest signal about which product matters.

    Rarer than the catalogue query, but it names exactly the product the user is
    being sold, so it outranks anything inferred from names.
    """
    return insert_at_body_start(
        text,
        ".method public launchBillingFlow(Lio/flutter/plugins/inapppurchase/"
        "Messages$PlatformBillingFlowParams;)Lio/flutter/plugins/inapppurchase/"
        "Messages$PlatformBillingResult;\n",
        f"\n    invoke-static {{p1}}, {RT}->sold(Ljava/lang/Object;)V\n",
    )


def patch_player_pro(text: str) -> tuple[str, int]:
    """`PlayerConfig.isPro` — carried across the boundary in the player config."""
    return replace_method(
        text,
        ".method public final isPro()Z\n",
        """    .locals 1

    const/4 v0, 0x1

    return v0

""",
    )


def find_signature_holder(decoded) -> object:
    """The class that answers the app's own signature channel.

    "Digests a `Signature`" is not enough to identify it — Play Billing,
    Firebase and Glide all do that, twelve classes in this build. What is unique
    is doing it *in answer to a MethodChannel call*, and the method name on that
    channel is protocol: Dart sends the same literal, so it cannot be renamed.

    The channel name is read out of the app rather than assumed. Whichever class
    registers a `MethodChannel` is found first, and its channel string tells us
    the name the handler is answering; that keeps this working if the app
    renames `com.mountains.signature` to something else.
    """
    from pathlib import Path

    folders = [decoded / name for name in ("smali", "smali_classes2", "smali_classes3")]
    digest = "Landroid/content/pm/Signature;->toByteArray()[B"

    candidates = []
    for folder in folders:
        if not folder.is_dir():
            continue
        for path in folder.rglob("*.smali"):
            body = path.read_text(encoding="utf-8", errors="ignore")
            if digest not in body:
                continue
            # The handler holds the method name it responds to, as a constant.
            if 'const-string' in body and '"getSignature"' in body:
                candidates.append((path, body))

    if not candidates:
        raise PatchError(
            "no class answers a signature channel; either the check was removed "
            "or its method was renamed")
    if len(candidates) > 1:
        names = sorted(str(Path(p).name) for p, _ in candidates)
        raise PatchError(f"several classes answer a signature channel: {names}")
    return candidates[0][0]


def patch_signature(text: str) -> tuple[str, int]:
    """The signature channel's digest input.

    The app reports `SHA-256(apkContentsSigners[0].toByteArray())` to its
    backend, and re-signing changes it — the one thing a repack cannot hide by
    itself. Swapping the certificate *before* it is digested leaves the app's own
    hashing and formatting untouched, so the separator and case it uses stay
    correct without having to be reproduced anywhere.
    """
    anchor = "invoke-virtual {v0}, Landroid/content/pm/Signature;->toByteArray()[B"
    index = text.find(anchor)
    if index < 0:
        raise PatchError("the Signature.toByteArray call site was not found")
    move = text.find("move-result-object v0", index)
    if move < 0:
        raise PatchError("no move-result after Signature.toByteArray")
    cut = move + len("move-result-object v0")
    insert = (
        f"\n\n    invoke-static {{v0}}, {RT}->cert([B)[B"
        "\n\n    move-result-object v0"
    )
    return text[:cut] + insert + text[cut:], 1
