package com.termux.filepicker;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedList;
import java.util.Locale;

/**
 * A document provider for the Storage Access Framework which exposes the files in the
 * $HOME/ directory to other apps.
 * <p/>
 * Note that this replaces providing an activity matching the ACTION_GET_CONTENT intent:
 * <p/>
 * "A document provider and ACTION_GET_CONTENT should be considered mutually exclusive. If you
 * support both of them simultaneously, your app will appear twice in the system picker UI,
 * offering two different ways of accessing your stored data. This would be confusing for users."
 * - http://developer.android.com/guide/topics/providers/document-provider.html#43
 */
public class TermuxDocumentsProvider extends DocumentsProvider {

    private static final String ALL_MIME_TYPES = "*/*";

    private final File mBaseDir;
    private final Creator mCreator;
    private final Deleter mDeleter;
    private final Lister mLister;
    private final Statter mStatter;
    interface Creator { int create(File root, String id, boolean directory) throws FileNotFoundException; }
    interface Deleter { void delete(File root, String id) throws FileNotFoundException; }
    interface Lister { File[] list(File root, String id) throws FileNotFoundException; }
    interface Statter { SafeDocumentOpen.Entry stat(File root, String id) throws FileNotFoundException; }

    public TermuxDocumentsProvider() { this(TermuxConstants.TERMUX_HOME_DIR, SafeDocumentOpen::create, SafeDocumentOpen::delete, SafeDocumentOpen::list, SafeDocumentOpen::stat); }

    // Allows provider operations to be tested against an isolated temporary root.
    TermuxDocumentsProvider(File baseDir, Creator creator, Deleter deleter, Lister lister, Statter statter) { mBaseDir = baseDir; mCreator = creator; mDeleter = deleter; mLister = lister; mStatter = statter; }


    // The default columns to return information about a root if no specific
    // columns are requested in a query.
    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_MIME_TYPES,
        Root.COLUMN_FLAGS,
        Root.COLUMN_ICON,
        Root.COLUMN_TITLE,
        Root.COLUMN_SUMMARY,
        Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_AVAILABLE_BYTES
    };

    // The default columns to return information about a document if no specific
    // columns are requested in a query.
    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE
    };

    @Override
    public Cursor queryRoots(String[] projection) {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        final String applicationName = getContext().getString(R.string.application_name);

        final MatrixCursor.RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, getDocIdForFile(mBaseDir));
        row.add(Root.COLUMN_DOCUMENT_ID, getDocIdForFile(mBaseDir));
        row.add(Root.COLUMN_SUMMARY, null);
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_SEARCH | Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(Root.COLUMN_TITLE, applicationName);
        row.add(Root.COLUMN_MIME_TYPES, ALL_MIME_TYPES);
        row.add(Root.COLUMN_AVAILABLE_BYTES, mBaseDir.getFreeSpace());
        row.add(Root.COLUMN_ICON, R.mipmap.ic_launcher);
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        includeFile(result, documentId, null);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        final File parent = getFileForDocId(parentDocumentId);
        File[] children = mLister.list(mBaseDir, parent.getAbsolutePath());
        for (File file : children) {
            try { includeFile(result, null, getFileForDocId(file.getAbsolutePath())); }
            catch (FileNotFoundException ignored) { /* Skip inaccessible or linked entries. */ }
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(final String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        return SafeDocumentOpen.open(mBaseDir, documentId, mode);
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(String documentId, Point sizeHint, CancellationSignal signal) throws FileNotFoundException {
        final ParcelFileDescriptor pfd = SafeDocumentOpen.open(mBaseDir, documentId, "r");
        return new AssetFileDescriptor(pfd, 0, pfd.getStatSize());
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        File parent = getFileForDocId(parentDocumentId);
        if (!parent.isDirectory() || !DocumentPathPolicy.validName(displayName))
            throw new FileNotFoundException("Invalid document name");
        for (int index = 1; index <= 1000; index++) {
            String name = index == 1 ? displayName : displayName + " (" + index + ")";
            File newFile = new File(parent, name);
            if (mCreator.create(mBaseDir, newFile.getAbsolutePath(), Document.MIME_TYPE_DIR.equals(mimeType)) == 0)
                return newFile.getAbsolutePath();
        }
        throw new FileNotFoundException("Too many conflicting documents");
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        if (file.equals(mBaseDir)) throw new FileNotFoundException("Cannot delete root");
        mDeleter.delete(mBaseDir, file.getAbsolutePath());
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        return getMimeType(file, mStatter.stat(mBaseDir, file.getAbsolutePath()).directory);
    }

    @Override
    public Cursor querySearchDocuments(String rootId, String query, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        final File parent = getFileForDocId(rootId);
        if (!parent.equals(mBaseDir) || query == null) throw new FileNotFoundException("Invalid search root or query");
        final String normalizedQuery = query.toLowerCase(Locale.ROOT);

        // This example implementation searches file names for the query and doesn't rank search
        // results, so we can stop as soon as we find a sufficient number of matches.  Other
        // implementations might rank results and use other data about files, rather than the file
        // name, to produce a match.
        final LinkedList<File> pending = new LinkedList<>();
        pending.add(parent);

        final int MAX_SEARCH_RESULTS = 50;
        int visited = 0;
        while (!pending.isEmpty() && result.getCount() < MAX_SEARCH_RESULTS && visited++ < 10000) {
            final File file = pending.removeFirst();
            // Avoid directories outside the $HOME directory linked with symlinks (to avoid e.g. search
            // through the whole SD card).
            SafeDocumentOpen.Entry entry;
            try { entry = mStatter.stat(mBaseDir, file.getAbsolutePath()); }
            catch (FileNotFoundException e) { continue; }
            if (entry.directory) {
                File[] children;
                try { children = mLister.list(mBaseDir, file.getAbsolutePath()); }
                catch (FileNotFoundException ignored) { continue; }
                for (File child : children) {
                    if (pending.size() + visited >= 10000) break;
                    pending.add(child);
                }
            } else if (file.getName().toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                try { includeFile(result, null, file); }
                catch (FileNotFoundException ignored) { /* Entry changed while searching. */ }
            }
        }

        return result;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            String parent = getFileForDocId(parentDocumentId).getCanonicalPath();
            String child = getFileForDocId(documentId).getCanonicalPath();
            return child.startsWith(parent + File.separator);
        } catch (IOException | RuntimeException e) { return false; }
    }

    /**
     * Get the document id given a file. This document id must be consistent across time as other
     * applications may save the ID and use it to reference documents later.
     * <p/>
     * The reverse of @{link #getFileForDocId}.
     */
    private static String getDocIdForFile(File file) {
        return file.getAbsolutePath();
    }

    /**
     * Get the file given a document id (the reverse of {@link #getDocIdForFile(File)}).
     */
    private File getFileForDocId(String docId) throws FileNotFoundException {
        return DocumentPathPolicy.existing(mBaseDir, docId);
    }

    private static String getMimeType(File file, boolean directory) {
        if (directory) {
            return Document.MIME_TYPE_DIR;
        } else {
            final String name = file.getName();
            final int lastDot = name.lastIndexOf('.');
            if (lastDot >= 0) {
                final String extension = name.substring(lastDot + 1).toLowerCase();
                final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                if (mime != null) return mime;
            }
            return "application/octet-stream";
        }
    }

    /**
     * Add a representation of a file to a cursor.
     *
     * @param result the cursor to modify
     * @param docId  the document ID representing the desired file (may be null if given file)
     * @param file   the File object representing the desired file (may be null if given docID)
     */
    private void includeFile(MatrixCursor result, String docId, File file)
        throws FileNotFoundException {
        if (docId == null) {
            docId = getDocIdForFile(file);
        } else {
            file = getFileForDocId(docId);
        }

        SafeDocumentOpen.Entry entry = mStatter.stat(mBaseDir, docId);
        int flags = 0;
        if (entry.directory) {
            if (entry.writable) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        } else if (entry.writable) {
            flags |= Document.FLAG_SUPPORTS_WRITE;
        }
        if (!file.equals(mBaseDir)) {
            try { if (mStatter.stat(mBaseDir, file.getParent()).writable) flags |= Document.FLAG_SUPPORTS_DELETE; }
            catch (FileNotFoundException ignored) { }
        }

        final String displayName = file.getName();
        final String mimeType = getMimeType(file, entry.directory);
        if (mimeType.startsWith("image/")) flags |= Document.FLAG_SUPPORTS_THUMBNAIL;

        final MatrixCursor.RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_DISPLAY_NAME, displayName);
        row.add(Document.COLUMN_SIZE, entry.size);
        row.add(Document.COLUMN_MIME_TYPE, mimeType);
        row.add(Document.COLUMN_LAST_MODIFIED, entry.modified);
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(Document.COLUMN_ICON, R.mipmap.ic_launcher);
    }

}
