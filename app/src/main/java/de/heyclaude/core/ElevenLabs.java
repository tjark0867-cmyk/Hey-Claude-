package de.heyclaude.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Echte, natuerliche Stimme ueber die ElevenLabs-API. */
final class ElevenLabs {

    static final String DEFAULT_VOICE_ID = "JBFqnCBsd6RMkjVDRZzb";
    static final String DEFAULT_VOICE_NAME = "George";
    static final String MODEL_QUALITY = "eleven_multilingual_v2";
    static final String MODEL_FAST = "eleven_flash_v2_5";

    private static final String BASE = "https://api.elevenlabs.io";

    static final class ApiException extends Exception {
        final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private ElevenLabs() {
    }

    /** Erzeugt eine MP3-Datei mit dem gesprochenen Text. */
    static File synthesize(Context ctx, String apiKey, String voiceId, boolean fast, String text)
            throws IOException, ApiException, JSONException {
        return synthesize(ctx, apiKey, voiceId, fast, text, null);
    }

    /** previousText: der zuvor gesprochene Satz – sorgt fuer natuerliche Betonung beim satzweisen Sprechen. */
    static File synthesize(Context ctx, String apiKey, String voiceId, boolean fast, String text, String previousText)
            throws IOException, ApiException, JSONException {
        String id = voiceId == null || voiceId.trim().isEmpty() ? DEFAULT_VOICE_ID : voiceId.trim();
        URL url = new URL(BASE + "/v1/text-to-speech/" + URLEncoder.encode(id, "UTF-8")
                + "?output_format=mp3_44100_128");
        JSONObject body = new JSONObject();
        body.put("text", text);
        body.put("model_id", fast ? MODEL_FAST : MODEL_QUALITY);
        if (previousText != null && !previousText.isEmpty()) {
            body.put("previous_text", previousText);
        }
        if (fast) {
            String lang = Locale.getDefault().getLanguage();
            if (lang != null && lang.length() == 2) {
                body.put("language_code", lang);
            }
        }

        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        try {
            con.setRequestMethod("POST");
            con.setConnectTimeout(15000);
            con.setReadTimeout(60000);
            con.setDoOutput(true);
            con.setRequestProperty("xi-api-key", apiKey.trim());
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Accept", "audio/mpeg");
            OutputStream os = con.getOutputStream();
            try {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                os.close();
            }
            int code = con.getResponseCode();
            if (code < 200 || code >= 300) {
                InputStream es = con.getErrorStream();
                throw new ApiException(code, errorMessage(es == null ? "" : readAll(es)));
            }
            cleanup(ctx);
            File out = new File(ctx.getCacheDir(), "voice-" + System.currentTimeMillis() + ".mp3");
            InputStream in = con.getInputStream();
            try {
                OutputStream fos = new FileOutputStream(out);
                try {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                    }
                } finally {
                    fos.close();
                }
            } finally {
                in.close();
            }
            if (out.length() < 100) {
                throw new IOException("Leere Audiodatei erhalten");
            }
            return out;
        } finally {
            con.disconnect();
        }
    }

    /** Liefert die Stimmen des Kontos als {voice_id, Anzeigename}. */
    static List<String[]> listVoices(String apiKey) throws IOException, ApiException, JSONException {
        String text;
        try {
            text = get(BASE + "/v2/voices?page_size=100", apiKey);
        } catch (ApiException e) {
            if (e.status == 401 || e.status == 403) {
                throw e;
            }
            text = get(BASE + "/v1/voices", apiKey);
        }
        JSONArray voices = new JSONObject(text).optJSONArray("voices");
        List<String[]> result = new ArrayList<>();
        if (voices == null) {
            return result;
        }
        for (int i = 0; i < voices.length(); i++) {
            JSONObject v = voices.optJSONObject(i);
            if (v == null) {
                continue;
            }
            String id = v.optString("voice_id", "");
            String name = v.optString("name", id);
            if (id.isEmpty()) {
                continue;
            }
            StringBuilder label = new StringBuilder(name);
            JSONObject labels = v.optJSONObject("labels");
            if (labels != null) {
                String gender = labels.optString("gender", "");
                String accent = labels.optString("accent", "");
                String extra = (gender + " " + accent).trim();
                if (!extra.isEmpty()) {
                    label.append(" (").append(extra).append(")");
                }
            }
            result.add(new String[]{id, name, label.toString()});
        }
        return result;
    }

    private static String get(String urlStr, String apiKey) throws IOException, ApiException {
        HttpURLConnection con = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setRequestProperty("xi-api-key", apiKey.trim());
            int code = con.getResponseCode();
            if (code < 200 || code >= 300) {
                InputStream es = con.getErrorStream();
                throw new ApiException(code, errorMessage(es == null ? "" : readAll(es)));
            }
            return readAll(con.getInputStream());
        } finally {
            con.disconnect();
        }
    }

    static String errorMessage(String body) {
        try {
            Object detail = new JSONObject(body).opt("detail");
            if (detail instanceof JSONObject) {
                JSONObject d = (JSONObject) detail;
                return d.optString("message", d.optString("status", body));
            }
            if (detail instanceof JSONArray) {
                JSONObject first = ((JSONArray) detail).optJSONObject(0);
                if (first != null) {
                    return first.optString("msg", body);
                }
            }
            if (detail != null) {
                return detail.toString();
            }
        } catch (JSONException ignored) {
            // Rohtext
        }
        return body;
    }

    /** Deutscher Hinweis zu einem Fehler. */
    static String explain(ApiException e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (e.status == 401 || m.contains("invalid_api_key") || m.contains("invalid api key")) {
            return "ElevenLabs-Schlüssel ungültig";
        }
        if (m.contains("quota") || m.contains("credits") || m.contains("limit")) {
            return "ElevenLabs-Guthaben aufgebraucht";
        }
        if (m.contains("permission") || e.status == 403) {
            return "ElevenLabs-Schlüssel fehlt die Berechtigung (Text to Speech / Voices)";
        }
        if (e.status == 404 || m.contains("voice")) {
            return "ElevenLabs-Stimme nicht gefunden – bitte neu auswählen";
        }
        return "ElevenLabs-Fehler " + e.status + ": " + e.getMessage();
    }

    private static void cleanup(Context ctx) {
        File[] old = ctx.getCacheDir().listFiles();
        if (old == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 60_000L;
        for (File f : old) {
            if (f.getName().startsWith("voice-") && f.lastModified() < cutoff) {
                f.delete();
            }
        }
    }

    private static String readAll(InputStream is) throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            is.close();
        }
    }
}
