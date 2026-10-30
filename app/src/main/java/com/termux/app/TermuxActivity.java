package com.termux.app;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.control.ControlCore;
import com.termux.app.monitor.ProcessSnapshot;
import com.termux.app.monitor.ProcessTable;
import com.termux.app.monitor.DeviceSnapshot;
import com.termux.app.monitor.DeviceCpuSnapshot;
import com.termux.app.api.file.FileReceiverActivity;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.clipboard.ClipboardImageImporter;
import com.termux.app.terminal.clipboard.SharedImageUris;
import com.termux.app.terminal.clipboard.PickerImageSelection;
import com.termux.app.terminal.clipboard.ImagePickerDelivery;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.app.activities.HelpActivity;
import com.termux.app.activities.ExtraKeysEditorActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.io.TerminalToolbarViewPager;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager.widget.ViewPager;

import java.util.Arrays;
import java.util.EnumSet;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends AppCompatActivity implements ServiceConnection {

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermuxService;

    /**
     * The {@link TerminalView} shown in  {@link TermuxActivity} that displays the terminal.
     */
    TerminalView mTerminalView;

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link TermuxActivity}.
     */
    TermuxTerminalViewClient mTermuxTerminalViewClient;

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxActivity}.
     */
    TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /**
     * Termux app shared preferences manager.
     */
    private TermuxAppSharedPreferences mPreferences;

    /**
     * Termux app SharedProperties loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * The root view of the {@link TermuxActivity}.
     */
    TermuxActivityRootView mTermuxActivityRootView;

    /**
     * The space at the bottom of {@link @mTermuxActivityRootView} of the {@link TermuxActivity}.
     */
    View mTermuxActivityBottomSpaceView;

    /**
     * The terminal extra keys view.
     */
    ExtraKeysView mExtraKeysView;

    /**
     * The client for the {@link #mExtraKeysView}.
     */
    TermuxTerminalExtraKeys mTermuxTerminalExtraKeys;

    /**
     * The termux sessions list controller.
     */
    TermuxSessionsListViewController mTermuxSessionListViewController;

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    /**
     * If onResume() was called after onCreate().
     */
    private boolean mIsOnResumeAfterOnCreate = false;

    /**
     * If activity was restarted like due to call to {@link #recreate()} after receiving
     * {@link TERMUX_ACTIVITY#ACTION_RELOAD_STYLE}, system dark night mode was changed or activity
     * was killed by android.
     */
    private boolean mIsActivityRecreated = false;

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private boolean mIsInvalidState;

    private int mNavBarHeight;

    private float mTerminalToolbarDefaultHeight;


    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_KILL_PROCESS_ID = 4;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_HELP_ID = 7;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_REPORT_ID = 9;
    private static final int CONTEXT_MENU_LOCAL_CONTROL = 13;
    private static final int CONTEXT_MENU_DIAGNOSTICS = 14;
    private static final int CONTEXT_MENU_IMPORT_IMAGE = 12;
    private static final int REQUEST_IMPORT_IMAGE = 48372;
    private static final String STATE_IMAGE_IMPORT_SESSION = "image_import_session";
    private static final String STATE_IMAGE_IMPORT_URIS = "image_import_uris";
    private String mImageImportSessionHandle;
    private static final int CONTEXT_MENU_PROCESS_SNAPSHOT = 15;
    private static final int CONTEXT_MENU_JOBS = 16;
    private static final int CONTEXT_MENU_FILES = 17;
    private static final int CONTEXT_MENU_WORKSPACES = 18;
    private final java.util.concurrent.atomic.AtomicBoolean mProcessSnapshotBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private AlertDialog mProcessDialog;
    private final android.os.Handler mProcessRefreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mProcessRefresh = this::loadProcessSnapshot;
    private boolean mProcessAutoRefresh;
    private int mProcessDialogGeneration;
    private ProcessSnapshot mPreviousProcessSnapshot;
    private java.util.Set<Integer> mPreviousProcessRoots;
    private long mPreviousProcessSnapshotTime;
    private ArrayList<Uri> mPendingPickerUris;
    private android.widget.TextView mProcessText;
    private EditText mProcessSearch;
    private android.widget.Spinner mProcessSort, mProcessScope;
    private static final int[] PROCESS_INTERVALS_MS = {2000, 5000, 10000, 30000};
    private int mProcessIntervalMs = 2000;
    private ProcessTable mProcessTable;
    private String mProcessHeader;
    private DeviceCpuSnapshot mPreviousDeviceCpu;
    private final java.util.ArrayDeque<String> mDeviceCpuHistory = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<String> mDeviceMemoryHistory = new java.util.ArrayDeque<>();

    private static final String ARG_TERMINAL_TOOLBAR_TEXT_INPUT = "terminal_toolbar_text_input";
    private static final String ARG_ACTIVITY_RECREATED = "activity_recreated";

    private static final String LOG_TAG = "TermuxActivity";
    public static final String EXTRA_SHOW_LOCAL_CONTROL = "com.termux.app.SHOW_LOCAL_CONTROL";
    private boolean mShowLocalControlPending;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");
        mIsOnResumeAfterOnCreate = true;

        if (savedInstanceState != null) {
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false);
            mImageImportSessionHandle = savedInstanceState.getString(STATE_IMAGE_IMPORT_SESSION);
            ArrayList<Uri> restored = savedInstanceState.getParcelableArrayList(STATE_IMAGE_IMPORT_URIS);
            if (restored != null) {
                try { mPendingPickerUris = PickerImageSelection.validate(restored); }
                catch (RuntimeException ignored) { mImageImportSessionHandle = null; }
            }
        }

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        // Load Termux app SharedProperties from disk
        mProperties = TermuxAppSharedProperties.getProperties();
        reloadProperties();

        setActivityTheme();

        super.onCreate(savedInstanceState);
        mShowLocalControlPending = getIntent() != null && getIntent().getBooleanExtra(EXTRA_SHOW_LOCAL_CONTROL, false);

        setContentView(R.layout.activity_termux);

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true;
            return;
        }

        setMargins();

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view);
        mTermuxActivityRootView.setActivity(this);
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view);
        mTermuxActivityRootView.setOnApplyWindowInsetsListener(new TermuxActivityRootView.WindowInsetsListener());

        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            mNavBarHeight = insets.getSystemWindowInsetBottom();
            return insets;
        });

        if (mProperties.isUsingFullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        setTermuxTerminalViewAndClients();

        setTerminalToolbarView(savedInstanceState);

        setSettingsButtonView();

        setNewSessionButtonView();

        setToggleKeyboardView();

        registerForContextMenu(mTerminalView);

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        try {
            // Start the {@link TermuxService} and make it run regardless of who is bound to it
            Intent serviceIntent = new Intent(this, TermuxService.class);
            startService(serviceIntent);

            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!bindService(serviceIntent, this, 0))
                throw new RuntimeException("bindService() failed");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,"TermuxActivity failed to start TermuxService", e);
            Logger.showToast(this,
                getString(e.getMessage() != null && e.getMessage().contains("app is in background") ?
                    R.string.error_termux_service_start_failed_bg : R.string.error_termux_service_start_failed_general),
                true);
            mIsInvalidState = true;
            return;
        }

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this);
    }

    @Override
    public void onStart() {
        super.onStart();

        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStart();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();

        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener();

        registerTermuxActivityBroadcastReceiver();
    }

    @Override
    public void onResume() {
        super.onResume();

        Logger.logVerbose(LOG_TAG, "onResume");

        if (mIsInvalidState) return;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onResume();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG);

        if (ExtraKeysEditorActivity.consumeReloadNeeded()) reloadActivityStyling(false);

        mIsOnResumeAfterOnCreate = false;
        consumeImageShareWhenReady();
        consumePendingImagePickerWhenReady();
        showPendingLocalControl();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_SHOW_LOCAL_CONTROL, false)) {
            mShowLocalControlPending = true;
            showPendingLocalControl();
        }
        if (isImageShare(intent)) {
            setIntent(intent);
            consumeImageShareWhenReady();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;
        if (mProcessDialog != null) mProcessDialog.dismiss();
        mProcessRefreshHandler.removeCallbacks(mProcessRefresh);

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStop();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();

        removeTermuxActivityRootViewGlobalLayoutListener();

        unregisterTermuxActivityBroadcastReceiver();
        getDrawer().closeDrawers();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Logger.logDebug(LOG_TAG, "onDestroy");

        if (mIsInvalidState) return;

        if (mTermuxService != null) {
            // Do not leave service and session clients with references to activity.
            mTermuxService.unsetTermuxTerminalSessionClient();
            mTermuxService = null;
        }

        try {
            unbindService(this);
        } catch (Exception e) {
            // ignore.
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState");

        super.onSaveInstanceState(savedInstanceState);
        saveTerminalToolbarTextInput(savedInstanceState);
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true);
        savedInstanceState.putString(STATE_IMAGE_IMPORT_SESSION, mImageImportSessionHandle);
        if (mPendingPickerUris != null) savedInstanceState.putParcelableArrayList(STATE_IMAGE_IMPORT_URIS, mPendingPickerUris);
    }





    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mTermuxService = ((TermuxService.LocalBinder) service).service;

        setTermuxSessionsListView();

        final Intent intent = getIntent();
        if (!isImageShare(intent)) setIntent(null);

        if (mTermuxService.isTermuxSessionsEmpty()) {
            if (mIsVisible) {
                TermuxInstaller.setupBootstrapIfNeeded(TermuxActivity.this, () -> {
                    if (mTermuxService == null) return; // Activity might have been destroyed.
                    try {
                        boolean launchFailsafe = false;
                        if (intent != null && intent.getExtras() != null) {
                            launchFailsafe = intent.getExtras().getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                        }
                        mTermuxTerminalSessionActivityClient.addNewSession(launchFailsafe, null);
                        consumeImageShareWhenReady();
                        consumePendingImagePickerWhenReady();
                    } catch (WindowManager.BadTokenException e) {
                        // Activity finished - ignore.
                    }
                });
            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing();
            }
        } else {
            // If termux was started from launcher "New session" shortcut and activity is recreated,
            // then the original intent will be re-delivered, resulting in a new session being re-added
            // each time.
            if (!mIsActivityRecreated && intent != null && Intent.ACTION_RUN.equals(intent.getAction())) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                boolean isFailSafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                mTermuxTerminalSessionActivityClient.addNewSession(isFailSafe, null);
            } else {
                mTermuxTerminalSessionActivityClient.setCurrentSession(mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast());
            }
        }

        // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
        mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient);
        consumeImageShareWhenReady();
        consumePendingImagePickerWhenReady();
        showPendingLocalControl();
    }

    private void showPendingLocalControl() {
        if (mShowLocalControlPending && mIsVisible && mTermuxService != null) {
            mShowLocalControlPending = false;
            showLocalControlDialog();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing();
    }






    private void reloadProperties() {
        mProperties.loadTermuxPropertiesFromDisk();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadProperties();
    }



    private void setActivityTheme() {
        // Update NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());

        // Set activity night mode. If NightMode.SYSTEM is set, then android will automatically
        // trigger recreation of activity when uiMode/dark mode configuration is changed so that
        // day or night theme takes affect.
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
    }

    private void setMargins() {
        RelativeLayout relativeLayout = findViewById(R.id.activity_termux_root_relative_layout);
        int marginHorizontal = mProperties.getTerminalMarginHorizontal();
        int marginVertical = mProperties.getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }



    public void addTermuxActivityRootViewGlobalLayoutListener() {
        getTermuxActivityRootView().getViewTreeObserver().addOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    public void removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView());
    }



    private void setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionActivityClient = new TermuxTerminalSessionActivityClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient);

        // Set termux terminal view
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onCreate();
    }

    private void setTermuxSessionsListView() {
        ListView termuxSessionsListView = findViewById(R.id.terminal_sessions_list);
        mTermuxSessionListViewController = new TermuxSessionsListViewController(this, mTermuxService.getTermuxSessions());
        termuxSessionsListView.setAdapter(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemClickListener(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemLongClickListener(mTermuxSessionListViewController);
    }



    private void setTerminalToolbarView(Bundle savedInstanceState) {
        mTermuxTerminalExtraKeys = new TermuxTerminalExtraKeys(this, mTerminalView,
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient);

        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (mPreferences.shouldShowTerminalToolbar()) terminalToolbarViewPager.setVisibility(View.VISIBLE);

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        mTerminalToolbarDefaultHeight = layoutParams.height;

        setTerminalToolbarHeight();

        String savedTextInput = null;
        if (savedInstanceState != null)
            savedTextInput = savedInstanceState.getString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT);

        terminalToolbarViewPager.setAdapter(new TerminalToolbarViewPager.PageAdapter(this, savedTextInput));
        terminalToolbarViewPager.addOnPageChangeListener(new TerminalToolbarViewPager.OnPageChangeListener(this, terminalToolbarViewPager));
    }

    private void setTerminalToolbarHeight() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        ViewGroup.LayoutParams layoutParams = terminalToolbarViewPager.getLayoutParams();
        layoutParams.height = Math.round(mTerminalToolbarDefaultHeight *
            (mTermuxTerminalExtraKeys.getExtraKeysInfo() == null ? 0 : mTermuxTerminalExtraKeys.getExtraKeysInfo().getMatrix().length) *
            mProperties.getTerminalToolbarHeightScaleFactor());
        terminalToolbarViewPager.setLayoutParams(layoutParams);
    }

    public void toggleTerminalToolbar() {
        final ViewPager terminalToolbarViewPager = getTerminalToolbarViewPager();
        if (terminalToolbarViewPager == null) return;

        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        terminalToolbarViewPager.setVisibility(showNow ? View.VISIBLE : View.GONE);
        if (showNow && isTerminalToolbarTextInputViewSelected()) {
            // Focus the text input view if just revealed.
            findViewById(R.id.terminal_toolbar_text_input).requestFocus();
        }
    }

    private void saveTerminalToolbarTextInput(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        final EditText textInputView = findViewById(R.id.terminal_toolbar_text_input);
        if (textInputView != null) {
            String textInput = textInputView.getText().toString();
            if (!textInput.isEmpty()) savedInstanceState.putString(ARG_TERMINAL_TOOLBAR_TEXT_INPUT, textInput);
        }
    }



    private void setSettingsButtonView() {
        ImageButton settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v -> {
            ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
        });
    }

    private void setNewSessionButtonView() {
        View newSessionButton = findViewById(R.id.new_session_button);
        newSessionButton.setOnClickListener(v -> mTermuxTerminalSessionActivityClient.addNewSession(false, null));
        newSessionButton.setOnLongClickListener(v -> {
            TextInputDialogUtils.textInput(TermuxActivity.this, R.string.title_create_named_session, null,
                R.string.action_create_named_session_confirm, text -> mTermuxTerminalSessionActivityClient.addNewSession(false, text),
                R.string.action_new_session_failsafe, text -> mTermuxTerminalSessionActivityClient.addNewSession(true, text),
                -1, null, null);
            return true;
        });
    }

    private void setToggleKeyboardView() {
        findViewById(R.id.toggle_keyboard_button).setOnClickListener(v -> {
            mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
            getDrawer().closeDrawers();
        });

        findViewById(R.id.toggle_keyboard_button).setOnLongClickListener(v -> {
            toggleTerminalToolbar();
            return true;
        });
    }





    @SuppressLint("RtlHardcoded")
    @Override
    public void onBackPressed() {
        if (getDrawer().isDrawerOpen(Gravity.LEFT)) {
            getDrawer().closeDrawers();
        } else {
            finishActivityIfNotFinishing();
        }
    }

    public void finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!TermuxActivity.this.isFinishing()) {
            finish();
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(TermuxActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);
        mLastToast.show();
    }



    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null) return;

        boolean autoFillEnabled = mTerminalView.isAutoFillEnabled();

        menu.add(Menu.NONE, CONTEXT_MENU_LOCAL_CONTROL, Menu.NONE, R.string.action_local_control);
        menu.add(Menu.NONE, CONTEXT_MENU_DIAGNOSTICS, Menu.NONE, R.string.action_diagnostics);
        menu.add(Menu.NONE, CONTEXT_MENU_IMPORT_IMAGE, Menu.NONE, R.string.action_import_image).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_PROCESS_SNAPSHOT, Menu.NONE, R.string.action_process_snapshot);
        menu.add(Menu.NONE, CONTEXT_MENU_JOBS, Menu.NONE, R.string.action_jobs);
        menu.add(Menu.NONE, CONTEXT_MENU_FILES, Menu.NONE, R.string.action_files);
        menu.add(Menu.NONE, CONTEXT_MENU_WORKSPACES, Menu.NONE, R.string.action_workspaces);
        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_RESET_TERMINAL_ID, Menu.NONE, R.string.action_reset_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_KILL_PROCESS_ID, Menu.NONE, getResources().getString(R.string.action_kill_process, getCurrentSession().getPid())).setEnabled(currentSession.isRunning());
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn());
        menu.add(Menu.NONE, CONTEXT_MENU_HELP_ID, Menu.NONE, R.string.action_open_help);
        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);
        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();

        switch (item.getItemId()) {
            case CONTEXT_MENU_DIAGNOSTICS:
                showDiagnostics();
                return true;
            case CONTEXT_MENU_LOCAL_CONTROL:
                showLocalControlDialog();
                return true;
            case CONTEXT_MENU_IMPORT_IMAGE:
                openImagePicker(session);
                return true;
            case CONTEXT_MENU_PROCESS_SNAPSHOT:
                requestProcessSnapshot();
                return true;
            case CONTEXT_MENU_JOBS:
                showJobs();
                return true;
            case CONTEXT_MENU_FILES:
                showFiles("home", "");
                return true;
            case CONTEXT_MENU_WORKSPACES:
                showWorkspaces();
                return true;
            case CONTEXT_MENU_SELECT_URL_ID:
                mTermuxTerminalViewClient.showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                mTermuxTerminalViewClient.shareSessionTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                mTermuxTerminalViewClient.shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_USERNAME:
                mTerminalView.requestAutoFillUsername();
                return true;
            case CONTEXT_MENU_AUTOFILL_PASSWORD:
                mTerminalView.requestAutoFillPassword();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID:
                onResetTerminalSession(session);
                return true;
            case CONTEXT_MENU_KILL_PROCESS_ID:
                showKillSessionDialog(session);
                return true;
            case CONTEXT_MENU_STYLING_ID:
                showStylingDialog();
                return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON:
                toggleKeepScreenOn();
                return true;
            case CONTEXT_MENU_HELP_ID:
                ActivityUtils.startActivity(this, new Intent(this, HelpActivity.class));
                return true;
            case CONTEXT_MENU_SETTINGS_ID:
                ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
                return true;
            case CONTEXT_MENU_REPORT_ID:
                mTermuxTerminalViewClient.reportIssueFromTranscript();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView.onContextMenuClosed(menu);
    }

    /**
     * Managed jobs started by local clients. The UI reads the same coordinator as IPC, never a terminal
     * transcript; job data is in memory, so these short reads stay on the main thread.
     */
    private void showJobs() {
        TermuxService service = getTermuxService();
        com.termux.app.control.jobs.JobCoordinator jobs = service == null ? null : service.getJobCoordinatorIfCreated();
        java.util.List<com.termux.app.control.jobs.JobCoordinator.JobView> views =
            jobs == null ? java.util.Collections.emptyList() : jobs.localJobs();
        if (views.isEmpty()) {
            new AlertDialog.Builder(this).setTitle(R.string.action_jobs).setMessage(R.string.jobs_empty)
                .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        String[] rows = new String[views.size()];
        for (int i = 0; i < rows.length; i++) {
            // Newest first; the coordinator returns oldest first.
            com.termux.app.control.jobs.JobRecord.Snapshot job = views.get(views.size() - 1 - i).job;
            rows[i] = job.id + "  " + jobStateLabel(job);
        }
        new AlertDialog.Builder(this).setTitle(R.string.action_jobs)
            .setItems(rows, (dialog, which) -> showJob(jobs, views.get(views.size() - 1 - which).job.id))
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    private String jobStateLabel(com.termux.app.control.jobs.JobRecord.Snapshot job) {
        String state = job.state.name().toLowerCase(java.util.Locale.ROOT);
        return job.exitCode == null ? state : getString(R.string.jobs_state_exit, state, job.exitCode);
    }

    private void showJob(com.termux.app.control.jobs.JobCoordinator jobs, String id) {
        com.termux.app.control.jobs.JobRecord.Snapshot job = null;
        for (com.termux.app.control.jobs.JobCoordinator.JobView view : jobs.localJobs())
            if (view.job.id.equals(id)) job = view.job;
        if (job == null) return;
        StringBuilder text = new StringBuilder(getString(R.string.jobs_detail_state, jobStateLabel(job)));
        if (job.failure != null) text.append("\n").append(getString(R.string.jobs_detail_failure, job.failure.name()));
        appendJobOutput(text, jobs, id, com.termux.app.control.jobs.JobOutputStore.Stream.STDOUT, R.string.jobs_detail_stdout);
        appendJobOutput(text, jobs, id, com.termux.app.control.jobs.JobOutputStore.Stream.STDERR, R.string.jobs_detail_stderr);
        android.widget.TextView body = new android.widget.TextView(this);
        body.setText(text); // Plain text: output is never interpreted as markup.
        body.setTextIsSelectable(true);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(body);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(id).setView(scroll)
            .setPositiveButton(R.string.jobs_refresh, (dialog, which) -> showJob(jobs, id))
            .setNegativeButton(android.R.string.cancel, null);
        if (!com.termux.app.control.jobs.JobRecord.isTerminal(job.state))
            builder.setNeutralButton(R.string.jobs_cancel, (dialog, which) -> confirmJobCancel(jobs, id));
        builder.show();
    }

    /** Shows the newest retained bytes of one stream, at most one read's worth. */
    private void appendJobOutput(StringBuilder text, com.termux.app.control.jobs.JobCoordinator jobs, String id,
                                 com.termux.app.control.jobs.JobOutputStore.Stream stream, int label) {
        final int window = 16 * 1024;
        com.termux.app.control.jobs.JobCoordinator.OutputResult probe = jobs.localOutput(id, stream, 0, 1);
        if (probe == null) return;
        long end = probe.output.endOffset;
        com.termux.app.control.jobs.JobCoordinator.OutputResult tail = jobs.localOutput(id, stream, Math.max(0, end - window), window);
        if (tail == null) return;
        text.append("\n\n").append(getString(label)).append("\n");
        if (tail.output.offset > 0) text.append(getString(R.string.jobs_detail_truncated)).append("\n");
        text.append(new String(tail.output.data, java.nio.charset.StandardCharsets.UTF_8));
    }

    private void confirmJobCancel(com.termux.app.control.jobs.JobCoordinator jobs, String id) {
        new AlertDialog.Builder(this).setTitle(R.string.jobs_cancel).setMessage(getString(R.string.jobs_cancel_confirm, id))
            .setPositiveButton(R.string.jobs_cancel, (dialog, which) -> {
                com.termux.app.control.jobs.JobCoordinator.CancelStatus status = jobs.localCancel(id);
                if (status == com.termux.app.control.jobs.JobCoordinator.CancelStatus.UNSUPPORTED)
                    showToast(getString(R.string.jobs_cancel_unsupported), true);
                showJob(jobs, id);
            })
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    /** java.util.function.Consumer needs API 24; the app supports API 21. */
    private interface UiResult<T> { void accept(T value); }

    /** Runs I/O on a worker and shows the result only if this activity is still visible. */
    private <T> void loadInBackground(java.util.concurrent.Callable<T> load, UiResult<T> show) {
        WeakReference<TermuxActivity> activity = new WeakReference<>(this);
        new Thread(() -> {
            T value;
            try { value = load.call(); } catch (Exception e) { value = null; }
            final T result = value;
            runOnUiThread(() -> {
                TermuxActivity current = activity.get();
                if (current == null || !current.isVisible()) return;
                if (result == null) current.showToast(current.getString(R.string.files_unavailable), true);
                else show.accept(result);
            });
        }, "TermuxUiLoad").start();
    }

    /**
     * Read-only browser over the same race-safe file access as the file RPC. Links are shown and never followed;
     * names are plain text. Listing and previews run off the main thread.
     */
    private void showFiles(String rootId, String path) {
        loadInBackground(() -> {
            com.termux.app.control.files.DescriptorAccess access = com.termux.app.control.files.DescriptorAccess.createIfAvailable();
            if (access == null) return null;
            java.util.Map<String, java.io.File> roots = new java.util.LinkedHashMap<>();
            roots.put("home", new java.io.File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH));
            roots.put("prefix", new java.io.File(com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH));
            com.termux.app.control.files.FileService files = new com.termux.app.control.files.FileService(roots, access);
            return files.list(rootId, path, 0, com.termux.app.control.files.FileService.MAX_PAGE).entries;
        }, entries -> {
            java.util.List<String> rows = new java.util.ArrayList<>();
            boolean atRoot = path.isEmpty();
            rows.add(atRoot ? getString("home".equals(rootId) ? R.string.files_switch_prefix : R.string.files_switch_home) : "..");
            for (com.termux.app.control.files.FileService.Entry entry : entries)
                rows.add(entry.name + ("directory".equals(entry.type) ? "/" : "symlink".equals(entry.type) ? " →" : ""));
            String title = rootId + ":" + (atRoot ? "/" : path) + (entries.size() >= com.termux.app.control.files.FileService.MAX_PAGE ?
                " " + getString(R.string.files_first_page) : "");
            new AlertDialog.Builder(this).setTitle(title)
                .setItems(rows.toArray(new String[0]), (dialog, which) -> {
                    if (which == 0) {
                        if (atRoot) showFiles("home".equals(rootId) ? "prefix" : "home", "");
                        else showFiles(rootId, path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "");
                        return;
                    }
                    com.termux.app.control.files.FileService.Entry entry = entries.get(which - 1);
                    if ("directory".equals(entry.type)) showFiles(rootId, entry.path);
                    else if ("file".equals(entry.type)) previewFile(rootId, entry);
                    else showToast(getString(R.string.files_not_followed), false);
                })
                .setNegativeButton(android.R.string.cancel, null).show();
        });
    }

    private void previewFile(String rootId, com.termux.app.control.files.FileService.Entry entry) {
        loadInBackground(() -> {
            com.termux.app.control.files.DescriptorAccess access = com.termux.app.control.files.DescriptorAccess.createIfAvailable();
            if (access == null) return null;
            java.util.Map<String, java.io.File> roots = new java.util.LinkedHashMap<>();
            roots.put("home", new java.io.File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH));
            roots.put("prefix", new java.io.File(com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH));
            return new com.termux.app.control.files.FileService(roots, access).read(rootId, entry.path, 0, 16 * 1024);
        }, chunk -> {
            java.nio.charset.CharsetDecoder decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder();
            String text;
            try { text = decoder.decode(java.nio.ByteBuffer.wrap(chunk.data)).toString(); }
            catch (java.nio.charset.CharacterCodingException e) { text = null; }
            if (text != null && text.indexOf('\0') >= 0) text = null;
            String body = text == null ? getString(R.string.files_binary, entry.size) :
                text + (chunk.eof ? "" : "\n\n" + getString(R.string.files_preview_truncated));
            android.widget.TextView view = new android.widget.TextView(this);
            view.setText(body); // Plain text only; file contents are never interpreted as markup.
            view.setTextIsSelectable(true);
            view.setTypeface(android.graphics.Typeface.MONOSPACE);
            int padding = (int) (16 * getResources().getDisplayMetrics().density);
            view.setPadding(padding, padding, padding, padding);
            android.widget.ScrollView scroll = new android.widget.ScrollView(this);
            scroll.addView(view);
            new AlertDialog.Builder(this).setTitle(entry.name).setView(scroll).setPositiveButton(android.R.string.ok, null).show();
        });
    }

    /** Workspaces are references to directories. Opening one starts a new session there; others are unchanged. */
    private void showWorkspaces() {
        TermuxService service = getTermuxService();
        if (service == null) return;
        loadInBackground(() -> service.workspaceRepositoryForUi().list(), workspaces -> {
            if (workspaces.isEmpty()) {
                new AlertDialog.Builder(this).setTitle(R.string.action_workspaces).setMessage(R.string.workspaces_empty)
                    .setPositiveButton(android.R.string.ok, null).show();
                return;
            }
            String[] rows = new String[workspaces.size()];
            for (int i = 0; i < rows.length; i++) {
                com.termux.app.control.workspace.WorkspaceRepository.Workspace workspace = workspaces.get(i);
                rows[i] = workspace.name + "  " + workspace.rootId + ":" + (workspace.path.isEmpty() ? "/" : workspace.path) +
                    (workspace.available ? "" : "  " + getString(R.string.workspaces_unavailable));
            }
            new AlertDialog.Builder(this).setTitle(R.string.action_workspaces)
                .setItems(rows, (dialog, which) -> showWorkspace(service, workspaces.get(which)))
                .setNegativeButton(android.R.string.cancel, null).show();
        });
    }

    private void showWorkspace(TermuxService service, com.termux.app.control.workspace.WorkspaceRepository.Workspace workspace) {
        StringBuilder text = new StringBuilder(workspace.rootId + ":" + (workspace.path.isEmpty() ? "/" : workspace.path));
        if (!workspace.taskPresets.isEmpty()) {
            text.append("\n\n").append(getString(R.string.workspaces_presets));
            for (com.termux.app.control.workspace.WorkspaceRepository.TaskPreset preset : workspace.taskPresets)
                text.append("\n• ").append(preset.label).append(": ").append(preset.executable).append(' ')
                    .append(android.text.TextUtils.join(" ", preset.args));
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(workspace.name).setMessage(text)
            .setNegativeButton(android.R.string.cancel, null);
        if (workspace.available) builder.setPositiveButton(R.string.workspaces_open_terminal, (dialog, which) ->
            loadInBackground(() -> service.workspaceRepositoryForUi().directory(workspace.id).getPath(),
                directory -> mTermuxTerminalSessionActivityClient.addNewSessionIn(directory, workspace.name)));
        builder.show();
    }

    private void showKillSessionDialog(TerminalSession session) {
        if (session == null) return;

        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_kill_process);
        b.setPositiveButton(android.R.string.yes, (dialog, id) -> {
            dialog.dismiss();
            session.finishIfRunning();
        });
        b.setNegativeButton(android.R.string.no, null);
        b.show();
    }

    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);

            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onResetTerminalSession();
        }
    }

    private void showStylingDialog() {
        Intent stylingIntent = new Intent();
        stylingIntent.setClassName(TermuxConstants.TERMUX_STYLING_PACKAGE_NAME, TermuxConstants.TERMUX_STYLING_APP.TERMUX_STYLING_ACTIVITY_NAME);
        try {
            startActivity(stylingIntent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            // The startActivity() call is not documented to throw IllegalArgumentException.
            // However, crash reporting shows that it sometimes does, so catch it here.
            new AlertDialog.Builder(this).setMessage(getString(R.string.error_styling_not_installed))
                .setPositiveButton(R.string.action_styling_install,
                    (dialog, which) -> ActivityUtils.startActivity(this, new Intent(Intent.ACTION_VIEW, Uri.parse(TermuxConstants.TERMUX_STYLING_FDROID_PACKAGE_URL))))
                .setNegativeButton(android.R.string.cancel, null).show();
        }
    }
    private void toggleKeepScreenOn() {
        if (mTerminalView.getKeepScreenOn()) {
            mTerminalView.setKeepScreenOn(false);
            mPreferences.setKeepScreenOn(false);
        } else {
            mTerminalView.setKeepScreenOn(true);
            mPreferences.setKeepScreenOn(true);
        }
    }



    /**
     * For processes to access primary external storage (/sdcard, /storage/emulated/0, ~/storage/shared),
     * termux needs to be granted legacy WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    public void requestStoragePermission(boolean isPermissionCallback) {
        new Thread() {
            @Override
            public void run() {
                // Do not ask for permission again
                int requestCode = isPermissionCallback ? -1 : PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;

                // If permission is granted, then also setup storage symlinks.
                if(PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    TermuxActivity.this, requestCode, !isPermissionCallback)) {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request));

                    TermuxInstaller.setupStorageSymlinks(TermuxActivity.this);
                } else {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_not_granted_on_request));
                }
            }
        }.start();
    }

    /** Read clipboard description only in response to this explicit diagnostic action. */
    private void showDiagnostics() {
        String unknown = getString(R.string.diagnostics_unknown);
        StringBuilder info = new StringBuilder();
        info.append(getString(R.string.diagnostics_versions, com.termux.BuildConfig.VERSION_NAME,
            Build.VERSION.RELEASE, Build.VERSION.SDK_INT, System.getProperty("os.version", unknown)));
        TermuxService service = getTermuxService();
        if (service == null) {
            info.append("\n\n").append(getString(R.string.diagnostics_sessions_unknown));
        } else {
            int total = service.getTermuxSessionsSize(), running = 0;
            for (int i = 0; i < total; i++) {
                com.termux.shared.termux.shell.command.runner.terminal.TermuxSession item = service.getTermuxSession(i);
                if (item != null && item.getTerminalSession().isRunning()) running++;
            }
            info.append("\n\n").append(getString(R.string.diagnostics_sessions, running, total));
            int controlStatus = !service.hasLocalControl() ? R.string.diagnostics_control_off :
                service.isLocalControlStopping() ? R.string.local_control_stopping :
                service.isLocalControlRunning() ? (service.localControlAllowsSessionWrite() ?
                    R.string.diagnostics_control_write : R.string.diagnostics_control_read) : R.string.local_control_starting;
            info.append("\n").append(getString(controlStatus));
        }
        info.append("\n\n").append(getString(R.string.diagnostics_process_visibility));
        String storage = unknown;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            storage = getString(Environment.isExternalStorageManager() ? R.string.diagnostics_granted : R.string.diagnostics_not_granted);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            storage = getString(checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ?
                R.string.diagnostics_granted : R.string.diagnostics_not_granted);
        }
        info.append("\n\n").append(getString(R.string.diagnostics_storage, storage));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            NotificationManager notifications = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            String status = notifications == null ? unknown : getString(notifications.areNotificationsEnabled() ?
                R.string.diagnostics_granted : R.string.diagnostics_not_granted);
            info.append("\n").append(getString(R.string.diagnostics_notifications, status));
        }
        StringBuilder formats = new StringBuilder();
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipDescription description = clipboard == null ? null : clipboard.getPrimaryClipDescription();
            if (description != null) {
                int count = Math.min(description.getMimeTypeCount(), 16);
                for (int i = 0; i < count; i++) {
                    String mime = description.getMimeType(i);
                    if (mime == null) continue;
                    if (formats.length() != 0) formats.append(", ");
                    // Provider-controlled metadata must not grow the dialog without bound.
                    formats.append(mime.substring(0, Math.min(mime.length(), 128)).replaceAll("[\\p{Cntrl}]", "?"));
                }
                if (description.getMimeTypeCount() > count) formats.append(", …");
            }
        } catch (RuntimeException ignored) { /* Clipboard metadata may be unavailable. */ }
        info.append("\n\n").append(getString(R.string.diagnostics_clipboard, formats.length() == 0 ? unknown : formats.toString()));
        new AlertDialog.Builder(this).setTitle(R.string.action_diagnostics).setMessage(info.toString())
            .setPositiveButton(android.R.string.ok, null).show();
    }

    /** Local control has its own settings screen with grouped, remembered choices. */
    private void showLocalControlDialog() {
        startActivity(new Intent(this, com.termux.app.activities.LocalControlActivity.class));
    }

    private static boolean isImageShare(@Nullable Intent intent) {
        return intent != null && (Intent.ACTION_SEND.equals(intent.getAction()) ||
            Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()));
    }

    /** External intents are never allowed to inject text without a local confirmation. */
    private void consumeImageShareWhenReady() {
        Intent intent = getIntent();
        if (!isImageShare(intent) || !mIsVisible || mTermuxService == null) return;
        TerminalSession target = getCurrentSession();
        if (target == null) return; // A cold start may still be installing the bootstrap.
        setIntent(null);
        final List<Uri> uris;
        try {
            uris = SharedImageUris.fromIntent(intent);
        } catch (RuntimeException e) {
            showToast(getString(R.string.clipboard_image_failed), true);
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.share_image_to_terminal)
            .setMessage(getString(R.string.share_image_confirm, uris.size()))
            .setPositiveButton(R.string.share_image_import, (dialog, which) -> {
                TerminalSession current = getCurrentSession();
                if (current != target || !target.isRunning()) {
                    showToast(getString(R.string.clipboard_image_picker_session_changed), true);
                    return;
                }
                mTermuxTerminalSessionActivityClient.importClipboardImages(target, uris);
            })
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void openImagePicker(TerminalSession session) {
        if (session == null || !session.isRunning()) return;
        if (mImageImportSessionHandle != null || mPendingPickerUris != null) {
            showToast(getString(R.string.clipboard_image_busy), true); return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/png", "image/jpeg", "image/webp"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        mImageImportSessionHandle = session.mHandle;
        try {
            startActivityForResult(intent, REQUEST_IMPORT_IMAGE);
        } catch (ActivityNotFoundException e) {
            mImageImportSessionHandle = null;
            showToast(getString(R.string.clipboard_image_picker_unavailable), true);
        }
    }

    private void handleImagePickerResult(int resultCode, @Nullable Intent data) {
        if (resultCode != RESULT_OK || data == null) {
            mImageImportSessionHandle = null; mPendingPickerUris = null; return;
        }
        if (mImageImportSessionHandle == null) {
            showToast(getString(R.string.clipboard_image_picker_session_changed), true); return;
        }
        try { mPendingPickerUris = PickerImageSelection.fromIntent(data); }
        catch (PickerImageSelection.TooManyException e) {
            mImageImportSessionHandle = null;
            showToast(getString(R.string.clipboard_image_too_many), true); return;
        } catch (RuntimeException e) {
            mImageImportSessionHandle = null;
            showToast(getString(R.string.clipboard_image_failed), true); return;
        }
        consumePendingImagePickerWhenReady();
    }

    /** Only deliver the result to the originating, currently selected live session. */
    public void consumePendingImagePickerWhenReady() {
        if (mPendingPickerUris == null) return;
        boolean ready = mTermuxService != null && mTermuxTerminalSessionActivityClient != null;
        String handle = mImageImportSessionHandle;
        TerminalSession original = !ready || handle == null ? null : mTermuxService.getTerminalSessionForHandle(handle);
        TerminalSession current = ready ? getCurrentSession() : null;
        ImagePickerDelivery.Decision decision = ImagePickerDelivery.decide(mIsVisible, ready, original != null,
            current != null, original != null && original == current, original != null && original.isRunning());
        if (decision == ImagePickerDelivery.Decision.WAIT) return;
        ArrayList<Uri> uris = mPendingPickerUris;
        mPendingPickerUris = null; mImageImportSessionHandle = null;
        if (decision == ImagePickerDelivery.Decision.REJECT) {
            showToast(getString(R.string.clipboard_image_picker_session_changed), true); return;
        }
        mTermuxTerminalSessionActivityClient.importClipboardImages(original, uris);
    }

    private void requestProcessSnapshot() {
        if (mProcessDialog != null) return;
        mProcessAutoRefresh = true;
        ++mProcessDialogGeneration;
        android.widget.LinearLayout panel = new android.widget.LinearLayout(this);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        panel.setPadding(padding, 0, padding, 0);
        mProcessSearch = new EditText(this);
        mProcessSearch.setSingleLine(true);
        mProcessSearch.setHint(R.string.process_snapshot_search);
        mProcessSearch.setContentDescription(getString(R.string.process_snapshot_search));
        mProcessSearch.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(100)});
        panel.addView(mProcessSearch);
        mProcessSort = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<CharSequence> sorts = android.widget.ArrayAdapter.createFromResource(this,
            R.array.process_snapshot_sorts, android.R.layout.simple_spinner_item);
        sorts.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mProcessSort.setAdapter(sorts);
        mProcessSort.setContentDescription(getString(R.string.process_snapshot_sort));
        panel.addView(mProcessSort);
        mProcessScope = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<CharSequence> scopes = android.widget.ArrayAdapter.createFromResource(this,
            R.array.process_snapshot_scopes, android.R.layout.simple_spinner_item);
        scopes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mProcessScope.setAdapter(scopes);
        mProcessScope.setContentDescription(getString(R.string.process_snapshot_scope));
        panel.addView(mProcessScope);
        android.widget.Spinner interval = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<CharSequence> intervals = android.widget.ArrayAdapter.createFromResource(this,
            R.array.process_snapshot_intervals, android.R.layout.simple_spinner_item);
        intervals.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        interval.setAdapter(intervals);
        interval.setContentDescription(getString(R.string.process_snapshot_interval));
        int savedInterval = getPreferences(MODE_PRIVATE).getInt("process_monitor_interval_ms", 2000);
        int intervalIndex = 0;
        for (int i = 0; i < PROCESS_INTERVALS_MS.length; i++)
            if (PROCESS_INTERVALS_MS[i] == savedInterval) intervalIndex = i;
        mProcessIntervalMs = PROCESS_INTERVALS_MS[intervalIndex];
        interval.setSelection(intervalIndex);
        interval.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= PROCESS_INTERVALS_MS.length) return;
                mProcessIntervalMs = PROCESS_INTERVALS_MS[position];
                getPreferences(MODE_PRIVATE).edit().putInt("process_monitor_interval_ms", mProcessIntervalMs).apply();
                scheduleProcessRefresh();
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        panel.addView(interval);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        mProcessText = new android.widget.TextView(this);
        mProcessText.setText(R.string.process_snapshot_loading);
        scroll.addView(mProcessText);
        int tableHeight = Math.min((int) (320 * getResources().getDisplayMetrics().density),
            getResources().getDisplayMetrics().heightPixels / 2);
        panel.addView(scroll, new android.widget.LinearLayout.LayoutParams(-1, tableHeight));
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.action_process_snapshot)
            .setView(panel)
            .setPositiveButton(R.string.process_snapshot_refresh, null)
            .setNeutralButton(R.string.process_snapshot_pause, null)
            .setNegativeButton(android.R.string.cancel, null).create();
        mProcessDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (mProcessDialog != dialog) return;
            mProcessDialog = null;
            ++mProcessDialogGeneration;
            mProcessRefreshHandler.removeCallbacks(mProcessRefresh);
            mPreviousProcessSnapshot = null;
            mPreviousProcessRoots = null;
            mPreviousDeviceCpu = null; mDeviceCpuHistory.clear(); mDeviceMemoryHistory.clear();
            mProcessTable = null; mProcessHeader = null; mProcessText = null; mProcessSearch = null; mProcessSort = null; mProcessScope = null;
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> loadProcessSnapshot());
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                mProcessAutoRefresh = !mProcessAutoRefresh;
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setText(mProcessAutoRefresh ?
                    R.string.process_snapshot_pause : R.string.process_snapshot_resume);
                mProcessRefreshHandler.removeCallbacks(mProcessRefresh);
                if (mProcessAutoRefresh) loadProcessSnapshot();
            });
        });
        mProcessSearch.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { renderProcessTable(); }
            public void afterTextChanged(android.text.Editable s) { }
        });
        mProcessSort.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { renderProcessTable(); }
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        mProcessScope.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { renderProcessTable(); }
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        dialog.show();
        loadProcessSnapshot();
    }

    private void scheduleProcessRefresh() {
        mProcessRefreshHandler.removeCallbacks(mProcessRefresh);
        if (mProcessDialog != null && mProcessAutoRefresh && mIsVisible)
            mProcessRefreshHandler.postDelayed(mProcessRefresh, mProcessIntervalMs);
    }

    private void loadProcessSnapshot() {
        if (mProcessDialog == null || !mIsVisible || !mProcessSnapshotBusy.compareAndSet(false, true)) return;
        final int generation = mProcessDialogGeneration;
        TermuxService service = getTermuxService();
        if (service == null) {
            mProcessSnapshotBusy.set(false);
            setProcessMessage(getString(R.string.process_snapshot_failed));
            scheduleProcessRefresh();
            return;
        }
        final TermuxService.MonitorRoots monitorRoots = service.getMonitorRoots();
        final java.util.Set<Integer> roots = monitorRoots.pids;
        final ProcessSnapshot prior = roots.equals(mPreviousProcessRoots) ? mPreviousProcessSnapshot : null;
        final long priorTime = mPreviousProcessSnapshotTime;
        final java.util.concurrent.atomic.AtomicBoolean busy = mProcessSnapshotBusy;
        final WeakReference<TermuxActivity> activity = new WeakReference<>(this);
        final Context appContext = getApplicationContext();
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        try {
            new Thread(() -> {
                ProcessSnapshot result;
                try { result = ProcessSnapshot.collect(new java.io.File("/proc"), android.os.Process.myUid(), roots); }
                catch (RuntimeException e) { result = null; }
                final ProcessSnapshot snapshot = result;
                final long sampledAt = android.os.SystemClock.elapsedRealtime();
                long tickValue = -1, pageValue = -1;
                try {
                    tickValue = android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK);
                    pageValue = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE);
                } catch (RuntimeException ignored) { }
                final long ticksPerSecond = tickValue, pageSize = pageValue;
                ProcessTable prepared;
                try { prepared = snapshot == null ? null : new ProcessTable(snapshot, prior, sampledAt - priorTime, ticksPerSecond, pageSize,
                    monitorRoots.sessions, monitorRoots.tasks); }
                catch (RuntimeException e) { prepared = null; }
                final ProcessTable table = prepared;
                final DeviceSnapshot device = DeviceSnapshot.collect(appContext);
                final DeviceCpuSnapshot deviceCpu = DeviceCpuSnapshot.collect(new java.io.File("/proc/stat"));
                main.post(() -> {
                    busy.set(false);
                    TermuxActivity current = activity.get();
                    if (current == null || !current.isVisible() || current.isFinishing() || current.mProcessDialog == null) return;
                    if (generation != current.mProcessDialogGeneration) {
                        if (current.mProcessAutoRefresh) current.loadProcessSnapshot();
                        return;
                    }
                    if (snapshot == null || table == null) {
                        current.setProcessMessage(current.getString(R.string.process_snapshot_failed));
                    } else {
                        TermuxService currentService = current.getTermuxService();
                        TermuxService.MonitorRoots currentRoots = currentService == null ? null : currentService.getMonitorRoots();
                        if (currentRoots == null || !monitorRoots.sessions.equals(currentRoots.sessions) ||
                            !monitorRoots.tasks.equals(currentRoots.tasks) || monitorRoots.unknownTaskPids != currentRoots.unknownTaskPids) {
                            current.setProcessMessage(current.getString(R.string.process_snapshot_changed));
                        } else {
                            current.showProcessSnapshot(snapshot, table, device, deviceCpu, roots, monitorRoots.unknownTaskPids, sampledAt);
                        }
                    }
                    current.scheduleProcessRefresh();
                });
            }, "TermuxProcessSnapshot").start();
        } catch (RuntimeException e) {
            busy.set(false);
            setProcessMessage(getString(R.string.process_snapshot_failed));
            scheduleProcessRefresh();
        }
    }

    private void showProcessSnapshot(ProcessSnapshot snapshot, ProcessTable table, DeviceSnapshot device, DeviceCpuSnapshot deviceCpu, java.util.Set<Integer> roots, int unknownTaskPids,
                                     long sampledAt) {
        mProcessTable = table;
        mPreviousProcessSnapshot = snapshot;
        mPreviousProcessRoots = roots;
        mPreviousProcessSnapshotTime = sampledAt;
        StringBuilder text = new StringBuilder(getString(R.string.process_snapshot_caveat));
        String unknown = getString(R.string.diagnostics_unknown);
        String memory = unknown;
        Double memoryPercent = null;
        if (device.totalMemoryBytes != null && device.availableMemoryBytes != null) {
            Long used = DeviceSnapshot.memoryUsedBytes(device.totalMemoryBytes, device.availableMemoryBytes);
            if (used != null) {
                memory = getString(R.string.device_memory_amount, used / 1048576, device.totalMemoryBytes / 1048576);
                memoryPercent = 100.0 * used / device.totalMemoryBytes;
            }
        }
        String battery = device.batteryPercent == null ? unknown : getString(R.string.device_battery_amount, device.batteryPercent);
        String thermal = device.thermalStatus == null ? unknown : thermalStatusLabel(device.thermalStatus);
        Double cpuPercent = DeviceCpuSnapshot.percent(mPreviousDeviceCpu, deviceCpu);
        mPreviousDeviceCpu = deviceCpu;
        String cpu = formatDevicePercent(cpuPercent);
        addDeviceHistory(mDeviceCpuHistory, cpu);
        addDeviceHistory(mDeviceMemoryHistory, formatDevicePercent(memoryPercent));
        text.append("\n\n").append(getString(R.string.device_cpu, cpu));
        text.append("\n").append(getString(R.string.device_cpu_history, android.text.TextUtils.join(", ", mDeviceCpuHistory)));
        text.append("\n").append(getString(R.string.device_memory, memory));
        text.append("\n").append(getString(R.string.device_memory_history, android.text.TextUtils.join(", ", mDeviceMemoryHistory)));
        if (Boolean.TRUE.equals(device.lowMemory)) text.append("\n").append(getString(R.string.device_low_memory));
        text.append("\n").append(getString(R.string.device_battery, battery));
        text.append("\n").append(getString(R.string.device_thermal, thermal));
        if (snapshot.incomplete || snapshot.missingRoots > 0)
            text.append("\n\n").append(getString(R.string.process_snapshot_incomplete, snapshot.missingRoots));
        if (unknownTaskPids > 0) text.append("\n\n").append(getString(R.string.process_snapshot_unknown_tasks, unknownTaskPids));
        mProcessHeader = text.toString();
        renderProcessTable();
    }

    private String formatDevicePercent(Double value) {
        return value == null ? getString(R.string.diagnostics_unknown) :
            String.format(java.util.Locale.getDefault(), "%.1f%%", value);
    }

    private static void addDeviceHistory(java.util.ArrayDeque<String> history, String value) {
        if (history.size() >= 8) history.removeFirst();
        history.addLast(value);
    }

    private void setProcessMessage(String message) {
        mProcessTable = null; mProcessHeader = null;
        if (mProcessText != null) mProcessText.setText(message);
    }

    private void renderProcessTable() {
        if (mProcessText == null || mProcessTable == null || mProcessSearch == null || mProcessSort == null || mProcessScope == null) return;
        int position = mProcessSort.getSelectedItemPosition();
        ProcessTable.Sort[] sorts = ProcessTable.Sort.values();
        ProcessTable.Sort sort = position >= 0 && position < sorts.length ? sorts[position] : ProcessTable.Sort.PID;
        ProcessTable.Scope[] scopes = ProcessTable.Scope.values();
        int scopePosition = mProcessScope.getSelectedItemPosition();
        ProcessTable.Scope scope = scopePosition >= 0 && scopePosition < scopes.length ? scopes[scopePosition] : ProcessTable.Scope.OWNED;
        java.util.List<ProcessTable.TreeRow> tree = sort == ProcessTable.Sort.TREE ?
            mProcessTable.selectTree(mProcessSearch.getText().toString(), scope) : null;
        java.util.List<ProcessTable.Row> rows = tree == null ? mProcessTable.select(mProcessSearch.getText().toString(), sort, scope) : null;
        int rowCount = tree == null ? rows.size() : tree.size();
        StringBuilder text = new StringBuilder(mProcessHeader);
        if (rowCount == 0) text.append("\n\n").append(getString(R.string.process_snapshot_empty_search));
        int count = Math.min(rowCount, 100);
        for (int i = 0; i < count; i++) {
            ProcessTable.Row row = tree == null ? rows.get(i) : tree.get(i).row;
            ProcessSnapshot.Entry entry = row.entry;
            String name = entry.name.substring(0, Math.min(entry.name.length(), 80)).replaceAll("[\\p{Cntrl}]", "?");
            String rssText = row.rssKiB == null ? getString(R.string.diagnostics_unknown) : getString(R.string.process_snapshot_rss, row.rssKiB);
            String cpuText = row.cpuPercent == null ? getString(R.string.diagnostics_unknown) :
                String.format(java.util.Locale.getDefault(), "%.1f%%", row.cpuPercent);
            text.append("\n\n");
            if (tree != null && tree.get(i).depth > 0) {
                for (int level = 0; level < tree.get(i).depth; level++) text.append("  ");
                text.append("↳ ");
            }
            text.append(getString(R.string.process_snapshot_entry,
                entry.pid, entry.ppid, name, String.valueOf(entry.state), rssText, cpuText));
        }
        if (rowCount > count) text.append("\n\n").append(getString(R.string.process_snapshot_truncated));
        mProcessText.setText(text.toString());
    }

    private String thermalStatusLabel(int status) {
        int[] labels = { R.string.thermal_none, R.string.thermal_light, R.string.thermal_moderate,
            R.string.thermal_severe, R.string.thermal_critical, R.string.thermal_emergency, R.string.thermal_shutdown };
        return status >= 0 && status < labels.length ? getString(labels[status]) : getString(R.string.diagnostics_unknown);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT_IMAGE) {
            handleImagePickerResult(resultCode, data);
            return;
        }
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: " + requestCode + ", resultCode: "  + resultCode + ", data: "  + IntentUtils.getIntentString(data));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Logger.logVerbose(LOG_TAG, "onRequestPermissionsResult: requestCode: " + requestCode + ", permissions: "  + Arrays.toString(permissions) + ", grantResults: "  + Arrays.toString(grantResults));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }



    public int getNavBarHeight() {
        return mNavBarHeight;
    }

    public TermuxActivityRootView getTermuxActivityRootView() {
        return mTermuxActivityRootView;
    }

    public View getTermuxActivityBottomSpaceView() {
        return mTermuxActivityBottomSpaceView;
    }

    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    public TermuxTerminalExtraKeys getTermuxTerminalExtraKeys() {
        return mTermuxTerminalExtraKeys;
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    public DrawerLayout getDrawer() {
        return (DrawerLayout) findViewById(R.id.drawer_layout);
    }


    public ViewPager getTerminalToolbarViewPager() {
        return (ViewPager) findViewById(R.id.terminal_toolbar_view_pager);
    }

    public float getTerminalToolbarDefaultHeight() {
        return mTerminalToolbarDefaultHeight;
    }

    public boolean isTerminalViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 0;
    }

    public boolean isTerminalToolbarTextInputViewSelected() {
        return getTerminalToolbarViewPager().getCurrentItem() == 1;
    }


    public void termuxSessionListNotifyUpdated() {
        mTermuxSessionListViewController.notifyDataSetChanged();
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isOnResumeAfterOnCreate() {
        return mIsOnResumeAfterOnCreate;
    }

    public boolean isActivityRecreated() {
        return mIsActivityRecreated;
    }



    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public TermuxTerminalViewClient getTermuxTerminalViewClient() {
        return mTermuxTerminalViewClient;
    }

    public TermuxTerminalSessionActivityClient getTermuxTerminalSessionClient() {
        return mTermuxTerminalSessionActivityClient;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null)
            return mTerminalView.getCurrentSession();
        else
            return null;
    }

    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }




    public static void updateTermuxActivityStyling(Context context, boolean recreateActivity) {
        // Make sure that terminal styling is always applied.
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity);
        context.sendBroadcast(stylingIntent);
    }

    private void registerTermuxActivityBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);

        registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter);
    }

    private void unregisterTermuxActivityBroadcastReceiver() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver);
    }

    private void fixTermuxActivityBroadcastReceiverIntent(Intent intent) {
        if (intent == null) return;

        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    class TermuxActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceiverIntent(intent);

                switch (intent.getAction()) {
                    case TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH:
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash");
                        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG);
                        return;
                    case TERMUX_ACTIVITY.ACTION_RELOAD_STYLE:
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling");
                        reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true));
                        return;
                    case TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS:
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions");
                        requestStoragePermission(false);
                        return;
                    default:
                }
            }
        }
    }

    private void reloadActivityStyling(boolean recreateActivity) {
        if (mProperties != null) {
            reloadProperties();
            if (mTermuxTerminalExtraKeys != null) mTermuxTerminalExtraKeys.setExtraKeys();

            if (mExtraKeysView != null) {
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
            }

            // Update NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());
        }

        setMargins();
        setTerminalToolbarHeight();

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onReloadActivityStyling();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadActivityStyling();

        // To change the activity and drawer theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity");
            TermuxActivity.this.recreate();
        }
    }



    public static void startTermuxActivity(@NonNull final Context context) {
        ActivityUtils.startActivity(context, newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

}
