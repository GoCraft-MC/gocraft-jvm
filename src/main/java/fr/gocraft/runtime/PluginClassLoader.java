package fr.gocraft.runtime;

import java.net.URL;
import java.net.URLClassLoader;

/// One classloader per plugin: child-first, with a fixed set of exceptions.
///
/// Child-first is what lets two plugins each use their own version of the same
/// library. Without it the first one loaded wins and the second silently gets a
/// version it was not compiled against.
///
/// The exceptions are what make the whole thing work, and getting them wrong
/// produces the failure §13 warns about: **if a shared API is not shared, the
/// class the runtime instantiates is not the class the plugin sees.** Both are
/// named `fr.gocraft.api.Plugin`, both look right in a debugger, and the cast
/// fails with a `ClassCastException` naming the same type twice. It is one of
/// the least legible errors in Java, so the delegation rule is spelled out here
/// rather than left implicit.
///
/// Three things must come from the parent, always:
///
///   - `java.*` and `jdk.*`, because the platform is not the plugin's to
///     replace and a duplicated `java.lang.String` would not link at all;
///   - `fr.gocraft.api.*`, the contract this runtime and the plugin share;
///   - anything another plugin published as a shared event or service API,
///     which is the same argument applied to plugin-to-plugin contracts.
///
/// Everything else is the plugin's own, and only falls back to the parent when
/// the plugin does not have it — a library shipped in `payload/lib` wins over
/// whatever the runtime happens to carry.
final class PluginClassLoader extends URLClassLoader {

    /// The package a plugin implements against. Loaded by the parent so the
    /// `Plugin` the runtime casts to is the `Plugin` the plugin implements.
    private static final String API_PACKAGE = "fr.gocraft.api.";

    static {
        // Two plugins can be loaded concurrently, and loadClass below locks per
        // class name rather than on the loader. Without this registration that
        // locking is not sound.
        registerAsParallelCapable();
    }

    private final String pluginId;

    PluginClassLoader(String pluginId, URL[] classpath, ClassLoader parent) {
        super("plugin-" + pluginId, classpath, parent);
        this.pluginId = pluginId;
    }

    String pluginId() {
        return pluginId;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> found = findLoadedClass(name);
            if (found == null) {
                found = fromParentIfShared(name);
            }
            if (found == null) {
                found = childFirst(name);
            }
            if (resolve) {
                resolveClass(found);
            }
            return found;
        }
    }

    private Class<?> fromParentIfShared(String name) throws ClassNotFoundException {
        if (isShared(name)) {
            return getParent().loadClass(name);
        }
        return null;
    }

    private Class<?> childFirst(String name) throws ClassNotFoundException {
        try {
            return findClass(name);
        } catch (ClassNotFoundException notOurs) {
            // Not in this plugin's own jars, so it is either the runtime's or
            // nobody's. The parent decides which.
            return getParent().loadClass(name);
        }
    }

    static boolean isShared(String name) {
        return name.startsWith("java.")
                || name.startsWith("jdk.")
                || name.startsWith("javax.")
                || name.startsWith(API_PACKAGE);
    }
}