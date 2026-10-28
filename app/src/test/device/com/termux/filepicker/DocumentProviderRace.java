package com.termux.filepicker;

import android.database.Cursor;
import android.provider.DocumentsContract.Document;
import android.system.Os;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises real provider operations while a directory is replaced with an outside link. */
public final class DocumentProviderRace {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        File base = File.createTempFile("provider-race-", "", new File(System.getenv("TMPDIR")));
        check(base.delete() && base.mkdir());
        File root = new File(base, "home"), outside = new File(base, "outside");
        File dir = new File(root, "dir"), held = new File(root, "held");
        File local = new File(dir, "local"), secret = new File(outside, "outside_secret");
        check(root.mkdir() && outside.mkdir() && dir.mkdir());
        try (FileOutputStream out = new FileOutputStream(local)) { out.write(1); }
        try (FileOutputStream out = new FileOutputStream(secret)) { out.write(1); }
        TermuxDocumentsProvider provider = new TermuxDocumentsProvider(root, SafeDocumentOpen::create,
            SafeDocumentOpen::delete, SafeDocumentOpen::list, SafeDocumentOpen::stat);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread swapper = new Thread(() -> {
            try {
                while (running.get()) {
                    check(dir.renameTo(held));
                    Os.symlink(outside.getAbsolutePath(), dir.getAbsolutePath());
                    check(dir.delete());
                    check(held.renameTo(dir));
                }
            } catch (Throwable t) { failure.set(t); }
        });
        try {
            swapper.start();
            for (int i = 0; i < 1000; i++) {
                try (Cursor cursor = provider.queryChildDocuments(dir.getAbsolutePath(), null, (String) null)) {
                    int column = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME);
                    while (cursor.moveToNext()) check(!"outside_secret".equals(cursor.getString(column)));
                } catch (FileNotFoundException expected) { /* The directory may be between replacements. */ }
                try (Cursor cursor = provider.queryDocument(new File(dir, "outside_secret").getAbsolutePath(), null)) {
                    throw new AssertionError("Outside metadata was exposed");
                } catch (FileNotFoundException expected) { }
                try (Cursor cursor = provider.querySearchDocuments(root.getAbsolutePath(), "outside_secret", null)) {
                    check(cursor.getCount() == 0);
                }
            }
        } finally {
            running.set(false); swapper.join();
            if (held.exists() && !dir.exists()) held.renameTo(dir);
            local.delete(); dir.delete(); root.delete(); secret.delete(); outside.delete(); base.delete();
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
        System.out.println("Android provider directory swap race passed");
    }
}
