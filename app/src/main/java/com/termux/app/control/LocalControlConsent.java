package com.termux.app.control;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.EnumSet;

/**
 * The user's local control choice, kept in app-private preferences. Only the app's own settings screen and
 * consent dialog write it; clients cannot. A stored value is read through {@link LocalControlOptions#decode},
 * so damage can only narrow access.
 */
public final class LocalControlConsent {
    public static final String PREFERENCES = "local_control_consent";
    private static final String KEY_SCOPES = "scopes", KEY_AUTO_START = "auto_start";

    private final SharedPreferences mPreferences;

    public LocalControlConsent(Context context) {
        mPreferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /** Null when the user has never chosen. */
    public EnumSet<ControlCore.Scope> scopes() {
        String stored = mPreferences.getString(KEY_SCOPES, null);
        return stored == null ? null : LocalControlOptions.decode(stored);
    }

    /** The stored choice, or read-only sessions when nothing is stored yet. */
    public EnumSet<ControlCore.Scope> scopesOrDefault() {
        EnumSet<ControlCore.Scope> scopes = scopes();
        return scopes == null ? EnumSet.of(ControlCore.Scope.SESSIONS_READ) : scopes;
    }

    public boolean autoStart() { return mPreferences.getBoolean(KEY_AUTO_START, false); }

    public void save(java.util.Set<ControlCore.Scope> scopes, boolean autoStart) {
        mPreferences.edit().putString(KEY_SCOPES, LocalControlOptions.encode(LocalControlOptions.decode(LocalControlOptions.encode(scopes))))
            .putBoolean(KEY_AUTO_START, autoStart).apply();
    }

    /** Adds or removes one scope; dependent scopes follow their base (no close without write, no resume without output). */
    public void set(ControlCore.Scope scope, boolean enabled) {
        EnumSet<ControlCore.Scope> scopes = scopesOrDefault();
        if (enabled) scopes.add(scope); else scopes.remove(scope);
        if (scope == ControlCore.Scope.SESSIONS_READ) scopes.add(scope);
        save(scopes, autoStart());
    }

    public void setAutoStart(boolean autoStart) { save(scopesOrDefault(), autoStart); }

    public void forget() { mPreferences.edit().clear().apply(); }
}
