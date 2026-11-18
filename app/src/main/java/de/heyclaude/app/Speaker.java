package de.heyclaude.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import java.io.File;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Spricht Text aus: bevorzugt mit einer echten ElevenLabs-Stimme,
 * sonst (oder bei Fehlern) mit der besten Stimme des Handys.
 * Alle oeffentlichen Methoden auf dem Haupt-Thread aufrufen.
 */
final class Speaker implements TextToSpeech.OnInitListener {

    interface Callback {
        void onDone();
    }

    private static final String TAG = "HeyClaude";

    /** Medien-Lautstaerke (wie Musik) – damit ist die Stimme sicher hoerbar. */
    static final AudioAttributes ATTRS = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

    static volatile String lastInfo = "";

    private final Context ctx;
    private final Handler main;
    private final ExecutorService worker;
    private final TextToSpeech tts;
    private boolean ttsReady;
    private boolean ttsFailed;
    private String pendingText;
    private int pendingSession;
    private int session;
    private Callback currentCb;
    private MediaPlayer player;
    private File playingFile;
    private AudioFocusRequest focusRequest;

    Speaker(Context ctx, Handler main, ExecutorService worker) {
        this.ctx = ctx.getApplicationContext();
        this.main = main;
        this.worker = worker;
        this.tts = new TextToSpeech(this.ctx, this);
    }

    // ------------------------------------------------------------------ Handy-Stimme

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            ttsFailed = true;
            lastInfo = "Handy-Sprachausgabe nicht verfügbar";
            Log.e(TAG, "TextToSpeech init: " + status);
        } else {
            try {
                Locale loc = Locale.getDefault();
                int r = tts.setLanguage(loc);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    loc = Locale.GERMANY;
                    tts.setLanguage(loc);
                }
                Voice best = pickBestVoice(loc);
                if (best != null) {
                    tts.setVoice(best);
                }
                tts.setAudioAttributes(ATTRS);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        main.post(() -> finishUtterance(utteranceId));
                    }

                    @Override
                    @SuppressWarnings("deprecation")
                    public void onError(String utteranceId) {
                        main.post(() -> finishUtterance(utteranceId));
                    }
                });
                ttsReady = true;
            } catch (Exception e) {
                Log.e(TAG, "TTS-Setup", e);
                ttsFailed = true;
            }
        }
        if (pendingText != null) {
            String t = pendingText;
            pendingText = null;
            speakWithPhone(t, pendingSession);
        }
    }

    private Voice pickBestVoice(Locale loc) {
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null) {
                return null;
            }
            Voice best = null;
            int bestScore = Integer.MIN_VALUE;
            for (Voice v : voices) {
                if (v.getLocale() == null || !loc.getLanguage().equals(v.getLocale().getLanguage())) {
                    continue;
                }
                Set<String> feats = v.getFeatures();
                if (feats != null && feats.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) {
                    continue;
                }
                int score = v.getQuality() * 10;
                if (v.isNetworkConnectionRequired()) {
                    score -= 5; // lokal ist zuverlaessiger
                }
                if (loc.getCountry().equals(v.getLocale().getCountry())) {
                    score += 3;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = v;
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private void speakWithPhone(String text, int s) {
        if (s != session) {
            return;
        }
        if (!ttsReady) {
            if (ttsFailed) {
                finish(s);
            } else {
                pendingText = text;
                pendingSession = s;
            }
            return;
        }
        requestFocus();
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
        int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "u" + s);
        if (r != TextToSpeech.SUCCESS) {
            lastInfo = "Handy-Sprachausgabe fehlgeschlagen";
            finish(s);
        }
    }

    private void finishUtterance(String utteranceId) {
        if (utteranceId != null && utteranceId.equals("u" + session)) {
            finish(session);
        }
    }

    // ------------------------------------------------------------------ Oeffentlich

    /** Spricht den Text. callback wird genau einmal aufgerufen (ausser nach stop()). */
    void speak(String text, Callback callback) {
        stopPlayback();
        final int s = ++session;
        currentCb = callback;
        String t = clean(text);
        int max = Math.min(3900, TextToSpeech.getMaxSpeechInputLength() - 10);
        if (t.length() > max) {
            t = t.substring(0, max);
        }
        final String finalText = t;
        if (finalText.isEmpty()) {
            finish(s);
            return;
        }
        final String key = Prefs.elevenKey(ctx);
        if (key.isEmpty()) {
            lastInfo = "Handy-Stimme";
            speakWithPhone(finalText, s);
            return;
        }
        final String voiceId = Prefs.elevenVoiceId(ctx);
        final boolean fast = Prefs.elevenFast(ctx);
        worker.execute(() -> {
            try {
                File f = ElevenLabs.synthesize(ctx, key, voiceId, fast, finalText);
                main.post(() -> playFile(f, finalText, s));
            } catch (ElevenLabs.ApiException e) {
                Log.e(TAG, "ElevenLabs " + e.status + ": " + e.getMessage());
                final String info = ElevenLabs.explain(e);
                main.post(() -> {
                    lastInfo = info + " – Handy-Stimme benutzt";
                    speakWithPhone(finalText, s);
                });
            } catch (Exception e) {
                Log.e(TAG, "ElevenLabs", e);
                main.post(() -> {
                    lastInfo = "ElevenLabs nicht erreichbar – Handy-Stimme benutzt";
                    speakWithPhone(finalText, s);
                });
            }
        });
    }

    /** Bricht die aktuelle Ausgabe ab (ohne Callback). */
    void stop() {
        session++;
        currentCb = null;
        pendingText = null;
        stopPlayback();
        abandonFocus();
    }

    void shutdown() {
        stop();
        try {
            tts.shutdown();
        } catch (Exception ignored) {
            // egal
        }
    }

    // ------------------------------------------------------------------ ElevenLabs-Wiedergabe

    private void playFile(File f, String text, int s) {
        if (s != session) {
            f.delete();
            return;
        }
        stopPlayback();
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(ATTRS);
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnCompletionListener(m -> finish(s));
            mp.setOnErrorListener((m, what, extra) -> {
                finish(s);
                return true;
            });
            mp.prepare();
            player = mp;
            playingFile = f;
            requestFocus();
            mp.start();
            lastInfo = "ElevenLabs-Stimme";
        } catch (Exception e) {
            Log.e(TAG, "Wiedergabe", e);
            mp.release();
            player = null;
            f.delete();
            lastInfo = "Wiedergabe fehlgeschlagen – Handy-Stimme benutzt";
            speakWithPhone(text, s);
        }
    }

    private void stopPlayback() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
                // egal
            }
            player.release();
            player = null;
        }
        if (playingFile != null) {
            playingFile.delete();
            playingFile = null;
        }
        try {
            if (ttsReady) {
                tts.stop();
            }
        } catch (Exception ignored) {
            // egal
        }
    }

    private void finish(int s) {
        if (s != session) {
            return;
        }
        Callback cb = currentCb;
        currentCb = null;
        if (player != null) {
            player.release();
            player = null;
        }
        if (playingFile != null) {
            playingFile.delete();
            playingFile = null;
        }
        abandonFocus();
        if (cb != null) {
            cb.onDone();
        }
    }

    // ------------------------------------------------------------------ Hilfen

    static String clean(String s) {
        String t = s.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
        t = t.replaceAll("https?://\\S+", "");
        t = t.replaceAll("(?m)^\\s*[-*•]\\s+", "");
        t = t.replaceAll("(?m)^\\s*#+\\s*", "");
        t = t.replace("**", "").replace("__", "").replace("`", "").replace("*", "");
        return t.trim();
    }

    private void requestFocus() {
        try {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(ATTRS)
                    .build();
            am.requestAudioFocus(focusRequest);
        } catch (Exception e) {
            Log.w(TAG, "Audiofokus", e);
        }
    }

    private void abandonFocus() {
        if (focusRequest != null) {
            try {
                ctx.getSystemService(AudioManager.class).abandonAudioFocusRequest(focusRequest);
            } catch (Exception ignored) {
                // egal
            }
            focusRequest = null;
        }
    }
}
