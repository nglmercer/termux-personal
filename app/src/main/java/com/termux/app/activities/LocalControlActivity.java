package com.termux.app.activities;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.CheckBoxPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import com.termux.R;
import com.termux.app.TermuxService;
import com.termux.app.control.ControlCore;
import com.termux.app.control.LocalControlConsent;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.theme.NightMode;

/**
 * Local control settings: one switch to run the listener, then grouped permissions. Every choice is saved,
 * so turning local control on later, or at Termux start when chosen, uses the same permissions. Only this
 * screen writes the choice; clients can never change it.
 */
public final class LocalControlActivity extends AppCompatActivity {
    private TermuxService mService;
    private LocalControlFragment mFragment;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = ((TermuxService.LocalBinder) binder).service;
            if (mFragment != null) mFragment.refresh();
        }
        @Override public void onServiceDisconnected(ComponentName name) { mService = null; }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
        setContentView(R.layout.activity_settings);
        mFragment = new LocalControlFragment();
        getSupportFragmentManager().beginTransaction().replace(R.id.settings, mFragment).commit();
        AppCompatActivityUtils.setToolbar(this, com.termux.shared.R.id.toolbar);
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);
        setTitle(R.string.action_local_control);
        bindService(new Intent(this, TermuxService.class), mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onDestroy() {
        unbindService(mConnection);
        super.onDestroy();
    }

    @Override public boolean onSupportNavigateUp() { onBackPressed(); return true; }

    @Nullable TermuxService service() { return mService; }

    public static final class LocalControlFragment extends PreferenceFragmentCompat {
        private static final String KEY_RUNNING = "running", KEY_AUTO_START = "auto_start", KEY_APPLY = "apply";
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private final Runnable mPoll = new Runnable() {
            @Override public void run() { refresh(); mHandler.postDelayed(this, 750); }
        };
        private LocalControlConsent mConsent;
        private SwitchPreferenceCompat mRunning;
        private Preference mApply;
        private String mStartedWith;

        /** Saved permissions; each key is a scope name, plus the auto-start flag. */
        private final PreferenceDataStore mStore = new PreferenceDataStore() {
            @Override public boolean getBoolean(String key, boolean defValue) {
                if (KEY_AUTO_START.equals(key)) return mConsent.autoStart();
                ControlCore.Scope scope = scope(key);
                return scope != null ? mConsent.scopesOrDefault().contains(scope) : defValue;
            }
            @Override public void putBoolean(String key, boolean value) {
                if (KEY_AUTO_START.equals(key)) { mConsent.setAutoStart(value); return; }
                ControlCore.Scope scope = scope(key);
                if (scope != null) mConsent.set(scope, value);
                refresh();
            }
        };

        @Override public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            Context context = requireContext();
            mConsent = new LocalControlConsent(context);
            getPreferenceManager().setPreferenceDataStore(mStore);
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
            setPreferenceScreen(screen);

            mRunning = new SwitchPreferenceCompat(context);
            mRunning.setKey(KEY_RUNNING);
            mRunning.setPersistent(false);
            mRunning.setTitle(R.string.local_control_screen_running);
            mRunning.setIconSpaceReserved(false);
            mRunning.setOnPreferenceChangeListener((preference, value) -> { setRunning((Boolean) value); return false; });
            screen.addPreference(mRunning);

            mApply = new Preference(context);
            mApply.setKey(KEY_APPLY);
            mApply.setPersistent(false);
            mApply.setTitle(R.string.local_control_screen_apply);
            mApply.setSummary(R.string.local_control_screen_apply_summary);
            // Always present so the list never shifts under a tap; enabled only when there is something to apply.
            mApply.setEnabled(false);
            mApply.setIconSpaceReserved(false);
            mApply.setOnPreferenceClickListener(preference -> { restart(); return true; });
            screen.addPreference(mApply);

            PreferenceCategory sessions = category(screen, R.string.local_control_group_sessions);
            option(sessions, ControlCore.Scope.SESSIONS_WRITE, R.string.local_control_opt_session_write, R.string.local_control_opt_session_write_summary);
            option(sessions, ControlCore.Scope.SESSIONS_OUTPUT, R.string.local_control_opt_output, R.string.local_control_opt_output_summary);
            option(sessions, ControlCore.Scope.SESSIONS_OUTPUT_RESUME, R.string.local_control_opt_resume, R.string.local_control_opt_resume_summary)
                .setDependency(ControlCore.Scope.SESSIONS_OUTPUT.name());
            option(sessions, ControlCore.Scope.SESSIONS_CLOSE, R.string.local_control_opt_close, R.string.local_control_opt_close_summary)
                .setDependency(ControlCore.Scope.SESSIONS_WRITE.name());

            PreferenceCategory jobs = category(screen, R.string.local_control_group_jobs);
            option(jobs, ControlCore.Scope.JOBS_READ, R.string.local_control_opt_jobs_read, R.string.local_control_opt_jobs_read_summary);
            option(jobs, ControlCore.Scope.JOBS_OUTPUT, R.string.local_control_opt_jobs_output, R.string.local_control_opt_jobs_output_summary);
            option(jobs, ControlCore.Scope.JOBS_EXECUTE, R.string.local_control_opt_jobs_execute, R.string.local_control_opt_jobs_execute_summary);
            option(jobs, ControlCore.Scope.JOBS_CANCEL, R.string.local_control_opt_jobs_cancel, R.string.local_control_opt_jobs_cancel_summary);

            PreferenceCategory processes = category(screen, R.string.local_control_group_processes);
            option(processes, ControlCore.Scope.PROCESSES_READ, R.string.local_control_opt_processes_read, R.string.local_control_opt_processes_read_summary);
            option(processes, ControlCore.Scope.PROCESSES_SIGNAL, R.string.local_control_opt_processes_signal, R.string.local_control_opt_processes_signal_summary);

            PreferenceCategory files = category(screen, R.string.local_control_group_files);
            option(files, ControlCore.Scope.FILES_READ, R.string.local_control_opt_files_read, R.string.local_control_opt_files_read_summary);
            option(files, ControlCore.Scope.FILES_WRITE, R.string.local_control_opt_files_write, R.string.local_control_opt_files_write_summary);

            PreferenceCategory workspaces = category(screen, R.string.local_control_group_workspaces);
            option(workspaces, ControlCore.Scope.WORKSPACES_READ, R.string.local_control_opt_workspaces_read, R.string.local_control_opt_workspaces_read_summary);
            option(workspaces, ControlCore.Scope.WORKSPACES_WRITE, R.string.local_control_opt_workspaces_write, R.string.local_control_opt_workspaces_write_summary);

            PreferenceCategory startup = category(screen, R.string.local_control_group_startup);
            SwitchPreferenceCompat autoStart = new SwitchPreferenceCompat(context);
            autoStart.setKey(KEY_AUTO_START);
            autoStart.setTitle(R.string.local_control_opt_auto_start);
            autoStart.setSummary(R.string.local_control_opt_auto_start_summary);
            autoStart.setIconSpaceReserved(false);
            startup.addPreference(autoStart);

            Preference about = new Preference(context);
            about.setSelectable(false);
            about.setSummary(R.string.local_control_screen_footer);
            about.setIconSpaceReserved(false);
            screen.addPreference(about);
        }

        private static PreferenceCategory category(PreferenceScreen screen, int title) {
            PreferenceCategory category = new PreferenceCategory(screen.getContext());
            category.setTitle(title);
            category.setIconSpaceReserved(false);
            screen.addPreference(category);
            return category;
        }

        private static CheckBoxPreference option(PreferenceCategory category, ControlCore.Scope scope, int title, int summary) {
            CheckBoxPreference option = new CheckBoxPreference(category.getContext());
            option.setKey(scope.name());
            option.setTitle(title);
            option.setSummary(summary);
            option.setIconSpaceReserved(false);
            category.addPreference(option);
            return option;
        }

        @Nullable private static ControlCore.Scope scope(String key) {
            for (ControlCore.Scope scope : ControlCore.Scope.values()) if (scope.name().equals(key)) return scope;
            return null;
        }

        @Override public void onResume() { super.onResume(); mHandler.post(mPoll); }
        @Override public void onPause() { mHandler.removeCallbacks(mPoll); super.onPause(); }

        @Nullable private TermuxService service() {
            LocalControlActivity activity = (LocalControlActivity) getActivity();
            return activity == null ? null : activity.service();
        }

        /** Mirrors the listener state; offers a restart when the saved choice differs from the running one. */
        void refresh() {
            if (mRunning == null) return;
            TermuxService service = service();
            boolean present = service != null && service.hasLocalControl();
            mRunning.setEnabled(service != null && !(present && service.isLocalControlStopping()));
            mRunning.setChecked(present);
            int summary = service == null ? R.string.local_control_screen_unavailable :
                !present ? R.string.local_control_screen_off :
                service.isLocalControlStopping() ? R.string.local_control_screen_stopping :
                !service.isLocalControlRunning() ? R.string.local_control_screen_starting : R.string.local_control_screen_on;
            mRunning.setSummary(summary);
            if (!present) mStartedWith = null;
            else if (mStartedWith == null) mStartedWith = current();
            boolean pending = present && mStartedWith != null && !mStartedWith.equals(current());
            mApply.setEnabled(pending);
            mApply.setSummary(pending ? R.string.local_control_screen_apply_summary : R.string.local_control_screen_applied);
        }

        private String current() { return com.termux.app.control.LocalControlOptions.encode(mConsent.scopesOrDefault()); }

        private void setRunning(boolean on) {
            TermuxService service = service();
            if (service == null) return;
            if (!on) { service.stopLocalControl(); refresh(); return; }
            try {
                mStartedWith = current();
                if (!service.startLocalControl(mConsent.scopesOrDefault(), started -> {
                    if (!started && getContext() != null)
                        android.widget.Toast.makeText(getContext(), R.string.local_control_start_failed, android.widget.Toast.LENGTH_LONG).show();
                    refresh();
                })) android.widget.Toast.makeText(requireContext(), R.string.local_control_unavailable, android.widget.Toast.LENGTH_LONG).show();
            } catch (IllegalStateException e) {
                android.widget.Toast.makeText(requireContext(), R.string.local_control_unavailable, android.widget.Toast.LENGTH_LONG).show();
            }
            refresh();
        }

        /** Stops the listener, waits for it to release the socket, then starts it with the saved permissions. */
        private void restart() {
            TermuxService service = service();
            if (service == null || !service.hasLocalControl()) return;
            service.stopLocalControl();
            mHandler.post(new Runnable() {
                int attempts;
                @Override public void run() {
                    TermuxService current = service();
                    if (current == null) return;
                    if (current.hasLocalControl() && ++attempts < 40) { mHandler.postDelayed(this, 100); return; }
                    if (!current.hasLocalControl()) setRunning(true);
                }
            });
        }
    }
}
