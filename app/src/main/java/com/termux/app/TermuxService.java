package com.termux.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.event.SystemEventReceiver;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalSessionServiceClient;
import com.termux.shared.termux.plugins.TermuxPluginUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.net.uri.UriUtils;
import com.termux.shared.errors.Errno;
import com.termux.shared.shell.ShellUtils;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.shared.termux.shell.TermuxShellUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.logger.Logger;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.ExecutionCommand.Runner;
import com.termux.shared.shell.command.ExecutionCommand.ShellCreateMode;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.util.ArrayList;
import java.util.List;

/**
 * A service holding a list of {@link TermuxSession} in {@link TermuxShellManager#mTermuxSessions} and background {@link AppShell}
 * in {@link TermuxShellManager#mTermuxTasks}, showing a foreground notification while running so that it is not terminated.
 * The user interacts with the session through {@link TermuxActivity}, but this service may outlive
 * the activity when the user or the system disposes of the activity. In that case the user may
 * restart {@link TermuxActivity} later to yet again access the sessions.
 * <p/>
 * In order to keep both terminal sessions and spawned processes (who may outlive the terminal sessions) alive as long
 * as wanted by the user this service is a foreground service, {@link Service#startForeground(int, Notification)}.
 * <p/>
 * Optionally may hold a wake and a wifi lock, in which case that is shown in the notification - see
 * {@link #buildNotification()}.
 */
public final class TermuxService extends Service implements AppShell.AppShellClient, TermuxSession.TermuxSessionClient {

    /** This service is only bound from inside the same process and never uses IPC. */
    public class LocalBinder extends Binder {
        public final TermuxService service = TermuxService.this;
    }

    private final IBinder mBinder = new LocalBinder();

    private final Handler mHandler = new Handler();


    /** The full implementation of the {@link TerminalSessionClient} interface to be used by {@link TerminalSession}
     * that holds activity references for activity related functions.
     * Note that the service may often outlive the activity, so need to clear this reference.
     */
    private TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /** The basic implementation of the {@link TerminalSessionClient} interface to be used by {@link TerminalSession}
     * that does not hold activity references and only a service reference.
     */
    private final TermuxTerminalSessionServiceClient mTermuxTerminalSessionServiceClient = new TermuxTerminalSessionServiceClient(this);

    /**
     * Termux app shared properties manager, loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * Termux app shell manager
     */
    private TermuxShellManager mShellManager;
    private com.termux.app.control.ControlCore mControlCore;
    private com.termux.app.control.OutputSubscriptions mOutputSubscriptions;
    private com.termux.app.control.ipc.ServiceThreadDispatch mControlDispatch;
    private volatile com.termux.app.control.ipc.LocalControlServer mLocalControlServer;
    private boolean mControlAvailable;
    /** Managed-job consent; revoked whenever local control stops. Running children are not signalled. */
    private final com.termux.app.control.jobs.JobAuthority mJobAuthority = new com.termux.app.control.jobs.JobAuthority();
    private final Object mJobLock = new Object();
    private com.termux.app.control.jobs.JobCoordinator mJobCoordinator;
    /** Process listing and workspace store are created once per service on the start worker, under their own lock. */
    private final Object mDomainLock = new Object();
    private com.termux.app.control.process.ProcessService mProcessService;
    private com.termux.app.control.workspace.WorkspaceRepository mWorkspaceRepository;
    private boolean mSafeControlCloseEnabled;

    /** The wake lock and wifi lock are always acquired and released together. */
    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;

    /** If the user has executed the {@link TERMUX_SERVICE#ACTION_STOP_SERVICE} intent. */
    boolean mWantsToStop = false;

    private static final String LOG_TAG = "TermuxService";

    @Override
    public void onCreate() {
        Logger.logVerbose(LOG_TAG, "onCreate");

        // Get Termux app SharedProperties without loading from disk since TermuxApplication handles
        // load and TermuxActivity handles reloads
        mProperties = TermuxAppSharedProperties.getProperties();

        mShellManager = TermuxShellManager.getShellManager();
        TerminalSession.setProcessHandlesEnabled(false);
        mControlCore = new com.termux.app.control.ControlCore(new com.termux.app.control.TermuxControlBackend(this));
        mOutputSubscriptions = new com.termux.app.control.OutputSubscriptions(
            () -> android.os.Looper.myLooper() == android.os.Looper.getMainLooper(), id -> {
                TerminalSession session = getTerminalSessionForHandle(id);
                if (session == null) throw new com.termux.app.control.ControlCore.ControlException(com.termux.app.control.ControlCore.Error.NOT_FOUND);
                if (session.getPid() <= 0) throw new com.termux.app.control.ControlCore.ControlException(com.termux.app.control.ControlCore.Error.NOT_RUNNING);
                final com.termux.terminal.OutputReplayBuffer buffer;
                try { buffer = session.enableOutputReplay(); }
                catch (IllegalStateException e) { throw new com.termux.app.control.ControlCore.ControlException(com.termux.app.control.ControlCore.Error.NOT_RUNNING); }
                return new com.termux.app.control.OutputSubscriptions.Source() {
                    @Override public com.termux.terminal.OutputReplayBuffer buffer() { return buffer; }
                    @Override public void close() { session.disableOutputReplay(); }
                };
            }, android.os.SystemClock::elapsedRealtime, (task, delay) -> {
                if (!mHandler.postDelayed(task, delay)) throw new java.util.concurrent.RejectedExecutionException();
                return () -> mHandler.removeCallbacks(task);
            });
        android.os.Handler controlHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        mControlDispatch = new com.termux.app.control.ipc.ServiceThreadDispatch(task -> {
            if (!controlHandler.post(task)) throw new java.util.concurrent.RejectedExecutionException();
        }, () -> android.os.Looper.myLooper() == android.os.Looper.getMainLooper(), 20);
        mControlAvailable = true;

        runStartForeground();

        SystemEventReceiver.registerPackageUpdateEvents(this);

        // Keeps the app-owned shell profile current after app updates; sessions started later pick it up.
        new Thread(ShellCompatibility::ensure, "TermuxShellCompat").start();

        // Only a consent the user explicitly chose to remember and auto-start is applied here.
        new android.os.Handler(android.os.Looper.getMainLooper()).post(this::autoStartLocalControl);
    }

    /** The user's remembered local control choice; written only by the app's own UI. */
    public com.termux.app.control.LocalControlConsent localControlConsent() {
        return new com.termux.app.control.LocalControlConsent(this);
    }

    private void autoStartLocalControl() {
        com.termux.app.control.LocalControlConsent consent = localControlConsent();
        if (!mControlAvailable || mLocalControlServer != null || !consent.autoStart()) return;
        java.util.EnumSet<com.termux.app.control.ControlCore.Scope> scopes = consent.scopes();
        if (scopes == null) return;
        try {
            if (!startLocalControl(scopes, started -> { if (!started) Logger.logWarn(LOG_TAG, "Saved local control did not start"); }))
                Logger.logWarn(LOG_TAG, "Saved local control was refused");
        } catch (IllegalStateException e) {
            Logger.logWarn(LOG_TAG, "Saved local control unavailable");
        }
    }

    @SuppressLint("Wakelock")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.logDebug(LOG_TAG, "onStartCommand");

        // Run again in case service is already started and onCreate() is not called
        runStartForeground();

        String action = null;
        if (intent != null) {
            Logger.logVerboseExtended(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent));
            action = intent.getAction();
        }

        if (action != null) {
            switch (action) {
                case TERMUX_SERVICE.ACTION_STOP_SERVICE:
                    Logger.logDebug(LOG_TAG, "ACTION_STOP_SERVICE intent received");
                    actionStopService();
                    break;
                case TERMUX_SERVICE.ACTION_WAKE_LOCK:
                    Logger.logDebug(LOG_TAG, "ACTION_WAKE_LOCK intent received");
                    actionAcquireWakeLock();
                    break;
                case TERMUX_SERVICE.ACTION_WAKE_UNLOCK:
                    Logger.logDebug(LOG_TAG, "ACTION_WAKE_UNLOCK intent received");
                    actionReleaseWakeLock(true);
                    break;
                case TERMUX_SERVICE.ACTION_SERVICE_EXECUTE:
                    Logger.logDebug(LOG_TAG, "ACTION_SERVICE_EXECUTE intent received");
                    actionServiceExecute(intent);
                    break;
                default:
                    Logger.logError(LOG_TAG, "Invalid action: \"" + action + "\"");
                    break;
            }
        }

        // If this service really do get killed, there is no point restarting it automatically - let the user do on next
        // start of {@link Term):
        return Service.START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Logger.logVerbose(LOG_TAG, "onDestroy");
        stopLocalControl();
        mJobAuthority.revoke();
        synchronized (mJobLock) { if (mJobCoordinator != null) mJobCoordinator.stopAccepting(); }
        if (mOutputSubscriptions != null) mOutputSubscriptions.close();
        if (mControlAvailable) setSafeControlCloseEnabled(false);
        else TerminalSession.setProcessHandlesEnabled(false);
        mControlAvailable = false;

        TermuxShellUtils.clearTermuxTMPDIR(true);

        actionReleaseWakeLock(false);
        if (!mWantsToStop)
            killAllTermuxExecutionCommands();

        TermuxShellManager.onAppExit(this);

        SystemEventReceiver.unregisterPackageUpdateEvents(this);

        runStopForeground();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Logger.logVerbose(LOG_TAG, "onBind");
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Logger.logVerbose(LOG_TAG, "onUnbind");

        // Since we cannot rely on {@link TermuxActivity.onDestroy()} to always complete,
        // we unset clients here as well if it failed, so that we do not leave service and session
        // clients with references to the activity.
        if (mTermuxTerminalSessionActivityClient != null)
            unsetTermuxTerminalSessionClient();
        return false;
    }

    /** Make service run in foreground mode. */
    private void runStartForeground() {
        setupNotificationChannel();
        startForeground(TermuxConstants.TERMUX_APP_NOTIFICATION_ID, buildNotification());
    }

    /** Make service leave foreground mode. */
    private void runStopForeground() {
        stopForeground(true);
    }

    /** Request to stop service. */
    private void requestStopService() {
        Logger.logDebug(LOG_TAG, "Requesting to stop service");
        runStopForeground();
        stopSelf();
    }

    /** Process action to stop service. */
    private void actionStopService() {
        mWantsToStop = true;
        killAllTermuxExecutionCommands();
        requestStopService();
    }

    /** Kill all TermuxSessions and TermuxTasks by sending SIGKILL to their processes.
     *
     * For TermuxSessions, all sessions will be killed, whether user manually exited Termux or if
     * onDestroy() was directly called because of unintended shutdown. The processing of results
     * will only be done if user manually exited termux or if the session was started by a plugin
     * which **expects** the result back via a pending intent.
     *
     * For TermuxTasks, only tasks that were started by a plugin which **expects** the result
     * back via a pending intent will be killed, whether user manually exited Termux or if
     * onDestroy() was directly called because of unintended shutdown. The processing of results
     * will always be done for the tasks that are killed. The remaining processes will keep on
     * running until the termux app process is killed by android, like by OOM, so we let them run
     * as long as they can.
     *
     * Some plugin execution commands may not have been processed and added to mTermuxSessions and
     * mTermuxTasks lists before the service is killed, so we maintain a separate
     * mPendingPluginExecutionCommands list for those, so that we can notify the pending intent
     * creators that execution was cancelled.
     *
     * Note that if user didn't manually exit Termux and if onDestroy() was directly called because
     * of unintended shutdown, like android deciding to kill the service, then there will be no
     * guarantee that onDestroy() will be allowed to finish and termux app process may be killed before
     * it has finished. This means that in those cases some results may not be sent back to their
     * creators for plugin commands but we still try to process whatever results can be processed
     * despite the unreliable behaviour of onDestroy().
     *
     * Note that if don't kill the processes started by plugins which **expect** the result back
     * and notify their creators that they have been killed, then they may get stuck waiting for
     * the results forever like in case of commands started by Termux:Tasker or RUN_COMMAND intent,
     * since once TermuxService has been killed, no result will be sent back. They may still get
     * stuck if termux app process gets killed, so for this case reasonable timeout values should
     * be used, like in Tasker for the Termux:Tasker actions.
     *
     * We make copies of each list since items are removed inside the loop.
     */
    private synchronized void killAllTermuxExecutionCommands() {
        boolean processResult;

        Logger.logDebug(LOG_TAG, "Killing TermuxSessions=" + mShellManager.mTermuxSessions.size() +
            ", TermuxTasks=" + mShellManager.mTermuxTasks.size() +
            ", PendingPluginExecutionCommands=" + mShellManager.mPendingPluginExecutionCommands.size());

        List<TermuxSession> termuxSessions = new ArrayList<>(mShellManager.mTermuxSessions);
        List<AppShell> termuxTasks = new ArrayList<>(mShellManager.mTermuxTasks);
        List<ExecutionCommand> pendingPluginExecutionCommands = new ArrayList<>(mShellManager.mPendingPluginExecutionCommands);

        for (int i = 0; i < termuxSessions.size(); i++) {
            ExecutionCommand executionCommand = termuxSessions.get(i).getExecutionCommand();
            processResult = mWantsToStop || executionCommand.isPluginExecutionCommandWithPendingResult();
            termuxSessions.get(i).killIfExecuting(this, processResult);
            if (!processResult)
                mShellManager.mTermuxSessions.remove(termuxSessions.get(i));
        }


        for (int i = 0; i < termuxTasks.size(); i++) {
            ExecutionCommand executionCommand = termuxTasks.get(i).getExecutionCommand();
            if (executionCommand.isPluginExecutionCommandWithPendingResult())
                termuxTasks.get(i).killIfExecuting(this, true);
            else
                mShellManager.mTermuxTasks.remove(termuxTasks.get(i));
        }

        for (int i = 0; i < pendingPluginExecutionCommands.size(); i++) {
            ExecutionCommand executionCommand = pendingPluginExecutionCommands.get(i);
            if (!executionCommand.shouldNotProcessResults() && executionCommand.isPluginExecutionCommandWithPendingResult()) {
                if (executionCommand.setStateFailed(Errno.ERRNO_CANCELLED.getCode(), this.getString(com.termux.shared.R.string.error_execution_cancelled))) {
                    TermuxPluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand);
                }
            }
        }
    }



    /** Process action to acquire Power and Wi-Fi WakeLocks. */
    @SuppressLint({"WakelockTimeout", "BatteryLife"})
    private void actionAcquireWakeLock() {
        if (mWakeLock != null) {
            Logger.logDebug(LOG_TAG, "Ignoring acquiring WakeLocks since they are already held");
            return;
        }

        Logger.logDebug(LOG_TAG, "Acquiring WakeLocks");

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TermuxConstants.TERMUX_APP_NAME.toLowerCase() + ":service-wakelock");
        mWakeLock.acquire();

        // http://tools.android.com/tech-docs/lint-in-studio-2-3#TOC-WifiManager-Leak
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TermuxConstants.TERMUX_APP_NAME.toLowerCase());
        mWifiLock.acquire();

        if (!PermissionUtils.checkIfBatteryOptimizationsDisabled(this)) {
            PermissionUtils.requestDisableBatteryOptimizations(this);
        }

        updateNotification();

        Logger.logDebug(LOG_TAG, "WakeLocks acquired successfully");

    }

    /** Process action to release Power and Wi-Fi WakeLocks. */
    private void actionReleaseWakeLock(boolean updateNotification) {
        if (mWakeLock == null && mWifiLock == null) {
            Logger.logDebug(LOG_TAG, "Ignoring releasing WakeLocks since none are already held");
            return;
        }

        Logger.logDebug(LOG_TAG, "Releasing WakeLocks");

        if (mWakeLock != null) {
            mWakeLock.release();
            mWakeLock = null;
        }

        if (mWifiLock != null) {
            mWifiLock.release();
            mWifiLock = null;
        }

        if (updateNotification)
            updateNotification();

        Logger.logDebug(LOG_TAG, "WakeLocks released successfully");
    }

    /** Process {@link TERMUX_SERVICE#ACTION_SERVICE_EXECUTE} intent to execute a shell command in
     * a foreground TermuxSession or in a background TermuxTask. */
    private void actionServiceExecute(Intent intent) {
        if (intent == null) {
            Logger.logError(LOG_TAG, "Ignoring null intent to actionServiceExecute");
            return;
        }

        ExecutionCommand executionCommand = new ExecutionCommand(TermuxShellManager.getNextShellId());

        executionCommand.executableUri = intent.getData();
        executionCommand.isPluginExecutionCommand = true;

        // If EXTRA_RUNNER is passed, use that, otherwise check EXTRA_BACKGROUND and default to Runner.TERMINAL_SESSION
        executionCommand.runner = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RUNNER,
            (intent.getBooleanExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, false) ? Runner.APP_SHELL.getName() : Runner.TERMINAL_SESSION.getName()));
        if (Runner.runnerOf(executionCommand.runner) == null) {
            String errmsg = this.getString(R.string.error_termux_service_invalid_execution_command_runner, executionCommand.runner);
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), errmsg);
            TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            return;
        }

        if (executionCommand.executableUri != null) {
            Logger.logVerbose(LOG_TAG, "uri: \"" + executionCommand.executableUri + "\", path: \"" + executionCommand.executableUri.getPath() + "\", fragment: \"" + executionCommand.executableUri.getFragment() + "\"");

            // Get full path including fragment (anything after last "#")
            executionCommand.executable = UriUtils.getUriFilePathWithFragment(executionCommand.executableUri);
            executionCommand.arguments = IntentUtils.getStringArrayExtraIfSet(intent, TERMUX_SERVICE.EXTRA_ARGUMENTS, null);
            if (Runner.APP_SHELL.equalsRunner(executionCommand.runner))
                executionCommand.stdin = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_STDIN, null);
            executionCommand.backgroundCustomLogLevel = IntentUtils.getIntegerExtraIfSet(intent, TERMUX_SERVICE.EXTRA_BACKGROUND_CUSTOM_LOG_LEVEL, null);
        }

        executionCommand.workingDirectory = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_WORKDIR, null);
        executionCommand.isFailsafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
        executionCommand.sessionAction = intent.getStringExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION);
        executionCommand.shellName = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_SHELL_NAME, null);
        executionCommand.shellCreateMode = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_SHELL_CREATE_MODE, null);
        executionCommand.commandLabel = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "Execution Intent Command");
        executionCommand.commandDescription = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_DESCRIPTION, null);
        executionCommand.commandHelp = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_COMMAND_HELP, null);
        executionCommand.pluginAPIHelp = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_PLUGIN_API_HELP, null);
        executionCommand.resultConfig.resultPendingIntent = intent.getParcelableExtra(TERMUX_SERVICE.EXTRA_PENDING_INTENT);
        executionCommand.resultConfig.resultDirectoryPath = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_DIRECTORY, null);
        if (executionCommand.resultConfig.resultDirectoryPath != null) {
            executionCommand.resultConfig.resultSingleFile = intent.getBooleanExtra(TERMUX_SERVICE.EXTRA_RESULT_SINGLE_FILE, false);
            executionCommand.resultConfig.resultFileBasename = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILE_BASENAME, null);
            executionCommand.resultConfig.resultFileOutputFormat = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILE_OUTPUT_FORMAT, null);
            executionCommand.resultConfig.resultFileErrorFormat = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILE_ERROR_FORMAT, null);
            executionCommand.resultConfig.resultFilesSuffix = IntentUtils.getStringExtraIfSet(intent, TERMUX_SERVICE.EXTRA_RESULT_FILES_SUFFIX, null);
        }

        if (executionCommand.shellCreateMode == null)
            executionCommand.shellCreateMode = ShellCreateMode.ALWAYS.getMode();

        // Add the execution command to pending plugin execution commands list
        mShellManager.mPendingPluginExecutionCommands.add(executionCommand);

        if (Runner.APP_SHELL.equalsRunner(executionCommand.runner))
            executeTermuxTaskCommand(executionCommand);
        else if (Runner.TERMINAL_SESSION.equalsRunner(executionCommand.runner))
            executeTermuxSessionCommand(executionCommand);
        else {
            String errmsg = getString(R.string.error_termux_service_unsupported_execution_command_runner, executionCommand.runner);
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), errmsg);
            TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
        }
    }





    /** Execute a shell command in background TermuxTask. */
    private void executeTermuxTaskCommand(ExecutionCommand executionCommand) {
        if (executionCommand == null) return;

        Logger.logDebug(LOG_TAG, "Executing background \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask command");

        // Transform executable path to shell/session name, e.g. "/bin/do-something.sh" => "do-something.sh".
        if (executionCommand.shellName == null && executionCommand.executable != null)
            executionCommand.shellName = ShellUtils.getExecutableBasename(executionCommand.executable);

        AppShell newTermuxTask = null;
        ShellCreateMode shellCreateMode = processShellCreateMode(executionCommand);
        if (shellCreateMode == null) return;
        if (ShellCreateMode.NO_SHELL_WITH_NAME.equals(shellCreateMode)) {
            newTermuxTask = getTermuxTaskForShellName(executionCommand.shellName);
            if (newTermuxTask != null)
                Logger.logVerbose(LOG_TAG, "Existing TermuxTask with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.getMode() + "\"");
            else
                Logger.logVerbose(LOG_TAG, "No existing TermuxTask with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.getMode() + "\"");
        }

        if (newTermuxTask == null)
            newTermuxTask = createTermuxTask(executionCommand);
    }

    /** Create a TermuxTask. */
    @Nullable
    public AppShell createTermuxTask(String executablePath, String[] arguments, String stdin, String workingDirectory) {
        return createTermuxTask(new ExecutionCommand(TermuxShellManager.getNextShellId(), executablePath,
            arguments, stdin, workingDirectory, Runner.APP_SHELL.getName(), false));
    }

    /** Create a TermuxTask. */
    @Nullable
    public synchronized AppShell createTermuxTask(ExecutionCommand executionCommand) {
        if (executionCommand == null) return null;

        Logger.logDebug(LOG_TAG, "Creating \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask");

        if (!Runner.APP_SHELL.equalsRunner(executionCommand.runner)) {
            Logger.logDebug(LOG_TAG, "Ignoring wrong runner \"" + executionCommand.runner + "\" command passed to createTermuxTask()");
            return null;
        }

        executionCommand.setShellCommandShellEnvironment = true;

        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE)
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString());

        AppShell newTermuxTask = AppShell.execute(this, executionCommand, this,
            new TermuxShellEnvironment(), null,false);
        if (newTermuxTask == null) {
            Logger.logError(LOG_TAG, "Failed to execute new TermuxTask command for:\n" + executionCommand.getCommandIdAndLabelLogString());
            // If the execution command was started for a plugin, then process the error
            if (executionCommand.isPluginExecutionCommand)
                TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            else {
                Logger.logError(LOG_TAG, "Set log level to debug or higher to see error in logs");
                Logger.logErrorPrivateExtended(LOG_TAG, executionCommand.toString());
            }
            return null;
        }

        mShellManager.mTermuxTasks.add(newTermuxTask);

        // Remove the execution command from the pending plugin execution commands list since it has
        // now been processed
        if (executionCommand.isPluginExecutionCommand)
            mShellManager.mPendingPluginExecutionCommands.remove(executionCommand);

        updateNotification();

        return newTermuxTask;
    }

    /** Callback received when a TermuxTask finishes. */
    @Override
    public void onAppShellExited(final AppShell termuxTask) {
        mHandler.post(() -> {
            if (termuxTask != null) {
                ExecutionCommand executionCommand = termuxTask.getExecutionCommand();

                Logger.logVerbose(LOG_TAG, "The onTermuxTaskExited() callback called for \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxTask command");

                // If the execution command was started for a plugin, then process the results
                if (executionCommand != null && executionCommand.isPluginExecutionCommand)
                    TermuxPluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand);

                synchronized (TermuxService.this) { mShellManager.mTermuxTasks.remove(termuxTask); }
            }

            updateNotification();
        });
    }





    /** Execute a shell command in a foreground {@link TermuxSession}. */
    private void executeTermuxSessionCommand(ExecutionCommand executionCommand) {
        if (executionCommand == null) return;

        Logger.logDebug(LOG_TAG, "Executing foreground \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession command");

        // Transform executable path to shell/session name, e.g. "/bin/do-something.sh" => "do-something.sh".
        if (executionCommand.shellName == null && executionCommand.executable != null)
            executionCommand.shellName = ShellUtils.getExecutableBasename(executionCommand.executable);

        TermuxSession newTermuxSession = null;
        ShellCreateMode shellCreateMode = processShellCreateMode(executionCommand);
        if (shellCreateMode == null) return;
        if (ShellCreateMode.NO_SHELL_WITH_NAME.equals(shellCreateMode)) {
            newTermuxSession = getTermuxSessionForShellName(executionCommand.shellName);
            if (newTermuxSession != null)
                Logger.logVerbose(LOG_TAG, "Existing TermuxSession with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.getMode() + "\"");
            else
                Logger.logVerbose(LOG_TAG, "No existing TermuxSession with \"" + executionCommand.shellName + "\" shell name found for shell create mode \"" + shellCreateMode.getMode() + "\"");
        }

        if (newTermuxSession == null)
            newTermuxSession = createTermuxSession(executionCommand);
        if (newTermuxSession == null) return;

        handleSessionAction(DataUtils.getIntFromString(executionCommand.sessionAction,
            TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY),
            newTermuxSession.getTerminalSession());
    }

    /**
     * Create a {@link TermuxSession}.
     * Currently called by {@link TermuxTerminalSessionActivityClient#addNewSession(boolean, String)} to add a new {@link TermuxSession}.
     */
    @Nullable
    public TermuxSession createTermuxSession(String executablePath, String[] arguments, String stdin,
                                             String workingDirectory, boolean isFailSafe, String sessionName) {
        ExecutionCommand executionCommand = new ExecutionCommand(TermuxShellManager.getNextShellId(),
            executablePath, arguments, stdin, workingDirectory, Runner.TERMINAL_SESSION.getName(), isFailSafe);
        executionCommand.shellName = sessionName;
        return createTermuxSession(executionCommand);
    }

    /** Create a {@link TermuxSession}. */
    @Nullable
    public synchronized TermuxSession createTermuxSession(ExecutionCommand executionCommand) {
        if (executionCommand == null) return null;

        Logger.logDebug(LOG_TAG, "Creating \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession");

        if (!Runner.TERMINAL_SESSION.equalsRunner(executionCommand.runner)) {
            Logger.logDebug(LOG_TAG, "Ignoring wrong runner \"" + executionCommand.runner + "\" command passed to createTermuxSession()");
            return null;
        }

        executionCommand.setShellCommandShellEnvironment = true;
        executionCommand.terminalTranscriptRows = mProperties.getTerminalTranscriptRows();

        if (Logger.getLogLevel() >= Logger.LOG_LEVEL_VERBOSE)
            Logger.logVerboseExtended(LOG_TAG, executionCommand.toString());

        // If the execution command was started for a plugin, only then will the stdout be set
        // Otherwise if command was manually started by the user like by adding a new terminal session,
        // then no need to set stdout
        TermuxSession newTermuxSession = TermuxSession.execute(this, executionCommand, getTermuxTerminalSessionClient(),
            this, new TermuxShellEnvironment(), null, executionCommand.isPluginExecutionCommand);
        if (newTermuxSession == null) {
            Logger.logError(LOG_TAG, "Failed to execute new TermuxSession command for:\n" + executionCommand.getCommandIdAndLabelLogString());
            // If the execution command was started for a plugin, then process the error
            if (executionCommand.isPluginExecutionCommand)
                TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            else {
                Logger.logError(LOG_TAG, "Set log level to debug or higher to see error in logs");
                Logger.logErrorPrivateExtended(LOG_TAG, executionCommand.toString());
            }
            return null;
        }

        mShellManager.mTermuxSessions.add(newTermuxSession);

        // Remove the execution command from the pending plugin execution commands list since it has
        // now been processed
        if (executionCommand.isPluginExecutionCommand)
            mShellManager.mPendingPluginExecutionCommands.remove(executionCommand);

        // Notify {@link TermuxSessionsListViewController} that sessions list has been updated if
        // activity in is foreground
        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.termuxSessionListNotifyUpdated();

        updateNotification();

        // No need to recreate the activity since it likely just started and theme should already have applied
        TermuxActivity.updateTermuxActivityStyling(this, false);

        return newTermuxSession;
    }

    /** Remove a TermuxSession. */
    public synchronized int removeTermuxSession(TerminalSession sessionToRemove) {
        int index = getIndexOfSession(sessionToRemove);

        if (index >= 0)
            mShellManager.mTermuxSessions.get(index).finish();

        return index;
    }

    /** Callback received when a {@link TermuxSession} finishes. */
    @Override
    public void onTermuxSessionExited(final TermuxSession termuxSession) {
        if (termuxSession != null) {
            ExecutionCommand executionCommand = termuxSession.getExecutionCommand();

            Logger.logVerbose(LOG_TAG, "The onTermuxSessionExited() callback called for \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession command");

            // If the execution command was started for a plugin, then process the results
            if (executionCommand != null && executionCommand.isPluginExecutionCommand)
                TermuxPluginUtils.processPluginExecutionCommandResult(this, LOG_TAG, executionCommand);

            mShellManager.mTermuxSessions.remove(termuxSession);

            // Notify {@link TermuxSessionsListViewController} that sessions list has been updated if
            // activity in is foreground
            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.termuxSessionListNotifyUpdated();
        }

        updateNotification();
    }





    private ShellCreateMode processShellCreateMode(@NonNull ExecutionCommand executionCommand) {
        if (ShellCreateMode.ALWAYS.equalsMode(executionCommand.shellCreateMode))
            return ShellCreateMode.ALWAYS; // Default
        else if (ShellCreateMode.NO_SHELL_WITH_NAME.equalsMode(executionCommand.shellCreateMode))
            if (DataUtils.isNullOrEmpty(executionCommand.shellName)) {
                TermuxPluginUtils.setAndProcessPluginExecutionCommandError(this, LOG_TAG, executionCommand, false,
                    getString(R.string.error_termux_service_execution_command_shell_name_unset, executionCommand.shellCreateMode));
                return null;
            } else {
               return ShellCreateMode.NO_SHELL_WITH_NAME;
            }
        else {
            TermuxPluginUtils.setAndProcessPluginExecutionCommandError(this, LOG_TAG, executionCommand, false,
                getString(R.string.error_termux_service_unsupported_execution_command_shell_create_mode, executionCommand.shellCreateMode));
            return null;
        }
    }

    /** Process session action for new session. */
    private void handleSessionAction(int sessionAction, TerminalSession newTerminalSession) {
        Logger.logDebug(LOG_TAG, "Processing sessionAction \"" + sessionAction + "\" for session \"" + newTerminalSession.mSessionName + "\"");

        switch (sessionAction) {
            case TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY:
                setCurrentStoredTerminalSession(newTerminalSession);
                if (mTermuxTerminalSessionActivityClient != null)
                    mTermuxTerminalSessionActivityClient.setCurrentSession(newTerminalSession);
                startTermuxActivity();
                break;
            case TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_OPEN_ACTIVITY:
                if (getTermuxSessionsSize() == 1)
                    setCurrentStoredTerminalSession(newTerminalSession);
                startTermuxActivity();
                break;
            case TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY:
                setCurrentStoredTerminalSession(newTerminalSession);
                if (mTermuxTerminalSessionActivityClient != null)
                    mTermuxTerminalSessionActivityClient.setCurrentSession(newTerminalSession);
                break;
            case TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_KEEP_CURRENT_SESSION_AND_DONT_OPEN_ACTIVITY:
                if (getTermuxSessionsSize() == 1)
                    setCurrentStoredTerminalSession(newTerminalSession);
                break;
            default:
                Logger.logError(LOG_TAG, "Invalid sessionAction: \"" + sessionAction + "\". Force using default sessionAction.");
                handleSessionAction(TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY, newTerminalSession);
                break;
        }
    }

    /** Launch the {@link }TermuxActivity} to bring it to foreground. */
    private void startTermuxActivity() {
        // For android >= 10, apps require Display over other apps permission to start foreground activities
        // from background (services). If it is not granted, then TermuxSessions that are started will
        // show in Termux notification but will not run until user manually clicks the notification.
        if (PermissionUtils.validateDisplayOverOtherAppsPermissionForPostAndroid10(this, true)) {
            TermuxActivity.startTermuxActivity(this);
        } else {
            TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(this);
            if (preferences == null) return;
            if (preferences.arePluginErrorNotificationsEnabled(false))
                Logger.showToast(this, this.getString(R.string.error_display_over_other_apps_permission_not_granted_to_start_terminal), true);
        }
    }





    /** If {@link TermuxActivity} has not bound to the {@link TermuxService} yet or is destroyed, then
     * interface functions requiring the activity should not be available to the terminal sessions,
     * so we just return the {@link #mTermuxTerminalSessionServiceClient}. Once {@link TermuxActivity} bind
     * callback is received, it should call {@link #setTermuxTerminalSessionClient} to set the
     * {@link TermuxService#mTermuxTerminalSessionActivityClient} so that further terminal sessions are directly
     * passed the {@link TermuxTerminalSessionActivityClient} object which fully implements the
     * {@link TerminalSessionClient} interface.
     *
     * @return Returns the {@link TermuxTerminalSessionActivityClient} if {@link TermuxActivity} has bound with
     * {@link TermuxService}, otherwise {@link TermuxTerminalSessionServiceClient}.
     */
    public synchronized TermuxTerminalSessionClientBase getTermuxTerminalSessionClient() {
        if (mTermuxTerminalSessionActivityClient != null)
            return mTermuxTerminalSessionActivityClient;
        else
            return mTermuxTerminalSessionServiceClient;
    }

    /** This should be called when {@link TermuxActivity#onServiceConnected} is called to set the
     * {@link TermuxService#mTermuxTerminalSessionActivityClient} variable and update the {@link TerminalSession}
     * and {@link TerminalEmulator} clients in case they were passed {@link TermuxTerminalSessionServiceClient}
     * earlier.
     *
     * @param termuxTerminalSessionActivityClient The {@link TermuxTerminalSessionActivityClient} object that fully
     * implements the {@link TerminalSessionClient} interface.
     */
    public synchronized void setTermuxTerminalSessionClient(TermuxTerminalSessionActivityClient termuxTerminalSessionActivityClient) {
        mTermuxTerminalSessionActivityClient = termuxTerminalSessionActivityClient;

        for (int i = 0; i < mShellManager.mTermuxSessions.size(); i++)
            mShellManager.mTermuxSessions.get(i).getTerminalSession().updateTerminalSessionClient(mTermuxTerminalSessionActivityClient);
    }

    /** This should be called when {@link TermuxActivity} has been destroyed and in {@link #onUnbind(Intent)}
     * so that the {@link TermuxService} and {@link TerminalSession} and {@link TerminalEmulator}
     * clients do not hold an activity references.
     */
    public synchronized void unsetTermuxTerminalSessionClient() {
        for (int i = 0; i < mShellManager.mTermuxSessions.size(); i++)
            mShellManager.mTermuxSessions.get(i).getTerminalSession().updateTerminalSessionClient(mTermuxTerminalSessionServiceClient);

        mTermuxTerminalSessionActivityClient = null;
    }





    private Notification buildNotification() {
        Resources res = getResources();

        // Set pending intent to be launched when notification is clicked
        Intent notificationIntent = TermuxActivity.newInstance(this);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);


        // Set notification text
        int sessionCount = getTermuxSessionsSize();
        int taskCount = mShellManager.mTermuxTasks.size();
        String notificationText = sessionCount + " session" + (sessionCount == 1 ? "" : "s");
        if (taskCount > 0) {
            notificationText += ", " + taskCount + " task" + (taskCount == 1 ? "" : "s");
        }

        final boolean wakeLockHeld = mWakeLock != null;
        if (wakeLockHeld) notificationText += " (wake lock held)";


        // Set notification priority
        // If holding a wake or wifi lock consider the notification of high priority since it's using power,
        // otherwise use a low priority
        int priority = (wakeLockHeld) ? Notification.PRIORITY_HIGH : Notification.PRIORITY_LOW;


        // Build the notification
        Notification.Builder builder =  NotificationUtils.geNotificationBuilder(this,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID, priority,
            TermuxConstants.TERMUX_APP_NAME, notificationText, null,
            contentIntent, null, NotificationUtils.NOTIFICATION_MODE_SILENT);
        if (builder == null)  return null;

        // No need to show a timestamp:
        builder.setShowWhen(false);

        // Set notification icon
        builder.setSmallIcon(R.drawable.ic_service_notification);

        // Set background color for small notification icon
        builder.setColor(0xFF607D8B);

        // TermuxSessions are always ongoing
        builder.setOngoing(true);


        // Set Exit button action
        Intent exitIntent = new Intent(this, TermuxService.class).setAction(TERMUX_SERVICE.ACTION_STOP_SERVICE);
        builder.addAction(android.R.drawable.ic_delete, res.getString(R.string.notification_action_exit), PendingIntent.getService(this, 0, exitIntent, 0));


        // Set Wakelock button actions
        String newWakeAction = wakeLockHeld ? TERMUX_SERVICE.ACTION_WAKE_UNLOCK : TERMUX_SERVICE.ACTION_WAKE_LOCK;
        Intent toggleWakeLockIntent = new Intent(this, TermuxService.class).setAction(newWakeAction);
        String actionTitle = res.getString(wakeLockHeld ? R.string.notification_action_wake_unlock : R.string.notification_action_wake_lock);
        int actionIcon = wakeLockHeld ? android.R.drawable.ic_lock_idle_lock : android.R.drawable.ic_lock_lock;
        builder.addAction(actionIcon, actionTitle, PendingIntent.getService(this, 0, toggleWakeLockIntent, 0));


        return builder.build();
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationUtils.setupNotificationChannel(this, TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_ID,
            TermuxConstants.TERMUX_APP_NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
    }

    /** Update the shown foreground service notification after making any changes that affect it. */
    private synchronized void updateNotification() {
        if (mWakeLock == null && mShellManager.mTermuxSessions.isEmpty() && mShellManager.mTermuxTasks.isEmpty()) {
            // Exit if we are updating after the user disabled all locks with no sessions or tasks running.
            requestStopService();
        } else {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(TermuxConstants.TERMUX_APP_NOTIFICATION_ID, buildNotification());
        }
    }





    private void setCurrentStoredTerminalSession(TerminalSession terminalSession) {
        if (terminalSession == null) return;
        // Make the newly created session the current one to be displayed
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(this);
        if (preferences == null) return;
        preferences.setCurrentSession(terminalSession.mHandle);
    }

    public synchronized boolean isTermuxSessionsEmpty() {
        return mShellManager.mTermuxSessions.isEmpty();
    }

    /** In-process core; callers must establish authorization independently. */
    public com.termux.app.control.ControlCore getControlCore() { return mControlCore; }

    /** Trusted callers must derive output scopes from their own authentication. */
    public com.termux.app.control.OutputSubscriptions getOutputSubscriptions() { return mOutputSubscriptions; }

    /** For bounded background-to-main handoff; this does not start a listener. */
    public com.termux.app.control.ipc.ServiceThreadDispatch getControlDispatch() { return mControlDispatch; }

    public boolean isControlAvailable() { return mControlAvailable; }

    public interface LocalControlStartCallback { void onResult(boolean started); }

    /** Workspace scopes are read and write consent, selected separately. Neither implies job or file access. */
    private static boolean requestsWorkspaceScope(java.util.Set<com.termux.app.control.ControlCore.Scope> scopes) {
        return scopes.contains(com.termux.app.control.ControlCore.Scope.WORKSPACES_READ) ||
            scopes.contains(com.termux.app.control.ControlCore.Scope.WORKSPACES_WRITE);
    }

    /**
     * Owned processes are the session shells. Their PIDs are read under the service lock for a moment; the sample
     * itself is read from /proc on the worker. No task roots exist yet, so task and owned views report session shells.
     */
    private com.termux.app.control.process.ProcessService processServiceForWorker() {
        synchronized (mDomainLock) {
            if (mProcessService != null) return mProcessService;
            long ticks = android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK);
            long pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGE_SIZE);
            mProcessService = new com.termux.app.control.process.ProcessService(new java.io.File("/proc"),
                android.os.Process.myUid(), ticks, pageSize, new com.termux.app.control.process.ProcessService.Roots() {
                    @Override public java.util.Set<Integer> sessionRoots() {
                        java.util.Set<Integer> pids = new java.util.HashSet<>();
                        synchronized (TermuxService.this) {
                            for (com.termux.shared.termux.shell.command.runner.terminal.TermuxSession item : getTermuxSessions()) {
                                int pid = item.getTerminalSession().getPid();
                                if (pid > 0) pids.add(pid);
                            }
                        }
                        return pids;
                    }
                    @Override public java.util.Set<Integer> taskRoots() { return java.util.Collections.emptySet(); }
                });
            return mProcessService;
        }
    }

    /**
     * Read-only client file access to the shell home and the prefix. Null when descriptor-relative traversal is
     * unavailable: the path-checked fallback is not race-safe, so file methods are then not offered at all.
     */
    private com.termux.app.control.files.FileService fileServiceForWorker(boolean writable) throws java.io.IOException {
        com.termux.app.control.files.DescriptorAccess access = com.termux.app.control.files.DescriptorAccess.createIfAvailable();
        if (access == null) return null;
        java.util.Map<String, java.io.File> roots = new java.util.LinkedHashMap<>();
        roots.put("home", new java.io.File(TermuxConstants.TERMUX_HOME_DIR_PATH));
        roots.put("prefix", new java.io.File(TermuxConstants.TERMUX_PREFIX_DIR_PATH));
        // Only the home is ever writable; the prefix changes through the package manager, not file RPC.
        return new com.termux.app.control.files.FileService(roots,
            writable ? java.util.Collections.singleton("home") : java.util.Collections.<String>emptySet(), access);
    }

    /** One store per service. Its root is the app-private files directory, never the shell home itself. */
    private com.termux.app.control.workspace.WorkspaceRepository workspaceRepositoryForWorker() throws java.io.IOException {
        synchronized (mDomainLock) {
            if (mWorkspaceRepository != null) return mWorkspaceRepository;
            java.io.File store = new java.io.File(getFilesDir(), "workspaces");
            if (!store.isDirectory() && !store.mkdirs()) throw new java.io.IOException("Workspace store unavailable");
            java.util.Map<String, java.io.File> roots = new java.util.HashMap<>();
            roots.put("home", new java.io.File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            try {
                mWorkspaceRepository = new com.termux.app.control.workspace.WorkspaceRepository(new java.io.File(store, "v1.json"),
                    new com.termux.app.control.files.FileService(roots));
            } catch (com.termux.app.control.workspace.WorkspaceRepository.WorkspaceException e) {
                throw new java.io.IOException("Workspace store unavailable", e);
            }
            return mWorkspaceRepository;
        }
    }

    /** Avoids Collection.stream(), which is not available on the minimum supported Android API level. */
    private static boolean requestsJobScope(java.util.Set<com.termux.app.control.ControlCore.Scope> scopes) {
        for (com.termux.app.control.ControlCore.Scope scope : scopes)
            if (scope.name().startsWith("JOBS_") || scope == com.termux.app.control.ControlCore.Scope.PROCESSES_SIGNAL) return true;
        return false;
    }

    /**
     * Creates the one coordinator for this service lifetime. The path policy resolves configured roots
     * on disk, so this runs on the local-control start worker. The service monitor is not held here.
     */
    private com.termux.app.control.jobs.JobCoordinator jobCoordinatorForWorker() throws java.io.IOException {
        synchronized (mJobLock) {
            if (mJobCoordinator != null) return mJobCoordinator;
            com.termux.app.control.jobs.JobPathPolicy policy = new com.termux.app.control.jobs.JobPathPolicy(
                java.util.Collections.singletonList(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH),
                java.util.Collections.singletonList(TermuxConstants.TERMUX_HOME_DIR_PATH),
                TermuxConstants.TERMUX_HOME_DIR_PATH,
                java.util.Collections.singletonMap("git", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/git"),
                // The policy resolves on job workers, so loading the store here stays off the main thread.
                workspaceId -> workspaceRepositoryForWorker().directory(workspaceId));
            com.termux.app.control.jobs.JobRegistry registry = new com.termux.app.control.jobs.JobRegistry(
                new com.termux.app.control.jobs.JobIoPool(com.termux.app.control.jobs.JobIoPool.MAX_JOBS),
                new com.termux.app.control.jobs.JobOutputStore(),
                com.termux.app.control.jobs.JobOutputStore.DEFAULT_RECORD_LIMIT);
            mJobCoordinator = new com.termux.app.control.jobs.JobCoordinator(registry,
                new com.termux.app.control.jobs.AppShellJobAdapter(this), policy, mJobAuthority);
            return mJobCoordinator;
        }
    }

    /** The workspace store for the app's own UI. Loads from disk on first use, so call it off the main thread. */
    public com.termux.app.control.workspace.WorkspaceRepository workspaceRepositoryForUi() throws java.io.IOException {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IllegalStateException("Workspace store must load off the main thread");
        return workspaceRepositoryForWorker();
    }

    /** The job coordinator if local control ever created one in this service lifetime, for the app's own UI. */
    @androidx.annotation.Nullable
    public com.termux.app.control.jobs.JobCoordinator getJobCoordinatorIfCreated() {
        synchronized (mJobLock) { return mJobCoordinator; }
    }

    /** Explicit in-process opt-in. Scopes must be selected by a trusted local caller. */
    public boolean startLocalControl(java.util.Set<com.termux.app.control.ControlCore.Scope> scopes,
                                     LocalControlStartCallback callback) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper() || !mControlAvailable)
            throw new IllegalStateException("Control service unavailable on this thread");
        if (scopes == null || mLocalControlServer != null) return false;
        boolean close = scopes.contains(com.termux.app.control.ControlCore.Scope.SESSIONS_CLOSE);
        boolean resume = scopes.contains(com.termux.app.control.ControlCore.Scope.SESSIONS_OUTPUT_RESUME);
        if (close && !scopes.contains(com.termux.app.control.ControlCore.Scope.SESSIONS_WRITE)) return false;
        if (resume && !scopes.contains(com.termux.app.control.ControlCore.Scope.SESSIONS_OUTPUT)) return false;
        final boolean requestsJobs = requestsJobScope(scopes);
        final boolean requestsProcesses = scopes.contains(com.termux.app.control.ControlCore.Scope.PROCESSES_READ);
        final boolean requestsFiles = scopes.contains(com.termux.app.control.ControlCore.Scope.FILES_READ) ||
            scopes.contains(com.termux.app.control.ControlCore.Scope.FILES_WRITE);
        final boolean writesFiles = scopes.contains(com.termux.app.control.ControlCore.Scope.FILES_WRITE);
        final boolean requestsWorkspaces = requestsWorkspaceScope(scopes);
        com.termux.app.control.ipc.LocalControlServer server =
            new com.termux.app.control.ipc.LocalControlServer(this, mControlCore, mControlDispatch, scopes, stopped ->
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (mLocalControlServer == stopped) {
                        mLocalControlServer = null;
                        mJobAuthority.revoke();
                        if (mControlAvailable && mSafeControlCloseEnabled) setSafeControlCloseEnabled(false);
                        if (mControlAvailable) mOutputSubscriptions.setRetentionEnabled(false);
                    }
                }), mOutputSubscriptions);
        setSafeControlCloseEnabled(close);
        mOutputSubscriptions.setRetentionEnabled(resume);
        mLocalControlServer = server;
        try { new Thread(() -> {
            try {
                com.termux.app.control.jobs.JobCoordinator jobs = requestsJobs ? jobCoordinatorForWorker() : null;
                if (jobs != null) mJobAuthority.grantScopes(scopes);
                server.configureDomains(jobs, jobs == null ? null : mJobAuthority,
                    requestsProcesses ? processServiceForWorker() : null,
                    requestsFiles ? fileServiceForWorker(writesFiles) : null,
                    requestsWorkspaces ? workspaceRepositoryForWorker() : null);
                server.start();
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (mLocalControlServer == server && callback != null) callback.onResult(server.isRunning());
                });
            } catch (java.io.IOException | RuntimeException e) {
                Logger.logWarn(LOG_TAG, "Local control socket could not start");
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (mLocalControlServer == server) {
                        mJobAuthority.revoke();
                        if (mControlAvailable && mSafeControlCloseEnabled) setSafeControlCloseEnabled(false);
                        if (mControlAvailable) mOutputSubscriptions.setRetentionEnabled(false);
                        if (callback != null) callback.onResult(false);
                    }
                });
                server.requestStop();
            }
        }, "TermuxControlStart").start(); }
        catch (RuntimeException e) {
            mLocalControlServer = null;
            if (mSafeControlCloseEnabled) setSafeControlCloseEnabled(false);
            mOutputSubscriptions.setRetentionEnabled(false);
            return false;
        }
        return true;
    }

    /** Stops accepting work immediately; descriptor cleanup runs on a worker. */
    public void stopLocalControl() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            throw new IllegalStateException("Control service stop must run on the main thread");
        com.termux.app.control.ipc.LocalControlServer server = mLocalControlServer;
        if (mControlAvailable && mSafeControlCloseEnabled) setSafeControlCloseEnabled(false);
        if (mControlAvailable) mOutputSubscriptions.setRetentionEnabled(false);
        // Future job requests fail as soon as the user stops local control; running children are not signalled.
        mJobAuthority.revoke();
        if (server != null) server.requestStop();
    }

    public boolean hasLocalControl() { return mLocalControlServer != null; }

    public boolean isLocalControlRunning() {
        return mLocalControlServer != null && mLocalControlServer.isRunning();
    }

    public boolean isLocalControlStopping() {
        return mLocalControlServer != null && mLocalControlServer.isStopping();
    }

    public boolean localControlAllowsSessionWrite() {
        return mLocalControlServer != null && mLocalControlServer.allowsSessionWrite();
    }

    public boolean localControlAllowsOutput() {
        return mLocalControlServer != null && mLocalControlServer.allowsOutput();
    }

    public boolean localControlAllowsJobs() {
        return mLocalControlServer != null && mLocalControlServer.allowsJobs();
    }

    public boolean localControlAllowsJobExecute() {
        return mLocalControlServer != null && mLocalControlServer.allowsJobExecute();
    }

    public boolean localControlAllowsOutputResume() {
        return mLocalControlServer != null && mLocalControlServer.allowsOutputResume();
    }

    /** Local opt-in for new sessions; no remote caller may grant itself this setting. */
    public void setSafeControlCloseEnabled(boolean enabled) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper() || !mControlAvailable)
            throw new IllegalStateException("Control service unavailable on this thread");
        mSafeControlCloseEnabled = enabled;
        TerminalSession.setProcessHandlesEnabled(enabled);
        if (!enabled) {
            synchronized (this) {
                for (TermuxSession item : mShellManager.mTermuxSessions)
                    item.getTerminalSession().releaseProcessHandle();
            }
        }
    }

    public boolean isSafeControlCloseEnabled() { return mSafeControlCloseEnabled; }

    public String getControlDefaultWorkingDirectory() {
        return mProperties.getDefaultWorkingDirectory();
    }

    /** Remove only an unstarted, non-plugin session after PTY initialization failed. */
    public boolean discardUnstartedControlSession(TermuxSession item) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper() || !mControlAvailable)
            throw new IllegalStateException("Control service unavailable on this thread");
        synchronized (this) {
            TerminalSession session = item.getTerminalSession();
            if (session.getPid() != 0 || session.getEmulator() != null
                || item.getExecutionCommand().isPluginExecutionCommand
                || !mShellManager.mTermuxSessions.remove(item)) return false;
            item.getExecutionCommand().setStateFailed(Errno.ERRNO_FAILED.getCode(), "Terminal initialization failed");
        }
        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.termuxSessionListNotifyUpdated();
        updateNotification();
        return true;
    }

    /** A visible local terminal controls its own geometry. Called on the main thread. */
    public boolean isSessionVisibleForControl(TerminalSession session) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper() || !mControlAvailable)
            throw new IllegalStateException("Control service unavailable on this thread");
        return mTermuxTerminalSessionActivityClient != null
            && mTermuxTerminalSessionActivityClient.isSessionVisible(session);
    }

    /** Select a session without bringing the activity to the foreground. */
    public void focusSessionForControl(TerminalSession session) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper() || !mControlAvailable)
            throw new IllegalStateException("Control service unavailable on this thread");
        setCurrentStoredTerminalSession(session);
        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.setCurrentSession(session);
    }

    /** Rename an existing session on the main thread and refresh the session list. */
    public boolean renameSessionForControl(String id, String name) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper() || !mControlAvailable)
            throw new IllegalStateException("Control service unavailable on this thread");
        TermuxSession selected = null;
        synchronized (this) {
            for (TermuxSession item : mShellManager.mTermuxSessions) {
                if (id.equals(item.getTerminalSession().mHandle)) {
                    selected = item;
                    selected.getTerminalSession().mSessionName = name;
                    selected.getExecutionCommand().shellName = name;
                    break;
                }
            }
        }
        if (selected == null) return false;
        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.termuxSessionListNotifyUpdated();
        updateNotification();
        return true;
    }

    /** PID hints for read-only monitoring. PIDs do not confer signalling authority. */
    public static final class MonitorRoots {
        public final java.util.Set<Integer> pids, sessions, tasks;
        public final int unknownTaskPids;
        MonitorRoots(java.util.Set<Integer> sessions, java.util.Set<Integer> tasks, int unknownTaskPids) {
            this.sessions = java.util.Collections.unmodifiableSet(new java.util.HashSet<>(sessions));
            this.tasks = java.util.Collections.unmodifiableSet(new java.util.HashSet<>(tasks));
            java.util.Set<Integer> all = new java.util.HashSet<>(sessions); all.addAll(tasks);
            this.pids = java.util.Collections.unmodifiableSet(all);
            this.unknownTaskPids = unknownTaskPids;
        }
    }

    /** Capture only existing service-owned roots; process traversal happens off the main thread. */
    public synchronized MonitorRoots getMonitorRoots() {
        java.util.Set<Integer> sessions = new java.util.HashSet<>(), tasks = new java.util.HashSet<>();
        for (TermuxSession item : mShellManager.mTermuxSessions) {
            int pid = item.getTerminalSession().getPid();
            if (pid > 0) sessions.add(pid);
        }
        int unknown = 0;
        for (AppShell task : mShellManager.mTermuxTasks) {
            try {
                task.getProcess().exitValue(); // Already exited; the service callback may still be pending.
            } catch (IllegalThreadStateException running) {
                int pid = task.getExecutionCommand().mPid;
                if (pid > 0) tasks.add(pid); else unknown++;
            }
        }
        return new MonitorRoots(sessions, tasks, unknown);
    }

    public synchronized int getTermuxSessionsSize() {
        return mShellManager.mTermuxSessions.size();
    }

    public synchronized List<TermuxSession> getTermuxSessions() {
        return mShellManager.mTermuxSessions;
    }

    @Nullable
    public synchronized TermuxSession getTermuxSession(int index) {
        if (index >= 0 && index < mShellManager.mTermuxSessions.size())
            return mShellManager.mTermuxSessions.get(index);
        else
            return null;
    }

    @Nullable
    public synchronized TermuxSession getTermuxSessionForTerminalSession(TerminalSession terminalSession) {
        if (terminalSession == null) return null;

        for (int i = 0; i < mShellManager.mTermuxSessions.size(); i++) {
            if (mShellManager.mTermuxSessions.get(i).getTerminalSession().equals(terminalSession))
                return mShellManager.mTermuxSessions.get(i);
        }

        return null;
    }

    public synchronized TermuxSession getLastTermuxSession() {
        return mShellManager.mTermuxSessions.isEmpty() ? null : mShellManager.mTermuxSessions.get(mShellManager.mTermuxSessions.size() - 1);
    }

    public synchronized int getIndexOfSession(TerminalSession terminalSession) {
        if (terminalSession == null) return -1;

        for (int i = 0; i < mShellManager.mTermuxSessions.size(); i++) {
            if (mShellManager.mTermuxSessions.get(i).getTerminalSession().equals(terminalSession))
                return i;
        }
        return -1;
    }

    public synchronized TerminalSession getTerminalSessionForHandle(String sessionHandle) {
        TerminalSession terminalSession;
        for (int i = 0, len = mShellManager.mTermuxSessions.size(); i < len; i++) {
            terminalSession = mShellManager.mTermuxSessions.get(i).getTerminalSession();
            if (terminalSession.mHandle.equals(sessionHandle))
                return terminalSession;
        }
        return null;
    }

    public synchronized AppShell getTermuxTaskForShellName(String name) {
        if (DataUtils.isNullOrEmpty(name)) return null;
        AppShell appShell;
        for (int i = 0, len = mShellManager.mTermuxTasks.size(); i < len; i++) {
            appShell = mShellManager.mTermuxTasks.get(i);
            String shellName = appShell.getExecutionCommand().shellName;
            if (shellName != null && shellName.equals(name))
                return appShell;
        }
        return null;
    }

    public synchronized TermuxSession getTermuxSessionForShellName(String name) {
        if (DataUtils.isNullOrEmpty(name)) return null;
        TermuxSession termuxSession;
        for (int i = 0, len = mShellManager.mTermuxSessions.size(); i < len; i++) {
            termuxSession = mShellManager.mTermuxSessions.get(i);
            String shellName = termuxSession.getExecutionCommand().shellName;
            if (shellName != null && shellName.equals(name))
                return termuxSession;
        }
        return null;
    }



    public boolean wantsToStop() {
        return mWantsToStop;
    }

}
