package de.heyclaude.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 7;

    static volatile boolean visible;

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
    private boolean askAfterStart;
    private Speaker testSpeaker;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        lastQView = findViewById(R.id.lastQ);
        lastAView = findViewById(R.id.lastA);
        toggleButton = findViewById(R.id.toggle);
        apiKeyEdit = findViewById(R.id.apiKey);
        modelEdit = findViewById(R.id.model);
        webSearchBox = findViewById(R.id.webSearch);
        followUpBox = findViewById(R.id.followUp);
        locationBox = findViewById(R.id.location);
        elevenKeyEdit = findViewById(R.id.elevenKey);
        voiceNameView = findViewById(R.id.voiceName);
        voiceInfoView = findViewById(R.id.voiceInfo);
        elevenFastBox = findViewById(R.id.elevenFast);
        autoUpdateBox = findViewById(R.id.autoUpdate);
        updateInfoView = findViewById(R.id.updateInfo);
        allowUpdatesButton = findViewById(R.id.allowUpdates);
        autoUpdateBox.setChecked(Prefs.autoUpdate(this));
        allowUpdatesButton.setOnClickListener(v -> openUnknownSources());
        Button checkUpdate = findViewById(R.id.checkUpdate);
        checkUpdate.setOnClickListener(v -> checkForUpdate());

        apiKeyEdit.setText(Prefs.apiKey(this));
        modelEdit.setText(Prefs.model(this));
        webSearchBox.setChecked(Prefs.webSearch(this));
        followUpBox.setChecked(Prefs.followUp(this));
        locationBox.setChecked(Prefs.location(this));
        elevenKeyEdit.setText(Prefs.elevenKey(this));
        elevenFastBox.setChecked(Prefs.elevenFast(this));
        voiceNameView.setText("Stimme: " + Prefs.elevenVoiceName(this));

        Button getElevenKey = findViewById(R.id.getElevenKey);
        getElevenKey.setOnClickListener(v -> openUrl("https://elevenlabs.io/app/settings/api-keys"));

        Button chooseVoice = findViewById(R.id.chooseVoice);
        chooseVoice.setOnClickListener(v -> chooseVoice());

        Button testVoice = findViewById(R.id.testVoice);
        testVoice.setOnClickListener(v -> testVoice());

        toggleButton.setOnClickListener(v -> {
            if (VoiceService.running) {
                Prefs.setEnabled(this, false);
                stopService(new Intent(this, VoiceService.class));
                mainHandler.postDelayed(this::render, 300);
            } else {
                askAfterStart = false;
                startAssistant();
            }
        });

        Button ask = findViewById(R.id.ask);
        ask.setOnClickListener(v -> {
            if (VoiceService.running) {
                startService(new Intent(this, VoiceService.class).setAction(VoiceService.ACTION_ASK));
            } else {
                askAfterStart = true;
                startAssistant();
            }
        });

        Button save = findViewById(R.id.save);
        save.setOnClickListener(v -> {
            savePrefs();
            Toast.makeText(this, "Gespeichert", Toast.LENGTH_SHORT).show();
        });

        Button getKey = findViewById(R.id.getKey);
        getKey.setOnClickListener(v -> openUrl("https://platform.claude.com/settings/keys"));

        Button dnd = findViewById(R.id.dndAccess);
        dnd.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                Toast.makeText(this, "„Hey Claude“ erlauben", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Bitte in den Einstellungen „Nicht stören“-Zugriff erlauben", Toast.LENGTH_LONG).show();
            }
        });

        Button battery = findViewById(R.id.battery);
        battery.setOnClickListener(v -> requestBatteryExemption());

        if (Prefs.apiKey(this).isEmpty()) {
            Toast.makeText(this, "Trag zuerst deinen Claude API-Schlüssel ein", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        VoiceService.statusListener = () -> runOnUiThread(this::render);
        render();
        // War der Assistent an (z.B. nach Update oder Handy-Neustart)? Dann automatisch weitermachen.
        if (Prefs.enabled(this) && !VoiceService.running
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && !Prefs.apiKey(this).isEmpty()) {
            launchService();
        }
    }

    @Override
    protected void onPause() {
        visible = false;
        VoiceService.statusListener = null;
        savePrefs();
        super.onPause();
    }

    private void render() {
        statusView.setText(VoiceService.running ? VoiceService.lastStatus : "Gestoppt");
        toggleButton.setText(VoiceService.running ? "Stoppen" : "Starten");
        String q = VoiceService.lastQuestion;
        String a = VoiceService.lastAnswer;
        lastQView.setText(q == null || q.isEmpty() ? "–" : q);
        lastAView.setText(a == null || a.isEmpty() ? "–" : a);
        String info = Speaker.lastInfo;
        voiceInfoView.setText(info == null || info.isEmpty() ? "" : "Zuletzt: " + info);
        String us = Updater.lastStatus;
        updateInfoView.setText("Version " + Updater.currentVersion(this)
                + (us == null || us.isEmpty() ? "" : " · " + us));
        allowUpdatesButton.setVisibility(getPackageManager().canRequestPackageInstalls() ? View.GONE : View.VISIBLE);
    }

    private void openUnknownSources() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "„Aus dieser Quelle zulassen“ einschalten", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Bitte in den Einstellungen „Unbekannte Apps installieren“ für Hey Claude erlauben", Toast.LENGTH_LONG).show();
        }
    }

    private void checkForUpdate() {
        savePrefs();
        if (!getPackageManager().canRequestPackageInstalls()) {
            openUnknownSources();
            return;
        }
        updateInfoView.setText("Suche nach Update …");
        final android.content.Context app = getApplicationContext();
        bg.execute(() -> {
            final java.io.File apk = Updater.download(app, true);
            runOnUiThread(this::render);
            if (apk != null) {
                Updater.install(app, apk);
                runOnUiThread(this::render);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (testSpeaker != null) {
            testSpeaker.shutdown();
        }
        bg.shutdownNow();
        super.onDestroy();
    }

    private void testVoice() {
        savePrefs();
        if (testSpeaker == null) {
            testSpeaker = new Speaker(this, mainHandler, bg);
        }
        voiceInfoView.setText(Prefs.elevenKey(this).isEmpty() ? "Handy-Stimme …" : "Erzeuge ElevenLabs-Stimme …");
        testSpeaker.speak("Hallo! Ich bin Claude. So klinge ich, wenn ich dir antworte.", this::render);
        mainHandler.postDelayed(this::render, 4000);
    }

    private void chooseVoice() {
        savePrefs();
        final String key = Prefs.elevenKey(this);
        if (key.isEmpty()) {
            Toast.makeText(this, "Bitte zuerst den ElevenLabs-Schlüssel eintragen", Toast.LENGTH_LONG).show();
            elevenKeyEdit.requestFocus();
            return;
        }
        Toast.makeText(this, "Lade Stimmen …", Toast.LENGTH_SHORT).show();
        bg.execute(() -> {
            try {
                final List<String[]> voices = ElevenLabs.listVoices(key);
                runOnUiThread(() -> showVoiceDialog(voices));
            } catch (ElevenLabs.ApiException e) {
                final String msg = ElevenLabs.explain(e);
                runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Stimmen konnten nicht geladen werden", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showVoiceDialog(final List<String[]> voices) {
        if (isFinishing()) {
            return;
        }
        if (voices.isEmpty()) {
            Toast.makeText(this, "Keine Stimmen im Konto gefunden", Toast.LENGTH_LONG).show();
            return;
        }
        String[] labels = new String[voices.size()];
        for (int i = 0; i < voices.size(); i++) {
            labels[i] = voices.get(i)[2];
        }
        new AlertDialog.Builder(this)
                .setTitle("Stimme wählen")
                .setItems(labels, (dialog, which) -> {
                    String[] v = voices.get(which);
                    Prefs.get(this).edit()
                            .putString(Prefs.ELEVEN_VOICE_ID, v[0])
                            .putString(Prefs.ELEVEN_VOICE_NAME, v[1])
                            .apply();
                    voiceNameView.setText("Stimme: " + v[1]);
                    testVoice();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void savePrefs() {
        String model = modelEdit.getText().toString().trim();
        SharedPreferences.Editor e = Prefs.get(this).edit();
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

    private void startAssistant() {
        savePrefs();
        if (Prefs.apiKey(this).isEmpty()) {
            Toast.makeText(this, "Bitte zuerst den API-Schlüssel eintragen", Toast.LENGTH_LONG).show();
            apiKeyEdit.requestFocus();
            return;
        }
        List<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (locationBox.isChecked() && !LocationHelper.hasPermission(this)) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_PERMS);
            return;
        }
        launchService();
    }

    private void launchService() {
        Intent i = new Intent(this, VoiceService.class);
        if (askAfterStart) {
            i.setAction(VoiceService.ACTION_ASK);
            askAfterStart = false;
        }
        try {
            startForegroundService(i);
        } catch (Exception e) {
            Toast.makeText(this, "Start fehlgeschlagen: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        statusView.postDelayed(this::render, 300);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            launchService();
        } else {
            Toast.makeText(this, "Ohne Mikrofon-Berechtigung kann ich nicht zuhören.", Toast.LENGTH_LONG).show();
        }
    }

    private void requestBatteryExemption() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "Akku-Optimierung ist schon aus 👍", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "Bitte in den Einstellungen manuell erlauben", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }
}
