#!/usr/bin/env python3
"""Audit translation keys for the Gett Core mod.

Usage (from the repository root, no Gradle needed):
    python -I gettcore/tools/check-lang.py

Checks:
  * every key referenced as a string literal in gettcore/src/main/java
    (``gettcore.a.b`` or ``item.gettcore.x``) exists in both lang files;
  * keys present in one lang file but missing from the other.

Exit code 1 if anything is missing or mismatched, 0 otherwise.
Standard library only.
"""

import json
import re
import sys
from pathlib import Path

MOD_ROOT = Path(__file__).resolve().parent.parent
JAVA_DIR = MOD_ROOT / "src" / "main" / "java"
LANG_DIR = MOD_ROOT / "src" / "main" / "resources" / "assets" / "gettcore" / "lang"
LANG_FILES = {"ko_kr": LANG_DIR / "ko_kr.json", "en_us": LANG_DIR / "en_us.json"}

STRING_LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
KEY_PATTERN = re.compile(r"(?:gettcore\.[a-z_]+(?:\.[a-z_]+)+|item\.gettcore\.[a-z_]+)")


def collect_code_keys():
    """Return {key: sorted list of 'file:line' locations} for keys used in Java string literals."""
    found = {}
    for java_file in sorted(JAVA_DIR.rglob("*.java")):
        text = java_file.read_text(encoding="utf-8")
        for line_no, line in enumerate(text.splitlines(), start=1):
            for literal in STRING_LITERAL.finditer(line):
                for key in KEY_PATTERN.findall(literal.group(1)):
                    rel = java_file.relative_to(MOD_ROOT).as_posix()
                    found.setdefault(key, []).append(f"{rel}:{line_no}")
    return found


def load_lang(path):
    """Load a lang JSON file, failing loudly on invalid JSON or duplicate keys."""
    def no_duplicates(pairs):
        seen = {}
        for k, v in pairs:
            if k in seen:
                raise ValueError(f"duplicate key {k!r}")
            seen[k] = v
        return seen

    with path.open(encoding="utf-8") as fh:
        return json.load(fh, object_pairs_hook=no_duplicates)


def main():
    code_keys = collect_code_keys()
    lang = {name: load_lang(path) for name, path in LANG_FILES.items()}

    problems = 0

    print(f"Keys used in code: {len(code_keys)}")
    print("Keys used in code but missing from a lang file:")
    any_missing = False
    for key in sorted(code_keys):
        missing_in = [name for name in LANG_FILES if key not in lang[name]]
        if missing_in:
            any_missing = True
            problems += 1
            locs = ", ".join(code_keys[key][:3])
            print(f"  {key}  missing in {', '.join(missing_in)}  (used at {locs})")
    if not any_missing:
        print("  (none)")

    ko_keys, en_keys = set(lang["ko_kr"]), set(lang["en_us"])
    print("Keys present in only one lang file:")
    only_ko = sorted(ko_keys - en_keys)
    only_en = sorted(en_keys - ko_keys)
    for key in only_ko:
        problems += 1
        print(f"  {key}  only in ko_kr")
    for key in only_en:
        problems += 1
        print(f"  {key}  only in en_us")
    if not only_ko and not only_en:
        print("  (none)")

    print(f"RESULT: {'FAIL' if problems else 'OK'} ({problems} problem(s))")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
