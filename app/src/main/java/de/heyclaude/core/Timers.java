package de.heyclaude.core;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.heyclaude.app.CoreReceiver;
import de.heyclaude.app.MainActivity;

/**
 * Timer (Countdown), Wecker (Uhrzeit) und Erinnerungen. Gespeichert in den Einstellungen, ausgeloest
 * ueber den exakten AlarmManager – klappt auch, wenn das Handy schlaeft oder neu gestartet wurde.
 */
final class Timers {

    private static final String TAG = "HeyClaude";
    static final String ACTION_FIRE = "de.heyclaude.TIMER_FIRE";
    static final String ACTION_STOP = "de.heyclaude.TIMER_STOP";
    static final String ACTION_SNOOZE = "de.heyclaude.TIMER_SNOOZE";
    private static final String KEY = "timers";
    private static final String CH_RING = "heyclaude_ring";
    private static final String CH_REMIND = "heyclaude_remind";
    private static final int NOTIF_BASE = 1000;

    private Timers() {
    }

    // ------------------------------------------------------------------ Anlegen / Auflisten / Loeschen

    /** type: timer, alarm, reminder. at: Zeitpunkt in ms. Liefert Werkzeug-Ergebnis. */
    static synchronized String create(Context ctx, String type, long at, String label) {
        try {
            long now = System.currentTimeMillis();
            if (at <= now) {
                if ("alarm".equals(type) && at > now - 24 * 3600_000L) {
                    at += 24 * 3600_000L; // Wecker-Uhrzeit schon vorbei -> morgen
                } else {
                    return "Fehler: Der Zeitpunkt liegt in der Vergangenheit.";
                }
            }
            JSONArray all = load(ctx);
            int id = nextId(all);
            JSONObject e = new JSONObject();
            e.put("id", id);
            e.put("type", type);
            e.put("at", at);
            e.put("label", label == null ? "" : label.trim());
            all.put(e);
            save(ctx, all);
            schedule(ctx, e);
            return "OK: " + describe(e);
        } catch (Exception ex) {
            Log.e(TAG, "Timer anlegen", ex);
            return "Fehler: " + ex.getMessage();
        }
    }

    static synchronized List<JSONObject> list(Context ctx) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray all = load(ctx);
        for (int i = 0; i < all.length(); i++) {
            JSONObject e = all.optJSONObject(i);
            if (e != null) {
                out.add(e);
            }
        }
        java.util.Collections.sort(out, (x, y) -> Long.compare(x.optLong("at"), y.optLong("at")));
        return out;
    }

    /** Kurzer Text fuer Claude und die App. Leer, wenn nichts laeuft. */
    static String summary(Context ctx) {
        List<JSONObject> l = list(ctx);
        if (l.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JSONObject e : l) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(describe(e));
        }
        return sb.toString();
    }

    /** label leer + all=false: den naechsten loeschen. Liefert Werkzeug-Ergebnis. */
    static synchronized String cancel(Context ctx, String label, boolean all, String type) {
        JSONArray arr = load(ctx);
        JSONArray keep = new JSONArray();
        List<JSONObject> removed = new ArrayList<>();
        String want = label == null ? "" : label.trim().toLowerCase(Locale.ROOT);
        JSONObject next = null;
        for (JSONObject e : list(ctx)) {
            if (type == null || type.isEmpty() || type.equals(e.optString("type"))) {
                next = e;
                break;
            }
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.optJSONObject(i);
            if (e == null) {
                continue;
            }
            boolean typeOk = type == null || type.isEmpty() || type.equals(e.optString("type"));
            boolean match = typeOk && (all
                    || (!want.isEmpty() && e.optString("label").toLowerCase(Locale.ROOT).contains(want))
                    || (want.isEmpty() && next != null && e.optInt("id") == next.optInt("id")));
            if (match) {
                removed.add(e);
                unschedule(ctx, e.optInt("id"));
            } else {
                keep.put(e);
            }
        }
        save(ctx, keep);
        if (removed.isEmpty()) {
            return "Fehler: Kein passender Timer, Wecker oder Erinnerung gefunden.";
        }
        return "OK: gelöscht: " + removed.size();
    }

    // ------------------------------------------------------------------ AlarmManager

    private static PendingIntent firePi(Context ctx, int id) {
        Intent i = new Intent(ctx, CoreReceiver.class).setAction(ACTION_FIRE).putExtra("id", id);
        return PendingIntent.getBroadcast(ctx, 5000 + id, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void schedule(Context ctx, JSONObject e) {
        int id = e.optInt("id");
        long at = e.optLong("at");
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        PendingIntent op = firePi(ctx, id);
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                PendingIntent show = PendingIntent.getActivity(ctx, 6000 + id, new Intent(ctx, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), op);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op);
            }
        } catch (SecurityException se) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op);
        }
    }

    private static void unschedule(Context ctx, int id) {
        try {
            ctx.getSystemService(AlarmManager.class).cancel(firePi(ctx, id));
        } catch (Exception ignored) {
            // egal
        }
    }

    /** Nach Handy-Neustart oder App-Update: alles neu planen, Verpasstes sofort melden. */
    static synchronized void rescheduleAll(Context ctx) {
        long now = System.currentTimeMillis();
        for (JSONObject e : list(ctx)) {
            if (e.optLong("at") <= now) {
                fire(ctx, e.optInt("id"));
            } else {
                schedule(ctx, e);
            }
        }
    }

    // ------------------------------------------------------------------ Ausloesen

    /** Empfaenger-Ereignisse (vom CoreReceiver). */
    static void handle(Context ctx, Intent intent) {
        String a = intent.getAction();
        int id = intent.getIntExtra("id", -1);
        if (ACTION_FIRE.equals(a)) {
            fire(ctx, id);
        } else if (ACTION_STOP.equals(a)) {
            stopRinging(ctx);
        } else if (ACTION_SNOOZE.equals(a)) {
            stopRinging(ctx);
            snooze(ctx, intent.getStringExtra("label"));
        }
    }

    static void fire(Context ctx, int id) {
        JSONObject e = null;
        synchronized (Timers.class) {
            JSONArray arr = load(ctx);
            JSONArray keep = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject x = arr.optJSONObject(i);
                if (x == null) {
                    continue;
                }
                if (x.optInt("id") == id) {
                    e = x;
                } else {
                    keep.put(x);
                }
            }
            save(ctx, keep);
        }
        if (e == null) {
            return;
        }
        String type = e.optString("type");
        String label = e.optString("label");
        if ("reminder".equals(type)) {
            notifyReminder(ctx, id, label);
            Engine.announce("Erinnerung: " + (label.isEmpty() ? "Du wolltest an etwas erinnert werden." : label));
        } else {
            ring(ctx, id, type, label);
        }
    }

    private static void ensureChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CH_RING) == null) {
            NotificationChannel ch = new NotificationChannel(CH_RING, "Timer & Wecker", NotificationManager.IMPORTANCE_HIGH);
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (sound == null) {
                sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }
            ch.setSound(sound, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 600, 400, 600, 400, 600});
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CH_REMIND) == null) {
            NotificationChannel ch = new NotificationChannel(CH_REMIND, "Erinnerungen", NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
    }

    /** Klingelt (wiederholt sich, bis Stopp/Wegwischen) – laeuft ueber das System, auch ohne App. */
    private static void ring(Context ctx, int id, String type, String label) {
        ensureChannels(ctx);
        boolean alarm = "alarm".equals(type);
        String title = alarm ? "⏰ Wecker" : "⏱ Timer abgelaufen";
        String text = label.isEmpty() ? (alarm ? "Aufstehen!" : "Dein Timer ist abgelaufen.") : label;
        Notification.Builder b = new Notification.Builder(ctx, CH_RING)
                .setSmallIcon(Res.drawable(ctx, "ic_mic"))
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setOngoing(true)
                .setTimeoutAfter(alarm ? 10 * 60_000L : 2 * 60_000L)
                .setContentIntent(actionPi(ctx, ACTION_STOP, id, label))
                .setDeleteIntent(actionPi(ctx, ACTION_STOP, id, label))
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(ctx, Res.drawable(ctx, "ic_stop")),
                        "Stopp", actionPi(ctx, ACTION_STOP, id, label)).build());
        if (alarm) {
            b.addAction(new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(ctx, Res.drawable(ctx, "ic_mic")),
                    "Schlummern 9 Min", actionPi(ctx, ACTION_SNOOZE, id, label)).build());
            Prefs.get(ctx).edit().putLong("last_alarm_at", System.currentTimeMillis())
                    .putString("last_alarm_label", label).apply();
        }
        Notification n = b.build();
        n.flags |= Notification.FLAG_INSISTENT; // Ton wiederholt sich bis Stopp
        ctx.getSystemService(NotificationManager.class).notify(NOTIF_BASE + id, n);
    }

    private static void notifyReminder(Context ctx, int id, String label) {
        ensureChannels(ctx);
        Notification n = new Notification.Builder(ctx, CH_REMIND)
                .setSmallIcon(Res.drawable(ctx, "ic_mic"))
                .setContentTitle("🔔 Erinnerung")
                .setContentText(label.isEmpty() ? "Erinnerung" : label)
                .setStyle(new Notification.BigTextStyle().bigText(label))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(ctx, 7000 + id, new Intent(ctx, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
        ctx.getSystemService(NotificationManager.class).notify(NOTIF_BASE + id, n);
    }

    private static PendingIntent actionPi(Context ctx, String action, int id, String label) {
        Intent i = new Intent(ctx, CoreReceiver.class).setAction(action).putExtra("id", id).putExtra("label", label);
        int code = (ACTION_STOP.equals(action) ? 8000 : 9000) + id;
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Klingelt gerade etwas? */
    static boolean isRinging(Context ctx) {
        try {
            for (StatusBarNotification sbn : ctx.getSystemService(NotificationManager.class).getActiveNotifications()) {
                if (sbn.getId() >= NOTIF_BASE && CH_RING.equals(sbn.getNotification().getChannelId())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // egal
        }
        return false;
    }

    /** Stoppt alle klingelnden Timer/Wecker. */
    static void stopRinging(Context ctx) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            for (StatusBarNotification sbn : nm.getActiveNotifications()) {
                if (sbn.getId() >= NOTIF_BASE && CH_RING.equals(sbn.getNotification().getChannelId())) {
                    nm.cancel(sbn.getId());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Stopp", e);
        }
    }

    /** Schlummern: Wecker in 9 Minuten nochmal. */
    static String snooze(Context ctx, String label) {
        return create(ctx, "alarm", System.currentTimeMillis() + 9 * 60_000L,
                label == null || label.isEmpty() ? "Schlummern" : label);
    }

    /** Hat in den letzten 15 Minuten ein Wecker geklingelt? (fuer "Schlummern" per Stimme) */
    static boolean alarmRecentlyRang(Context ctx) {
        return System.currentTimeMillis() - Prefs.get(ctx).getLong("last_alarm_at", 0) < 15 * 60_000L;
    }

    static String lastAlarmLabel(Context ctx) {
        return Prefs.get(ctx).getString("last_alarm_label", "");
    }

    // ------------------------------------------------------------------ Hilfen

    static String describe(JSONObject e) {
        String type = e.optString("type");
        String label = e.optString("label");
        long at = e.optLong("at");
        String name = "alarm".equals(type) ? "Wecker" : "reminder".equals(type) ? "Erinnerung" : "Timer";
        if (!label.isEmpty()) {
            name += " „" + label + "“";
        }
        return name + " um " + formatTime(at) + " (" + remaining(at) + ")";
    }

    static String formatTime(long at) {
        Calendar c = Calendar.getInstance();
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(at);
        String time = new SimpleDateFormat("HH:mm", Locale.GERMAN).format(new Date(at));
        if (c.get(Calendar.YEAR) == t.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)) {
            return time + " Uhr";
        }
        c.add(Calendar.DAY_OF_YEAR, 1);
        if (c.get(Calendar.YEAR) == t.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)) {
            return "morgen " + time + " Uhr";
        }
        return new SimpleDateFormat("EEEE, d. MMMM, HH:mm", Locale.GERMAN).format(new Date(at)) + " Uhr";
    }

    static String remaining(long at) {
        long sec = Math.max(0, (at - System.currentTimeMillis()) / 1000);
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) {
            return "noch " + h + " Std. " + m + " Min.";
        }
        if (m > 0) {
            return "noch " + m + " Min." + (m < 5 && s > 0 ? " " + s + " Sek." : "");
        }
        return "noch " + s + " Sek.";
    }

    /** "2026-11-19T07:00", "07:00" oder "7:30" -> ms (lokale Zeit). -1 bei Fehler. */
    static long parseAt(String at) {
        if (at == null) {
            return -1;
        }
        String s = at.trim().replace(' ', 'T');
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm"};
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.ROOT);
                f.setLenient(false);
                Date d = f.parse(s.length() > 19 ? s.substring(0, 19) : s);
                if (d != null) {
                    return d.getTime();
                }
            } catch (Exception ignored) {
                // naechstes Format
            }
        }
        if (s.matches("\\d{1,2}:\\d{2}")) {
            String[] hm = s.split(":");
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
            c.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() <= System.currentTimeMillis()) {
                c.add(Calendar.DAY_OF_YEAR, 1);
            }
            return c.getTimeInMillis();
        }
        return -1;
    }

    private static JSONArray load(Context ctx) {
        try {
            return new JSONArray(Prefs.get(ctx).getString(KEY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void save(Context ctx, JSONArray arr) {
        Prefs.get(ctx).edit().putString(KEY, arr.toString()).apply();
    }

    /** Kleinste freie Nummer 1..900. */
    private static int nextId(JSONArray all) {
        java.util.HashSet<Integer> used = new java.util.HashSet<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject e = all.optJSONObject(i);
            if (e != null) {
                used.add(e.optInt("id"));
            }
        }
        for (int id = 1; id < 900; id++) {
            if (!used.contains(id)) {
                return id;
            }
        }
        return 900;
    }
}
