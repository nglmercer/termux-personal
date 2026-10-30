package com.termux.app.extrakeys;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Every named key the extra-keys parser understands, plus common symbols, with a short description so the
 * user can pick a key without knowing the configuration format. The value is what is stored in the layout.
 */
public final class ExtraKeyCatalog {
    public static final class Key {
        public final String value, label, description, group;
        Key(String value, String label, String description, String group) {
            this.value = value; this.label = label; this.description = description; this.group = group;
        }
        /** Case-insensitive match on the value, label, description or group. */
        public boolean matches(String query) {
            if (query == null || query.trim().isEmpty()) return true;
            String q = query.trim().toLowerCase(Locale.ROOT);
            return value.toLowerCase(Locale.ROOT).contains(q) || label.toLowerCase(Locale.ROOT).contains(q) ||
                description.toLowerCase(Locale.ROOT).contains(q) || group.toLowerCase(Locale.ROOT).contains(q);
        }
    }

    private static final List<Key> KEYS;
    static {
        List<Key> keys = new ArrayList<>();
        String modifiers = "Modifiers", navigation = "Navigation", editing = "Editing", function = "Function keys",
            app = "Termux", symbols = "Symbols";
        add(keys, "CTRL", "CTRL", "Control modifier; tap, then another key", modifiers);
        add(keys, "ALT", "ALT", "Alt / Meta modifier", modifiers);
        add(keys, "SHIFT", "SHIFT", "Shift modifier", modifiers);
        add(keys, "FN", "FN", "Function modifier for the other extra keys", modifiers);
        add(keys, "ESC", "ESC", "Escape", editing);
        add(keys, "TAB", "TAB ↹", "Tab, completion in shells", editing);
        add(keys, "ENTER", "ENTER ↲", "Enter / Return", editing);
        add(keys, "SPACE", "SPACE", "Space bar", editing);
        add(keys, "BKSP", "BKSP ⌫", "Backspace, delete to the left", editing);
        add(keys, "DEL", "DEL ⌦", "Delete to the right", editing);
        add(keys, "INS", "INS", "Insert", editing);
        add(keys, "UP", "UP ↑", "Arrow up, previous command", navigation);
        add(keys, "DOWN", "DOWN ↓", "Arrow down", navigation);
        add(keys, "LEFT", "LEFT ←", "Arrow left", navigation);
        add(keys, "RIGHT", "RIGHT →", "Arrow right", navigation);
        add(keys, "HOME", "HOME", "Start of line", navigation);
        add(keys, "END", "END", "End of line", navigation);
        add(keys, "PGUP", "PGUP", "Page up", navigation);
        add(keys, "PGDN", "PGDN", "Page down", navigation);
        for (int i = 1; i <= 12; i++) add(keys, "F" + i, "F" + i, "Function key " + i, function);
        add(keys, "KEYBOARD", "KEYBOARD ⌨", "Show or hide the soft keyboard", app);
        add(keys, "DRAWER", "DRAWER ☰", "Open the sessions drawer", app);
        add(keys, "PASTE", "PASTE ⎘", "Paste from the clipboard", app);
        add(keys, "SCROLL", "SCROLL ⇳", "Toggle scroll mode", app);
        String[][] symbolKeys = {{"/", "Slash"}, {"-", "Dash"}, {"|", "Pipe"}, {"~", "Tilde, home directory"}, {"_", "Underscore"},
            {"*", "Asterisk"}, {"&", "Ampersand"}, {"$", "Dollar"}, {"#", "Hash"}, {"!", "Exclamation"}, {"?", "Question mark"},
            {":", "Colon"}, {";", "Semicolon"}, {"=", "Equals"}, {"+", "Plus"}, {"%", "Percent"}, {"^", "Caret"}, {"@", "At"},
            {"\\", "Backslash"}, {"'", "Single quote"}, {"\"", "Double quote"}, {"`", "Backtick"}, {"<", "Less than"}, {">", "Greater than"},
            {"(", "Open parenthesis"}, {")", "Close parenthesis"}, {"[", "Open bracket"}, {"]", "Close bracket"},
            {"{", "Open brace"}, {"}", "Close brace"}};
        for (String[] symbol : symbolKeys) add(keys, symbol[0], symbol[0], symbol[1], symbols);
        KEYS = Collections.unmodifiableList(keys);
    }

    private static void add(List<Key> keys, String value, String label, String description, String group) {
        keys.add(new Key(value, label, description, group));
    }

    private ExtraKeyCatalog() { }

    public static List<Key> all() { return KEYS; }

    public static List<Key> search(String query) {
        List<Key> result = new ArrayList<>();
        for (Key key : KEYS) if (key.matches(query)) result.add(key);
        return result;
    }
}
