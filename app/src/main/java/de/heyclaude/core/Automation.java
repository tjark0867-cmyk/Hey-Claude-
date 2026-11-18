package de.heyclaude.core;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Chat-Automatik ueber die Bedienungshilfe – funktioniert in beliebigen Chat-Apps (Purp, Snapchat,
 * Instagram, WhatsApp …), auch fuer Personen, die nicht als Kontakt gespeichert sind:
 * Chat in der Liste finden (sonst Chats-Tab oder Suche), pruefen, ob der richtige Chat offen ist,
 * Text eintragen, Senden druecken. Gesendet wird NUR, wenn der Name im geoeffneten Chat zu sehen ist.
 */
final class Automation {

    private static final String TAG = "HeyClaude";
    private static final long TIMEOUT_MS = 45_000L;

    /** Chat ist schon offen (z.B. WhatsApp-Link mit Text): nur eintragen + senden. */
    static final int MODE_SEND = 0;
    /** Chat erst in der App finden (Liste, Chats-Tab oder Suche), dann eintragen + senden. */
    static final int MODE_FIND_SEND = 1;
    /** Alter Name, gleiche Bedeutung. */
    static final int MODE_SEARCH_SEND = MODE_FIND_SEND;

    private static final String[] CHAT_TABS = {"Chats", "Chat", "Nachrichten", "Messages", "Inbox", "Posteingang",
            "Direct", "DMs", "Direktnachrichten", "Unterhaltungen", "Conversations", "Freunde", "Friends"};
    private static final String[] SEARCH_LABELS = {"Suchen", "Search", "Suche", "Durchsuchen", "Find"};
    private static final String[] SEND_LABELS = {"Senden", "Send", "Abschicken", "Nachricht senden", "Send message",
            "SMS senden", "Absenden"};

    // Schritte
    private static final int S_OPEN = 0;
    private static final int S_FIND = 1;
    private static final int S_SEARCH_TYPE = 2;
    private static final int S_SEARCH_PICK = 3;
    private static final int S_CHAT = 4;
    private static final int S_SEND = 5;
    private static final int S_VERIFY = 6;

    static final class Job {
        final String pkg;
        final String text;
        final String recipient;
        final int mode;
        final Context ctx;
        long deadline;
        int step;
        long waitUntil;
        int scrolls;
        boolean triedTab;
        boolean triedSearch;
        int sendTries;
        String nav = "liste";

        Job(Context ctx, String pkg, String text, String recipient, int mode) {
            this.ctx = ctx.getApplicationContext();
            this.pkg = pkg;
            this.text = text;
            this.recipient = recipient == null ? "" : recipient.trim();
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
            job.step = job.mode == MODE_SEND ? S_CHAT : S_OPEN;
            job.waitUntil = System.currentTimeMillis() + 1500;
            log(job, "Start (" + (job.mode == MODE_SEND ? "Chat direkt" : "Chat suchen") + ")");
            again(1500);
        });
    }

    /** Neues Ereignis aus einer App -> bald nachsehen. */
    static void poke() {
        Job j = current;
        if (j != null && System.currentTimeMillis() >= j.waitUntil) {
            again(250);
        }
    }

    private static void again(long ms) {
        MAIN.removeCallbacks(TICK);
        MAIN.postDelayed(TICK, ms);
    }

    private static void log(Job j, String msg) {
        Launcher.log("Chat " + j.recipient + " (" + j.pkg.substring(j.pkg.lastIndexOf('.') + 1) + "): " + msg);
    }

    private static void finish(boolean ok, String why) {
        Job j = current;
        current = null;
        MAIN.removeCallbacks(TICK);
        if (j == null) {
            return;
        }
        if (ok) {
            log(j, "gesendet ✅ (Weg: " + j.nav + ")");
            Prefs.get(j.ctx).edit().putString("chat_nav_" + j.pkg, j.nav).apply();
            Engine.announce("Gesendet.");
        } else {
            log(j, "abgebrochen: " + why);
            Hints.show(j.ctx, Hints.ID_LAUNCH, "Nachricht nicht gesendet",
                    "An " + j.recipient + ": „" + j.text + "“ – " + why + ". Der Text liegt in der Zwischenablage.", null);
            Engine.announce("Die Nachricht an " + j.recipient + " konnte ich nicht senden. " + why + ".");
        }
    }

    private static void tick() {
        Job j = current;
        if (j == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now > j.deadline) {
            finish(false, j.step <= S_SEARCH_PICK ? "Chat nicht gefunden" : "Senden hat nicht geklappt");
            return;
        }
        if (now < j.waitUntil) {
            again(j.waitUntil - now);
            return;
        }
        AccessibilityService s = Shell.accessibility();
        if (s == null) {
            finish(false, "Bedienungshilfe ist aus");
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
            step(s, j, root);
        } catch (Exception e) {
            Log.w(TAG, "Automatik", e);
            pause(j, 700);
        }
    }

    // ------------------------------------------------------------------ Ablauf

    private static void step(AccessibilityService s, Job j, AccessibilityNodeInfo root) {
        switch (j.step) {
            case S_OPEN: {
                // gelernten Weg zuerst (z.B. "suche", wenn die Liste das letzte Mal nicht reichte)
                String learned = Prefs.get(j.ctx).getString("chat_nav_" + j.pkg, "liste");
                j.step = S_FIND;
                if ("suche".equals(learned)) {
                    j.triedTab = true;
                    j.scrolls = 99;
                }
                pause(j, 600);
                return;
            }
            case S_FIND: {
                AccessibilityNodeInfo hit = findName(root, j.recipient);
                if (hit != null) {
                    log(j, "in der Liste gefunden");
                    tap(s, hit);
                    j.step = S_CHAT;
                    pause(j, 1500);
                    return;
                }
                if (j.scrolls < 2) {
                    AccessibilityNodeInfo list = firstScrollable(root);
                    if (list != null && list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                        j.scrolls++;
                        pause(j, 700);
                        return;
                    }
                    j.scrolls = 2;
                }
                if (!j.triedTab) {
                    j.triedTab = true;
                    AccessibilityNodeInfo tab = clickableWithText(root, CHAT_TABS);
                    if (tab != null) {
                        log(j, "öffne Chats-Bereich");
                        j.nav = "tab";
                        tap(s, tab);
                        j.scrolls = 0;
                        pause(j, 1300);
                        return;
                    }
                }
                if (!j.triedSearch) {
                    j.triedSearch = true;
                    AccessibilityNodeInfo field = firstEditable(root);
                    AccessibilityNodeInfo search = field != null ? field : findSearch(root);
                    if (search != null) {
                        log(j, "nutze die Suche");
                        j.nav = "suche";
                        tap(s, search);
                        j.step = S_SEARCH_TYPE;
                        pause(j, 900);
                        return;
                    }
                }
                finish(false, "„" + j.recipient + "“ habe ich in der App nicht gefunden");
                return;
            }
            case S_SEARCH_TYPE: {
                AccessibilityNodeInfo field = firstEditable(root);
                if (field == null) {
                    pause(j, 600);
                    return;
                }
                setText(field, j.recipient);
                j.step = S_SEARCH_PICK;
                pause(j, 2200); // Suchergebnisse laden lassen
                return;
            }
            case S_SEARCH_PICK: {
                AccessibilityNodeInfo hit = findName(root, j.recipient);
                if (hit == null) {
                    pause(j, 800);
                    return;
                }
                log(j, "in der Suche gefunden");
                tap(s, hit);
                j.step = S_CHAT;
                pause(j, 1700);
                return;
            }
            case S_CHAT: {
                AccessibilityNodeInfo composer = findComposer(root);
                if (composer == null) {
                    pause(j, 700);
                    return;
                }
                // Sicherheit: nur senden, wenn der Name im geoeffneten Chat zu sehen ist
                if (j.mode == MODE_FIND_SEND && findNameAnywhere(root, j.recipient) == null) {
                    finish(false, "Ich war mir nicht sicher, ob der richtige Chat offen ist");
                    return;
                }
                if (!contains(composer, j.text)) {
                    tap(s, composer);
                    setText(composer, j.text);
                    if (!contains(composer, j.text)) {
                        composer.performAction(AccessibilityNodeInfo.ACTION_PASTE); // Rueckfall: einfuegen
                    }
                }
                j.step = S_SEND;
                pause(j, 700);
                return;
            }
            case S_SEND: {
                AccessibilityNodeInfo composer = findComposer(root);
                if (composer != null && !contains(composer, j.text)) {
                    j.step = S_CHAT; // Text ging verloren -> nochmal
                    pause(j, 400);
                    return;
                }
                AccessibilityNodeInfo send = findSend(root, j.pkg, composer);
                if (send == null) {
                    if (++j.sendTries > 6) {
                        finish(false, "Den Senden-Knopf habe ich nicht gefunden");
                        return;
                    }
                    pause(j, 600);
                    return;
                }
                tap(s, send);
                j.step = S_VERIFY;
                pause(j, 1200);
                return;
            }
            case S_VERIFY: {
                AccessibilityNodeInfo composer = findComposer(root);
                if (composer == null || !contains(composer, j.text)) {
                    finish(true, null); // Eingabefeld ist leer -> gesendet
                    return;
                }
                if (++j.sendTries > 6) {
                    finish(false, "Senden hat nicht geklappt");
                    return;
                }
                j.step = S_SEND;
                pause(j, 500);
                return;
            }
            default:
                finish(false, "unbekannter Schritt");
        }
    }

    private static void pause(Job j, long ms) {
        j.waitUntil = System.currentTimeMillis() + ms;
        again(ms);
    }

    // ------------------------------------------------------------------ Bildschirm-Elemente finden

    private static List<AccessibilityNodeInfo> all(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        collect(root, out, 0);
        return out;
    }

    private static void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 45 || out.size() > 1500) {
            return;
        }
        out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) {
            collect(n.getChild(i), out, depth + 1);
        }
    }

    static String norm(CharSequence cs) {
        if (cs == null) {
            return "";
        }
        return cs.toString().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String label(AccessibilityNodeInfo n) {
        String t = norm(n.getText());
        return t.isEmpty() ? norm(n.getContentDescription()) : t;
    }

    /**
     * Wie gut passt der Text zum Namen? 3 = genau ("Emily"), 2 = beginnt damit ("Emily S.", "emily 🌸"),
     * 1 = kommt als Wort in kurzem Text vor, 0 = passt nicht (z.B. lange Nachrichtenvorschau).
     */
    private static int score(String label, String name) {
        String nm = norm(name);
        if (nm.isEmpty() || label.isEmpty()) {
            return 0;
        }
        if (label.equals(nm)) {
            return 3;
        }
        if (label.startsWith(nm + " ")) {
            return 2;
        }
        if ((" " + label + " ").contains(" " + nm + " ") && label.length() <= nm.length() + 25) {
            return 1;
        }
        return 0;
    }

    private static boolean matches(String label, String name) {
        return score(label, name) > 0;
    }

    /** Klickbarer Eintrag mit dem Namen (keine Eingabefelder): bester Treffer, bei Gleichstand der oberste. */
    private static AccessibilityNodeInfo findName(AccessibilityNodeInfo root, String name) {
        AccessibilityNodeInfo best = null;
        int bestScore = 0;
        int bestTop = Integer.MAX_VALUE;
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : all(root)) {
            if (n.isEditable() || !n.isVisibleToUser()) {
                continue;
            }
            int sc = score(label(n), name);
            if (sc == 0) {
                continue;
            }
            AccessibilityNodeInfo c = clickable(n);
            if (c == null || c.isEditable()) {
                c = n; // notfalls per Geste tippen
            }
            c.getBoundsInScreen(r);
            if (sc > bestScore || (sc == bestScore && r.top < bestTop)) {
                bestScore = sc;
                bestTop = r.top;
                best = c;
            }
        }
        return best;
    }

    /** Name irgendwo sichtbar (z.B. Titel des geoeffneten Chats). */
    private static AccessibilityNodeInfo findNameAnywhere(AccessibilityNodeInfo root, String name) {
        for (AccessibilityNodeInfo n : all(root)) {
            if (!n.isEditable() && n.isVisibleToUser() && matches(label(n), name)) {
                return n;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo root) {
        for (AccessibilityNodeInfo n : all(root)) {
            if (n.isEditable() && n.isVisibleToUser()) {
                return n;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo firstScrollable(AccessibilityNodeInfo root) {
        for (AccessibilityNodeInfo n : all(root)) {
            if (n.isScrollable() && n.isVisibleToUser()) {
                return n;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo findSearch(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo byLabel = clickableWithText(root, SEARCH_LABELS);
        if (byLabel != null) {
            return byLabel;
        }
        for (AccessibilityNodeInfo n : all(root)) {
            String id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName().toLowerCase(Locale.ROOT);
            if (id.contains("search") && n.isVisibleToUser()) {
                AccessibilityNodeInfo c = clickable(n);
                return c != null ? c : n;
            }
        }
        return null;
    }

    /** Eingabefeld fuer die Nachricht (meist das unterste Eingabefeld). */
    private static AccessibilityNodeInfo findComposer(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo lowest = null;
        int lowestBottom = -1;
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : all(root)) {
            if (!n.isEditable() || !n.isVisibleToUser()) {
                continue;
            }
            String id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName().toLowerCase(Locale.ROOT);
            String hint = n.getHintText() == null ? "" : n.getHintText().toString().toLowerCase(Locale.ROOT);
            if (id.endsWith(":id/entry") || id.contains("compose") || hint.contains("nachricht")
                    || hint.contains("message") || hint.contains("schreib")) {
                return n;
            }
            n.getBoundsInScreen(r);
            if (r.bottom > lowestBottom) {
                lowestBottom = r.bottom;
                lowest = n;
            }
        }
        return lowest;
    }

    /** Senden-Knopf: per ID, per Beschriftung, sonst der Knopf rechts neben dem Eingabefeld. */
    private static AccessibilityNodeInfo findSend(AccessibilityNodeInfo root, String pkg, AccessibilityNodeInfo composer) {
        List<AccessibilityNodeInfo> byId = root.findAccessibilityNodeInfosByViewId(pkg + ":id/send");
        if (byId != null) {
            for (AccessibilityNodeInfo n : byId) {
                if (n.isVisibleToUser()) {
                    AccessibilityNodeInfo c = clickable(n);
                    return c != null ? c : n;
                }
            }
        }
        AccessibilityNodeInfo byLabel = clickableWithText(root, SEND_LABELS);
        if (byLabel != null) {
            return byLabel;
        }
        for (AccessibilityNodeInfo n : all(root)) {
            String id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName().toLowerCase(Locale.ROOT);
            if (id.contains("send") && n.isVisibleToUser() && !n.isEditable()) {
                AccessibilityNodeInfo c = clickable(n);
                return c != null ? c : n;
            }
        }
        if (composer == null) {
            return null;
        }
        // Rueckfall: klickbares Element in derselben Zeile rechts vom Eingabefeld
        Rect cr = new Rect();
        composer.getBoundsInScreen(cr);
        AccessibilityNodeInfo best = null;
        int bestLeft = Integer.MAX_VALUE;
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : all(root)) {
            if (!n.isClickable() || n.isEditable() || !n.isVisibleToUser()) {
                continue;
            }
            n.getBoundsInScreen(r);
            int midY = (r.top + r.bottom) / 2;
            if (r.left >= cr.right - 10 && midY >= cr.top - 40 && midY <= cr.bottom + 40 && r.left < bestLeft) {
                bestLeft = r.left;
                best = n;
            }
        }
        return best;
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
                String l = label(n);
                String want = norm(label);
                if (!(l.equals(want) || l.startsWith(want))) {
                    continue; // nur echte Beschriftungen, nicht Nachrichtentext
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

    private static boolean contains(AccessibilityNodeInfo field, String text) {
        CharSequence t = field.getText();
        return t != null && norm(t).contains(norm(text));
    }

    // ------------------------------------------------------------------ Aktionen

    /** Tippen: normaler Klick, sonst eine echte Fingertipp-Geste auf die Mitte. */
    private static void tap(AccessibilityService s, AccessibilityNodeInfo n) {
        if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return;
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) {
            return;
        }
        Path p = new Path();
        p.moveTo(r.exactCenterX(), r.exactCenterY());
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60))
                .build();
        s.dispatchGesture(g, null, null);
    }

    private static void setText(AccessibilityNodeInfo node, String text) {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }
}
