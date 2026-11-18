package de.heyclaude.core;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.provider.ContactsContract;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.net.URLEncoder;
import java.util.Locale;

/** Nachrichten schreiben: Chat oeffnen, Text eintragen und – mit Bedienungshilfe – absenden. */
final class Messenger {

    private static final String TAG = "HeyClaude";
    static final String WHATSAPP = "com.whatsapp";
    static final String WHATSAPP_BUSINESS = "com.whatsapp.w4b";
    static final String INSTAGRAM = "com.instagram.android";
    static final String TELEGRAM = "org.telegram.messenger";

    private Messenger() {
    }

    static boolean canReadContacts(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Liefert das Werkzeug-Ergebnis fuer Claude (kurz). */
    static String send(Context ctx, Handler main, String app, String to, String text) {
        String a = app == null ? "" : app.toLowerCase(Locale.ROOT).trim();
        String recipient = to == null ? "" : to.trim();
        String msg = text == null ? "" : text.trim();
        if (recipient.isEmpty() || msg.isEmpty()) {
            return "Fehler: Empfänger oder Text fehlt.";
        }
        copyToClipboard(ctx, msg); // Rueckfall: Text liegt in der Zwischenablage
        switch (a) {
            case "":
            case "whatsapp":
                return whatsapp(ctx, main, recipient, msg);
            case "instagram":
                return instagram(ctx, main, recipient, msg);
            case "sms":
                return sms(ctx, main, recipient, msg);
            case "telegram":
                return telegram(ctx, main, recipient, msg);
            default:
                return anyApp(ctx, main, app.trim(), recipient, msg); // Purp, Snapchat, Discord …
        }
    }

    // ------------------------------------------------------------------ Apps

    private static String whatsapp(Context ctx, Handler main, String to, String msg) {
        String pkg = Launcher.isInstalled(ctx, WHATSAPP) ? WHATSAPP
                : Launcher.isInstalled(ctx, WHATSAPP_BUSINESS) ? WHATSAPP_BUSINESS : null;
        if (pkg == null) {
            return "Fehler: WhatsApp ist nicht installiert.";
        }
        String[] contact = findContact(ctx, to);
        String number = contact != null ? contact[1] : (looksLikeNumber(to) ? to : null);
        if (number == null) {
            return canReadContacts(ctx)
                    ? "Fehler: " + to + " ist nicht in deinen Kontakten."
                    : "Fehler: Ich darf deine Kontakte noch nicht lesen (in der App erlauben).";
        }
        String digits = internationalDigits(ctx, number);
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + digits + "?text=" + enc(msg)));
        i.setPackage(pkg);
        Launcher.launch(ctx, main, i, "WhatsApp an " + (contact != null ? contact[0] : to));
        return autoSend(ctx, pkg, msg, contact != null ? contact[0] : to, Automation.MODE_SEND);
    }

    private static String sms(Context ctx, Handler main, String to, String msg) {
        String[] contact = findContact(ctx, to);
        String number = contact != null ? contact[1] : (looksLikeNumber(to) ? to : null);
        if (number == null) {
            return canReadContacts(ctx)
                    ? "Fehler: " + to + " ist nicht in deinen Kontakten."
                    : "Fehler: Ich darf deine Kontakte noch nicht lesen (in der App erlauben).";
        }
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)));
        i.putExtra("sms_body", msg);
        String smsPkg = defaultSmsPackage(ctx);
        if (smsPkg != null) {
            i.setPackage(smsPkg);
        }
        Launcher.launch(ctx, main, i, "SMS an " + (contact != null ? contact[0] : to));
        return smsPkg == null ? "OK: SMS-App geöffnet"
                : autoSend(ctx, smsPkg, msg, contact != null ? contact[0] : to, Automation.MODE_SEND);
    }

    private static String instagram(Context ctx, Handler main, String to, String msg) {
        if (!Launcher.isInstalled(ctx, INSTAGRAM)) {
            return "Fehler: Instagram ist nicht installiert.";
        }
        String user = to.startsWith("@") ? to.substring(1) : to;
        // Benutzername (z.B. "emily.mueller" oder "@emily") -> direkt in den Chat; sonst per Name suchen
        boolean isUsername = user.matches("[A-Za-z0-9._]{2,30}")
                && (to.startsWith("@") || user.matches(".*[._0-9].*"));
        Intent i;
        int mode;
        if (isUsername) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://ig.me/m/" + user));
            mode = Automation.MODE_SEND;
        } else {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/direct/inbox/"));
            mode = Automation.MODE_FIND_SEND;
        }
        i.setPackage(INSTAGRAM);
        Launcher.launch(ctx, main, i, "Instagram: " + to);
        return autoSend(ctx, INSTAGRAM, msg, isUsername ? user : to, mode);
    }

    private static String telegram(Context ctx, Handler main, String to, String msg) {
        if (!Launcher.isInstalled(ctx, TELEGRAM)) {
            return "Fehler: Telegram ist nicht installiert.";
        }
        String user = to.startsWith("@") ? to.substring(1) : to;
        Intent i;
        int mode;
        if (user.matches("[A-Za-z0-9_]{5,32}") && !user.equals(capitalized(user))) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/" + user));
            mode = Automation.MODE_SEND;
        } else {
            i = ctx.getPackageManager().getLaunchIntentForPackage(TELEGRAM);
            mode = Automation.MODE_SEARCH_SEND;
        }
        if (i == null) {
            return "Fehler: Telegram lässt sich nicht öffnen.";
        }
        i.setPackage(TELEGRAM);
        Launcher.launch(ctx, main, i, "Telegram: " + to);
        return autoSend(ctx, TELEGRAM, msg, user, mode);
    }

    /** Beliebige Chat-App: oeffnen, Chat per Name finden (Liste, Chats-Tab, Suche), pruefen, senden. */
    private static String anyApp(Context ctx, Handler main, String appName, String to, String msg) {
        String[] found = Launcher.findApp(ctx, appName);
        if (found == null) {
            return "Fehler: Die App „" + appName + "“ habe ich auf dem Handy nicht gefunden.";
        }
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(found[0]);
        if (i == null) {
            return "Fehler: " + found[1] + " lässt sich nicht öffnen.";
        }
        Launcher.launch(ctx, main, i, found[1] + ": " + to);
        if (!Automation.available()) {
            return "OK: " + found[1] + " geöffnet, Text in der Zwischenablage (zum automatischen Senden die Bedienungshilfe einschalten)";
        }
        Automation.start(new Automation.Job(ctx, found[0], msg, to, Automation.MODE_FIND_SEND));
        return "OK: Nachricht wird in " + found[1] + " gesendet";
    }

    private static String autoSend(Context ctx, String pkg, String msg, String recipient, int mode) {
        if (Automation.available()) {
            Automation.start(new Automation.Job(ctx, pkg, msg, recipient, mode));
            return "OK: Nachricht wird gesendet";
        }
        return "OK: Chat geöffnet, Text eingetragen";
    }

    // ------------------------------------------------------------------ Kontakte

    /** Sucht einen Kontakt per Name. Liefert {Name, Nummer} oder null. */
    static String[] findContact(Context ctx, String name) {
        if (!canReadContacts(ctx) || name == null || name.trim().isEmpty()) {
            return null;
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        String[] best = null;
        int bestScore = 0;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.TYPE},
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                    new String[]{"%" + name.trim() + "%"}, null);
            if (c == null) {
                return null;
            }
            while (c.moveToNext()) {
                String display = c.getString(0);
                String number = c.getString(1);
                int type = c.getInt(2);
                if (display == null || number == null) {
                    continue;
                }
                String d = display.toLowerCase(Locale.ROOT);
                int score = d.equals(wanted) ? 30 : d.startsWith(wanted + " ") || d.startsWith(wanted) ? 20 : 10;
                if (type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) {
                    score += 5;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = new String[]{display, number};
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Kontakte", e);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ Hilfen

    private static boolean looksLikeNumber(String s) {
        return s.replaceAll("[\\s()/-]", "").matches("\\+?[0-9]{6,15}");
    }

    /** Nummer fuer wa.me: nur Ziffern mit Laendervorwahl (0171… -> 49171…). */
    static String internationalDigits(Context ctx, String number) {
        String n = number.replaceAll("[^0-9+]", "");
        if (n.startsWith("+")) {
            return n.substring(1);
        }
        if (n.startsWith("00")) {
            return n.substring(2);
        }
        if (n.startsWith("0")) {
            return countryCode(ctx) + n.substring(1);
        }
        return n;
    }

    private static String countryCode(Context ctx) {
        String iso = "";
        try {
            TelephonyManager tm = ctx.getSystemService(TelephonyManager.class);
            if (tm != null && tm.getSimCountryIso() != null) {
                iso = tm.getSimCountryIso();
            }
        } catch (Exception ignored) {
            // egal
        }
        if (iso.isEmpty()) {
            iso = Locale.getDefault().getCountry();
        }
        switch (iso.toLowerCase(Locale.ROOT)) {
            case "at":
                return "43";
            case "ch":
                return "41";
            case "nl":
                return "31";
            case "pl":
                return "48";
            case "tr":
                return "90";
            case "fr":
                return "33";
            case "it":
                return "39";
            case "es":
                return "34";
            case "gb":
                return "44";
            case "us":
                return "1";
            default:
                return "49";
        }
    }

    private static String defaultSmsPackage(Context ctx) {
        try {
            return android.provider.Telephony.Sms.getDefaultSmsPackage(ctx);
        } catch (Exception e) {
            return null;
        }
    }

    private static void copyToClipboard(Context ctx, String text) {
        try {
            ClipboardManager cm = ctx.getSystemService(ClipboardManager.class);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Nachricht", text));
            }
        } catch (Exception ignored) {
            // im Hintergrund evtl. nicht erlaubt
        }
    }

    private static String capitalized(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }
}
