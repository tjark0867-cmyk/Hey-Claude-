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

    /** Werkzeuge, die die App fuer Claude ausfuehrt (z.B. Handy-Steuerung). */
    public interface ToolHandler {
        JSONArray definitions() throws JSONException;

        /** Ergebnis als Text; beginnt mit "Fehler", wenn es nicht geklappt hat. */
        String run(String name, JSONObject input);
    }

    /**
     * Stellt eine Frage. history enthaelt abwechselnd {"user", text} und {"assistant", text},
     * der letzte Eintrag ist die aktuelle Frage des Nutzers.
     */
    public static String ask(String apiKey, String model, boolean webSearch, boolean lowEffort,
                             String systemPrompt, List<String[]> history, LocationHelper.Place place,
                             ToolHandler tools)
            throws IOException, ApiException, JSONException {
        try {
            return askOnce(apiKey, model, webSearch, lowEffort, systemPrompt, history, place, tools);
        } catch (ApiException e) {
            // Falls ein Zusatz (Websuche / Effort / Werkzeuge) nicht unterstuetzt wird:
            // einmal ganz schlicht nochmal versuchen.
            if (e.status == 400 && !isCreditProblem(e.getMessage())) {
                return askOnce(apiKey, model, false, false, systemPrompt, history, null, null);
            }
            throw e;
        }
    }

    static boolean isCreditProblem(String msg) {
        return msg != null && msg.toLowerCase(Locale.ROOT).contains("credit");
    }

    private static String askOnce(String apiKey, String model, boolean webSearch, boolean lowEffort,
                                  String systemPrompt, List<String[]> history, LocationHelper.Place place,
                                  ToolHandler handler)
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
        JSONArray tools = new JSONArray();
        if (webSearch) {
            JSONObject tool = new JSONObject();
            tool.put("type", "web_search_20250305");
            tool.put("name", "web_search");
            tool.put("max_uses", 3);
            JSONObject loc = new JSONObject();
            loc.put("type", "approximate");
            loc.put("timezone", TimeZone.getDefault().getID());
            String country = place != null && place.countryCode != null
                    ? place.countryCode : Locale.getDefault().getCountry();
            if (country != null && country.length() == 2) {
                loc.put("country", country.toUpperCase(Locale.ROOT));
            }
            if (place != null && place.city != null && !place.city.isEmpty()) {
                loc.put("city", place.city);
            }
            if (place != null && place.region != null && !place.region.isEmpty()) {
                loc.put("region", place.region);
            }
            tool.put("user_location", loc);
            tools.put(tool);
        }
        if (handler != null) {
            JSONArray defs = handler.definitions();
            for (int i = 0; i < defs.length(); i++) {
                tools.put(defs.optJSONObject(i));
            }
        }
        if (tools.length() > 0) {
            body.put("tools", tools);
        }

        StringBuilder answer = new StringBuilder();
        String beforeTools = "";
        boolean usedTools = false;
        // pause_turn: lange Suche fortsetzen. tool_use: Werkzeug ausfuehren und Ergebnis zurueckgeben.
        for (int round = 0; round < 6; round++) {
            JSONObject resp = post(apiKey, body);
            JSONArray content = resp.optJSONArray("content");
            String stop = resp.optString("stop_reason");
            JSONArray results = new JSONArray();
            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    JSONObject block = content.optJSONObject(i);
                    if (block == null) {
                        continue;
                    }
                    String type = block.optString("type");
                    if ("text".equals(type)) {
                        answer.append(block.optString("text"));
                    } else if ("tool_use".equals(type) && handler != null) {
                        JSONObject input = block.optJSONObject("input");
                        String out = handler.run(block.optString("name"), input != null ? input : new JSONObject());
                        JSONObject r = new JSONObject();
                        r.put("type", "tool_result");
                        r.put("tool_use_id", block.optString("id"));
                        r.put("content", out);
                        if (out.startsWith("Fehler")) {
                            r.put("is_error", true);
                        }
                        results.put(r);
                    }
                }
            }
            boolean toolStop = "tool_use".equals(stop);
            if ((toolStop || "pause_turn".equals(stop)) && content != null) {
                if (toolStop && results.length() == 0) {
                    break;
                }
                JSONObject assistant = new JSONObject();
                assistant.put("role", "assistant");
                assistant.put("content", content);
                messages.put(assistant);
                if (toolStop) {
                    usedTools = true;
                    if (answer.length() > 0) {
                        beforeTools = answer.toString();
                        answer.setLength(0);
                    }
                    JSONObject user = new JSONObject();
                    user.put("role", "user");
                    user.put("content", results);
                    messages.put(user);
                }
                body.put("messages", messages);
                continue;
            }
            break;
        }
        String result = answer.toString().trim();
        if (result.isEmpty()) {
            result = beforeTools.trim();
        }
        if (result.isEmpty()) {
            result = usedTools ? "Erledigt." : "Darauf habe ich leider keine Antwort bekommen.";
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
