"""Checks that the release workflow attests the files it attaches.

    python3 scripts/check-release-workflow.py [.github/workflows/release.yml]

The release job must have id-token and attestations write permissions, and
must run actions/attest-build-provenance pinned to the reviewed commit on the
APKs and the bundle, after the signer check and before the files are attached
to the release. Prints what is wrong and exits with 1, or prints OK."""

import pathlib
import sys

ATTEST = "actions/attest-build-provenance@4d101475d8b20a2381f78447822ac1eab6504dd8"


def problems(text):
    found = []
    lines = text.splitlines()
    for permission in ("id-token: write", "attestations: write"):
        if not any(line.strip() == permission for line in lines):
            found.append(f"the release job lacks '{permission}'")
    uses = [line.strip() for line in lines if "attest-build-provenance" in line and "uses:" in line]
    if not uses:
        found.append("no step uses actions/attest-build-provenance")
    elif not all(line.split("uses:", 1)[1].split("#")[0].strip() == ATTEST for line in uses):
        found.append(f"actions/attest-build-provenance is not pinned to {ATTEST.split('@')[1]}: {uses}")
    for subject in ("dist/*.apk", "dist/*.aab"):
        if not any(line.strip() == subject for line in lines):
            found.append(f"the attestation does not cover {subject}")
    names = [line.strip()[len("- name:"):].strip() for line in lines if line.strip().startswith("- name:")]
    order = {}
    for wanted in ("Check the signer", "Attest build provenance", "Attach to the release"):
        order[wanted] = next((i for i, name in enumerate(names) if name.startswith(wanted)), None)
        if order[wanted] is None:
            found.append(f"no step named '{wanted}'")
    if None not in order.values() and not (
        order["Check the signer"] < order["Attest build provenance"] < order["Attach to the release"]
    ):
        found.append("the attestation must come after the signer check and before the files are attached")
    return found


def main():
    path = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".github/workflows/release.yml")
    found = problems(path.read_text())
    for problem in found:
        print(f"::error file={path}::{problem}")
    if found:
        sys.exit(1)
    print(f"OK: {path} attests the APKs and the bundle with {ATTEST}")


main()
