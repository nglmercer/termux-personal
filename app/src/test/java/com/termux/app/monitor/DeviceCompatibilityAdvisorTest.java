package com.termux.app.monitor;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.termux.app.monitor.DeviceCompatibilityAdvisor.Status;
import java.util.Arrays;
import org.junit.Test;

public class DeviceCompatibilityAdvisorTest {
    private static DeviceCompatibilityAdvisor.Inputs inputs(Integer sdk) {
        DeviceCompatibilityAdvisor.Inputs in = new DeviceCompatibilityAdvisor.Inputs();
        in.sdkInt = sdk; in.release = "14"; in.manufacturer = "Xiaomi"; in.kernelVersion = "4.19.157"; in.abis = Arrays.asList("arm64-v8a");
        in.nativeLauncherAvailable = true; in.memoryApiAvailable = true; in.thermalApiAvailable = true; in.procListingVisible = true;
        in.foregroundServiceRunning = true; in.ignoringBatteryOptimizations = false;
        return in;
    }

    @Test public void capabilitiesComeFromMeasuredInputsNotConstants() {
        JsonObject on = DeviceCompatibilityAdvisor.advise(inputs(34)).capabilitiesJson().getAsJsonObject("resourceLimits");
        assertTrue(on.get("rlimitAs").getAsBoolean()); assertTrue(on.get("safeProcessStop").getAsBoolean()); assertTrue(on.get("memorySoftMonitoring").getAsBoolean());
        assertFalse(on.get("cgroups").getAsBoolean()); assertFalse(on.get("virtualMachine").getAsBoolean());
        DeviceCompatibilityAdvisor.Inputs none = inputs(34); none.nativeLauncherAvailable = false; none.procListingVisible = false;
        JsonObject off = DeviceCompatibilityAdvisor.advise(none).capabilitiesJson().getAsJsonObject("resourceLimits");
        assertFalse(off.get("rlimitAs").getAsBoolean()); assertFalse(off.get("rlimitCpu").getAsBoolean());
        assertFalse(off.get("safeProcessStop").getAsBoolean()); assertFalse(off.get("memorySoftMonitoring").getAsBoolean());
        DeviceCompatibilityAdvisor.Inputs unknown = inputs(34); unknown.nativeLauncherAvailable = null;
        assertFalse("unknown is not supported", DeviceCompatibilityAdvisor.advise(unknown).capabilitiesJson().getAsJsonObject("resourceLimits").get("rlimitAs").getAsBoolean());
    }

    @Test public void phantomProcessNoteAppliesFromAndroid12AndClaimsNoUniversalLimit() {
        assertEquals(Status.INFO, DeviceCompatibilityAdvisor.advise(inputs(30)).item("phantom_process_policy").status);
        assertTrue(DeviceCompatibilityAdvisor.advise(inputs(30)).item("phantom_process_policy").detail.contains("do not have"));
        String detail = DeviceCompatibilityAdvisor.advise(inputs(33)).item("phantom_process_policy").detail;
        assertTrue(detail.contains("vary"));
        assertFalse(detail.contains("32"));
        assertEquals(Status.UNKNOWN, DeviceCompatibilityAdvisor.advise(inputs(null)).item("phantom_process_policy").status);
    }

    @Test public void thermalNeedsAndroid10() {
        assertEquals(Status.UNSUPPORTED, DeviceCompatibilityAdvisor.advise(inputs(28)).item("thermal_api").status);
        assertEquals(Status.SUPPORTED, DeviceCompatibilityAdvisor.advise(inputs(34)).item("thermal_api").status);
    }

    @Test public void settingsChangingCommandsAreListedButSeparatedAndDiagnosticsAreReadOnly() {
        DeviceCompatibilityAdvisor.Report report = DeviceCompatibilityAdvisor.advise(inputs(34));
        for (String command : report.adbDiagnostics) {
            assertFalse(command, command.contains(" put ")); assertFalse(command, command.contains("oom_score_adj")); assertFalse(command.contains("lmkd"));
        }
        assertTrue(report.adbDiagnostics.toString().contains("max_phantom_processes"));
        assertFalse(DeviceCompatibilityAdvisor.advise(inputs(30)).adbDiagnostics.toString().contains("max_phantom_processes"));
        assertFalse(report.adbOptionalChanges.isEmpty());
        for (String command : report.adbOptionalChanges) assertTrue(command.contains("support varies"));
        assertTrue(report.toJson().has("adbOptionalChangesNotRunByTermux"));
    }

    @Test public void oemNoteIsInformationalAndMissingDataStaysUnknown() {
        assertTrue(DeviceCompatibilityAdvisor.advise(inputs(34)).item("oem_background_restrictions").detail.contains("Xiaomi"));
        DeviceCompatibilityAdvisor.Inputs sparse = new DeviceCompatibilityAdvisor.Inputs();
        DeviceCompatibilityAdvisor.Report r = DeviceCompatibilityAdvisor.advise(sparse);
        assertEquals(Status.UNKNOWN, r.item("native_launcher").status);
        assertEquals(Status.UNKNOWN, r.item("process_metrics").status);
        assertEquals(Status.UNKNOWN, r.item("battery_optimization").status);
        assertNotNull(r.toJson().getAsJsonArray("items"));
    }
}
