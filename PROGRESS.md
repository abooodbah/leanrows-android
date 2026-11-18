# Progress

Updated with every merge. The next session starts here.

## Plan for 0.3.0

Started 2026-11-18. The app is at 0.2.0 (2026-11-06). Work in this order,
one pull request per change, each merged when CI is green. Tests first: the
failing tests go in their own commit before the change. The budgets job
measures every pull request against main on the same emulator.

1. This plan.
2. Copy row: the cell dialog gets a Copy row button that copies the row
   number and every cell, separated by tabs, as desktop `Ctrl+C` copies a
   row. The app has no row copy yet, and desktop 0.6.0 leaves hidden columns
   out of copies, so this comes first.
3. Hide columns, from desktop 0.6.0 (see below). Long press on a column
   title keeps its sort orders and gains Hide column and Show all columns.
   The More menu gets Hide column, which lists the shown columns, and Show
   all columns.
4. Zoom, from desktop 0.5.1: each column keeps the width it was sized at,
   so zooming in and out does not make widths drift, and a zoom step
   rebinds the rows on screen in one layout pass instead of dropping and
   rebuilding every row view. The budgets job measures a run of zoom steps
   with `dumpsys gfxinfo` on main and the branch.
5. Build provenance attestations in `release.yml`, as the desktop repos got
   on 2026-11-18, and a README line on `gh attestation verify`.
6. Version 0.3.0: the version bump pull request. This session cannot push
   tags or create releases, so the tag, the release files and the Play
   bundle run are left for the maintainer.

The two open items the 0.2.0 efficiency review found are already fixed and
merged in 0.2.0: Find previous (#19) and the system light or dark switch
(#20). This plan's own budgets run measures both again on main, and the
numbers go in "Measured numbers" below. The review's other rows need no
fix, except a larger read chunk for indexing, which it called a small gain
and left for the maintainer; it is not in this plan.

## Desktop changes since 0.2.0

Desktop releases after 2026-11-06, from the desktop `CHANGELOG.md` and
`docs/releases/`: 0.5.1 (2026-11-06), 0.6.0 (2026-11-14) and 0.6.1
(2026-11-15), and on 2026-11-18 build provenance attestations for the
release files. 0.5.0 (2026-11-05) came before; its themes, settings panel
and zoom match what Android 0.2.0 took from LeanMark 0.6.0, and its faster
sort (many runs merged at once, the reverse map written in sequence) was
already how the Android sort engine works.

| Desktop | Change | On Android |
| --- | --- | --- |
| 0.6.0 | Hide column from the header's right-click menu; Show all columns | Ported: long press on a column title (which keeps the sort orders) gains Hide column and Show all columns |
| 0.6.0 | More menu: Hide column submenu of the shown columns, Show all columns | Ported: More > Hide column… opens a list of the shown columns; More > Show all columns |
| 0.6.0 | The file is not changed; nothing is saved; a reload, another file or another sheet shows every column | Ported |
| 0.6.0 | Hidden columns are left out of `Ctrl+C` copies | Ported with Copy row in the cell dialog (step 2), which leaves hidden columns out |
| 0.6.0 | Hidden columns are left out of sideways scrolling | Ported: the table pans over the shown columns only |
| 0.6.0 | The last shown column cannot be hidden, so one-column Preview views have nothing to hide | Ported |
| 0.6.0 | A sorted column that is hidden keeps its order; the status marks it as hidden | Ported |
| 0.6.0 | Find still searches whole rows and the status says so while a result is shown | Ported |
| 0.6.0 | CSV, TSV and `.xlsx` sheets; sheet columns keep their letters | Ported |
| 0.6.0 | Rows on screen skip decoding hidden fields | Partly: hidden cells are not cut to width or drawn. They are still read and decoded with the row, which the desktop skips; the app holds a few hundred rows at a time, and Copy row and Show all columns need them |
| 0.6.0 | Accessible row names leave hidden columns out | Ported: the row's content description leaves them out |
| 0.6.0 | Each tab keeps its own hidden columns | Not needed: one file per window |
| 0.5.1 | A zoom step changes only the rows and their titles and paints once | Ported: rows are rebound in place in one layout pass; measured with `dumpsys gfxinfo` |
| 0.5.1 | Ctrl+wheel notches already queued are one zoom change | Not needed: zoom is a button in the settings sheet, one step per tap |
| 0.5.1 | Column widths do not drift when zooming in and out; a width set by hand becomes the basis | Ported for drift. Widths cannot be set by hand on Android |
| 0.5.1 | A file opened while zoomed gets columns sized for that zoom | Already so since 0.2.0; a test now checks it |
| 0.5.1 | Thin scroll bars in the theme's colours | Skipped: the app has RecyclerView's thin fast scroller; recolouring it per theme needs RecyclerView internals. Listed as a known gap |
| 0.5.1 | The settings shortcut list wraps | Not needed: no shortcut list on a phone |
| 0.6.1 | The Microsoft Store lists the app as LeanRows CSV Viewer | Not ported: the Android app name, package id and launcher label stay as they are; the maintainer changes the Play listing |
| 2026-11-18 | Build provenance attestation for release files | Ported to `release.yml` (step 5) |

## 0.2.0 plan and desktop features (done)

The 0.2.0 plan was: an efficiency review, column sorting from desktop
0.4.0, LeanMark 0.6.0's five themes with a settings sheet, and the release.
All of it is merged; the details are under "Done" below.

Desktop 0.4.0 features in 0.2.0:

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

0.3.0 so far:

- The plan above (#29). Its budgets run (44312402286, main against the
  same code) measured the two items the 0.2.0 review found again: Find
  previous with no match in 100,001 rows 160 and 178 ms (2,654 ms in
  0.1.2), Find next 358 and 359 ms, and a system switch to dark 46 ms until
  the rows are drawn again, with the file kept (424 ms and a reopened file
  in 0.1.2). Both stay fixed.
- The budgets job also runs when CI is started by hand on a branch, against
  main (#30), so a pull request can be opened with its numbers. Its first
  budgets attempt lost the emulator right after boot; the rerun passed.
- Copy row (#31): the cell dialog has Copy row, which copies the row number
  and every cell, separated by tabs, as desktop `Ctrl+C` copies a row.
- Build provenance attestations (#33): `release.yml` gives the release job
  `id-token: write` and `attestations: write` and runs
  `actions/attest-build-provenance` v4.2.2, pinned to commit
  4d101475d8b20a2381f78447822ac1eab6504dd8, on the APKs and the bundle
  after the signer check and before the files are attached. The README
  says how to check a download with `gh attestation verify`. It runs for
  the first time with the 0.3.0 release files.
- A manual CI run can measure against any branch, tag or commit (#35), so
  the version bump compares 0.3.0 with the v0.2.0 tag on one emulator.
  Checked with base v0.2.0 in run 44337327373.
- Zoom from desktop 0.5.1 (#34). Each column keeps the width it was sized
  at and every zoomed width is worked out from it; before, each step
  multiplied the last widths, which drifted by about 0.0001 px per trip
  through all the levels. A zoom step binds the row views on screen again
  in one layout pass instead of dropping them and making new ones. The
  budgets job measured no difference in time or frames (13 zoom changes:
  median 16 and 15 ms until the next frame, 89 frames on both, run
  44334941454); on this emulator a step was already one frame.
- Hide columns from desktop 0.6.0 (#32). A long press on a column title
  keeps the four sort orders and Restore source order, and gains Hide
  column and Show all columns, as the desktop's header menu has them. The
  More menu gains Hide column…, a list of the shown columns, and Show all
  columns. Hidden columns are left out of drawing, taps, sideways panning,
  row descriptions and Copy row; the last shown column cannot be hidden; a
  sorted column stays sorted and the status line marks it "(hidden)"; Find
  searches whole rows and says so. Nothing is saved.

0.2.0:

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

- The first frame was about 100 ms later than main after #20. Bisected on
  the budgets job: the settings button's vector icon. #27 draws it in code.
  First frame minus the empty app 99 to 89 ms, rows drawn minus the empty
  app 359 to 267 ms, main against the branch (run 42178980021).

## In progress

- Release v0.3.0: the version bump is #36. This session could not push
  tags or create releases. After #36 merges, the maintainer tags v0.3.0,
  creates the GitHub pre-release with `docs/releases/v0.3.0.md`, runs
  "Release files" for the tag (its first run with the attestation step), and
  runs "Play bundle" if wanted.

## Remaining

- The phone test below, on real hardware, now with hiding columns and Copy
  row.
- Tag v0.3.0, the GitHub pre-release, "Release files" and "Play bundle"
  (see In progress); this session cannot push tags.
- Strip the footer line the session's GitHub proxy added to the bodies of
  #29 to #36.
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

0.3.0 mutations, all caught: Copy row (#31, run 44316875150), hiding
columns (#32, device run 44323386381; JVM run locally after Maven Central
refused the CI job with HTTP 429), zoom (#34, run 44334946599), and the
release workflow check (#33, by hand). The tables are in the pull requests.

## Known gaps

- The fast scroller is drawn in one grey for light and one for dark system
  mode, not in the app theme's colours (desktop 0.5.1 themes its scroll
  bars). RecyclerView creates its fast scroller from XML and keeps its
  drawables private.
- Hidden columns are still read and decoded with their row; only cutting
  them to width and drawing them is skipped.
- A hide or show message stays under the status line until the next sort,
  Find, or hide or show.
- `fastlane/.../full_description.txt` and `store/` do not mention hiding
  columns or Copy row; the maintainer edits the Play listing.
- The README says the bundle on a GitHub release is signed with CI's
  throwaway key, but `release.yml` signs it with the release key.

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

0.3.0 against 0.2.0, release builds, budgets job on the API 34 emulator,
with `base` = `v0.2.0` (CI run 44387335223):

| Measure | 0.2.0 | 0.3.0 |
| --- | ---: | ---: |
| Release APK, all ABIs (bytes) | 216,556 | 218,796 |
| First frame minus the empty app (ms) | 194 | 163 |
| Rows drawn minus the empty app (ms) | 392 | 349 |
| large-multiline.csv drawn after a cold start (ms) | 768 | 734 |
| PSS after large-multiline.csv (KB) | 26,181 | 26,162 |
| Janky frames over 10 swipes (software GPU) | 159 | 144 |
| Find previous, no match, 100,001 rows (ms) | 101 | 102 |
| System switch to dark until rows drawn (ms) | 41 | 36 |
| Sort 1,000,001 rows, text (ms) | 787 | 787 |
| Peak PSS while sorting (KB) | 60,137 | 60,798 |
| 13 zoom changes, median until the next frame (ms) | 16 | 16 |

The same comparison in three other runs, first frame and rows drawn minus
the empty app (0.2.0, then 0.3.0): run 44383486528, 154 and 221, 474 and
540 ms (empty app 714 ms, the slowest emulator); run 44387328730, 48 and
109, 239 and 254 ms; run 44337327373 (main with Copy row and Hide columns),
208 and 220, 546 and 514 ms. Across the four runs the medians are 174 and
192 ms for the first frame and 433 and 434 ms for the rows: the first
frame varies more between runs than between versions, and nothing in
0.3.0 runs before it.

0.2.0 against 0.1.2, release builds, budgets job on the API 34 emulator.
"0.1.2" is main in the run of #14 (41982387830).

| Measure | 0.1.2 | 0.2.0 |
| --- | ---: | ---: |
| Release APK, all ABIs (bytes) | 217,221 | 216,628 |
| First frame minus the empty app (ms) | 51 | 89 |
| Rows drawn minus the empty app (ms) | 354 | 267 |
| PSS after large-multiline.csv (KB) | 25,297 | 26,169 |
| Find previous, no match, 100,001 rows (ms) | 2,654 | 190 to 221 |
| System switch to dark until rows drawn (ms) | 424 | 33 |
| Sort 1,000,001 rows, text (ms) | NA | 1,091 to 1,490 |
| Peak PSS while sorting 1,000,001 rows (KB) | NA | 57,812 |

The 0.2.0 column is from run 42178980021 (#27), sort and Find from
runs 41988225938 and 41987717205. Since #27 (run 42178980021) the first
frame is 89 ms and rows 267 ms over the empty app, against 51 and 354 ms
for 0.1.2. Two identical builds differed by 65 ms in a control run
(42177209022), so the first frame is within the emulator's spread of
0.1.2.

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

- `LeanRows-0.3.0.apk`: the release build (minified), signed with a throwaway
  key. Use this one for timing.
- `LeanRows-0.3.0-debug.apk`: the debug build, for comparison only. It is slower
  by design.
- `empty-app.apk`: an app with one empty screen, for the start-time
  baseline with adb (it has no launcher icon).
- `LeanRows-0.3.0-phone-test-files.zip`: the files below. Unzip it into
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

### Hiding columns and Copy row

1. Open `weather-stations.csv`. Long-press the title "Column 3": the menu
   has the four sort orders, Restore source order, Hide column and Show
   all columns. Choose Hide column. The station names go, Column 4 moves
   into their place, and the status line says "1 column hidden".
2. Tap a cell and choose Copy row. Paste it into a note: the row number and
   the cells, with tabs between them and no station name.
3. More (⋮) > Show all columns brings Column 3 back.

### Themes

Tap the dot in the toolbar five times: System, Light, Dark, Dim, Dusk and
back. Open the settings (the sliders button), pick a theme, and zoom the
table in and out. Close and open the app: both are kept.

### What to send back

Phone model, Android version, the three start times and the empty-app time,
what the scrolling bars looked like, the workbook time, the sort time, and
the memory numbers if you used adb.
