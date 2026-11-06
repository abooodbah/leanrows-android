# Progress

Updated with every merge. The next session starts here.

## Plan for 0.2.0

Work in this order, one pull request per change, each merged when CI is
green. Tests first: the failing tests go in their own commit before the
change.

1. Efficiency review. Extend the budgets job (`scripts/measure.sh`) so it
   also measures Find, a system theme switch, a rotation and allocations
   while scrolling, on release builds. Write the findings into the table
   below. Fix the clear wins, one per pull request, with before and after
   numbers. List the rest for the maintainer.
2. Column sorting, ported from desktop LeanRows 0.4.0 (see below).
   - Sort engine first (JVM tests): text and numeric order with the
     desktop's rules, ties in source order, bounded memory, temporary files
     in the app's cache. Unlike desktop 0.4.0, the file is read once from
     start to end (no read per row) and runs are joined by a multi-way
     merge (not two at a time). Measured on 10 million rows.
   - Then the screen: tap a column title to sort, tap again to reverse,
     long-press it for text or numeric order, tap Row to restore the
     source order, Cancel while it runs. Row numbers, Find and Go to row
     keep the file's own numbers. A free-space check before a large sort.
     An .xlsx sheet is not sorted; the app says so. Emulator tests first.
3. The five themes of LeanMark 0.6.0 (System, Light, Dark, Dim, Dusk) with
   its exact colours, the theme button with its coloured dot and a tooltip
   that names the theme, and a settings button that opens a sheet with
   theme and zoom. Zoom enlarges the table only. Both are remembered.
4. Version 0.2.0: version bump pull request, annotated tag v0.2.0, a GitHub
   pre-release with plain notes, the "Release files" workflow for the APK,
   AAB and .sha256 files, and the "Play bundle" workflow. Nothing is
   uploaded to Google Play.

## Desktop features the app lacks

Desktop releases after 2026-10-29: only 0.4.0 (2026-11-02), column
sorting. 0.3.0 (2026-10-29, .xlsx) is already in the app since 0.1.1.

| Desktop 0.4.0 feature | On Android |
| --- | --- |
| Click a column header to sort the whole file ascending, click again to reverse | Ported: tap a column title |
| Right-click a header for text or numeric order; the same commands in the overflow menu | Ported: long-press a column title, and the More menu |
| Click the Row header, or Restore source order, to go back | Ported: tap Row, or More > Restore source order |
| Cancel sort keeps the view you had | Ported: Cancel next to the progress, and in More |
| Text order by bytes, case sensitive; exact numeric order (integers, decimals, exponents); numbers, then text, then empty values in both directions; ties keep the file's order | Ported with the desktop's test cases |
| Row numbers, Find and Go to row keep the original numbers | Ported |
| 16 MiB sort memory, temporary files in the app's data folder, removed when done and after a crash | Ported: 8 MiB, files in the app's cache, unlinked as soon as they are open so nothing is left behind |
| A sort that will not fit on the drive or in the 8 GiB limit is refused before it starts | Ported: checked against the free space of the app's cache |
| Sorting a .xlsx workbook says it does not work yet | Ported (the app could sort the sheet's CSV copy, but keeps the desktop rule) |
| One sort at a time across tabs | Skipped: the app has one file open at a time |
| Sort commands in the overflow menu and keyboard access | The More menu has them; no keyboard shortcuts on a phone |

Skipped from earlier desktop releases, and why: tabs and several open
files (0.2.0; Android shows one file per window, and the system's recent
apps list already switches between files), keyboard shortcuts (no
keyboard on most phones), the installer and Explorer registration
(Android uses the open-with filters in the manifest).

## Efficiency review

Measured on release builds by the budgets job (API 34 emulator, no GPU),
CI run 41982387830 (PR #14) unless a row says otherwise; "main" is the 0.1.2
code with only the new log lines.

| Place | What it costs | Measured (main) | Proposed fix |
| --- | --- | --- | --- |
| Find previous (`Finder.last`) | Steps back one checkpoint section (about 32 rows) at a time, reads a whole 64 KiB chunk and allocates 576 KiB for each, and when it wraps it searches the whole file again | 2,654 and 3,036 ms with no match in large-multiline.csv, against 364 and 399 ms for Find next; on the JVM it read 408 MB of the 9.6 MB file | Fixed in #19: steps back at least 1 MiB at a time, reuses the buffers, searches only the rest of the file when wrapping. 2,224 to 190 ms (run 41987717205) |
| System light or dark switch (manifest `configChanges` lacks `uiMode`) | The activity is recreated: the file is opened and indexed again and the list goes back to row 1 (an .xlsx sheet is written out again) | 424 and 566 ms until the rows are drawn again, and the place in the file is lost | Fixed with the five themes (#20): `uiMode` is handled and the colours change in place; an emulator test checks the same activity keeps the file and the place |
| APK: Kotlin metadata (`kotlin/*.kotlin_builtins`) and `DebugProbesKt.bin` | Files only kotlin-reflect and coroutine debugging read; the app uses neither | 9 entries, 12,835 bytes stored | Fixed in #15: excluded when packaging. APK 217,221 to 203,088 bytes (run 41986249081) |
| APK: `resources.arsc` | androidx.core brings strings in many languages; the app is in English | 28,420 bytes | Fixed in #16: English resources only. APK 217,221 to 200,241 bytes (run 41984977500) |
| Scrolling | A content description string per bound row and one ellipsized string per cell | 4,490 to 4,600 objects allocated over 10 swipes through 100,001 rows in one run, 5 in another, so the count is not reliable yet | None: small, and needed for TalkBack |
| Rotation | No recreate (`configChanges` covers it); one layout pass | 3 frames; PSS 26.7 MB after, 25.2 MB before | None |
| Open | Cold start to first frame and to rows | first frame 51 ms over the empty app, rows drawn 354 to 378 ms over it | None: under the 1 s budget |
| Indexing (`Table.scan`) | Reads 64 KiB at a time | 178 to 201 ms for 100,001 rows | None now; a larger chunk is a small gain, left for the maintainer |
| Row reads (`Table.rows`) | Allocates a 64 KiB and a 4 KiB buffer per 128-row window | part of the 4,600 objects above | None: small |
| Dead code | `Format.label` and `Format.delimited` are unused | R8 removes them from the APK | Left as is; no size cost |
| Dependencies | Only androidx.recyclerview, which brings androidx.core, collection and customview; R8 keeps only what is used | classes.dex 159,879 bytes stored | None |

Zoom did not exist before 0.2.0; it is measured with the settings sheet.

## Done

- 0.2.0 work so far: the budgets job also measures Find, a system theme
  switch, rotation and allocations (#14); Kotlin metadata left out of the
  APK (#15); English resources only (#16); the sort engine (#17), with the
  desktop's order and test cases, one read of the file and a multi-way
  merge: 10,000,000 rows in 9.7 s (text) and 12.5 s (numbers) on the JVM
  with a 40 MiB heap.
- Find previous reads the file about once (#19): 2,224 to 190 ms with no
  match in 100,001 rows on the emulator.
- Sorting on the screen (#18): tap a title, tap again to reverse, long-press
  for numbers, tap Row for the file's order, More menu, Cancel, a space
  check, no sorting for .xlsx. A million shuffled rows sort in 1,490 ms on
  the emulator (release build, run 41988225938); peak PSS 57,846 KB while
  sorting, 27,262 KB after.
- The five themes of LeanMark 0.6.0, the theme dot and the settings sheet
  with theme and table zoom (#20). A system switch to dark recolours in
  place and keeps the file and the place.
  Theme button 15 ms, zoom step 16 ms, system dark switch 207 to 33 ms
  (run 42051398128).

- Gradle project: Kotlin, classic Views. One library: androidx.recyclerview
  1.4.0 for the table (it brings androidx.core and androidx.collection; the
  release APK is 204 KB). compileSdk and targetSdk 36, minSdk 26.
- Manifest: no permissions (the one androidx.core adds is removed and a test
  checks the merged manifest); open-with filters for CSV, TSV, JSON Lines and
  log files (VIEW and SEND); backup off.
- Table (PR #2): a port of the desktop row engine (record scanner, fields,
  cell text, adaptive row index), checked against the desktop engine's own
  output for 20 inputs. Files are read with pread where the provider gives a
  real file; a pipe is copied to the cache first and removed as soon as it
  is open. Rows are read in 128-row windows around what is on screen, three
  windows kept. Find (with Match case), Go to row, a cell dialog with copy,
  sideways panning with the row numbers fixed, a fast scroller, rotation.
- CI: lint, JVM tests, release APK and bundle, the permission check,
  instrumented tests on API 26 and API 34, budgets against the base branch
  and an empty app, APK contents and the arm64 download size. Mutation check:
  `mutations.yml` with `mutations/*.json`.
- Excel .xlsx (PR #4, v0.1.1): the chosen sheet is written as CSV into the
  cache and shown by the row engine, with columns A, B, C and a Sheet button
  for the other visible sheets. The rules are in `docs/XLSX.md`, shared with
  desktop LeanRows. The zip is read through its central directory and each
  part is counted while it is unpacked; DOCTYPE is refused. Test files come
  from LibreOffice, XlsxWriter and openpyxl, and the expected rows from
  `tools/xlsx-fixtures/oracle.py`, a second implementation.
- Store texts, data safety, content rating, closed-test plan and light and
  dark screenshots in `store/`; signing steps in the README.

## In progress

- The first frame was about 100 ms later than main since #20. Bisected on
  the budgets job: the settings button's vector icon. It is now drawn in
  code (this pull request).

- Release v0.2.0: this version bump, then the tag, the pre-release and
  the release files.

## Remaining

- The phone test below, on real hardware.
- Play Console setup and the closed test (see `store/closed-test.md`);
  these need the maintainer's account.

## Mutation checks

Device mutations for sorting (workflow run 41988605064), all caught:

| Mutation | File | First failing test | Failing tests |
| --- | --- | --- | ---: |
| file:// URIs are read | `MainActivity.kt` | TableTest.fileUrisAreRefused | 1 |
| Stream copies are left in the cache | `Sources.kt` | TableTest.aStreamWithoutASizeIsCopiedThenShown | 1 |
| Sideways drags are not taken | `TableViews.kt` | TableTest.aSidewaysDragMovesTheColumnsButNotTheRowNumbers | 1 |
| Match case is ignored | `MainActivity.kt` | TableTest.findJumpsToTheNextMatchingRow | 1 |
| The unterminated quote is not reported | `MainActivity.kt` | TableTest.anUnterminatedQuoteIsReported | 1 |
| A second tap does not reverse the sort | `MainActivity.kt` | SortTableTest.tappingATitleSortsTheWholeFileAndTappingAgainReverses | 1 |
| Tapping Row does not restore the file's order | `MainActivity.kt` | SortTableTest.aLongPressOffersNumericOrderAndTappingRowRestoresTheFile | 1 |
| Workbooks are sorted | `MainActivity.kt` | SortTableTest.aWorkbookIsNotSortedAndSaysSo | 1 |
| Sorted rows show their place, not their number | `TableViews.kt` | SortTableTest.aMillionRowsSortInBoundedMemoryAndStaySortedWhenRotated | 4 |
| Find shows the file row's place, not the sorted one | `MainActivity.kt` | SortTableTest.findAndGoToRowUseTheFilesOwnNumbersInASortedView | 1 |
| Go to row ignores the sort | `MainActivity.kt` | SortTableTest.findAndGoToRowUseTheFilesOwnNumbersInASortedView | 1 |
| No space check before a sort | `MainActivity.kt` | SortTableTest.aSortThatWouldNotFitIsRefusedBeforeItStarts | 1 |
| The long-press menu sorts as text | `MainActivity.kt` | SortTableTest.aLongPressOffersNumericOrderAndTappingRowRestoresTheFile | 1 |

Theme mutations: all caught (run 41989890809, see #20).

## Known gaps

- .xls, .xlsm and .xlsb are not supported. Number formats other than dates
  and times are not applied, so 12.5% shows as 0.125.
- No test workbook was written by Excel itself, and none has phonetic text
  (used with Japanese), because none of the writers used makes it.
- A sheet is written out in full before it is shown: about 3 s for 100,000
  rows on the emulator.
- Columns are named Column 1, Column 2 and so on, and the first line is
  shown as a row, as in the desktop app; there is no header detection.
- At most 64 fields of 1,024 bytes are shown per record, from its first
  4 KiB, as in the desktop app; longer cells end with " ...".
- Release builds from CI are signed with a throwaway key that changes with
  every run, so one release cannot be installed over another; uninstall
  first.
- The emulator has no GPU, so start time and jank from CI are not the
  budget check.
- Sorting does not work on .xlsx sheets yet, as on the desktop; the app says
  so. A number with an exponent past about 9.2e18, such as 1e99999999999999999999,
  makes a numeric sort stop with a message, as on the desktop.
- Peak PSS while sorting a million rows is about 32 MB over the file's own
  (the runtime grows its heap for the 8 MiB of sort buffers); it falls back
  after the sort. Not yet measured for ten million rows on a phone.
- A file that changes while it is sorted is not detected (the desktop
  checks its size and time); the app reads one open copy of it.
- The store screenshots in `store/` still show the 0.1 toolbar.
- The allocation count over the swipes in the budgets job is not reliable
  yet (4,600 objects in some runs, 5 in others).

## Measured numbers

0.2.0 against 0.1.2, release builds, budgets job on the API 34 emulator.
"0.1.2" is main in the run of #14 (41982387830).

| Measure | 0.1.2 | 0.2.0 |
| --- | ---: | ---: |
| Release APK, all ABIs (bytes) | 217,221 | 216,660 |
| First frame minus the empty app (ms) | 51 | 157 |
| Rows drawn minus the empty app (ms) | 354 | 475 |
| PSS after large-multiline.csv (KB) | 25,297 | 26,242 |
| Find previous, no match, 100,001 rows (ms) | 2,654 | 190 to 221 |
| System switch to dark until rows drawn (ms) | 424 | 33 |
| Sort 1,000,001 rows, text (ms) | NA | 1,091 to 1,490 |
| Peak PSS while sorting 1,000,001 rows (KB) | NA | 57,812 |

The 0.2.0 column is from the themes run (42051398128), sort and Find from
runs 41988225938 and 41987717205. The first frame is about 100 ms later than
0.1.2; the app's own log puts its part (process start to first draw) at
100 to 170 ms, so the cause is outside the app's code and not found yet.

Earlier numbers:

From the budgets job on an API 34 emulator (SwiftShader, no GPU), PR #2,
run 40841223895. The empty app took 1,484 ms to its first frame there.

| Measure | Value |
| --- | ---: |
| Release APK, all ABIs | 204,053 bytes |
| First frame minus the empty app, weather-stations.csv | -62 ms (no slower than the empty app) |
| Rows drawn minus the empty app's first frame, weather-stations.csv | 726 ms |
| large-multiline.csv (100,001 rows) drawn after a cold start | 2,474 ms |
| Indexing all 100,001 rows | 461 ms |
| App PSS after weather-stations.csv | 22,575 KB |
| App PSS after large-multiline.csv | 26,935 KB |
| Janky frames over 10 swipes (software GPU, not a budget check) | 67 of 68 |

For .xlsx, PR #4, run 40876588303 (empty app 704 ms to its first frame):

| Measure | Value |
| --- | ---: |
| Release APK, all ABIs | 217,293 bytes |
| Writing the sheet of large-weather.xlsx (100,001 rows) as CSV | 3,138 ms |
| large-weather.xlsx rows drawn after a cold start | 3,716 ms |
| App PSS after large-weather.xlsx | 24,339 KB |

## Phone test

The emulator in CI has no GPU, so its start times and jank do not show what
a phone does. This is the check to run on a real phone. It takes about ten
minutes.

### Install

From the release page on GitHub, download onto the phone:

- `LeanRows-0.2.0.apk`: the release build (minified), signed with a throwaway
  key. Use this one for timing.
- `LeanRows-0.2.0-debug.apk`: the debug build, for comparison only. It is slower
  by design.
- `empty-app.apk`: an app with one empty screen, for the start-time
  baseline with adb (it has no launcher icon).
- `LeanRows-0.2.0-phone-test-files.zip`: the files below. Unzip it into
  Downloads.

Open the APK from the browser's downloads or the Files app and allow the
install when Android asks. Both APKs have the same package name, so the
second replaces the first. To check a download, compare it with its
`.sha256` file.

### Start time

1. Settings > Apps > LeanRows > Force stop.
2. Start a screen recording (Quick Settings > Screen record), then in the
   Files app tap `weather-stations.csv` in Downloads and choose LeanRows.
3. Stop the recording when the rows appear. Step through the recording frame
   by frame and note the time from the tap until the rows are on screen.
4. Repeat steps 1 to 3 three times and keep the middle value.
5. Do the same with an app that does very little (for example the
   Calculator), and subtract its time: that difference is the number the
   budget means. The budget is under 1 second.

With a computer and adb the system measures it:

```sh
# Start from the launcher icon to the first frame (TotalTime, in ms):
adb shell am force-stop io.github.abooodbah.leanrows
adb shell am start-activity -W -n io.github.abooodbah.leanrows/.MainActivity

# Opening weather-stations.csv: force-stop, clear the log, open the file from the
# Files app as in step 2, then read the time the app reports when the
# content is on screen:
adb shell am force-stop io.github.abooodbah.leanrows
adb logcat -c
adb logcat -d | grep "Fully drawn io.github.abooodbah.leanrows"
```

For the baseline, install `empty-app.apk` from the same release (one
empty screen, no libraries; the same app CI subtracts) and time it the same
way:

```sh
adb install empty-app.apk
adb shell am force-stop io.github.abooodbah.leanrows.baseline
adb shell am start-activity -W -n io.github.abooodbah.leanrows.baseline/.EmptyActivity
```

### Scrolling

1. Settings > System > Developer options > Profile HWUI rendering > On
   screen as bars. (Developer options appear after tapping Build number
   seven times in Settings > About phone.)
2. Open `large-multiline.csv`. Flick down through large-multiline.csv for about 10 seconds, then drag the fast scroller from top to bottom and back, then drag the table sideways a few times.
3. Note how often bars cross the green line, and whether anything stutters
   or shows blank areas.

With adb: `adb shell dumpsys gfxinfo io.github.abooodbah.leanrows reset`, scroll,
then `adb shell dumpsys gfxinfo io.github.abooodbah.leanrows` and note "Janky
frames" and "90th percentile".

### Memory

After scrolling `large-multiline.csv`: `adb shell dumpsys meminfo io.github.abooodbah.leanrows`
(TOTAL PSS).

### Excel workbook

1. Force stop LeanRows, then open `large-weather.xlsx` from the Files app.
   Note the time from the tap until the rows are on screen, as for the start
   time above. The status line shows "Reading sheet" while it is written out.
2. Open `features.xlsx`. The Sheet button lists Readings and Notes (the
   hidden sheet Lookup is not offered). Check that dates show as 2026-01-01.

With adb, after opening the workbook: `adb logcat -d -s LeanRows:I` shows
"wrote sheet 1 as 100001 CSV rows in N ms".

### Sorting

1. Open `large-multiline.csv`. Tap the title "Column 1". The status line
   shows the progress; note how long it takes until it says "sorted by
   Column 1, text, ascending". Tap the title again: the order reverses.
2. Long-press "Column 3" and choose Sort numbers ascending. Tap Row: the
   file's own order comes back.
3. Sort again and tap Cancel while it runs: the order you had stays.

With adb: `adb logcat -d -s LeanRows:I` shows "sorted N rows by column C in
M ms".

### Themes

Tap the dot in the toolbar five times: System, Light, Dark, Dim, Dusk and
back. Open the settings (the sliders button), pick a theme, and zoom the
table in and out. Close and open the app: both are kept.

### What to send back

Phone model, Android version, the three start times and the empty-app time,
what the scrolling bars looked like, the workbook time, the sort time, and
the memory numbers if you used adb.
