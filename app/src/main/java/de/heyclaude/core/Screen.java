package de.heyclaude.core;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.heyclaude.app.Component;
import de.heyclaude.app.Core;
import de.heyclaude.app.VoiceService;

/** Der App-Bildschirm. Komplett im Code aufgebaut, damit er sich ohne Installation aktualisieren laesst. */
public class Screen implements Component {

    private static final int REQ_PERMS = 7;

    static volatile boolean visible;

    private Activity a;
    private TextView statusView;
    private TextView lastQView;
    private TextView lastAView;
    private Button toggleButton;
    private EditText apiKeyEdit;
    private EditText modelEdit;
    private CheckBox webSearchBox;
    private CheckBox followUpBox;
    private CheckBox locationBox;
    private EditText elevenKeyEdit;
    private TextView voiceNameView;
    private TextView voiceInfoView;
    private CheckBox elevenFastBox;
    private CheckBox autoUpdateBox;
    private TextView updateInfoView;
    private Button allowUpdatesButton;
    private Button overlayButton;
    private Button contactsButton;
    private Button accessibilityButton;
    private Button appInfoButton;
    private Button shellButton;
    private Button xiaomiButton;
    private TextView warnView;
    private LinearLayout box;
    private boolean askAfterStart;
    private Speaker testSpeaker;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public Object handle(Object host, String event, Object... args) {
        switch (event) {
            case "onCreate":
                a = (Activity) host;
                create();
                break;
            case "onResume":
                resume();
                break;
            case "onPause":
                pause();
                break;
            case "onDestroy":
                destroy();
                break;
            case "onRequestPermissionsResult":
                permissionsResult((Integer) args[0]);
                break;
            default:
                break;
        }
        return null;
    }

    // ------------------------------------------------------------------ Aufbau

    private int dp(int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, boolean bold, int topPad) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        t.setPadding(0, dp(topPad), 0, dp(2));
        box.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private void header(String s) {
        text(s, 18, true, 24);
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        box.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private EditText edit(String hint, String value, boolean password) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        if (Build.VERSION.SDK_INT >= 26) {
            e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        e.setText(value);
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private CheckBox check(String label, boolean checked) {
        CheckBox c = new CheckBox(a);
        c.setText(label);
        c.setChecked(checked);
        box.addView(c, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }

    private void create() {
        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(32));
        scroll.addView(box);
        a.setContentView(scroll);

        statusView = text("Gestoppt", 22, true, 0);
        warnView = text("", 14, true, 4);
        warnView.setTextColor(0xFFC0392B);
        toggleButton = button("Starten", v -> {
            if (Engine.running) {
                Prefs.setEnabled(a, false);
                a.stopService(new Intent(a, VoiceService.class));
                mainHandler.postDelayed(this::render, 300);
            } else {
                askAfterStart = false;
                startAssistant();
            }
        });
        button("Jetzt fragen (ohne „Hey Claude“)", v -> {
            if (Engine.running) {
                a.startService(new Intent(a, VoiceService.class).setAction(Engine.ACTION_ASK));
            } else {
                askAfterStart = true;
                startAssistant();
            }
        });

        text("Letzte Frage", 14, true, 16);
        lastQView = text("–", 15, false, 0);
        lastQView.setTextIsSelectable(true);
        text("Antwort", 14, true, 8);
        lastAView = text("–", 15, false, 0);
        lastAView.setTextIsSelectable(true);

        header("Einstellungen");
        text("Claude API-Schlüssel", 14, false, 8);
        apiKeyEdit = edit("sk-ant-…", Prefs.apiKey(a), true);
        button("API-Schlüssel besorgen", v -> openUrl("https://platform.claude.com/settings/keys"));
        text("Modell (Standard: " + Prefs.DEFAULT_MODEL + ", am schnellsten)", 14, false, 8);
        modelEdit = edit("", Prefs.model(a), false);
        webSearchBox = check("Websuche für aktuelle Infos (Wetter, News …)", Prefs.webSearch(a));
        followUpBox = check("Nach Rückfragen von Claude automatisch weiter zuhören", Prefs.followUp(a));
        locationBox = check("Standort an Claude senden (Wetter, Orte in der Nähe …)", Prefs.location(a));

        header("Stimme");
        text("ElevenLabs API-Schlüssel (für eine echte, natürliche Stimme – leer lassen = Handy-Stimme)", 14, false, 8);
        elevenKeyEdit = edit("sk_…", Prefs.elevenKey(a), true);
        button("ElevenLabs-Schlüssel besorgen", v -> openUrl("https://elevenlabs.io/app/settings/api-keys"));
        voiceNameView = text("Stimme: " + Prefs.elevenVoiceName(a), 15, false, 8);
        button("Stimme auswählen", v -> chooseVoice());
        elevenFastBox = check("Schneller antworten (Flash-Modell, etwas weniger natürlich)", Prefs.elevenFast(a));
        button("Stimme testen", v -> testVoice());
        voiceInfoView = text("", 13, false, 0);

        button("Speichern", v -> {
            savePrefs();
            Toast.makeText(a, "Gespeichert", Toast.LENGTH_SHORT).show();
        });

        header("Erlaubnisse");
        overlayButton = button("Apps & YouTube direkt öffnen erlauben („Über anderen Apps einblenden“)", v -> openOverlaySettings());
        xiaomiButton = button("Xiaomi: „Pop-up-Fenster im Hintergrund anzeigen“ erlauben", v -> {
            try {
                a.startActivity(Launcher.xiaomiPermissionEditor(a));
                Toast.makeText(a, "„Pop-up-Fenster im Hintergrund anzeigen“ einschalten", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                openAppInfo();
            }
        });
        xiaomiButton.setVisibility(Launcher.isXiaomi() ? View.VISIBLE : View.GONE);
        contactsButton = button("Kontakte erlauben (für Nachrichten an Namen)", v ->
                a.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_PERMS + 1));
        accessibilityButton = button("Nachrichten automatisch absenden: Bedienungshilfe einschalten", v -> openAccessibility());
        appInfoButton = button("Bedienungshilfe ausgegraut? App-Info öffnen → ⋮ → „Eingeschränkte Einstellungen zulassen“", v -> openAppInfo());
        button("Lautlos-Modus erlauben (für „Mach das Handy lautlos“)", v -> {
            try {
                a.startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                Toast.makeText(a, "„Hey Claude“ erlauben", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(a, "Bitte in den Einstellungen „Nicht stören“-Zugriff erlauben", Toast.LENGTH_LONG).show();
            }
        });
        button("Akku-Optimierung ausschalten (empfohlen)", v -> requestBatteryExemption());

        header("Updates");
        updateInfoView = text("", 14, false, 4);
        autoUpdateBox = check("Automatisch aktualisieren", Prefs.autoUpdate(a));
        allowUpdatesButton = button("App-Updates erlauben (selten nötig)", v -> openUnknownSources());
        shellButton = button("Neue App-Hülle installieren (für automatisches Senden)", v -> confirmShellInstall());
        button("Jetzt nach Update suchen", v -> checkForUpdate(true));

        text("So geht es: API-Schlüssel eintragen → „Starten“ → App kann geschlossen werden. "
                + "Sag „Hey Claude“, warte auf den Piep und stell deine Frage – oder sag z.B. "
                + "„Spiel auf YouTube Bohemian Rhapsody“, „Öffne Instagram und schreib Emily hey“, „nächstes Lied“, „Musik leiser“, "
                + "„Taschenlampe an“ oder „Handy auf lautlos“. Neue Versionen kommen automatisch, "
                + "ohne dass du etwas installieren musst.", 13, false, 20);

        if (Prefs.apiKey(a).isEmpty()) {
            Toast.makeText(a, "Trag zuerst deinen Claude API-Schlüssel ein", Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ Lebenszyklus

    private void resume() {
        visible = true;
        Engine.statusListener = () -> a.runOnUiThread(this::render);
        render();
        if (Prefs.enabled(a) && !Engine.running
                && a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && !Prefs.apiKey(a).isEmpty()) {
            launchService();
        }
        askOnce();
        // Beim Oeffnen der App kurz nach neuem Code schauen (hoechstens alle 5 Minuten)
        long last = Prefs.get(a).getLong(Prefs.LAST_UPDATE_CHECK, 0);
        if (Prefs.autoUpdate(a) && System.currentTimeMillis() - last > 5 * 60_000L) {
            checkForUpdate(false);
        }
    }

    /** Fragt wichtige Erlaubnisse einmalig von selbst ab. */
    private void askOnce() {
        SharedPreferences sp = Prefs.get(a);
        if (!Messenger.canReadContacts(a) && !sp.getBoolean("asked_contacts", false)) {
            sp.edit().putBoolean("asked_contacts", true).apply();
            a.requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_PERMS + 1);
            return;
        }
        if (!Settings.canDrawOverlays(a) && !sp.getBoolean("asked_overlay", false)) {
            sp.edit().putBoolean("asked_overlay", true).apply();
            new AlertDialog.Builder(a)
                    .setTitle("Apps direkt öffnen")
                    .setMessage("Damit ich YouTube, Instagram, WhatsApp und andere Apps auf Zuruf direkt öffnen kann, "
                            + "braucht Android die Erlaubnis „Über anderen Apps einblenden“. Jetzt einschalten?")
                    .setPositiveButton("Erlauben", (d, w) -> openOverlaySettings())
                    .setNegativeButton("Später", null)
                    .show();
        }
    }

    private void pause() {
        visible = false;
        Engine.statusListener = null;
        savePrefs();
    }

    private void destroy() {
        if (testSpeaker != null) {
            testSpeaker.shutdown();
        }
        bg.shutdownNow();
    }

    private void render() {
        if (a == null || statusView == null) {
            return;
        }
        statusView.setText(Engine.running ? Engine.lastStatus : "Gestoppt");
        toggleButton.setText(Engine.running ? "Stoppen" : "Starten");
        String q = Engine.lastQuestion;
        String ans = Engine.lastAnswer;
        lastQView.setText(q == null || q.isEmpty() ? "–" : q);
        lastAView.setText(ans == null || ans.isEmpty() ? "–" : ans);
        String info = Speaker.lastInfo;
        voiceInfoView.setText(info == null || info.isEmpty() ? "" : "Zuletzt: " + info);
        String us = Updater.lastStatus;
        updateInfoView.setText("App-Hülle " + Updater.currentVersion(a) + " · Programm-Version " + BuildInfo.BUILD
                + (Core.isDownloadedCode() ? " (nachgeladen)" : "")
                + (us == null || us.isEmpty() ? "" : "\n" + us));
        allowUpdatesButton.setVisibility(a.getPackageManager().canRequestPackageInstalls() ? View.GONE : View.VISIBLE);
        boolean overlay = Settings.canDrawOverlays(a);
        boolean contacts = Messenger.canReadContacts(a);
        boolean a11yPossible = Shell.version() >= 2;
        boolean a11y = a11yPossible && Shell.accessibilityEnabled(a);
        boolean newShell = Updater.shellAvailable > Shell.version();
        overlayButton.setVisibility(overlay ? View.GONE : View.VISIBLE);
        contactsButton.setVisibility(contacts ? View.GONE : View.VISIBLE);
        accessibilityButton.setVisibility(a11yPossible && !a11y ? View.VISIBLE : View.GONE);
        appInfoButton.setVisibility(a11yPossible && !a11y ? View.VISIBLE : View.GONE);
        shellButton.setVisibility(newShell ? View.VISIBLE : View.GONE);
        StringBuilder warn = new StringBuilder();
        if (!overlay && !(a11yPossible && a11y)) {
            warn.append("⚠ Apps öffnen sich nur, wenn Hey Claude offen ist – unten „Apps & YouTube direkt öffnen erlauben“ tippen und „Über anderen Apps einblenden“ einschalten.\n");
        }
        if (overlay) {
            Hints.cancel(a, Hints.ID_OVERLAY);
        }
        if (!contacts) {
            warn.append("⚠ Für Nachrichten an Namen: „Kontakte erlauben“.\n");
        }
        if (a11yPossible && !a11y) {
            warn.append("⚠ Nachrichten werden nur vorbereitet – zum automatischen Absenden die Bedienungshilfe einschalten.\n");
        }
        if (newShell) {
            warn.append("ℹ Neue App-Hülle verfügbar (automatisches Absenden) – unten bei „Updates“.\n");
        }
        warnView.setText(warn.toString().trim());
        warnView.setVisibility(warn.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void savePrefs() {
        if (modelEdit == null) {
            return;
        }
        String model = modelEdit.getText().toString().trim();
        SharedPreferences.Editor e = Prefs.get(a).edit();
        e.putString(Prefs.API_KEY, apiKeyEdit.getText().toString().trim());
        e.putString(Prefs.MODEL, model.isEmpty() ? Prefs.DEFAULT_MODEL : model);
        e.putBoolean(Prefs.WEB_SEARCH, webSearchBox.isChecked());
        e.putBoolean(Prefs.FOLLOW_UP, followUpBox.isChecked());
        e.putBoolean(Prefs.LOCATION, locationBox.isChecked());
        e.putString(Prefs.ELEVEN_KEY, elevenKeyEdit.getText().toString().trim());
        e.putBoolean(Prefs.ELEVEN_FAST, elevenFastBox.isChecked());
        e.putBoolean(Prefs.AUTO_UPDATE, autoUpdateBox.isChecked());
        e.apply();
    }

    // ------------------------------------------------------------------ Starten

    private void startAssistant() {
        savePrefs();
        if (Prefs.apiKey(a).isEmpty()) {
            Toast.makeText(a, "Bitte zuerst den API-Schlüssel eintragen", Toast.LENGTH_LONG).show();
            apiKeyEdit.requestFocus();
            return;
        }
        List<String> needed = new ArrayList<>();
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!Messenger.canReadContacts(a)) {
            needed.add(Manifest.permission.READ_CONTACTS);
        }
        if (locationBox.isChecked() && !LocationHelper.hasPermission(a)) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (!needed.isEmpty()) {
            a.requestPermissions(needed.toArray(new String[0]), REQ_PERMS);
            return;
        }
        launchService();
    }

    private void launchService() {
        Intent i = new Intent(a, VoiceService.class);
        if (askAfterStart) {
            i.setAction(Engine.ACTION_ASK);
            askAfterStart = false;
        }
        try {
            a.startForegroundService(i);
        } catch (Exception e) {
            Toast.makeText(a, "Start fehlgeschlagen: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        mainHandler.postDelayed(this::render, 300);
    }

    private void permissionsResult(int requestCode) {
        render();
        if (requestCode != REQ_PERMS) {
            if (requestCode == REQ_PERMS + 1) {
                askOnce(); // danach ggf. die naechste Erlaubnis
            }
            return;
        }
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            launchService();
        } else {
            Toast.makeText(a, "Ohne Mikrofon-Berechtigung kann ich nicht zuhören.", Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ Stimme

    private void testVoice() {
        savePrefs();
        if (testSpeaker == null) {
            testSpeaker = new Speaker(a, mainHandler, bg);
        }
        voiceInfoView.setText(Prefs.elevenKey(a).isEmpty() ? "Handy-Stimme …" : "Erzeuge ElevenLabs-Stimme …");
        testSpeaker.speak("Hallo! Ich bin Claude. So klinge ich, wenn ich dir antworte.", this::render);
        mainHandler.postDelayed(this::render, 4000);
    }

    private void chooseVoice() {
        savePrefs();
        final String key = Prefs.elevenKey(a);
        if (key.isEmpty()) {
            Toast.makeText(a, "Bitte zuerst den ElevenLabs-Schlüssel eintragen", Toast.LENGTH_LONG).show();
            elevenKeyEdit.requestFocus();
            return;
        }
        Toast.makeText(a, "Lade Stimmen …", Toast.LENGTH_SHORT).show();
        bg.execute(() -> {
            try {
                final List<String[]> voices = ElevenLabs.listVoices(key);
                a.runOnUiThread(() -> showVoiceDialog(voices));
            } catch (ElevenLabs.ApiException e) {
                final String msg = ElevenLabs.explain(e);
                a.runOnUiThread(() -> Toast.makeText(a, msg, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                a.runOnUiThread(() -> Toast.makeText(a, "Stimmen konnten nicht geladen werden", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showVoiceDialog(final List<String[]> voices) {
        if (a.isFinishing()) {
            return;
        }
        if (voices.isEmpty()) {
            Toast.makeText(a, "Keine Stimmen im Konto gefunden", Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[voices.size()];
        for (int i = 0; i < voices.size(); i++) {
            labels[i] = voices.get(i)[2];
        }
        new AlertDialog.Builder(a)
                .setTitle("Stimme wählen")
                .setItems(labels, (dialog, which) -> {
                    String[] v = voices.get(which);
                    Prefs.get(a).edit()
                            .putString(Prefs.ELEVEN_VOICE_ID, v[0])
                            .putString(Prefs.ELEVEN_VOICE_NAME, v[1])
                            .apply();
                    voiceNameView.setText("Stimme: " + v[1]);
                    testVoice();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    // ------------------------------------------------------------------ Updates & Erlaubnisse

    private void checkForUpdate(final boolean userInitiated) {
        savePrefs();
        if (userInitiated) {
            updateInfoView.setText("Suche nach Update …");
        }
        final android.content.Context app = a.getApplicationContext();
        bg.execute(() -> {
            final Updater.Update u = Updater.check(app, userInitiated);
            a.runOnUiThread(this::render);
            if (u == null) {
                return;
            }
            if (u.shell && !app.getPackageManager().canRequestPackageInstalls()) {
                a.runOnUiThread(() -> {
                    Toast.makeText(a, "Für dieses Update bitte einmal „App-Updates erlauben“", Toast.LENGTH_LONG).show();
                    openUnknownSources();
                });
                return;
            }
            if (Engine.idle || !Engine.running) {
                Updater.apply(app, u); // nie mitten in einem Gespraech
            }
            a.runOnUiThread(this::render);
        });
    }

    private void openAccessibility() {
        try {
            a.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(a, "„Hey Claude“ suchen und einschalten", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(a, "Einstellungen → Bedienungshilfen → Hey Claude", Toast.LENGTH_LONG).show();
        }
    }

    private void openAppInfo() {
        try {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.getPackageName())));
            Toast.makeText(a, "Oben rechts ⋮ → „Eingeschränkte Einstellungen zulassen“", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(a, "Einstellungen → Apps → Hey Claude", Toast.LENGTH_LONG).show();
        }
    }

    /** Neue Huelle: einmalige Installation, Play Protect muss dafuer kurz aus sein. */
    private void confirmShellInstall() {
        new AlertDialog.Builder(a)
                .setTitle("Neue App-Hülle installieren")
                .setMessage("Damit ich Nachrichten selbst absenden kann, brauche ich die Bedienungshilfe. "
                        + "Google Play Protect blockiert solche Apps außerhalb des Play Store. So geht es:\n\n"
                        + "1. Play Store → Profilbild → Play Protect → Zahnrad → „Apps mit Play Protect scannen“ aus\n"
                        + "2. Hier „Installieren“ tippen und bestätigen\n"
                        + "3. Play Protect wieder einschalten\n"
                        + "4. In der App „Bedienungshilfe einschalten“ tippen")
                .setPositiveButton("Installieren", (d, w) -> installShell())
                .setNeutralButton("Play Store öffnen", (d, w) -> {
                    Intent i = a.getPackageManager().getLaunchIntentForPackage("com.android.vending");
                    if (i != null) {
                        a.startActivity(i);
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void installShell() {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            openUnknownSources();
            return;
        }
        updateInfoView.setText("Lade neue App-Hülle …");
        final android.content.Context app = a.getApplicationContext();
        bg.execute(() -> {
            Updater.Update u = Updater.check(app, true, true);
            if (u != null && u.shell) {
                Updater.apply(app, u);
            }
            a.runOnUiThread(this::render);
        });
    }

    private void openUnknownSources() {
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
            Toast.makeText(a, "„Aus dieser Quelle zulassen“ einschalten", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(a, "Bitte „Unbekannte Apps installieren“ für Hey Claude erlauben", Toast.LENGTH_LONG).show();
        }
    }

    private void openOverlaySettings() {
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + a.getPackageName())));
            Toast.makeText(a, "„Über anderen Apps einblenden“ für Hey Claude einschalten", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(a, "Bitte in den Einstellungen „Über anderen Apps einblenden“ erlauben", Toast.LENGTH_LONG).show();
        }
    }

    private void requestBatteryExemption() {
        PowerManager pm = a.getSystemService(PowerManager.class);
        if (pm != null && pm.isIgnoringBatteryOptimizations(a.getPackageName())) {
            Toast.makeText(a, "Akku-Optimierung ist schon aus 👍", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(a, "Bitte in den Einstellungen manuell erlauben", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openUrl(String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(a, url, Toast.LENGTH_LONG).show();
        }
    }
}
