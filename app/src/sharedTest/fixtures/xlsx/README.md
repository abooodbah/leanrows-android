# .xlsx test files

Written by `tools/xlsx-fixtures/make.py` with real spreadsheet writers. The
expected rows in `expected/` come from `tools/xlsx-fixtures/oracle.py`, a
second implementation of the rules in `docs/XLSX.md` that reads the raw XML.

| File | Written by | What it holds |
| --- | --- | --- |
| `weather-stations.xlsx` | LibreOffice 24.2.7.2, from the desktop fixture `weather-stations.csv` | 1,441 rows; numbers LibreOffice recognised are stored as numbers |
| `document-smoke.xlsx` | LibreOffice 24.2.7.2, from the desktop fixture `document-smoke.csv` | a small sheet |
| `inline-quoted.xlsx` | LibreOffice 24.2.7.2, from the desktop fixture `inline-quoted.csv` | quotes, commas and a line break in cells |
| `features.xlsx` | XlsxWriter 3.2.9 | three sheets, one hidden; dates, date-times, times, a custom date format, booleans, an error, formulas with stored results, a percentage, a currency format, a line break, a gap of two rows, a cell in column L, rich text, spaces, a tab, non-Latin text |
| `inline-strings.xlsx` | XlsxWriter 3.2.9, constant-memory mode | strings stored inline in the sheet, not shared |
| `date-1904.xlsx` | XlsxWriter 3.2.9 | dates in the 1904 date system |
| `openpyxl-stations.xlsx` | openpyxl 3.1.5 | 25 rows of the weather data, a date and a boolean |
| `large-weather.xlsx` | XlsxWriter 3.2.9, constant-memory mode | 100,000 data rows: the desktop weather readings, repeated |
| `hostile/truncated.xlsx` | the first half of `features.xlsx` | an interrupted download |
| `hostile/doctype.xlsx` | `features.xlsx` with a DOCTYPE and an entity added to its shared strings | refused |
| `hostile/zip-bomb.xlsx` | `features.xlsx` with its first sheet replaced by 1.5 GiB of empty rows that deflate to 2.3 MB | refused by counting |

XlsxWriter writes "Microsoft Excel" as the application name in
`docProps/app.xml`, as Excel does; these files were not written by Excel.
None of the files was written by Microsoft Excel itself; that is a known gap.

Running the script again rewrites every file. LibreOffice and XlsxWriter put
the time in `docProps/core.xml`, so the bytes change while the cells do not;
run `oracle.py` afterwards.
