// Copyright (c) 2026. All rights reserved.
package app.cpltracker.mas;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

// Serves files from cache/share/ to the app the instructor shares the progress email with (email, Drive, ...).
// Not exported: other apps can read a file only through the one-off permission granted on the share intent.
public class ShareProvider extends ContentProvider {
    static final String AUTHORITY = "app.cpltracker.mas.files";

    static File dir(android.content.Context c) { File d = new File(c.getCacheDir(), "share"); d.mkdirs(); return d; }
    static Uri uriFor(File f) { return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(f.getName()).build(); }

    private File fileFor(Uri u) throws FileNotFoundException {
        String name = u.getLastPathSegment();
        if (name == null || name.contains("/") || name.startsWith(".")) throw new FileNotFoundException();
        File f = new File(dir(getContext()), name);
        if (!f.isFile()) throw new FileNotFoundException();
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(u), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri u, String[] proj, String sel, String[] args, String sort) {
        try {
            File f = fileFor(u);
            MatrixCursor c = new MatrixCursor(new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE });
            c.addRow(new Object[] { f.getName(), f.length() });
            return c;
        } catch (FileNotFoundException e) { return null; }
    }

    @Override
    public String getType(Uri u) {
        String n = u.getLastPathSegment() == null ? "" : u.getLastPathSegment().toLowerCase();
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".heic")) return "image/heic";
        if (n.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
}
