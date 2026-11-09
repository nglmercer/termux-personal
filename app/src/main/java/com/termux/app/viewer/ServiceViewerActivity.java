package com.termux.app.viewer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.termux.R;
import com.termux.app.TermuxService;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Opt-in viewer for the web interface of one workspace service, opened only by an explicit user action.
 *
 * <ul>
 * <li>It shows a single origin, the service's {@code http://127.0.0.1:PORT}. Other web pages go to the user's browser;
 * any other scheme is refused ({@link ServiceViewerPolicy}).</li>
 * <li>The intent carries only the workspace and service IDs. The endpoint, and its credential, are read inside this
 * process and sent once, as an {@code Authorization} header of the first page load; the backend turns it into its own
 * session cookie. The credential is never put in a URL, the title, an intent or a log.</li>
 * <li>No JavaScript bridge, no file or content access, no mixed content, no third-party cookies.</li>
 * <li>Nothing reloads by itself: if the service restarted or stopped, the viewer says so and the user decides. A reload is
 * a fresh GET of the root, never a resubmission. After process death the page state is not restored.</li>
 * </ul>
 */
public class ServiceViewerActivity extends Activity {
    public static final String EXTRA_WORKSPACE_ID = "workspace_id", EXTRA_SERVICE_ID = "service_id";

    /** What the viewer needs from {@code workspace.service.endpoint}. */
    public static final class Endpoint {
        public final String state, instanceId, url, origin, credential;
        public final boolean listening;
        public Endpoint(String state, String instanceId, String url, String origin, boolean listening, String credential) {
            this.state = state; this.instanceId = instanceId; this.url = url; this.origin = origin; this.listening = listening; this.credential = credential;
        }
        static Endpoint of(Map<String, Object> m) {
            if (m == null) return null;
            return new Endpoint((String) m.get("state"), (String) m.get("instanceId"), (String) m.get("url"), (String) m.get("origin"),
                Boolean.TRUE.equals(m.get("listening")), (String) m.get("credential"));
        }
    }

    /** Where endpoints come from; blocking, called off the main thread. */
    public interface EndpointSource { Endpoint fetch(String workspaceId, String serviceId) throws Exception; }

    /** Replaces the TermuxService lookup in instrumented tests only; production never sets it. */
    @VisibleForTesting public static volatile EndpointSource sEndpointSourceForTesting;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private String mWorkspaceId, mServiceId;
    private WebView mWebView;
    private TextView mStatus;
    private Endpoint mShown;
    private volatile TermuxService mService;
    private boolean mBound, mDestroyed;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = ((TermuxService.LocalBinder) binder).service;
            open();
        }
        @Override public void onServiceDisconnected(ComponentName name) { mService = null; }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(null); // never restore a previous page: after process death the user starts again
        mWorkspaceId = getIntent().getStringExtra(EXTRA_WORKSPACE_ID);
        mServiceId = getIntent().getStringExtra(EXTRA_SERVICE_ID);
        if (mWorkspaceId == null || mServiceId == null) { finish(); return; }
        setTitle(mServiceId);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        mStatus = new TextView(this);
        mStatus.setPadding(24, 8, 8, 8);
        bar.addView(mStatus, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(button(R.string.service_viewer_reload, v -> open()));
        bar.addView(button(R.string.service_viewer_browser, v -> openInBrowser()));
        bar.addView(button(R.string.service_viewer_close, v -> finish()));
        root.addView(bar);
        try {
            mWebView = new WebView(this);
        } catch (RuntimeException e) { // no usable WebView on this device
            mStatus.setText(R.string.service_viewer_no_webview);
            setContentView(root);
            return;
        }
        configure(mWebView);
        root.addView(mWebView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        if (sEndpointSourceForTesting != null) open();
        else mBound = bindService(new Intent(this, TermuxService.class), mConnection, Context.BIND_AUTO_CREATE);
    }

    private Button button(int text, View.OnClickListener listener) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text); b.setOnClickListener(listener);
        return b;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configure(WebView view) {
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true); // service UIs need it; there is no bridge to the app (no addJavascriptInterface)
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setGeolocationEnabled(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSaveFormData(false);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= 21) CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) { return redirect(request.getUrl().toString()); }
            @SuppressWarnings("deprecation")
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return redirect(url); }
            @Override public void onPageStarted(WebView v, String url, Bitmap favicon) {
                // Covers loads that do not go through shouldOverrideUrlLoading (for example a server redirect on old WebViews).
                if (mShown != null && ServiceViewerPolicy.navigation(mShown.origin, url) != ServiceViewerPolicy.Navigation.LOAD) {
                    v.stopLoading();
                    redirect(url);
                }
            }
        });
    }

    /** True when the viewer handled the URL itself (opened elsewhere or refused) instead of loading it. */
    private boolean redirect(String url) {
        ServiceViewerPolicy.Navigation decision = ServiceViewerPolicy.navigation(mShown == null ? null : mShown.origin, url);
        if (decision == ServiceViewerPolicy.Navigation.LOAD) return false;
        if (decision == ServiceViewerPolicy.Navigation.EXTERNAL) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)); }
            catch (RuntimeException e) { mStatus.setText(R.string.service_viewer_no_browser); }
        } else mStatus.setText(R.string.service_viewer_blocked);
        return true;
    }

    /** Reads the endpoint, then loads the root page or explains why not. Never automatic: only on start or the user's request. */
    private void open() {
        mStatus.setText(R.string.service_viewer_checking);
        mWorker.execute(() -> {
            Endpoint endpoint;
            try { endpoint = fetch(); } catch (Exception e) { endpoint = null; }
            final Endpoint result = endpoint;
            mMain.post(() -> show(result));
        });
    }

    private Endpoint fetch() throws Exception {
        EndpointSource source = sEndpointSourceForTesting;
        if (source != null) return source.fetch(mWorkspaceId, mServiceId);
        TermuxService service = mService;
        return service == null ? null : Endpoint.of(service.serviceEndpointForUi(mWorkspaceId, mServiceId));
    }

    private void show(Endpoint endpoint) {
        if (mDestroyed || mWebView == null) return;
        if (endpoint == null) { mStatus.setText(R.string.service_viewer_unavailable); return; }
        if (endpoint.url == null) { mStatus.setText(R.string.service_viewer_no_port); return; }
        if (endpoint.instanceId == null || !endpoint.listening) { mStatus.setText(getString(R.string.service_viewer_not_listening, endpoint.state)); return; }
        mShown = endpoint;
        mStatus.setText(endpoint.url);
        Map<String, String> headers = endpoint.credential == null ? Collections.<String, String>emptyMap()
            : Collections.singletonMap("Authorization", "Bearer " + endpoint.credential);
        mWebView.clearHistory();
        mWebView.loadUrl(endpoint.url, headers);
    }

    @Override protected void onResume() {
        super.onResume();
        checkCurrentRun();
    }

    /** Compares the shown run with the service's current one and tells the user; never reloads. Called on every resume. */
    @VisibleForTesting public void checkCurrentRun() {
        if (mShown == null) return;
        final String shown = mShown.instanceId;
        mWorker.execute(() -> {
            Endpoint now;
            try { now = fetch(); } catch (Exception e) { now = null; }
            final ServiceViewerPolicy.Check check = ServiceViewerPolicy.check(shown, now == null ? null : now.instanceId);
            mMain.post(() -> {
                if (mDestroyed) return;
                if (check == ServiceViewerPolicy.Check.RESTARTED) mStatus.setText(R.string.service_viewer_restarted);
                else if (check == ServiceViewerPolicy.Check.STOPPED) mStatus.setText(R.string.service_viewer_stopped);
            });
        });
    }

    /** The plain service URL, without any credential: the browser authenticates on its own terms, if the backend allows it. */
    private void openInBrowser() {
        if (mShown == null) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(mShown.url)).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (RuntimeException e) { mStatus.setText(R.string.service_viewer_no_browser); }
    }

    @VisibleForTesting public WebView webViewForTesting() { return mWebView; }
    @VisibleForTesting public CharSequence statusForTesting() { return mStatus.getText(); }

    @Override protected void onDestroy() {
        mDestroyed = true;
        mWorker.shutdownNow();
        if (mBound) { unbindService(mConnection); mBound = false; }
        if (mWebView != null) {
            mWebView.stopLoading();
            mWebView.clearHistory();
            mWebView.clearCache(true);
            ((ViewGroup) mWebView.getParent()).removeView(mWebView);
            mWebView.destroy();
            mWebView = null;
        }
        mShown = null;
        super.onDestroy();
    }

    public static Intent intent(@NonNull Context context, @NonNull String workspaceId, @NonNull String serviceId) {
        return new Intent(context, ServiceViewerActivity.class).putExtra(EXTRA_WORKSPACE_ID, workspaceId).putExtra(EXTRA_SERVICE_ID, serviceId);
    }
}
