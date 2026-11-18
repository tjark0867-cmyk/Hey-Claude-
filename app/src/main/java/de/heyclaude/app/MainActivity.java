package de.heyclaude.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

/** Huelle fuer den App-Bildschirm. Oberflaeche und Logik kommen aus dem nachladbaren Code (Screen). */
public class MainActivity extends Activity {

    private Component impl;
    private int generation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        generation = Core.generation();
        impl = Core.create(this, "Screen");
        if (impl == null) {
            showError();
            return;
        }
        call("onCreate", savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Core.setVisibleActivity(this);
        if (generation != Core.generation()) {
            recreate(); // neuer Code ist aktiv
            return;
        }
        call("onResume");
    }

    @Override
    protected void onPause() {
        Core.setVisibleActivity(null);
        call("onPause");
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        call("onDestroy");
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        call("onNewIntent", intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        call("onRequestPermissionsResult", requestCode, permissions, grantResults);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        call("onActivityResult", requestCode, resultCode, data);
    }

    private void call(String event, Object... args) {
        Component c = impl;
        if (c == null) {
            return;
        }
        try {
            c.handle(this, event, args);
        } catch (Throwable t) {
            Log.e("HeyClaude", "Screen-Fehler bei " + event, t);
            impl = null;
            if (Core.fallBackToBundled()) {
                recreate();
            } else {
                showError();
            }
        }
    }

    private void showError() {
        TextView tv = new TextView(this);
        tv.setPadding(48, 48, 48, 48);
        tv.setText("Hey Claude konnte nicht geladen werden. Bitte die App schließen und neu öffnen.");
        setContentView(tv);
    }
}
