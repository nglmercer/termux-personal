package com.termux.app.activities;

import android.app.AlertDialog;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.termux.R;
import com.termux.app.extrakeys.ExtraKeyLayout;
import com.termux.app.extrakeys.ExtraKeyDocument;
import com.termux.app.extrakeys.ExtraKeyRecovery;
import com.termux.app.extrakeys.ExtraKeyPropertyStore;
import com.termux.shared.settings.properties.SharedProperties;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit, local editor for existing extra-key configuration. */
public final class ExtraKeysEditorActivity extends AppCompatActivity {
    private static final AtomicBoolean RELOAD_NEEDED = new AtomicBoolean();
    private static final int REQUEST_IMPORT = 48380, REQUEST_EXPORT = 48381;
    private static final String STATE_EDITOR = "extraKeysEditor", STATE_LAYOUT = "layout", STATE_ORIGINAL = "original",
        STATE_PROOF = "proof", STATE_BLOCKED = "blocked", STATE_PENDING_URI = "pendingUri", STATE_PENDING_REQUEST = "pendingRequest";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView status;
    private Button save;
    private ExtraKeyLayout layout;
    private ExtraKeyPropertyStore.Snapshot snapshot;
    private String originalLayout;
    private boolean busy, saving, saveBlocked;
    private byte[] snapshotProof;
    private Bundle recovery;
    private Uri pendingUri;
    private int pendingRequest;

    public static boolean consumeReloadNeeded() { return RELOAD_NEEDED.getAndSet(false); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            recovery = state.getBundle(STATE_EDITOR);
            String uri = state.getString(STATE_PENDING_URI);
            if (uri != null) { pendingUri = Uri.parse(uri); pendingRequest = state.getInt(STATE_PENDING_REQUEST); }
        }
        setTitle(R.string.extra_keys_editor_title);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        status = new TextView(this); status.setText(R.string.extra_keys_editor_loading); root.addView(status);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = new LinearLayout(this);
        Button presets = button(R.string.extra_keys_editor_presets, v -> choosePreset());
        Button revert = button(R.string.extra_keys_editor_revert, v -> revert());
        save = button(R.string.extra_keys_editor_save, v -> save()); save.setEnabled(false);
        actions.addView(presets); actions.addView(revert); actions.addView(save); root.addView(actions);
        LinearLayout transfer = new LinearLayout(this);
        transfer.addView(button(R.string.extra_keys_editor_import, v -> requestImport()));
        transfer.addView(button(R.string.extra_keys_editor_export, v -> requestExport()));
        root.addView(transfer);
        setContentView(root);
        load();
    }

    private Button button(int label, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(listener); return button;
    }

    private static File activeFile() {
        return SharedProperties.getPropertiesFileFromList(TermuxConstants.TERMUX_PROPERTIES_FILE_PATHS_LIST, "ExtraKeysEditor");
    }

    /** Reject redirected configuration directories; the store also protects the final file. */
    private static void validateParent(File file) throws IOException {
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH).getCanonicalFile();
        File expected;
        if (file.equals(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE)) expected = new File(home, ".termux");
        else if (file.equals(TermuxConstants.TERMUX_PROPERTIES_SECONDARY_FILE)) expected = new File(home, ".config/termux");
        else throw new IOException("Unexpected properties path");
        if (!file.getParentFile().getCanonicalFile().equals(expected)) throw new IOException("Redirected configuration directory");
    }

    private static String layoutFrom(byte[] bytes) throws IOException {
        Properties properties = new Properties();
        try (InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IllegalArgumentException e) { throw new IOException("Invalid properties", e); }
        return properties.getProperty(TermuxPropertyConstants.KEY_EXTRA_KEYS, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS);
    }

    private void load() {
        busy = true;
        WeakReference<ExtraKeysEditorActivity> ref = new WeakReference<>(this);
        Handler handler = main;
        io.execute(() -> {
            ExtraKeyPropertyStore.Snapshot result = null; ExtraKeyLayout parsed = null; String original = null;
            try {
                File file = activeFile();
                if (file == null) {
                    file = TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE;
                    validateParent(file);
                    File parent = file.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdir()) throw new IOException("Cannot create configuration directory");
                }
                validateParent(file);
                result = ExtraKeyPropertyStore.read(file);
                original = layoutFrom(result.bytes());
                parsed = ExtraKeyLayout.parse(original);
                new ExtraKeysInfo(parsed.toJson(), "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES);
            } catch (Exception ignored) { parsed = null; }
            final ExtraKeyPropertyStore.Snapshot loaded = result;
            final ExtraKeyLayout model = parsed;
            final String initial = original;
            final byte[] proof = loaded == null ? null : loaded.fingerprint();
            handler.post(() -> {
                ExtraKeysEditorActivity current = ref.get();
                if (current == null || current.isFinishing() || current.isDestroyed()) return;
                current.busy = false;
                if (loaded == null || (model == null && current.recovery == null)) {
                    current.saveBlocked = true; current.status.setText(R.string.extra_keys_editor_load_failed); return;
                }
                current.snapshot = loaded; current.snapshotProof = proof; current.layout = model; current.originalLayout = initial;
                if (current.recovery != null) {
                    Bundle saved = current.recovery;
                    current.recovery = null;
                    try {
                        ExtraKeyRecovery restored = ExtraKeyRecovery.restore(saved.getString(STATE_LAYOUT), saved.getString(STATE_ORIGINAL),
                            saved.getByteArray(STATE_PROOF), proof, saved.getBoolean(STATE_BLOCKED));
                        current.layout = restored.layout; current.originalLayout = restored.original;
                        current.saveBlocked = restored.saveBlocked || model == null;
                    } catch (Exception e) { current.saveBlocked = true; }
                }
                if (current.layout == null) {
                    current.status.setText(R.string.extra_keys_editor_load_failed); return;
                }
                current.status.setText(current.saveBlocked ? current.getString(R.string.extra_keys_editor_restore_conflict) :
                    current.getString(R.string.extra_keys_editor_explanation, loaded.file().getAbsolutePath()));
                current.render();
                if (current.pendingUri != null) {
                    Uri uri = current.pendingUri; int request = current.pendingRequest;
                    current.pendingUri = null; current.pendingRequest = 0;
                    current.transfer(request, uri);
                }
            });
        });
    }

    private void render() {
        if (layout == null) return;
        content.removeAllViews();
        for (int row = 0; row < layout.rowCount(); row++) {
            final int r = row;
            TextView label = new TextView(this); label.setText(getString(R.string.extra_keys_editor_row, row + 1)); content.addView(label);
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            LinearLayout keys = new LinearLayout(this);
            for (int index = 0; index < layout.keyCount(row); index++) {
                final int i = index;
                String labelText = keyLabel(layout.key(row, index));
                Button key = new Button(this); key.setText(labelText); key.setContentDescription(getString(R.string.extra_keys_editor_key_description, row + 1, index + 1, labelText));
                key.setOnClickListener(v -> editKey(r, i)); keys.addView(key);
            }
            scroll.addView(keys); content.addView(scroll);
            LinearLayout rowActions = new LinearLayout(this);
            rowActions.addView(button(R.string.extra_keys_editor_add_key, v -> addKey(r)));
            rowActions.addView(button(R.string.extra_keys_editor_remove_row, v -> removeRow(r)));
            content.addView(rowActions);
        }
        Button addRow = button(R.string.extra_keys_editor_add_row, v -> {
            if (busy) return;
            try { layout.addRow(); render(); } catch (RuntimeException e) { message(R.string.extra_keys_editor_limit); }
        });
        addRow.setEnabled(layout.rowCount() < ExtraKeyLayout.MAX_ROWS && !busy); content.addView(addRow);
        save.setEnabled(!busy && !saveBlocked && snapshot != null);
    }

    private String keyLabel(JsonElement value) {
        if (value.isJsonPrimitive()) return truncate(value.getAsString());
        JsonObject object = value.getAsJsonObject();
        String text = object.has("display") ? object.get("display").getAsString() :
            object.has("macro") ? object.get("macro").getAsString() : object.get("key").getAsString();
        if (object.has("macro")) text = getString(R.string.extra_keys_editor_macro_label, text);
        if (object.has("popup")) text = getString(R.string.extra_keys_editor_popup_label, text);
        return truncate(text);
    }
    private static String truncate(String text) { return text.length() > 40 ? text.substring(0, 40) + "…" : text; }

    private interface KeyChoice { void chosen(String value); }

    private void addKey(int row) {
        if (busy || layout == null) return;
        pickKey(R.string.extra_keys_editor_add_key, value -> {
            try { layout.addKey(row, value); render(); }
            catch (RuntimeException e) { message(R.string.extra_keys_editor_limit); }
        });
    }

    /**
     * Searchable list of every named key and common symbol with a short description, so nobody needs to know
     * the configuration format. The last row enters any other character or text.
     */
    private void pickKey(int title, KeyChoice choice) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        panel.setPadding(pad, pad / 2, pad, 0);
        EditText search = new EditText(this);
        search.setSingleLine(true); search.setHint(R.string.extra_keys_editor_search); search.setInputType(InputType.TYPE_CLASS_TEXT);
        panel.addView(search);
        android.widget.ListView list = new android.widget.ListView(this);
        panel.addView(list, new LinearLayout.LayoutParams(-1, (int) (360 * getResources().getDisplayMetrics().density)));
        final java.util.List<com.termux.app.extrakeys.ExtraKeyCatalog.Key> shown = new java.util.ArrayList<>(com.termux.app.extrakeys.ExtraKeyCatalog.all());
        android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return shown.size() + 1; }
            @Override public Object getItem(int position) { return position < shown.size() ? shown.get(position) : null; }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convert, android.view.ViewGroup parent) {
                View view = convert != null ? convert :
                    getLayoutInflater().inflate(android.R.layout.simple_list_item_2, parent, false);
                TextView first = view.findViewById(android.R.id.text1), second = view.findViewById(android.R.id.text2);
                if (position < shown.size()) {
                    com.termux.app.extrakeys.ExtraKeyCatalog.Key key = shown.get(position);
                    first.setText(key.label);
                    second.setText(key.group + " · " + key.description);
                } else {
                    first.setText(R.string.extra_keys_editor_custom);
                    second.setText(R.string.extra_keys_editor_custom_summary);
                }
                return view;
            }
        };
        list.setAdapter(adapter);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable text) {
                shown.clear(); shown.addAll(com.termux.app.extrakeys.ExtraKeyCatalog.search(text.toString()));
                adapter.notifyDataSetChanged();
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(panel)
            .setNegativeButton(android.R.string.cancel, null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            if (position < shown.size()) choice.chosen(shown.get(position).value);
            else customKey(title, search.getText().toString(), choice);
        });
        dialog.show();
    }

    private void customKey(int title, String initial, KeyChoice choice) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(initial);
        new AlertDialog.Builder(this).setTitle(title).setMessage(R.string.extra_keys_editor_custom_summary).setView(input)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                if (input.getText().length() == 0) { message(R.string.extra_keys_editor_invalid); return; }
                choice.chosen(input.getText().toString());
            }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void removeRow(int row) {
        if (busy || layout == null) return;
        new AlertDialog.Builder(this).setMessage(R.string.extra_keys_editor_remove_row_confirm)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                try { layout.removeRow(row); render(); } catch (RuntimeException e) { message(R.string.extra_keys_editor_limit); }
            }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void editKey(int row, int index) {
        if (busy || layout == null) return;
        String[] options = getResources().getStringArray(R.array.extra_keys_editor_actions);
        new AlertDialog.Builder(this).setTitle(keyLabel(layout.key(row, index))).setItems(options, (d, choice) -> {
            try {
                switch (choice) {
                    case 0: layout.moveKey(row, index, row, index - 1); break;
                    case 1: layout.moveKey(row, index, row, index + 1); break;
                    case 2: layout.moveKey(row, index, row - 1, Math.min(index, layout.keyCount(row - 1))); break;
                    case 3: layout.moveKey(row, index, row + 1, Math.min(index, layout.keyCount(row + 1))); break;
                    case 4:
                        pickKey(R.string.extra_keys_editor_replace_key, value -> {
                            try { layout.replaceKeyJson(row, index, new com.google.gson.JsonPrimitive(value).toString()); render(); }
                            catch (Exception e) { message(R.string.extra_keys_editor_invalid); }
                        });
                        return;
                    case 5: editKeyJson(row, index); return;
                    case 6: layout.removeKey(row, index); break;
                    default: return;
                }
                render();
            } catch (RuntimeException e) { message(R.string.extra_keys_editor_limit); }
        }).show();
    }

    private void editKeyJson(int row, int index) {
        EditText input = new EditText(this); input.setText(layout.key(row, index).toString());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(this).setTitle(R.string.extra_keys_editor_edit_json).setMessage(R.string.extra_keys_editor_json_hint).setView(input)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                try { layout.replaceKeyJson(row, index, input.getText().toString()); render(); }
                catch (Exception e) { message(R.string.extra_keys_editor_invalid); }
            }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void choosePreset() {
        if (busy || layout == null) return;
        ExtraKeyLayout.Preset[] presets = ExtraKeyLayout.Preset.values();
        new AlertDialog.Builder(this).setTitle(R.string.extra_keys_editor_presets)
            .setItems(R.array.extra_keys_editor_presets, (d, index) -> new AlertDialog.Builder(this)
                .setMessage(R.string.extra_keys_editor_preset_confirm)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    try { layout = ExtraKeyLayout.preset(presets[index]); render(); }
                    catch (IOException e) { message(R.string.extra_keys_editor_invalid); }
                }).setNegativeButton(android.R.string.cancel, null).show()).show();
    }

    private void revert() {
        if (busy || originalLayout == null) return;
        try { layout = ExtraKeyLayout.parse(originalLayout); render(); }
        catch (IOException e) { message(R.string.extra_keys_editor_invalid); }
    }

    private void requestImport() {
        if (busy || layout == null) return;
        new AlertDialog.Builder(this).setMessage(R.string.extra_keys_editor_import_confirm)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
                try { startActivityForResult(intent, REQUEST_IMPORT); }
                catch (ActivityNotFoundException e) { message(R.string.extra_keys_editor_picker_failed); }
            }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void requestExport() {
        if (busy || layout == null) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "termux-extra-keys.json");
        try { startActivityForResult(intent, REQUEST_EXPORT); }
        catch (ActivityNotFoundException e) { message(R.string.extra_keys_editor_picker_failed); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT && requestCode != REQUEST_EXPORT) return;
        if (resultCode != Activity.RESULT_OK) return;
        Uri uri = data == null ? null : data.getData();
        if (uri != null && "content".equals(uri.getScheme()) && layout == null && !saveBlocked) {
            pendingUri = uri; pendingRequest = requestCode; return;
        }
        transfer(requestCode, uri);
    }

    private void transfer(int requestCode, Uri uri) {
        if ((requestCode != REQUEST_IMPORT && requestCode != REQUEST_EXPORT) || busy || layout == null || snapshot == null || uri == null || !"content".equals(uri.getScheme())) {
            message(R.string.extra_keys_editor_transfer_failed); return;
        }
        final ExtraKeyLayout exported;
        try { exported = requestCode == REQUEST_EXPORT ? ExtraKeyLayout.parse(layout.toJson()) : null; }
        catch (IOException e) { message(R.string.extra_keys_editor_invalid); return; }
        busy = true; render();
        status.setText(requestCode == REQUEST_IMPORT ? R.string.extra_keys_editor_importing : R.string.extra_keys_editor_exporting);
        ContentResolver resolver = getApplicationContext().getContentResolver();
        WeakReference<ExtraKeysEditorActivity> ref = new WeakReference<>(this);
        Handler handler = main;
        io.execute(() -> {
            ExtraKeyLayout imported = null; boolean success = false;
            try {
                if (requestCode == REQUEST_IMPORT) {
                    try (InputStream input = resolver.openInputStream(uri)) {
                        if (input == null) throw new IOException("Input unavailable");
                        imported = ExtraKeyDocument.read(input);
                        new ExtraKeysInfo(imported.toJson(), "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES);
                    }
                } else {
                    // Validate and serialize before a provider is allowed to truncate the selected document.
                    java.io.ByteArrayOutputStream staged = new java.io.ByteArrayOutputStream();
                    ExtraKeyDocument.write(staged, exported);
                    try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
                        if (output == null) throw new IOException("Output unavailable");
                        staged.writeTo(output);
                        output.flush();
                    }
                }
                success = true;
            } catch (Exception ignored) { /* Never log document contents or provider URI. */ }
            final ExtraKeyLayout result = imported;
            final boolean completed = success;
            handler.post(() -> {
                ExtraKeysEditorActivity current = ref.get();
                if (current == null || current.isFinishing() || current.isDestroyed()) return;
                current.busy = false;
                current.status.setText(current.saveBlocked ? current.getString(R.string.extra_keys_editor_restore_conflict) :
                    current.getString(R.string.extra_keys_editor_explanation, current.snapshot.file().getAbsolutePath()));
                if (completed && requestCode == REQUEST_IMPORT) current.layout = result;
                current.render();
                current.message(completed ? (requestCode == REQUEST_IMPORT ? R.string.extra_keys_editor_imported : R.string.extra_keys_editor_exported) :
                    (requestCode == REQUEST_IMPORT ? R.string.extra_keys_editor_import_failed : R.string.extra_keys_editor_export_failed));
            });
        });
    }

    private void save() {
        if (busy || saveBlocked || snapshot == null || layout == null) return;
        final ExtraKeyLayout copy;
        try { copy = ExtraKeyLayout.parse(layout.toJson()); }
        catch (IOException e) { message(R.string.extra_keys_editor_invalid); return; }
        busy = true; saving = true; render();
        final ExtraKeyPropertyStore.Snapshot expected = snapshot;
        WeakReference<ExtraKeysEditorActivity> ref = new WeakReference<>(this);
        Handler handler = main;
        io.execute(() -> {
            ExtraKeyPropertyStore.SaveResult result = null; String error = null; boolean uncertain = false;
            try {
                File current = activeFile();
                if ((expected.existed() && (current == null || !current.equals(expected.file()))) ||
                    (!expected.existed() && current != null)) throw new ExtraKeyPropertyStore.ConflictException();
                validateParent(expected.file());
                new ExtraKeysInfo(copy.toJson(), "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES);
                result = ExtraKeyPropertyStore.save(expected, copy);
                RELOAD_NEEDED.set(true);
            } catch (ExtraKeyPropertyStore.SaveUncertainException e) {
                uncertain = true; error = e.getMessage() + (e.backup == null ? "" : "\n" + e.backup.getAbsolutePath());
            } catch (Exception e) { error = e.getMessage(); }
            final ExtraKeyPropertyStore.SaveResult saved = result;
            final String failure = error;
            final boolean wasUncertain = uncertain;
            handler.post(() -> {
                ExtraKeysEditorActivity current = ref.get();
                if (current == null || current.isFinishing() || current.isDestroyed()) return;
                current.busy = false; current.saving = false;
                if (saved != null) {
                    android.widget.Toast.makeText(current, saved.backup == null ? current.getString(R.string.extra_keys_editor_saved) :
                        current.getString(R.string.extra_keys_editor_saved_backup, saved.backup.getAbsolutePath()), android.widget.Toast.LENGTH_LONG).show();
                    current.setResult(RESULT_OK);
                    current.finish();
                } else {
                    if (wasUncertain) current.saveBlocked = true;
                    current.render();
                    new AlertDialog.Builder(current).setTitle(R.string.extra_keys_editor_save_failed)
                        .setMessage(failure == null ? current.getString(R.string.extra_keys_editor_invalid) : failure)
                        .setPositiveButton(android.R.string.ok, null).show();
                }
            });
        });
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        Bundle saved = recovery == null ? null : new Bundle(recovery);
        if (layout != null && snapshotProof != null && originalLayout != null) {
            saved = new Bundle();
            saved.putString(STATE_LAYOUT, layout.toJson());
            saved.putString(STATE_ORIGINAL, originalLayout);
            saved.putByteArray(STATE_PROOF, snapshotProof);
            saved.putBoolean(STATE_BLOCKED, saveBlocked || saving);
        }
        if (saved != null) out.putBundle(STATE_EDITOR, saved);
        if (pendingUri != null) {
            out.putString(STATE_PENDING_URI, pendingUri.toString());
            out.putInt(STATE_PENDING_REQUEST, pendingRequest);
        }
    }

    private void message(int text) { new AlertDialog.Builder(this).setMessage(text).setPositiveButton(android.R.string.ok, null).show(); }
    @Override public void onBackPressed() { if (!saving) super.onBackPressed(); }
    @Override protected void onDestroy() {
        if (saving) io.shutdown(); else io.shutdownNow();
        super.onDestroy();
    }
}
