package de.heyclaude.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 7;

    private TextView statusView;
    private TextView lastQView;
    private TextView lastAView;
    private Button toggleButton;
    private EditText apiKeyEdit;
    private EditText modelEdit;
    private CheckBox webSearchBox;
    private CheckBox followUpBox;
    private boolean askAfterStart;

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

        apiKeyEdit.setText(Prefs.apiKey(this));
        modelEdit.setText(Prefs.model(this));
        webSearchBox.setChecked(Prefs.webSearch(this));
        followUpBox.setChecked(Prefs.followUp(this));

        toggleButton.setOnClickListener(v -> {
            if (VoiceService.running) {
                stopService(new Intent(this, VoiceService.class));
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

        Button battery = findViewById(R.id.battery);
        battery.setOnClickListener(v -> requestBatteryExemption());

        if (Prefs.apiKey(this).isEmpty()) {
            Toast.makeText(this, "Trag zuerst deinen Claude API-Schlüssel ein", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        VoiceService.statusListener = () -> runOnUiThread(this::render);
        render();
    }

    @Override
    protected void onPause() {
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
    }

    private void savePrefs() {
        String model = modelEdit.getText().toString().trim();
        SharedPreferences.Editor e = Prefs.get(this).edit();
        e.putString(Prefs.API_KEY, apiKeyEdit.getText().toString().trim());
        e.putString(Prefs.MODEL, model.isEmpty() ? Prefs.DEFAULT_MODEL : model);
        e.putBoolean(Prefs.WEB_SEARCH, webSearchBox.isChecked());
        e.putBoolean(Prefs.FOLLOW_UP, followUpBox.isChecked());
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
