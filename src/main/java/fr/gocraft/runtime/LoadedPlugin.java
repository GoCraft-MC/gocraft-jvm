package fr.gocraft.runtime;

import fr.gocraft.api.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/// One loaded plugin, and everything that has to be released to unload it.
///
/// The fields are the point. §13 names the mistake that costs days: on unload
/// you must close the classloader **and purge every reference to it** —
/// handlers, MethodHandles, command invokers, service handles, scheduler tasks.
/// A single retained reference keeps the loader alive, and with it every class
/// the plugin defined. That is Bukkit's classic `/reload` leak, and the reason
/// this type exists at all rather than a pair of maps somewhere.
///
/// Anything that gains a reference to plugin code later belongs here, released
/// in [#close()], and nowhere else.
final class LoadedPlugin implements AutoCloseable {

    private final String id;
    private final PluginClassLoader loader;
    private final Path extracted;
    private Plugin instance;

    LoadedPlugin(String id, PluginClassLoader loader, Path extracted, Plugin instance) {
        this.id = id;
        this.loader = loader;
        this.extracted = extracted;
        this.instance = instance;
    }

    String id() {
        return id;
    }

    Plugin instance() {
        return instance;
    }

    ClassLoader loader() {
        return loader;
    }

    /// Stops the plugin and releases everything it holds.
    ///
    /// `disable()` runs first and its failure does not stop the rest: a plugin
    /// that throws on the way out must not keep its classloader alive, or a
    /// single bad unload leaks for the lifetime of the server.
    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            instance.disable();
        } catch (RuntimeException | Error thrown) {
            failure = new IOException("plugin " + id + " threw from disable(): " + thrown, thrown);
        }

        // Dropped before the loader is closed, because this is the reference
        // that would retain it.
        instance = null;

        try {
            loader.close();
        } catch (IOException closing) {
            failure = merge(failure, closing);
        }
        try {
            deleteRecursively(extracted);
        } catch (IOException cleaning) {
            failure = merge(failure, cleaning);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static IOException merge(IOException first, IOException second) {
        if (first == null) {
            return second;
        }
        first.addSuppressed(second);
        return first;
    }

    /// Removes the directory the bundle's payload was unpacked into.
    ///
    /// Deepest first, because a directory cannot be removed while it still has
    /// entries. On Windows this can fail while a jar is still mapped, which is
    /// why it runs after the loader is closed rather than before.
    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}