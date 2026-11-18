package de.heyclaude.core;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tippt mit der Bedienungshilfe in anderen Apps: Text eintragen, Empfaenger suchen, "Senden" druecken.
 * Laeuft nur fuer einen konkreten Auftrag (z.B. "schreib Emily hey") und hoert nach kurzer Zeit auf.
 */
final class Automation {

    private static final String TAG = "HeyClaude";
    private static final long TIMEOUT_MS = 30_000L;

    static final int MODE_SEND = 0;        // Chat ist offen (Text evtl. schon eingetragen) -> eintragen + senden
    static final int MODE_SEARCH_SEND = 1; // Erst Empfaenger in der App suchen, dann eintragen + senden

    static final class Job {
        final String pkg;
        final String text;
        final String recipient;
        final int mode;
        final Context ctx;
        long deadline;
        int step;
        long waitUntil;

        Job(Context ctx, String pkg, String text, String recipient, int mode) {
            this.ctx = ctx.getApplicationContext();
            this.pkg = pkg;
            this.text = text;
            this.recipient = recipient;
            this.mode = mode;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Job current;

    private static final Runnable TICK = Automation::tick;

    private Automation() {
    }

    static boolean available() {
        return Shell.accessibility() != null;
    }

    static void start(Job job) {
        MAIN.post(() -> {
            current = job;
            job.deadline = System.currentTimeMillis() + TIMEOUT_MS;
            job.step = 0;
            job.waitUntil = System.currentTimeMillis() + 1500; // App erst mal oeffnen lassen
            MAIN.removeCallbacks(TICK);
            MAIN.postDelayed(TICK, 1500);
        });
    }

    /** Neues Ereignis aus einer App -> bald nachsehen. */
    static void poke() {
        if (current != null) {
            MAIN.removeCallbacks(TICK);
            MAIN.postDelayed(TICK, 250);
        }
    }

    private static void again(long ms) {
        MAIN.removeCallbacks(TICK);
        MAIN.postDelayed(TICK, ms);
    }

    private static void finish(boolean ok, String why) {
        Job j = current;
        current = null;
        MAIN.removeCallbacks(TICK);
        if (j == null) {
            return;
        }
        if (ok) {
            Log.i(TAG, "Nachricht gesendet an " + j.recipient);
        } else {
            Log.w(TAG, "Automatik abgebrochen: " + why);
            Hints.show(j.ctx, Hints.ID_LAUNCH, "Nachricht nicht gesendet",
                    "An " + j.recipient + ": „" + j.text + "“ – bitte selbst auf Senden tippen.", null);
        }
    }

    private static void tick() {
        Job j = current;
        if (j == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now > j.deadline) {
            finish(false, "Zeit abgelaufen");
            return;
        }
        if (now < j.waitUntil) {
            again(j.waitUntil - now);
            return;
        }
        AccessibilityService s = Shell.accessibility();
        if (s == null) {
            finish(false, "Bedienungshilfe aus");
            return;
        }
        AccessibilityNodeInfo root;
        try {
            root = s.getRootInActiveWindow();
        } catch (Exception e) {
            root = null;
        }
        if (root == null || root.getPackageName() == null || !j.pkg.equals(root.getPackageName().toString())) {
            again(500);
            return;
        }
        try {
            if (j.mode == MODE_SEARCH_SEND) {
                searchStep(j, root);
            } else {
                sendStep(j, root);
            }
        } catch (Exception e) {
            Log.w(TAG, "Automatik", e);
            again(700);
        }
    }

    // ------------------------------------------------------------------ Schritte

    /** Empfaenger in der App suchen und Chat oeffnen (z.B. Instagram-Direktnachrichten). */
    private static void searchStep(Job j, AccessibilityNodeInfo root) {
        if (j.step == 0) {
            AccessibilityNodeInfo field = firstEditable(root);
            if (field == null) {
                AccessibilityNodeInfo search = clickableWithText(root, "Suchen", "Search", "Suche");
                if (search != null) {
                    search.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                wait(j, 900);
                return;
            }
            setText(field, j.recipient);
            j.step = 1;
            wait(j, 2000); // Suchergebnisse laden lassen
            return;
        }
        if (j.step == 1) {
            AccessibilityNodeInfo hit = clickableWithText(root, j.recipient);
            if (hit == null) {
                wait(j, 800);
                return;
            }
            hit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            j.step = 2;
            wait(j, 1800);
            return;
        }
        // ab hier wie im offenen Chat
        sendStep(j, root);
    }

    /** Im offenen Chat: Text eintragen (falls noetig) und Senden druecken. */
    private static void sendStep(Job j, AccessibilityNodeInfo root) {
        AccessibilityNodeInfo composer = findComposer(root);
        boolean hasText = composer != null && composer.getText() != null
                && composer.getText().toString().contains(j.text);
        if (composer != null && !hasText && j.step < 10) {
            setText(composer, j.text);
            j.step = 10;
            wait(j, 700);
            return;
        }
        AccessibilityNodeInfo send = findSend(root, j.pkg);
        if (send != null && (hasText || j.step >= 10 || composer == null)) {
            if (send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                finish(true, null);
                return;
            }
        }
        wait(j, 600);
    }

    private static void wait(Job j, long ms) {
        j.waitUntil = System.currentTimeMillis() + ms;
        again(ms);
    }

    // ------------------------------------------------------------------ Bildschirm-Elemente finden

    private static void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 40) {
            return;
        }
        out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) {
            collect(n.getChild(i), out, depth + 1);
        }
    }

    private static AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collect(root, all, 0);
        for (AccessibilityNodeInfo n : all) {
            if (n.isEditable() && n.isVisibleToUser()) {
                return n;
            }
        }
        return null;
    }

    /** Eingabefeld fuer die Nachricht. */
    private static AccessibilityNodeInfo findComposer(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collect(root, all, 0);
        AccessibilityNodeInfo last = null;
        for (AccessibilityNodeInfo n : all) {
            if (!n.isEditable() || !n.isVisibleToUser()) {
                continue;
            }
            last = n;
            String id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName().toLowerCase(Locale.ROOT);
            String hint = n.getHintText() == null ? "" : n.getHintText().toString().toLowerCase(Locale.ROOT);
            if (id.endsWith(":id/entry") || id.contains("compose") || id.contains("message")
                    || hint.contains("nachricht") || hint.contains("message") || hint.contains("text")) {
                return n;
            }
        }
        return last;
    }

    /** Senden-Knopf. */
    private static AccessibilityNodeInfo findSend(AccessibilityNodeInfo root, String pkg) {
        List<AccessibilityNodeInfo> byId = root.findAccessibilityNodeInfosByViewId(pkg + ":id/send");
        if (byId != null) {
            for (AccessibilityNodeInfo n : byId) {
                AccessibilityNodeInfo c = clickable(n);
                if (c != null && n.isVisibleToUser()) {
                    return c;
                }
            }
        }
        return clickableWithText(root, "Senden", "Send", "SMS senden", "Nachricht senden", "Send message");
    }

    /** Klickbares Element, dessen Text/Beschreibung einen der Begriffe enthaelt (keine Eingabefelder). */
    private static AccessibilityNodeInfo clickableWithText(AccessibilityNodeInfo root, String... labels) {
        for (String label : labels) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(label);
            if (hits == null) {
                continue;
            }
            for (AccessibilityNodeInfo n : hits) {
                if (n.isEditable() || !n.isVisibleToUser()) {
                    continue;
                }
                AccessibilityNodeInfo c = clickable(n);
                if (c != null && !c.isEditable()) {
                    return c;
                }
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        int guard = 0;
        while (n != null && !n.isClickable() && guard++ < 8) {
            n = n.getParent();
        }
        return n;
    }

    private static void setText(AccessibilityNodeInfo node, String text) {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }
}
