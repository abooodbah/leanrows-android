"""Writes the expected rows for each .xlsx test file, from the raw XML, by the
rules in docs/XLSX.md. It is a second implementation of those rules, kept
apart from the app's Kotlin code on purpose, like tools/oracle for CSV.

    python3 tools/xlsx-fixtures/oracle.py

For every visible sheet of every file in app/src/sharedTest/fixtures/xlsx it
writes <file>.<sheet index>.jsonl into expected/: one JSON array of cell
texts per sheet row, from row 1 to the last stored row, empty rows included.
expected/sheets.json lists each file's visible sheet names in order.
"""

import datetime
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ElementTree
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "app/src/sharedTest/fixtures/xlsx"
EXPECTED = FIXTURES / "expected"

MAIN = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
REL = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
PACKAGE_REL = "{http://schemas.openxmlformats.org/package/2006/relationships}"

DATE_IDS = set(range(14, 23)) | {45, 46, 47}
TIME_ONLY_IDS = {18, 19, 20, 21, 45, 46, 47}


def text_of(element) -> str:
    """All <t> text in order, rich runs included, phonetic runs left out."""
    parts = []
    for child in element:
        if child.tag == MAIN + "t":
            parts.append(child.text or "")
        elif child.tag == MAIN + "r":
            for run in child:
                if run.tag == MAIN + "t":
                    parts.append(run.text or "")
    return "".join(parts)


def format_kind(code: str) -> str:
    """'date', 'datetime', 'time' or '' for a custom number format code."""
    cleaned = re.sub(r'"[^"]*"|\\.|_.|\*.', "", code)
    elapsed = bool(re.search(r"\[(h+|m+|s+)\]", cleaned, re.I))
    cleaned = re.sub(r"\[[^\]]*\]", "", cleaned).lower()
    section = cleaned.split(";")[0]
    has_date = bool(re.search(r"[dy]", section)) or ("m" in section and not re.search(r"[hs]", section))
    has_time = elapsed or bool(re.search(r"[hs]", section))
    if has_date and has_time:
        return "datetime"
    if has_date:
        return "date"
    if has_time:
        return "time"
    return ""


def serial_text(value: str, kind: str, date1904: bool) -> str:
    serial = float(value)
    base = datetime.datetime(1904, 1, 1) if date1904 else datetime.datetime(1899, 12, 30)
    if not date1904 and serial < 61:
        # Excel counts the day 1900-02-29 that never was; before it, its
        # serials are one higher than the calendar.
        serial += 1
    moment = base + datetime.timedelta(days=serial)
    # Round to the nearest second, as Excel shows them.
    moment = (moment + datetime.timedelta(microseconds=500_000)).replace(microsecond=0)
    if kind == "time":
        return moment.strftime("%H:%M:%S")
    if kind == "date":
        return moment.strftime("%Y-%m-%d")
    return moment.strftime("%Y-%m-%d %H:%M:%S")


def column_index(reference: str) -> int:
    letters = re.match(r"[A-Z]+", reference).group(0)
    number = 0
    for letter in letters:
        number = number * 26 + ord(letter) - 64
    return number - 1


def read(path: pathlib.Path):
    with zipfile.ZipFile(path) as book:
        names = set(book.namelist())
        workbook = ElementTree.fromstring(book.read("xl/workbook.xml"))
        rels = ElementTree.fromstring(book.read("xl/_rels/workbook.xml.rels"))
        targets = {r.get("Id"): r.get("Target") for r in rels.iter(PACKAGE_REL + "Relationship")}
        properties = workbook.find(MAIN + "workbookPr")
        date1904 = properties is not None and properties.get("date1904") in ("1", "true")
        shared = []
        if "xl/sharedStrings.xml" in names:
            for item in ElementTree.fromstring(book.read("xl/sharedStrings.xml")).iter(MAIN + "si"):
                shared.append(text_of(item))
        kinds = []
        if "xl/styles.xml" in names:
            styles = ElementTree.fromstring(book.read("xl/styles.xml"))
            codes = {}
            formats = styles.find(MAIN + "numFmts")
            if formats is not None:
                for item in formats:
                    codes[int(item.get("numFmtId"))] = item.get("formatCode")
            cells = styles.find(MAIN + "cellXfs")
            for xf in (cells if cells is not None else []):
                ident = int(xf.get("numFmtId", "0"))
                if ident in codes:
                    kinds.append(format_kind(codes[ident]))
                elif ident in DATE_IDS:
                    kinds.append("time" if ident in TIME_ONLY_IDS else ("datetime" if ident == 22 else "date"))
                else:
                    kinds.append("")
        sheets = []
        for sheet in workbook.find(MAIN + "sheets"):
            if sheet.get("state") in ("hidden", "veryHidden"):
                continue
            target = targets[sheet.get(REL + "id")]
            target = target.lstrip("/") if target.startswith("/") else "xl/" + target
            rows = []
            data = ElementTree.fromstring(book.read(target)).find(MAIN + "sheetData")
            next_row = 1
            for row in data:
                number = int(row.get("r", next_row))
                while len(rows) < number - 1:
                    rows.append([])
                cells = []
                next_column = 0
                for cell in row:
                    reference = cell.get("r")
                    column = column_index(reference) if reference else next_column
                    while len(cells) < column:
                        cells.append("")
                    kind = cell.get("t", "n")
                    value = cell.find(MAIN + "v")
                    stored = value.text if value is not None and value.text is not None else None
                    if kind == "s":
                        text = shared[int(stored)] if stored is not None else ""
                    elif kind == "inlineStr":
                        inline = cell.find(MAIN + "is")
                        text = text_of(inline) if inline is not None else ""
                    elif kind == "b":
                        text = "" if stored is None else ("TRUE" if stored == "1" else "FALSE")
                    elif kind in ("str", "e", "d"):
                        text = stored or ""
                    elif stored is None:
                        text = ""
                    else:
                        style = int(cell.get("s", "0"))
                        date_kind = kinds[style] if style < len(kinds) else ""
                        text = serial_text(stored, date_kind, date1904) if date_kind else stored
                    cells.append(text)
                    next_column = column + 1
                rows.append(cells)
                next_row = number + 1
            sheets.append((sheet.get("name"), rows))
        return sheets


def main() -> None:
    EXPECTED.mkdir(exist_ok=True)
    listing = {}
    for path in sorted(FIXTURES.glob("*.xlsx")):
        sheets = read(path)
        listing[path.name] = [name for name, _ in sheets]
        for index, (_, rows) in enumerate(sheets):
            with open(EXPECTED / f"{path.name}.{index}.jsonl", "w", encoding="utf-8", newline="\n") as out:
                for row in rows:
                    out.write(json.dumps(row, ensure_ascii=False) + "\n")
    with open(EXPECTED / "sheets.json", "w", encoding="utf-8", newline="\n") as out:
        json.dump(listing, out, ensure_ascii=False, indent=2)
        out.write("\n")
    print(json.dumps(listing, ensure_ascii=False))


if __name__ == "__main__":
    sys.exit(main())
