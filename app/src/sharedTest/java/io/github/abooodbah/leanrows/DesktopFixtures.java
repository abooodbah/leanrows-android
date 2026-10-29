package io.github.abooodbah.leanrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A line-for-line port of the desktop's tools/New-LeanRowsFixtures.ps1, so
 * the large test files are the ones the desktop tests use without storing
 * 100 MB in the repository. DesktopFixturesTest checks each output against
 * the SHA-256 the desktop script wrote for the same parameters.
 *
 * <p>Java, not Kotlin, because the instrumented tests' file provider runs it
 * in a process without the Kotlin standard library.
 */
public final class DesktopFixtures {
    public static final int SEED = 1729;
    public static final long ROWS = 100_000L;
    public static final int PAYLOAD = 32;
    public static final long GIANT_PAYLOAD = 64L * 1024 * 1024;

    /**
     * From the manifest.json written by the desktop script with its defaults
     * (desktop commit a069d78).
     */
    public static final Map<String, String> SHA256 = new LinkedHashMap<>();

    static {
        SHA256.put("large-multiline.csv", "b4bfbfe6fe55be50bcfb4a664c09a3c883937907195763d523d7a457c1820498");
        SHA256.put("large.jsonl", "d9ce05942fbf8e195f9c84aadf5ef6724c112161999e958933b440f2643ccc52");
        SHA256.put("large.log", "09c33ecd8e819c350e39cbb843b354cf4347facfc3ac0dade241da0e74752b29");
        SHA256.put("malformed.csv", "3e7b22b22dc58cb4940fdf1496c9efebeb274ef48dc82f6123216d9ce4107cf0");
        SHA256.put("giant-record.csv", "b29dcec2ba3c1e4de186ff711119bf3fad085c07bcf42c39245636dedf870c4c");
    }

    public static final List<String> NAMES = Arrays.asList(SHA256.keySet().toArray(new String[0]));

    private static final String MALFORMED =
        "id,name,notes\r\n1,alpha,valid\r\n2,beta,extra,column\r\n3,ga\"mma,unescaped-quote\r\n"
            + "4,delta,\"quoted\r\nmultiline\"\r\n5,epsilon,\"unterminated";

    private DesktopFixtures() {}

    public static boolean has(String name) {
        return SHA256.containsKey(name);
    }

    public static void write(String name, OutputStream out) throws IOException {
        switch (name) {
            case "large-multiline.csv": largeMultilineCsv(out); break;
            case "large.jsonl": largeJsonl(out); break;
            case "large.log": largeLog(out); break;
            case "malformed.csv": out.write(MALFORMED.getBytes(StandardCharsets.UTF_8)); break;
            case "giant-record.csv": giantRecordCsv(out); break;
            default: throw new IllegalArgumentException(name);
        }
    }

    public static byte[] bytes(String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(name, out);
        return out.toByteArray();
    }

    public static String sha256(String name) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        MessageDigest sink = digest;
        write(name, new OutputStream() {
            @Override public void write(int b) { sink.update((byte) b); }
            @Override public void write(byte[] b, int off, int len) { sink.update(b, off, len); }
        });
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b & 0xff));
        return hex.toString();
    }

    private static String filler(long row, int salt) {
        char letter = (char) ('a' + (int) ((row + SEED + salt) % 26));
        char[] chars = new char[PAYLOAD];
        Arrays.fill(chars, letter);
        return new String(chars);
    }

    /** Buffers text and writes it as UTF-8 in 64 KiB steps, as the script does. */
    private static final class Text {
        private final OutputStream out;
        private final StringBuilder buffer = new StringBuilder(1 << 16);

        Text(OutputStream out) { this.out = out; }

        void add(String text) throws IOException {
            buffer.append(text);
            if (buffer.length() >= 1 << 16) flush();
        }

        void flush() throws IOException {
            out.write(buffer.toString().getBytes(StandardCharsets.UTF_8));
            buffer.setLength(0);
        }
    }

    private static void largeMultilineCsv(OutputStream out) throws IOException {
        Text text = new Text(out);
        text.add("row_id,timestamp_ms,value_milli,phase,notes\r\n");
        for (long row = 0; row < ROWS; row++) {
            long timestamp = 1704067200000L + row * 1000L;
            long value = ((row % 1000000L) * 1103515245L + SEED) % 1000000L;
            String filler = filler(row, 11);
            String notes = row % 97L == 0L
                ? "window " + row + ", \"stable\"\r\ncontinuation " + row + " " + filler
                : "window " + row + ", \"stable\" " + filler;
            text.add(row + "," + timestamp + "," + value + ",phase-" + (row % 7L) + ",\""
                + notes.replace("\"", "\"\"") + "\"\r\n");
        }
        text.flush();
    }

    private static void largeJsonl(OutputStream out) throws IOException {
        Text text = new Text(out);
        for (long row = 0; row < ROWS; row++) {
            long timestamp = 1704067200000L + row * 1000L;
            long value = ((row % 1000000L) * 1664525L + SEED + 1013904223L) % 1000000L;
            text.add("{\"row_id\":" + row + ",\"timestamp_ms\":" + timestamp + ",\"value_milli\":" + value
                + ",\"phase\":\"phase-" + (row % 7L) + "\",\"payload\":\"" + filler(row, 17) + "\"}\n");
        }
        text.flush();
    }

    private static void largeLog(OutputStream out) throws IOException {
        String[] levels = {"TRACE", "DEBUG", "INFO", "WARN", "ERROR"};
        Text text = new Text(out);
        for (long row = 0; row < ROWS; row++) {
            long timestamp = 1704067200000L + row * 1000L;
            long value = ((row % 1000000L) * 22695477L + SEED + 1L) % 1000000L;
            text.add("timestamp_ms=" + timestamp + " level=" + levels[(int) (row % 5L)] + " row_id=" + row
                + " sensor=sensor-" + (row % 32L) + " value_milli=" + value + " payload=" + filler(row, 23) + "\n");
        }
        text.flush();
    }

    private static void giantRecordCsv(OutputStream out) throws IOException {
        out.write("id,payload\r\n1,\"".getBytes(StandardCharsets.UTF_8));
        byte[] chunk = new byte[65536];
        Arrays.fill(chunk, (byte) 'G');
        long remaining = GIANT_PAYLOAD;
        long interval = 1048576L;
        long untilCrLf = interval;
        while (remaining > 0) {
            if (untilCrLf == 0L && remaining >= 2) {
                out.write(new byte[] {'\r', '\n'});
                remaining -= 2;
                untilCrLf = interval;
                continue;
            }
            long take = Math.min(65536L, remaining);
            if (untilCrLf > 0) take = Math.min(take, untilCrLf);
            if (take <= 0) take = Math.min(1L, remaining);
            out.write(chunk, 0, (int) take);
            remaining -= take;
            if (untilCrLf > 0) untilCrLf -= take;
        }
        out.write("\"\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
