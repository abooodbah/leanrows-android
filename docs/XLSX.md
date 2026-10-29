# How LeanRows shows .xlsx files

LeanRows reads an Excel workbook read-only and shows one sheet at a time as
rows. Nothing in the file runs: formulas are not calculated, macros and
links are not followed. These rules are shared with desktop LeanRows, so a
workbook shows the same text on both.

## Sheets

- Sheets come in workbook order (`xl/workbook.xml`), each found through
  `xl/_rels/workbook.xml.rels`.
- The first sheet that is not hidden or very hidden opens. The Sheet button
  lists the visible sheets; hidden sheets are not offered.

## Cell text

| Stored as | Shown as | Example |
| --- | --- | --- |
| shared string (`t="s"`) | all its text, rich-text runs joined, phonetic runs left out | `Plain, bold and plain again` |
| inline string (`t="inlineStr"`) | the same | `ST001` |
| formula string (`t="str"`) | the stored result | `Harbor Point north` |
| boolean (`t="b"`) | `TRUE` or `FALSE` | `TRUE` |
| error (`t="e"`) | the stored error | `#DIV/0!` |
| ISO date (`t="d"`) | the stored text | `2026-01-01T06:30:00` |
| number with a date format | `YYYY-MM-DD` | `2026-01-01` |
| number with a date and time format | `YYYY-MM-DD HH:MM:SS` | `2026-01-01 06:30:15` |
| number with a time format | `HH:MM:SS` | `06:30:00` |
| any other number | the stored text, unrounded, without a locale | `1234.5`, `1E-07` |
| formula without a stored result | empty | |

- Date and time formats are the built-in ones (14 to 22, 45 to 47) and
  custom format codes with d, m, y, h or s outside quoted text, escaped
  characters and `[...]` sections. `[h]`, `[m]` and `[s]` count as time.
- The 1904 date system (`date1904`) is honoured. In the 1900 system serials
  below 61 are moved one day, because Excel counts a 29 February 1900 that
  never was.
- Seconds are rounded to the nearest second.
- Text keeps its spaces, tabs and line breaks. The table shows line breaks
  and tabs as `\n` and `\t`, as it does for CSV.

## Rows and columns

- A cell's place comes from its reference (`C5`); a cell without one follows
  the one before it.
- Rows that the sheet leaves out are shown empty, so row numbers match the
  sheet. A row is as wide as its last stored cell.
- Columns are titled with the sheet's letters, A, B, C and so on. The first
  row is a row, not a header, as for CSV.
- The table's limits for CSV apply: at most 64 cells of 1,024 characters
  per row are shown, and longer text ends with " ...".

## Limits

A workbook beyond these is refused with a message that says which limit:

| Limit | Value |
| --- | --- |
| Rows in a sheet | 1,048,576, Excel's own |
| Columns in a sheet | 16,384, Excel's own |
| One part of the zip, unpacked | 1 GiB |
| Unpacked to compressed size of one part, past its first MiB | 200 to 1 |
| Shared strings, in total | 256 MiB of text |

The sizes are counted while unpacking; the sizes written in the zip are not
trusted. XML with a DOCTYPE is refused, so entities cannot expand.

## Not supported

- .xlsm, .xlsb and the older .xls format.
- Number formats other than dates and times: numbers are shown as stored,
  so 12.5% shows as 0.125.
- Merged cells are shown in their first cell only.

## How it works on Android

The chosen sheet is written as CSV into the app's cache, one row at a time,
and the CSV row engine shows it. A copy of the workbook is made first when
the file arrives as a stream. Both are deleted when the file is closed.
