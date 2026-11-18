package de.heyclaude.app;

import dalvik.system.DexClassLoader;

/**
 * Laedt Klassen aus "de.heyclaude.core" bevorzugt aus der heruntergeladenen Code-Datei
 * (child-first), alles andere wie gewohnt aus der installierten App.
 */
final class CoreClassLoader extends DexClassLoader {

    private static final String CORE_PKG = "de.heyclaude.core.";

    CoreClassLoader(String dexPath, String libraryPath, ClassLoader parent) {
        super(dexPath, null, libraryPath, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith(CORE_PKG)) {
            synchronized (this) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException e) {
                        c = super.loadClass(name, resolve);
                    }
                }
                return c;
            }
        }
        return super.loadClass(name, resolve);
    }
}
