#!/usr/bin/env bash
# Writes the files for the phone test in PROGRESS.md into one zip:
#   scripts/phone-test-files.sh out.zip
# weather-stations.csv is the desktop's store sample; large-multiline.csv
# (100,001 rows) is written by the port of the desktop's fixture script.
set -euo pipefail
out=$(realpath -m "$1")
work=$(mktemp -d)
cp app/src/sharedTest/fixtures/desktop/weather-stations.csv "$work/"
java app/src/sharedTest/java/io/github/abooodbah/leanrows/DesktopFixtures.java large-multiline.csv "$work/large-multiline.csv"
(cd "$work" && zip -q -X "$out" weather-stations.csv large-multiline.csv)
rm -rf "$work"
