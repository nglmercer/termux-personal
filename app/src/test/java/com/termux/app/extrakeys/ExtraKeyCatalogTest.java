package com.termux.app.extrakeys;

import static org.junit.Assert.*;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class ExtraKeyCatalogTest {
    @Test public void everyParserKeyNameIsOfferedOnce() {
        Set<String> values = new HashSet<>();
        for (ExtraKeyCatalog.Key key : ExtraKeyCatalog.all()) assertTrue("duplicate " + key.value, values.add(key.value));
        for (String name : ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS.keySet()) assertTrue("missing " + name, values.contains(name));
        for (String special : new String[]{"CTRL", "ALT", "SHIFT", "FN", "KEYBOARD", "DRAWER", "PASTE", "SCROLL"})
            assertTrue("missing " + special, values.contains(special));
    }

    @Test public void searchMatchesNamesDescriptionsAndGroups() {
        assertEquals("CTRL", ExtraKeyCatalog.search("control").get(0).value);
        assertTrue(ExtraKeyCatalog.search("ARROW").size() >= 4);
        assertTrue(ExtraKeyCatalog.search("pipe").stream().anyMatch(key -> key.value.equals("|")));
        assertEquals(12, ExtraKeyCatalog.search("function key").size());
        assertEquals(ExtraKeyCatalog.all().size(), ExtraKeyCatalog.search("  ").size());
        assertTrue(ExtraKeyCatalog.search("no such key zzz").isEmpty());
    }

    @Test public void everyCatalogKeyIsAcceptedByTheLayout() throws Exception {
        ExtraKeyLayout layout = ExtraKeyLayout.parse("[[]]");
        for (ExtraKeyCatalog.Key key : ExtraKeyCatalog.all()) {
            ExtraKeyLayout row = ExtraKeyLayout.parse("[[]]");
            row.addKey(0, key.value);
            assertEquals(key.value, ExtraKeyLayout.parse(row.toJson()).key(0, 0).getAsString());
        }
        assertNotNull(layout);
    }
}
