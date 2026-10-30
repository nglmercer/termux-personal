package com.termux.app.control.ipc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.termux.app.control.ControlCore;
import org.junit.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class ControlMethodRegistryTest {
    @Test public void registryMatchesSharedContract() throws Exception {
        try (InputStreamReader reader = new InputStreamReader(getClass().getResourceAsStream("/methods.json"), StandardCharsets.UTF_8)) {
            JsonObject contract = JsonParser.parseReader(reader).getAsJsonObject();
            assertEquals(1, contract.get("protocolVersion").getAsInt());
            JsonArray methods = contract.getAsJsonArray("methods");
            List<ControlMethodRegistry.Descriptor> descriptors = ControlMethodRegistry.all();
            assertEquals(methods.size(), descriptors.size());
            Set<String> names = new HashSet<>();
            for (int i = 0; i < methods.size(); i++) {
                JsonObject expected = methods.get(i).getAsJsonObject();
                ControlMethodRegistry.Descriptor actual = descriptors.get(i);
                assertTrue(names.add(actual.name));
                assertEquals(expected.get("name").getAsString(), actual.name);
                assertEquals(expected.get("mutates").getAsBoolean(), actual.mutates);
                assertEquals(expected.get("binaryDirection").getAsString(), actual.binary);
                assertEquals(expected.get("outputService").getAsBoolean(), actual.output);
                JsonArray scopes = expected.getAsJsonArray("scopes");
                assertEquals(scopes.size(), actual.scopes.size());
                for (int s = 0; s < scopes.size(); s++) assertEquals(ControlCore.Scope.valueOf(scopes.get(s).getAsString()), actual.scopes.get(s));
            }
        }
    }

    /** Only job, process, file and workspace methods may leave the service-thread dispatch; session state must stay there. */
    @Test public void onlyWorkerDomainMethodsRunOffServiceThread() {
        for (ControlMethodRegistry.Descriptor descriptor : ControlMethodRegistry.all()) {
            boolean worker = descriptor.name.startsWith("job.") || descriptor.name.startsWith("process.")
                || descriptor.name.startsWith("file.") || descriptor.name.startsWith("workspace.");
            assertEquals(descriptor.name, worker, ControlMethodRegistry.runsOffServiceThread(descriptor.name));
        }
    }

    @Test public void binaryRequestRoutesMatchRegistry() {
        Set<String> requests = new HashSet<>();
        for (ControlMethodRegistry.Descriptor descriptor : ControlMethodRegistry.all())
            if ("request".equals(descriptor.binary)) requests.add(descriptor.name);
        assertEquals(requests, BinaryPayloadRouter.requestMethods());
    }
}
