package com.termux.app.compat;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.termux.app.TermuxActivity;
import com.termux.app.compat.auth.BrowserCallbackProbe;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;

/** Opt-in external-handler test. It does not perform OAuth or inspect authentication data. */
@RunWith(AndroidJUnit4.class)
public class ExternalBrowserCallbackInstrumentedTest {
    @Test public void externalHandlerCanReachTemporaryIpv4Callback() throws Exception {
        Assume.assumeTrue("Requires explicit browser test opt-in",
            "true".equals(InstrumentationRegistry.getArguments().getString("compatExternalBrowser")));
        try (BrowserCallbackProbe probe = new BrowserCallbackProbe();
             ActivityScenario<TermuxActivity> scenario = ActivityScenario.launch(TermuxActivity.class)) {
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<BrowserCallbackProbe.Status> result = worker.submit(probe::await);
                AtomicReference<AndroidUrlLauncher.Status> dispatched = new AtomicReference<>();
                scenario.onActivity(activity -> dispatched.set(AndroidUrlLauncher.open(activity, probe.url())));
                assertEquals(AndroidUrlLauncher.Status.DISPATCHED_UNCONFIRMED, dispatched.get());
                assertEquals(BrowserCallbackProbe.Status.REQUEST_RECEIVED, result.get(35, TimeUnit.SECONDS));
            } finally {
                probe.close();
                worker.shutdownNow();
            }
        }
    }
}
