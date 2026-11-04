package com.termux.app.compat;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.PersistableBundle;
import com.termux.shared.data.DataUtils;

/** Copy a user-selected web link without exposing it in supported clipboard previews. */
public final class SensitiveUrlClipboard {
    private SensitiveUrlClipboard() { }

    public static boolean copy(Context context, String url) {
        if (context == null || url == null) return false;
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return false;
            ClipData clip = ClipData.newPlainText(null, DataUtils.getTruncatedCommandOutput(
                url, DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES, true, false, false));
            if (Build.VERSION.SDK_INT >= 33) {
                PersistableBundle extras = new PersistableBundle();
                extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
                clip.getDescription().setExtras(extras);
            }
            clipboard.setPrimaryClip(clip);
            return true;
        } catch (RuntimeException e) {
            // Clipboard service errors can include the data; do not log them.
            return false;
        }
    }
}
