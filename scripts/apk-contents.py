"""Prints what an APK is made of, grouped, as a Markdown table.

    python3 scripts/apk-contents.py app-release.apk [--forbid PREFIX ...]

With --forbid, exits with an error when an entry starts with one of the
prefixes: files the app does not need and the build should leave out.

"Stored" is the size inside the APK (what is downloaded), "Unpacked" the size
once installed or loaded. Native libraries are stored uncompressed so Android
can map them straight from the APK, so both columns match for them.
"""
import sys
import zipfile
from collections import defaultdict


def group(name):
    parts = name.split("/")
    if parts[0] == "lib" and len(parts) > 2:
        return "lib/" + parts[1] + "/" + parts[-1]
    if parts[0] == "assets" and len(parts) > 2:
        return "assets/" + "/".join(parts[1:-1]) + "/"
    if parts[0] in ("res", "META-INF"):
        return parts[0] + "/"
    if name.endswith(".dex"):
        return "classes*.dex"
    return name


def main(path, forbidden=()):
    stored = defaultdict(int)
    unpacked = defaultdict(int)
    with zipfile.ZipFile(path) as apk:
        for info in apk.infolist():
            key = group(info.filename)
            stored[key] += info.compress_size
            unpacked[key] += info.file_size
    total = sum(stored.values())
    print("| Part | Stored bytes | Unpacked bytes | Share of APK |")
    print("| --- | ---: | ---: | ---: |")
    for key in sorted(stored, key=lambda k: -stored[k]):
        print(f"| `{key}` | {stored[key]:,} | {unpacked[key]:,} | {100 * stored[key] / total:.1f}% |")
    print(f"| total (entries) | {total:,} | {sum(unpacked.values()):,} | |")
    with zipfile.ZipFile(path) as apk:
        found = [info for info in apk.infolist() if info.filename.startswith(tuple(forbidden))]
    if found:
        print()
        for info in found:
            print(f"Not needed in the APK: `{info.filename}` ({info.compress_size:,} bytes stored)")
        sys.exit(f"{len(found)} entries the app does not need, {sum(i.compress_size for i in found):,} bytes stored")


if __name__ == "__main__":
    arguments = sys.argv[1:]
    forbid = arguments[arguments.index("--forbid") + 1:] if "--forbid" in arguments else []
    main(arguments[0], forbid)
