# Desktop oracle

`app/src/test/resources/oracle/*.jsonl.gz` holds what the desktop LeanRows
engine shows for each test file: the row count, the unterminated-quote
diagnostic, and every cell of up to eight 128-row windows. `DesktopOracleTest`
checks that this app shows the same cells.

To write them again (needs Rust 1.97.1 and PowerShell 7):

```sh
git clone https://github.com/abooodbah/leanrows desktop
cd desktop && git checkout a069d78
pwsh -NoProfile -File tools/New-LeanRowsFixtures.ps1 -OutputDirectory /tmp/oracle-in
cp tests/fixtures/document-smoke* store/samples/weather-stations.csv /tmp/oracle-in/
cp ../leanrows-android/app/src/sharedTest/fixtures/desktop/inline-* /tmp/oracle-in/
# the two inputs the desktop tests build in code
python3 -c "open('/tmp/oracle-in/inline-giant-line.log','wb').write(b'x'*2097152+b'\n')"
python3 -c "open('/tmp/oracle-in/inline-20000-rows.txt','wb').write(b'row\n'*20000)"
rm /tmp/oracle-in/manifest.*
cat ../leanrows-android/tools/oracle/android_oracle.rs >> crates/leanrows-win32/src/document_engine.rs
mkdir /tmp/oracle-out
ORACLE_IN=/tmp/oracle-in ORACLE_OUT=/tmp/oracle-out \
  cargo test -p leanrows-win32 --lib android_oracle --release
for f in /tmp/oracle-out/*.jsonl; do gzip -9n -c "$f" > "../leanrows-android/app/src/test/resources/oracle/$(basename "$f").gz"; done
```

The appended module only reads; it is not part of the desktop app and is
not committed there.

The `inline-*` files are the byte strings the desktop engine tests write in
`crates/leanrows-win32/src/document_engine.rs`. The other files in
`app/src/sharedTest/fixtures/desktop` are copied unchanged from the desktop
`tests/fixtures` and `store/samples`.
