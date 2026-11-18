package de.heyclaude.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Durchsichtiger Bildschirm fuer spaetere Funktionen. Welche Klasse aus dem Programmcode ihn
 * steuert, steht im Extra "screen". Gibt es sie (noch) nicht, schliesst er sich sofort.
 */
public class CoreActivity extends Activity {

    private Component impl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String name = getIntent() != null ? getIntent().getStringExtra("screen") : null;
        impl = name != null ? Core.createOptional(this, name) : null;
        if (impl == null) {
            finish();
            return;
        }
        call("onCreate", savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        call("onResume");
    }

    @Override
    protected void onPause() {
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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        call("onActivityResult", requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        call("onRequestPermissionsResult", requestCode, permissions, grantResults);
    }

    private void call(String event, Object... args) {
        if (impl == null) {
            return;
        }
        try {
            impl.handle(this, event, args);
        } catch (Throwable t) {
            Log.e("HeyClaude", "CoreActivity " + event, t);
            impl = null;
            finish();
        }
    }
}
