package de.heyclaude.core;

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

    /** Bekommt den Antworttext stueckweise, waehrend Claude noch schreibt (fuer schnelles Sprechen). */
    public interface TextListener {
        void onText(String delta);
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
        return ask(apiKey, model, webSearch, lowEffort, systemPrompt, history, place, tools, null);
    }

    /** Wie oben; mit listener wird gestreamt (Text kommt stueckweise, sobald Claude ihn schreibt). */
    public static String ask(String apiKey, String model, boolean webSearch, boolean lowEffort,
                             String systemPrompt, List<String[]> history, LocationHelper.Place place,
                             ToolHandler tools, TextListener listener)
            throws IOException, ApiException, JSONException {
        try {
            return askOnce(apiKey, model, webSearch, lowEffort, systemPrompt, history, place, tools, listener);
        } catch (ApiException e) {
            // Falls ein Zusatz (Websuche / Effort / Werkzeuge) nicht unterstuetzt wird:
            // einmal ganz schlicht nochmal versuchen.
            if (e.status == 400 && !isCreditProblem(e.getMessage())) {
                return askOnce(apiKey, model, false, false, systemPrompt, history, null, null, listener);
            }
            throw e;
        }
    }

    static boolean isCreditProblem(String msg) {
        return msg != null && msg.toLowerCase(Locale.ROOT).contains("credit");
    }

    private static String askOnce(String apiKey, String model, boolean webSearch, boolean lowEffort,
                                  String systemPrompt, List<String[]> history, LocationHelper.Place place,
                                  ToolHandler handler, TextListener listener)
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
            JSONObject resp = listener != null ? postStream(apiKey, body, listener) : post(apiKey, body);
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

    /**
     * Wie post, aber mit "stream": true. Liest die Server-Sent-Events, gibt Text sofort an den listener
     * weiter und baut daraus dieselbe Antwort-Struktur wie ohne Streaming (fuer Werkzeuge und Websuche).
     */
    private static JSONObject postStream(String apiKey, JSONObject body, TextListener listener)
            throws IOException, ApiException, JSONException {
        body.put("stream", true);
        HttpURLConnection con = (HttpURLConnection) new URL(API_URL).openConnection();
        try {
            con.setRequestMethod("POST");
            con.setConnectTimeout(15000);
            con.setReadTimeout(90000);
            con.setDoOutput(true);
            con.setRequestProperty("content-type", "application/json");
            con.setRequestProperty("accept", "text/event-stream");
            con.setRequestProperty("x-api-key", apiKey.trim());
            con.setRequestProperty("anthropic-version", API_VERSION);
            OutputStream os = con.getOutputStream();
            try {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                os.close();
            }
            int code = con.getResponseCode();
            if (code < 200 || code >= 300) {
                InputStream es = con.getErrorStream();
                String text = es == null ? "" : readAll(es);
                String msg = text;
                try {
                    JSONObject err = new JSONObject(text).optJSONObject("error");
                    if (err != null) {
                        msg = err.optString("message", text);
                    }
                } catch (JSONException ignored) {
                    // Rohtext
                }
                throw new ApiException(code, msg);
            }
            java.util.TreeMap<Integer, JSONObject> blocks = new java.util.TreeMap<>();
            java.util.HashMap<Integer, StringBuilder> inputs = new java.util.HashMap<>();
            String stopReason = null;
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8));
            try {
                StringBuilder data = new StringBuilder();
                String line;
                while (true) {
                    line = r.readLine();
                    if (line == null || line.isEmpty()) {
                        if (data.length() > 0) {
                            String sr = handleEvent(data.toString(), blocks, inputs, listener);
                            if (sr != null) {
                                stopReason = sr;
                            }
                            data.setLength(0);
                        }
                        if (line == null) {
                            break;
                        }
                        continue;
                    }
                    if (line.startsWith("data:")) {
                        if (data.length() > 0) {
                            data.append('\n');
                        }
                        data.append(line.substring(5).trim());
                    }
                }
            } finally {
                r.close();
            }
            JSONArray content = new JSONArray();
            for (JSONObject b : blocks.values()) {
                content.put(b);
            }
            JSONObject resp = new JSONObject();
            resp.put("content", content);
            resp.put("stop_reason", stopReason == null ? "end_turn" : stopReason);
            return resp;
        } finally {
            body.remove("stream");
            con.disconnect();
        }
    }

    /** Verarbeitet ein Event. Liefert stop_reason, falls darin enthalten. */
    private static String handleEvent(String data, java.util.TreeMap<Integer, JSONObject> blocks,
                                      java.util.HashMap<Integer, StringBuilder> inputs, TextListener listener)
            throws JSONException, ApiException {
        JSONObject d = new JSONObject(data);
        String type = d.optString("type");
        switch (type) {
            case "content_block_start": {
                JSONObject cb = d.optJSONObject("content_block");
                if (cb != null) {
                    blocks.put(d.optInt("index"), cb);
                }
                return null;
            }
            case "content_block_delta": {
                int idx = d.optInt("index");
                JSONObject cb = blocks.get(idx);
                JSONObject delta = d.optJSONObject("delta");
                if (cb == null || delta == null) {
                    return null;
                }
                switch (delta.optString("type")) {
                    case "text_delta": {
                        String t = delta.optString("text");
                        cb.put("text", cb.optString("text", "") + t);
                        if (listener != null && !t.isEmpty()) {
                            listener.onText(t);
                        }
                        break;
                    }
                    case "thinking_delta":
                        cb.put("thinking", cb.optString("thinking", "") + delta.optString("thinking"));
                        break;
                    case "signature_delta":
                        cb.put("signature", delta.optString("signature"));
                        break;
                    case "input_json_delta": {
                        StringBuilder sb = inputs.get(idx);
                        if (sb == null) {
                            sb = new StringBuilder();
                            inputs.put(idx, sb);
                        }
                        sb.append(delta.optString("partial_json"));
                        break;
                    }
                    case "citations_delta": {
                        JSONArray cits = cb.optJSONArray("citations");
                        if (cits == null) {
                            cits = new JSONArray();
                            cb.put("citations", cits);
                        }
                        JSONObject c = delta.optJSONObject("citation");
                        if (c != null) {
                            cits.put(c);
                        }
                        break;
                    }
                    default:
                        break;
                }
                return null;
            }
            case "content_block_stop": {
                int idx = d.optInt("index");
                StringBuilder sb = inputs.remove(idx);
                JSONObject cb = blocks.get(idx);
                if (sb != null && cb != null) {
                    cb.put("input", sb.length() == 0 ? new JSONObject() : new JSONObject(sb.toString()));
                }
                return null;
            }
            case "message_delta": {
                JSONObject delta = d.optJSONObject("delta");
                if (delta != null && delta.has("stop_reason") && !delta.isNull("stop_reason")) {
                    return delta.optString("stop_reason");
                }
                return null;
            }
            case "error": {
                JSONObject err = d.optJSONObject("error");
                String et = err != null ? err.optString("type") : "";
                String msg = err != null ? err.optString("message", et) : data;
                int status = "overloaded_error".equals(et) ? 529 : "rate_limit_error".equals(et) ? 429 : 500;
                throw new ApiException(status, msg);
            }
            default:
                return null;
        }
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
