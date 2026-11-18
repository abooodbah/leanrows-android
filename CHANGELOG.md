# Changelog

What changed in each release of LeanRows for Android. The notes for each
GitHub release are the entry below, and the tag message has the same text.

## 0.3.0 - 2026-11-18

LeanRows for Android 0.3.0 hides columns from the view, as desktop LeanRows
0.6.0 does, copies whole rows, and zooms without changing column widths, as
desktop 0.5.1 does.

Not on Google Play yet. This build is for testing on a phone.

### Hide columns

- Long-press a column title and choose Hide column. The same menu still has
  the text and numeric sort orders, and Show all columns brings every hidden
  column back.
- The More menu (⋮) has Hide column…, with a list of the shown columns, and
  Show all columns.
- Hiding a column changes the view only. The file is not changed and nothing
  is saved. Another file or another sheet shows every column.
- The last shown column cannot be hidden, so JSON Lines and log files, which
  show one Preview column, have nothing to hide.
- Hidden columns are left out of sideways panning, of Copy row and of what
  TalkBack reads for a row.
- Sort by a column and then hide it, and the rows stay in that order. The
  status line marks the sorted column as hidden and counts the hidden
  columns.
- Find still searches whole rows, hidden columns included, and says so while
  it shows a result.
- CSV, TSV and Excel sheets all work. Sheet columns keep their letters.

### Copy row

- Tap a cell. Besides Copy, the dialog has Copy row: the row number and every
  cell, separated by tabs, as desktop LeanRows copies a row.

### Zoom

- Column widths come back exactly when you zoom in and out.
- A zoom step redraws the rows on screen in place instead of making them
  again.

### Release files

- The release files carry a build provenance attestation. Check a download
  with `gh attestation verify <file> --repo abooodbah/leanrows-android`. This
  shows the file was built by this repository's release workflow from the
  tag. It is not code signing.

### Measured

On a GitHub Actions API 34 emulator (x86_64, no GPU), 0.2.0 and 0.3.0
release builds measured the same way in one run:

| Measure | 0.2.0 | 0.3.0 |
| --- | ---: | ---: |
| Release APK, all ABIs (bytes) | 216,556 | 218,796 |
| Cold start to rows drawn, weather-stations.csv (ms, median of 9) | 820 | 777 |
| The same, minus an empty app's first frame (ms) | 392 | 349 |
| large-multiline.csv (100,001 rows) drawn after a cold start (ms, median of 3) | 768 | 734 |
| App memory (PSS) after large-multiline.csv (KB, median of 3) | 26,181 | 26,162 |
| Find previous, no match, 100,001 rows (ms) | 101 | 102 |
| Sorting 1,000,001 rows by the first column (ms) | 787 | 787 |
| 13 zoom changes, median until the next frame (ms) | 16 | 16 |

From CI run 44387335223. Three other runs of the same comparison (44383486528,
44387328730, 44337327373) put the rows drawn minus the empty app at 474 and
540, 239 and 254, and 546 and 514 ms: the two versions are within the
emulator's spread of each other.

### Known gaps

- The fast scroller is not drawn in the theme's colours.
- Hidden cells are still read with their row; they are only not drawn.
- Sorting does not work on Excel sheets yet; the app says so.
- Start time and jank were measured on an emulator without a GPU, not on a
  phone.

Still no permissions and no network.

## 0.2.0 - 2026-11-05

LeanRows for Android 0.2.0 sorts a whole file by any column, as desktop
LeanRows 0.4.0 does, and has LeanMark's five themes and a settings sheet.

Not on Google Play yet. This build is for testing on a phone.

### Sorting

- Tap a column title to sort the whole file by it. Tap it again to reverse
  the order. The sorted column shows an arrow.
- Long-press a title for text or numeric order. Tap Row to go back to the
  file's own order. The More menu (⋮) has the same commands and Sort by
  column.
- Text order compares the field's bytes, case sensitively. Numeric order
  compares integers, decimals and exponents exactly. Numbers come first,
  then text, then empty values, in both directions. Equal values keep the
  file's order.
- Rows keep their own numbers from the file. Find and Go to row use them.
- Cancel, next to the progress, stops a sort and keeps the view you had.
- A sort uses at most about 8 MB of memory. Its temporary files go in the
  app's cache and are removed as soon as they are no longer needed, also if
  the app is closed. A sort that would not fit in the free space is refused
  before it starts, with a message that says how much it needs.
- Excel sheets are not sorted yet; the app says so.

### Themes and settings

- Five themes: System, Light, Dark, Dim and Dusk, with LeanMark's colours.
- The theme button shows the theme as a coloured dot and names it when you
  press and hold it. A tap moves to the next theme.
- The settings button opens a sheet with the theme and the zoom of the
  table, from 50% to 300%. Zoom enlarges the table only.
- Both are remembered.
- Go to row moved to the More menu.

### Faster and smaller

- Find previous reads the file about once. It read the same file many
  times over before.
- Switching the phone between light and dark no longer opens the file
  again; you keep your place.
- The APK is smaller: Kotlin metadata files and other languages' strings
  from a library are left out.

Still no permissions and no network.
