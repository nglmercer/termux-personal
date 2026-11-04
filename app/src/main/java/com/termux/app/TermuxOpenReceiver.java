package com.termux.app;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import com.termux.shared.termux.plugins.TermuxPluginUtils;
import com.termux.shared.data.DataUtils;
import com.termux.app.compat.AndroidUrlLauncher;
import com.termux.shared.net.uri.UriUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.net.uri.UriScheme;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import androidx.annotation.NonNull;

public class TermuxOpenReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "TermuxOpenReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        final Uri data = intent.getData();
        if (data == null) {
            Logger.logError(LOG_TAG, "Called without intent data");
            return;
        }


        final String contentTypeExtra = intent.getStringExtra("content-type");
        final boolean useChooser = intent.getBooleanExtra("chooser", false);
        final String requestedAction = intent.getAction();
        final String intentAction;
        if (requestedAction == null || Intent.ACTION_VIEW.equals(requestedAction)) intentAction = Intent.ACTION_VIEW;
        else if (Intent.ACTION_SEND.equals(requestedAction)) intentAction = Intent.ACTION_SEND;
        else {
            Logger.logError(LOG_TAG, "Invalid action, using view");
            intentAction = Intent.ACTION_VIEW;
        }

        String scheme = data.getScheme();
        if (scheme != null && !UriScheme.SCHEME_FILE.equals(scheme)) {
            if (Intent.ACTION_VIEW.equals(intentAction) && contentTypeExtra == null &&
                ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                AndroidUrlLauncher.Status status = AndroidUrlLauncher.openFromReceiver(data.toString());
                Logger.logDebug(LOG_TAG, "Web launch status: " + status);
                android.app.Activity foreground = AndroidUrlLauncher.resumedActivity();
                String notice = null;
                if (status == AndroidUrlLauncher.Status.QUEUED_FOR_USER && (foreground == null || foreground.isFinishing()))
                    notice = context.getString(com.termux.R.string.compat_url_queued_notice);
                else if (status != AndroidUrlLauncher.Status.DISPATCHED_UNCONFIRMED && status != AndroidUrlLauncher.Status.QUEUED_FOR_USER)
                    notice = context.getString(com.termux.R.string.compat_url_failed, status.name());
                if (notice != null) {
                    try { android.widget.Toast.makeText(context, notice, android.widget.Toast.LENGTH_LONG).show(); }
                    catch (RuntimeException e) { Logger.logWarn(LOG_TAG, "Could not show web request notice"); }
                }
                return;
            }
            Intent urlIntent = new Intent(intentAction, data);
            if (intentAction.equals(Intent.ACTION_SEND)) {
                urlIntent.putExtra(Intent.EXTRA_TEXT, data.toString());
                urlIntent.setData(null);
            } else if (contentTypeExtra != null) {
                urlIntent.setDataAndType(data, contentTypeExtra);
            }
            urlIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(urlIntent);
            } catch (ActivityNotFoundException e) {
                Logger.logError(LOG_TAG, "No app handles the requested URI");
            } catch (SecurityException e) {
                Logger.logError(LOG_TAG, "Android restricted the requested URI launch");
                showRestrictedNotice(context);
            }
            return;
        }

        // Get full path including fragment (anything after last "#")
        String filePath = UriUtils.getUriFilePathWithFragment(data);
        if (DataUtils.isNullOrEmpty(filePath)) {
            Logger.logError(LOG_TAG, "filePath is null or empty");
            return;
        }

        final File fileToShare = new File(filePath);
        if (!(fileToShare.isFile() && fileToShare.canRead())) {
            Logger.logError(LOG_TAG, "File is not readable");
            return;
        }

        Intent sendIntent = new Intent();
        sendIntent.setAction(intentAction);
        sendIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        String contentTypeToUse;
        if (contentTypeExtra == null) {
            String fileName = fileToShare.getName();
            int lastDotIndex = fileName.lastIndexOf('.');
            String fileExtension = fileName.substring(lastDotIndex + 1);
            MimeTypeMap mimeTypes = MimeTypeMap.getSingleton();
            // Lower casing makes it work with e.g. "JPG":
            contentTypeToUse = mimeTypes.getMimeTypeFromExtension(fileExtension.toLowerCase());
            if (contentTypeToUse == null) contentTypeToUse = "application/octet-stream";
        } else {
            contentTypeToUse = contentTypeExtra;
        }

        // Do not create Uri with Uri.parse() and use Uri.Builder().path(), check UriUtils.getUriFilePath().
        Uri uriToShare = UriUtils.getContentUri(TermuxConstants.TERMUX_FILE_SHARE_URI_AUTHORITY, fileToShare.getAbsolutePath());

        if (Intent.ACTION_SEND.equals(intentAction)) {
            sendIntent.putExtra(Intent.EXTRA_STREAM, uriToShare);
            sendIntent.setType(contentTypeToUse);
        } else {
            sendIntent.setDataAndType(uriToShare, contentTypeToUse);
        }

        if (useChooser) {
            sendIntent = Intent.createChooser(sendIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        try {
            context.startActivity(sendIntent);
        } catch (ActivityNotFoundException e) {
            Logger.logError(LOG_TAG, "No app handles the requested URI");
        } catch (SecurityException e) {
            Logger.logError(LOG_TAG, "Android restricted the requested file launch");
            showRestrictedNotice(context);
        }
    }

    private static void showRestrictedNotice(Context context) {
        try { android.widget.Toast.makeText(context, com.termux.R.string.compat_launch_restricted, android.widget.Toast.LENGTH_LONG).show(); }
        catch (RuntimeException e) { Logger.logWarn(LOG_TAG, "Could not show launch restriction notice"); }
    }

    public static class ContentProvider extends android.content.ContentProvider {

        private static final String LOG_TAG = "TermuxContentProvider";

        /** Both paths must already be canonical; compare directory boundaries, not string prefixes. */
        static boolean withinCanonicalRoot(String path, String root) {
            if (path == null || root == null || !root.startsWith("/") || !path.startsWith("/")) return false;
            return path.equals(root) || path.startsWith(root.endsWith("/") ? root : root + "/");
        }

        static boolean isProtectedCanonicalPath(String path, java.util.List<String> protectedPaths) throws IOException {
            for (String protectedPath : protectedPaths)
                if (path.equals(new File(protectedPath).getCanonicalPath())) return true;
            return false;
        }

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
            File file = new File(uri.getPath());

            if (projection == null) {
                projection = new String[]{
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns._ID
                };
            }

            Object[] row = new Object[projection.length];
            for (int i = 0; i < projection.length; i++) {
                String column = projection[i];
                Object value;
                switch (column) {
                    case MediaStore.MediaColumns.DISPLAY_NAME:
                        value = file.getName();
                        break;
                    case MediaStore.MediaColumns.SIZE:
                        value = (int) file.length();
                        break;
                    case MediaStore.MediaColumns._ID:
                        value = 1;
                        break;
                    default:
                        value = null;
                }
                row[i] = value;
            }

            MatrixCursor cursor = new MatrixCursor(projection);
            cursor.addRow(row);
            return cursor;
        }

        @Override
        public String getType(@NonNull Uri uri) {
            String pathComponent = uri.getLastPathSegment();
            if (pathComponent == null || pathComponent.isEmpty()) return null;

            int extIndex = pathComponent.lastIndexOf('.') + 1;
            if (extIndex > 0) {
                MimeTypeMap mimeMap = MimeTypeMap.getSingleton();
                String ext = pathComponent.substring(extIndex).toLowerCase();
                return mimeMap.getMimeTypeFromExtension(ext);
            }
            return null;
        }

        @Override
        public Uri insert(@NonNull Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
            return 0;
        }

        @Override
        public int update(@NonNull Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            return 0;
        }

        @Override
        public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
            File file = new File(uri.getPath());
            try {
                String path = file.getCanonicalPath();
                Logger.logDebug(LOG_TAG, "Open file request received; path omitted");
                String filesPath = new File(TermuxConstants.TERMUX_FILES_DIR_PATH).getCanonicalPath();
                String storagePath = Environment.getExternalStorageDirectory().getCanonicalPath();
                // See https://support.google.com/faqs/answer/7496913:
                if (!(withinCanonicalRoot(path, filesPath) || withinCanonicalRoot(path, storagePath))) {
                    throw new IllegalArgumentException("Path is outside shared roots");
                }

                // If TermuxConstants.PROP_ALLOW_EXTERNAL_APPS property to not set to "true", then throw exception
                String errmsg = TermuxPluginUtils.checkIfAllowExternalAppsPolicyIsViolated(getContext(), LOG_TAG);
                if (errmsg != null) {
                    throw new IllegalArgumentException(errmsg);
                }

                // **DO NOT** allow these files to be modified by ContentProvider exposed to external
                // apps, since they may silently modify the values for security properties like
                // TermuxConstants.PROP_ALLOW_EXTERNAL_APPS set by users without their explicit consent.
                if (isProtectedCanonicalPath(path, TermuxConstants.TERMUX_PROPERTIES_FILE_PATHS_LIST) ||
                    isProtectedCanonicalPath(path, TermuxConstants.TERMUX_FLOAT_PROPERTIES_FILE_PATHS_LIST)) {
                    mode = "r";
                }

            } catch (IOException e) {
                throw new IllegalArgumentException(e);
            }

            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
        }
    }

}
