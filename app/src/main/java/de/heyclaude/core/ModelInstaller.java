package de.heyclaude.core;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Kopiert das Offline-Wake-Word-Modell (Vosk) beim ersten Start aus der APK in den App-Speicher.
 */
final class ModelInstaller {

    static final String ASSET_DIR = "model-en";
    private static final String MARKER = "installed.ok";

    private ModelInstaller() {
    }

    static String install(Context ctx) throws IOException {
        AssetManager am = ctx.getAssets();
        String[] top = am.list(ASSET_DIR);
        if (top == null || top.length == 0) {
            throw new FileNotFoundException("Wake-Word-Modell fehlt in der APK");
        }
        File target = new File(ctx.getFilesDir(), "vosk-model");
        File marker = new File(target, MARKER);
        long apkTime;
        try {
            apkTime = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            apkTime = 0;
        }
        if (marker.exists() && marker.lastModified() >= apkTime) {
            return target.getAbsolutePath();
        }
        deleteRecursive(target);
        copyAssetDir(am, ASSET_DIR, target);
        if (!marker.createNewFile() && !marker.exists()) {
            throw new IOException("Konnte Modell nicht speichern");
        }
        return target.getAbsolutePath();
    }

    private static void copyAssetDir(AssetManager am, String assetPath, File out) throws IOException {
        String[] children = am.list(assetPath);
        if (children == null || children.length == 0) {
            copyFile(am, assetPath, out);
            return;
        }
        if (!out.isDirectory() && !out.mkdirs()) {
            throw new IOException("Ordner konnte nicht angelegt werden: " + out);
        }
        for (String child : children) {
            copyAssetDir(am, assetPath + "/" + child, new File(out, child));
        }
    }

    private static void copyFile(AssetManager am, String assetPath, File out) throws IOException {
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        InputStream in = am.open(assetPath);
        try {
            OutputStream os = new FileOutputStream(out);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) {
                    os.write(buf, 0, n);
                }
            } finally {
                os.close();
            }
        } finally {
            in.close();
        }
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursive(k);
                }
            }
        }
        if (f.exists()) {
            f.delete();
        }
    }
}
