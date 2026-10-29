# Progress

Updated with every merge. The next session starts here.

## Done

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
- Store texts, data safety, content rating, closed-test plan and light and
  dark screenshots in `store/`; signing steps in the README.

## In progress

- Release v0.1.0.

## Remaining

- The phone test below, on real hardware.
- Play Console setup and the closed test (see `store/closed-test.md`);
  these need the maintainer's account.

## Known gaps

- .xlsx is not supported. It needs a zip and XML reader and shared-string
  handling; it did not fit this round.
- Columns are named Column 1, Column 2 and so on, and the first line is
  shown as a row, as in the desktop app; there is no header detection.
- At most 64 fields of 1,024 bytes are shown per record, from its first
  4 KiB, as in the desktop app; longer cells end with " ...".
- Release builds from CI are signed with a throwaway key that changes with
  every run, so one release cannot be installed over another; uninstall
  first.
- The emulator has no GPU, so start time and jank from CI are not the
  budget check.

## Measured numbers

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

## Phone test

The emulator in CI has no GPU, so its start times and jank do not show what
a phone does. This is the check to run on a real phone. It takes about ten
minutes.

### Install

From the release page on GitHub, download onto the phone:

- `LeanRows-0.1.0.apk`: the release build (minified), signed with a throwaway
  key. Use this one for timing.
- `LeanRows-0.1.0-debug.apk`: the debug build, for comparison only. It is slower
  by design.
- `empty-app.apk`: an app with one empty screen, for the start-time
  baseline with adb (it has no launcher icon).
- `LeanRows-0.1.0-phone-test-files.zip`: the files below. Unzip it into
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

### What to send back

Phone model, Android version, the three start times and the empty-app time,
what the scrolling bars looked like, and the memory numbers if you used adb.
