package de.heyclaude.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Minimaler Client fuer die Claude Messages API (ohne externe Bibliotheken). */
public final class ClaudeClient {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    /** Fehler, den die API zurueckgegeben hat (HTTP-Status + Meldung). */
    public static final class ApiException extends Exception {
        public final int status;

        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private ClaudeClient() {
    }

    /**
     * Stellt eine Frage. history enthaelt abwechselnd {"user", text} und {"assistant", text},
     * der letzte Eintrag ist die aktuelle Frage des Nutzers.
     */
    public static String ask(String apiKey, String model, boolean webSearch, boolean lowEffort,
                             String systemPrompt, List<String[]> history)
            throws IOException, ApiException, JSONException {
        try {
            return askOnce(apiKey, model, webSearch, lowEffort, systemPrompt, history);
        } catch (ApiException e) {
            // Falls ein Zusatz (Websuche / Effort) vom Konto oder Modell nicht unterstuetzt wird:
            // einmal ganz schlicht nochmal versuchen.
            if (e.status == 400 && (webSearch || lowEffort) && !isCreditProblem(e.getMessage())) {
                return askOnce(apiKey, model, false, false, systemPrompt, history);
            }
            throw e;
        }
    }

    static boolean isCreditProblem(String msg) {
        return msg != null && msg.toLowerCase(Locale.ROOT).contains("credit");
    }

    private static String askOnce(String apiKey, String model, boolean webSearch, boolean lowEffort,
                                  String systemPrompt, List<String[]> history)
            throws IOException, ApiException, JSONException {
        JSONArray messages = new JSONArray();
        for (String[] turn : history) {
            JSONObject m = new JSONObject();
            m.put("role", turn[0]);
            m.put("content", turn[1]);
            messages.put(m);
        }

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", 2000);
        body.put("system", systemPrompt);
        body.put("messages", messages);
        if (lowEffort) {
            body.put("output_config", new JSONObject().put("effort", "low"));
        }
        if (webSearch) {
            JSONObject tool = new JSONObject();
            tool.put("type", "web_search_20250305");
            tool.put("name", "web_search");
            tool.put("max_uses", 3);
            JSONObject loc = new JSONObject();
            loc.put("type", "approximate");
            loc.put("timezone", TimeZone.getDefault().getID());
            String country = Locale.getDefault().getCountry();
            if (country != null && country.length() == 2) {
                loc.put("country", country);
            }
            tool.put("user_location", loc);
            body.put("tools", new JSONArray().put(tool));
        }

        StringBuilder answer = new StringBuilder();
        // Lange Suchen koennen mit stop_reason "pause_turn" unterbrochen werden -> fortsetzen.
        for (int round = 0; round < 4; round++) {
            JSONObject resp = post(apiKey, body);
            JSONArray content = resp.optJSONArray("content");
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    JSONObject block = content.optJSONObject(i);
                    if (block != null && "text".equals(block.optString("type"))) {
                        answer.append(block.optString("text"));
                    }
                }
            }
            if ("pause_turn".equals(resp.optString("stop_reason")) && content != null) {
                JSONObject assistant = new JSONObject();
                assistant.put("role", "assistant");
                assistant.put("content", content);
                messages.put(assistant);
                body.put("messages", messages);
                continue;
            }
            break;
        }
        String result = answer.toString().trim();
        if (result.isEmpty()) {
            result = "Darauf habe ich leider keine Antwort bekommen.";
        }
        return result;
    }

    private static JSONObject post(String apiKey, JSONObject body)
            throws IOException, ApiException, JSONException {
        HttpURLConnection con = (HttpURLConnection) new URL(API_URL).openConnection();
        try {
            con.setRequestMethod("POST");
            con.setConnectTimeout(15000);
            con.setReadTimeout(90000);
            con.setDoOutput(true);
            con.setRequestProperty("content-type", "application/json");
            con.setRequestProperty("x-api-key", apiKey.trim());
            con.setRequestProperty("anthropic-version", API_VERSION);

            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream os = con.getOutputStream();
            try {
                os.write(data);
            } finally {
                os.close();
            }

            int code = con.getResponseCode();
            InputStream is = code >= 200 && code < 300 ? con.getInputStream() : con.getErrorStream();
            String text = is == null ? "" : readAll(is);
            if (code < 200 || code >= 300) {
                String msg = text;
                try {
                    JSONObject err = new JSONObject(text).optJSONObject("error");
                    if (err != null) {
                        msg = err.optString("message", text);
                    }
                } catch (JSONException ignored) {
                    // Rohtext verwenden
                }
                throw new ApiException(code, msg);
            }
            return new JSONObject(text);
        } finally {
            con.disconnect();
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
