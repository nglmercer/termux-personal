package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ClipDescription;
import android.net.Uri;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.terminal.clipboard.ImagePreview;
import com.termux.app.terminal.clipboard.ClipboardImageImporter;
import com.termux.app.terminal.clipboard.ClipboardPastePolicy;
import com.termux.app.terminal.clipboard.ClipboardTextReader;
import com.termux.app.terminal.clipboard.ImageImportException;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.TermuxConstants;
import com.termux.app.TermuxService;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.settings.properties.ClipboardImageLimits;
import com.termux.shared.termux.terminal.io.BellHandler;
import com.termux.shared.logger.Logger;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.terminal.TextStyle;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Properties;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** The {@link TerminalSessionClient} implementation that may require an {@link Activity} for its interface methods. */
public class TermuxTerminalSessionActivityClient extends TermuxTerminalSessionClientBase {

    private final TermuxActivity mActivity;

    private static final int MAX_SESSIONS = 8;
    private static final AtomicBoolean IMAGE_IMPORT_IN_PROGRESS = new AtomicBoolean();
    private static final AtomicBoolean TEXT_URI_IN_PROGRESS = new AtomicBoolean();
    // Accessed on the main thread only; neither field retains an Activity.
    private static WeakReference<TermuxTerminalSessionActivityClient> sVisibleImageClient = new WeakReference<>(null);
    private static ImageImportResult sPendingImageResult;

    private static final class ImageImportResult {
        final String sessionHandle;
        final List<File> files;
        final int errorMessage;
        final ClipboardImageLimits limits;
        final android.graphics.Bitmap preview;
        ImageImportResult(String sessionHandle, List<File> files, int errorMessage, ClipboardImageLimits limits, android.graphics.Bitmap preview) {
            this.sessionHandle = sessionHandle;
            this.files = files;
            this.errorMessage = errorMessage;
            this.limits = limits;
            this.preview = preview;
        }
    }

    private SoundPool mBellSoundPool;

    private int mBellSoundId;

    private static final String LOG_TAG = "TermuxTerminalSessionActivityClient";

    public TermuxTerminalSessionActivityClient(TermuxActivity activity) {
        this.mActivity = activity;
    }

    /**
     * Should be called when mActivity.onCreate() is called
     */
    public void onCreate() {
        // Set terminal fonts and colors
        checkForFontAndColors();
    }

    /**
     * Should be called when mActivity.onStart() is called
     */
    public void onStart() {
        // The service has connected, but data may have changed since we were last in the foreground.
        // Get the session stored in shared preferences stored by {@link #onStop} if its valid,
        // otherwise get the last session currently running.
        if (mActivity.getTermuxService() != null) {
            setCurrentSession(getCurrentStoredSessionOrLast());
            termuxSessionListNotifyUpdated();
        }

        // Restore the visible terminal geometry after a background control resize.
        TerminalSession current = mActivity.getCurrentSession();
        if (current != null && current.getPid() > 0) mActivity.getTerminalView().updateSize();
        // The current terminal session may have changed while being away, force
        // a refresh of the displayed terminal.
        mActivity.getTerminalView().onScreenUpdated();
        sVisibleImageClient = new WeakReference<>(this);
        deliverPendingImageImport();
    }

    /**
     * Should be called when mActivity.onResume() is called
     */
    public void onResume() {
        // Just initialize the mBellSoundPool and load the sound, otherwise bell might not run
        // the first time bell key is pressed and play() is called, since sound may not be loaded
        // quickly enough before the call to play(). https://stackoverflow.com/questions/35435625
        loadBellSoundPool();
    }

    /**
     * Should be called when mActivity.onStop() is called
     */
    public void onStop() {
        if (sVisibleImageClient.get() == this) sVisibleImageClient.clear();
        // Store current session in shared preferences so that it can be restored later in
        // {@link #onStart} if needed.
        setCurrentStoredSession();

        // Release mBellSoundPool resources, specially to prevent exceptions like the following to be thrown
        // java.util.concurrent.TimeoutException: android.media.SoundPool.finalize() timed out after 10 seconds
        // Bell is not played in background anyways
        // Related: https://stackoverflow.com/a/28708351/14686958
        releaseBellSoundPool();
    }

    /**
     * Should be called when mActivity.reloadActivityStyling() is called
     */
    public void onReloadActivityStyling() {
        // Set terminal fonts and colors
        checkForFontAndColors();
    }



    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) {
        if (!mActivity.isVisible()) return;

        if (mActivity.getCurrentSession() == changedSession) mActivity.getTerminalView().onScreenUpdated();
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession updatedSession) {
        if (!mActivity.isVisible()) return;

        if (updatedSession != mActivity.getCurrentSession()) {
            // Only show toast for other sessions than the current one, since the user
            // probably consciously caused the title change to change in the current session
            // and don't want an annoying toast for that.
            mActivity.showToast(toToastTitle(updatedSession), true);
        }

        termuxSessionListNotifyUpdated();
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        TermuxService service = mActivity.getTermuxService();

        if (service == null || service.wantsToStop()) {
            // The service wants to stop as soon as possible.
            mActivity.finishActivityIfNotFinishing();
            return;
        }

        int index = service.getIndexOfSession(finishedSession);

        // For plugin commands that expect the result back, we should immediately close the session
        // and send the result back instead of waiting fo the user to press enter.
        // The plugin can handle/show errors itself.
        boolean isPluginExecutionCommandWithPendingResult = false;
        TermuxSession termuxSession = service.getTermuxSession(index);
        if (termuxSession != null) {
            isPluginExecutionCommandWithPendingResult = termuxSession.getExecutionCommand().isPluginExecutionCommandWithPendingResult();
            if (isPluginExecutionCommandWithPendingResult)
                Logger.logVerbose(LOG_TAG, "The \"" + finishedSession.mSessionName + "\" session will be force finished automatically since result in pending.");
        }

        if (mActivity.isVisible() && finishedSession != mActivity.getCurrentSession()) {
            // Show toast for non-current sessions that exit.
            // Verify that session was not removed before we got told about it finishing:
            if (index >= 0)
                mActivity.showToast(toToastTitle(finishedSession) + " - exited", true);
        }

        if (mActivity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            // On Android TV devices we need to use older behaviour because we may
            // not be able to have multiple launcher icons.
            if (service.getTermuxSessionsSize() > 1 || isPluginExecutionCommandWithPendingResult) {
                removeFinishedSession(finishedSession);
            }
        } else {
            // Once we have a separate launcher icon for the failsafe session, it
            // should be safe to auto-close session on exit code '0' or '130'.
            if (finishedSession.getExitStatus() == 0 || finishedSession.getExitStatus() == 130 || isPluginExecutionCommandWithPendingResult) {
                removeFinishedSession(finishedSession);
            }
        }
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        if (!mActivity.isVisible()) return;

        ShareUtils.copyTextToClipboard(mActivity, text);
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        if (!mActivity.isVisible()) return;

        TerminalSession target = session != null ? session : mActivity.getCurrentSession();
        if (target == null || !target.isRunning() || target.getEmulator() == null) return;

        ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        ClipDescription description = clip.getDescription();
        ClipData.Item first = clip.getItemAt(0);
        CharSequence directText = first.getText();
        Uri firstUri = first.getUri();
        ClipboardPastePolicy.Kind kind = ClipboardPastePolicy.classify(directText,
            description != null && description.hasMimeType("image/*"),
            description != null && description.hasMimeType("text/*"), firstUri == null ? null : firstUri.getScheme());
        if (kind == ClipboardPastePolicy.Kind.IMAGE) {
            if (clip.getItemCount() > ClipboardImageImporter.MAX_BATCH_ITEMS) {
                mActivity.showToast(mActivity.getString(R.string.clipboard_image_too_many), true);
                return;
            }
            List<Uri> uris = new ArrayList<>();
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri == null) {
                    mActivity.showToast(mActivity.getString(R.string.clipboard_image_failed), true);
                    return;
                }
                uris.add(uri);
            }
            importClipboardImages(target, uris);
            return;
        }
        if (kind == ClipboardPastePolicy.Kind.TEXT) {
            if (directText.length() > 0) target.getEmulator().paste(directText.toString());
        } else if (kind == ClipboardPastePolicy.Kind.WEB_URI) {
            target.getEmulator().paste(firstUri.toString());
        } else if (kind == ClipboardPastePolicy.Kind.TEXT_URI) {
            pasteTextUri(target, firstUri);
        } else {
            mActivity.showToast(mActivity.getString(R.string.clipboard_type_unsupported), true);
        }
    }

    private void pasteTextUri(TerminalSession target, Uri uri) {
        if (!TEXT_URI_IN_PROGRESS.compareAndSet(false, true)) {
            mActivity.showToast(mActivity.getString(R.string.clipboard_text_busy), true); return;
        }
        final Context context = mActivity.getApplicationContext();
        final String handle = target.mHandle;
        try {
            new Thread(() -> {
                String text = null;
                try (android.content.res.AssetFileDescriptor descriptor = context.getContentResolver()
                        .openTypedAssetFileDescriptor(uri, "text/*", null)) {
                    if (descriptor != null) try (InputStream input = descriptor.createInputStream()) {
                        text = ClipboardTextReader.read(input);
                    }
                } catch (Exception ignored) { /* Do not log clipboard data or URI. */ }
                final String result = text;
                boolean posted = new Handler(Looper.getMainLooper()).post(() -> {
                    TEXT_URI_IN_PROGRESS.set(false);
                    TermuxTerminalSessionActivityClient client = sVisibleImageClient.get();
                    if (client == null || !client.mActivity.isVisible()) return;
                    TerminalSession current = client.mActivity.getCurrentSession();
                    if (result == null) client.mActivity.showToast(client.mActivity.getString(R.string.clipboard_text_failed), true);
                    else if (current == null || !handle.equals(current.mHandle) || !current.isRunning() || current.getEmulator() == null)
                        client.mActivity.showToast(client.mActivity.getString(R.string.clipboard_text_session_changed), true);
                    else if (!result.isEmpty()) current.getEmulator().tryPaste(result);
                });
                if (!posted) TEXT_URI_IN_PROGRESS.set(false);
            }, "TermuxClipboardTextImport").start();
        } catch (RuntimeException e) {
            TEXT_URI_IN_PROGRESS.set(false);
            mActivity.showToast(mActivity.getString(R.string.clipboard_text_failed), true);
        }
    }

    public void importClipboardImages(TerminalSession target, List<Uri> uris) {
        importClipboardImages(target, uris, () -> { });
    }

    /** On success owns the release callback until the I/O worker has finished with the URI. */
    public boolean importClipboardImages(TerminalSession target, List<Uri> uris, Runnable releasePermission) {
        if (target == null || uris == null || releasePermission == null) return false;
        final ClipboardImageLimits limits;
        try { limits = mActivity.getProperties().getClipboardImageLimits(); }
        catch (RuntimeException e) {
            mActivity.showToast(mActivity.getString(R.string.clipboard_image_failed), true);
            return false;
        }
        final List<Uri> selectedUris = new ArrayList<>(uris);
        if (!IMAGE_IMPORT_IN_PROGRESS.compareAndSet(false, true)) {
            mActivity.showToast(mActivity.getString(R.string.clipboard_image_busy), true);
            return false;
        }
        final Context context = mActivity.getApplicationContext();
        final String sessionHandle = target.mHandle;
        Thread worker = new Thread(() -> {
            List<File> files = null;
            int errorMessage = R.string.clipboard_image_failed;
            try {
                files = ClipboardImageImporter.importImages(context, selectedUris, limits);
            } catch (ImageImportException e) {
                switch (e.getReason()) {
                    case ACCESS: errorMessage = R.string.clipboard_image_access_failed; break;
                    case UNSUPPORTED: errorMessage = R.string.clipboard_image_unsupported; break;
                    case SIZE: errorMessage = R.string.clipboard_image_size_limit; break;
                    case CACHE: errorMessage = R.string.clipboard_image_cache_full; break;
                }
            } catch (SecurityException e) {
                errorMessage = R.string.clipboard_image_access_failed;
            } catch (Exception e) {
                // Do not log clipboard contents or provider URIs.
            } finally {
                try { releasePermission.run(); } catch (RuntimeException ignored) { }
            }
            android.graphics.Bitmap preview = files == null || files.isEmpty() ? null : ImagePreview.decode(files.get(0));
            final ImageImportResult result = new ImageImportResult(sessionHandle, files, errorMessage, limits, preview);
            new Handler(Looper.getMainLooper()).post(() -> {
                sPendingImageResult = result;
                TermuxTerminalSessionActivityClient client = sVisibleImageClient.get();
                if (client != null) client.deliverPendingImageImport();
            });
        }, "TermuxClipboardImageImport");
        try {
            worker.start();
            return true;
        } catch (RuntimeException e) {
            IMAGE_IMPORT_IN_PROGRESS.set(false);
            mActivity.showToast(mActivity.getString(R.string.clipboard_image_failed), true);
            return false;
        }
    }

    /** Deliver only when a visible activity has restored a session. */
    private void deliverPendingImageImport() {
        ImageImportResult result = sPendingImageResult;
        if (result == null || !mActivity.isVisible()) return;
        TerminalSession target = mActivity.getCurrentSession();
        if (target == null && result.files != null) return;
        sPendingImageResult = null;
        IMAGE_IMPORT_IN_PROGRESS.set(false);
        boolean previewUsed = false;
        if (result.files == null) {
            mActivity.showToast(result.errorMessage == R.string.clipboard_image_size_limit ?
                mActivity.getString(result.errorMessage, result.limits.fileMiB, result.limits.batchMiB) :
                mActivity.getString(result.errorMessage), true);
        } else if (!result.sessionHandle.equals(target.mHandle) || !target.isRunning() || target.getEmulator() == null) {
            mActivity.showToast(mActivity.getString(R.string.clipboard_image_session_changed, result.files.get(0).getParent()), true);
        } else if (target.getEmulator().tryPaste(ClipboardImageImporter.quotePaths(result.files))) {
            previewUsed = showImageImportPreview(result.preview, result.files.size());
        } else {
            // Run after the queue's rejection notification so the recovery location stays visible.
            mActivity.getTerminalView().post(() -> mActivity.showToast(
                mActivity.getString(R.string.clipboard_image_not_pasted, result.files.get(0).getParent()), true));
        }
        if (result.preview != null && !previewUsed) result.preview.recycle();
    }

    private boolean showImageImportPreview(android.graphics.Bitmap preview, int count) {
        String message = mActivity.getResources().getQuantityString(R.plurals.clipboard_image_imported_count, count, count);
        if (preview == null) { mActivity.showToast(message, false); return false; }
        try {
            android.widget.LinearLayout content = new android.widget.LinearLayout(mActivity);
            content.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            content.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int padding = (int) (12 * mActivity.getResources().getDisplayMetrics().density);
            content.setPadding(padding, padding, padding, padding);
            android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
            background.setColor(0xff323232);
            background.setCornerRadius(padding);
            content.setBackground(background);
            android.widget.ImageView thumbnail = new android.widget.ImageView(mActivity);
            thumbnail.setImageBitmap(preview);
            thumbnail.setContentDescription(mActivity.getString(R.string.clipboard_image_preview));
            int size = (int) (56 * mActivity.getResources().getDisplayMetrics().density);
            content.addView(thumbnail, new android.widget.LinearLayout.LayoutParams(size, size));
            android.widget.TextView text = new android.widget.TextView(mActivity);
            text.setText(message); text.setTextColor(android.graphics.Color.WHITE);
            text.setMaxWidth((int) (220 * mActivity.getResources().getDisplayMetrics().density));
            text.setPadding(padding, 0, 0, 0);
            content.addView(text);
            android.widget.Toast toast = new android.widget.Toast(mActivity);
            toast.setDuration(android.widget.Toast.LENGTH_LONG);
            toast.setView(content);
            toast.show();
            return true;
        } catch (RuntimeException e) {
            mActivity.showToast(message, false);
            return false;
        }
    }

    @Override
    public void onTerminalInputRejected(@NonNull TerminalSession session) {
        logWarn("TerminalSession", "Terminal input rejected: queue full or closed");
        if (mActivity.isVisible())
            mActivity.showToast(mActivity.getString(R.string.terminal_input_queue_full), true);
    }

    @Override
    public void onBell(@NonNull TerminalSession session) {
        if (!mActivity.isVisible()) return;

        switch (mActivity.getProperties().getBellBehaviour()) {
            case TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_VIBRATE:
                BellHandler.getInstance(mActivity).doBell();
                break;
            case TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_BEEP:
                loadBellSoundPool();
                if (mBellSoundPool != null)
                    mBellSoundPool.play(mBellSoundId, 1.f, 1.f, 1, 0, 1.f);
                break;
            case TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_IGNORE:
                // Ignore the bell character.
                break;
        }
    }

    @Override
    public void onColorsChanged(@NonNull TerminalSession changedSession) {
        if (mActivity.getCurrentSession() == changedSession)
            updateBackgroundColor();
    }

    @Override
    public void onTerminalCursorStateChange(boolean enabled) {
        // Do not start cursor blinking thread if activity is not visible
        if (enabled && !mActivity.isVisible()) {
            Logger.logVerbose(LOG_TAG, "Ignoring call to start cursor blinking since activity is not visible");
            return;
        }

        // If cursor is to enabled now, then start cursor blinking if blinking is enabled
        // otherwise stop cursor blinking
        mActivity.getTerminalView().setTerminalCursorBlinkerState(enabled, false);
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession terminalSession, int pid) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;
        
        TermuxSession termuxSession = service.getTermuxSessionForTerminalSession(terminalSession);
        if (termuxSession != null)
            termuxSession.getExecutionCommand().mPid = pid;
    }


    /**
     * Should be called when mActivity.onResetTerminalSession() is called
     */
    public void onResetTerminalSession() {
        // Ensure blinker starts again after reset if cursor blinking was disabled before reset like
        // with "tput civis" which would have called onTerminalCursorStateChange()
        mActivity.getTerminalView().setTerminalCursorBlinkerState(true, true);
    }



    @Override
    public Integer getTerminalCursorStyle() {
        return mActivity.getProperties().getTerminalCursorStyle();
    }



    /** Load mBellSoundPool */
    private synchronized void loadBellSoundPool() {
        if (mBellSoundPool == null) {
            mBellSoundPool = new SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
                new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()).build();

            try {
                mBellSoundId = mBellSoundPool.load(mActivity, com.termux.shared.R.raw.bell, 1);
            } catch (Exception e){
                // Catch java.lang.RuntimeException: Unable to resume activity {com.termux/com.termux.app.TermuxActivity}: android.content.res.Resources$NotFoundException: File res/raw/bell.ogg from drawable resource ID
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load bell sound pool", e);
            }
        }
    }

    /** Release mBellSoundPool resources */
    private synchronized void releaseBellSoundPool() {
        if (mBellSoundPool != null) {
            mBellSoundPool.release();
            mBellSoundPool = null;
        }
    }



    /** Whether the activity currently displays this session. Called on the main thread. */
    public boolean isSessionVisible(TerminalSession session) {
        return mActivity.isVisible() && mActivity.getCurrentSession() == session;
    }

    /** Try switching to session. */
    public void setCurrentSession(TerminalSession session) {
        if (session == null) return;

        if (mActivity.getTerminalView().attachSession(session)) {
            // notify about switched session if not already displaying the session
            notifyOfSessionChange();
        }

        // We call the following even when the session is already being displayed since config may
        // be stale, like current session not selected or scrolled to.
        checkAndScrollToSession(session);
        updateBackgroundColor();
        deliverPendingImageImport();
        mActivity.consumePendingImagePickerWhenReady();
    }

    void notifyOfSessionChange() {
        if (!mActivity.isVisible()) return;

        if (!mActivity.getProperties().areTerminalSessionChangeToastsDisabled()) {
            TerminalSession session = mActivity.getCurrentSession();
            mActivity.showToast(toToastTitle(session), false);
        }
    }

    public void switchToSession(boolean forward) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        TerminalSession currentTerminalSession = mActivity.getCurrentSession();
        int index = service.getIndexOfSession(currentTerminalSession);
        int size = service.getTermuxSessionsSize();
        if (forward) {
            if (++index >= size) index = 0;
        } else {
            if (--index < 0) index = size - 1;
        }

        TermuxSession termuxSession = service.getTermuxSession(index);
        if (termuxSession != null)
            setCurrentSession(termuxSession.getTerminalSession());
    }

    public void switchToSession(int index) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        TermuxSession termuxSession = service.getTermuxSession(index);
        if (termuxSession != null)
            setCurrentSession(termuxSession.getTerminalSession());
    }

    @SuppressLint("InflateParams")
    public void renameSession(final TerminalSession sessionToRename) {
        if (sessionToRename == null) return;

        TextInputDialogUtils.textInput(mActivity, R.string.title_rename_session, sessionToRename.mSessionName, R.string.action_rename_session_confirm, text -> {
            renameSession(sessionToRename, text);
            termuxSessionListNotifyUpdated();
        }, -1, null, -1, null, null);
    }

    private void renameSession(TerminalSession sessionToRename, String text) {
        if (sessionToRename == null) return;
        sessionToRename.mSessionName = text;
        TermuxService service = mActivity.getTermuxService();
        if (service != null) {
            TermuxSession termuxSession = service.getTermuxSessionForTerminalSession(sessionToRename);
            if (termuxSession != null)
                termuxSession.getExecutionCommand().shellName = text;
        }
    }

    /** A new session in an explicit directory; existing sessions keep their own working directories. */
    public void addNewSessionIn(String workingDirectory, String sessionName) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;
        if (service.getTermuxSessionsSize() >= MAX_SESSIONS) {
            new AlertDialog.Builder(mActivity).setTitle(R.string.title_max_terminals_reached).setMessage(R.string.msg_max_terminals_reached)
                .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        TermuxSession newTermuxSession = service.createTermuxSession(null, null, null, workingDirectory, false, sessionName);
        if (newTermuxSession == null) return;
        setCurrentSession(newTermuxSession.getTerminalSession());
        mActivity.getDrawer().closeDrawers();
    }

    public void addNewSession(boolean isFailSafe, String sessionName) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        if (service.getTermuxSessionsSize() >= MAX_SESSIONS) {
            new AlertDialog.Builder(mActivity).setTitle(R.string.title_max_terminals_reached).setMessage(R.string.msg_max_terminals_reached)
                .setPositiveButton(android.R.string.ok, null).show();
        } else {
            TerminalSession currentSession = mActivity.getCurrentSession();

            String workingDirectory;
            if (currentSession == null) {
                workingDirectory = mActivity.getProperties().getDefaultWorkingDirectory();
            } else {
                workingDirectory = currentSession.getCwd();
            }

            TermuxSession newTermuxSession = service.createTermuxSession(null, null, null, workingDirectory, isFailSafe, sessionName);
            if (newTermuxSession == null) return;

            TerminalSession newTerminalSession = newTermuxSession.getTerminalSession();
            setCurrentSession(newTerminalSession);

            mActivity.getDrawer().closeDrawers();
        }
    }

    public void setCurrentStoredSession() {
        TerminalSession currentSession = mActivity.getCurrentSession();
        if (currentSession != null)
            mActivity.getPreferences().setCurrentSession(currentSession.mHandle);
        else
            mActivity.getPreferences().setCurrentSession(null);
    }

    /** The current session as stored or the last one if that does not exist. */
    public TerminalSession getCurrentStoredSessionOrLast() {
        TerminalSession stored = getCurrentStoredSession();

        if (stored != null) {
            // If a stored session is in the list of currently running sessions, then return it
            return stored;
        } else {
            // Else return the last session currently running
            TermuxService service = mActivity.getTermuxService();
            if (service == null) return null;

            TermuxSession termuxSession = service.getLastTermuxSession();
            if (termuxSession != null)
                return termuxSession.getTerminalSession();
            else
                return null;
        }
    }

    private TerminalSession getCurrentStoredSession() {
        String sessionHandle = mActivity.getPreferences().getCurrentSession();

        // If no session is stored in shared preferences
        if (sessionHandle == null)
            return null;

        // Check if the session handle found matches one of the currently running sessions
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return null;

        return service.getTerminalSessionForHandle(sessionHandle);
    }

    public void removeFinishedSession(TerminalSession finishedSession) {
        // Return pressed with finished session - remove it.
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        int index = service.removeTermuxSession(finishedSession);

        int size = service.getTermuxSessionsSize();
        if (size == 0) {
            // There are no sessions to show, so finish the activity.
            mActivity.finishActivityIfNotFinishing();
        } else {
            if (index >= size) {
                index = size - 1;
            }
            TermuxSession termuxSession = service.getTermuxSession(index);
            if (termuxSession != null)
                setCurrentSession(termuxSession.getTerminalSession());
        }
    }

    public void termuxSessionListNotifyUpdated() {
        mActivity.termuxSessionListNotifyUpdated();
    }

    public void checkAndScrollToSession(TerminalSession session) {
        if (!mActivity.isVisible()) return;
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        final int indexOfSession = service.getIndexOfSession(session);
        if (indexOfSession < 0) return;
        final ListView termuxSessionsListView = mActivity.findViewById(R.id.terminal_sessions_list);
        if (termuxSessionsListView == null) return;

        termuxSessionsListView.setItemChecked(indexOfSession, true);
        // Delay is necessary otherwise sometimes scroll to newly added session does not happen
        termuxSessionsListView.postDelayed(() -> termuxSessionsListView.smoothScrollToPosition(indexOfSession), 1000);
    }


    String toToastTitle(TerminalSession session) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return null;

        final int indexOfSession = service.getIndexOfSession(session);
        if (indexOfSession < 0) return null;
        StringBuilder toastTitle = new StringBuilder("[" + (indexOfSession + 1) + "]");
        if (!TextUtils.isEmpty(session.mSessionName)) {
            toastTitle.append(" ").append(session.mSessionName);
        }
        String title = session.getTitle();
        if (!TextUtils.isEmpty(title)) {
            // Space to "[${NR}] or newline after session name:
            toastTitle.append(session.mSessionName == null ? " " : "\n");
            toastTitle.append(title);
        }
        return toastTitle.toString();
    }


    public void checkForFontAndColors() {
        try {
            File colorsFile = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
            File fontFile = TermuxConstants.TERMUX_FONT_FILE;

            final Properties props = new Properties();
            if (colorsFile.isFile()) {
                try (InputStream in = new FileInputStream(colorsFile)) {
                    props.load(in);
                }
            }

            TerminalColors.COLOR_SCHEME.updateWith(props);
            TerminalSession session = mActivity.getCurrentSession();
            if (session != null && session.getEmulator() != null) {
                session.getEmulator().mColors.reset();
            }
            updateBackgroundColor();

            final Typeface newTypeface = (fontFile.exists() && fontFile.length() > 0) ? Typeface.createFromFile(fontFile) : Typeface.MONOSPACE;
            mActivity.getTerminalView().setTypeface(newTypeface);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error in checkForFontAndColors()", e);
        }
    }

    public void updateBackgroundColor() {
        if (!mActivity.isVisible()) return;
        TerminalSession session = mActivity.getCurrentSession();
        if (session != null && session.getEmulator() != null) {
            mActivity.getWindow().getDecorView().setBackgroundColor(session.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        }
    }

}
