package de.heyclaude.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContextWrapper;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import de.heyclaude.app.Component;
import de.heyclaude.app.Core;
import de.heyclaude.app.MainActivity;
import de.heyclaude.app.VoiceService;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Laeuft dauerhaft im Hintergrund (Vordergrund-Dienst mit Mikrofon):
 * 1. hoert offline auf "Hey Claude" (Vosk),
 * 2. nimmt danach die Frage auf (Android-Spracherkennung),
 * 3. fragt Claude ueber die API,
 * 4. liest die Antwort vor (Text-to-Speech).
 */
public class Engine extends ContextWrapper implements Component {

    private Service svc;

    public Engine() {
        super(null);
    }

    @Override
    public Object handle(Object host, String event, Object... args) {
        switch (event) {
            case "onCreate":
                svc = (Service) host;
                attachBaseContext(svc);
                create();
                return null;
            case "onStartCommand":
                return startCommand((Intent) args[0], (Integer) args[1], (Integer) args[2]);
            case "onDestroy":
                destroy();
                return null;
            default:
                return null;
        }
    }

    /** Fuer andere Teile des Codes: laeuft gerade kein Gespraech? */
    static volatile boolean idle = true;

    private static final String TAG = "HeyClaude";

    static final String ACTION_STOP = "de.heyclaude.app.STOP";
    static final String ACTION_ASK = "de.heyclaude.app.ASK";
    private static final String CHANNEL_ID = "heyclaude_listen";
    private static final int NOTIF_ID = 42;
    private static final int SAMPLE_RATE = 16000;
    private static final long HISTORY_TIMEOUT_MS = 5 * 60 * 1000L;
    private static final int MAX_HISTORY = 12;

    private static final String[] WAKE_FIRST = {"hey", "hi"};
    private static final String[] WAKE_SECOND = {"claude", "cloud", "clod", "clot", "clawed"};
    private static final Pattern WAKE_PATTERN = Pattern.compile(
            "(^|\\s)(hey|hi)\\s+(claude|cloud|clod|clot|clawed)(\\s|$)");
    private static final Pattern STOP_PATTERN = Pattern.compile(
            "^(stopp?|abbrechen|vergiss es|nichts|egal|ruhe|halt|schon gut|nein danke)$");

    // ---- Status fuer die Oberflaeche ----
    static volatile boolean running;
    static volatile String lastStatus = "Gestoppt";
    static volatile String lastQuestion = "";
    static volatile String lastAnswer = "";
    static volatile Runnable statusListener;

    private enum State { LOADING, WAKE, LISTENING, THINKING, SPEAKING, IDLE }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<String[]> history = new ArrayList<>();

    private State state = State.LOADING;
    private boolean foregroundStarted;
    private boolean destroyed;
    private Model voskModel;
    private String modelError;
    private WakeThread wakeThread;
    private SpeechRecognizer recognizer;
    private int srSession;
    private Speaker speaker;
    private LocationHelper locationHelper;
    private DeviceControl device;
    private android.media.AudioFocusRequest sessionFocus;
    private boolean followUpAfterSpeech;
    private long lastInteraction;
    private PowerManager.WakeLock wakeLock;

    // ------------------------------------------------------------------ Lebenszyklus

    private void create() {
        running = true;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Hey Claude (zuhören)",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        speaker = new Speaker(this, main, worker);
        locationHelper = new LocationHelper(this);
        device = new DeviceControl(this, main);
        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeyClaude:listen");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private int startCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            Prefs.setEnabled(this, false);
            svc.stopSelf();
            return Service.START_NOT_STICKY;
        }
        if (VoiceService.ACTION_RESUME.equals(action)) {
            // Neuer Code im laufenden Betrieb: Dienst ist schon im Vordergrund
            foregroundStarted = true;
            setStatus("Neue Version geladen …");
            loadModel();
            main.postDelayed(updateCheck, 30 * 60_000L);
            return Service.START_STICKY;
        }
        if (!foregroundStarted) {
            try {
                Notification n = buildNotification("Starte …");
                if (Build.VERSION.SDK_INT >= 29) {
                    int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                    if (Prefs.location(this) && LocationHelper.hasPermission(this)) {
                        types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                    }
                    try {
                        svc.startForeground(NOTIF_ID, n, types);
                    } catch (SecurityException e) {
                        // Standort-Typ nicht erlaubt -> nur Mikrofon
                        svc.startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                    }
                } else {
                    svc.startForeground(NOTIF_ID, n);
                }
                foregroundStarted = true;
            } catch (Exception e) {
                // z.B. Android 14+: Mikrofon-Dienst darf nicht aus dem Hintergrund starten
                Log.e(TAG, "startForeground fehlgeschlagen", e);
                lastStatus = "Bitte App öffnen und neu starten";
                notifyUi();
                svc.stopSelf();
                return Service.START_NOT_STICKY;
            }
            Prefs.setEnabled(this, true);
            Hints.cancel(this, Hints.ID_RESTART);
            setStatus("Lade Wake-Word …");
            loadModel();
            main.postDelayed(updateCheck, 60_000L);
            if (Prefs.location(this)) {
                worker.execute(() -> locationHelper.get(5000)); // Standort schon mal vorab holen
            }
        }
        if (ACTION_ASK.equals(action)) {
            main.post(this::onWakeWord);
        }
        return Service.START_STICKY;
    }

    private void destroy() {
        destroyed = true;
        idle = true;
        running = false;
        main.removeCallbacksAndMessages(null);
        stopWakeThread();
        destroyRecognizer();
        if (speaker != null) {
            speaker.shutdown();
        }
        if (device != null) {
            device.shutdown();
        }
        abandonSessionFocus();
        worker.shutdownNow();
        if (voskModel != null) {
            try {
                voskModel.close();
            } catch (Exception ignored) {
                // egal
            }
            voskModel = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        lastStatus = "Gestoppt";
        notifyUi();
    }

    // ------------------------------------------------------------------ Automatische Updates

    private final Runnable updateCheck = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            main.postDelayed(this, 30 * 60_000L); // alle 30 Minuten
            if (!Prefs.autoUpdate(Engine.this)) {
                return;
            }
            final android.content.Context app = getApplicationContext();
            new Thread(() -> {
                final Updater.Update u = Updater.check(app, false);
                if (u != null) {
                    main.post(() -> applyWhenIdle(u, 0));
                }
            }, "update-check").start();
        }
    };

    /** Wendet ein Update erst an, wenn gerade kein Gespraech laeuft. */
    private void applyWhenIdle(final Updater.Update u, final int tries) {
        if (destroyed) {
            return;
        }
        if (state == State.WAKE || state == State.IDLE) {
            final android.content.Context app = getApplicationContext();
            new Thread(() -> Updater.apply(app, u), "update-apply").start();
        } else if (tries < 60) {
            main.postDelayed(() -> applyWhenIdle(u, tries + 1), 30_000L);
        }
    }

    // ------------------------------------------------------------------ Wake-Word (Vosk)

    private void loadModel() {
        new Thread(() -> {
            try {
                String path = ModelInstaller.install(getApplicationContext());
                final Model m = new Model(path);
                main.post(() -> {
                    if (destroyed) {
                        try {
                            m.close();
                        } catch (Exception ignored) {
                            // egal
                        }
                        return;
                    }
                    voskModel = m;
                    if (state == State.LOADING || state == State.IDLE) {
                        returnToWake();
                    }
                });
            } catch (Throwable t) {
                Log.e(TAG, "Modell konnte nicht geladen werden", t);
                final String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                main.post(() -> {
                    modelError = msg;
                    if (state == State.LOADING) {
                        returnToWake();
                    }
                });
            }
        }, "vosk-load").start();
    }

    private static String wakeGrammar() {
        JSONArray arr = new JSONArray();
        for (String a : WAKE_FIRST) {
            for (String b : WAKE_SECOND) {
                arr.put(a + " " + b);
            }
        }
        arr.put("[unk]");
        return arr.toString();
    }

    static boolean isWakePhrase(String json) {
        if (json == null || json.isEmpty()) {
            return false;
        }
        try {
            JSONObject o = new JSONObject(json);
            String t = o.optString("text", "");
            if (t.isEmpty()) {
                t = o.optString("partial", "");
            }
            return WAKE_PATTERN.matcher(t.toLowerCase(Locale.ROOT)).find();
        } catch (Exception e) {
            return false;
        }
    }

    /** Liest das Mikrofon und wartet auf "Hey Claude". */
    private final class WakeThread extends Thread {
        volatile boolean stopRequested;

        WakeThread() {
            super("wake-word");
        }

        @Override
        public void run() {
            Recognizer rec = null;
            AudioRecord ar = null;
            boolean triggered = false;
            String error = null;
            try {
                rec = new Recognizer(voskModel, (float) SAMPLE_RATE, wakeGrammar());
                int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(min, SAMPLE_RATE));
                if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IOException("Mikrofon nicht verfügbar");
                }
                ar.startRecording();
                short[] buf = new short[SAMPLE_RATE / 10];
                while (!stopRequested) {
                    int n = ar.read(buf, 0, buf.length);
                    if (n < 0) {
                        throw new IOException("Audiofehler " + n);
                    }
                    if (n == 0) {
                        continue;
                    }
                    String json = rec.acceptWaveForm(buf, n) ? rec.getResult() : rec.getPartialResult();
                    if (isWakePhrase(json)) {
                        triggered = true;
                        break;
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "Wake-Word-Fehler", t);
                error = t.getMessage() != null ? t.getMessage() : t.toString();
            } finally {
                if (ar != null) {
                    try {
                        ar.stop();
                    } catch (Exception ignored) {
                        // war evtl. nicht gestartet
                    }
                    ar.release();
                }
                if (rec != null) {
                    try {
                        rec.close();
                    } catch (Exception ignored) {
                        // egal
                    }
                }
            }
            if (stopRequested) {
                return;
            }
            final WakeThread self = this;
            final boolean wasTriggered = triggered;
            final String err = error;
            main.post(() -> {
                if (wakeThread != self) {
                    return;
                }
                wakeThread = null;
                if (wasTriggered) {
                    onWakeWord();
                } else {
                    onWakeError(err);
                }
            });
        }
    }

    private void startWakeThread() {
        stopWakeThread();
        wakeThread = new WakeThread();
        wakeThread.start();
    }

    private void stopWakeThread() {
        WakeThread t = wakeThread;
        wakeThread = null;
        if (t != null) {
            t.stopRequested = true;
            try {
                t.join(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void onWakeError(String err) {
        if (destroyed || state != State.WAKE) {
            return;
        }
        setStatus("Mikrofon belegt – versuche es gleich wieder …");
        main.postDelayed(() -> {
            if (!destroyed && state == State.WAKE && wakeThread == null) {
                returnToWake();
            }
        }, 3000);
    }

    /** Zurueck in den Ruhezustand: auf "Hey Claude" warten. */
    private void returnToWake() {
        if (destroyed) {
            return;
        }
        abandonSessionFocus();
        idle = true;
        destroyRecognizer();
        if (voskModel != null) {
            state = State.WAKE;
            startWakeThread();
            setStatus("Sag „Hey Claude“");
        } else if (modelError != null) {
            state = State.IDLE;
            setStatus("Bereit – tippe auf „Fragen“ (Wake-Word nicht verfügbar: " + modelError + ")");
        } else {
            state = State.LOADING;
            setStatus("Lade Wake-Word …");
        }
    }

    // ------------------------------------------------------------------ Frage aufnehmen

    /** "Hey Claude" erkannt (oder Fragen-Knopf gedrueckt). */
    private void onWakeWord() {
        if (destroyed || state == State.LISTENING || state == State.THINKING) {
            return;
        }
        if (state == State.SPEAKING) {
            speaker.stop();
        }
        stopWakeThread();
        idle = false;
        requestSessionFocus();
        state = State.LISTENING;
        setStatus("Ich höre zu …");
        beep();
        main.postDelayed(() -> startRecognizer(0, false), 350);
    }

    private void startRecognizer(final int attempt, final boolean withoutLanguage) {
        if (destroyed || state != State.LISTENING) {
            return;
        }
        destroyRecognizer();
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Auf diesem Handy ist keine Spracherkennung verfügbar. Bitte installiere oder aktiviere die Google App.", false);
            return;
        }
        ComponentName google = attempt > 0 ? findGoogleRecognizer() : null;
        try {
            recognizer = google != null
                    ? SpeechRecognizer.createSpeechRecognizer(this, google)
                    : SpeechRecognizer.createSpeechRecognizer(this);
        } catch (Exception e) {
            Log.e(TAG, "SpeechRecognizer", e);
            speak("Die Spracherkennung konnte nicht gestartet werden.", false);
            return;
        }
        final int session = ++srSession;
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
            }

            @Override
            public void onBeginningOfSpeech() {
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }

            @Override
            public void onResults(Bundle results) {
                if (session != srSession || state != State.LISTENING) {
                    return;
                }
                ArrayList<String> list = results != null
                        ? results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
                String text = list != null && !list.isEmpty() ? list.get(0) : "";
                destroyRecognizer();
                if (text == null || text.trim().isEmpty()) {
                    returnToWake();
                } else {
                    handleQuestion(text.trim());
                }
            }

            @Override
            public void onError(int error) {
                if (session != srSession || state != State.LISTENING) {
                    return;
                }
                Log.w(TAG, "Spracherkennung Fehler " + error + " (Versuch " + attempt + ")");
                destroyRecognizer();
                if (error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    // Nichts gesagt -> leise zurueck in den Ruhezustand
                    returnToWake();
                    return;
                }
                if (attempt < 2) {
                    boolean langProblem = error == 12 || error == 13; // Sprache nicht unterstuetzt
                    main.postDelayed(() -> startRecognizer(attempt + 1, withoutLanguage || langProblem), 600);
                    return;
                }
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    speak("Mir fehlt die Berechtigung für die Spracherkennung. Öffne bitte die App und starte mich neu.", false);
                } else if (error == SpeechRecognizer.ERROR_NETWORK
                        || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                    speak("Für die Spracherkennung fehlt gerade die Internetverbindung.", false);
                } else {
                    speak("Die Spracherkennung hat nicht funktioniert. Fehler " + error + ".", false);
                }
            }
        });

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        if (!withoutLanguage) {
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        }
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            Log.e(TAG, "startListening", e);
            destroyRecognizer();
            speak("Die Spracherkennung konnte nicht gestartet werden.", false);
        }
    }

    private ComponentName findGoogleRecognizer() {
        try {
            List<ResolveInfo> list = getPackageManager().queryIntentServices(
                    new Intent(RecognitionService.SERVICE_INTERFACE), 0);
            for (ResolveInfo ri : list) {
                if (ri.serviceInfo != null
                        && "com.google.android.googlequicksearchbox".equals(ri.serviceInfo.packageName)) {
                    return new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Google-Erkennung nicht gefunden", e);
        }
        return null;
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            srSession++;
            try {
                recognizer.cancel();
            } catch (Exception ignored) {
                // egal
            }
            try {
                recognizer.destroy();
            } catch (Exception ignored) {
                // egal
            }
            recognizer = null;
        }
    }

    // ------------------------------------------------------------------ Claude fragen

    private void handleQuestion(String text) {
        lastQuestion = text;
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", "").trim();
        if (STOP_PATTERN.matcher(normalized).matches() || normalized.equals("pause")) {
            if (device.isMusicActive()) {
                device.sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE); // "Stopp" haelt die Musik an
            }
            beep();
            returnToWake();
            return;
        }
        String apiKey = Prefs.apiKey(this);
        if (apiKey.isEmpty()) {
            speak("Bitte trag zuerst deinen Claude API-Schlüssel in der App ein.", false);
            return;
        }
        state = State.THINKING;
        setStatus("Denke nach …");

        long now = System.currentTimeMillis();
        if (now - lastInteraction > HISTORY_TIMEOUT_MS) {
            history.clear();
        }
        history.add(new String[]{"user", text});
        final List<String[]> snapshot = new ArrayList<>(history);
        final String model = Prefs.model(this);
        final boolean web = Prefs.webSearch(this);
        final boolean useLocation = Prefs.location(this);

        worker.execute(() -> {
            String answer;
            boolean ok = false;
            try {
                LocationHelper.Place place = useLocation ? locationHelper.get(4000) : null;
                String system = systemPrompt(web, place, device.statusLine());
                answer = ClaudeClient.ask(apiKey, model, web, true, system, snapshot, place, device);
                ok = true;
            } catch (ClaudeClient.ApiException e) {
                Log.e(TAG, "API-Fehler " + e.status + ": " + e.getMessage());
                answer = apiErrorText(e);
            } catch (IOException e) {
                Log.e(TAG, "Netzwerkfehler", e);
                answer = "Ich konnte Claude nicht erreichen. Bitte prüfe deine Internetverbindung.";
            } catch (Exception e) {
                Log.e(TAG, "Fehler", e);
                answer = "Da ist etwas schiefgelaufen.";
            }
            final String a = answer;
            final boolean success = ok;
            main.post(() -> onAnswer(a, success));
        });
    }

    private void onAnswer(String answer, boolean ok) {
        if (destroyed) {
            return;
        }
        if (ok) {
            history.add(new String[]{"assistant", answer});
            while (history.size() > MAX_HISTORY) {
                history.remove(0);
                history.remove(0);
            }
        } else if (!history.isEmpty()) {
            history.remove(history.size() - 1);
        }
        lastInteraction = System.currentTimeMillis();
        lastAnswer = answer;
        boolean followUp = ok && Prefs.followUp(this) && answer.trim().endsWith("?");
        speak(answer, followUp);
    }

    private String apiErrorText(ClaudeClient.ApiException e) {
        if (e.status == 401 || e.status == 403) {
            return "Der API-Schlüssel wird nicht akzeptiert. Bitte prüfe ihn in der App.";
        }
        if (ClaudeClient.isCreditProblem(e.getMessage())) {
            return "Dein API-Guthaben ist aufgebraucht. Du kannst es in der Claude Console aufladen.";
        }
        if (e.status == 404) {
            return "Das eingestellte Modell gibt es nicht. Bitte prüfe den Modellnamen in der App.";
        }
        if (e.status == 429) {
            return "Gerade kommen zu viele Anfragen. Versuch es gleich nochmal.";
        }
        if (e.status == 529 || e.status >= 500) {
            return "Claude ist gerade überlastet. Versuch es bitte gleich nochmal.";
        }
        return "Die Anfrage hat nicht geklappt, Fehler " + e.status + ".";
    }

    private static String systemPrompt(boolean web, LocationHelper.Place place, String deviceStatus) {
        SimpleDateFormat f = new SimpleDateFormat("EEEE, d. MMMM yyyy, HH:mm 'Uhr'", Locale.GERMAN);
        f.setTimeZone(TimeZone.getDefault());
        StringBuilder sb = new StringBuilder();
        sb.append("Du bist Claude und arbeitest als Sprachassistent auf dem Android-Handy des Nutzers, ähnlich wie Alexa. ");
        sb.append("Deine Antworten werden laut vorgelesen. Antworte deshalb in der Sprache des Nutzers (normalerweise Deutsch), ");
        sb.append("kurz, freundlich und natürlich gesprochen – meistens in ein bis drei Sätzen. ");
        sb.append("Nur wenn der Nutzer ausdrücklich etwas Ausführliches möchte (z.B. eine Geschichte oder Erklärung), darfst du länger antworten. ");
        sb.append("Benutze niemals Markdown, Listen, Aufzählungszeichen, Überschriften, Emojis, Tabellen oder Links. ");
        sb.append("Schreibe Zahlen, Einheiten und Abkürzungen so, dass sie vorgelesen gut klingen. ");
        sb.append("Die Frage stammt aus einer Spracherkennung und kann falsch verstandene Wörter enthalten – interpretiere sie sinnvoll. ");
        sb.append("Du kannst das Handy steuern: laufende Musik (abspielen, pausieren, nächster oder vorheriger Titel), ");
        sb.append("bestimmte Lieder, Künstler oder Videos auf YouTube, YouTube Music oder Spotify starten (play_music, Standard YouTube), ");
        sb.append("Apps öffnen (open_app), Nachrichten schreiben und senden (send_message über WhatsApp, Instagram, SMS oder Telegram), ");
        sb.append("Lautstärke, Klingelmodus (normal, vibrieren, lautlos) und Taschenlampe. Benutze dafür die passenden Werkzeuge. ");
        sb.append("Beispiel: „Öffne Instagram und schreib Emily hey“ → send_message mit app=instagram, to=Emily, text=hey. ");
        sb.append("„Schreib Mama, dass ich später komme“ → send_message mit app=whatsapp, to=Mama, text=Ich komme später. ");
        sb.append("Frag bei solchen Befehlen nicht nach, sondern mach es einfach. ");
        sb.append("WICHTIG: Nach dem Öffnen einer App, dem Starten von Musik, dem Senden einer Nachricht oder einer anderen Handy-Steuerung ");
        sb.append("antworte NUR mit „Okay.“ – keine Erklärungen, keine Hinweise auf Benachrichtigungen, Erlaubnisse oder Einstellungen. ");
        sb.append("Nur wenn ein Werkzeug mit „Fehler“ antwortet, sag in einem kurzen Satz, was nicht ging. ");
        sb.append("Anrufe, WLAN, Bluetooth, Helligkeit, Timer oder Wecker kannst du noch nicht – sag das ehrlich. ");
        if (deviceStatus != null && !deviceStatus.isEmpty()) {
            sb.append("Aktueller Handy-Status: ").append(deviceStatus).append(". ");
        }
        if (web) {
            sb.append("Für aktuelle Informationen wie Wetter, Nachrichten, Sportergebnisse, Öffnungszeiten oder Preise nutze die Websuche. ");
            sb.append("Nenne keine Quellen-URLs, fasse das Ergebnis einfach in eigenen Worten zusammen. ");
        }
        if (place != null) {
            String where = place.describe();
            sb.append("Aktueller Standort des Handys: ");
            sb.append(where.isEmpty() ? "unbekannte Adresse" : where);
            sb.append(String.format(Locale.ROOT, " (Koordinaten %.5f, %.5f; Genauigkeit etwa %d m; ermittelt vor %d Minuten). ",
                    place.lat, place.lon, Math.round(place.accuracy),
                    Math.max(0, (System.currentTimeMillis() - place.time) / 60000)));
            sb.append("Wenn der Nutzer nach Wetter, Orten in der Nähe, Wegen oder Ähnlichem fragt, ohne einen Ort zu nennen, beziehe dich auf diesen Standort. ");
            sb.append("Wenn er fragt, wo er ist, sag es ihm. ");
        }
        sb.append("Aktuelles Datum und Uhrzeit: ").append(f.format(new Date()))
                .append(" (Zeitzone ").append(TimeZone.getDefault().getID()).append(").");
        return sb.toString();
    }

    // ------------------------------------------------------------------ Sprachausgabe

    private void speak(String text, boolean followUp) {
        if (destroyed) {
            return;
        }
        state = State.SPEAKING;
        followUpAfterSpeech = followUp;
        setStatus("Spreche …");
        speaker.speak(text, this::onSpeechFinished);
    }

    private void onSpeechFinished() {
        if (destroyed || state != State.SPEAKING) {
            return;
        }
        if (followUpAfterSpeech) {
            followUpAfterSpeech = false;
            state = State.IDLE;
            onWakeWord(); // Rueckfrage: direkt weiter zuhoeren
        } else {
            returnToWake();
        }
    }

    /** Waehrend eines Gespraechs laeuft Musik leiser (wie bei Alexa). */
    private void requestSessionFocus() {
        if (sessionFocus != null) {
            return;
        }
        try {
            sessionFocus = new android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(Speaker.ATTRS)
                    .build();
            getSystemService(AudioManager.class).requestAudioFocus(sessionFocus);
        } catch (Exception e) {
            sessionFocus = null;
        }
    }

    private void abandonSessionFocus() {
        if (sessionFocus != null) {
            try {
                getSystemService(AudioManager.class).abandonAudioFocusRequest(sessionFocus);
            } catch (Exception ignored) {
                // egal
            }
            sessionFocus = null;
        }
    }

    private void beep() {
        try {
            final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
            main.postDelayed(tg::release, 400);
        } catch (Exception e) {
            Log.w(TAG, "Ton", e);
        }
    }

    // ------------------------------------------------------------------ Status / Benachrichtigung

    private void setStatus(String status) {
        lastStatus = status;
        if (foregroundStarted && !destroyed) {
            try {
                getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(status));
            } catch (Exception e) {
                Log.w(TAG, "Benachrichtigung", e);
            }
        }
        notifyUi();
    }

    private static void notifyUi() {
        Runnable r = statusListener;
        if (r != null) {
            r.run();
        }
    }

    private Notification buildNotification(String status) {
        int piFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piOpen = PendingIntent.getActivity(this, 0, open, piFlags);
        PendingIntent piAsk = PendingIntent.getService(this, 1,
                new Intent(this, VoiceService.class).setAction(ACTION_ASK), piFlags);
        PendingIntent piStop = PendingIntent.getService(this, 2,
                new Intent(this, VoiceService.class).setAction(ACTION_STOP), piFlags);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(Res.drawable(this, "ic_mic"))
                .setContentTitle("Hey Claude")
                .setContentText(status)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(piOpen)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, Res.drawable(this, "ic_mic")), "Fragen", piAsk).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, Res.drawable(this, "ic_stop")), "Beenden", piStop).build());
        String q = lastQuestion;
        String a = lastAnswer;
        if (a != null && !a.isEmpty()) {
            b.setStyle(new Notification.BigTextStyle()
                    .bigText(status + "\n\nDu: " + q + "\nClaude: " + a));
        }
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }
}
