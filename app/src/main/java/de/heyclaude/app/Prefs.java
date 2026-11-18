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
    static final String ELEVEN_KEY = "eleven_key";
    static final String ELEVEN_VOICE_ID = "eleven_voice_id";
    static final String ELEVEN_VOICE_NAME = "eleven_voice_name";
    static final String ELEVEN_FAST = "eleven_fast";
    static final String LOCATION = "location";

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

    static String elevenKey(Context ctx) {
        return get(ctx).getString(ELEVEN_KEY, "").trim();
    }

    static String elevenVoiceId(Context ctx) {
        return get(ctx).getString(ELEVEN_VOICE_ID, ElevenLabs.DEFAULT_VOICE_ID);
    }

    static String elevenVoiceName(Context ctx) {
        return get(ctx).getString(ELEVEN_VOICE_NAME, ElevenLabs.DEFAULT_VOICE_NAME);
    }

    static boolean elevenFast(Context ctx) {
        return get(ctx).getBoolean(ELEVEN_FAST, false);
    }

    static boolean location(Context ctx) {
        return get(ctx).getBoolean(LOCATION, true);
    }
}
