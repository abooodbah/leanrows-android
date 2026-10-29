// Scratch-only oracle for the Android port: prints what this engine shows.
#[cfg(test)]
#[allow(clippy::all, clippy::pedantic, clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod android_oracle {
    use super::*;
    use std::fmt::Write as _;

    fn units_json(out: &mut String, units: &[u16]) {
        out.push('"');
        for &unit in units {
            match unit {
                0x22 => out.push_str("\\\""),
                0x5c => out.push_str("\\\\"),
                0x20..=0x7e => out.push(char::from(u8::try_from(unit).unwrap())),
                _ => write!(out, "\\u{unit:04x}").unwrap(),
            }
        }
        out.push('"');
    }

    #[test]
    fn dump() {
        let Ok(input) = std::env::var("ORACLE_IN") else { return };
        let output = std::env::var("ORACLE_OUT").unwrap();
        let mut names: Vec<_> = fs::read_dir(&input).unwrap().map(|e| e.unwrap().path()).collect();
        names.sort();
        for path in names {
            let token = CancellationToken::new();
            let generation = token.snapshot();
            let (mut engine, _) = DocumentEngine::open(&path, &token, generation).unwrap().into_parts();
            loop {
                let step = engine.scan_step(&token, generation).unwrap();
                if step.status() == DocumentScanStatus::Complete { break; }
            }
            let progress = engine.progress();
            let rows = progress.row_count();
            let mut starts = vec![0_u64];
            if rows > 128 {
                starts.extend([rows / 3, rows / 2, rows - 50, rows - 1]);
                for i in 1..=3_u64 { starts.push((i * 7919 + 13) % rows); }
            }
            let mut out = String::new();
            let diagnostic = progress.diagnostic().map(|d| format!("{:?}", d.kind()));
            writeln!(out, "{{\"rows\":{rows},\"diagnostic\":{}}}",
                diagnostic.map_or("null".to_string(), |d| format!("\"{d}\""))).unwrap();
            for start in starts {
                let snapshot = engine.snapshot(start, &token, generation).unwrap();
                let columns = snapshot.columns();
                writeln!(out, "{{\"window\":{start},\"count\":{},\"eof\":{},\"preview\":{},\"dataColumns\":{}}}",
                    snapshot.len(), snapshot.reached_eof(),
                    columns.kind() == UiColumnKind::Preview, columns.data_columns()).unwrap();
                for row in snapshot.into_rows() {
                    let parts = row.into_parts();
                    write!(out, "{{\"row\":{},\"source\":{},\"display\":{},\"escaped\":{},\"cells\":[",
                        parts.absolute_row, parts.source_truncated, parts.display_truncated, parts.escaped_non_utf8).unwrap();
                    for (i, cell) in parts.cells.into_iter().enumerate() {
                        if i > 0 { out.push(','); }
                        let units = cell.into_units();
                        units_json(&mut out, units.strip_suffix(&[0]).unwrap());
                    }
                    out.push_str("]}\n");
                }
            }
            let name = path.file_name().unwrap().to_string_lossy().to_string();
            fs::write(Path::new(&output).join(format!("{name}.jsonl")), out).unwrap();
        }
    }
}
