package io.github.abooodbah.leanrows;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves test files to the app the way other apps do. Paths are
 * MODE/SOURCE/NAME:
 *
 * <ul>
 *   <li>MODE "file" hands over a real file, as file managers do; "pipe"
 *       streams it without a size, as mail and cloud apps often do.
 *   <li>SOURCE "desktop" is a file copied from the desktop repository;
 *       "generated" is written by the desktop's fixture script (through its
 *       port, DesktopFixtures).
 * </ul>
 *
 * <p>Java, not Kotlin: Android starts this provider in the test package's own
 * process, where the Kotlin standard library is missing because the build
 * leaves it out of the test APK (the app already has it).
 */
public final class FixtureProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.abooodbah.leanrows.fixtures";

    private static final ConcurrentHashMap<String, Long> WRITTEN = new ConcurrentHashMap<>();

    public static Uri uri(String path) {
        return Uri.parse("content://" + AUTHORITY + "/" + path);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
        String extension = name.substring(name.lastIndexOf('.') + 1);
        switch (extension) {
            case "csv": return "text/csv";
            case "tsv": return "text/tab-separated-values";
            case "jsonl":
            case "ndjson": return "application/x-ndjson";
            case "log":
            case "txt": return "text/plain";
            default: return "application/octet-stream";
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        List<String> parts = uri.getPathSegments();
        String[] columns = projection != null
            ? projection
            : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                row[i] = uri.getLastPathSegment();
            } else if (OpenableColumns.SIZE.equals(columns[i]) && "file".equals(parts.get(0))) {
                try {
                    row[i] = file(parts).length();
                } catch (IOException e) {
                    throw new IllegalArgumentException(String.valueOf(uri), e);
                }
            }
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("read only");
        List<String> parts = uri.getPathSegments();
        if (parts.size() != 3) throw new FileNotFoundException(String.valueOf(uri));
        try {
            if ("file".equals(parts.get(0))) {
                return ParcelFileDescriptor.open(file(parts), ParcelFileDescriptor.MODE_READ_ONLY);
            }
            if (!"pipe".equals(parts.get(0))) throw new FileNotFoundException(String.valueOf(uri));
            String key = uri.getPath();
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            WRITTEN.remove(key);
            ParcelFileDescriptor write = pipe[1];
            new Thread(() -> {
                long[] total = {0};
                try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(write)) {
                    OutputStream counting = new OutputStream() {
                        @Override public void write(int b) throws IOException {
                            out.write(b);
                            total[0]++;
                        }
                        @Override public void write(byte[] b, int off, int len) throws IOException {
                            out.write(b, off, len);
                            total[0] += len;
                        }
                    };
                    writeTo(parts.get(1), parts.get(2), counting);
                } catch (IOException closed) {
                    // The reader stopped early.
                }
                WRITTEN.put(key, total[0]);
            }).start();
            return pipe[0];
        } catch (IOException e) {
            throw new FileNotFoundException(e.toString());
        }
    }

    /** "written": how many bytes the last pipe for the path arg delivered, or -1 while it runs. */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!"written".equals(method) || arg == null) return null;
        Bundle result = new Bundle();
        Long bytes = WRITTEN.get(arg);
        result.putLong("bytes", bytes == null ? -1 : bytes);
        return result;
    }

    /** The fixture as a real file, written to this package's cache on first use. */
    private File file(List<String> parts) throws IOException {
        File file = new File(getContext().getCacheDir(), parts.get(1) + "/" + parts.get(2));
        if (file.isFile()) return file;
        File folder = file.getParentFile();
        if (folder != null) folder.mkdirs();
        File partial = new File(file.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(partial)) {
            writeTo(parts.get(1), parts.get(2), out);
        }
        if (!partial.renameTo(file)) throw new IOException("could not keep " + file);
        return file;
    }

    private void writeTo(String source, String name, OutputStream out) throws IOException {
        if ("generated".equals(source) && DesktopFixtures.has(name)) {
            DesktopFixtures.write(name, out);
            return;
        }
        if (!"desktop".equals(source)) throw new FileNotFoundException(source + "/" + name);
        try (InputStream in = getContext().getAssets().open("desktop/" + name)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
