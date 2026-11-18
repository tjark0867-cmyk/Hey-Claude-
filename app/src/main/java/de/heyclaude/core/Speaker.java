package de.heyclaude.core;

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
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Spricht Text aus: bevorzugt mit einer echten ElevenLabs-Stimme, sonst (oder bei Fehlern) mit der
 * besten Stimme des Handys. Kann satzweise sprechen (Streaming): Saetze werden der Reihe nach
 * erzeugt und abgespielt, waehrend Claude noch weiterschreibt.
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

    /** Ein Stueck Sprache in der Warteschlange: fertige Audiodatei oder Text fuer die Handy-Stimme. */
    private static final class Item {
        final File file;
        final String text;

        Item(File file, String text) {
            this.file = file;
            this.text = text;
        }
    }

    private final Context ctx;
    private final Handler main;
    private final ExecutorService synth = Executors.newSingleThreadExecutor();
    private final TextToSpeech tts;
    private boolean ttsReady;
    private boolean ttsFailed;

    private int session;
    private Callback currentCb;
    private boolean streamEnded;
    private boolean useEleven;
    private String elevenKey;
    private String elevenVoice;
    private boolean elevenFast;
    private String prevChunk;
    private int synthPending;
    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    private boolean playing;
    private int utteranceCounter;
    private String activeUtterance;
    private Runnable activeDone;
    private MediaPlayer player;
    private File playingFile;
    private AudioFocusRequest focusRequest;

    Speaker(Context ctx, Handler main, ExecutorService unused) {
        this.ctx = ctx.getApplicationContext();
        this.main = main;
        this.tts = new TextToSpeech(this.ctx, this);
    }

    // ------------------------------------------------------------------ Oeffentlich

    /** Spricht einen ganzen Text. callback kommt genau einmal (ausser nach stop()). */
    void speak(String text, Callback callback) {
        beginStream();
        enqueue(text);
        endStream(callback);
    }

    /** Beginnt eine satzweise Ausgabe. */
    void beginStream() {
        stopPlayback();
        session++;
        currentCb = null;
        streamEnded = false;
        synthPending = 0;
        prevChunk = null;
        elevenKey = Prefs.elevenKey(ctx);
        elevenVoice = Prefs.elevenVoiceId(ctx);
        elevenFast = Prefs.elevenFast(ctx);
        useEleven = !elevenKey.isEmpty();
        lastInfo = useEleven ? "ElevenLabs-Stimme" : "Handy-Stimme";
        requestFocus();
    }

    /** Haengt einen Satz (oder mehrere) an. */
    void enqueue(String text) {
        final String t = limit(clean(text));
        if (t.isEmpty()) {
            return;
        }
        final int s = session;
        if (!useEleven) {
            if (synthPending == 0) {
                addItem(new Item(null, t), s);
            } else {
                // Es werden noch fruehere Saetze erzeugt: hinten anstellen, damit die Reihenfolge stimmt
                synthPending++;
                synth.execute(() -> main.post(() -> {
                    if (s != session) {
                        return;
                    }
                    synthPending--;
                    addItem(new Item(null, t), s);
                }));
            }
            return;
        }
        synthPending++;
        final String prev = prevChunk;
        prevChunk = t;
        final String key = elevenKey;
        final String voice = elevenVoice;
        final boolean fast = elevenFast;
        synth.execute(() -> {
            File f = null;
            String info = null;
            try {
                f = ElevenLabs.synthesize(ctx, key, voice, fast, t, prev);
            } catch (ElevenLabs.ApiException e) {
                Log.e(TAG, "ElevenLabs " + e.status + ": " + e.getMessage());
                info = ElevenLabs.explain(e) + " – Handy-Stimme benutzt";
            } catch (Exception e) {
                Log.e(TAG, "ElevenLabs", e);
                info = "ElevenLabs nicht erreichbar – Handy-Stimme benutzt";
            }
            final File file = f;
            final String inf = info;
            main.post(() -> {
                if (s != session) {
                    if (file != null) {
                        file.delete();
                    }
                    return;
                }
                synthPending--;
                if (inf != null) {
                    lastInfo = inf;
                    useEleven = false; // Rest des Satzes mit der Handy-Stimme (Reihenfolge bleibt)
                }
                addItem(new Item(file, t), s);
            });
        });
    }

    /** Keine weiteren Saetze; callback, sobald alles gesprochen ist. */
    void endStream(Callback callback) {
        currentCb = callback;
        streamEnded = true;
        maybeFinish(session);
    }

    /** Bricht die aktuelle Ausgabe ab (ohne Callback). */
    void stop() {
        session++;
        currentCb = null;
        streamEnded = false;
        stopPlayback();
        abandonFocus();
    }

    void shutdown() {
        stop();
        synth.shutdownNow();
        try {
            tts.shutdown();
        } catch (Exception ignored) {
            // egal
        }
    }

    // ------------------------------------------------------------------ Warteschlange

    private void addItem(Item it, int s) {
        if (s != session) {
            if (it.file != null) {
                it.file.delete();
            }
            return;
        }
        queue.add(it);
        if (!playing) {
            playNext(s);
        }
    }

    private void playNext(final int s) {
        if (s != session) {
            return;
        }
        Item it = queue.poll();
        if (it == null) {
            playing = false;
            maybeFinish(s);
            return;
        }
        playing = true;
        if (it.file != null) {
            playFile(it, s);
        } else {
            speakPhone(it.text, s, 0);
        }
    }

    private void maybeFinish(int s) {
        if (s != session || !streamEnded || synthPending > 0 || playing || !queue.isEmpty()) {
            return;
        }
        Callback cb = currentCb;
        currentCb = null;
        streamEnded = false;
        abandonFocus();
        if (cb != null) {
            cb.onDone();
        }
    }

    // ------------------------------------------------------------------ ElevenLabs-Datei abspielen

    private void playFile(final Item it, final int s) {
        releasePlayer();
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(ATTRS);
            mp.setDataSource(it.file.getAbsolutePath());
            mp.setOnCompletionListener(m -> {
                releasePlayer();
                playNext(s);
            });
            mp.setOnErrorListener((m, what, extra) -> {
                releasePlayer();
                playNext(s);
                return true;
            });
            mp.prepare();
            player = mp;
            playingFile = it.file;
            mp.start();
        } catch (Exception e) {
            Log.e(TAG, "Wiedergabe", e);
            mp.release();
            player = null;
            it.file.delete();
            lastInfo = "Wiedergabe fehlgeschlagen – Handy-Stimme benutzt";
            speakPhone(it.text, s, 0);
        }
    }

    private void releasePlayer() {
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
    }

    // ------------------------------------------------------------------ Handy-Stimme

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            ttsFailed = true;
            Log.e(TAG, "TextToSpeech init: " + status);
            return;
        }
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
                    main.post(() -> utteranceFinished(utteranceId));
                }

                @Override
                @SuppressWarnings("deprecation")
                public void onError(String utteranceId) {
                    main.post(() -> utteranceFinished(utteranceId));
                }
            });
            ttsReady = true;
        } catch (Exception e) {
            Log.e(TAG, "TTS-Setup", e);
            ttsFailed = true;
        }
    }

    private void speakPhone(final String text, final int s, final int waited) {
        if (s != session) {
            return;
        }
        if (!ttsReady) {
            if (ttsFailed || waited > 20) {
                playNext(s); // ohne Sprachausgabe weitermachen
            } else {
                main.postDelayed(() -> speakPhone(text, s, waited + 1), 250);
            }
            return;
        }
        String id = "u" + s + "_" + (++utteranceCounter);
        activeUtterance = id;
        activeDone = () -> playNext(s);
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
        int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id);
        if (r != TextToSpeech.SUCCESS) {
            lastInfo = "Handy-Sprachausgabe fehlgeschlagen";
            utteranceFinished(id);
        }
    }

    private void utteranceFinished(String id) {
        if (id != null && id.equals(activeUtterance)) {
            Runnable r = activeDone;
            activeDone = null;
            activeUtterance = null;
            if (r != null) {
                r.run();
            }
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
                    score -= 5;
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

    // ------------------------------------------------------------------ Hilfen

    private void stopPlayback() {
        queue.clear();
        playing = false;
        activeUtterance = null;
        activeDone = null;
        releasePlayer();
        try {
            if (ttsReady) {
                tts.stop();
            }
        } catch (Exception ignored) {
            // egal
        }
    }

    private static String limit(String t) {
        int max = Math.min(3900, TextToSpeech.getMaxSpeechInputLength() - 10);
        return t.length() > max ? t.substring(0, max) : t;
    }

    static String clean(String s) {
        String t = s.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
        t = t.replaceAll("https?://\\S+", "");
        t = t.replaceAll("(?m)^\\s*[-*•]\\s+", "");
        t = t.replaceAll("(?m)^\\s*#+\\s*", "");
        t = t.replace("**", "").replace("__", "").replace("`", "").replace("*", "");
        return t.trim();
    }

    private void requestFocus() {
        if (focusRequest != null) {
            return;
        }
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
