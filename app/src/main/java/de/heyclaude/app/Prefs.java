package de.heyclaude.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Gespeicherte Einstellungen. */
final class Prefs {

    static final String DEFAULT_MODEL = "claude-haiku-5-5";

    private static final String FILE = "heyclaude";
    static final String API_KEY = "api_key";
    static final String MODEL = "model";
    static final String WEB_SEARCH = "web_search";
    static final String FOLLOW_UP = "follow_up";

    private Prefs() {
    }

    static SharedPreferences get(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String apiKey(Context ctx) {
        return get(ctx).getString(API_KEY, "").trim();
    }

    static String model(Context ctx) {
        String m = get(ctx).getString(MODEL, DEFAULT_MODEL).trim();
        return m.isEmpty() ? DEFAULT_MODEL : m;
    }

    static boolean webSearch(Context ctx) {
        return get(ctx).getBoolean(WEB_SEARCH, true);
    }

    static boolean followUp(Context ctx) {
        return get(ctx).getBoolean(FOLLOW_UP, true);
    }
}
