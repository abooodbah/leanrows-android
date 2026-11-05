#!/usr/bin/env bash
# Measures release builds on a running emulator and prints a Markdown table.
#
#   scripts/measure.sh empty=path/to/empty.apk base=path/to/base.apk branch=path/to/branch.apk
#
# The instrumented test APK must be installed first: its fixture provider
# serves the files as content:// URIs. "empty" is the baseline module, an app
# with one empty activity; its start time is subtracted, because on an
# emulator without a GPU even an empty app takes over a second. Builds take
# turns, so drift in the emulator affects both alike. Every number comes from
# am start -W, the "Fully drawn" line the app logs through reportFullyDrawn(),
# dumpsys meminfo and dumpsys gfxinfo.
set -euo pipefail

PKG=io.github.abooodbah.leanrows
ACTIVITY=$PKG/.MainActivity
EMPTY_PKG=$PKG.baseline
FIXTURES=content://$PKG.fixtures
SMALL=file/desktop/weather-stations.csv
SMALL_NAME="weather-stations.csv"
SMALL_TYPE=text/csv
DRAWN_WHAT=rows
LARGE=file/generated/large-multiline.csv
LARGE_NAME="large-multiline.csv (100,001 rows)"
LARGE_TYPE=text/csv
WEBVIEW=no
MEASURE_INDEXING=yes
TIMING_TAG=none
# An .xlsx workbook is written out as CSV before its rows can be shown.
WORKBOOK=file/xlsx/large-weather.xlsx
WORKBOOK_NAME="large-weather.xlsx (100,001 rows)"
WORKBOOK_TYPE=application/vnd.openxmlformats-officedocument.spreadsheetml.sheet

ROUNDS=${ROUNDS:-3}
STARTS_PER_ROUND=${STARTS_PER_ROUND:-3}
STARTS=$((ROUNDS * STARTS_PER_ROUND))

declare -a LABELS=()
declare -A APKS=()
EMPTY_APK=""
for argument in "$@"; do
  label="${argument%%=*}"
  if [ "$label" = empty ]; then
    EMPTY_APK="${argument#*=}"
  else
    LABELS+=("$label")
    APKS["$label"]="${argument#*=}"
  fi
done

log() { echo "[measure] $*" >&2; }

# Root lets the script ask ART for its allocation count (SIGQUIT dump). The
# AOSP emulator image allows it; elsewhere allocations are reported as NA.
ROOT=no
if adb root >/dev/null 2>&1; then
  # adbd restarts as root; the device is offline for a moment.
  sleep 3
  for _ in $(seq 1 60); do
    if adb shell true >/dev/null 2>&1; then break; fi
    sleep 1
  done
  if [ "$(adb shell id -u 2>/dev/null | tr -d '\r' || true)" = 0 ]; then ROOT=yes; fi
fi
log "adb root: $ROOT"

install() {
  adb install -r -d "${APKS[$1]}" >/dev/null
}

open_document() {
  adb shell am force-stop "$PKG"
  adb logcat -c
  adb shell am start-activity -W -n "$ACTIVITY" -a android.intent.action.VIEW \
    -d "$FIXTURES/$1" -t "$2" --grant-read-uri-permission | tr -d '\r'
}

# Milliseconds from "+1s234ms" or "+987ms".
to_ms() {
  sed -E 's/^\+//; s/^([0-9]+)s([0-9]+)ms$/\1 \2/; s/^([0-9]+)ms$/0 \1/' <<<"$1" |
    awk '{ print $1 * 1000 + $2 }'
}

# Waits for the app's reportFullyDrawn() and prints its time, or NA.
fully_drawn() {
  local limit=$1 line
  for _ in $(seq 1 $((limit * 10))); do
    line=$(adb logcat -d | grep -o "Fully drawn $PKG/\.MainActivity: +[0-9a-z]*" | head -n 1 || true)
    if [ -n "$line" ]; then
      to_ms "${line##*: }"
      return
    fi
    sleep 0.1
  done
  echo NA
}

median() {
  local values
  values=$(grep -E '^[0-9]+$' | sort -n || true)
  if [ -z "$values" ]; then echo NA; return; fi
  awk '{ v[NR] = $1 } END { if (NR % 2) print v[(NR + 1) / 2]; else print int((v[NR / 2] + v[NR / 2 + 1]) / 2) }' <<<"$values"
}

minus() {
  if [[ "$1" =~ ^[0-9]+$ && "$2" =~ ^[0-9]+$ ]]; then echo $(($1 - $2)); else echo NA; fi
}

plus() {
  if [[ "$1" =~ ^[0-9]+$ && "$2" =~ ^[0-9]+$ ]]; then echo $(($1 + $2)); else echo NA; fi
}

# The app's own log line matching $1, waiting up to $2 seconds; prints it or nothing.
wait_log() {
  local pattern=$1 limit=$2 line
  for _ in $(seq 1 $((limit * 10))); do
    line=$(adb logcat -d -v epoch -s LeanRows:I LeanRowsMeasure:I | grep -E "$pattern" | head -n 1 || true)
    if [ -n "$line" ]; then
      echo "$line"
      return
    fi
    sleep 0.1
  done
}

# Milliseconds between the measure marker and the app's line matching $1.
since_mark() {
  local line mark
  line=$(wait_log "$1" "$2")
  mark=$(adb logcat -d -v epoch -s LeanRowsMeasure:I | grep -m 1 "mark" || true)
  if [ -z "$line" ] || [ -z "$mark" ]; then echo NA; return; fi
  awk -v a="$(awk '{ print $1 }' <<<"$mark")" -v b="$(awk '{ print $1 }' <<<"$line")" 'BEGIN { printf "%d\n", (b - a) * 1000 }'
}

mark() {
  adb logcat -c
  adb shell log -t LeanRowsMeasure mark
}

ui_dump() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/ui.xml 2>/dev/null | tr -d '\r' || true
}

# The centre of the view with resource id $2 in the dump $1, as "x y".
centre_of() {
  grep -o "resource-id=\"$PKG:id/$2\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" <<<"$1" | head -n 1 |
    sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' |
    awk '{ print int(($1 + $3) / 2), int(($2 + $4) / 2) }' || true
}

# The number of the first row on screen, from the row views' descriptions.
# The dump waits for the screen to be idle, so it is tried a few times.
first_row() {
  local number
  for _ in 1 2 3; do
    number=$(grep -o 'content-desc="Row [0-9]*' <<<"$(ui_dump)" | head -n 1 | sed 's/.*Row //' || true)
    if [ -n "$number" ]; then
      echo "$number"
      return
    fi
    sleep 2
  done
}

# Objects allocated by the app since it started, from ART's SIGQUIT dump.
allocations() {
  if [ "$ROOT" != yes ]; then echo NA; return; fi
  local pid newest value
  pid=$(adb shell pidof "$PKG" | tr -d '\r' || true)
  if [ -z "$pid" ]; then echo NA; return; fi
  adb shell rm -f '/data/anr/*' >/dev/null 2>&1 || true
  adb shell kill -3 "$pid" || true
  for _ in $(seq 1 50); do
    newest=$(adb shell ls -t /data/anr 2>/dev/null | tr -d '\r' | head -n 1 || true)
    if [ -n "$newest" ]; then
      value=$(adb shell cat "/data/anr/$newest" 2>/dev/null | tr -d '\r' | grep -m 1 "Total number of allocations" | awk '{ print $NF }' || true)
      if [ -n "$value" ]; then echo "$value"; return; fi
    fi
    sleep 0.2
  done
  echo NA
}

# The empty app first, on the same emulator: its start time is subtracted.
EMPTY_FIRST=NA
if [ -n "$EMPTY_APK" ]; then
  adb install -r -d "$EMPTY_APK" >/dev/null
  values=""
  for _ in $(seq 0 "$STARTS"); do
    adb shell am force-stop "$EMPTY_PKG"
    total=$(adb shell am start-activity -W -n "$EMPTY_PKG/.EmptyActivity" | tr -d '\r' | sed -n 's/^TotalTime: *//p')
    values+="${total:-NA}"$'\n'
  done
  # The first start after install is a warm-up and is left out.
  EMPTY_FIRST=$(tail -n +2 <<<"$values" | median)
  log "empty app: first frame $EMPTY_FIRST ms (median of $STARTS)"
fi

declare -A FIRST_FRAME=() DRAWN=() PHASES=() PHASE_NAMES=()
for round in $(seq 1 "$ROUNDS"); do
  for label in "${LABELS[@]}"; do
    install "$label"
    open_document "$SMALL" "$SMALL_TYPE" >/dev/null # Warm-up after install.
    fully_drawn 10 >/dev/null
    for _ in $(seq 1 "$STARTS_PER_ROUND"); do
      total=$(open_document "$SMALL" "$SMALL_TYPE" | sed -n 's/^TotalTime: *//p')
      drawn=$(fully_drawn 10)
      log "round $round $label: first frame ${total:-NA} ms, drawn $drawn ms"
      # The app's own "phase NAME N ms" lines, from creation to each step.
      while read -r name value; do
        [ -n "$name" ] || continue
        PHASES["$label|$name"]+="$value"$'\n'
        PHASE_NAMES["$name"]=1
      done < <(adb logcat -d -v raw -s "${TIMING_TAG:-none}:I" | sed -n 's/^phase \(.*\) \([0-9][0-9]*\) ms$/\1 \2/p' | sed 's/ \([0-9][0-9]*\)$/|\1/' | tr ' ' '_' | tr '|' ' ')
      FIRST_FRAME[$label]+="${total:-NA}"$'\n'
      DRAWN[$label]+="$drawn"$'\n'
    done
  done
done

pss_of() {
  # The summary line reads "TOTAL PSS: 12345 ..." on current Android and the
  # table row "TOTAL 12345 ..." on older versions.
  adb shell dumpsys meminfo "$1" | tr -d '\r' |
    awk '/TOTAL PSS:/ { print $3; exit } /^ *TOTAL +[0-9]/ { print $2; exit }'
}

renderer_pss() {
  # WebView renderers are isolated processes; their PSS is read from the
  # system-wide "Total PSS by process" list rather than by pid.
  adb shell dumpsys meminfo | tr -d '\r' | awk '
    /Total PSS by process:/ { inside = 1; next }
    inside && /^ *$/ { inside = 0 }
    inside && /sandboxed_process/ { value = $1; gsub(/[,K:]/, "", value); total += value }
    END { print total + 0 }'
}

indexed_ms() {
  local limit=$1 line
  for _ in $(seq 1 $((limit * 10))); do
    line=$(adb logcat -d -s LeanRows:I | grep -o "indexed [0-9]* rows in [0-9]* ms" | head -n 1 || true)
    if [ -n "$line" ]; then
      awk '{ print $5 }' <<<"$line"
      return
    fi
    sleep 0.1
  done
  echo NA
}

sheet_ms() {
  local limit=$1 line
  for _ in $(seq 1 $((limit * 10))); do
    line=$(adb logcat -d -s LeanRows:I | grep -o "wrote sheet [0-9]* as [0-9]* CSV rows in [0-9]* ms" | head -n 1 || true)
    if [ -n "$line" ]; then
      awk '{ print $(NF - 1) }' <<<"$line"
      return
    fi
    sleep 0.1
  done
  echo NA
}

renderer_count() {
  adb shell ps -A -o NAME | tr -d '\r' | grep -c sandboxed_process || true
}

# Memory with the small file, to tell the WebView's own cost from the document's.
declare -A SMALL_APP=() SMALL_RENDERER=()
for label in "${LABELS[@]}"; do
  install "$label"
  open_document "$SMALL" "$SMALL_TYPE" >/dev/null
  fully_drawn 30 >/dev/null
  sleep 5
  SMALL_APP[$label]=$(pss_of "$PKG")
  SMALL_RENDERER[$label]=0
  if [ "$WEBVIEW" = yes ]; then SMALL_RENDERER[$label]=$(renderer_pss); fi
  log "$label small file: app ${SMALL_APP[$label]:-NA} KB, renderer ${SMALL_RENDERER[$label]} KB in $(renderer_count) renderer process(es)"
done

# The fixture provider writes generated files into its cache the first time.
open_document "$LARGE" "$LARGE_TYPE" >/dev/null
sleep 20

declare -A APP_PSS=() RENDERER_PSS=() TOTAL_PSS=() LARGE_DRAWN=() INDEXED=() JANK=() FRAMES=() P90=() SCROLL_ALLOCS=()
for label in "${LABELS[@]}"; do
  install "$label"
  for run in 1 2 3; do
    open_document "$LARGE" "$LARGE_TYPE" >/dev/null
    drawn=$(fully_drawn 90)
    indexed=NA
    if [ "$MEASURE_INDEXING" = yes ]; then indexed=$(indexed_ms 60); fi
    sleep 5
    app=$(pss_of "$PKG")
    renderer=0
    if [ "$WEBVIEW" = yes ]; then renderer=$(renderer_pss); fi
    total=$(plus "${app:-NA}" "$renderer")
    log "$label large run $run: drawn $drawn ms, indexed $indexed ms, app ${app:-NA} KB, renderer $renderer KB in $(renderer_count) process(es), total $total KB"
    LARGE_DRAWN[$label]+="$drawn"$'\n'
    INDEXED[$label]+="$indexed"$'\n'
    APP_PSS[$label]+="${app:-NA}"$'\n'
    RENDERER_PSS[$label]+="$renderer"$'\n'
    TOTAL_PSS[$label]+="$total"$'\n'
  done

  # Scrolling: ten swipes through the large file, as a reader would. adb input
  # goes to the window with focus. When the emulator's launcher has kept focus
  # in front of the app, the swipes would miss it, so nothing is reported.
  focus=$(adb shell dumpsys window | tr -d '\r' | grep -m 1 'mCurrentFocus=' || true)
  if ! grep -q "$PKG" <<<"$focus"; then
    log "$label scrolling not measured, another window has focus: $focus"
    FRAMES[$label]="not measured"
    JANK[$label]="not measured (app not in front)"
    P90[$label]=NA
    continue
  fi
  before=$(allocations)
  adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
  for _ in $(seq 1 10); do
    adb shell input swipe 540 1800 540 600 250
    sleep 0.5
  done
  gfx=$(adb shell dumpsys gfxinfo "$PKG" | tr -d '\r')
  after=$(allocations)
  SCROLL_ALLOCS[$label]=$(minus "$after" "$before")
  log "$label scrolling: $before objects allocated before the swipes, $after after"
  FRAMES[$label]=$(sed -n 's/^Total frames rendered: *//p' <<<"$gfx" | head -n 1)
  JANK[$label]=$(sed -n 's/^Janky frames: *//p' <<<"$gfx" | head -n 1)
  P90[$label]=$(sed -n 's/^90th percentile: *//p' <<<"$gfx" | head -n 1)
  log "$label scrolling: frames ${FRAMES[$label]:-NA}, janky ${JANK[$label]:-NA}, p90 ${P90[$label]:-NA}"
done

# Find, a system theme switch and a rotation with the large file open. Each
# runs on a fresh start of the app. NA means the step could not be driven.
declare -A FIND_NEXT=() FIND_PREVIOUS=() THEME_MS=() THEME_ROWS=() ROTATE_FRAMES=() ROTATE_JANK=() ROTATE_ROWS=() ROTATE_PSS=()
open_large() {
  open_document "$LARGE" "$LARGE_TYPE" >/dev/null
  fully_drawn 90 >/dev/null
  indexed_ms 60 >/dev/null
  sleep 2
}
adb shell settings put system accelerometer_rotation 0 >/dev/null 2>&1 || true
adb shell settings put system user_rotation 0 >/dev/null 2>&1 || true
adb shell cmd uimode night no >/dev/null 2>&1 || true
for label in "${LABELS[@]}"; do
  install "$label"

  # Find with no match: next walks the file forward once, previous backward.
  open_large
  dump=$(ui_dump)
  find_button=$(centre_of "$dump" find)
  if [ -n "$find_button" ]; then
    adb shell input tap $find_button
    sleep 1
    adb shell input text zzqxj
    sleep 1
    dump=$(ui_dump)
    next_button=$(centre_of "$dump" find_next)
    previous_button=$(centre_of "$dump" find_previous)
    if [ -n "$next_button" ] && [ -n "$previous_button" ]; then
      adb logcat -c
      adb shell input tap $next_button
      FIND_NEXT[$label]=$(wait_log "find next" 120 | grep -o "in [0-9]* ms" | awk '{ print $2 }' || true)
      adb logcat -c
      adb shell input tap $previous_button
      FIND_PREVIOUS[$label]=$(wait_log "find previous" 300 | grep -o "in [0-9]* ms" | awk '{ print $2 }' || true)
    fi
  fi
  log "$label find with no match: next ${FIND_NEXT[$label]:-NA} ms, previous ${FIND_PREVIOUS[$label]:-NA} ms"

  # The system switches to dark: from the switch until the rows are drawn again.
  open_large
  for _ in 1 2 3; do adb shell input swipe 540 1800 540 600 250; sleep 0.5; done
  # The fast scroller fades out first; until then the screen is not idle.
  sleep 3
  before=$(first_row)
  mark
  adb shell cmd uimode night yes >/dev/null
  THEME_MS[$label]=$(since_mark "rows drawn|theme applied" 60)
  sleep 2
  after=$(first_row)
  THEME_ROWS[$label]="${before:-NA} to ${after:-NA}"
  log "$label system dark mode: ${THEME_MS[$label]} ms, first row on screen ${THEME_ROWS[$label]}"
  adb shell cmd uimode night no >/dev/null
  sleep 3

  # Rotation to landscape: frames drawn, where the list is, and memory.
  open_large
  for _ in 1 2 3; do adb shell input swipe 540 1800 540 600 250; sleep 0.5; done
  # The fast scroller fades out first; until then the screen is not idle.
  sleep 3
  before=$(first_row)
  adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
  adb shell settings put system user_rotation 1
  sleep 4
  gfx=$(adb shell dumpsys gfxinfo "$PKG" | tr -d '\r')
  ROTATE_FRAMES[$label]=$(sed -n 's/^Total frames rendered: *//p' <<<"$gfx" | head -n 1)
  ROTATE_JANK[$label]=$(sed -n 's/^Janky frames: *//p' <<<"$gfx" | head -n 1)
  after=$(first_row)
  ROTATE_ROWS[$label]="${before:-NA} to ${after:-NA}"
  ROTATE_PSS[$label]=$(pss_of "$PKG")
  log "$label rotation: frames ${ROTATE_FRAMES[$label]:-NA}, janky ${ROTATE_JANK[$label]:-NA}, first row ${ROTATE_ROWS[$label]}, app ${ROTATE_PSS[$label]:-NA} KB"
  adb shell settings put system user_rotation 0
  sleep 3
done

# Sorting: a shuffled file of a million rows by its first column, in text
# order, through a tap on the column title. The time is the app's own line;
# PSS is sampled every second while it runs. A build without sorting shows NA.
SORT_FILE=file/sort/shuffled-1000000.csv
declare -A SORT_MS=() SORT_PEAK=() SORT_AFTER=()
open_document "$SORT_FILE" text/csv >/dev/null # The provider writes the file once.
fully_drawn 300 >/dev/null
for label in "${LABELS[@]}"; do
  install "$label"
  open_document "$SORT_FILE" text/csv >/dev/null
  fully_drawn 120 >/dev/null
  indexed_ms 120 >/dev/null
  sleep 2
  bounds=$(grep -o "resource-id=\"$PKG:id/header\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" <<<"$(ui_dump)" | head -n 1 |
    sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' || true)
  if [ -n "$bounds" ]; then
    read -r left top _ bottom <<<"$bounds"
    adb logcat -c
    # Past the row numbers, in the first column.
    adb shell input tap $((left + 300)) $(((top + bottom) / 2))
    if [ -n "$(wait_log "sort started" 10)" ]; then
      peak=0
      for _ in $(seq 1 600); do
        line=$(adb logcat -d -s LeanRows:I | grep -o "sorted [0-9]* rows by column [0-9]* in [0-9]* ms" | head -n 1 || true)
        if [ -n "$line" ]; then
          SORT_MS[$label]=$(awk '{ print $(NF - 1) }' <<<"$line")
          break
        fi
        pss=$(pss_of "$PKG")
        if [[ "$pss" =~ ^[0-9]+$ ]] && [ "$pss" -gt "$peak" ]; then peak=$pss; fi
        sleep 1
      done
      SORT_PEAK[$label]=$peak
      sleep 5
      SORT_AFTER[$label]=$(pss_of "$PKG")
    fi
  fi
  log "$label sort of a million rows: ${SORT_MS[$label]:-NA} ms, peak PSS while sorting ${SORT_PEAK[$label]:-NA} KB, after ${SORT_AFTER[$label]:-NA} KB"
done

# The theme button and the table zoom, with the large file open. A build
# without them shows NA. Each ends where it started (System, 100%).
declare -A THEME_TAP=() ZOOM_STEP=()
for label in "${LABELS[@]}"; do
  install "$label"
  open_large
  dump=$(ui_dump)
  theme_button=$(centre_of "$dump" theme)
  if [ -n "$theme_button" ]; then
    adb shell input tap $theme_button # System to Light
    sleep 2
    adb logcat -c
    adb shell input tap $theme_button # Light to Dark
    THEME_TAP[$label]=$(wait_log "theme applied" 20 | grep -o "in [0-9]* ms" | awk '{ print $2 }' || true)
    for _ in 1 2 3; do adb shell input tap $theme_button; sleep 1; done # Dim, Dusk, System
  fi
  settings_button=$(centre_of "$dump" settings)
  if [ -n "$settings_button" ]; then
    adb shell input tap $settings_button
    sleep 2
    dump=$(ui_dump)
    zoom_in=$(centre_of "$dump" zoom_in)
    zoom_reset=$(centre_of "$dump" zoom_reset)
    if [ -n "$zoom_in" ]; then
      adb logcat -c
      adb shell input tap $zoom_in
      ZOOM_STEP[$label]=$(wait_log "zoom 110% applied" 20 | grep -o "in [0-9]* ms" | awk '{ print $2 }' || true)
      sleep 1
      if [ -n "$zoom_reset" ]; then adb shell input tap $zoom_reset; fi
    fi
    adb shell input keyevent BACK
  fi
  log "$label theme button to Dark ${THEME_TAP[$label]:-NA} ms, zoom to 110% ${ZOOM_STEP[$label]:-NA} ms"
done

# The workbook: time to write the sheet as CSV, time until its rows are drawn
# after a cold start, and memory. A build without .xlsx support shows NA.
declare -A SHEET_MS=() SHEET_DRAWN=() SHEET_PSS=()
if [ -n "${WORKBOOK:-}" ]; then
  for label in "${LABELS[@]}"; do
    install "$label"
    for run in 1 2 3; do
      open_document "$WORKBOOK" "$WORKBOOK_TYPE" >/dev/null
      drawn=$(fully_drawn 120)
      written=$(sheet_ms 5)
      sleep 5
      app=$(pss_of "$PKG")
      log "$label workbook run $run: sheet written in $written ms, drawn $drawn ms, app ${app:-NA} KB"
      SHEET_MS[$label]+="$written"$'\n'
      SHEET_DRAWN[$label]+="$drawn"$'\n'
      SHEET_PSS[$label]+="${app:-NA}"$'\n'
    done
  done
fi

row() {
  local name=$1; shift
  printf '| %s |' "$name"
  for value in "$@"; do printf ' %s |' "${value:-NA}"; done
  printf '\n'
}

{
  printf '| Measure |'; for label in "${LABELS[@]}"; do printf ' %s |' "$label"; done; printf '\n'
  printf '| --- |'; for _ in "${LABELS[@]}"; do printf ' ---: |'; done; printf '\n'
  sizes=(); first=(); first_net=(); drawn=(); drawn_net=(); large=(); indexed=(); app=(); renderer=(); total=(); frames=(); jank=(); p90=()
  for label in "${LABELS[@]}"; do
    sizes+=("$(stat -c %s "${APKS[$label]}")")
    f=$(median <<<"${FIRST_FRAME[$label]}")
    d=$(median <<<"${DRAWN[$label]}")
    first+=("$f"); first_net+=("$(minus "$f" "$EMPTY_FIRST")")
    drawn+=("$d"); drawn_net+=("$(minus "$d" "$EMPTY_FIRST")")
    large+=("$(median <<<"${LARGE_DRAWN[$label]}")")
    indexed+=("$(median <<<"${INDEXED[$label]}")")
    app+=("$(median <<<"${APP_PSS[$label]}")")
    renderer+=("$(median <<<"${RENDERER_PSS[$label]}")")
    total+=("$(median <<<"${TOTAL_PSS[$label]}")")
    frames+=("${FRAMES[$label]:-NA}")
    jank+=("${JANK[$label]:-NA}")
    p90+=("${P90[$label]:-NA}")
  done
  row "Release APK, all ABIs (bytes)" "${sizes[@]}"
  row "Cold start to first frame, $SMALL_NAME (ms, median of $STARTS)" "${first[@]}"
  row "**First frame minus the empty app** (ms)" "${first_net[@]}"
  row "Cold start to $DRAWN_WHAT drawn, $SMALL_NAME (ms, median of $STARTS)" "${drawn[@]}"
  row "**Drawn minus the empty app's first frame** (ms)" "${drawn_net[@]}"
  row "$LARGE_NAME drawn after a cold start (ms, median of 3)" "${large[@]}"
  if [ "$MEASURE_INDEXING" = yes ]; then row "Indexing all rows of $LARGE_NAME (ms, median of 3)" "${indexed[@]}"; fi
  small_app=(); small_renderer=(); small_total=()
  for label in "${LABELS[@]}"; do
    small_app+=("${SMALL_APP[$label]:-NA}")
    small_renderer+=("${SMALL_RENDERER[$label]:-NA}")
    small_total+=("$(plus "${SMALL_APP[$label]:-NA}" "${SMALL_RENDERER[$label]:-0}")")
  done
  if [ "$WEBVIEW" = yes ]; then
    row "Total PSS after $SMALL_NAME, app and renderer (KB)" "${small_total[@]}"
  else
    row "App process PSS after $SMALL_NAME (KB)" "${small_app[@]}"
  fi
  row "App process PSS after $LARGE_NAME (KB, median of 3)" "${app[@]}"
  if [ "$WEBVIEW" = yes ]; then
    row "WebView renderer PSS after $LARGE_NAME (KB, median of 3)" "${renderer[@]}"
    row "**Total PSS, app and renderer** (KB, median of 3)" "${total[@]}"
  fi
  row "Frames over 10 swipes (software GPU, not a budget check)" "${frames[@]}"
  row "Janky frames over 10 swipes (software GPU, not a budget check)" "${jank[@]}"
  row "90th percentile frame time (software GPU, not a budget check)" "${p90[@]}"
  allocs=(); find_next=(); find_previous=(); theme_ms=(); theme_rows=(); rotate_frames=(); rotate_jank=(); rotate_rows=(); rotate_pss=()
  for label in "${LABELS[@]}"; do
    allocs+=("${SCROLL_ALLOCS[$label]:-NA}")
    find_next+=("${FIND_NEXT[$label]:-NA}")
    find_previous+=("${FIND_PREVIOUS[$label]:-NA}")
    theme_ms+=("${THEME_MS[$label]:-NA}")
    theme_rows+=("${THEME_ROWS[$label]:-NA}")
    rotate_frames+=("${ROTATE_FRAMES[$label]:-NA}")
    rotate_jank+=("${ROTATE_JANK[$label]:-NA}")
    rotate_rows+=("${ROTATE_ROWS[$label]:-NA}")
    rotate_pss+=("${ROTATE_PSS[$label]:-NA}")
  done
  row "Objects allocated over the 10 swipes (ART count)" "${allocs[@]}"
  row "Find next, no match, $LARGE_NAME (ms)" "${find_next[@]}"
  row "Find previous, no match, $LARGE_NAME (ms)" "${find_previous[@]}"
  row "System switch to dark until rows are drawn again (ms)" "${theme_ms[@]}"
  row "First row on screen before and after the dark switch" "${theme_rows[@]}"
  row "Frames drawn while rotating to landscape" "${rotate_frames[@]}"
  row "Janky frames while rotating (software GPU, not a budget check)" "${rotate_jank[@]}"
  row "First row on screen before and after rotating" "${rotate_rows[@]}"
  row "App process PSS after rotating (KB)" "${rotate_pss[@]}"
  sort_ms=(); sort_peak=(); sort_after=()
  for label in "${LABELS[@]}"; do
    sort_ms+=("${SORT_MS[$label]:-NA}")
    sort_peak+=("${SORT_PEAK[$label]:-NA}")
    sort_after+=("${SORT_AFTER[$label]:-NA}")
  done
  theme_tap=(); zoom_step=()
  for label in "${LABELS[@]}"; do
    theme_tap+=("${THEME_TAP[$label]:-NA}")
    zoom_step+=("${ZOOM_STEP[$label]:-NA}")
  done
  row "Theme button, Light to Dark, until the next frame (ms)" "${theme_tap[@]}"
  row "Zoom 100% to 110%, until the next frame (ms)" "${zoom_step[@]}"
  row "Sorting 1,000,001 shuffled rows by the first column, text (ms)" "${sort_ms[@]}"
  row "Peak app PSS while sorting, sampled each second (KB)" "${sort_peak[@]}"
  row "App PSS after the sort (KB)" "${sort_after[@]}"
  if [ -n "${WORKBOOK:-}" ]; then
    sheet=(); sheet_drawn=(); sheet_pss=()
    for label in "${LABELS[@]}"; do
      sheet+=("$(median <<<"${SHEET_MS[$label]}")")
      sheet_drawn+=("$(median <<<"${SHEET_DRAWN[$label]}")")
      sheet_pss+=("$(median <<<"${SHEET_PSS[$label]}")")
    done
    row "Writing the sheet of $WORKBOOK_NAME as CSV (ms, median of 3)" "${sheet[@]}"
    row "$WORKBOOK_NAME rows drawn after a cold start (ms, median of 3)" "${sheet_drawn[@]}"
    row "App process PSS after $WORKBOOK_NAME (KB, median of 3)" "${sheet_pss[@]}"
  fi
  if [ "${#PHASE_NAMES[@]}" -gt 0 ]; then
    echo
    echo "Where the time goes when $SMALL_NAME is opened: milliseconds from the activity's"
    echo "creation to each step, as the app logs them (median of $STARTS)."
    echo
    printf '| Step |'; for label in "${LABELS[@]}"; do printf ' %s |' "$label"; done; printf '\n'
    printf '| --- |'; for _ in "${LABELS[@]}"; do printf ' ---: |'; done; printf '\n'
    for name in "webview_created" "page_loaded" "reader_ready" "document_sent" "drawn"; do
      [ -n "${PHASE_NAMES[$name]:-}" ] || continue
      printf '| %s |' "${name//_/ }"
      for label in "${LABELS[@]}"; do printf ' %s |' "$(median <<<"${PHASES[$label|$name]:-}")"; done
      printf '\n'
    done
  fi
  echo
  echo "Empty app on the same emulator: $EMPTY_FIRST ms to first frame (median of $STARTS)."
  echo "Start time and jank on this emulator are not the budget check: it has no GPU,"
  echo "and an empty app alone takes the time above. The budget needs a real phone;"
  echo "see the phone test in PROGRESS.md."
}
