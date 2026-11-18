package de.heyclaude.core;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** Steuert das Handy: Musik, Lautstaerke, Klingelmodus, Taschenlampe. Liefert Werkzeuge fuer Claude. */
final class DeviceControl implements ClaudeClient.ToolHandler {

    private static final String TAG = "HeyClaude";

    private final Context ctx;
    private final Handler main;
    private final AudioManager audio;
    private CameraManager cameras;
    private String torchCameraId;
    private volatile boolean torchOn;
    private CameraManager.TorchCallback torchCallback;

    DeviceControl(Context ctx, Handler main) {
        this.ctx = ctx.getApplicationContext();
        this.main = main;
        this.audio = this.ctx.getSystemService(AudioManager.class);
        try {
            cameras = this.ctx.getSystemService(CameraManager.class);
            if (cameras != null) {
                for (String id : cameras.getCameraIdList()) {
                    CameraCharacteristics c = cameras.getCameraCharacteristics(id);
                    Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                    if (Boolean.TRUE.equals(flash)) {
                        if (torchCameraId == null || (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK)) {
                            torchCameraId = id;
                        }
                    }
                }
                if (torchCameraId != null) {
                    final String id = torchCameraId;
                    torchCallback = new CameraManager.TorchCallback() {
                        @Override
                        public void onTorchModeChanged(String cameraId, boolean enabled) {
                            if (id.equals(cameraId)) {
                                torchOn = enabled;
                            }
                        }
                    };
                    cameras.registerTorchCallback(torchCallback, main);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Kamera/Taschenlampe", e);
        }
    }

    void shutdown() {
        try {
            if (cameras != null && torchCallback != null) {
                cameras.unregisterTorchCallback(torchCallback);
            }
        } catch (Exception ignored) {
            // egal
        }
    }

    boolean isMusicActive() {
        return audio != null && audio.isMusicActive();
    }

    // ------------------------------------------------------------------ Werkzeuge fuer Claude

    @Override
    public JSONArray definitions() throws JSONException {
        return toolDefinitions();
    }

    static JSONArray toolDefinitions() throws JSONException {
        JSONArray tools = new JSONArray();
        tools.put(tool("media_control",
                "Steuert die Musik- oder Medienwiedergabe auf dem Handy (Spotify, YouTube Music, Podcasts usw.): abspielen, pausieren, nächster oder vorheriger Titel.",
                new JSONObject().put("action", new JSONObject()
                        .put("type", "string")
                        .put("enum", new JSONArray().put("play").put("pause").put("play_pause")
                                .put("next").put("previous").put("stop"))),
                new JSONArray().put("action")));
        tools.put(tool("set_volume",
                "Ändert die Lautstärke des Handys. stream: media (Musik/Videos, Standard), ring (Klingelton), alarm (Wecker), notification. "
                        + "action: set (auf percent Prozent setzen), up/down (um percent Prozent, Standard 20), mute, unmute.",
                new JSONObject()
                        .put("stream", new JSONObject().put("type", "string")
                                .put("enum", new JSONArray().put("media").put("ring").put("alarm").put("notification")))
                        .put("action", new JSONObject().put("type", "string")
                                .put("enum", new JSONArray().put("set").put("up").put("down").put("mute").put("unmute")))
                        .put("percent", new JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 100)),
                new JSONArray().put("action")));
        tools.put(tool("ringer_mode",
                "Stellt den Klingelmodus des Handys: normal (mit Ton), vibrate (nur Vibration) oder silent (lautlos).",
                new JSONObject().put("mode", new JSONObject().put("type", "string")
                        .put("enum", new JSONArray().put("normal").put("vibrate").put("silent"))),
                new JSONArray().put("mode")));
        tools.put(tool("play_music",
                "Startet ein Lied, Album, Künstler, Video oder eine Playlist in einer Musik-App auf dem Handy. "
                        + "app: youtube (Standard, wenn der Nutzer nichts anderes sagt), youtube_music oder spotify. "
                        + "query: wonach gesucht werden soll, möglichst genau (z.B. \"Queen Bohemian Rhapsody\").",
                new JSONObject()
                        .put("query", new JSONObject().put("type", "string"))
                        .put("app", new JSONObject().put("type", "string")
                                .put("enum", new JSONArray().put("youtube").put("youtube_music").put("spotify"))),
                new JSONArray().put("query")));
        tools.put(tool("open_app",
                "Öffnet eine installierte App auf dem Handy, z.B. WhatsApp, Kamera, Google Maps, Instagram.",
                new JSONObject().put("name", new JSONObject().put("type", "string")
                        .put("description", "Name der App, wie er auf dem Handy heißt")),
                new JSONArray().put("name")));
        tools.put(tool("send_message",
                "Schreibt und sendet eine Nachricht an eine Person – in jeder Chat-App, auch an Leute, die nicht als "
                        + "Kontakt gespeichert sind. app: whatsapp (Standard), instagram, sms, telegram oder der Name jeder "
                        + "anderen App auf dem Handy (z.B. purp, snapchat, discord, tiktok). "
                        + "to: Name der Person so wie er in der App angezeigt wird bzw. wie der Nutzer ihn sagt (z.B. \"Emily\"). "
                        + "text: die Nachricht genau so, wie der Nutzer sie haben will.",
                new JSONObject()
                        .put("app", new JSONObject().put("type", "string"))
                        .put("to", new JSONObject().put("type", "string"))
                        .put("text", new JSONObject().put("type", "string")),
                new JSONArray().put("to").put("text")));
        tools.put(tool("create_timer",
                "Stellt einen Timer (Countdown, klingelt), einen Wecker (klingelt zu einer Uhrzeit) oder eine Erinnerung "
                        + "(Benachrichtigung und vorlesen). type: timer, alarm oder reminder. Zeit entweder in_seconds "
                        + "(ab jetzt) ODER at als lokale Zeit im Format YYYY-MM-DDTHH:MM (aktuelles Datum steht im Systemprompt). "
                        + "label: kurzer Name bzw. bei Erinnerungen der Text, an den erinnert werden soll.",
                new JSONObject()
                        .put("type", new JSONObject().put("type", "string")
                                .put("enum", new JSONArray().put("timer").put("alarm").put("reminder")))
                        .put("in_seconds", new JSONObject().put("type", "integer").put("minimum", 1))
                        .put("at", new JSONObject().put("type", "string"))
                        .put("label", new JSONObject().put("type", "string")),
                new JSONArray().put("type")));
        tools.put(tool("list_timers",
                "Listet alle laufenden Timer, Wecker und Erinnerungen mit Restzeit.",
                new JSONObject(), new JSONArray()));
        tools.put(tool("cancel_timer",
                "Löscht Timer, Wecker oder Erinnerungen. label: Teil des Namens (leer = den nächsten). "
                        + "all=true löscht alle (optional nur eines type). Stoppt auch etwas, das gerade klingelt.",
                new JSONObject()
                        .put("label", new JSONObject().put("type", "string"))
                        .put("all", new JSONObject().put("type", "boolean"))
                        .put("type", new JSONObject().put("type", "string")
                                .put("enum", new JSONArray().put("timer").put("alarm").put("reminder"))),
                new JSONArray()));
        tools.put(tool("flashlight",
                "Schaltet die Taschenlampe des Handys ein (on=true) oder aus (on=false).",
                new JSONObject().put("on", new JSONObject().put("type", "boolean")),
                new JSONArray().put("on")));
        return tools;
    }

    private static JSONObject tool(String name, String description, JSONObject props, JSONArray required)
            throws JSONException {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", required);
        JSONObject t = new JSONObject();
        t.put("name", name);
        t.put("description", description);
        t.put("input_schema", schema);
        return t;
    }

    /** Fuehrt ein Werkzeug aus. Ergebnis beginnt mit "Fehler" wenn es nicht geklappt hat. */
    @Override
    public String run(String name, JSONObject input) {
        try {
            switch (name) {
                case "media_control":
                    return media(input.optString("action", "play_pause"));
                case "set_volume":
                    return volume(input.optString("stream", "media"), input.optString("action", "set"),
                            input.has("percent") ? input.optInt("percent", -1) : -1);
                case "ringer_mode":
                    return ringer(input.optString("mode", "normal"));
                case "flashlight":
                    return flashlight(input.optBoolean("on", true));
                case "play_music":
                    return playMusic(input.optString("app", "youtube"), input.optString("query", ""));
                case "open_app":
                    return openApp(input.optString("name", ""));
                case "create_timer": {
                    String type = input.optString("type", "timer");
                    long at;
                    if (input.has("in_seconds")) {
                        at = System.currentTimeMillis() + input.optLong("in_seconds", 60) * 1000L;
                    } else if (input.has("at")) {
                        at = Timers.parseAt(input.optString("at"));
                        if (at < 0) {
                            return "Fehler: Zeit nicht verstanden (bitte YYYY-MM-DDTHH:MM).";
                        }
                    } else {
                        return "Fehler: in_seconds oder at fehlt.";
                    }
                    return Timers.create(ctx, type, at, input.optString("label", ""));
                }
                case "list_timers": {
                    String sum = Timers.summary(ctx);
                    return sum.isEmpty() ? "Keine Timer, Wecker oder Erinnerungen." : sum;
                }
                case "cancel_timer": {
                    Timers.stopRinging(ctx);
                    return Timers.cancel(ctx, input.optString("label", ""), input.optBoolean("all", false),
                            input.optString("type", ""));
                }
                case "send_message":
                    return Messenger.send(ctx, main, input.optString("app", "whatsapp"),
                            input.optString("to", ""), input.optString("text", ""));
                default:
                    return "Fehler: unbekanntes Werkzeug " + name;
            }
        } catch (SecurityException e) {
            return "Fehler: Dafür fehlt der App eine Berechtigung (" + e.getMessage() + ")";
        } catch (Exception e) {
            Log.w(TAG, "Werkzeug " + name, e);
            return "Fehler: " + e.getMessage();
        }
    }

    private String media(String action) {
        int code;
        String done;
        switch (action) {
            case "play":
                code = KeyEvent.KEYCODE_MEDIA_PLAY;
                done = "Wiedergabe gestartet";
                break;
            case "pause":
                code = KeyEvent.KEYCODE_MEDIA_PAUSE;
                done = "Wiedergabe pausiert";
                break;
            case "next":
                code = KeyEvent.KEYCODE_MEDIA_NEXT;
                done = "Nächster Titel";
                break;
            case "previous":
                code = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                done = "Vorheriger Titel";
                break;
            case "stop":
                code = KeyEvent.KEYCODE_MEDIA_STOP;
                done = "Wiedergabe gestoppt";
                break;
            default:
                code = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
                done = "Wiedergabe umgeschaltet";
                break;
        }
        sendMediaKey(code);
        return "OK: " + done;
    }

    void sendMediaKey(int code) {
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    private static int streamOf(String s) {
        switch (s) {
            case "ring":
                return AudioManager.STREAM_RING;
            case "alarm":
                return AudioManager.STREAM_ALARM;
            case "notification":
                return AudioManager.STREAM_NOTIFICATION;
            default:
                return AudioManager.STREAM_MUSIC;
        }
    }

    private String volume(String streamName, String action, int percent) {
        int stream = streamOf(streamName);
        int max = audio.getStreamMaxVolume(stream);
        int cur = audio.getStreamVolume(stream);
        int flags = AudioManager.FLAG_SHOW_UI;
        switch (action) {
            case "mute":
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags);
                return "OK: stumm geschaltet";
            case "unmute":
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, flags);
                return "OK: Ton wieder an (" + pct(audio.getStreamVolume(stream), max) + " %)";
            case "up":
            case "down": {
                int step = Math.max(1, Math.round(max * (percent > 0 ? percent : 20) / 100f));
                int target = "up".equals(action) ? cur + step : cur - step;
                target = Math.max(0, Math.min(max, target));
                if ("up".equals(action) && audio.isStreamMute(stream)) {
                    audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0);
                }
                audio.setStreamVolume(stream, target, flags);
                return "OK: Lautstärke jetzt " + pct(audio.getStreamVolume(stream), max) + " %";
            }
            default: {
                int p = percent < 0 ? 50 : Math.min(100, percent);
                int target = Math.round(max * p / 100f);
                if (target > 0 && audio.isStreamMute(stream)) {
                    audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0);
                }
                audio.setStreamVolume(stream, target, flags);
                return "OK: Lautstärke jetzt " + pct(audio.getStreamVolume(stream), max) + " %";
            }
        }
    }

    private static int pct(int v, int max) {
        return max <= 0 ? 0 : Math.round(v * 100f / max);
    }

    private String ringer(String mode) {
        int m = "silent".equals(mode) ? AudioManager.RINGER_MODE_SILENT
                : "vibrate".equals(mode) ? AudioManager.RINGER_MODE_VIBRATE
                : AudioManager.RINGER_MODE_NORMAL;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        boolean dndAccess = nm != null && nm.isNotificationPolicyAccessGranted();
        if (m == AudioManager.RINGER_MODE_SILENT && !dndAccess) {
            // Ohne "Nicht stoeren"-Zugriff geht lautlos nicht – Vibration ist das Naechstbeste
            try {
                audio.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                return "Teilweise: nur auf Vibration gestellt. Für komplett lautlos in der App „Lautlos-Modus erlauben“ antippen.";
            } catch (SecurityException e) {
                return "Fehler: Für lautlos in der App „Lautlos-Modus erlauben“ antippen.";
            }
        }
        try {
            audio.setRingerMode(m);
        } catch (SecurityException e) {
            return "Fehler: Dafür in der App „Lautlos-Modus erlauben“ antippen.";
        }
        return "OK: Klingelmodus " + mode;
    }

    private String flashlight(boolean on) throws Exception {
        if (cameras == null || torchCameraId == null) {
            return "Fehler: Dieses Handy hat keine Taschenlampe, die ich steuern kann.";
        }
        cameras.setTorchMode(torchCameraId, on);
        torchOn = on;
        return on ? "OK: Taschenlampe an" : "OK: Taschenlampe aus";
    }

    private String playMusic(String app, String query) {
        if (query.trim().isEmpty()) {
            return "Fehler: Es fehlt, was gespielt werden soll.";
        }
        String[] label = new String[1];
        Intent i = Launcher.musicIntent(ctx, app, query, label);
        Launcher.launch(ctx, main, i, label[0]);
        return "OK";
    }

    private String openApp(String name) {
        String[] found = Launcher.findApp(ctx, name);
        if (found == null) {
            return "Fehler: Keine App namens „" + name + "“ gefunden.";
        }
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(found[0]);
        if (i == null) {
            return "Fehler: " + found[1] + " lässt sich nicht öffnen.";
        }
        Launcher.launch(ctx, main, i, found[1]);
        return "OK";
    }

    /** Kurzer Handy-Status fuer Claude (Akku, Lautstaerke, ...). */
    String statusLine() {
        StringBuilder sb = new StringBuilder();
        try {
            BatteryManager bm = ctx.getSystemService(BatteryManager.class);
            if (bm != null) {
                int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if (level > 0) {
                    sb.append("Akku ").append(level).append(" %");
                    sb.append(bm.isCharging() ? " (lädt)" : " (lädt nicht)");
                    sb.append(", ");
                }
            }
        } catch (Exception ignored) {
            // egal
        }
        try {
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            sb.append("Medienlautstärke ").append(pct(audio.getStreamVolume(AudioManager.STREAM_MUSIC), max)).append(" %, ");
            sb.append("Musik läuft gerade ").append(audio.isMusicActive() ? "ja" : "nein").append(", ");
            int rm = audio.getRingerMode();
            sb.append("Klingelmodus ").append(rm == AudioManager.RINGER_MODE_SILENT ? "lautlos"
                    : rm == AudioManager.RINGER_MODE_VIBRATE ? "vibrieren" : "normal").append(", ");
        } catch (Exception ignored) {
            // egal
        }
        if (torchCameraId != null) {
            sb.append("Taschenlampe ").append(torchOn ? "an" : "aus");
        }
        String timers = Timers.summary(ctx);
        sb.append(timers.isEmpty() ? ", keine Timer/Wecker" : ", aktiv: " + timers);
        String s = sb.toString().trim();
        if (s.endsWith(",")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.toLowerCase(Locale.ROOT).isEmpty() ? "" : s;
    }
}
