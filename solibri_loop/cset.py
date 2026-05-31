"""Read ruleset metadata out of Solibri .cset / .smc files.

The REST API cannot list or load rulesets, so we read them from disk. Both formats are:
    .cset  = ZIP { Model<n>.smc (Java-serialized object graph), <version> }
    .smc   = ZIP { Model<n>.smc (Java-serialized object graph), <version>, plugin data }

The serialized blob (magic 0xACED0005) stores ruleset names, .cset filenames, rule titles
and descriptions as Java String instances (TC_STRING = 0x74, 2-byte big-endian length,
then UTF-8). Rule implementation classes appear as plain "com.solibri.*Rule" byte runs.

This is read-only metadata extraction. EDITING a ruleset means changing values inside this
serialized graph - feasible for same-length scalar params (tolerances, severities) by
in-place byte patching, but structural edits need the Solibri SDK or UI. See README.
"""

from __future__ import annotations

import re
import struct
import zipfile

JAVA_SER_MAGIC = b"\xac\xed\x00\x05"


def _load_serialized_blob(path: str) -> bytes:
    """Return the inner Java-serialized object graph from a .cset/.smc (or the file itself)."""
    with open(path, "rb") as fh:
        head = fh.read(4)
    if head == JAVA_SER_MAGIC:
        with open(path, "rb") as fh:
            return fh.read()
    if head[:2] == b"PK":
        with zipfile.ZipFile(path) as zf:
            # the model entry is the big one; pick the largest entry that is java-serialized
            candidates = sorted(zf.infolist(), key=lambda i: i.file_size, reverse=True)
            for info in candidates:
                data = zf.read(info.filename)
                if data[:4] == JAVA_SER_MAGIC:
                    return data
            # fall back to largest entry
            if candidates:
                return zf.read(candidates[0].filename)
    raise ValueError(f"{path}: not a Solibri .cset/.smc (no zip or java-serialization magic)")


def _serialized_strings(blob: bytes, min_len: int = 2, max_len: int = 4096):
    """Yield every Java TC_STRING value in the blob (the readable String fields)."""
    n = len(blob)
    i = 0
    out = []
    while i < n - 3:
        if blob[i] == 0x74:  # TC_STRING
            length = struct.unpack_from(">H", blob, i + 1)[0]
            if min_len <= length <= max_len and i + 3 + length <= n:
                chunk = blob[i + 3:i + 3 + length]
                try:
                    s = chunk.decode("utf-8")
                except UnicodeDecodeError:
                    i += 1
                    continue
                if s.isprintable() or "\n" in s:
                    out.append(s)
                    i += 3 + length
                    continue
        i += 1
    return out


_CLASSNAME_OK = re.compile(rb"^[A-Za-z0-9_.$]+$")


def _length_prefixed_classes(blob: bytes) -> list[str]:
    """Extract Solibri class names using the serialization 2-byte length prefix.

    Java writes class names in TC_CLASSDESC as a length-prefixed UTF (not a TC_STRING),
    so reading the 2 bytes before "com.solibri" gives the exact name and avoids picking up
    trailing serialization tags (0x70/0x78/0x71...) that look like lowercase letters.
    """
    out = set()
    for m in re.finditer(rb"com\.solibri", blob):
        o = m.start()
        if o < 2:
            continue
        length = struct.unpack_from(">H", blob, o - 2)[0]
        if 10 < length < 200 and o + length <= len(blob):
            name = blob[o:o + length]
            if _CLASSNAME_OK.match(name):
                out.add(name.decode("ascii"))
    return sorted(out)


def read_ruleset_info(path: str) -> dict:
    """Extract ruleset metadata from a .cset or project .smc.

    Returns: {rulesets, ruleset_files, rule_classes, rule_unique_ids, titles_sample}.
    """
    blob = _load_serialized_blob(path)
    strings = _serialized_strings(blob)

    ruleset_files = sorted({s for s in strings if s.lower().endswith(".cset")})
    # ruleset display name = file basename without extension (works for absolute paths too)
    import os
    rulesets = sorted({os.path.splitext(os.path.basename(f))[0] for f in ruleset_files})

    all_classes = _length_prefixed_classes(blob)
    rule_classes = [c for c in all_classes if "Rule" in c or c.endswith("Constraint")]
    rule_unique_ids = sorted(set(re.findall(rb"SOL/\d+/[\d.]+", blob)))
    rule_unique_ids = [u.decode("ascii") for u in rule_unique_ids]

    # candidate human titles: medium-length strings that aren't class/file/path-like
    titles = sorted({
        s for s in strings
        if 4 <= len(s) <= 70 and " " in s
        and not s.startswith("com.solibri")
        and "\\" not in s and "/" not in s and "<" not in s
    })

    return {
        "source": path,
        "rulesets": rulesets,
        "ruleset_files": ruleset_files,
        "rule_classes": rule_classes,
        "rule_unique_ids": rule_unique_ids,
        "titles_sample": titles[:40],
    }
